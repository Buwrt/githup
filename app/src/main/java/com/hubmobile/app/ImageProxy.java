package com.hubmobile.app;

import android.content.Context;
import android.util.Log;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * README 图片的「原样转发」通道。
 *
 * <p>背景：以前 App 显示 README 图片走的是这么一条路 ——
 * 前端发现一个 &lt;img&gt;，把它的 https 地址交给原生；原生用 Http.requestB64
 * 把图片**读成字符串**（Base64，凭空胖 33%），再切成 48KB 一片塞进
 * evaluateJavascript 过 Binder 念给 JS；JS 拼回字符串、塞成 data: URI，
 * WebView 再从头解一遍码。
 *
 * <p>三次本不必要的开销：体积 +33%、两次编解码、以及 Binder 这一趟本身就慢。
 * 更要命的是它**丢掉了浏览器天生就有的一切**：HTTP 缓存用不上、
 * 渐进式渲染没有（必须整张到齐才出第一帧）、<img> 自己的并发调度也全废了。
 * 所以网页浏览器里「滑到就出图」，App 里要等好几秒。
 *
 * <p>这里做的正是在裁判位置上把球接住：WebView 要取这张图的时候
 * （shouldInterceptRequest），我们用已经跑通的原生网络栈去拿，拿到的**字节原样**
 * 交给 WebView 自己解码渲染 —— 不经过 String、不经过 Binder、不经过 JS。
 * 这一层再配一个磁盘缓存，第二次打开同一张图就是读本地文件，零网络。
 *
 * <p>失败一律返回 null = 「我没意见，你自己来」。WebView 会照原路重试。
     * 这个降级不是客套：它图的是「接住的时候就该够快」，不是「一定能成」——
     * 任何一处不确定都该把路让回去，而不是替用户拍板给一张裂图。
 */
public final class ImageProxy {

    private static final String TAG = "ImageProxy";

    /** 磁盘缓存的新鲜期。README 里的图几乎是写完就不再改的资源。 */
    private static final long CACHE_TTL = 24L * 3600 * 1000;
    private static final long MAX_CACHE_BYTES = 150L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 800;
    /** 单张图上限：超过就不要了（此时更应该给它一张裂图，而不是拖住整个队列） */
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /** 和浏览器默认完全一致的一行，省得 CDN 因为 Accept 不同错判格式 */
    private static final String DEFAULT_ACCEPT =
            "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";

    private static final int PREFETCH_LIMIT = 8;

    /** 逐跳首部和对不上账的首部：原样透传会让 WebView 读不完这条响应 */
    private static final List<String> DROP_HEADERS = Arrays.asList(
            "content-encoding",     /* Http 层已经解开了，再写着 gzip 就是骗 WebView */
            "transfer-encoding",
            "content-length",       /* 下面按解压后的真实长度重算 */
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "trailer",
            "upgrade");

    public interface TokenProvider {
        String get();
    }

    private static final class Hit {
        byte[] data;
        String mime = "";
        Map<String, String> headers = new HashMap<>();
    }

    private final File dir;
    private final TokenProvider tokens;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** 只为了「写满 N 次才巡一遍目录」，别让每张图都付一次 listing 的钱 */
    private final AtomicInteger writesSinceTrim = new AtomicInteger();

    ImageProxy(Context ctx, TokenProvider tokens) {
        this.dir = new File(ctx.getCacheDir(), "imgproxy");
        this.tokens = tokens;
        //noinspection ResultOfMethodCallIgnored
        this.dir.mkdirs();
    }

