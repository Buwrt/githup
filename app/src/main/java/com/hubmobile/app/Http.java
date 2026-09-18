package com.hubmobile.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 轻量 HTTP/HTTPS 客户端。
 * 直接基于 Socket 实现，因而支持 PATCH 等在 HttpURLConnection 中受限的方法，
 * 并能完整读取响应头（分页 Link 头）。
 */
public final class Http {

    private static final String TAG = "HubHttp";
    /* 连接超时 12s：太长的话，连不上的目标（被墙的域名）会把工作线程
     * 白白占住 20s —— 线程池就那么大，页面自己的请求全在后面排队。 */
    private static final int CONNECT_TIMEOUT = 12000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_REDIRECT = 5;

    public static final class Response {
        public int code;
        public String body = "";
        public String headers = "{}";
    }

    private static final class Raw {
        int code;
        String reason = "";
        List<String[]> headers = new ArrayList<>();
        byte[] body;
    }

    public static Response request(String method, String urlStr, String body, Map<String, String> headers)
            throws IOException {
        return requestBytes(method, urlStr,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
    }

    /** 支持二进制请求体的版本（用于上传 Release 附件等）。 */
    public static Response requestBytes(String method, String urlStr, byte[] body,
                                        Map<String, String> headers) throws IOException {
        String methodU = method.toUpperCase(Locale.US);
        String current = urlStr;
        for (int i = 0; i <= MAX_REDIRECT; i++) {
            Raw raw = execBytes(methodU, current, body, headers);
            String loc = header(raw, "Location");
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.length() > 0) {
                if (raw.code == 303) methodU = "GET";
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            Response r = new Response();
            r.code = raw.code;
            r.body = raw.body == null ? "" : new String(raw.body, StandardCharsets.UTF_8);
            r.headers = headersJson(raw);
            return r;
        }
        throw new IOException("重定向次数过多");
    }

    /** Base64 响应上限：图片类资源足够，防一张超大文件把内存吃光 */
    private static final int MAX_B64_BYTES = 15 * 1024 * 1024;

    /**
     * 二进制**响应**版：body 以 Base64 返回。
     *
     * 给 README 图片这类资源走原生通道用 —— WebView 直连
     * raw.githubusercontent.com 在不少网络下不通，而原生栈是通的。
     * 响应体按字节读进来再编码，绝不能过一遍 String（UTF-8 解码会把
     * 二进制搅碎），所以不能复用 requestBytes。
     */
    public static Response requestB64(String method, String urlStr, byte[] body,
                                      Map<String, String> headers) throws IOException {
        String methodU = method.toUpperCase(Locale.US);
        String current = urlStr;
        for (int i = 0; i <= MAX_REDIRECT; i++) {
            Raw raw = execBytes(methodU, current, body, headers);
            String loc = header(raw, "Location");
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.length() > 0) {
                if (raw.code == 303) methodU = "GET";
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (raw.body != null && raw.body.length > MAX_B64_BYTES)
                throw new IOException("文件太大（超过 " + (MAX_B64_BYTES / 1024 / 1024) + "MB）");
            Response r = new Response();
            r.code = raw.code;
            r.body = raw.body == null ? "" : android.util.Base64.encodeToString(raw.body, android.util.Base64.NO_WRAP);
            r.headers = headersJson(raw);
            return r;
        }
        throw new IOException("重定向次数过多");
    }

    /** 响应头打包成小写 key 的 JSON，供桥接层透传给前端 */
    private static String headersJson(Raw raw) {
        JSONObject jo = new JSONObject();
        for (String[] kv : raw.headers) {
            if (kv[0] == null) continue;
            try {
                jo.put(kv[0].toLowerCase(Locale.US), kv[1]);
            } catch (Exception ignored) {
            }
        }
        return jo.toString();
    }

    private static Raw execBytes(String method, String urlStr, byte[] body, Map<String, String> headers)
            throws IOException {
        URL u = new URL(urlStr);
        boolean secure = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (secure ? 443 : 80);
        String host = u.getHost();
        String path = (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath();
        if (u.getQuery() != null) path += "?" + u.getQuery();

        Socket socket;
        if (secure) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            Socket plain = new Socket();
            plain.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT);
            SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
            ssl.setSoTimeout(READ_TIMEOUT);
            ssl.startHandshake();
            SSLSession session = ssl.getSession();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                try { ssl.close(); } catch (Exception ignored) {}
                throw new IOException("证书主机名校验失败: " + host);
            }
            socket = ssl;
        } else {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
        }

