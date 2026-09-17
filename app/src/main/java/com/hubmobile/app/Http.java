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
            JSONObject jo = new JSONObject();
            for (String[] kv : raw.headers) {
                if (kv[0] == null) continue;
                try {
                    jo.put(kv[0].toLowerCase(Locale.US), kv[1]);
                } catch (Exception ignored) {
                }
            }
            r.headers = jo.toString();
            return r;
        }
        throw new IOException("重定向次数过多");
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