    /* ============================================================
     * 拦截：这里是在 IO 线程上跑的（不是主线程），可以做网络 IO，
     * 但每阻塞一秒就有一个 <img> 在等，所以下限要有、上限也要有。
     * ============================================================ */
    WebResourceResponse intercept(WebResourceRequest req) {
        if (req == null) return null;
        if (req.isForMainFrame()) return null;
        if (!"GET".equalsIgnoreCase(req.getMethod())) return null;

        String url = req.getUrl() == null ? null : req.getUrl().toString();
        if (url == null || !url.startsWith("https://")) return null;
        if (!looksLikeImage(url, req.getRequestHeaders())) return null;

        String log = url;
        if (log.length() > 96) log = log.substring(0, 96) + "…";
        long t0 = android.os.SystemClock.elapsedRealtime();

        try {
            File f = fileFor(url);
            /* 同一个 URL 串行：README 里三处引用同一张图时，WebView 会几乎同时
             * 来问三次。第一个去下载，后两个在锁上等它落盘，然后直接读现成的 ——
             * 省下的不是几百毫秒，是两张图的流量。 */
            Object lock = lockFor(url);
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (lock) {
                Hit hit = readCache(f);
                String from = "cache";
                if (hit == null) {
                    hit = download(url, req.getRequestHeaders());
                    from = "net";
                    if (hit != null) writeCache(f, hit.data, hit.mime);
                }
                if (hit == null) {
                    Log.w(TAG, "没接住（退回 WebView 自取）：" + log);
                    return null;
                }
                Log.d(TAG, from + " " + (android.os.SystemClock.elapsedRealtime() - t0)
                        + "ms " + (hit.data.length / 1024) + "KB " + log);
                /* WebView 对 <img> 的 Content-Type 挑剔得很：text/plain、
                 * octet-stream 一律拒绝渲染成图片，哪怕字节是张完好无损的图。
                 * CDN 会犯糊涂（raw 域名历史上就把 SVG 标成 text/plain），
                 * 磁盘缓存丢了 .type 侧标也会落成 octet-stream。在这里按
                 * URL 扩展名最后把关一次，两处来源（网络 / 缓存）都覆盖。 */
                hit.mime = saneMime(hit.mime, url);
                return wrap(hit);
            }
        } catch (Throwable t) {
            Log.w(TAG, "代理抛异常，退回 WebView：" + log, t);
            return null;
        }
    }

