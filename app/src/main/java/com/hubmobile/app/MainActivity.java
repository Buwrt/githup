package com.hubmobile.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * 主界面：以 WebView 承载前端单页应用，并通过 JsBridge 提供原生能力。
 */
public class MainActivity extends Activity {

    /** 首页地址：渲染进程崩溃后重建时也要回到这里 */
    private static final String HOME_URL = "file:///android_asset/web/index.html";

    private WebView webView;
    private JsBridge bridge;
    /** README 图片代理：只造一次，渲染进程重建时它和里面的缓存都还在 */
    private ImageProxy imageProxy;
    /** WebView 挂在这一层上。留成字段是为了渲染进程没了之后能换一个新的上去 */
    private FrameLayout contentRoot;
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

        contentRoot = new FrameLayout(this);
        contentRoot.setBackgroundColor(Color.WHITE);
        contentRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(contentRoot);

        attachWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    /**
     * 造一个 WebView 挂到底层上 —— 首次进入和渲染进程崩溃后的重建都走这里。
     *
     * 为什么不直接 `new WebView()` 完事：配置项、三个 Client、JsBridge 这套东西
     * 加起来几十行，写两遍必然某一边漏掉一样，而那种漏法只有在崩溃恢复之后
     * 才看得出差别，最难复查。所以只能有一条「装一个完整的 WebView」的路。
     */
    /**
     * 取图片代理（顺手创建）。
     *
     * 令牌是延迟取值的：用户可能先进 App 随便逛逛，之后再登录 ——
     * 要是构造时把令牌抄成字符串存起来，之后代理拿到的永远是空。
     */
    private ImageProxy imageProxy() {
        if (imageProxy == null) {
            imageProxy = new ImageProxy(getApplicationContext(),
                    () -> bridge == null ? "" : bridge.getToken());
        }
        return imageProxy;
    }

