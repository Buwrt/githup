package com.hubmobile.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView 与前端之间的桥接层：
 * 网络请求、令牌安全存储、剪贴板、分享、下载、震动等原生能力。
 */
public class JsBridge {

    private final Activity activity;
    private final WebView webView;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final String TOKEN_KEY = "gh_token";

    JsBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        watchDownloads();
    }

    private void runJs(final String js) {
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    /* ---------------- 网络 ---------------- */
    @JavascriptInterface
    public void http(String id, String method, String url, String body, String headersJson) {
        pool.execute(() -> {
            try {
                Map<String, String> headers = new HashMap<>();
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject jo = new JSONObject(headersJson);
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        headers.put(k, jo.optString(k, ""));
                    }
                }
                Http.Response r = Http.request(method, url, body, headers);
                String js = "window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ","
                        + r.code + "," + JSONObject.quote(r.body == null ? "" : r.body) + ","
                        + JSONObject.quote(r.headers == null ? "{}" : r.headers) + ")";
                runJs(js);
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                String js = "window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ",0,"
                        + JSONObject.quote("") + "," + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "}") + ")";
                runJs(js);
            }
        });
    }

    /* ---------------- 文件选择与上传 ---------------- */

    /** 当前挂起的文件选择回调 id（一次只允许一个）。 */
    private String pendingPickId;
    private String pendingUploadId;
    private Uri pendingUploadUri;
    private String pendingUploadUrl;
    private String pendingUploadHeaders;

    /** 打开文件选择器；结果通过 window.Native._pick(id, json) 回调。 */
    @JavascriptInterface
    public void pickFile(String id, String accept) {
        pendingPickId = id;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String mime = (accept == null || accept.isEmpty()) ? "*/*" : accept;
                i.setType(mime);
                if (!"*/*".equals(mime)) {
                    // 允许在同类型里多选（Android 支持时）
                    try { i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false); } catch (Exception ignored) {}
                }
                activity.startActivityForResult(Intent.createChooser(i, "选择文件"), FilePick.REQ_PICK);
            } catch (Exception e) {
                failPick(id, "无法打开文件选择器");
            }
        });
    }

    private void failPick(String id, String msg) {
        runJs("window.Native._pick(" + JSONObject.quote(String.valueOf(id)) + ",null,"
                + JSONObject.quote(msg) + ")");
    }

    /** MainActivity 在 onActivityResult 中转发结果。 */
    void onPickResult(int resultCode, Intent data) {
        String id = pendingPickId;
        pendingPickId = null;
        if (id == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")");
            return;
        }
        Uri uri = data.getData();
        try {
            // 持久化读权限，避免后续读取时失效
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) { }
            FilePick.Meta meta = FilePick.query(activity, uri);
            JSONObject jo = new JSONObject();
            jo.put("name", meta.name);
            jo.put("size", meta.size);
            jo.put("mime", meta.mime);
            jo.put("uri", uri.toString());
            runJs("window.Native._pick(" + JSONObject.quote(id) + ","
                    + jo.toString() + ",\"\")");
        } catch (Exception e) {
            failPick(id, "读取文件信息失败");
        }
    }

    void onPickCancel() {
        String id = pendingPickId;
        pendingPickId = null;
        if (id != null) {
            runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")");
        }
    }

    /** 读取选中文件的 Base64（用于仓库文件上传）。大文件会给出提示。 */
    @JavascriptInterface
    public void readFileBase64(String id, String uriStr, long maxBytes) {
        pool.execute(() -> {
            try {
                Uri uri = Uri.parse(uriStr);
                FilePick.Meta meta = FilePick.query(activity, uri);
                if (maxBytes > 0 && meta.size > maxBytes) {
                    runJs("window.Native._read(" + JSONObject.quote(String.valueOf(id))
                            + ",null," + JSONObject.quote("文件过大（" + FilePick.human(meta.size)
                            + "），超过 " + FilePick.human(maxBytes) + " 限制") + ")");
                    return;
                }
                byte[] bytes = FilePick.readAll(activity, uri);
                String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                runJs("window.Native._read(" + JSONObject.quote(String.valueOf(id)) + ","
                        + JSONObject.quote(b64) + ",\"\")");
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                runJs("window.Native._read(" + JSONObject.quote(String.valueOf(id)) + ",null,"
                        + JSONObject.quote(msg) + ")");
            }
        });
    }

    /**
     * 二进制上传到指定 URL（用于 Release 附件）。
     * 走原生读取 + 二进制请求体，避免 JS 侧无法承载大文件的问题。
     */
    @JavascriptInterface
    public void uploadBinary(String id, String url, String uriStr, String headersJson) {
        pool.execute(() -> {
            try {
                Map<String, String> headers = new HashMap<>();
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject jo = new JSONObject(headersJson);
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        headers.put(k, jo.optString(k, ""));
                    }
                }
                Uri uri = Uri.parse(uriStr);
                byte[] data = FilePick.readAll(activity, uri);
                Http.Response r = Http.requestBytes("POST", url, data, headers);
                runJs("window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ","
                        + r.code + "," + JSONObject.quote(r.body == null ? "" : r.body) + ","
                        + JSONObject.quote(r.headers == null ? "{}" : r.headers) + ")");
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                runJs("window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ",0,"
                        + JSONObject.quote("") + "," + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "}") + ")");
            }
        });
    }

    /**
     * 在应用内把用户输入的字母数字变成签名密钥（PKCS12 的 Base64）。
     *
     * 用户不想为了签名去电脑上敲 keytool，所以钥匙由 App 自己生成。
     * 同一串输入会派生出同一把钥匙，因此换设备、换时间都能重建出
     * 完全相同的签名，打出来的包可以覆盖安装。
     * 计算 RSA 2048 需要几秒，所以走线程池，结果通过 window.Native._ks 回调。
     */
    @JavascriptInterface
    public void makeKeystore(String id, String seed, String alias, String storePass, String keyPass) {
        pool.execute(() -> {
            try {
                byte[] ks = KeyTool.makeKeystore(seed, alias, storePass, keyPass);
                String b64 = android.util.Base64.encodeToString(ks, android.util.Base64.NO_WRAP);
                runJs("window.Native._ks(" + JSONObject.quote(String.valueOf(id)) + ","
                        + JSONObject.quote(b64) + ",\"\")");
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                runJs("window.Native._ks(" + JSONObject.quote(String.valueOf(id)) + ",null,"
                        + JSONObject.quote(msg) + ")");
            }
        });
    }

    /** 应用版本号，供「设置 → 关于」显示。 */
    @JavascriptInterface
    public String appVersion() {
        try {
            return BuildConfig.VERSION_NAME;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 本机已安装 APK 的 SHA-256 指纹。
     *
     * 用途：版本号冻结不变、但包里内容已经换过的情况下，
     * 前端靠比对这个指纹来判断「有没有新内容」。
     * 读不到就返回空串（前端会跳过指纹比对，不当成异常）。
     */
    @JavascriptInterface
    public String apkSha256() {
        try {
            android.content.pm.ApplicationInfo ai =
                activity.getPackageManager().getApplicationInfo(activity.getPackageName(), 0);
            java.io.File apk = new java.io.File(ai.sourceDir);
            if (!apk.exists()) return "";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (java.io.FileInputStream in = new java.io.FileInputStream(apk)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /* ---------------- 令牌 ---------------- */
    @JavascriptInterface
    public String getToken() {
        String t = SecurePrefs.get(activity, TOKEN_KEY, "");
        return t == null ? "" : t;
    }

    @JavascriptInterface
    public void setToken(String token) {
        if (token == null || token.isEmpty()) SecurePrefs.remove(activity, TOKEN_KEY);
        else SecurePrefs.put(activity, TOKEN_KEY, token);
    }

    /* ---------------- 键值存储 ---------------- */
    @JavascriptInterface
    public String getPref(String key) {
        return activity.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE).getString(key, null);
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
        activity.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE).edit().putString(key, value).apply();
    }

    /* ---------------- 系统能力 ---------------- */
    @JavascriptInterface
    public void copy(String text) {
        activity.runOnUiThread(() -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("hub", text));
        });
    }

    @JavascriptInterface
    public void share(String url, String title) {
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, url);
                i.putExtra(Intent.EXTRA_SUBJECT, title == null ? "" : title);
                activity.startActivity(Intent.createChooser(i, "分享"));
            } catch (Exception e) {
                Toast.makeText(activity, "无法分享", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressWarnings("deprecation")
    /* ---------------- 应用内浏览器 ---------------- */

    /** 在应用内置浏览器中打开链接（共享 Cookie，可复用网页登录态）。 */
    @JavascriptInterface
    public void openInApp(String url, String title) {
        if (url == null || url.isEmpty()) return;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(activity, WebViewActivity.class);
                i.putExtra(WebViewActivity.EXTRA_URL, url);
                i.putExtra(WebViewActivity.EXTRA_TITLE, title);
                activity.startActivity(i);
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开页面", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void openExternal(String url) {        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                activity.startActivity(i);
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void download(String url, String filename) {
        downloadWithHeaders(url, filename, null);
    }

    /**
     * 带自定义请求头的下载。
     *
     * GitHub 的 Release 资产（私有仓库）与 Actions 构建产物（artifacts）的
     * 下载地址都要求携带 Authorization，否则返回 403。
     * 原生 Http 客户端是自己写的，不支持这些头，所以必须让 DownloadManager
     * 带上 Authorization 与 Accept 再请求。
     *
     * @param headersJson JSON 对象，如 {"Authorization":"Bearer ghp_xxx"}
     */
    /**
     * 下载构建产物（ZIP）并自动解压出 APK 安装。
     *
     * GitHub 的构建产物一定是 ZIP 包，官网只给「下载」，用户还得自己在手机上
     * 找文件管理器解压再点安装。这里一步做完：下载 → 解压 → 拉起安装器。
     */
    @JavascriptInterface
    public void installApk(String url, String filename, String headersJson) {
        enqueueDownload(url, filename, headersJson, true, null);
    }

    /**
     * 带完整性校验的安装：下载完成后先算 SHA-256，跟 expectedSha 比对，
     * 一致才拉起安装器，不一致直接删掉并报错。
     *
     * 这是防「更新源被替换 / 中间人换包」的关键一道：
     * 光靠 HTTPS 只保证「传输过程没被改」，不保证「服务端给的包是对的」。
     * 校验和由客户端内置 + 仓库清单双来源提供，攻击者要同时改两处才行。
     *
     * @param expectedSha 期望的 SHA-256（小写十六进制）。传空则不校验。
     */
    @JavascriptInterface
    public void installApkChecked(String url, String filename, String headersJson, String expectedSha) {
        enqueueDownload(url, filename, headersJson, true, expectedSha);
    }

    @JavascriptInterface
    public void downloadWithHeaders(String url, String filename, String headersJson) {
        enqueueDownload(url, filename, headersJson, false, null);
    }

    private void enqueueDownload(String url, String filename, String headersJson, boolean autoInstall) {
        enqueueDownload(url, filename, headersJson, autoInstall, null);
    }

    private void enqueueDownload(String url, String filename, String headersJson,
                                 boolean autoInstall, String expectedSha) {
        activity.runOnUiThread(() -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setTitle(filename);
                req.setDescription("githup 下载");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName(filename));
                req.allowScanningByMediaScanner();
                req.addRequestHeader("User-Agent", "githup");
                // 默认按 API 语义请求；调用方可覆盖
                req.addRequestHeader("Accept", "*/*");
                req.addRequestHeader("X-GitHub-Api-Version", "2022-11-28");
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject jo = new JSONObject(headersJson);
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = jo.optString(k, "");
                        if (!v.isEmpty()) req.addRequestHeader(k, v);
                    }
                }
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    long id = dm.enqueue(req);
                    rememberDownload(id, filename, autoInstall);
                    expectedShas.put(id, expectedSha == null ? "" : expectedSha.trim().toLowerCase());
                    Toast.makeText(activity, "开始下载 " + filename, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 记录下载任务，便于完成后提示安装 APK */
    private final java.util.Map<Long, String> downloads = new java.util.HashMap<>();
    /** 需要在下载完成后解压并安装的下载任务 */
    private final java.util.Set<Long> autoInstalls = new java.util.HashSet<>();
    /** 每个下载任务期望的 SHA-256（空串 = 不校验） */
    private final java.util.Map<Long, String> expectedShas = new java.util.HashMap<>();

    private void rememberDownload(long id, String name, boolean autoInstall) {
        downloads.put(id, name);
        if (autoInstall) autoInstalls.add(id);
        if (downloads.size() > 50) {
            downloads.clear();
            autoInstalls.clear();
            expectedShas.clear();
        }
    }

    /**
     * 校验下载下来的 APK 该不该装。
     *
     * 两道检查，任一不过就不装：
     *
     *  1) **签名证书**（必查）—— 跟官方发布用的证书比。
     *     这道最关键，因为它跟包的字节无关：不管内容怎么变，
     *     只要是我们签的，证书指纹就不变；别人重签的一定对不上。
     *     它挡住的就是「更新源被替换 / 中间人换成别的 APK」这类攻击。
     *
     *  2) **SHA-256**（选查）—— 调用方给了期望值就比对，确保装的是
     *     清单里写明的那一份，而不是「官方签过但版本不对」的包。
     *
     * @return null 表示可以装；非 null 是拒绝原因（直接展示给用户）
     */
    private String verifySha(Uri fileUri, String expected) {
        // --- 1) 签名证书：必须是官方签的 ---
        String sig = archiveCertSha256(fileUri);
        if (sig != null && !sig.isEmpty()) {
            if (!sig.equals(SignCheck.officialCertSha256())) {
                return "不是官方签名的安装包";
            }
        } else {
            // 读不到签名信息（文件损坏、或压根不是 APK）——不能放行
            return "无法验证安装包签名";
        }

        // --- 2) SHA-256：给了期望值就必须对上 ---
        if (expected == null || expected.trim().isEmpty()) return null;
        try (InputStream in = activity.getContentResolver().openInputStream(fileUri)) {
            if (in == null) return "读不到下载的文件";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            if (sb.toString().equalsIgnoreCase(expected.trim())) return null;
            return "校验和不一致（安装包可能被篡改）";
        } catch (Throwable t) {
            return "校验失败：" + t.getClass().getSimpleName();
        }
    }

    /** 取一个 APK 文件（未安装）的签名证书 SHA-256 */
    @SuppressWarnings("deprecation")
    private String archiveCertSha256(Uri apkUri) {
        String path = null;
        try {
            if ("file".equals(apkUri.getScheme())) {
                path = apkUri.getPath();
            } else {
                // DownloadManager 给的是 content://，用它的 COLUMN_LOCAL_FILENAME 更稳
                try (android.database.Cursor c = activity.getContentResolver()
                        .query(apkUri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex("_data");
                        if (i >= 0) path = c.getString(i);
                    }
                } catch (Throwable ignored) { }
            }
            if (path == null) return null;

            PackageManager pm = activity.getPackageManager();
            android.content.pm.PackageInfo pi;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return null;
                android.content.pm.Signature[] arr = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
                if (arr == null || arr.length == 0) return null;
                return sha256Hex(arr[0]);
            } else {
                pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES);
                if (pi == null || pi.signatures == null || pi.signatures.length == 0) return null;
                return sha256Hex(pi.signatures[0]);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256Hex(android.content.pm.Signature sig) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sig.toByteArray());
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * APK 下载完成后弹出安装界面。
     *
     * 用户在 Actions 里下载的构建产物、或在 Release 里下载的 APK，下载完
     * 如果只是躺在通知栏会很不方便 —— 这里直接拉起安装。
     * Android 8.0+ 要求声明 REQUEST_INSTALL_PACKAGES 权限，否则会被系统拦截。
     */
    private void watchDownloads() {
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    String name = downloads.remove(id);
                    if (name == null) return;
                    boolean inst = autoInstalls.remove(id);
                    boolean isApk = name.toLowerCase().endsWith(".apk");
                    if (!inst && !isApk) return;
                    DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm == null) return;
                    android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c == null) return;
                    try {
                        if (!c.moveToFirst()) return;
                        int i = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (i < 0 || c.getInt(i) != DownloadManager.STATUS_SUCCESSFUL) return;
                        int ui = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                        if (ui < 0) return;
                        String uriStr = c.getString(ui);
                        if (uriStr == null) return;
                        Uri uri = Uri.parse(uriStr);
                        // 装 APK 之前先验指纹：对不上就是包被换了，宁可装不上也不能装错
                        String exp = expectedShas.remove(id);
                        if (exp != null && !exp.isEmpty() && !name.toLowerCase().endsWith(".zip")) {
                            String bad = verifySha(uri, exp);
                            if (bad != null) {
                                final String msg = bad;
                                activity.runOnUiThread(() -> {
                                    Toast.makeText(activity,
                                        "已阻止安装：" + msg + "。请到设置里重新检查更新。",
                                        Toast.LENGTH_LONG).show();
                                });
                                try {
                                    Uri u = uri;
                                    if ("file".equals(u.getScheme()) && u.getPath() != null) {
                                        new File(u.getPath()).delete();
                                    }
                                } catch (Throwable ignored) { }
                                return;
                            }
                        }
                        if (inst && name.toLowerCase().endsWith(".zip")) {
                            final Uri zip = uri;
                            final String zipName = name;
                            pool.execute(() -> extractAndInstall(zip, zipName));
                        } else {
                            openInstaller(uri);
                        }
                    } finally {
                        c.close();
                    }
                }
            };
            activity.getApplicationContext().registerReceiver(r, f);
        } catch (Throwable t) {
            // 注册失败不影响下载本身
        }
    }

    /**
     * 从构建产物 ZIP 里解压出 APK 并交给系统安装器。
     * 一个包里可能有多个 APK（ABI 分包等），取体积最大的那个，通常才是完整包。
     */
    private void extractAndInstall(Uri zipUri, String zipName) {
        File dir = new File(activity.getFilesDir(), ApkProvider.DIR);
        dir.mkdirs();
        // 清掉上次解压的残留，避免装到旧版本
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                try { f.delete(); } catch (Exception ignored) { }
            }
        }
        java.util.List<File> found = new java.util.ArrayList<>();
        try (InputStream in = activity.getContentResolver().openInputStream(zipUri)) {
            if (in == null) throw new java.io.IOException("cannot open " + zipName);
            ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(in));
            ZipEntry e;
            int count = 0;
            while ((e = zis.getNextEntry()) != null && count < 8) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n == null || !n.toLowerCase().endsWith(".apk")) continue;
                File out = new File(dir, safeName(new File(n).getName()));
                if (writeEntry(zis, out) > 0) {
                    found.add(out);
                    count++;
                }
            }
        } catch (Throwable t) {
            toast("解压失败：" + zipName);
            return;
        }
        if (found.isEmpty()) {
            toast("压缩包里没有找到 APK，文件已保存在下载目录");
            return;
        }
        File best = found.get(0);
        for (File f : found) {
            if (f.length() > best.length()) best = f;
        }
        Uri uri = ApkProvider.uriFor(
                activity.getPackageName() + ApkProvider.AUTHORITY_SUFFIX, best.getName());
        openInstaller(uri);
    }

    private long writeEntry(ZipInputStream zis, File out) throws java.io.IOException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int len;
            long total = 0;
            while ((len = zis.read(buf)) > 0) {
                fos.write(buf, 0, len);
                total += len;
                // 防御性上限：单个 APK 500MB
                if (total > 500L * 1024 * 1024) throw new java.io.IOException("apk too large");
            }
            return total;
        }
    }

    private void toast(final String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    /** 用系统安装器打开 APK。Android 7.0+ 必须走 content:// 并授予临时读权限。 */
    private void openInstaller(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
            } else {
                i.setDataAndType(uri, "application/vnd.android.package-archive");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "请在下载目录中找到该 APK 并安装", Toast.LENGTH_LONG).show();
        }
    }

    private static String safeName(String n) {
        if (n == null || n.isEmpty()) return "download";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @JavascriptInterface
    public void haptic() {
        activity.runOnUiThread(() -> {
            try {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(12);
                }
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void clearCache() {
        activity.runOnUiThread(() -> webView.clearCache(true));
    }

    @JavascriptInterface
    public void setStatusBar(String colorHex) {
        activity.runOnUiThread(() -> {
            try {
                int color = android.graphics.Color.parseColor(colorHex);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.getWindow().setStatusBarColor(color);
                }
                boolean light = isLightColor(color);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    View decor = activity.getWindow().getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    decor.setSystemUiVisibility(flags);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.getWindow().setNavigationBarColor(color);
                    View decor = activity.getWindow().getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    decor.setSystemUiVisibility(flags);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static boolean isLightColor(int color) {
        double lum = (0.299 * android.graphics.Color.red(color)
                + 0.587 * android.graphics.Color.green(color)
                + 0.114 * android.graphics.Color.blue(color)) / 255.0;
        return lum > 0.6;
    }

    @JavascriptInterface
    public String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    @JavascriptInterface
    public void exit() {
        activity.runOnUiThread(() -> activity.finish());
    }
}
