package com.hubmobile.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 应用内浏览器。用于在 App 内部打开 GitHub 页面（如「创建访问令牌」）。
 *
 * 阶段 AJ18 订正：旧注释说「与主界面共享 Cookie，登录态可复用」是错的。
 * 主界面 WebView 只加载 file:///android_asset 的本地页面，从来没有
 * github.com 的会话 Cookie；App 登录用的是 Keystore 里的 PAT / OAuth
 * access_token，走 OkHttp 的 Authorization 头，与浏览器 Cookie 是两套。
 * 所以这里打开 github.com/settings/tokens/new 一定未登录 → 302 到 /login
 * → 用户得在 WebView 里再登一次（含 2FA / 设备验证），这就是「特别慢」
 * 的最大放大器。RFC 8252 也要求原生 App 的登录/授权走系统浏览器，
 * 不要内嵌 WebView（共享系统会话、能显示真实域名与证书）。
 *
 * 因此：令牌创建页默认改走系统浏览器（见 page-home.js）；本 Activity
 * 仍保留应用内入口，但补齐错误页、超时提示、重试与一键外跳，
 * 不再出现「进度条卡在 10% + 整屏白」。
 */
public class WebViewActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView webView;
    /** 当前地址：渲染进程崩溃后重建要用它把页面拉回来 */
    private String currentUrl;
    private ProgressBar bar;
    private TextView titleView;
    private TextView urlView;
    private FrameLayout wrap;
    // 视频全屏（同 MainActivity：不接回调，点全屏按钮就没反应）
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    /* 阶段 AJ18：加载失败/超时的可见兜底。 */
    private String originalUrl;
    private boolean pageFinished = false;
    private View errorView;
    private TextView hintView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable slowHint = new Runnable() {
        @Override public void run() {
            if (!pageFinished && errorView == null && hintView != null) {
                hintView.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) title = "浏览器";
        if (TextUtils.isEmpty(url)) { finish(); return; }

        // ---- 顶部工具条 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar1 = new LinearLayout(this);
        bar1.setOrientation(LinearLayout.HORIZONTAL);
        bar1.setGravity(Gravity.CENTER_VERTICAL);
        bar1.setBackgroundColor(0xFF1F2328);
        int pad = dp(12);
        bar1.setPadding(pad, dp(10), pad, dp(10));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(18);
        close.setPadding(dp(8), dp(4), dp(16), dp(4));
        close.setOnClickListener(v -> finish());
        bar1.addView(close);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titles.setLayoutParams(tp);

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);

        urlView = new TextView(this);
        urlView.setTextColor(0xFFB0B7BF);
        urlView.setTextSize(10);
        urlView.setSingleLine(true);
        urlView.setEllipsize(TextUtils.TruncateAt.END);
        urlView.setText(url);
        titles.addView(urlView);

        bar1.addView(titles);

        TextView external = new TextView(this);
        external.setText("↗");
        external.setTextColor(Color.WHITE);
        external.setTextSize(16);
        external.setPadding(dp(16), dp(4), dp(4), dp(4));
        external.setOnClickListener(v -> openExternalBest());
        bar1.addView(external);

        root.addView(bar1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        /* 20 秒还没加载完：给一条不挡内容的提示，用户能立刻切系统浏览器。
           不强制中断——github.com 网页在国内偶尔就是慢，不是失败。 */
        hintView = new TextView(this);
        hintView.setText("加载较慢 · 点此用系统浏览器打开");
        hintView.setTextColor(Color.WHITE);
        hintView.setBackgroundColor(0xFF9A6700);
        hintView.setTextSize(12);
        hintView.setPadding(dp(12), dp(7), dp(12), dp(7));
        hintView.setVisibility(View.GONE);
        hintView.setOnClickListener(v -> openExternalBest());
        root.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 埋点三：应用内浏览器也要过一遍（它是 JS 桥之外的另一个入口）
        Guard.Result g = Guard.verify(this);
        if (!g.ok) {
            App.sBrokenRing = g.brokenRing;
            App.sBrokenDetail = g.detail;
            App.sBrokenCode = g.code;
            LogBook.error(this, "打开内置浏览器时校验没通过",
                    "第 " + g.brokenRing + " 环，" + g.detail + "（代码 " + g.code + "）");
            App.goBlocked(this);
            finish();
            return;
        }

        // ---- WebView ----
        /* 整套配置收在 makeWebView() 里：渲染进程被回收之后要按同一套规格
         * 再做一个顶上去，写两遍必然某一边漏一样。 */
        webView = makeWebView();

        wrap = new FrameLayout(this);
        wrap.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(wrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        originalUrl = url;
        currentUrl = url;
        webView.loadUrl(url);
        ui.postDelayed(slowHint, 20000);
    }

    /** 配好一个完整的 WebView（首次进入和崩溃后重建都走这一条路） */
    private WebView makeWebView() {
        WebView webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        /* GitHub 网页里不少按钮是 window.open（登录弹窗、帮助卡片）。
           不接住的话点了没反应，看起来就像「加载失败」。 */
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        /* 同 MainActivity：关掉 WebView 的算法深色化。
           这里的页面是我们自己排版的（README / issue 等），深色由 CSS 自己管，
           让 WebView 再反色一次只会把配色弄乱。 */
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(false);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {
        }

        webView.setWebChromeClient(new WebChromeClient() {
            /** window.open：把子窗口要去的地址接回当前页面，别让弹窗石沉大海。 */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                try {
                    WebView child = new WebView(WebViewActivity.this);
                    child.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                            loadIntoMain(r.getUrl().toString()); return true;
                        }
                        @Override public boolean shouldOverrideUrlLoading(WebView v, String u) {
                            loadIntoMain(u); return true;
                        }
                    });
                    WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                    t.setWebView(child);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable t) {
                    com.hubmobile.app.Diag.caught(com.hubmobile.app.Diag.NATIVE, t);
                    return false;
                }
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (bar == null) return;
                bar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                bar.setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String t) {
                if (titleView != null && !TextUtils.isEmpty(t)) titleView.setText(t);
            }
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v;
                customCb = cb;
                if (webView != null) webView.setVisibility(View.GONE);
                if (wrap != null) {
                    wrap.addView(v, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                }
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return handle(view, u);
            }

            /** 阶段 AI（四轮审计修复） B9：外链明文自动升级 https，避免降级 / MITM。
             *  升级后的 https 地址会再走一次本回调并放行，不会递归。
             *  阶段 AJ18：intent:/market:/mailto:/tel: 交系统处理，别静默吞掉。 */
            private boolean handle(WebView view, String u) {
                if (u == null) return true;
                if (u.startsWith("http://")) {
                    view.loadUrl("https://" + u.substring(7));
                    return true;
                }
                if (u.startsWith("https://")) return false;
                if (u.startsWith("intent:") || u.startsWith("market:")
                        || u.startsWith("mailto:") || u.startsWith("tel:")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                    } catch (Throwable t) {
                        com.hubmobile.app.Diag.caught(com.hubmobile.app.Diag.NATIVE, t);
                    }
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String u, android.graphics.Bitmap favicon) {
                pageFinished = false;
                hideError();
            }

            /** 主框架加载失败：以前这里什么都不接，进度条停在 10%、整屏白。 */
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    String d = "未知错误";
                    try { if (Build.VERSION.SDK_INT >= 23 && err != null) d = err.getDescription().toString(); }
                    catch (Throwable ignored) { }
                    fail("网页加载失败", d);
                }
            }
            @Override public void onReceivedError(WebView view, int code, String d, String failingUrl) {
                fail("网页加载失败", d + "（" + code + "）");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
                if (req != null && req.isForMainFrame() && resp != null) {
                    int st = 0;
                    try { if (Build.VERSION.SDK_INT >= 21) st = resp.getStatusCode(); } catch (Throwable ignored) { }
                    if (st >= 400) fail("服务器返回 " + st, "GitHub 网页暂时不可用，可重试或改用系统浏览器");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError err) {
                try { handler.cancel(); } catch (Throwable ignored) { }
                fail("证书校验失败", "连接不安全，已停止加载");
            }

            /**
             * 渲染进程没了（为什么一定要接这个回调，见 MainActivity 同名方法
             * 里的说明）。这里按同一套规格换一个新的 WebView 重新打开当前
             * 地址；实在起不来就直接收掉，不留一张不会动的图。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return onRendererGone(view);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                pageFinished = true;
                ui.removeCallbacks(slowHint);
                if (hintView != null) hintView.setVisibility(View.GONE);
                if (urlView != null) urlView.setText(u);
                // 记下来：崩溃重建时要回到当时正在看的那一页，而不是最初的地址
                if (!TextUtils.isEmpty(u)) currentUrl = u;
            }
        });

        return webView;
    }

    private void loadIntoMain(String u) {
        if (u != null && u.startsWith("https://") && webView != null) {
            currentUrl = u;
            webView.loadUrl(u);
        }
    }

    /** 右上角 ↗ / 顶部提示条：优先当前地址，卡在首帧时 getUrl() 可能是 null。 */
    private String bestUrl() {
        String u = webView != null ? webView.getUrl() : null;
        if (TextUtils.isEmpty(u)) u = currentUrl;
        if (TextUtils.isEmpty(u)) u = originalUrl;
        return u;
    }

    private void openExternalBest() {
        String u = bestUrl();
        if (TextUtils.isEmpty(u)) { toast("地址还没准备好，稍等一下再点"); return; }
        try {
            startActivity(Intent.createChooser(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(u)), "选择浏览器打开"));
        } catch (Throwable t) {
            com.hubmobile.app.Diag.caught(com.hubmobile.app.Diag.NATIVE, t);
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
            catch (Throwable t2) { toast("没有可用的浏览器"); }
        }
    }

    private void fail(String title, String detail) {
        try { LogBook.error(this, "内置浏览器：" + title, detail + " | " + bestUrl()); }
        catch (Throwable ignored) { }
        if (errorView != null || wrap == null) return;
        pageFinished = true;
        ui.removeCallbacks(slowHint);
        if (hintView != null) hintView.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(36), dp(24), dp(24));

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(0xFF1F2328);
        h.setTextSize(17);
        h.setGravity(Gravity.CENTER);
        box.addView(h);

        TextView d = new TextView(this);
        d.setText(detail + "\n\n" + bestUrl());
        d.setTextColor(0xFF656D76);
        d.setTextSize(13);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, dp(10), 0, dp(18));
        box.addView(d);

        box.addView(makeBtn("重新加载", v -> { hideError(); pageFinished = false;
            if (webView != null) webView.loadUrl(bestUrl()); ui.postDelayed(slowHint, 20000); }));
        box.addView(makeBtn("用系统浏览器打开（推荐）", v -> openExternalBest()));
        box.addView(makeBtn("复制当前链接", v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", bestUrl()));
                toast("链接已复制");
            } catch (Throwable ignored) { }
        }));

        errorView = box;
        if (webView != null) webView.setVisibility(View.GONE);
        wrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button makeBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void hideError() {
        if (errorView == null) return;
        try { if (errorView.getParent() != null) ((ViewGroup) errorView.getParent()).removeView(errorView); }
        catch (Throwable ignored) { }
        errorView = null;
        if (webView != null) webView.setVisibility(View.VISIBLE);
    }

    private void toast(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }

    private boolean onRendererGone(WebView dead) {
        try {
            WebView fresh = makeWebView();
            if (wrap == null) return true;
            wrap.removeView(dead);
            wrap.addView(fresh, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            webView = fresh;
            if (!TextUtils.isEmpty(currentUrl)) fresh.loadUrl(currentUrl);
            try { dead.destroy(); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            com.hubmobile.app.Diag.caught(com.hubmobile.app.Diag.NATIVE, t);
            finish();
        }
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {           // 视频全屏中：先退出全屏
            exitFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        View v = customView;
        customView = null;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        if (webView != null) webView.setVisibility(View.VISIBLE);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(slowHint);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
