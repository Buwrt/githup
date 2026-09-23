package com.hubmobile.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebView 的图片通道。
 *
 * ============================================================
 * 为什么要这个东西 —— 「README / 议题里的图片加载特别慢」
 * ============================================================
 *
 * 以前 WebView 里的 <img> 是**它自己去拉**的：
 *
 *   1. WebView 用自己的网络栈直连 raw.githubusercontent.com /
 *      avatars.githubusercontent.com —— 这套栈不走我们那条能连通的链路，
 *      在国内网络下常常是几十秒的超时，最后还失败；
 *   2. 于是 md.js 又给每张图排了一次「原生通道」的队：Native.httpB64 拉字节
 *      → base64（体积 +33%）→ 整块字符串跨过 JS 桥 → 拼成 data URI → 塞回 src。
 *      也就是说**同一张图被下载了两遍**，第二遍还要过一遍桥，
 *      而且并发只有 4 —— README 里十来张图就得排好几轮队；
 *   3. 不在这条路上的图（头像、列表里的徽标）连这个兜底都没有，只能干等。
 *
 * 所以慢不是「图太大」，是**每张图都在绕远路，还绕两次**。
 *
 * 这里的做法是把 WebView 的图片请求直接接管过来（shouldInterceptRequest）：
 * 字节走 Http 那条原生栈（连接复用、gzip、跨主机跳转摘凭据都在那儿），
 * 拿回来直接交给渲染器 —— 不编码、不过桥、不下载两遍，
 * 另外再加一层内存 + 磁盘缓存，第二次看同一个 README 是本地直出。
 *
 * 接管失败（不是图片、视频、带 Range 的分片请求、网络真的不通）一律返回 null，
 * 让 WebView 按原来的方式自己处理 —— 绝不比改之前更差。
 */
public final class WebImageProxy {

    private static final String TAG = "WebImg";

    /** 内存缓存上限。缩略图一张几十 KB，24MB 能放几百张。 */
    private static final int MEM_MAX = 24 * 1024 * 1024;

