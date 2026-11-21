package com.hubmobile.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 阶段 AJ19 —— 任意下载目录（SAF）。
 *
 * 为什么以前只能存 Download/：Android 10（API 29）起是分区存储，App 不能再用
 * {@code Environment.getExternalStorageDirectory() + "/任意路径"} 直接写公共存储。
 * 公共目录只有两条合法通道：DownloadManager / MediaStore 的 Downloads 集合，
 * 或者由用户在系统文件选择器里授权一棵目录树（Storage Access Framework）。
 * 旧版只开放了「Download 下的文件夹名」，因为换道续传、SHA 校验、
 * 历史「打开/删除」全都依赖一个确定的相对路径，改成 tree Uri 会让整条链路断掉。
 *
 * 这版保留那条链路作为**暂存**：下载引擎（DownloadManager / 限速 / 直取）
 * 照旧把字节落到 Download/githup/，完成后统一由 {@link #publishStream} 发布到
 * 用户授权的目录，再清掉系统下载记录。于是：
 *   · 引擎、续传、换道、校验一字节未动，ROM 兼容性不变；
 *   · 成品最终只出现在用户选的目录，Download/ 里不留副本；
 *   · 历史里改存 SAF document Uri，「打开 / 删除」由 ContentResolver 直接寻址。
 *
 * 全部用 platform 的 DocumentsContract，不引入 androidx.documentfile（本工程
 * dependencies 为空）。权限是 persistable 的 READ|WRITE，跨重启仍在；
 * 用户点「恢复默认」会 release 掉。
 */
public final class DlDir {

    private static final String PREF = "hub_prefs";
    private static final String KEY_URI = "dl_tree_uri";
    private static final String KEY_LABEL = "dl_tree_label";

    /** 系统文件选择器请求码（与 FilePick 的 4711 / 4713 错开） */
    public static final int REQ_PICK = 4715;

    private DlDir() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Uri get(Context c) {
        String s = "";
        try { s = prefs(c).getString(KEY_URI, ""); } catch (Throwable ignored) { }
        if (s == null || s.isEmpty()) return null;
        try { return Uri.parse(s); } catch (Throwable ignored) { return null; }
    }

    public static boolean isCustom(Context c) { return get(c) != null; }

    public static String label(Context c) {
        String s = "";
        try { s = prefs(c).getString(KEY_LABEL, ""); } catch (Throwable ignored) { }
        if (s != null && !s.isEmpty()) return s;
        Uri u = get(c);
        return u == null ? ("Download/" + JsBridge.DOWNLOAD_SUBDIR) : describe(u);
    }

    /** 把 "primary:Download/我的目录" 变成看得懂的位置文字 */
    public static String describe(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            if (id == null) return "已选目录";
            if (id.startsWith("primary:")) return "内部存储/" + id.substring(8);
            int c = id.indexOf(':');
            if (c > 0) return "存储卷/" + id.substring(c + 1);
            return id;
        } catch (Throwable t) {
            return "已选目录";
        }
    }

    public static void save(Context c, Uri tree) {
        if (tree == null) { clear(c); return; }
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                c.getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (Throwable ignored) { }
            prefs(c).edit()
                    .putString(KEY_URI, tree.toString())
                    .putString(KEY_LABEL, describe(tree))
                    .apply();
        } catch (Throwable ignored) { }
    }

    public static void clear(Context c) {
        Uri old = get(c);
        try {
            if (old != null) {
                c.getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (Throwable ignored) { }
        try { prefs(c).edit().remove(KEY_URI).remove(KEY_LABEL).apply(); }
        catch (Throwable ignored) { }
    }

    /** tree 下按名字找一个直接子文档（不递归），返回 documentUri；找不到 null */
    private static Uri child(Context c, String parentDocId, String name) {
        Uri tree = get(c);
        if (tree == null || name == null || name.isEmpty()) return null;
        Cursor cur = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId);
            cur = c.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String n = cur.getString(1);
                if (n != null && n.equals(name)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) { try { cur.close(); } catch (Throwable ignored) { } }
        }
        return null;
    }

    /** 目标子目录的 documentUri（按分类分目录，沿用 Download/githup/release 的口径） */
    private static Uri subDir(Context c, String sub) {
        Uri tree = get(c);
        if (tree == null) return null;
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        if (sub == null || sub.isEmpty()) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        }
        String cat = JsBridge.safeCategory(sub);
        if (cat.isEmpty()) return DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        Uri found = child(c, rootId, cat);
        if (found != null) return found;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            return DocumentsContract.createDocument(c.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, cat);
        } catch (Throwable ignored) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        }
    }

    /** 按名字找已发布的文件 */
    public static Uri find(Context c, String name, String sub) {
        Uri dir = subDir(c, sub);
        if (dir == null) return null;
        try {
            String docId = DocumentsContract.getDocumentId(dir);
            return child(c, docId, JsBridge.safeName(name));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean exists(Context c, String name, String sub) {
        return find(c, name, sub) != null;
    }

    public static boolean delete(Context c, String name, String sub) {
        Uri u = find(c, name, sub);
        if (u == null) return false;
        try { return DocumentsContract.deleteDocument(c.getContentResolver(), u); }
        catch (Throwable ignored) { return false; }
    }

    /** 给 DownloadProvider 用：按名字返回可读句柄 */
    public static ParcelFileDescriptor openPfd(Context c, String name, String sub) {
        Uri u = find(c, name, sub);
        if (u == null) return null;
        try { return c.getContentResolver().openFileDescriptor(u, "r"); }
        catch (Throwable ignored) { return null; }
    }

    /** 在目标目录里建（或覆盖）一个文件并返回它的 documentUri */
    private static Uri create(Context c, String name, String sub, String mime) {
        Uri dir = subDir(c, sub);
        if (dir == null) return null;
        String safe = JsBridge.safeName(name);
        try {
            String dirId = DocumentsContract.getDocumentId(dir);
            Uri old = child(c, dirId, safe);
            if (old != null) {
                try { DocumentsContract.deleteDocument(c.getContentResolver(), old); }
                catch (Throwable ignored) { }
            }
            return DocumentsContract.createDocument(c.getContentResolver(), dir, mime, safe);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".yml") || n.endsWith(".yaml")) return "text/plain";
        return "application/octet-stream";
    }

    /**
     * 把一段流发布到用户目录。成功返回 documentUri 字符串，失败返回 ""。
     * 不关闭传入的流（由调用方负责）。
     */
    public static String publishStream(Context c, InputStream in, String name,
                                       String sub, String[] uriOut) {
        if (in == null) return "";
        Uri doc = create(c, name, sub, mimeOf(name));
        if (doc == null) return "";
        OutputStream out = null;
        try {
            out = c.getContentResolver().openOutputStream(doc);
            if (out == null) throw new java.io.IOException("openOutputStream 返回 null");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            if (uriOut != null && uriOut.length > 0) uriOut[0] = doc.toString();
            return doc.toString();
        } catch (Throwable t) {
            try { DocumentsContract.deleteDocument(c.getContentResolver(), doc); }
            catch (Throwable ignored) { }
            return "";
        } finally {
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    /** 把私有目录里的成品发布过去；成功后删掉源文件。 */
    public static boolean publishFile(Context c, File src, String name, String sub) {
        if (src == null || !src.exists()) return false;
        InputStream in = null;
        try {
            in = new FileInputStream(src);
            String u = publishStream(c, in, name, sub, null);
            if (u.isEmpty()) return false;
            try { src.delete(); } catch (Throwable ignored) { }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }
}
