package com.hubmobile.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 主界面：以 WebView 承载前端单页应用，并通过 JsBridge 提供原生能力。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private JsBridge bridge;
    private long lastBackPressed = 0;
    // 视频全屏时挂在窗口上的自定义视图（见 enterFullscreen / exitFullscreen）
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 埋点一：进主界面前跑一遍防护链。
        // 不通过就直接跳到「强制下载官方版」的页面，这里一行都不往下走。
        if (!guardPassed()) return;

        /* 过了卡点再预热 DNS。
         *
         * 首屏那几个请求都要先解析 api.github.com，运营商 DNS 动辄上百毫秒，
         * 几个域名串起来够呛。这里在后台线程先解析一轮，
         * 等用户点开列表时基本都是白捡的。失败无所谓，该解析时还会解析。
         *
         * 这一行曾经没能跟着过到这个库来（加固埋点那段把它顶掉了），
         * 于是同一个版本比另一边的库慢一截 —— DNS 全靠现等。
         * 「加载特别慢」有一部分就出在这儿。 */
        Http.warmUp();

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        // 视频：README / issue 里上传的 mp4 现在会内嵌成 <video> 播放。
        // 关掉「必须用户手势才允许播放」，否则部分机型上点了播放键也没反应。
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setDefaultTextEncodingName("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        bridge = new JsBridge(this, webView);
        webView.addJavascriptInterface(bridge, "NativeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file:///android_asset/") || url.startsWith("about:")) return false;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    openExternal(url);
                    return true;
                }
                if (url.startsWith("mailto:") || url.startsWith("tel:")) {
                    openExternal(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith("file:///android_asset/")) {
                    // 本地资源加载失败：重试一次
                    view.postDelayed(() -> view.loadUrl("file:///android_asset/web/index.html"), 500);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // 前端自行管理加载指示
            }

            /* 视频全屏：不接这两个回调的话，点 <video> 右下角的全屏按钮
             * 会没反应（或者黑屏一片），因为没人把自定义视图挂到窗口上。 */
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                enterFullscreen(view, cb);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                String name = "download";
                try {
                    if (contentDisposition != null && contentDisposition.contains("filename=")) {
                        name = contentDisposition.split("filename=")[1].replace("\"", "").trim();
                    } else {
                        name = Uri.parse(url).getLastPathSegment();
                    }
                } catch (Exception ignored) {
                }
                try {
                    /* 统一交给 JsBridge：加速通道、卡住/失败自动换道、落盘位置
                     * （Download/githup/）、进度条登记全在那边一份实现，
                     * 跟前端主动调的下载走的是同一条路。 */
                    if (bridge != null) bridge.enqueueWebViewDownload(url, userAgent, name);
                    else openExternal(url);
                } catch (Exception e) {
                    openExternal(url);
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/web/index.html");
        }
    }

    /**
     * 签名不对时的收场：弹一句人话，然后退出。
     *
     * 不写「检测到破解」这种对抗性文案 —— 用户是无辜的，他可能只是
     * 从某个第三方渠道下到了被改过的包。告诉他去哪儿拿正版就行。
     */
    /**
     * 跑一遍防护链。不通过就把用户送到「只能下载官方版」的页面。
     *
     * 这里额外再跑一次 Guard.verify 而不只是读 App 里的缓存结果 ——
     * 就算有人把 Application 的埋点摘掉了，这个入口照样拦得住。
     */
    private boolean guardPassed() {
        Guard.Result r = Guard.verify(this);
        if (!r.ok) {
            App.sBrokenRing = r.brokenRing;
            App.sBrokenDetail = r.detail;
            App.sBrokenCode = r.code;
        } else {
            App.check();   // 顺手同步一次全局状态
            if (!App.passed()) {
                r = new Guard.Result(false, App.sBrokenRing, App.sBrokenDetail, App.sBrokenCode);
            }
        }
        if (!r.ok) {
            App.goBlocked(this);
            finish();
            return false;
        }
        return true;
    }

    /**
     * 进入视频全屏。不接 WebChromeClient 那两个回调的话，点 <video> 的
     * 全屏按钮会毫无反应 —— 因为没人把这块视图挂到窗口上。
     */
    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = view;
        customCb = cb;
        getWindow().addContentView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 看视频时别中途熄屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        View v = customView;
        customView = null;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // 只有 http/https 才加 CATEGORY_BROWSABLE；mailto:、tel: 这类自定义
            // scheme 加了会把能接它的 App 过滤光，最后抛 ActivityNotFoundException。
            if (isWebUrl(url)) i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) {
            try {
                if (!isWebUrl(url)) {
                    Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    web.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(web);
                    return;
                }
            } catch (Exception ignored) {}
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FilePick.REQ_PICK && bridge != null) {
            if (resultCode == RESULT_OK) bridge.onPickResult(resultCode, data);
            else bridge.onPickCancel();
        }
    }

    /**
     * 媒体权限申请结果：不管给没给，都把文件选择器打开。
     * 给了 —— 系统相册 / 照片选择器能看到全部照片；
     * 没给 —— ACTION_OPEN_DOCUMENT 也能逐张挑，只是相册预览可能是空的。
     * 绝不能因为「用户拒绝权限」就什么都不发生，那样等于点了没反应。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == JsBridge.REQ_MEDIA_PERM && bridge != null) {
            bridge.onMediaPermissionResult();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 埋点二：从后台切回来再验一次（防运行中被注入替换）
        Guard.Result r = Guard.verify(this);
        if (!r.ok) {
            App.sBrokenRing = r.brokenRing;
            App.sBrokenDetail = r.detail;
            App.sBrokenCode = r.code;
            App.goBlocked(this);
            finish();
            return;
        }
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{if(window.AppOnResume)window.AppOnResume();}catch(e){}})()", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.evaluateJavascript(
                "(function(){try{window.__paused=true;}catch(e){}})()", null);
    }

    @Override
    public void onBackPressed() {
        /*
          统一交给前端处理：关弹层 -> 关图片查看器 -> 路由回退。
          前端返回 handled=true 表示这一层已被消费，不需要再走原生的回退；
          返回 false 说明已经在栈底，此时按「再按一次退出」处理。

          用 evaluateJavascript 的异步回调而不是同步返回值，因为
          JavascriptInterface 只能用 @JavascriptInterface 方法，
          拿不到即时的布尔结果。
        */
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // 视频全屏中：返回键先退出全屏，而不是把整个页面退掉
        if (customView != null) {
            exitFullscreen();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return !!(window.App&&App.handleBack&&App.handleBack());}"
                        + "catch(e){return false;}})()",
                value -> {
                    boolean handled = "true".equals(value);
                    if (handled) return;
                    confirmExit();
                });
    }

    /** 已在最底层：两次返回键间隔 2 秒内才真正退出，避免误触。 */
    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressed < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressed = now;
        Toast.makeText(this, "再按一次退出 githup", Toast.LENGTH_SHORT).show();
    }

    /*
      不再重写 onKeyDown 去转发 KEYCODE_BACK。
      系统在按下返回键时本来就会调用 onBackPressed()，如果 onKeyDown 里
      再手动调一次，返回逻辑会被触发两遍（弹层关掉的同时又回退了一层路由）。
      统一交给 onBackPressed 处理即可。
    */
}
