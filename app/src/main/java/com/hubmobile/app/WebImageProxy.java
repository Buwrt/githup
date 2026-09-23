package com.hubmobile.app;

import android.content.Context;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    /** 内存缓存上限。图是字节数组，6MB 大约能放几十张常见截图。 */
    private static final int MEM_MAX = 6 * 1024 * 1024;
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
        if (!isImageRequest(url, reqHeaders)) return null;

        /* 分片请求不接：我们给的是完整字节，拿它回答 Range 会让解码器读错位。
         * 图片请求一般不会带 Range，带了的（个别图床）就让它自己来。 */
        if (hasHeader(reqHeaders, "Range")) return null;

        Entry hit = memGet(url);
        if (hit != null) return respond(hit, "memory");

        byte[] disk = diskRead(ctx, url);
        if (disk != null) {
            String type = mimeOf(disk);
            if (type != null) {
                memPut(url, disk, type);
                return respond(new Entry(disk, type), "disk");
            }
        }

        String token = tokenFor(ctx, url);
        byte[] body = null;
        String type = null;

        /* 先带令牌试一次（私有仓库的附件要靠它），
         * 4xx/5xx 再不带试一次 —— 带签名的 CDN 见到 Authorization 会回 400，
         * 只试一次的话用户看到的就是「图明明传上去了，就是不显示」。 */
        if (token.length() > 0) {
            Http.Bytes r = fetch(url, bearerHeaders(token));
            if (r != null && r.code == 200 && r.body.length > 0) {
                body = r.body;
                type = pickType(r.contentType, body);
            }
        }
        if (body == null) {
            Http.Bytes r = fetch(url, plainHeaders());
            if (r != null && r.code == 200 && r.body.length > 0) {
                body = r.body;
                type = pickType(r.contentType, body);
            }
        }

        // 拿回来的不是图（比如 404 的 HTML 错误页）：不接管，让 WebView 自己再试
        if (body == null || type == null) return null;

        if (body.length <= SINGLE_MAX) {
            memPut(url, body, type);
            diskWrite(ctx, url, body, type);
        }
        return respond(new Entry(body, type), "network");
    }

    /* ================= 判定 ================= */

    private static final String[] IMAGE_HOSTS = {
            "avatars.githubusercontent.com",
            "camo.githubusercontent.com",
            "user-images.githubusercontent.com",
            "private-user-images.githubusercontent.com",
            "github.githubassets.com"
            /* raw.githubusercontent.com 故意不在表里：它也用来取文件正文
             * （page-repo.js 的 fetchRaw），那种请求的 Accept 是「什么都要」，
             * 一旦按主机一刀切就会被当成图片接管，正文就变成一堆字节了。
             * 那里的图片都带扩展名，下面那条扩展名判定够用了。 */
    };

    private static final String[] IMAGE_EXT = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".avif", ".svg"
    };

    /**
     * 这个请求是不是在要一张图。
     *
     * 主要看 Accept 头 —— <img> 发出来的一定带 image/*，这是最准的信号。
     * 扩展名和「已知图床」是两道兜底：万一某些 WebView 版本没把头透传过来，
     * 头像（avatars.githubusercontent.com/u/123?v=4 这种没扩展名的）也不至于漏掉。
     */
    private static boolean isImageRequest(String url, Map<String, String> h) {
        String u = url.toLowerCase(Locale.US);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;

        String acc = headerValue(h, "Accept");
        if (acc != null && acc.toLowerCase(Locale.US).contains("image/")) return true;

        int q = u.indexOf('?');
        int h2 = u.indexOf('#');
        int cut = q >= 0 ? q : (h2 >= 0 ? h2 : u.length());
        String path = u.substring(0, cut);
        for (String ext : IMAGE_EXT) {
            if (path.endsWith(ext)) return true;
        }
        /* GitHub 上传的附件没有扩展名（assets/<uuid>），只能认路径 */
        if (path.contains("github.com/user-attachments/")) return true;
        try {
            String host = new java.net.URL(url).getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.US);
                for (String s : IMAGE_HOSTS) {
                    if (host.equals(s)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        if (!gh) return "";
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
        return h;
    }

    private static Map<String, String> plainHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        return h;
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

    private static boolean hasHeader(Map<String, String> h, String name) {
        String v = headerValue(h, name);
        return v != null && v.length() > 0;
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
