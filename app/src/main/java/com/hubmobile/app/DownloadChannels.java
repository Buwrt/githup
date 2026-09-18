package com.hubmobile.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载地址的候选通道：**直连 + 若干加速镜像**。
 *
 * 为什么需要：系统 DownloadManager 是自己直连 GitHub 的。Release 附件、源码包
 * 最终都会 302 到 objects.githubusercontent.com，这个域名在国内不少宽带
 * （广电 / 移动 / 长城…）下又慢又容易中途断，表现就是「几十 KB/s 慢慢爬」或者
 * 干脆「下载失败」。而 App 里的列表、README、图片是通的 —— 它们走的是原生
 * 网络栈 + 加速镜像。所以给下载也补上同样的多通道。
 *
 * 这里只放**纯逻辑**（不碰任何 Android API），这样能直接在 JVM 上跑单元测试，
 * 不用 Robolectric。真正发起下载的是 JsBridge。
 */
final class DownloadChannels {

    private DownloadChannels() { }

    /** 「直连」这条通道的稳定标识 */
    static final String DIRECT = "direct";

    /**
     * 可用的加速镜像前缀（拼法：前缀 + 完整原 URL）。
     *
     * 2026-09 实测（国内网络，取文件前 200KB 计时）：
     *   gh-proxy.com   ~1.1s  ✅ 最快
     *   ghfast.top     ~1.9s  ✅
     *   ghproxy.net    ~2.2s  ✅
     * 已排除：gh.llkk.cc、mirror.ghproxy.com（连接超时）；
     *         gh-proxy.net（回的是 HTML 页面，不是文件）。
     */
    static final String[] MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
    };

    /** 只有这些域名的下载地址才允许走加速镜像（别把无关链接也送去代理） */
    static final String[] MIRRORABLE_HOSTS = {
            "github.com", "objects.githubusercontent.com",
            "raw.githubusercontent.com", "codeload.github.com",
    };

    /** 从 URL 里抠出主机名。不走 Uri.parse —— 那样就没法脱离 Android 单测了 */
    static String hostOf(String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String hostPort = rest.substring(0, end);
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) hostPort = hostPort.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) hostPort = hostPort.substring(0, colon);
        return hostPort.isEmpty() ? null : hostPort.toLowerCase();
    }

    /** 这个地址能不能走加速镜像 */
    static boolean isMirrorable(String url) {
        String h = hostOf(url);
        if (h == null) return false;
        for (String m : MIRRORABLE_HOSTS) {
            if (h.equals(m) || h.endsWith("." + m)) return true;
        }
        return false;
    }

    /**
     * 请求里带了令牌就不能走镜像。
     *
     * 这是硬约束，不是性能取舍：把用户的私有仓库令牌（或 Actions 的临时签名）
     * 发给第三方代理等于把账号交出去。私有仓库附件、Actions 构建产物都属这一类，
     * 只能直连 —— 慢一点，但安全。
     */
    static boolean hasAuthHeader(String headersJson) {
        if (headersJson == null || headersJson.isEmpty()) return false;
        String s = headersJson.toLowerCase();
        return s.contains("authorization") || s.contains("bearer ") || s.contains("\"token\"");
    }

    /** 这条 URL 走的是哪条通道（稳定标识，用于记住「上次哪条通的」） */
    static String channelKey(String url) {
        if (url != null) {
            for (String m : MIRRORS) {
                if (url.startsWith(m)) return m;
            }
        }
        return DIRECT;
    }

    /** 给进度条看的通道名，如「加速 1」/「直连」 */
    static String channelName(String url) {
        if (url != null) {
            for (int i = 0; i < MIRRORS.length; i++) {
                if (url.startsWith(MIRRORS[i])) return "加速 " + (i + 1);
            }
        }
        return "直连";
    }

    /**
     * 生成候选下载地址：加速镜像在前，直连兜底。
     *
     * @param url         原始下载地址
     * @param allowMirror 是否允许走镜像（带令牌时必须传 false）
     * @param lastChannel 上次成功走通的通道（{@link #DIRECT} 或某个镜像前缀），
     *                    传空串表示没有记录 —— 用户网络通常稳定，命中就能
     *                    省掉一轮「先慢后换」的等待
     */
    static List<String> candidates(String url, boolean allowMirror, String lastChannel) {
        List<String> out = new ArrayList<>();
        if (allowMirror && isMirrorable(url)) {
            List<String> mirrors = new ArrayList<>();
            for (String m : MIRRORS) mirrors.add(m + url);
            if (lastChannel != null && !lastChannel.isEmpty() && !DIRECT.equals(lastChannel)) {
                for (int i = 0; i < mirrors.size(); i++) {
                    if (mirrors.get(i).startsWith(lastChannel)) {
                        out.add(mirrors.remove(i));
                        break;
                    }
                }
            }
            out.addAll(mirrors);
        }
        /* 上次是直连通的，就把直连排最前，别让海外用户白绕一圈代理 */
        if (DIRECT.equals(lastChannel)) out.add(0, url);
        else out.add(url);
        return out;
    }
}
