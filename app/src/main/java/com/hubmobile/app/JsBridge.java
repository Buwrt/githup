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
import android.provider.MediaStore;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
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

    /** 申请媒体权限的请求码（结果由 MainActivity 转发回来）。 */
    static final int REQ_MEDIA_PERM = 4712;

    JsBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        watchDownloads();
    }

    private void runJs(final String js) {
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    /**
     * 告诉前端「这次自动更新没装成」。
     *
     * 为什么需要这条回执：
     *   前端在用户按下「立即更新」时会记一笔「我正在装这一份」，
     *   免得下载安装这段空窗期里反复弹同一个提示。但那一笔是在**按下时**
     *   就记下的 —— 下载失败、或校验不过被拦下时，更新其实没装成，
     *   而那条记录还挂着，用户接下来每次打开都不再收到提醒，
     *   干等也不知道为什么（真机反馈就是这个）。
     *
     *   前端侧已经给那条记录加了 30 分钟有效期兜底，但「立刻知道失败」
     *   显然比「等它过期」好，所以这里主动收回来。
     */
    private void notifyUpdateAborted(String why) {
        try {
            String q = why == null ? "" : why.replace("\\", "\\\\").replace("'", "\\'");
            runJs("try{if(window.Updater&&Updater.clearUpdating)Updater.clearUpdating('" + q + "');}catch(e){}");
        } catch (Throwable ignored) { }
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

    /*
     * 打开选择器；结果通过 window.Native._pick(id, json) 回调。
     *
     * accept 决定走哪种选择器：
     *   - 纯图片 / 纯视频 / 图片+视频  ->  系统「相册」（见 launchGallery）
     *   - 其它（APK、压缩包、任意文件）->  系统文件浏览器（见 launchFileBrowser）
     *
     * 为什么媒体要单独走相册：
     *   用户要的是「在相册里挑照片」，而不是在一个列着 DCIM、Download、
     *   Android 这些目录名的文件管理器里翻。ACTION_OPEN_DOCUMENT 虽然稳，
     *   但它是文件浏览器语义，对普通用户太绕。
     *
     * 为什么之前会看到「此应用只能访问您选择的照片 / 无照片或视频」：
     *   那是 Android 13+ 的系统「照片选择器」。它按【授权范围】显示内容 ——
     *   应用一个媒体权限都没被授予时，它就只剩那句提示。
     *   现在选之前先申请权限（READ_MEDIA_IMAGES / READ_MEDIA_VIDEO，
     *   旧系统是 READ_EXTERNAL_STORAGE），并且：
     *     · Android 14+ 额外申请 READ_MEDIA_VISUAL_USER_SELECTED ——
     *       这是「只选部分照片」权限，用户就算点了「仅允许选中的照片」，
     *       相册里也能看到内容，而不是空白
     *     · 被拒绝也照样打开选择器（不拦、不报错），最多是内容少一些
     */
    @JavascriptInterface
    public void pickFile(String id, String accept) {
        pendingPickId = id;
        final String raw = (accept == null || accept.isEmpty()) ? "*/*" : accept.trim();
        activity.runOnUiThread(() -> {
            if (isMediaAccept(raw)) {
                // 相册路径：先要权限，拿到（或拿不到）都继续开相册
                if (!ensureMediaPermission(raw)) {
                    pendingPickAccept = raw;   // 等权限回调，见 onMediaPermissionResult()
                    return;
                }
                launchGallery(raw);
            } else {
                launchFileBrowser(raw);
            }
        });
    }

    /** 权限回调未回来前暂存的 accept 参数。 */
    private String pendingPickAccept;

    /** 这个 accept 是不是「只要图片 / 视频」—— 是的话走相册，不走文件浏览器。 */
    private static boolean isMediaAccept(String accept) {
        if (accept == null) return false;
        String a = accept.trim();
        if (a.isEmpty() || "*/*".equals(a)) return false;   // 任意文件 -> 文件浏览器
        String[] types = splitTypes(a);
        for (String t : types) {
            // 只要出现非图片非视频的类型，就说明要的是「文件」，不能只给相册
            if (!t.startsWith("image/") && !t.startsWith("video/")) return false;
        }
        return types.length > 0;
    }

    /**
     * 打开系统相册（图片 / 视频）。
     *
     * 三级降级，按系统版本挑最新的可用方式：
     *   1. Android 13+：ACTION_PICK_IMAGES —— 系统照片选择器，就是相册那个界面。
     *      传 MediaStore.getPickImagesMaxLimit() 允许一次多选。
     *   2. Android 7~12：MediaStore.ACTION_PICK_IMAGES 不存在，用
     *      ACTION_PICK + MediaStore 的 images/video 集合 —— 同样是相册界面。
     *      图片+视频同时要时用 Intent.ACTION_PICK 配 EXTRA_MIME_TYPES。
     *   3. 都没有（极少见）：退回文件浏览器，至少能用。
     *
     * 拿到的是带读权限的 content:// URI，经 FilePick.query 读取元信息后
     * 交给前端上传，和文件浏览器路径完全一致。
     */
    private void launchGallery(String accept) {
        String[] types = splitTypes(accept);
        boolean wantImage = false, wantVideo = false;
        for (String t : types) {
            if (t.startsWith("image/")) wantImage = true;
            else if (t.startsWith("video/")) wantVideo = true;
            else { wantImage = true; wantVideo = true; }
        }

        // 1) Android 13+ 的系统照片选择器（就是「相册」那个界面）
        //
        //    图片和视频【可以同时选】：官方文档写明，不限定 MIME 类型时
        //    选择器会同时显示照片和视频；只有 setType("image/*") 才只给图片。
        //    上一版这里搞错了 —— 「两者都要」时主动跳过系统相册、退到
        //    ACTION_PICK + MediaStore.Files，而部分定制系统（如部分一加/OPPO）
        //    把它渲染成文件浏览器，于是用户要「去文件夹里翻视频」。
        //    现在两者都要时不设 type，让系统相册同时列出图片和视频。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Intent i = new Intent("android.provider.action.PICK_IMAGES");
                if (wantImage && !wantVideo) {
                    i.setType("image/*");     // 只要图片
                } else if (wantVideo && !wantImage) {
                    i.setType("video/*");     // 只要视频
                }
                // 两者都要：不 setType —— 相册同时显示照片和视频
                try { i.putExtra("android.provider.extra.PICK_IMAGES_MAX", 100); } catch (Exception ignored) {}
                activity.startActivityForResult(i, FilePick.REQ_PICK);
                return;
            } catch (Exception ignored) {
                // 落到下一级
            }
        }

        // 2) Android 7~12：ACTION_PICK + MediaStore（也是相册界面）
        try {
            Intent i = new Intent(Intent.ACTION_PICK);
            if (wantImage && !wantVideo) {
                i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            } else if (wantVideo && !wantImage) {
                i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            } else {
                // 图片 + 视频：让用户在相册里挑，EXTRA_MIME_TYPES 声明两种类型
                i.setDataAndType(MediaStore.Files.getContentUri("external"), "*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            }
            try { i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); } catch (Exception ignored) {}
            try {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            activity.startActivityForResult(i, FilePick.REQ_PICK);
            return;
        } catch (Exception ignored) {
            // 落到下一级
        }

        // 3) 兜底：文件浏览器
        launchFileBrowser(accept);
    }

    /** 非媒体文件（APK / 压缩包 / 任意文件）走系统文件浏览器。 */
    private void launchFileBrowser(String accept) {
        try {
            String[] types = splitTypes(accept);
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (types.length == 1) {
                i.setType(types[0]);
            } else {
                i.setType("*/*");   // 通配 + EXTRA_MIME_TYPES：setType 只认一个类型串
                i.putExtra(Intent.EXTRA_MIME_TYPES, types);
            }
            try { i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); } catch (Exception ignored) {}
            try {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            } catch (Exception ignored) {}
            activity.startActivityForResult(i, FilePick.REQ_PICK);
        } catch (Exception e) {
            // 个别精简系统没有 DocumentsUI：退回老方式，至少给个选择器
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = splitTypes(accept);
                if (types.length == 1) i.setType(types[0]);
                else { i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, types); }
                activity.startActivityForResult(Intent.createChooser(i, "选择文件"), FilePick.REQ_PICK);
            } catch (Exception e2) {
                failPick(pendingPickId, "无法打开文件选择器");
            }
        }
    }

    /**
     * 按 accept 判断需要哪种媒体权限；不需要或已经有了就返回 true。
     * 需要但还没有：发起运行时申请并返回 false。
     */
    private boolean ensureMediaPermission(String accept) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        List<String> need = new ArrayList<>();
        boolean wantsImage = accept.contains("image") || accept.equals("*/*");
        boolean wantsVideo = accept.contains("video") || accept.equals("*/*");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：分区媒体权限，只能按类型申请。
            // 申请前先看「已授予」——
            //   Android 14 上 READ_MEDIA_IMAGES 未授予，但
            //   READ_MEDIA_VISUAL_USER_SELECTED 已授予（用户选了「仅选中的照片」）时，
            //   相册是有内容的，不该再弹一次权限框骚扰用户。
            boolean imgOk = activity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean vidOk = activity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean partialOk = false;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    partialOk = activity.checkSelfPermission(
                            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                } catch (Throwable ignored) { }
            }
            if (wantsImage && !imgOk && !partialOk) need.add(android.Manifest.permission.READ_MEDIA_IMAGES);
            if (wantsVideo && !vidOk && !partialOk) need.add(android.Manifest.permission.READ_MEDIA_VIDEO);
            if (need.isEmpty()) return true;
            // 已拿到「部分照片」权限时不再申请，直接开相册
            if (partialOk && (wantsImage || wantsVideo)) return true;
        } else {
            if ((wantsImage || wantsVideo)
                    && activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (need.isEmpty()) return true;
        try {
            activity.requestPermissions(need.toArray(new String[0]), REQ_MEDIA_PERM);
        } catch (Exception e) {
            return true;   // 申请失败也别把选择器堵死
        }
        return false;
    }

    /** 权限申请结果由 MainActivity 转发过来。无论如何都把选择器打开。 */
    void onMediaPermissionResult() {
        String accept = pendingPickAccept;
        pendingPickAccept = null;
        if (pendingPickId == null) return;
        launchGallery(accept == null || accept.isEmpty() ? "*/*" : accept);
    }

    /* 把 accept 拆成 MIME 数组： "image/*,video/*" -> ["image/*","video/*"]；
     * 空或通配按单元素处理。 */
    private static String[] splitTypes(String accept) {
        if (accept == null || accept.isEmpty() || "*/*".equals(accept.trim())) {
            return new String[]{"*/*"};
        }
        String[] parts = accept.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) out.add("*/*");
        return out.toArray(new String[0]);
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
        if (resultCode != Activity.RESULT_OK || data == null) {
            runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")");
            return;
        }

        // 多选：ClipData 里是一个列表；单选仍在 data.getData()。
        // 前端拿到数组后逐个上传。
        java.util.List<Uri> uris = new ArrayList<>();
        try {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } catch (Exception ignored) { }

        if (uris.isEmpty()) {
            // 用户什么都没选（点返回 / 关掉选择器）
            runJs("window.Native._pick(" + JSONObject.quote(id) + ",null,\"\")");
            return;
        }

        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            JSONArray arr = new JSONArray();
            for (Uri uri : uris) {
                // 持久化读权限，避免后续读取时失效
                try {
                    activity.getContentResolver().takePersistableUriPermission(uri, flags);
                } catch (Exception ignored) { }
                FilePick.Meta meta = FilePick.query(activity, uri);
                JSONObject jo = new JSONObject();
                jo.put("name", meta.name);
                jo.put("size", meta.size);
                jo.put("mime", meta.mime);
                jo.put("uri", uri.toString());
                arr.put(jo);
            }
            // 单个也包成数组，前端统一按数组处理（_pick 里再摊平回单对象）
            runJs("window.Native._pick(" + JSONObject.quote(id) + ","
                    + arr.toString() + ",\"\")");
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
     * multipart/form-data 上传：用于往 GitHub 传图片 / 视频附件。
     *
     * 与 uploadBinary 的区别 ——
     *   uploadBinary 是一次性把整个文件读成 byte[]，适合几百 KB 的 APK；
     *   图片视频动辄十几 MB，必须流式，否则低端机直接 OOM。
     *   所以这里只传头尾字符串，文件由原生边读边发。
     *
     * @param head 文件之前的内容（含末尾空行）
     * @param tail 文件之后的内容（含结束边界）
     */
    @JavascriptInterface
    public void uploadMultipart(String id, String url, String uriStr, String headersJson,
                                String head, String tail) {
        pool.execute(() -> {
            InputStream in = null;
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
                String ctype = headers.remove("Content-Type");   // 由请求头单独带，别重复

                byte[] headBytes = head == null ? new byte[0] : head.getBytes("UTF-8");
                byte[] tailBytes = tail == null ? new byte[0] : tail.getBytes("UTF-8");
                long fileLen = FilePick.sizeOf(activity, uri);
                long total = headBytes.length + fileLen + tailBytes.length;

                // 头 + 文件 + 尾拼成一个流，交给底层流式发送
                in = new java.io.SequenceInputStream(
                        new java.io.ByteArrayInputStream(headBytes),
                        new java.io.SequenceInputStream(
                                FilePick.open(activity, uri),
                                new java.io.ByteArrayInputStream(tailBytes)));

                Http.Response r = Http.requestMultipart(url, in, total, ctype, headers);
                runJs("window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ","
                        + r.code + "," + JSONObject.quote(r.body == null ? "" : r.body) + ","
                        + JSONObject.quote(r.headers == null ? "{}" : r.headers) + ")");
            } catch (Throwable t) {
                String msg = t.getMessage();
                if (msg == null) msg = t.getClass().getSimpleName();
                runJs("window.Native._cb(" + JSONObject.quote(String.valueOf(id)) + ",0,"
                        + JSONObject.quote("") + "," + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "})"));
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
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
     * 本机安装包的签名证书指纹（SHA-256，小写），供「设置 → 关于」显示。
     *
     * 让人能自己核对：这个包的签名是不是官方那个。读不到就返回空串 ——
     * 编一个假的比不显示更有害。
     */
    @JavascriptInterface
    public String certSha256() {
        try {
            String s = SignCheck.signingSha256(activity);
            return s == null ? "" : s;
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
    /**
     * 用系统浏览器 / 对应的 App 打开一条外链。
     *
     * CATEGORY_BROWSABLE 只给 http/https 加，不能再无条件加：
     * 它是给「浏览器可安全打开的网页」用的过滤条件，加上它之后系统只会匹配
     * 声明了自己能处理 browsable 的 Activity。自定义 scheme（mqqapi://、
     * alipays://、weixin:// 之类）只有对应 App 的 Activity 能接，而这些
     * Activity 基本都不声明 browsable —— 于是匹配结果为空，startActivity 抛
     * ActivityNotFoundException，被 catch 成一句「无法打开链接」。
     * 「一键加群」点了没反应，根子就在这里。
     *
     * 自定义 scheme 走不带 category 的匹配；万一还是没人接（比如对应 App 没装），
     * 退化成用浏览器打开原链接，绝不留下「点了没反应」。
     */
    public void openExternal(String url) {
        activity.runOnUiThread(() -> {
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(activity, "链接为空", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                if (isWebUrl(url)) i.addCategory(Intent.CATEGORY_BROWSABLE);
                activity.startActivity(i);
            } catch (Exception e) {
                // 没人接这条 scheme（多半是对应 App 没装）：退回浏览器打开
                try {
                    if (!isWebUrl(url)) {
                        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        web.addCategory(Intent.CATEGORY_BROWSABLE);
                        activity.startActivity(web);
                        return;
                    }
                } catch (Exception ignored) {}
                Toast.makeText(activity, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 是不是 http/https 链接（只有这类才该带 CATEGORY_BROWSABLE） */
    private static boolean isWebUrl(String url) {
        try {
            String s = Uri.parse(url).getScheme();
            return s != null && (s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"));
        } catch (Exception e) {
            return false;
        }
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
     *
     * 注意这条守卫的反面：**读**的时候不能照着这个路径去读。
     * 这里 mkdirs 出来的目录在 Android 10+ 上是应用沙箱内的影子目录，
     * DownloadManager 写的真文件不在里面。所以一切「找已下载的文件」
     * 都得走 {@link DownloadProvider#resolve}，它会去问 DownloadManager。
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
        enqueueDownload(url, filename, headersJson, userAgent, autoInstall, expectedSha, 0);
    }

    private void enqueueDownload(String url, String filename, String headersJson,
                                 String userAgent, boolean autoInstall, String expectedSha,
                                 long expectedBytes) {
        if (url == null || url.isEmpty()) return;
        final boolean allowMirror = !DownloadChannels.hasAuthHeader(headersJson);
        final String sha = expectedSha == null ? "" : expectedSha.trim().toLowerCase();
        final String name = (filename == null || filename.isEmpty()) ? "download" : filename;
        activity.runOnUiThread(() -> {
            try {
                ensureDownloadDir();
                DlTask t = new DlTask(name, url, headersJson, userAgent, sha, autoInstall,
                        candidateUrls(url, allowMirror));
                t.expectedBytes = expectedBytes;
                if (!startTask(t)) {
                    Toast.makeText(activity, "下载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                /* 有多个候选通道时说一声 —— 用户知道「慢了会自动换」就不会
                 * 盯着几十 KB/s 干着急，也不会一失败就以为软件坏了。
                 *
                 * 顺带把「一共几条路」讲清楚：以前只说「慢会自动换道」，
                 * 用户看到「加速 3」还是失败就来问「怎么就这么几条」；
                 * 说成「共同 5 条路可自动切换」才说明白 —— 换道是软件自己
                 * 走完的，不需要用户做任何事。 */
                Toast.makeText(activity, t.urls.size() > 1
                        ? "开始下载 " + name + "（" + t.channel() + "，共 " + t.urls.size() + " 条路可自动切换）"
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
        /*
          第一次发车前先问一句「这个文件多大」。

          两条用途：
            1. 完成时能判断「下全了没有」—— 镜像把请求转到 HTML 错误页、
               或者传到一半断了却报 200，DownloadManager 都会当成成功；
            2. 万一所有通道都拿不到大小，也不影响下载本身（只是少一层校验）。

          只探一次（t.expectedBytes 已知就跳过），并且**只探原始地址**：
          各镜像的文件大小必然一致，没必要为每条通道都发一次请求。
        */
        if (t.expectedBytes <= 0 && t.idx == 0) {
            t.expectedBytes = probeLength(t.originUrl, t.headersJson, t.userAgent);
        }
        long id = safeEnqueue(dm, t, true);
        if (id <= 0) {
            /* 子目录建不起来（个别 ROM 的 DownloadManager 不给建），
             * 退回 Download 根目录再试一次 —— 位置不对也比下不到强。
             *
             * 注意后果：**文件会落在 Download 根目录**，而下载管理里显示的
             * 名字仍是原文件名。所以查找已下载文件一律走 DownloadManager
             * （见 DownloadProvider.resolve），不能按「Download/githup/名字」
             * 去拼 —— 拼出来的路径在这里是不存在的。 */
            id = safeEnqueue(dm, t, false);
        }
        if (id <= 0) return false;
        t.id = id;
        t.startedAt = System.currentTimeMillis();
        t.lastAt = t.startedAt;
        t.lastBytes = 0;
        t.slowStrikes = 0;
        /*
          每次发车（含换道后重发）都要把「观察起点」清干净。
          这两个字段是**每条通道各自**的，不是任务级的 —— 留着上一个通道的值，
          新通道就会带着别人的计时开局：
            firstDataAt 不清 → 宽限期立刻生效，新通道刚连上就被判慢；
            stallAt     不清 → 新通道还在建连就被判「卡死」。
        */
        t.firstDataAt = 0;
        t.stallAt = 0;
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

    /**
     * 问一句「这个文件多大」，只发一个 HEAD，不下载内容。
     *
     * 为什么用 HttpURLConnection 手写而不是复用 Http.java：这里要的是一个
     * **同步**、**不跟随重定向到文件体**的长度值，而且它跑在下载线程上，
     * 不能占着 Http 那套给页面用的连接池 —— 下载在跑的时候页面还要刷数据。
     *
     * 拿不到就返回 0（网络拒了 HEAD、镜像不支持、超时……都算「不知道」），
     * 只是少一层完成校验，不影响下载继续。
     */
    private long probeLength(String url, String headersJson, String userAgent) {
        if (url == null || url.isEmpty()) return 0;
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(url);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestMethod("HEAD");
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(5_000);
            c.setReadTimeout(5_000);
            c.setRequestProperty("User-Agent",
                    (userAgent == null || userAgent.isEmpty()) ? "githup" : userAgent);
            if (headersJson != null && !headersJson.isEmpty()) {
                try {
                    JSONObject jo = new JSONObject(headersJson);
                    Iterator<String> it = jo.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = jo.optString(k, "");
                        if (!v.isEmpty()) c.setRequestProperty(k, v);
                    }
                } catch (Exception ignored) { }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return 0;
            long len = c.getContentLengthLong();
            /*
              有些服务端对 HEAD 回 Content-Length: -1 或 0，但把真实长度放在
              Content-Range 里。这里顺手再找一遍，找不到就算了。
            */
            if (len <= 0) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) {
                        try { len = Long.parseLong(cr.substring(slash + 1).trim()); }
                        catch (Throwable ignored) { }
                    }
                }
            }
            return len > 0 ? len : 0;
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
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

                /*
                  第一滴数据到了才开始计时 —— 这是「宽限期」该有的语义。

                  原来宽限期是从**入队时刻** startedAt 算的，而 DownloadManager
                  的队列排位、DNS、TLS 握手、302 跳转全挤在前几秒里。于是出现
                  这种误判（用真实参数推演过）：

                       t=1.5s  累计 0B      宽限期内，不判
                       t=3.0s  累计 0B      ← 宽限期刚好到点，而这一轮量的是
                                             1.5~3.0s 这个窗口的字节差 = 0，
                                             speed=0 < 15KB/s，判慢
                       t=4.5s  累计 1150KB  speed=766KB/s 好得很 —— 但已经在
                                            3.0s 那拍被切走了

                  一条 500KB/s 的好通道，就因为「前 2.2 秒在建连」被误切。
                  用户看到的就是「明明在下，却弹速度太慢」。

                  改法：第一个字节到达前**永远不判慢**（那是在建连，不是慢），
                  一旦出过数据，就把 firstDataAt 定为当前时刻，
                  宽限期从这一刻起算。真正卡死的通道仍有兜底 —— 见下面的
                  STALL_LIMIT（一个字都不进超过这个时长就换道），
                  所以不会出现「永远等着」。
                */
                if (t.firstDataAt == 0) {
                    if (sofar <= 0) {
                        /*
                          首字节一直不来 —— 这不是「在建连」，是这条通道根本没接上。

                          必须有这个出口：上面那句「没出数据就 continue」如果不配兜底，
                          一条永远连不通的通道会被永久豁免，任务就一直挂着，谁也不管。
                        */
                        if (now - t.startedAt >= CONNECT_LIMIT) switchChannel(t, "连接超时");
                        continue;
                    }
                    t.firstDataAt = now;
                    t.lastAt = now;
                    t.lastBytes = sofar;
                    /* 首字节到达也算「进过账」，否则 stallAt 还停在 0，
                     * 下一拍万一没新数据，now - 0 是个天文数字，会被误判成卡死。 */
                    t.stallAt = now;
                    continue;
                }

                /* 用「这一轮的实测速度」判断：既抓得住完全卡死，
                 * 也抓得住「一直在爬但只有几十 KB/s」这种更气人的情况。 */
                long dt = now - t.lastAt;
                long dB = sofar - t.lastBytes;
                t.lastAt = now;
                t.lastBytes = sofar;
                if (dt <= 0) continue;
                long speed = dB * 1000L / dt;

                /*
                  一个字都不进超过 STALL_LIMIT：这是真卡死，立刻换道。
                  它和「慢」是两回事 —— 慢至少还在动，卡死是一个字节都不来。
                  必须有这条兜底，否则「出过第一个字节之后就再也不判慢」，
                  遇到半死不活的通道会一直挂着。
                */
                if (dB <= 0) {
                    if (now - t.stallAt >= STALL_LIMIT) switchChannel(t, "连接卡住");
                    continue;
                }
                t.stallAt = now;
                if (speed >= MIN_SPEED_BPS) {
                    t.slowStrikes = 0;
                    continue;
                }
                if (now - t.firstDataAt < GRACE_MS) continue;
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
            notifyUpdateAborted("download-failed");     // 撤回「正在装」，别把后续提醒一并压住
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "下载失败：" + n + "（所有通道都试过了，请检查网络）",
                    Toast.LENGTH_LONG).show());
            return;
        }
        t.idx++;
        t.slowStrikes = 0;
        /*
          换到新通道，两个时间戳必须一起归零：
            firstDataAt 不清零 → 新通道一上来就被当成「早就出过数据」，
                                 宽限期立刻生效，等于又回到误判老路；
            stallAt     不清零 → 新通道还在建连，就可能被判成「卡死」。
        */
        t.firstDataAt = 0;
        t.stallAt = 0;
        if (!startTask(t)) {
            addHistory(t, false, bytes);
            final String n = t.filename;
            notifyUpdateAborted("start-failed");
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "下载失败：" + n, Toast.LENGTH_SHORT).show());
            return;
        }
        final String ch = t.channel();
        activity.runOnUiThread(() -> Toast.makeText(activity,
                why + "，已切换到" + ch, Toast.LENGTH_SHORT).show());
    }

    /**
     * 组装下载请求。subDir = true 时落到 Download/githup/ 下。
     */
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
    private static final long WATCH_MS = 1_500;
    /**
     * 连续几轮判定太慢才真的换道（免得刚起步的抖动被误判）。
     *
     * 历史：2 轮 → 1 轮 → 现在回到 2 轮。
     *
     * 压到 1 轮的初衷是「别让用户干等」（当时配 3 秒监控间隔 + 8 秒宽限期，
     * 最坏 14 秒才切走）。但压到 1 轮之后变成**零容错**：镜像的限速本来就是
     * 波动的，某一轮掉到 15KB/s 以下很正常，下一轮又回到 100KB/s。
     * 1 轮就切，等于被抖动牵着鼻子走 —— 用户看到的是「下载好好的突然换道、
     * 进度从头再来」。
     *
     * 现在把「等待」交给监控间隔（1.5 秒）和下面的 STALL_LIMIT 去解决，
     * 这里保留 2 轮做抖动容错：真的要判一条通道的死刑，得连续两轮都不行。
     */
    private static final int SLOW_STRIKES = 2;
    /**
     * 首次出数据前的观察期。
     *
     * 注意语义已经改了：**建连阶段（一个字节都没来）根本不判慢**，
     * 这个宽限期是从「第一个字节到达」那一刻起算的 —— 见 watchTick 里的
     * firstDataAt。所以它现在衡量的是「出过数据之后，允许低速多久才认账」。
     *
     * 原来是 8 秒，后来压到 3 秒。压到 3 秒时配合的是「从入队算起」的老语义，
     * 那正是「明明在下却弹速度太慢」的根源。现在语义对了，3 秒够用：
     * 出过数据还连续 3 秒低于 15KB/s，基本可以判定这条通道被限速了。
     */
    private static final long GRACE_MS = 3_000;
    /**
     * 出过数据之后，**一个字节都不进**超过这个时长就换道。
     *
     * 为什么必须有它：建连阶段不再判慢之后，如果一条通道「发了起始几个包
     * 然后就彻底不动」，就会永远挂着不动。慢（还在爬）和卡死（完全不动）
     * 要分开对待：慢给宽限期，卡死直接切。
     * 8 秒是权衡 —— 短了会误切正在重试的通道，长了用户等得难受。
     */
    private static final long STALL_LIMIT = 8_000;
    /**
     * 从发车算起，多久还拿不到**第一个字节**就认定这条通道连不通。
     *
     * 和 STALL_LIMIT 分工不同：
     *   STALL_LIMIT    —— 出过数据、后来不动了（半路卡死），8 秒；
     *   CONNECT_LIMIT  —— 从头到尾一个字节都没有（压根没连上），15 秒。
     *
     * 15 秒看着长，但这里是「连握手都没完成」的量级：DownloadManager 排队、
     * DNS、TLS、镜像的 302 跳转都算在里面，直连 GitHub 在弱网下十几秒也正常。
     * 反过来，超过 15 秒还没动静的通道基本没有抢救价值，
     * 后面还有 5 条候选，早点让位更划算。
     */
    private static final long CONNECT_LIMIT = 15_000;

    /**
     * 完成时「大小对不上多少才算坏包」。
     *
     * 头部的 Content-Length 与 DownloadManager 统计的字节数偶尔会差一点
     * （重定向后的分块传输、ROM 的统计口径），卡死在完全相等会把好包也拦掉。
     * 32KB 足够区分「差几字节的统计口径」和「少了一多半的残包」。
     */
    private static final long SIZE_TOLERANCE = 32 * 1024;

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
        /**
         * 第一个字节到达的时刻，0 = 还没出过数据。
         *
         * 宽限期（GRACE_MS）从这一刻算起，而不是从入队时刻 startedAt 算起 ——
         * 建连那几秒里 DownloadManager 在排队、握手、追 302，一个字节都没有
         * 属于正常，拿它当「慢」就会把好通道误切掉。
         */
        long firstDataAt = 0;
        /**
         * 上一次「有字节进账」的时刻，用来判真卡死（见 STALL_LIMIT）。
         * 和 lastAt 的区别：lastAt 每轮都更新（用于算窗口速度），
         * 这个只在真的有字节进来时才更新。
         */
        long stallAt = 0;
        /**
         * 这条文件应该有多大（字节），0 = 未知。
         *
         * 用来抓「下完了但它其实是坏的」：镜像把请求 302 到一个 HTML 错误页、
         * 或者传到一半断了却报了 200，DownloadManager 都会当成成功。
         * 不看大小的话，用户拿到的是个装不上的包，还得自己猜为什么。
         */
        long expectedBytes = 0;

        /* 用来给日志/提示标明「这是第几次尝试」 */
        int attempt = 1;

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

    /**
     * 发起一次下载，把 DownloadManager 可能抛的异常收住。
     *
     * enqueue 会因为各种环境原因抛：外部存储没挂载、ROM 定制后拒绝带子目录的
     * 目标路径、DownloadManager 被禁用…… 这些都是「下不了」，不是「软件坏了」，
     * 所以返回 <=0 让上层去试下一条路，别把异常一路抛到 UI 线程炸给用户看。
     */
    private long safeEnqueue(DownloadManager dm, DlTask t, boolean subDir) {
        try {
            return dm.enqueue(buildRequest(t.url(), t.filename, t.headersJson, t.userAgent, subDir));
        } catch (Throwable e) {
            android.util.Log.w("githup", "enqueue 失败", e);
            return -1;
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
     *  1) **SHA-256** —— 调用方给了期望值就比对，确保装的是
     *     清单里写明的那一份，而不是「版本不对 / 被换掉」的包。
     *
     *  （本库是未加固版本：官方签名证书那道校验依赖 githup 才有的
     *   SignCheck / 防护链，这里不做。）
     *
     * 读文件的顺序很关键：**先问 DownloadManager 要地址**，再退回传进来的
     * content:// 地址。以前只走后者，而后者内部是自己拼公共目录路径 ——
     * Android 10+ 分区存储下读不到文件，于是抛 FileNotFoundException，
     * 用户看到「已阻止安装：校验失败：FileNotFoundException」，
     * 而那个包其实是完好的。
     *
     * @param downloadId DownloadManager 的下载 id，用它拿权威地址
     * @param fallback   兜底地址（content:// 形式的 DownloadProvider）
     * @return null 表示可以装；非 null 是拒绝原因（直接展示给用户）
     */
    private String verifySha(long downloadId, Uri fallback, String expected) {
        // --- SHA-256：给了期望值就必须对上 ---
        if (expected == null || expected.trim().isEmpty()) return null;

        Uri src = localUriOf(downloadId);
        if (src == null) src = fallback;

        InputStream in = null;
        Throwable first = null;
        try {
            in = activity.getContentResolver().openInputStream(src);
            /* DownloadManager 给的地址在某些 ROM 上可能打不开，再退兜底地址试一次 */
            if (in == null && fallback != null && !fallback.equals(src)) {
                in = activity.getContentResolver().openInputStream(fallback);
            }
            if (in == null) {
                /* 别把它说成「可能被篡改」—— 校验压根没跑起来，
                 * 两件事完全不同，前者会让人以为是被人动了手脚。 */
                return "读不到下载好的文件（可能已被清理，也可能是系统限制）";
            }
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
            first = t;
            return "读不到下载好的文件：" + t.getClass().getSimpleName();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (first != null) {
                android.util.Log.w("githup", "安装前校验失败", first);
            }
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
        if (t.expectedBytes > 0 && bytes > 0 && bytes != t.expectedBytes) {
            /*
              大小对不上 —— 这不是「下载失败」那么明显，但同样不能用。
              最常见的成因是镜像返回了一段残缺的文件（下到一半断了却报了
              200），或者镜像把请求重定向到了一个 HTML 错误页。这种包
              装下去只会被校验拦下，不如直接换道重下。

              只在「差得离谱」时才换（见 SIZE_TOLERANCE）：服务端给的
              Content-Length 与 DownloadManager 的统计口径偶尔差几字节，
              卡死在完全相等会把好包也拦掉。
            */
            long diff = Math.abs(bytes - t.expectedBytes);
            if (diff > SIZE_TOLERANCE) {
                /* lambda 里用到的都必须是 final / effectively final，
                   所以 LOST 要在外面先取好 */
                final long got = bytes;
                final String ch = t.channel();
                activity.runOnUiThread(() -> switchChannel(t, "文件不完整（" + ch + "）", got));
                return;
            }
        }
        final long size = bytes;
        activity.runOnUiThread(() -> {
            /*
              这条通道跑通了，记下来 —— 下次同网络环境直接先试它。

              但**直连不记**：它是兜底通道，candidates() 里永远排在最后，
              记了也没用；更麻烦的是它会被下一次的「上次通道」逻辑误读成
              「这网络直连能通，优先直连」，而这正是 1.1.4 之前下载变慢的根源。
              只记镜像通道，语义才干净：记的是「哪条加速通」。
            */
            String okChannel = t.channelKey();
            if (!DownloadChannels.DIRECT.equals(okChannel)) saveLastChannel(okChannel);
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
            boolean isZip = name.toLowerCase().endsWith(".zip");

            /* 交给外部（安装器）的地址统一用 content://：
             * DownloadManager 给的 file:// 在 Android 7+ 会被静默拦掉。 */
            Uri uri = DownloadProvider.uriFor(activity.getPackageName(), name);

            /* 校验读文件必须走 DownloadManager 自己的地址，不要手拼公共目录路径 ——
             * Android 10+ 分区存储下那条路径拿不到文件，会把完好的包误判成
             * 「校验失败：FileNotFoundException」而拦下安装。详见 DownloadProvider 注释。 */
            if (exp != null && !exp.isEmpty() && !isZip) {
                String bad = verifySha(t.id, uri, exp);
                if (bad != null) {
                    final String msg = bad;
                    /* 包被拦下了，等于没装成 —— 同样要撤回「正在装」 */
                    notifyUpdateAborted("verify-failed");
                    Toast.makeText(activity,
                            "已阻止安装：" + msg + "。请到下载管理里删除后重试。",
                            Toast.LENGTH_LONG).show();
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

    /** 取某个已完成下载的真实可读地址；拿不到返回 null */
    private Uri localUriOf(long downloadId) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return null;
        try {
            Uri u = dm.getUriForDownloadedFile(downloadId);
            if (u != null) return u;
        } catch (Throwable ignored) { }
        /* 官方 API 在个别 ROM 上会给 null，退回查 _data / local_uri 列 */
        android.database.Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && !s.isEmpty()) return Uri.parse(s);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 删除某个已完成下载产出的文件（走 DownloadManager 的地址，别拼路径） */
    private boolean deleteDownloadedFile(long downloadId, String name) {
        boolean gone = false;
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null && downloadId > 0) {
            /* dm.remove 会把这条记录和它产出的文件一起清掉 */
            try { gone = dm.remove(downloadId) > 0; } catch (Throwable ignored) { }
        }
        /* 再按名字兜一次：老记录（升级前下载的）里没有 dlId，
         * 或者记录已经不在 DownloadManager 里了，就按文件名找 */
        try {
            File f = DownloadProvider.resolve(activity, name);
            if (f != null && f.exists() && f.delete()) gone = true;
        } catch (Throwable ignored) { }
        return gone;
    }

    /** 文件名是否已经落在磁盘上（用来判断「打开」能不能点得动） */
    private boolean downloadedFileExists(String name) {
        try {
            File f = DownloadProvider.resolve(activity, name);
            return f != null && f.exists();
        } catch (Throwable ignored) {
            return false;
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
                /* 记下 DownloadManager 的下载 id：「删除」要按它删才删得掉真文件。
                 * 以前只存文件名，删除时去拼公共目录路径，Android 10+ 上删的是
                 * 沙箱里的影子路径，文件还在原地。 */
                o.put("dlId", t.id);
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
                /* 存在性判断也要走 resolve()：以前用 fileFor() 拼公共目录路径，
                 * Android 10+ 下明明文件在、却报「文件不在了」，点了没反应。 */
                if (!downloadedFileExists(name)) {
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
                /* 按 DownloadManager 的下载 id 删（如果这条记录还带着 id），
                 * 以前那种 fileFor(name).delete() 在 Android 10+ 上删的是
                 * 沙箱里的影子路径，文件纹丝不动。 */
                long dlId = o.optLong("dlId", -1);
                boolean gone = deleteDownloadedFile(dlId, name);
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
            /* 解压都失败了，自然谈不上装上 —— 撤回「正在装」 */
            notifyUpdateAborted("extract-failed");
            toast("解压失败：" + zipName);
            return;
        }
        if (found.isEmpty()) {
            /* 压缩包里没有 APK，安装环节还没开始就断了 */
            notifyUpdateAborted("zip-no-apk");
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
            /* 连安装器都拉不起来（ROM 屏蔽 / 没声明权限），这次更新等于没发生 */
            notifyUpdateAborted("installer-unavailable");
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

    /**
     * 真实的安全区高度（像素），给前端当 --safe-t / --safe-b 用。
     *
     * ⚠️ 为什么必须从原生拿，不能只靠 CSS 的 env(safe-area-inset-top)：
     * Android WebView 对 safe-area-inset-* 的支持**不可靠** ——
     * 它只在特定条件下（viewport-fit=cover + 部分 WebView 版本）才返回非 0，
     * 而且返回的是「刘海高度」而不是「状态栏高度」，两者在大多数机型上并不相等。
     * 实测影响：本应用是 edge-to-edge（SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN），
     * WebView 铺满整个屏幕、含被状态栏盖住的那一条。前端如果拿不到状态栏高度，
     * 顶栏就会被状态栏压住一截，看起来「标题位置不对 / 上面空一大块」。
     *
     * 返回 [状态栏高度, 导航栏高度, 左侧安全区, 右侧安全区]（单位都是设备像素），
     * 失败时 [0,0,0,0]，前端会退回 CSS env()。
     *
     * 后两个值是「适配市面所有机型」补上的：
     *   · 横屏时刘海/挖孔跑到屏幕左右两侧，内容会被挖孔切掉一块；
     *   · 曲面屏（部分魅族、华为）左右本来就有不可触控的弧面；
     *   · 某些 ROM（Flyme 的「隐藏刘海」、MIUI 的「屏幕顶部显示」）会把内容
     *     横向挤进系统区，各家行为不一致，只能量出来交给 CSS 处理。
     */
    @JavascriptInterface
    public String safeInsets() {
        int top = 0, bottom = 0, left = 0, right = 0;
        try {
            android.content.res.Resources r = activity.getResources();
            int idTop = r.getIdentifier("status_bar_height", "dimen", "android");
            if (idTop > 0) top = r.getDimensionPixelSize(idTop);
            /* 导航栏：优先用 insets 拿真实值，拿不到再退回资源里的高度。
               注意「手势导航」下导航栏高度是很小的（几 dp），不能写死 48dp。 */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsets ins =
                        activity.getWindow().getDecorView().getRootWindowInsets();
                if (ins != null) {
                    // getInsets 已废弃但兼容面最广，这里做一次防御性兜底
                    top = ins.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    bottom = ins.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    /* 左右：把 systemBars 和 displayCutout 一起算进来 ——
                       只算 systemBars 的话，横屏刘海那一条会漏掉。 */
                    android.graphics.Insets side = ins.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                    left = side.left;
                    right = side.right;
                }
            } else {
                int idBot = r.getIdentifier("navigation_bar_height", "dimen", "android");
                if (idBot > 0) bottom = r.getDimensionPixelSize(idBot);
            }
        } catch (Throwable ignored) {
        }
        return "[" + top + "," + bottom + "," + left + "," + right + "]";
    }

    @JavascriptInterface
    public void setStatusBar(String colorHex) {
        activity.runOnUiThread(() -> {
            try {
                /* ⚠️ 传进来的实色会被**忽略**，状态栏/导航栏一律保持透明。
                 *
                 * 原因：状态栏是盖在 WebView 之上的一条系统绘制区。给它上实色，
                 * 就等于在页面顶部贴了一条不透明的色带 —— 顶栏的毛玻璃再怎么调，
                 * 上面那一条永远是死白/死黑，看起来就是「标题被顶下去、上方空一块」。
                 * 透明之后露出来的是 WebView 里顶栏自己的玻璃，玻璃才能一直糊到
                 * 屏幕最顶上（iOS 就是这个行为）。
                 *
                 * 参数保留不删：前端还在按主题传 #ffffff / #010409，只是不再采用。
                 * 这里仍然按颜色算一次明暗，用来决定状态栏图标是深色还是浅色。 */
                int color = android.graphics.Color.parseColor(colorHex);
                boolean light = isLightColor(color);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    View decor = activity.getWindow().getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    /* 保持 edge-to-edge：内容继续延伸到状态栏底下，
                       否则透明状态栏会让顶栏整体下移，又变成「上面空一块」。 */
                    flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                    decor.setSystemUiVisibility(flags);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
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

    /**
     * 系统当前是否处于深色模式。
     *
     * 为什么需要这个接口：前端「跟随系统」原来只靠 CSS 的
     * prefers-color-scheme 媒体查询判断，但在 Android WebView 里这个查询
     * 并不可靠 —— 它受 WebSettings 的 force-dark / algorithmic-darkening
     * 影响，部分机型/WebView 版本下会一直返回 light，或者反过来一直返回
     * dark，导致「系统浅色、App 里却按深色渲染」这种错位。
     * Configuration.uiMode 是系统给的权威值，不受 WebView 配置干扰，
     * 所以把它作为「跟随系统」的唯一依据交给前端。
     */
    @JavascriptInterface
    public boolean systemDark() {
        try {
            int mode = activity.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Exception e) {
            return false;
        }
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
