package com.hubmobile.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 上传断点账本：{@code 文件路径 → { blob sha, 写入时刻 }}。
 *
 * 干什么用 ——
 *
 * 文件夹上传要先把每个文件 POST 成 blob，拿到 sha 再合成提交。传 235 个
 * 文件、294MB，弱网下传到第 200 个断掉是常事。以前断了就是从头再来：
 * 前面那 199 个 blob **其实已经安安稳稳躺在 GitHub 上了**，但代码不知道，
 * 下次照样逐个 POST 一遍。
 *
 * 有了这个账本，下次开传前先查：这个路径上次拿到过 sha 吗？有就直接挂
 * tree，不用再传。
 *
 * ══ 为什么必须带时间戳 ══
 *
 * 只拿到 sha、还没挂进任何 tree/commit 的 blob 是「不可达对象」。
 * git 的 gc.pruneExpire 默认 2 周，GitHub 会按自己的调度回收不可达对象
 * （它不公开确切周期）。所以账本存了三周再回来用，那个 sha 可能已经
 * 没了 —— 拿它去 POST /git/trees 会直接报 422 "does not exist"。
 *
 * 时间戳就是干这个的：超过 LEDGER_TTL_MS 的条目一概不信任，老老实实重传。
 * 但**光靠时间戳只是猜测**，调用方还会用 GET /git/blobs/{sha} 实测一遍
 * （见 page-repo.js），确认还在才用。时间戳负责粗筛省请求，GET 负责兜底。
 *
 * ══ 为什么用 SharedPreferences 而不是 WebView 的 localStorage ══
 *
 * 用户在系统里「清除 WebView 数据」会把 localStorage 一起抹掉，账本白记。
 * SharedPreferences 跟 WebView 生命周期解耦，清 WebView 数据不影响它。
 * 数据量很小（几百条 path→sha，几 KB），完全够用。
 */
final class UploadLedger {

    private UploadLedger() { }

    private static final String PREF = "upload_ledger";

    /** 账本条目的有效期。git 默认 2 周，这里保守取 7 天。 */
    static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final int MAX_ENTRIES = 3000;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * 取某个仓库的账本（JSON：{@code path → {"s": sha, "t": 写入时刻}}）。
     * 顺手丢掉过期和超限的条目 —— 不清理的话，反复传同一个仓库会让这张
     * 表一直涨，而里面的老条目早就没用了。
     */
    static String read(Context c, String key) {
        try {
            String raw = prefs(c).getString(key, null);
            if (raw == null || raw.isEmpty()) return "{}";
            JSONObject all = new JSONObject(raw);
            long now = System.currentTimeMillis();
            JSONObject out = new JSONObject();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String p = it.next();
                JSONObject v = all.optJSONObject(p);
                if (v == null) continue;
                String sha = v.optString("s", "");
                long t = v.optLong("t", 0L);
                if (sha.isEmpty() || t <= 0) continue;
                if (now - t > TTL_MS) continue;      // 过期，丢掉
                JSONObject o = new JSONObject();
                o.put("s", sha);
                o.put("t", t);
                out.put(p, o);
            }
            /* 超限时按时间排序，只留最新的那批。
               out 后面要被 lambda 捕获，所以中间结果必须落到一个 final 变量上 ——
               直接对 out 重新赋值的话它就不是 effectively final 了，
               排序 lambda 里引用它会编译不过。 */
            JSONObject picked = out;
            if (picked.length() > MAX_ENTRIES) {
                java.util.List<String> paths = new java.util.ArrayList<>();
                Iterator<String> i2 = picked.keys();
                while (i2.hasNext()) paths.add(i2.next());
                final JSONObject src = picked;
                paths.sort((a, b) -> Long.compare(src.optJSONObject(b).optLong("t", 0L),
                        src.optJSONObject(a).optLong("t", 0L)));
                JSONObject trimmed = new JSONObject();
                for (int i = 0; i < MAX_ENTRIES && i < paths.size(); i++) {
                    trimmed.put(paths.get(i), src.optJSONObject(paths.get(i)));
                }
                picked = trimmed;
            }
            out = picked;
            prefs(c).edit().putString(key, out.toString()).apply();
            return out.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** 记一批：{@code path → sha} */
    static void write(Context c, String key, String pairsJson) {
        try {
            JSONObject add = new JSONObject(pairsJson);
            if (add.length() == 0) return;
            JSONObject all;
            String raw = prefs(c).getString(key, null);
            try {
                all = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
            } catch (Throwable ignored) {
                all = new JSONObject();
            }
            long now = System.currentTimeMillis();
            Iterator<String> it = add.keys();
            while (it.hasNext()) {
                String p = it.next();
                String sha = add.optString(p, "");
                if (sha.isEmpty()) continue;
                JSONObject v = new JSONObject();
                v.put("s", sha);
                v.put("t", now);
                all.put(p, v);
            }
            /* 写完顺手做一次清理，避免无限增长 */
            read(c, key);
            prefs(c).edit().putString(key, all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 整批上传彻底成功后清掉 —— 成功之后账本就没用了，别留着占空间 */
    static void clear(Context c, String key) {
        try {
            prefs(c).edit().remove(key).apply();
        } catch (Throwable ignored) { }
    }
}
