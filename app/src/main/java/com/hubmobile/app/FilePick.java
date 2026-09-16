package com.hubmobile.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 文件选择结果的处理辅助：查询文件名 / 大小 / MIME，读取全部字节。
 * 用于「上传文件到仓库」与「上传 Release 附件（APK）」。
 */
final class FilePick {

    /** 文件选择请求码。 */
    static final int REQ_PICK = 4711;

    /** 仓库文件上传的体积上限：GitHub Contents API 对单文件建议不超过 100MB，
     *  但经 Base64 与 WebView 传递后开销较大，这里保守取 25MB。 */
    static final long MAX_CONTENTS_BYTES = 25L * 1024 * 1024;

    /** Release 附件上限：GitHub 官方为 2GB，这里限制在 200MB 以内避免内存问题。 */
    static final long MAX_ASSET_BYTES = 200L * 1024 * 1024;

    static final class Meta {
        String name = "";
        long size = 0;
        String mime = "application/octet-stream";
    }

    private FilePick() { }

    static Meta query(Context ctx, Uri uri) {
        Meta m = new Meta();
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) m.name = c.getString(nameIdx);
                if (sizeIdx >= 0) m.size = c.getLong(sizeIdx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) { try { c.close(); } catch (Exception ignored) {} }
        }
        if (m.name == null || m.name.isEmpty()) {
            m.name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
        }
        if (m.size <= 0) {
            // 某些提供方不返回 size，退化为读取一次长度
            try {
                InputStream is = cr.openInputStream(uri);
                if (is != null) {
                    m.size = is.available();
                    is.close();
                }
            } catch (Exception ignored) { }
        }
        m.mime = mimeOf(cr, uri, m.name);
        return m;
    }

    /** 推断 MIME；对 APK 给出官方类型，便于 Release 附件正确识别。 */
    static String mimeOf(ContentResolver cr, Uri uri, String name) {
        String mime = null;
        try { mime = cr.getType(uri); } catch (Exception ignored) { }
        if (mime == null || mime.isEmpty()) {
            String lower = name.toLowerCase(Locale.US);
            if (lower.endsWith(".apk")) mime = "application/vnd.android.package-archive";
            else if (lower.endsWith(".aab")) mime = "application/octet-stream";
            else {
                String ext = MimeTypeMap.getFileExtensionFromUrl(lower);
                if (ext != null && !ext.isEmpty()) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                }
            }
        }
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        // APK 显式使用标准类型
        if (name.toLowerCase(Locale.US).endsWith(".apk")
                && !mime.startsWith("application/vnd.android")) {
            mime = "application/vnd.android.package-archive";
        }
        return mime;
    }

    static byte[] readAll(Context ctx, Uri uri) throws Exception {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("无法读取文件");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { is.close(); } catch (Exception ignored) { }
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 版本 >= 33 时不再需要 READ_EXTERNAL_STORAGE（改用分区媒体权限），此处仅作兼容判断。 */
    static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
    }
}
