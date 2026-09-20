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
     * 为什么从 3 条扩到 5 条：3 条的时候用户最常见的反馈就是「加速 1 2 3
     * 全试完了还是失败」—— 通路太少，赶上其中两条抽风就没退路了。
     * 加速镜像都是免费公共代理，**单条随时可能限速、挂掉、或回一个错误页**，
     * 所以真正有用的不是「有几个名字」，而是「有几条独立的路」。
     *
     * 2026-09 实测（国内网络，取 Release 附件的前 2MB / 前 10MB 计时，
     * 同一文件、同一时间窗内取最快值，且**逐个验证过拿到的确实是文件本身**
     * 而不是目录页或错误页）：
     *
     * | 镜像 | Release 附件 | 源码 zip | 说明 |
     * |---|---|---|---|
     * | ghproxy.imciel.com | ~460~500 KB/s | ~2.0 MB/s | 最快、最稳 |
     * | gh.xxooo.cf        | ~340~540 KB/s | ~2.3 MB/s | 稳定 |
     * | gh-proxy.com       | ~160~360 KB/s | ~4.3 MB/s | 老牌，速度波动大 |
     * | ghfast.top         | ~190~370 KB/s | ~0.9 MB/s | 稳定 |
     * | ghproxy.net        | ~20~50 KB/s   | 慢        | 最慢，留作最后一条 |
     *
     * 顺序按实测速度排，快的在前。**注意别按「谁最知名」排** ——
     * 老牌的那几个恰好不是最快的。
     *
     * 已排除（2026-09 实测，别再往回加）：
     *   ghproxy.cc / gh.llkk.cc / github.moeyy.xyz / hub.gitmirror.com /
     *   gh.6ycloud.com / gh.waitship.top / ghp.ci / gh.zwnes.com /
     *   gh.jasonzeng.dev / github.7boe.top / gh.342800.xyz / gh.7ke.xyz
     *     —— 连接直接超时或拒连；
     *   ghps.cc / gh.ddlc.top
     *     —— 返回 404 或一个 HTML 目录页，拿不到文件；
     *   gh-proxy.net / gitproxy.click
     *     —— 回 401（要授权），等于不可用；
     *   ghproxy.cn / ghproxy.link / ghproxy.homeboyc.cn
     *     —— 回的是网页（HTML），不是文件；
     *   cdn.gh-proxy.com
     *     —— 只传回了一部分就断（774KB 的文件只拿到 208KB），
     *        正是「下完却装不上」的典型来源，坚决不用。
     *
     * 维护提示：这些都是第三方免费服务，随时可能关停。加新镜像时**必须真的
     * 下载一个 Release 附件验证**（不能只看域名能不能打开），并且确认拿到的是
     * 文件本身。顺序也要按当时的实测速度重排。
     */
    static final String[] MIRRORS = {
            "https://ghproxy.imciel.com/",
            "https://gh.xxooo.cf/",
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
        /*
          直连永远放在最后兜底。

          这里原来有一条「上次是直连通的就把直连排最前，别让海外用户白绕一圈
          代理」的优化 —— 想法没错，实测证明它是个坑：

          1) 这个偏好是**全局**的，不分文件也不分时间。某一次下载（哪怕是个
             小文件）恰好直连走通了，直连就被永久记成「优先通道」；
          2) 之后**每一次**下载都先试直连。国内网络下直连 GitHub 的 Release
             附件（会 302 到 objects.githubusercontent.com）正是最慢的那条；
          3) 更糟的是换道有代价：要熬过 GRACE_MS 宽限期才开始判慢，
             再连续 SLOW_STRIKES 轮才真的切走 —— 用户眼睁睁看着进度条
             卡在 0 B 十几秒，就是「下载突然变慢了」的真相。

          海外用户绕代理确实会慢一点，但「慢一点」远好过「先卡十几秒」。
          真要优化海外，应该按实际测速动态排序，而不是让一次偶然的成功
          永久改写全局顺序。
        */
        out.add(url);
        return out;
    }
}