        try {
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (port != (secure ? 443 : 80)) req.append(':').append(port);
            req.append("\r\n");
            req.append("Connection: close\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("User-Agent: ").append(headers.containsKey("User-Agent")
                    ? headers.get("User-Agent") : "HubMobile/1.0").append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if ("User-Agent".equalsIgnoreCase(e.getKey())) continue;
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            byte[] bodyBytes = null;
            if (body != null && body.length > 0) {
                bodyBytes = body;
                req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            }
            req.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (bodyBytes != null) os.write(bodyBytes);
            os.flush();

            InputStream is = socket.getInputStream();
            Raw raw = new Raw();
            String line = readLine(is);
            if (line == null) throw new IOException("空响应");
            String[] parts = line.split(" ", 3);
            raw.code = Integer.parseInt(parts[1]);
            raw.reason = parts.length > 2 ? parts[2] : "";
            while (true) {
                String h = readLine(is);
                if (h == null || h.isEmpty()) break;
                int idx = h.indexOf(':');
                if (idx > 0) {
                    raw.headers.add(new String[]{
                            h.substring(0, idx).trim(),
                            h.substring(idx + 1).trim()
                    });
                }
            }
            String enc = header(raw, "Content-Encoding");
            String len = header(raw, "Content-Length");
            String te = header(raw, "Transfer-Encoding");
            InputStream bodyStream = is;
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                bodyStream = new GZIPInputStream(is);
            }
            if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
                raw.body = readChunked(bodyStream);
            } else if (len != null) {
                int n = Integer.parseInt(len.trim());
                raw.body = readFully(bodyStream, n);
            } else if (raw.code != 204 && raw.code != 304) {
                raw.body = readAll(bodyStream);
            }
            return raw;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') {
                int len = buf.size();
                if (len > 0 && buf.toByteArray()[len - 1] == '\r') len--;
                return new String(buf.toByteArray(), 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
            if (buf.size() > 8192) break;
        }
        return buf.size() > 0 ? new String(buf.toByteArray(), StandardCharsets.ISO_8859_1) : null;
    }

    private static byte[] readFully(InputStream is, int len) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int remaining = len;
        while (remaining > 0) {
            int n = is.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(is);
            if (line == null) break;
            int size;
            try {
                size = Integer.parseInt(line.trim().split(";")[0], 16);
            } catch (Exception e) {
                break;
            }
            if (size == 0) {
                // trailer
                while (true) {
                    String t = readLine(is);
                    if (t == null || t.isEmpty()) break;
                }
                break;
            }
            byte[] chunk = readFully(is, size);
            out.write(chunk, 0, chunk.length);
            readLine(is); // 结尾 CRLF
        }
        return out.toByteArray();
    }

    /**
     * multipart/form-data 上传：边读文件边往 socket 写，不把整个文件读进内存。
     *
     * 为什么要单独一个方法 ——
     *   requestBytes 要求调用方先把整个文件变成 byte[]，一个 25MB 的视频就是
     *   25MB 常驻内存，低端机很容易被系统杀掉。这里改成流式：
     *   头部字符串 → 文件流（8KB 一块搬运）→ 尾部字符串，
     *   内存占用与文件大小无关。
     *
     * 请求体的拼接顺序不能乱：multipart 的字段顺序是签名的一部分，
     * file 字段必须最后，所以头尾由调用方算好传进来，这里只负责搬运。
     *
     * @param head  文件之前的所有内容（含最后那个空行）
     * @param tail  文件之后的所有内容（含结束边界）
     */
    public static Response requestMultipart(String urlStr, InputStream fileStream,
                                            long contentLength,
                                            String contentType,
                                            Map<String, String> headers) throws IOException {
        URL u = new URL(urlStr);
        boolean secure = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (secure ? 443 : 80);
        String host = u.getHost();
        String path = (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath();
        if (u.getQuery() != null) path += "?" + u.getQuery();

        Socket socket = openSocket(secure, host, port);
        try {
            StringBuilder req = new StringBuilder();
            req.append("POST ").append(path).append(" HTTP/1.1\r\n");
            buildHead(req, host, port, secure, headers, contentLength, contentType);
            req.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            os.flush();

            // 前面剩下的部分（已由调用方写进 head 的字节流）
            if (fileStream != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fileStream.read(buf)) > 0) os.write(buf, 0, n);
            }
            os.flush();

            return readResponse(socket.getInputStream());
        } finally {
            closeQuietly(socket);
        }
    }

    /** 建立（可能是 TLS 的）连接，TLS 时做主机名校验 */
    private static Socket openSocket(boolean secure, String host, int port) throws IOException {
        if (!secure) {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT);
            s.setSoTimeout(READ_TIMEOUT);
            return s;
        }
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket plain = new Socket();
        plain.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT);
        SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
        ssl.setSoTimeout(READ_TIMEOUT);
        ssl.startHandshake();
        SSLSession session = ssl.getSession();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
            closeQuietly(ssl);
            throw new IOException("证书主机名校验失败: " + host);
        }
        return ssl;
    }

    /** 拼公共请求头（含 Content-Length / Content-Type） */
    private static void buildHead(StringBuilder req, String host, int port, boolean secure,
                                  Map<String, String> headers, long contentLength,
                                  String contentType) {
        req.append("Host: ").append(host);
        if (port != (secure ? 443 : 80)) req.append(':').append(port);
        req.append("\r\n");
        req.append("Connection: close\r\n");
        req.append("Accept-Encoding: identity\r\n");
        req.append("User-Agent: ").append(headers.containsKey("User-Agent")
                ? headers.get("User-Agent") : "HubMobile/1.0").append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(e.getKey())) continue;
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            if ("Content-Length".equalsIgnoreCase(e.getKey())) continue;
            req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n");
        req.append("Content-Length: ").append(contentLength).append("\r\n");
    }

    /** 读一个完整响应（状态行 + 头 + 体） */
    private static Response readResponse(InputStream is) throws IOException {
        Raw raw = new Raw();
        String line = readLine(is);
        if (line == null) throw new IOException("空响应");
        String[] parts = line.split(" ", 3);
        raw.code = Integer.parseInt(parts[1]);
        raw.reason = parts.length > 2 ? parts[2] : "";
        while (true) {
            String h = readLine(is);
            if (h == null || h.isEmpty()) break;
            int idx = h.indexOf(':');
            if (idx > 0) {
                raw.headers.add(new String[]{
                        h.substring(0, idx).trim(),
                        h.substring(idx + 1).trim()
                });
            }
        }
        String enc = header(raw, "Content-Encoding");
        String len = header(raw, "Content-Length");
        String te = header(raw, "Transfer-Encoding");
        InputStream bodyStream = is;
        if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
            bodyStream = new GZIPInputStream(is);
        }
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            raw.body = readChunked(bodyStream);
        } else if (len != null) {
            int n = Integer.parseInt(len.trim());
            raw.body = readFully(bodyStream, n);
        } else if (raw.code != 204 && raw.code != 304) {
            raw.body = readAll(bodyStream);
        }

        Response r = new Response();
        r.code = raw.code;
        r.body = raw.body == null ? "" : new String(raw.body, StandardCharsets.UTF_8);
        r.headers = headersJson(raw);
        return r;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }

    private static String header(List<String[]> headers, String name) {
        for (String[] kv : headers) if (kv[0] != null && kv[0].equalsIgnoreCase(name)) return kv[1];
        return null;
    }

    private static String header(Raw raw, String name) {
        return header(raw.headers, name);
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }
}