    /* ================= 缩图：让「下载」变快的唯一有效手段 =================
     *
     * 前面几轮把路修通了（不裂、不下两遍、有缓存），但路上跑的一直是**原图**：
     * 一张 984×1398 的赞赏码原图 426,943 字节，而它在手机上一屏也就 360~411
     * 个点宽。实测同一张图压成 WebP 后的体积：
     *
     *   原图            426,943 B   100%
     *   1080 宽          58,930 B    13.8%
     *    720 宽          41,468 B     9.7%
     *    360 宽          24,156 B     5.7%
     *
     * 带宽是改不了的，能改的只有「搬多少货」。所以这里在字节交给渲染器之前
     * 先按屏幕需要的尺寸缩一遍，再压成 WebP —— 正文只拿缩略图，
     * 通常只剩原来的十分之一，「下载」这一步自然就快了。
     *
     * 缓存是分开的两份，键不同；点开大图时走「原图」那一份，而原图在当初下
     * 缩略图时就顺手存了 —— 所以放大是读盘，不是重新下载。
     *
     * 只处理 PNG / JPEG / 静态 WebP / BMP：GIF 和动画 WebP 一缩就只剩第一帧，
     * SVG 没有位图可缩。这几类（以及本来就不大的图）原样放行，不会比现在更差。
     */
    /** 缩略图最宽就这么宽；再宽屏幕上也放不下，纯属白下 */
    private static final int THUMB_MAX_W = 1080;
    /** 再窄也不能小于这个，否则小屏机上看着发糊 */
    private static final int THUMB_MIN_W = 480;
    /** 小于这个体积不值得动（省下来的还不够付一次解码的 CPU） */
    private static final int THUMB_MIN_BYTES = 12 * 1024;
    /** 超过这个体积的原图不解码，免得把内存顶爆 */
    private static final int THUMB_MAX_SRC = 8 * 1024 * 1024;
    private static final int THUMB_QUALITY = 82;
    /* ================= 通道：图走「哪条路」比「搬多少」更要命 ============
     *
     * 上一轮把字节压到十分之一，方向没错，但那有个前提 —— 字节得先取得到。
     * 实测同一张 427 KB 的图（国内网络，2026-09）：
     *
     *   直连 raw.githubusercontent.com      超时，0 字节   ← 根本不通
     *   直连 avatars.githubusercontent.com   超时，0 字节
     *   直连 camo.githubusercontent.com      超时，0 字节
     *   加速 gh-proxy.com                    首字节 0.63s，0.89s 取完
     *   加速 ghfast.top                      首字节 0.74s，1.92s 取完
     *   加速 ghproxy.imciel.com              首字节 1.09s，1.91s 取完
     *
     * 也就是说：**GitHub 这几台图床直连一律不通**，缩略图再小也没用，
     * 因为字节压根回不来。用户看到的「慢」，其实是「每张图都在等直连超时」。
     *
     * 所以第一件事是让它走加速镜像 —— 下载那边早就有一整套
     * （DownloadChannels），图片这边却一直没接上：只用 stripProxyPrefix
     * 认得镜像地址，却从不主动给图套上。
     *
     * 第二件事是**别串行换道**。串行是「试一条、失败、再试下一条」，
     * 失败的那条要熬完整个超时才肯放手 —— 那正是「慢」的主要来源。
     * 这里改成并发探路：几条一起发，谁先把字节交出来就用谁。
     *
     * 第三件事是**记住上次哪条通的**。探路要并发下两遍，多花一份流量，
     * 只能偶尔做一次；记住之后同主机的图都走那条 —— 一篇 README 十来张图，
     * 只有第一张多花这一份。
     *
     * 凭据照 DownloadChannels 既有的规矩：走镜像一律不带 Authorization，
     * 令牌只留给排在最后的直连（私有仓库的附件）。
     */
    /** 这些主机上的图直连取不到，必须走镜像 */
    private static final String[] IMG_MIRROR_HOSTS = {
            "raw.githubusercontent.com",
            "user-images.githubusercontent.com",
            "private-user-images.githubusercontent.com",
            "avatars.githubusercontent.com",
            "camo.githubusercontent.com",
            "github.githubassets.com",
            "github.com",
            "objects.githubusercontent.com",
    };
    /** 探路时并发几条。多了费流量，少了探不出最快的 */
    private static final int RACE_WAYS = 2;
    /** 探路最长等这么久；有人赢了会立刻返回，不用等满 */
    private static final long RACE_TIMEOUT_MS = 15000L;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4,
            new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "webimg-race");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 上次走通的通道，按主机记（进程内一份，盘上存一份，重启还记得） */
    private static final ConcurrentHashMap<String, String> chanMemo =
            new ConcurrentHashMap<String, String>();

    /** 磁盘缓存上限，超了按「最久没用过的先删」淘汰 */
    private static final long DISK_MAX = 64L * 1024 * 1024;
    /** 单张上限：超过这个不进缓存（但仍会转发给 WebView） */
    private static final int SINGLE_MAX = 8 * 1024 * 1024;
    /**
     * 磁盘缓存有效期。
     *
     * 头像、上传附件这类地址本身是不可变的（换了就是新地址），
     * README 里的图则会随提交变化 —— 所以不能永久缓存，否则用户改了图
     * 会一直看到旧的。半天是个平衡点：同一天内反复看同一个仓库是本地直出，
     * 隔一天再看自然会重新取一次。
     */
    private static final long DISK_TTL = 12 * 60 * 60 * 1000L;

    private static final Object LOCK = new Object();
    private static volatile LruCache<String, Entry> mem;
    private static volatile File dir;

    private static final class Entry {
        final byte[] body;
        final String type;

        Entry(byte[] body, String type) {
            this.body = body;
            this.type = type;
        }
    }

    private WebImageProxy() {
    }

    /* ================= 对外接口 ================= */

    /**
     * 拦截一次 WebView 的资源请求。
     *
     * @return 交给 WebView 的响应；**null 表示这次不接管**，WebView 走自己的路。
     */
    public static WebResourceResponse intercept(Context ctx, WebResourceRequest req) {
        if (ctx == null || req == null) return null;
        try {
            if (req.isForMainFrame()) return null;
            String method = req.getMethod();
            if (method != null && !"GET".equalsIgnoreCase(method)) return null;
            return intercept(ctx, req.getUrl() == null ? null : req.getUrl().toString(),
                    req.getRequestHeaders());
        } catch (Throwable t) {
            return null;      // 接管这件事本身出问题也不能影响页面加载
        }
    }

    public static WebResourceResponse intercept(Context ctx, String url, Map<String, String> reqHeaders) {
        if (ctx == null || url == null) return null;

        /* 先看是不是包内本来就有的图 —— 那就不必上网了。
         * 这一步要排在「是不是图片请求」之前：页面里的相对地址（img/tips.png）
         * 在 WebView 眼里是 file:///android_asset/web/index.html 的相对路径，
         * 得先补成包内路径才认得出来。正文之类不是图片，下面会被放行。 */
        String asset = assetPathFor(ctx, url, reqHeaders);
        if (asset != null && isImageRequest(url, reqHeaders) && !hasHeader(reqHeaders, "Range")) {
            WebResourceResponse local = fromAsset(ctx, asset);
            if (local != null) return local;
        }

        if (!isImageRequest(url, reqHeaders)) return null;

        /* 分片请求不接：我们给的是完整字节，拿它回答 Range 会让解码器读错位。
         * 图片请求一般不会带 Range，带了的（个别图床）就让它自己来。 */
        if (hasHeader(reqHeaders, "Range")) return null;

        /* 点开大图时要的是原图（前端会加 __ghfull=1），正文里要的是缩略图。
         * 这个标记只在 App 内部用，真正发请求之前就摘掉了。 */
        boolean full = wantsFull(url);
        String target = full ? stripFullFlag(url) : url;
        String fk = fullKey(target);
        String tk = thumbKey(target);

        Entry hit = memGet(full ? fk : tk);
        if (hit != null) return respond(hit, "memory");

        byte[] disk = diskRead(ctx, full ? fk : tk);
        if (disk != null) {
            String type = mimeOf(disk);
            if (type != null) {
                memPut(full ? fk : tk, disk, type);
                return respond(new Entry(disk, type), "disk");
            }
        }

        /* 正文要缩略图但盘上只有原图 —— 现缩一份，不必再上网 */
        if (!full) {
            byte[] orig = diskRead(ctx, fk);
            if (orig != null) {
                String ot = mimeOf(orig);
                if (ot != null) {
                    byte[] t = makeThumb(ctx, orig);
                    if (t != null) {
                        memPut(tk, t, "image/webp");
                        diskWrite(ctx, tk, t, "image/webp");
                        return respond(new Entry(t, "image/webp"), "缩(盘)");
                    }
                    memPut(tk, orig, ot);
                    return respond(new Entry(orig, ot), "disk");
                }
            }
        }

        String token = tokenFor(ctx, target);
        byte[] body = null;
        String type = null;
        int lastCode = 0;

        Got got = fetchViaChannel(ctx, target, token);
        if (got != null && got.r != null) {
            lastCode = got.r.code;
            if (got.r.code == 200 && got.r.body.length > 0) {
                body = got.r.body;
                type = pickType(got.r.contentType, body);
            }
        }

        /*
         * 拿不回来。
         *
         * 这里**必须返回 null（放行）**，不能返回一个空的 200 —— 那样会把
         * 「网络失败」伪装成「服务器说这张图是空的」，<img> 只当图裂了，
         * 前端那条「失败就重试」的兜底也永远不会触发。
         * 返回 null 之后 WebView 自己去试，试失败会报 error，
         * 前端再走它那条队列（能带令牌、能换档），成功率反而更高。
         *
         * 原因是拿不回来的情况里，「网络根本到不了」和「地址是错的」性质完全不同，
         * 只有日志里分得开，界面上才不至于让人以为是 App 坏了。
         */
        if (body == null || type == null) {
            logMiss(target, lastCode, token.length() > 0);
            return null;
        }

        /* 原图留一份：点开看大图时直接读盘，不必为了看一次大图再下一遍 */
        if (body.length <= SINGLE_MAX) {
            memPut(fk, body, type);
            diskWrite(ctx, fk, body, type);
        }

        if (!full) {
            byte[] t = makeThumb(ctx, body);
            if (t != null) {
                memPut(tk, t, "image/webp");
                diskWrite(ctx, tk, t, "image/webp");
                return respond(new Entry(t, "image/webp"), "缩(网)");
            }
            memPut(tk, body, type);
            return respond(new Entry(body, type), "network");
        }
        memPut(fk, body, type);
        return respond(new Entry(body, type), "network");
    }

    /* ================= 通道 ================= */

    private static final class Got {
        final Http.Bytes r;
        final String chan;          // 走通的通道前缀；直连是 ""
        Got(Http.Bytes r, String chan) { this.r = r; this.chan = chan; }
    }

    private static boolean okBytes(Http.Bytes r) {
        return r != null && r.code == 200 && r.body != null && r.body.length > 0;
    }

    /** 这个地址是走镜像取到的还是直连取到的（返回值就是镜像前缀，直连为空串） */
    private static String chanTag(String url) {
        if (url == null) return "";
        for (String m : DownloadChannels.MIRRORS) {
            if (url.startsWith(m)) return m;
        }
        return "";
    }

    private static boolean imgMirrorable(String url) {
        String h = hostOf(url);
        if (h == null) return false;
        for (String m : IMG_MIRROR_HOSTS) {
            if (h.equals(m) || h.endsWith("." + m)) return true;
        }
        return false;
    }

    private static String chanOf(Context ctx, String url) {
        String h = hostOf(url);
        if (h == null) return "";
        String v = chanMemo.get(h);
        if (v != null) return v;
        v = "";
        if (ctx != null) {
            try {
                String s = ctx.getSharedPreferences("webimg", Context.MODE_PRIVATE)
                        .getString("chan:" + h, "");
                if (s != null) v = s;
            } catch (Throwable ignored) {
            }
        }
        chanMemo.put(h, v);
        return v;
    }

    private static void noteChan(Context ctx, String url, String chan) {
        String h = hostOf(url);
        if (h == null) return;
        String v = chan == null ? "" : chan;
        chanMemo.put(h, v);
        if (ctx != null) {
            try {
                ctx.getSharedPreferences("webimg", Context.MODE_PRIVATE)
                        .edit().putString("chan:" + h, v).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 候选地址：上次走通的排最前，直连永远兜底（它才带令牌） */
    private static List<String> imgCandidates(String url, String lastChan) {
        List<String> mirrors = new ArrayList<String>();
        for (String m : DownloadChannels.MIRRORS) mirrors.add(m + url);
        List<String> out = new ArrayList<String>();
        if (lastChan != null && lastChan.length() > 0) {
            for (int i = 0; i < mirrors.size(); i++) {
                if (mirrors.get(i).startsWith(lastChan)) {
                    out.add(mirrors.remove(i));
                    break;
                }
            }
        }
        out.addAll(mirrors);
        out.add(url);
        return out;
    }

    /**
     * 并发发几条，谁先把字节交出来就用谁。
     *
     * 串行换道最要命的地方是：失败的那条要熬完整个超时才肯放手，
     * 而「等超时」正是用户感受到的慢。并发就不用等 —— 最快的那条
     * 通常一秒内就回来了，慢的那些爱等多久等多久，没人理它们。
     */
    private static Got race(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        if (urls.size() == 1) {
            Http.Bytes r = fetch(urls.get(0), plainHeaders());
            return okBytes(r) ? new Got(r, chanTag(urls.get(0))) : null;
        }

        final int n = Math.min(RACE_WAYS, urls.size());
        final CountDownLatch win = new CountDownLatch(1);
        final AtomicReference<Got> got = new AtomicReference<Got>();
        final List<Future<?>> fs = new ArrayList<Future<?>>();
        for (int i = 0; i < n; i++) {
            final String u = urls.get(i);
            try {
                fs.add(POOL.submit(new Runnable() {
                    public void run() {
                        if (got.get() != null) return;      // 已经有人赢了，别再白下一遍
                        Http.Bytes r = fetch(u, plainHeaders());
                        if (okBytes(r) && got.compareAndSet(null, new Got(r, chanTag(u)))) {
                            win.countDown();
                        }
                    }
                }));
            } catch (Throwable ignored) {
            }
        }
        try {
            win.await(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        for (Future<?> f : fs) {
            try {
                f.cancel(true);                             // 输的那些不必再下完
            } catch (Throwable ignored) {
            }
        }
        return got.get();
    }

    /** 按指定的通道单发一次；通道为空、或这条路已经不通，返回 null */
    private static Got tryChan(Context ctx, String url, String chan) {
        if (chan == null || chan.length() == 0) return null;
        String u = chan + url;
        Http.Bytes r = fetch(u, plainHeaders());
        if (okBytes(r)) return new Got(r, chanTag(u));
        noteChan(ctx, url, "");                              // 这条路不通了，忘掉它
        return null;
    }

    /** 每个主机一把锁，只用于「探路时别一拥而上」 */
    private static final ConcurrentHashMap<String, Object> probeLocks =
            new ConcurrentHashMap<String, Object>();

    private static Object lockFor(String host) {
        String h = host == null ? "_" : host;
        Object o = probeLocks.get(h);
        if (o == null) {
            Object made = new Object();
            o = probeLocks.putIfAbsent(h, made);
            if (o == null) o = made;
        }
        return o;
    }

    /**
     * 取字节：先挑一条能通的路，再谈搬多少。
     *
     * 不是 GitHub 图床（shields.io 的徽标之类）—— 那些直连本来就通，
     * 按原来的两档来（带令牌 → 不带令牌），别无谓地绕代理。
     */
    private static Got fetchViaChannel(Context ctx, String url, String token) {
        if (!imgMirrorable(url)) {
            if (token.length() > 0) {
                Http.Bytes r = fetch(url, bearerHeaders(token));
                if (okBytes(r)) return new Got(r, "");
                /* 带签名的 CDN 见到 Authorization 会回 400，
                 * 只试一次的话用户看到的就是「图明明传上去了，就是不显示」。 */
                if (r != null && r.code >= 400 && r.code < 500) {
                    Http.Bytes r2 = fetch(url, plainHeaders());
                    return new Got(r2 != null ? r2 : r, "");
                }
                return new Got(r, "");
            }
            return new Got(fetch(url, plainHeaders()), "");
        }

        /* 快路径：已经有记录就直接走那一条，不必探路。
         * 一篇 README 十来张图同时到，走这条的占绝大多数。 */
        Got quick = tryChan(ctx, url, chanOf(ctx, url));
        if (quick != null) return quick;

        /*
         * 慢路径：要探路了。同主机只许探一次 ——
         * 否则十张图各自并发两条路，就是二十个请求在抢带宽，
         * 流量翻倍不说，本来就窄的那条路被自己挤得更慢。
         * 第一张进去探，其余的在门口等，等出来直接捡结果走快路径。
         * 这个锁只在「确实要探路」时才会碰上，平时不挡路。
         */
        String h = hostOf(url);
        synchronized (lockFor(h)) {
            Got again = tryChan(ctx, url, chanOf(ctx, url));   // 排队期间别人可能已经探出来了
            if (again != null) return again;

            List<String> cands = imgCandidates(url, "");
            int i = 0;
            while (i < cands.size()) {
                List<String> batch = new ArrayList<String>();
                for (int k = 0; k < RACE_WAYS && i < cands.size(); k++, i++) batch.add(cands.get(i));
                Got g = race(batch);
                if (g != null) {
                    noteChan(ctx, url, g.chan);
                    return g;
                }
            }
        }

        /* 都拿不到：私有仓库的附件可能只有带令牌的直连认 */
        if (token.length() > 0) {
            Http.Bytes r = fetch(url, bearerHeaders(token));
            if (okBytes(r)) return new Got(r, "");
        }
        return null;
    }

    /* ================= 判定 ================= */

    /* 这些主机上的东西**基本只可能是图**：漏掉了也就是继续走 WebView 自己那条路，
     * 不会误伤（头像的地址没有扩展名，只靠扩展名判定会漏）。 */
    private static final String[] IMAGE_PURE_HOSTS = {
            "avatars.githubusercontent.com",
            "camo.githubusercontent.com",
            "user-images.githubusercontent.com",
            "private-user-images.githubusercontent.com",
            "github.githubassets.com"
    };

    private static final String[] IMAGE_EXT = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".avif", ".svg"
    };

    /**
     * 这个请求是不是在要一张图。
     *
     * 主要看 Accept 头 —— <img> 发出来的一定带 image/*，这是最准的信号；
     * 没有这个头（少数 WebView 不透传）才去看扩展名和「已知图床」。
     *
     * ⚠️ 顺序不能倒过来。以前是「先按主机一刀切，再看 Accept」，
     * 于是 raw.githubusercontent.com 上取文件正文的请求（Accept 是「什么都要」）
     * 也会被当成图片接走 —— 那样一整屏的代码就会变成一堆乱码字节。
     * 所以那个主机只在带 image/* 时才接。
     */
    private static boolean isImageRequest(String url, Map<String, String> h) {
        String u = url.toLowerCase(Locale.US);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;

        String acc = headerValue(h, "Accept");
        if (acc != null && acc.toLowerCase(Locale.US).contains("image/")) return true;

        /* 判定要看**真实目标**的路径。镜像 / 代理地址把它们的前缀也拼在路径里：
         *     https://ghfast.top/https://raw.githubusercontent.com/o/r/main/a.png
         * 这里如果照搬整个路径去找结尾，看到的是 "…/main/a.png" 还算走运，
         * 碰上带查询串的（?raw=1）就连扩展名都认不出，于是返回 null 放行 ——
         * 而那条直连在不少网络下根本连不通，图片就永远出不来。
         * 所以先把前缀剥掉，拿真正的目标地址来判。 */
        String path = stripProxyPrefix(url).toLowerCase(Locale.US);
        int q = path.indexOf('?');
        int h2 = path.indexOf('#');
        int cut = q >= 0 ? q : (h2 >= 0 ? h2 : path.length());
        path = path.substring(0, cut);
        for (String ext : IMAGE_EXT) {
            if (path.endsWith(ext)) return true;
        }
        /* GitHub 上传的附件没有扩展名（assets/<uuid>），只能认路径 */
        if (path.contains("github.com/user-attachments/")) return true;
        try {
            String host = new java.net.URL(stripProxyPrefix(url)).getHost();
            if (host != null) {
                for (String s : IMAGE_PURE_HOSTS) {
                    if (host.equalsIgnoreCase(s)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 剥掉「镜像 / 代理前缀」，拿回真正要访问的地址。
     *
     * 有些网络下 github 直连不通，用户（或系统分享出来的链接）会把地址套一层
     * 加速前缀，形如：
     *     https://ghfast.top/https://raw.githubusercontent.com/o/r/main/a.png
     * 对 java.net.URL 来说，这一整串的 host 是 ghfast.top、path 是
     * "/https://raw.githubusercontent.com/…"，于是「按主机认图床」和
     * 「按扩展名认图片」两条判定同时落空。剥掉前缀之后才是那个真实地址。
     *
     * 只认「路径以另一种 scheme 开头」这一种形态 —— 不去猜某个加速站的名字，
     * 那不穷举不完。普通地址原样返回。
     */
    private static String stripProxyPrefix(String url) {
        if (url == null) return null;
        String u = url;
        for (int i = 0; i < 3; i++) {          // 最多剥三层，防畸形地址套娃
            int scheme = u.indexOf("://");
            if (scheme <= 0) return u;
            int slash = u.indexOf('/', scheme + 3);
            if (slash < 0) return u;
            String rest = u.substring(slash + 1);
            String lower = rest.toLowerCase(Locale.US);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                u = rest;
                continue;
            }
            return u;
        }
        return u;
    }

    /* ================= 包内的图，别去网上拉 =================
     *
     * 有一类图片请求看着像网络请求，其实本机包里就有现成的：
     *
     *   README 里写的是 `app/src/main/assets/web/img/tips.png`（仓库内路径），
     *   有的地方还会写成 `https://raw.githubusercontent.com/…/tips.png`。
     *   而这张图**同时**也是 App 自己的资源，路径一模一样。
     *
     * 不认这一步的话，用户每翻一次 README 就要走一趟 GitHub：
     * 400 多 KB、国内网络动辄几秒起步，还占了 6 条连接里的一条 ——
     * 屏幕上就是「图半天不出来」。明明包里就有同一个文件。
     *
     * 附件缓存里存过的东西同样可以直接从本地取回，不用再下一次。
     */

    /** 网页根目录（file:///android_asset/web/）在包内的对应位置 */
    private static final String ASSET_WEB = "web/";

    /** 把请求地址映射成包内 assets 路径；不是本 App 资源就返回 null */
    private static String assetPathFor(Context ctx, String url, Map<String, String> h) {
        try {
            if (url == null) return null;
            String u = url;
            String lower = u.toLowerCase(Locale.US);

            if (lower.startsWith("file:///android_asset/")) {
                return u.substring("file:///android_asset/".length());
            }
            if (lower.startsWith("https://appassets.androidplatform.net/assets/")) {
                return u.substring("https://appassets.androidplatform.net/assets/".length());
            }
            /* 相对地址：页面就在 file:///android_asset/web/index.html，
             * 所以 img/tips.png 指的就是包内 web/img/tips.png。
             * 只对图片请求这么做 —— 正文、脚本之类不能凭这个改路径。 */
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                java.net.URL parsed = new java.net.URL(u);
                String host = parsed.getHost();
                if (host != null && !isAppWebOrigin(host)) return null;
                String path = parsed.getPath();
                if (path == null) return null;
                path = path.replaceFirst("^/+", "");
                if (path.startsWith(ASSET_WEB)) path = path.substring(ASSET_WEB.length());
                return looksLikeWebPath(path) ? ASSET_WEB + path : null;
            }
            if (lower.indexOf(':') > 0) return null;          // 其它 scheme（data: 等）
            int q = u.indexOf('?');
            int hash = u.indexOf('#');
            int cut = q >= 0 ? q : (hash >= 0 ? hash : u.length());
            String rel = u.substring(0, cut).replaceFirst("^/+(?=web/)?", "");
            if (rel.startsWith(ASSET_WEB)) rel = rel.substring(ASSET_WEB.length());
            return looksLikeWebPath(rel) ? ASSET_WEB + rel : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** appassets.androidplatform.net 是 WebViewAssetLoader 的默认域名（本 App 自己）；别的域名一概不认 */
    private static boolean isAppWebOrigin(String host) {
        return "appassets.androidplatform.net".equalsIgnoreCase(host);
    }

    /** 只认 assets/web/ 下确实存在的文件，且**必须是图片** —— 免得把任意资源都变成可读的 */
    private static boolean looksLikeWebPath(String rel) {
        String lower = rel.toLowerCase(Locale.US);
        boolean image = false;
        for (String ext : IMAGE_EXT) {
            if (lower.endsWith(ext)) {
                image = true;
                break;
            }
        }
        return image && !lower.contains("..");
    }

    /** 包内资源直接读出来交给渲染器（不联网、不缓存） */
    private static WebResourceResponse fromAsset(Context ctx, String assetPath) {
        if (!WEB_DIR && !isAllowedAsset(assetPath)) {
            return null;
        }
        InputStream in = null;
        try {
            in = ctx.getAssets().open(assetPath);
            return new WebResourceResponse(mimeOfName(assetPath), null, in);
        } catch (Throwable t) {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 只放行 assets/web/ 下的资源 —— 别的（含防护链清单）不该从这条路出去 */
    private static boolean isAllowedAsset(String assetPath) {
        return assetPath != null && assetPath.startsWith(ASSET_WEB);
    }

    /** 见上方 ASSET_WEB 的说明：这里先留一个恒为 true 的开关，方便将来收紧 */
    private static final boolean WEB_DIR = true;

    private static String mimeOfName(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.US) : "";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("bmp")) return "image/bmp";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("ico")) return "image/x-icon";
        if (ext.equals("avif")) return "image/avif";
        return "application/octet-stream";
    }

    /* ================= 类型判定 ================= */

    /** Content-Type 说是图片就信它；否则按魔数认一遍，认不出就不接管 */
    private static String pickType(String ct, byte[] b) {
        if (ct != null && ct.toLowerCase(Locale.US).startsWith("image/")) return ct;
        String m = mimeOf(b);
        if (m != null) return m;
        // SVG 是文本，没有魔数；头里说是 svg 或字节里能看出 svg 标签就按 svg 走
        if (ct != null && ct.toLowerCase(Locale.US).contains("svg")) return "image/svg+xml";
        if (b.length > 8 && looksLikeSvg(b)) return "image/svg+xml";
        return null;
    }

    /** 按文件头魔数认图；认不出返回 null */
    private static String mimeOf(byte[] b) {
        if (b == null || b.length < 12) return null;
        if (b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
        return null;
    }

    private static boolean looksLikeSvg(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 256), java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(Locale.US);
        return head.contains("<svg") || (head.contains("<?xml") && head.contains("svg"));
    }

    /* ================= 取字节 ================= */

    private static Http.Bytes fetch(String url, Map<String, String> h) {
        try {
            return Http.requestRawBytes("GET", url, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 令牌只发给 GitHub 自家域名 —— 发给第三方图床等于把仓库权限交出去。
     *
     * 另外跳过带签名的 CDN 主机：那类地址见到 Authorization 会直接回 400
     * （"Only one auth mechanism allowed"），给了反而取不到。
     */
    private static String tokenFor(Context ctx, String url) {
        String host = hostOf(url);
        if (host == null) return "";
        host = host.toLowerCase(Locale.US);
        boolean gh = host.equals("github.com") || host.endsWith(".github.com")
                || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com");
        /* 镜像 / 代理前缀：真正要访问的是前缀后面那个地址，令牌该不该带
         * 得看**目标**主机，而不是加速站的名字。 */
        if (!gh) {
            String real = hostOf(stripProxyPrefix(url));
            if (real != null) {
                real = real.toLowerCase(Locale.US);
                gh = real.equals("github.com") || real.endsWith(".github.com")
                        || real.equals("githubusercontent.com") || real.endsWith(".githubusercontent.com");
            }
            if (!gh) return "";
        }
        if (host.startsWith("private-user-images.") || host.startsWith("camo.")) return "";
        if (url != null && url.contains("jwt=")) return "";
        try {
            String t = SecurePrefs.get(ctx, "gh_token", "");
            return t == null ? "" : t.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Map<String, String> bearerHeaders(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        h.put("User-Agent", BROWSER_UA);
        return h;
    }

    private static Map<String, String> plainHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        h.put("User-Agent", BROWSER_UA);
        return h;
    }

    /**
     * 取图时用的 UA。
     *
     * 别用 "githup/1.0" 这种自定义串：raw.githubusercontent.com 和几个图床
     * 对不认识的 UA 有额外的限制策略，同一张图 WebView 自己拉得下来、
     * 走原生通道反而被挡，用户看到的就是「图时好时坏」。报一个真实浏览器的
     * 身份最省事，也最接近「网页端能看到什么，App 里就能看到什么」。
     */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";

    /**
     * 没接住的时候记一行，好回答「这图到底是网络不通还是地址不对」。
     *
     * 两种情况在界面上都是「图没出来」，但处理方式完全相反：
     * httpCode=0 是连接层就失败了（被墙 / DNS / 超时），该换条路重试；
     * 404 / 403 是地址本身不对或没权限，重试一百次也一样。
     * 以前这两种混在一起，只能靠猜。
     */
    private static void logMiss(String url, int httpCode, boolean hadToken) {
        try {
            String host = hostOf(url);
            Log.d(TAG, "没取到图 host=" + host + " code=" + httpCode
                    + " token=" + (hadToken ? "y" : "n") + " url=" + shorten(url));
        } catch (Throwable ignored) {
        }
    }

    private static String shorten(String url) {
        if (url == null) return "";
        return url.length() > 120 ? url.substring(0, 120) + "…" : url;
    }

    private static WebResourceResponse respond(Entry e, String from) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", e.type);
        h.put("Content-Length", String.valueOf(e.body.length));
        h.put("Cache-Control", "max-age=43200");
        h.put("Access-Control-Allow-Origin", "*");
        try {
            Log.d(TAG, "命中 " + from + "（" + e.body.length / 1024 + "KB）");
        } catch (Throwable ignored) {
        }
        return new WebResourceResponse(e.type, null, 200, "OK", h,
                new ByteArrayInputStream(e.body));
    }

    /* ================= 内存缓存 ================= */

    private static LruCache<String, Entry> mem() {
        if (mem == null) {
            synchronized (LOCK) {
                if (mem == null) {
                    mem = new LruCache<String, Entry>(MEM_MAX) {
                        @Override
                        protected int sizeOf(String k, Entry v) {
                            return v == null ? 0 : v.body.length;
                        }
                    };
                }
            }
        }
        return mem;
    }

    private static Entry memGet(String url) {
        try {
            return mem().get(key(url));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void memPut(String url, byte[] body, String type) {
        try {
            mem().put(key(url), new Entry(body, type));
        } catch (Throwable ignored) {
        }
    }

    /* ================= 磁盘缓存 ================= */

    private static File dir(Context ctx) {
        if (dir == null) {
            synchronized (LOCK) {
                if (dir == null) {
                    File d = new File(ctx.getCacheDir(), "webimg");
                    if (!d.exists() && !d.mkdirs()) return null;
                    dir = d;
                }
            }
        }
        return dir;
    }

    /** 读磁盘缓存。过期 / 读不动都当没有，不影响主流程。 */
    private static byte[] diskRead(Context ctx, String url) {
        File d = dir(ctx);
        if (d == null) return null;
        File f = new File(d, key(url));
        if (!f.exists() || !f.isFile()) return null;
        if (System.currentTimeMillis() - f.lastModified() > DISK_TTL) {
            try {
                // 过期了也顺手更新一下时间戳：淘汰顺序按「最久没用过」，
                // 但这张已经不能再当作有效缓存了，所以直接删掉重取
                f.delete();
            } catch (Throwable ignored) {
            }
            return null;
        }
        try {
            byte[] raw = readAll(f);
            if (raw.length < 2) return null;
            int nl = indexOf(raw, (byte) '\n');
            if (nl < 0) return null;
            byte[] body = Arrays.copyOfRange(raw, nl + 1, raw.length);
            if (body.length == 0) return null;
            // 顺带把「最近用过」这件事记下来，淘汰时才不会误删常用的图
            f.setLastModified(System.currentTimeMillis());
            return body;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void diskWrite(Context ctx, String url, byte[] body, String type) {
        File d = dir(ctx);
        if (d == null) return;
        File f = new File(d, key(url));
        try {
            File tmp = new File(d, key(url) + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                os.write((type + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                os.write(body);
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            if (!tmp.renameTo(f)) tmp.delete();
            trim(d);
        } catch (Throwable t) {
            // 写缓存失败无所谓 —— 下次再取一次就是了
        }
    }

    /** 总量超了就按最久没用过的删，删到上限的 3/4 为止 */
    private static void trim(File d) {
        try {
            File[] fs = d.listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            for (File f : fs) if (f.isFile()) total += f.length();
            if (total <= DISK_MAX) return;
            Arrays.sort(fs, (a, b) -> {
                long x = a.lastModified(), y = b.lastModified();
                return x < y ? -1 : (x > y ? 1 : 0);
            });
            long want = DISK_MAX * 3 / 4;
            for (File f : fs) {
                if (total <= want) break;
                if (!f.isFile()) continue;
                long n = f.length();
                if (f.delete()) total -= n;
            }
        } catch (Throwable ignored) {
        }
    }

    /* ================= 小工具 ================= */

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 请求头里带上 Range 的，一律不接（见 intercept 里的说明）。
     *
     * 需要单独判一下：有些 WebView 版本不把原始的 Range 头透传过来，
     * 而是塞在 Accept 里（Accept: image/*;Range=bytes=…），以前只查
     * "Range" 这一个键名就会漏掉这种写法，然后我们拿完整字节去回答一个
     * 分片请求 —— 解码器从中间读起，图上会出现横向错位的条纹。
     */
    private static boolean hasHeader(Map<String, String> h, String name) {
        String v = headerValue(h, name);
        if (v != null && v.length() > 0) return true;
        if (!"Range".equalsIgnoreCase(name)) return false;
        String acc = headerValue(h, "Accept");
        return acc != null && acc.toLowerCase(Locale.US).contains("range=");
    }

    private static String headerValue(Map<String, String> h, String name) {
        if (h == null) return null;
        String v = h.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    /** 缓存键：URL 直接哈希（同一张图的地址是稳定的，带上查询串也没问题） */
    private static String key(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(url.hashCode());
        }
    }

    /* ================= 原图 / 缩略图 ================= */

    /**
     * 前端点开大图时挂在地址上的内部标记。
     * 只有带它才给原图，正文里一律给缩略图；真正发请求之前会摘掉。
     */
    private static final String FULL_FLAG = "__ghfull=1";

    private static boolean wantsFull(String url) {
        return url != null && url.indexOf(FULL_FLAG) >= 0;
    }

    /** 摘掉内部标记，顺便把摘完留下的空参数位（?& / && / 末尾的 ? 和 &）收拾干净 */
    private static String stripFullFlag(String url) {
        if (url == null) return null;
        String out = url.replace(FULL_FLAG, "");
        out = out.replace("?&", "?").replace("&&", "&");
        while (out.endsWith("?") || out.endsWith("&")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 原图和缩略图是两份缓存，键必须分开，否则会互相顶掉 */
    private static String fullKey(String url) {
        return "f:" + key(url);
    }

    private static String thumbKey(String url) {
        return "t:" + key(url);
    }

    /**
     * 把原图压成一张「够屏幕用就行」的 WebP。
     *
     * 返回 null 表示「这张不该动」，调用方原样放行 —— 也就是最多回到改之前，
     * 不会更差。不动的有：太小（省下的不够付一次解码）、太大（怕顶爆内存）、
     * 认不出格式、GIF 和动画 WebP（压完只剩第一帧）。
     *
     * 两种省法：超宽的图先缩到屏幕宽度；不宽但很胖的 PNG（截图之类）不缩尺寸，
     * 单纯转成 WebP 也能瘦掉一半 —— 所以「没超宽」不等于「不用管」。
     */
    private static byte[] makeThumb(Context ctx, byte[] src) {
        if (src == null) return null;
        if (src.length < THUMB_MIN_BYTES) return null;
        if (src.length > THUMB_MAX_SRC) return null;

        String m = mimeOf(src);
        if (m == null) return null;                                 // SVG 之类没有位图可压
        if ("image/gif".equals(m)) return null;                     // 动图一压就死
        if ("image/webp".equals(m) && isAnimatedWebp(src)) return null;
        if (!("image/png".equals(m) || "image/jpeg".equals(m)
                || "image/webp".equals(m) || "image/bmp".equals(m))) return null;

        int maxW = thumbMaxW(ctx);
        try {
            /* 第一步只量尺寸，不解码 —— 免得为了「要不要缩」先吃掉几十 MB 内存 */
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;                      // 解码不了就别折腾

            /* 第二步按整倍数先降一档（省内存），再精确缩到目标宽度。
             * 没超宽的图 sample 算出来就是 1，只走后面的转码。 */
            int sample = 1;
            while (w / (sample * 2) >= maxW) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeByteArray(src, 0, src.length, opt);
            if (bm == null) return null;

            int bw = bm.getWidth();
            if (bw > maxW) {
                int bh = bm.getHeight();
                int th = Math.max(1, Math.round((float) bh * maxW / bw));
                Bitmap small = Bitmap.createScaledBitmap(bm, maxW, th, true);
                if (small != null) {
                    if (small != bm) bm.recycle();
                    bm = small;
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(src.length, 64 * 1024));
            boolean ok = bm.compress(Bitmap.CompressFormat.WEBP, THUMB_QUALITY, out);
            bm.recycle();
            if (!ok) return null;

            byte[] res = out.toByteArray();
            if (res.length == 0) return null;
            /* 极少数噪点特别多的图压完反而更大，那就还是用原图 */
            if (res.length >= src.length) return null;
            return res;
        } catch (OutOfMemoryError oom) {
            return null;                                            // 内存不够就别压了，原样给
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 缩略图该有多宽 —— 屏幕有多宽就给多宽，再宽就是白下。
     * 屏幕宽度不会变，算一次记住就行。
     */
    private static volatile int thumbMaxWCache = 0;

    private static int thumbMaxW(Context ctx) {
        int v = thumbMaxWCache;
        if (v > 0) return v;
        int w = THUMB_MIN_W;
        if (ctx != null) {
            try {
                android.util.DisplayMetrics dm =
                        ctx.getResources().getDisplayMetrics();
                if (dm != null && dm.widthPixels > 0) w = dm.widthPixels;
            } catch (Throwable ignored) {
            }
        }
        if (w < THUMB_MIN_W) w = THUMB_MIN_W;
        if (w > THUMB_MAX_W) w = THUMB_MAX_W;
        thumbMaxWCache = w;
        return w;
    }

    /** 按 RIFF 块走一遍找 ANIM / ANMF —— 有就是动画 WebP，不能缩 */
    private static boolean isAnimatedWebp(byte[] b) {
        int end = Math.min(b.length, 64 * 1024);
        int p = 12;                                                 // 跳过 RIFF + 长度 + WEBP
        while (p + 8 <= end) {
            boolean anim = (b[p] == 'A' && b[p + 1] == 'N' && b[p + 2] == 'I' && b[p + 3] == 'M')
                    || (b[p] == 'A' && b[p + 1] == 'N' && b[p + 2] == 'M' && b[p + 3] == 'F');
            if (anim) return true;
            int size = (b[p + 4] & 0xFF) | ((b[p + 5] & 0xFF) << 8)
                    | ((b[p + 6] & 0xFF) << 16) | ((b[p + 7] & 0xFF) << 24);
            if (size < 0 || size > end) return false;               // 长度离谱就不猜了
            p += 8 + size + (size & 1);                             // 块按 2 字节对齐
        }
        return false;
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        long n = f.length();
        if (n <= 0 || n > Integer.MAX_VALUE - 8) throw new java.io.IOException("文件大小异常");
        byte[] out = new byte[(int) n];
        InputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < out.length) {
                int r = in.read(out, off, out.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != out.length) throw new java.io.IOException("文件读不全");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int indexOf(byte[] a, byte b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b) return i;
        }
        return -1;
    }
}
