package com.hubmobile.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 给外部应用（系统安装器、文件查看器）提供**公共下载目录**里文件的临时读权限。
 *
 * 为什么需要：Android 7.0 起禁止把 file:// 直接抛给外部应用，必须走 content://。
 * 下载的 APK 躺在 Download/githup/ 里 —— 之前直接拿 DownloadManager 给的
 * file:// 去拉安装器，新系统上会被静默拦掉，表现就是「下载完成了却装不了」。
 *
 * 与 ApkProvider 同样的思路：不引 androidx 的 FileProvider，几十行自足。
 * 只暴露 Download/githup/ 这一层，路径只取 URI 最后一段且禁止分隔符与
 * 「..」，不存在路径穿越风险。
 */
public class DownloadProvider extends ContentProvider {

    /** 与 AndroidManifest 中 authorities 的后缀保持一致 */
    static final String AUTHORITY_SUFFIX = ".downloads";

    static Uri uriFor(String pkg, String fileName) {
        return Uri.parse("content://" + pkg + AUTHORITY_SUFFIX + "/" + Uri.encode(fileName));
    }

    /** 文件在公共下载目录里的实际位置 */
    static File fileFor(String fileName) {
        return new File(new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), JsBridge.DOWNLOAD_SUBDIR), fileName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("bad name");
        }
        if (name.contains("..")) throw new FileNotFoundException("bad name");
        File f = fileFor(name);
        if (!f.exists() || !f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return mimeFor(uri.getLastPathSegment());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /** 按扩展名猜 MIME，给「打开文件」用 */
    static String mimeFor(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".tgz") || n.endsWith(".gz")) return "application/gzip";
        if (n.endsWith(".tar")) return "application/x-tar";
        if (n.endsWith(".jar")) return "application/java-archive";
        return "application/octet-stream";
    }
}