    /**
     * 预热：README 刚渲染完就把前几张图的地址丢过来后台下载。
     *
     * <p>有了懒加载之后，图片是等到滑到眼前才发请求的 —— 对用户来说
     * 那就是「每次滑到这儿都要等一下」。提前把前几张灌进缓存，滑到时
     * 是读本地文件，才是网页那个「一瞬间」的手感。
     *
     * <p>只做前几张不是偷懒：一份 README 可能有几十张图，全预热等于替用户
     * 把整份文档连同他根本不会滑到的部分一起买单。
     */
    void prefetch(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        List<String> todo = new ArrayList<>();
        for (String u : urls) {
            if (u == null || !u.startsWith("https://")) continue;
            if (todo.size() >= PREFETCH_LIMIT) break;
            if (!isImageUrl(u)) continue;
            todo.add(u);
        }
        if (todo.isEmpty()) return;
        pool.execute(() -> {
            for (String u : todo) {
                try {
                    File f = fileFor(u);
                    Object lock = lockFor(u);
                    //noinspection SynchronizationOnLocalVariableOrMethodParameter
                    synchronized (lock) {
                        if (readCache(f) == null) {
                            Hit hit = download(u, null);
                            if (hit != null) {
                                writeCache(f, hit.data, hit.mime);
                                Log.d(TAG, "预热 " + (hit.data.length / 1024) + "KB " + u);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    /* 预热不该以任何方式被人看见：失败就是「到时候现拉」而已 */
                }
            }
        });
    }

    /* ============================================================ */

    /** 这张图归我管吗？请求头里写着呢：<img> 发的请求 Accept 必然含 image/*。 */
    private static boolean looksLikeImage(String url, Map<String, String> reqHeaders) {
        String accept = reqHeaders == null ? null : reqHeaders.get("Accept");
        if (accept == null) accept = reqHeaders == null ? null : reqHeaders.get("accept");
        if (accept != null && accept.toLowerCase(Locale.US).contains("image/")) return true;
        // 没有 Accept（老版本 WebView 偶尔不给）就退而看扩展名，再不行就别管
        return isImageUrl(url);
    }

    private static boolean isImageUrl(String url) {
        String u = url.split("\\?", 2)[0].split("#", 2)[0].toLowerCase(Locale.US);
        return u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg")
                || u.endsWith(".gif") || u.endsWith(".webp") || u.endsWith(".bmp")
                || u.endsWith(".svg") || u.endsWith(".avif") || u.endsWith(".ico");
    }

    /** 只有 GitHub 自己的域名配拿令牌 —— 同上：令牌不能外泄给第三方图床 */
    private static boolean githubHost(String url) {
        try {
            String host = new java.net.URL(url).getHost().toLowerCase(Locale.US);
            return host.equals("github.com")
                    || host.endsWith(".github.com")
                    || host.equals("githubusercontent.com")
                    || host.endsWith(".githubusercontent.com")
                    || host.equals("github.io")
                    || host.endsWith(".github.io");
        } catch (Exception e) {
            return false;
        }
    }

    private Hit download(String url, Map<String, String> reqHeaders) throws Exception {
        Map<String, String> h = new HashMap<>();
        String accept = pick(reqHeaders, "Accept");
        String ua = pick(reqHeaders, "User-Agent");
        h.put("Accept", accept != null ? accept : DEFAULT_ACCEPT);
        if (ua != null) h.put("User-Agent", ua);

        Http.RawResponse r = Http.requestRaw("GET", url, h);

        /* 私有仓库的 raw 地址、以及 issue 里的私有附件，匿名拉是 404。
         *
         * 为什么不在第一趟就把令牌带上：带 Authorization 的请求共享缓存一律不收，
         * 于是 public repo 的图会次次回源 —— CDN 边缘 HIT 那个「几十毫秒」就没了。
         * 匿名优先、撞墙再补令牌，两边都不亏。 */
        if (r.code == 401 || r.code == 403 || r.code == 404) {
            String t = tokens == null ? null : tokens.get();
            if (t != null && !t.isEmpty() && githubHost(url)) {
                h.put("Authorization", "Bearer " + t);
                r = Http.requestRaw("GET", url, h);
            }
        }
        if (r.code != 200 || r.body == null || r.body.length == 0) return null;
        if (r.body.length > MAX_IMAGE_BYTES) return null;

        Hit hit = new Hit();
        hit.data = r.body;
        String ct = r.header("Content-Type");
        /* 入缓存前就纠正：.type 侧标里存着 text/plain 的话，
         * 24 小时内每一次读缓存都得再纠一遍。 */
        hit.mime = saneMime(mimeOf(ct, url), url);
        for (Map.Entry<String, String> kv : headersToMap(r).entrySet()) {
            hit.headers.put(kv.getKey(), kv.getValue());
        }
        // Content-Type 必须留下：日后这张图从磁盘缓存里出来时，
        // 要靠它告诉 WebView 这是张什么格式的图（文件格式本身 md5 文件名是没有的）
        if (ct != null && !ct.isEmpty()) hit.headers.put("Content-Type", ct);
        return hit;
    }

    private static String pick(Map<String, String> m, String key) {
        if (m == null) return null;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static Map<String, String> headersToMap(Http.RawResponse r) {
        Map<String, String> out = new HashMap<>();
        for (String[] kv : r.headers) {
            if (kv.length < 2 || kv[0] == null || kv[1] == null) continue;
            String k = kv[0].toLowerCase(Locale.US);
            if (DROP_HEADERS.contains(k)) continue;
            out.put(kv[0], kv[1]);
        }
        return out;
    }

    /** 扩展名 → MIME。URL 是比响应头更硬的事实：文件叫什么就是什么。 */
    private static String mimeByExt(String url) {
        String u = url.split("\\?", 2)[0].toLowerCase(Locale.US);
        if (u.endsWith(".png")) return "image/png";
        if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg";
        if (u.endsWith(".gif")) return "image/gif";
        if (u.endsWith(".webp")) return "image/webp";
        if (u.endsWith(".svg")) return "image/svg+xml";
        if (u.endsWith(".bmp")) return "image/bmp";
        if (u.endsWith(".avif")) return "image/avif";
        if (u.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    /**
     * 把「WebView 拒绝当图渲染」的 MIME 纠正成扩展名给出的那个。
     *
     * <p>Content-Type 是 image/* 的照单全收；text/plain、octet-stream、
     * 空值这三类对 <img> 来说都是死路 —— SVG 曾经踩过第一个坑
     * （部分 CDN 给 SVG 回 text/plain），读缓存丢过 .type 的图踩过第二个。
     */
    private static String saneMime(String mime, String url) {
        if (mime == null || mime.isEmpty()
                || mime.startsWith("text/plain")
                || mime.startsWith("application/octet-stream")) {
            String byExt = mimeByExt(url);
            if (!"application/octet-stream".equals(byExt)) return byExt;
        }
        return mime;
    }

    private static String mimeOf(String contentType, String url) {
        if (contentType != null) {
            String ct = contentType.split(";", 2)[0].trim();
            if (!ct.isEmpty()) return ct;
        }
        return mimeByExt(url);
    }

    private WebResourceResponse wrap(Hit hit) {
        hit.headers.put("Content-Length", String.valueOf(hit.data.length));
        return new WebResourceResponse(
                hit.mime, null, 200, "OK", hit.headers,
                new ByteArrayInputStream(hit.data));
    }

    /* ============================================================
     * 磁盘缓存
     * ============================================================ */

    private File fileFor(String url) {
        String name = sha1(url);
        // 两级子目录：一个目录底下堆几千个文件，某些文件系统 Listing 会明显慢下来
        return new File(new File(dir, name.substring(0, 2)), name);
    }

    private Hit readCache(File f) {
        try {
            if (!f.exists() || !f.isFile()) return null;
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            int n = (int) f.length();
            if (n <= 0 || n > MAX_IMAGE_BYTES) return null;
            byte[] buf = new byte[n];
            java.io.InputStream in = new FileInputStream(f);
            try {
                int off = 0, got;
                while (off < n && (got = in.read(buf, off, n - off)) > 0) off += got;
                if (off < n) return null;
            } finally {
                in.close();
            }
            /* 命中一次就刷一次时间，坐实 trim 里那个 LRU 的说法。
             * 少了这一行，清理时看到的就只是「下载时间」，于是天天在用的图
             * 也可能被当成最旧的删掉。 */
            f.setLastModified(System.currentTimeMillis());
            Hit hit = new Hit();
            hit.data = buf;
            hit.mime = "application/octet-stream";   // 没有 .type 就让 WebView 自己嗅探
            File t = new File(f.getParentFile(), f.getName() + ".type");
            if (t.exists()) {
                String s = readText(t);
                if (s != null && !s.isEmpty()) hit.mime = s;
            }
            hit.headers.put("Content-Type", hit.mime);
            return hit;
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeCache(File f, byte[] data, String mime) {
        try {
            File parent = f.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            File tmp = new File(parent, f.getName() + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);        /* 原子：写到一半被杀不会留下半个文件 */
            File t = new File(parent, f.getName() + ".type");
            writeText(t, mime);
            if (writesSinceTrim.incrementAndGet() >= 20) {
                writesSinceTrim.set(0);
                trim();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 超量清理：按「最后用过的时间」从旧到新删一半。
     *
     * 为什么不看最后的访问时间：文件系统普遍不开 atime（开了就是每个读
     * 多一次写，拿 Cz 兑换，不划算）。命中一次缓存就把 lastModified 刷成现在，
     * 于是这个时间实际是「最后一次被取用的时间」而不是下载时间 —— LRU 该看的
     * 本来就是它。
     */
    private void trim() {
        try {
            List<File> all = new ArrayList<>();
            collect(dir, all);
            long total = 0;
            for (File f : all) total += f.length();
            if (all.size() <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) return;
            all.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int count = all.size();
            for (File f : all) {
                if (count <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) break;
                long len = f.length();
                //noinspection ResultOfMethodCallIgnored
                if (f.delete()) {
                    count--;
                    total -= len;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.isFile()) out.add(f);
        }
    }

    private static String readText(File f) {
        try {
            int n = (int) f.length();
            if (n <= 0 || n > 4096) return null;
            byte[] b = new byte[n];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0, got;
                while (off < n && (got = in.read(b, off, n - off)) > 0) off += got;
            } finally {
                in.close();
            }
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            FileOutputStream os = new FileOutputStream(f);
            try {
                os.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private Object lockFor(String url) {
        String key = String.valueOf(url.hashCode());
        Object o = locks.get(key);
        if (o != null) return o;
        Object fresh = new Object();
        Object prev = locks.putIfAbsent(key, fresh);
        return prev != null ? prev : fresh;
    }
}
