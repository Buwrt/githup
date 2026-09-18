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
import java.util.List;
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
    /* 8 个线程：网络通道同时要伺候「页面数据请求」和「翻译引擎的请求/探测」。
     * 曾经只有 4 个：自动选择翻译引擎时并行探测 5 家（其中 Google/DeepL 在
     * 国内要挂满连接超时），瞬间把池子占满，页面自己的数据请求只能在后面
     * 排队 —— 表现就是打开了翻译之后，页面骨架屏转个不停。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
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
                // 埋点五：非官方包连一个请求都发不出去（令牌也带不出去）
                if (!guardOk()) {
                    runJs("window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ",0,"
                            + JSONObject.quote("") + ","
                            + JSONObject.quote("{\"error\":\"UNAUTHORIZED_BUILD\"}") + ")");
                    return;
                }
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

    /**
     * 以 Base64 拉取二进制资源（README 图片、头像等）。
     *
     * WebView 直连 raw.githubusercontent.com 在不少网络下不通，而同样的网络下
     * 仓库列表 / README 文本能正常加载 —— 因为它们走的是这里的原生网络栈。
     * 图片也走同一条路：原生按字节拉回来给 base64，前端转成 data URI 塞进 <img>。
     * 带什么头由前端决定（只有 GitHub 自家域名才带令牌，外链绝不带）。
     */
    @JavascriptInterface
    public void httpB64(String id, String url, String headersJson) {
        pool.execute(() -> {
            try {
                Map<String, String> headers = new HashMap<>();
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject jo = new JSONObject(headersJson);
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = jo.optString(k, "");
                        if (!v.isEmpty()) headers.put(k, v);
                    }
                }
                Http.Response r = Http.requestB64("GET", url, null, headers);
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
        // 埋点四：令牌是最高价值的东西，读之前先过一遍防护链。
        // 非官方包直接拿不到令牌 —— 这是最后一道，也是最实在的一道。
        if (!guardOk()) return "";
        String t = SecurePrefs.get(activity, TOKEN_KEY, "");
        return t == null ? "" : t;
    }

    @JavascriptInterface
    public void setToken(String token) {
        if (!guardOk()) return;
        if (token == null || token.isEmpty()) SecurePrefs.remove(activity, TOKEN_KEY);
        else SecurePrefs.put(activity, TOKEN_KEY, token);
    }

    /**
     * 防护链埋点（桥接层）。
     * 不通过时不抛异常、不弹窗 —— 直接让这次调用失效，
     * 同时把状态推给界面层去处理，避免被逆向的人一眼看出「这里在校验」。
     */
    private boolean guardOk() {
        Guard.Result r = Guard.verify(activity);
        if (r.ok) return true;
        App.sBrokenRing = r.brokenRing;
        App.sBrokenDetail = r.detail;
        App.sBrokenCode = r.code;
        return false;
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

    /**
     * 下载统一落到 **Download/githup/** 这个子目录。
     *
     * 以前直接扔在 Download 根目录，跟浏览器、微信、QQ 下的东西混在一起，
     * 找个文件得翻半天。现在所有下载入口（前端调的 download / installApk、
     * WebView 里点下载链接）都走 downloadSubPath()，落盘位置只有一处定义。
     */
    public static final String DOWNLOAD_SUBDIR = "githup";

    /** 下载文件在 Download/ 下的相对路径，如 githup/foo.zip */
    public static String downloadSubPath(String name) {
        return DOWNLOAD_SUBDIR + "/" + safeName(name);
    }

    /**
     * 尽量先把 Download/githup 建出来。
     *
     * Android 9 及以下：App 自己有公共目录写权限，先 mkdirs 更保险。
     * Android 10 起是分区存储，App 建不了公共目录 —— 不用管，DownloadManager
     * 是系统组件，写的时候会自己把父目录建好。
     */
    @SuppressWarnings("deprecation")
    public static void ensureDownloadDir() {
        if (Build.VERSION.SDK_INT > 28) return;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) { }
    }

    private void enqueueDownload(String url, String filename, String headersJson,
                                 boolean autoInstall, String expectedSha) {
        enqueueDownload(url, filename, headersJson, null, autoInstall, expectedSha);
    }

    /**
     * WebView 里点下载链接走这里 —— 跟前端主动调的下载走同一套通道逻辑，
     * 不然「点链接下载」享受不到加速和自动换道。
     */
    public void enqueueWebViewDownload(String url, String userAgent, String name) {
        enqueueDownload(url, name, null, userAgent, false, null);
    }

    private void enqueueDownload(String url, String filename, String headersJson,
                                 String userAgent, boolean autoInstall, String expectedSha) {
        if (url == null || url.isEmpty()) return;
        final boolean allowMirror = !DownloadChannels.hasAuthHeader(headersJson);
        final String sha = expectedSha == null ? "" : expectedSha.trim().toLowerCase();
        final String name = (filename == null || filename.isEmpty()) ? "download" : filename;
        activity.runOnUiThread(() -> {
            try {
                ensureDownloadDir();
                DlTask t = new DlTask(name, url, headersJson, userAgent, sha, autoInstall,
                        candidateUrls(url, allowMirror));
                if (!startTask(t)) {
                    Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                /* 有多个候选通道时说一声 —— 用户知道「慢了会自动换」就不会
                 * 盯着几十 KB/s 干着急，也不会一失败就以为软件坏了。 */
                Toast.makeText(activity, t.urls.size() > 1
                        ? "开始下载 " + name + "（" + t.channel() + "，慢会自动换道）"
                        : "开始下载 " + name, Toast.LENGTH_SHORT).show();
                startWatch();
            } catch (Exception e) {
                Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 用任务当前的通道发起下载；成功返回 true */
    private boolean startTask(DlTask t) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        long id = dm.enqueue(buildRequest(t.url(), t.filename, t.headersJson, t.userAgent, true));
        if (id < 0) {
            /* 子目录建不起来（个别 ROM 的 DownloadManager 不给建），
             * 退回 Download 根目录再试一次 —— 位置不对也比下不到强。 */
            id = dm.enqueue(buildRequest(t.url(), t.filename, t.headersJson, t.userAgent, false));
        }
        if (id <= 0) return false;
        t.id = id;
        t.startedAt = System.currentTimeMillis();
        t.lastAt = t.startedAt;
        t.lastBytes = 0;
        t.slowStrikes = 0;
        downloads.put(id, t);
        if (t.autoInstall) autoInstalls.add(id);
        expectedShas.put(id, t.expectedSha);
        if (downloads.size() > 50) {
            downloads.clear();
            autoInstalls.clear();
            expectedShas.clear();
        }
        return true;
    }

    private final android.os.Handler watchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watching = false;

    private void startWatch() {
        if (watching) return;
        watching = true;
        watchHandler.post(this::watchTick);
    }

    /**
     * 每隔几秒看一眼进行中的下载：**不动、太慢、失败**都自动换到下一条通道。
     *
     * 这是「下载失败 / 下载慢」最实际的一层兜底：用户不用守着点重试，
     * 也不用知道 gh-proxy 是什么 —— 软件自己把能走的路都走一遍。
     */
    private void watchTick() {
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null || downloads.isEmpty()) {
                watching = false;
                return;
            }
            long now = System.currentTimeMillis();
            for (Object key : new ArrayList<Object>(downloads.keySet())) {
                long id = (Long) key;
                DlTask t = downloads.get(id);
                if (t == null) continue;
                long status = -1, sofar = -1;
                android.database.Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c == null || !c.moveToFirst()) continue;
                    int si = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int bi = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    if (si >= 0) status = c.getInt(si);
                    if (bi >= 0) sofar = c.getLong(bi);
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
                if (status == DownloadManager.STATUS_FAILED
                        || status == DownloadManager.STATUS_SUCCESSFUL) {
                    /* 终态统一交给收尾入口：失败走换道，成功走安装/提示。
                     * 广播虽然通常也会来，但这里不等它 —— 广播可能丢。 */
                    finishDownload(id);
                    continue;
                }
                /* 排队中 / 被系统暂停（比如等 WiFi）：不是通道的锅，别动它 */
                if (status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_PAUSED) continue;
                if (sofar < 0) continue;

                /* 用「这一轮的实测速度」判断：既抓得住完全卡死，
                 * 也抓得住「一直在爬但只有几十 KB/s」这种更气人的情况。 */
                long dt = now - t.lastAt;
                long dB = sofar - t.lastBytes;
                t.lastAt = now;
                t.lastBytes = sofar;
                if (dt <= 0) continue;
                long speed = dB * 1000L / dt;
                if (speed >= MIN_SPEED_BPS) {
                    t.slowStrikes = 0;
                    continue;
                }
                if (now - t.startedAt < GRACE_MS) continue;
                if (++t.slowStrikes >= SLOW_STRIKES) switchChannel(t, "速度太慢");
            }
        } catch (Throwable ignored) { }
        if (downloads.isEmpty()) {
            watching = false;
            return;
        }
        watchHandler.postDelayed(this::watchTick, WATCH_MS);
    }

    /** 当前通道不行，换下一条重下；所有通道都试过了才报失败 */
    private void switchChannel(DlTask t, String why) {
        switchChannel(t, why, -1);
    }

    private void switchChannel(DlTask t, String why, long bytes) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null && t.id > 0) {
            try { dm.remove(t.id); } catch (Throwable ignored) { }
            downloads.remove(t.id);
            autoInstalls.remove(t.id);
            expectedShas.remove(t.id);
        }
        if (t.idx + 1 >= t.urls.size()) {
            /* 一条都不剩了：这才算真正的失败，落进历史里 */
            addHistory(t, false, bytes);
            final String n = t.filename;
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "下载失败：" + n + "（所有通道都试过了，请检查网络）",
                    Toast.LENGTH_LONG).show());
            return;
        }
        t.idx++;
        t.slowStrikes = 0;
        if (!startTask(t)) {
            addHistory(t, false, bytes);
            final String n = t.filename;
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "下载失败：" + n, Toast.LENGTH_SHORT).show());
            return;
        }
        final String ch = t.channel();
        activity.runOnUiThread(() -> Toast.makeText(activity,
                why + "，已切换到" + ch, Toast.LENGTH_SHORT).show());
    }

    /** 组装下载请求。subDir = true 时落到 Download/githup/ 下 */
    private DownloadManager.Request buildRequest(String url, String filename,
                                                 String headersJson, String userAgent,
                                                 boolean subDir) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle(filename);
        req.setDescription("githup 下载");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                subDir ? downloadSubPath(filename) : safeName(filename));
        req.allowScanningByMediaScanner();
        req.addRequestHeader("User-Agent",
                (userAgent == null || userAgent.isEmpty()) ? "githup" : userAgent);
        // 默认按 API 语义请求；调用方可覆盖
        req.addRequestHeader("Accept", "*/*");
        req.addRequestHeader("X-GitHub-Api-Version", "2022-11-28");
        if (headersJson != null && !headersJson.isEmpty()) {
            try {
                JSONObject jo = new JSONObject(headersJson);
                Iterator<String> it = jo.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = jo.optString(k, "");
                    if (!v.isEmpty()) req.addRequestHeader(k, v);
                }
            } catch (Exception ignored) { }
        }
        return req;
    }

    // ------------------------------------------------------------------
    // 下载通道：直连慢 / 连不上时自动换道
    //
    // 为什么需要：系统 DownloadManager 是**自己直连** GitHub 的。Release 附件、
    // 源码包最终都会 302 到 objects.githubusercontent.com，这个域名在国内不少
    // 宽带（广电 / 移动 / 长城…）下又慢又容易中途断，表现就是「几十 KB/s 慢慢
    // 爬」或者直接「下载失败」。而 App 里的列表、README、图片是通的 —— 它们走
    // 的是原生网络栈 + 加速镜像。所以给下载也补上同样的多通道。
    // ------------------------------------------------------------------

    /** 低于这个速度算「太慢」，连续观察几轮还这样就换道 */
    private static final long MIN_SPEED_BPS = 15 * 1024;
    /** 监控间隔 */
    private static final long WATCH_MS = 3_000;
    /** 连续几轮判定太慢才真的换道（免得刚起步的抖动被误判） */
    private static final int SLOW_STRIKES = 2;
    /** 首次出数据前的观察期：这段时间内不判慢，等连接握手 */
    private static final long GRACE_MS = 8_000;

    private static final String PREF_DL = "githup_dl";
    private static final String KEY_CHANNEL = "last_channel";

    /** 记录下载任务，便于完成后提示安装 APK / 卡住时换道重下 */
    private final java.util.Map<Long, DlTask> downloads = new java.util.concurrent.ConcurrentHashMap<>();
    /** 需要在下载完成后解压并安装的下载任务 */
    private final java.util.Set<Long> autoInstalls = new java.util.HashSet<>();
    /** 每个下载任务期望的 SHA-256（空串 = 不校验） */
    private final java.util.Map<Long, String> expectedShas = new java.util.HashMap<>();

    /** 一个下载任务的完整状态。换道时要靠它原样重下一次，所以都存着 */
    private static final class DlTask {
        final String filename;
        final String originUrl;
        final String headersJson;
        final String userAgent;
        final String expectedSha;
        final boolean autoInstall;
        final List<String> urls;
        int idx = 0;
        long id = -1;
        long startedAt = 0;
        long lastBytes = 0;
        long lastAt = 0;
        int slowStrikes = 0;

        DlTask(String filename, String originUrl, String headersJson, String userAgent,
               String expectedSha, boolean autoInstall, List<String> urls) {
            this.filename = filename;
            this.originUrl = originUrl;
            this.headersJson = headersJson;
            this.userAgent = userAgent;
            this.expectedSha = expectedSha;
            this.autoInstall = autoInstall;
            this.urls = urls;
        }

        String url() { return urls.get(Math.min(idx, urls.size() - 1)); }

        /** 给进度条看的通道名，如「加速 1」/「直连」 */
        String channel() { return DownloadChannels.channelName(url()); }

        /** 这条通道的稳定标识，用于「上次成功过就优先用它」 */
        String channelKey() { return DownloadChannels.channelKey(url()); }
    }

    /** 上次成功走通的通道（空 = 还没记录过） */
    private String readLastChannel() {
        try {
            return activity.getSharedPreferences(PREF_DL, Context.MODE_PRIVATE)
                    .getString(KEY_CHANNEL, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void saveLastChannel(String ch) {
        try {
            activity.getSharedPreferences(PREF_DL, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CHANNEL, ch).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 生成候选下载地址。具体规则（哪些域名可加速、带令牌必须直连）
     * 都在 DownloadChannels 里，那边是纯逻辑、能单测。
     */
    private List<String> candidateUrls(String url, boolean allowMirror) {
        return DownloadChannels.candidates(url, allowMirror, readLastChannel());
    }

    /**
     * 正在进行的下载任务（JSON 数组），给前端的进度条轮询。
     *
     * 终态（成功/失败）的任务**绝不返回** —— 以前广播偶尔丢一次（App 在后台
     * 被杀），完成的任务就赖在表里，前端进度条每 800ms 弹一次「100%」，
     * 用户看到的就是「下载完了还不停地弹」。现在查到终态就地收尾，前端永远
     * 只会看到「正在进行」的。
     */
    @JavascriptInterface
    public String downloadStatus() {
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return "[]";
            org.json.JSONArray arr = new org.json.JSONArray();
            final java.util.List<Long> ended = new ArrayList<>();
            for (Map.Entry<Long, DlTask> e : downloads.entrySet()) {
                android.database.Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(e.getKey()));
                    if (c == null || !c.moveToFirst()) continue;
                    int si = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int bi = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int ti = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    int st = si < 0 ? 0 : c.getInt(si);
                    if (st == DownloadManager.STATUS_SUCCESSFUL
                            || st == DownloadManager.STATUS_FAILED) {
                        ended.add(e.getKey());
                        continue;
                    }
                    DlTask t = e.getValue();
                    JSONObject o = new JSONObject();
                    o.put("id", e.getKey());
                    o.put("name", t.filename);
                    o.put("ch", t.channel());
                    o.put("status", st);
                    o.put("sofar", bi < 0 ? 0 : c.getLong(bi));
                    o.put("total", ti < 0 ? -1 : c.getLong(ti));
                    arr.put(o);
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
            }
            /* 收尾要在主线程做（里面会弹 Toast / 拉安装器） */
            if (!ended.isEmpty()) {
                activity.runOnUiThread(() -> {
                    for (long id : ended) finishDownload(id);
                });
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
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
    /** 完成广播的注册开关：**只许注册一次**。
     * 以前每次建 JsBridge（每次进主界面）都注册一遍，同一个完成广播就触发
     * N 遍 —— 安装弹窗、「已保存」提示连着弹好几次，用户以为出了鬼。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RX_DONE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void watchDownloads() {
        if (!RX_DONE.compareAndSet(false, true)) return;
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id > 0) finishDownload(id);
                }
            };
            activity.getApplicationContext().registerReceiver(r, f);
        } catch (Throwable t) {
            RX_DONE.set(false);   // 没注册上就放开，下次再试
        }
    }

    /**
     * 下载收尾的**唯一入口**：完成广播、进度轮询、任务监控三处都汇到这里。
     *
     * `downloads.remove(id)` 的原子性保证一个任务只会被收尾一次 ——
     * 以前广播和轮询各管各的：广播一旦没送到（App 在后台被杀再回来），
     * 完成的任务就永远躺在表里，App 内进度条每 800ms 弹一次「100%」，
     * 用户看到的就是「下载完了还不停地弹」。
     */
    private void finishDownload(final long id) {
        final DlTask t = downloads.remove(id);
        if (t == null) return;          // 已经被别的入口收尾过了
        final boolean inst = autoInstalls.remove(id);
        final String exp = expectedShas.remove(id);
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        long bytes = 0;
        boolean ok = false;
        android.database.Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int si = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int bi = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                ok = si >= 0 && c.getInt(si) == DownloadManager.STATUS_SUCCESSFUL;
                if (bi >= 0) bytes = c.getLong(bi);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (!ok) {
            /* 失败：还有备选通道就再试一次，全部试完才落历史 */
            switchChannel(t, "下载失败", bytes);
            return;
        }
        final long size = bytes;
        activity.runOnUiThread(() -> {
            /* 这条通道跑通了，记下来 —— 下次同网络环境直接先试它 */
            saveLastChannel(t.channelKey());
            addHistory(t, true, size);
            boolean isApk = t.filename.toLowerCase().endsWith(".apk");
            if (!inst && !isApk) {
                /* 普通文件：下完告诉一声存哪了，省得去 Download 里翻 */
                Toast.makeText(activity,
                        "已保存到 Download/" + DOWNLOAD_SUBDIR + "/" + t.filename,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            installFinished(t, exp, inst);
        });
    }

    /** 成功后的安装环节：SHA 校验 →（ZIP 就解压装）→ 拉起安装器 */
    private void installFinished(DlTask t, String exp, boolean inst) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        android.database.Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(t.id));
            if (c == null || !c.moveToFirst()) return;
            String name = t.filename;
            boolean isApk = name.toLowerCase().endsWith(".apk");
            boolean isZip = name.toLowerCase().endsWith(".zip");
            /* 统一转成 content:// 再交给外部：DownloadManager 给的 file://
             * 在 Android 7+ 会被系统静默拦掉，之前「下载完成却装不了」就栽在这。 */
            Uri uri = DownloadProvider.uriFor(activity.getPackageName(), name);
            if (exp != null && !exp.isEmpty() && !isZip) {
                String bad = verifySha(uri, exp);
                if (bad != null) {
                    final String msg = bad;
                    Toast.makeText(activity,
                            "已阻止安装：" + msg + "。请到下载管理里删除后重试。",
                            Toast.LENGTH_LONG).show();
                    try {
                        DownloadProvider.fileFor(name).delete();
                    } catch (Throwable ignored) { }
                    return;
                }
            }
            if (inst && isZip) {
                pool.execute(() -> extractAndInstall(uri, name));
            } else {
                openInstaller(uri);
            }
        } finally {
            if (c != null) c.close();
        }
    }

    // ------------------------------------------------------------------
    // 下载历史（给「下载管理」页）
    //
    // 进行中的任务查 DownloadManager 就有，但「下完了 / 失败了」的记录
    // 系统不留账 —— 想找到刚下的文件得去文件管理器翻。这里自己记一本，
    // 存在本地（最多 30 条），管理页里能打开、删除、重试。
    // ------------------------------------------------------------------

    private static final String SP_DL_HISTORY = "dl_history";
    private static final int HISTORY_MAX = 30;

    /** 最新的在最前。JSONArray 非线程安全：UI 线程写、JS 线程读，得锁 */
    private final org.json.JSONArray dlHistory = new org.json.JSONArray();

    private void loadHistoryLocked() {
        if (dlHistory.length() > 0) return;
        try {
            String s = activity.getSharedPreferences(PREF_DL, Context.MODE_PRIVATE)
                    .getString(SP_DL_HISTORY, "[]");
            org.json.JSONArray a = new org.json.JSONArray(s);
            for (int i = 0; i < a.length() && i < HISTORY_MAX; i++) dlHistory.put(a.get(i));
        } catch (Throwable ignored) { }
    }

    private void saveHistoryLocked() {
        try {
            activity.getSharedPreferences(PREF_DL, Context.MODE_PRIVATE)
                    .edit().putString(SP_DL_HISTORY, dlHistory.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 收尾时写一条历史。ok=false 也会记 —— 失败了才知道要去重试 */
    private void addHistory(DlTask t, boolean ok, long bytes) {
        synchronized (dlHistory) {
            loadHistoryLocked();
            try {
                JSONObject o = new JSONObject();
                o.put("name", t.filename);
                o.put("url", t.originUrl == null ? "" : t.originUrl);
                o.put("ok", ok);
                o.put("bytes", bytes);
                o.put("time", System.currentTimeMillis());
                o.put("install", t.autoInstall);
                o.put("sha", t.expectedSha == null ? "" : t.expectedSha);
                dlHistory.put(0, o);
                while (dlHistory.length() > HISTORY_MAX) dlHistory.remove(dlHistory.length() - 1);
                saveHistoryLocked();
            } catch (Throwable ignored) { }
        }
    }

    private void removeHistoryLocked(String name) {
        synchronized (dlHistory) {
            loadHistoryLocked();
            for (int i = dlHistory.length() - 1; i >= 0; i--) {
                JSONObject o = dlHistory.optJSONObject(i);
                if (o != null && name.equals(o.optString("name"))) dlHistory.remove(i);
            }
            saveHistoryLocked();
        }
    }

    /** 下载历史（JSON 数组，最新在前），给「下载管理」页 */
    @JavascriptInterface
    public String downloadHistory() {
        synchronized (dlHistory) {
            loadHistoryLocked();
            return dlHistory.toString();
        }
    }

    /**
     * 下载管理页的操作入口。
     *
     * json: {"action":"open|delete|retry|cancel|clear",
     *        "name":"x.apk","id":12,"url":"https://…","install":true,"sha":"…"}
     *
     *  - cancel：取消进行中的任务（走 downloadStatus 里的 id）
     *  - open  ：打开已完成文件（APK 直接拉安装器，其它按类型交给系统）
     *  - delete：删文件 + 删记录
     *  - retry ：按记录的原始地址重新下载
     *  - clear ：只清记录，不动文件
     */
    @JavascriptInterface
    public void downloadAction(String json) {
        try {
            final JSONObject o = new JSONObject(json == null ? "{}" : json);
            activity.runOnUiThread(() -> runDlAction(o));
        } catch (Throwable ignored) { }
    }

    private void runDlAction(JSONObject o) {
        String act = o.optString("action", "");
        try {
            if ("cancel".equals(act)) {
                long id = o.optLong("id", -1);
                if (id <= 0) return;
                DlTask t = downloads.remove(id);
                autoInstalls.remove(id);
                expectedShas.remove(id);
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    try { dm.remove(id); } catch (Throwable ignored) { }
                }
                Toast.makeText(activity, t == null ? "已取消"
                        : "已取消下载 " + t.filename, Toast.LENGTH_SHORT).show();
                return;
            }
            final String name = o.optString("name", "");
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return;
            if ("open".equals(act)) {
                File f = DownloadProvider.fileFor(name);
                if (!f.exists()) {
                    Toast.makeText(activity, "文件不在了（可能已被删除）", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri u = DownloadProvider.uriFor(activity.getPackageName(), name);
                if (name.toLowerCase().endsWith(".apk")) {
                    openInstaller(u);
                } else {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, DownloadProvider.mimeFor(name));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        activity.startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(activity, "没有能打开这类文件的应用", Toast.LENGTH_SHORT).show();
                    }
                }
            } else if ("delete".equals(act)) {
                boolean gone = DownloadProvider.fileFor(name).delete();
                removeHistoryLocked(name);
                Toast.makeText(activity, gone ? "已删除文件和记录" : "记录已删除",
                        Toast.LENGTH_SHORT).show();
            } else if ("retry".equals(act)) {
                String url = o.optString("url", "");
                if (url.isEmpty()) {
                    Toast.makeText(activity, "这条记录太老了，重试不了", Toast.LENGTH_SHORT).show();
                    return;
                }
                enqueueDownload(url, name, null, null,
                        o.optBoolean("install", false), o.optString("sha", ""));
            } else if ("clear".equals(act)) {
                synchronized (dlHistory) {
                    while (dlHistory.length() > 0) dlHistory.remove(0);
                    saveHistoryLocked();
                }
            }
        } catch (Throwable ignored) { }
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