    private void attachWebView() {
        WebView dead = webView;
        WebView v = new WebView(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applySettings(v.getSettings());

        if (bridge == null) bridge = new JsBridge(this, v);
        else bridge.reattach(v);          // 别再造一个：那等于再泄漏一份线程池
        bridge.setImageProxy(imageProxy());
        v.addJavascriptInterface(bridge, "NativeBridge");

        v.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                /* file:// 只放行 SPA 入口本身。README 里漏网的相对链接会被
                 * WebView 拿页面地址解析成 file:///android_asset/web/README.zh-CN.md
                 * 之类 —— 那个文件不存在，导航过去唯一能等来的就是
                 * onReceivedError 里那趟整页重载，用户看到的正是
                 * 「点一下 README 里的语言切换，整个软件重启」。
                 * JS 层（md.js 的 normalizeLink）负责从源头堵；这里是最后
                 * 一道闸：真漏进来了，吃掉这一次导航就好，别让它翻车。 */
                if (url.startsWith("file://")) {
                    return !url.startsWith(HOME_URL);
                }
                if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return false;
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
                /* 只有 SPA 入口自己加载失败才值得整页重载。这个老签名对
                 * 每个子资源都会回调一次 —— README 里一张裂图、一个漏网的
                 * file:// 链接都算 —— 以前只要 failingUrl 落在 android_asset
                 * 下就重载首页，正好把「点坏链接」放大成「整个软件重启」。
                 * 子资源失败交给各自的兜底链（裂图样式、base64 重试），不动全局。 */
                if (failingUrl != null && failingUrl.startsWith(HOME_URL)) {
                    view.postDelayed(() -> view.loadUrl(HOME_URL), 500);
                }
            }

            /**
             * 渲染进程没了。
             *
             * WebView 的内容跑在一个独立的进程里，内存吃紧时被系统直接回收
             * 是很正常的事 —— 偏偏这个 App 的搜索结果缓存以往会一路涨，
             * 正好把渲染堆喂到那条线上（见 page-home.js 里 SEARCH_STATE 的注释）。
             * 进程一死，画面就停在**最后一帧**：排版、颜色、内容全都在，
             * 就是点什么都没反应、也不再重绘 —— 用户讲的「软件变成图片的样子」
             * 就是它。
             *
             * 不接这个回调的话，Java 层对此一无所知：Activity 活着、各个
             * 变量也都正常，于是它就那么一直卡在那儿，只能强杀重开。
             * 这里换成一个新的 WebView 重新加载首页 —— 前端的令牌、主题、
             * 路由本来就存在 localStorage 里，重载之后照样回到原来的地方。
             */
            /**
             * README 图片的快车道（详见 ImageProxy 的类注释）。
             *
             * WebView 每次要取一张网络图片都会先问这里一句。答得上来，
             * 字节就直接递过去 —— 不用跑那趟「base64 → Binder → JS → data URI」
             * 的冤枉路；答不上来（拿不到、不是图、出了岔子）就返回 null，
             * WebView 会照原样自己去取，用户的观感毫无变化。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                ImageProxy p = imageProxy();
                if (p == null) return null;
                return p.intercept(request);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return onRendererGone(view, detail);
            }
        });

        v.setWebChromeClient(new WebChromeClient() {
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

        v.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
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
        });

        // 新的先挂上去，死掉的那个再摘下来销毁 —— 中间不留空白
        contentRoot.addView(v);
        if (dead != null) {
            try {
                contentRoot.removeView(dead);
                dead.destroy();
            } catch (Throwable ignored) {
            }
        }
        webView = v;
    }

    /**
     * 渲染进程没了之后的收尾。返回 true 表示「这事我处理了」，
     * 系统就不会再按自己的方式去弹那套崩溃提示。
     */
    private boolean onRendererGone(WebView dead, RenderProcessGoneDetail detail) {
        try {
            Log.w("githup", "WebView 渲染进程退出（crash=" +
                    (detail != null && detail.didCrash()) + "），正在重建");
        } catch (Throwable ignored) {
        }
        try {
            attachWebView();
            webView.loadUrl(HOME_URL);
            Toast.makeText(this, "页面被系统回收了，正在重新加载", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            finish();       // 实在起不来：干脆收掉，别留一张不会动的图
        }
        return true;
    }

    /** WebView 的一整套设置（新装的、重建的都用这套，不能有第二份） */
    private void applySettings(WebSettings s) {
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
        /* 关掉 WebView 的「算法深色化」。
           这个开关是真机反馈「系统跟随的是浅色，底栏却渲染成深色」的根因：
           WebView 会在系统处于深色时，把整个页面**自动反色**（不是读我们的 CSS，
           而是自己算一套深色），于是我们的浅色玻璃面被强行改成深色，
           而页面里已经渲染好的浅色文字/卡片又不会同步变 —— 结果就是错乱。
           前端本身就是「自己管深色」的：applyTheme() 会按系统深浅给
           <html data-theme> 赋值，深浅两套变量都是手写的。
           所以正确做法是让 WebView 别插手，完全交给 CSS。
           33 起用 setAlgorithmicDarkeningAllowed，更早的版本用 setForceDark。 */
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(false);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {
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
     * 签名对不上时的收尾：说清楚发生了什么，给出官方下载地址，然后停住。
     *
     * 用对话框而不是跳一个新 Activity —— 本库是未加固版，不想为这一条分支
     * 再引入一个界面类。效果一样：不加载任何内容，也不给「继续用」的余地
     * （点遮罩和返回键都退不掉，只有「去下载官方版」这一条路）。
     */
    private void showUnofficialBuild() {
        String actual = SignCheck.signingSha256(this);
        String shortActual = (actual == null || actual.length() < 16)
                ? "（读不到）" : actual.substring(0, 16) + "…";
        try {
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                    .setTitle("不是官方版本")
                    .setMessage("这个安装包的签名与官方不一致，可能是被别人改过之后重新打包的。\n\n"
                            + "为了保护你的 GitHub 令牌和账号数据，应用不会继续运行。\n\n"
                            + "当前签名：" + shortActual + "\n"
                            + "官方签名：" + SignCheck.officialCertSha256().substring(0, 16) + "…")
                    .setCancelable(false)
                    .setPositiveButton("去下载官方版", (dlg, which) -> {
                        openExternal("https://github.com/Buwrt/githup/releases/latest");
                        finish();
                    })
                    .setNegativeButton("退出", (dlg, which) -> finish())
                    .create();
            d.setCanceledOnTouchOutside(false);
            d.show();
        } catch (Throwable t) {
            finish();
        }
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

    /**
     * 打开外链（页面内的 http/https 跳转、mailto:、tel: 都会走到这里）。
     *
     * CATEGORY_BROWSABLE 只对 http/https 加：它是「浏览器可安全打开的网页」
     * 这一层过滤，加在自定义 scheme 上会让系统匹配不到任何 Activity，
     * 直接抛 ActivityNotFoundException（表现就是点了没反应 / 「无法打开链接」）。
     * mailto:、tel: 这类非网页 scheme 同样不能带。
     */
    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
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
