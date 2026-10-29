package com.hubmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件夹上传：跑在**前台服务**里，不再挂在页面上。
 *
 * ═══ 为什么非要搬出 WebView ═══
 *
 * 原先整套流程（逐个文件 POST /git/blobs，再建 tree / commit / 更新 ref）
 * 都写在前端 JS 里：一个 Promise 链串着 235 个 `Native.uploadMultipartB64`
 * 回调。它有两个躲不掉的毛病：
 *
 *   1. **页面一走，上传就断**。用户提交完想干点别的、或者干脆退出仓库页，
 *      Router 换页 → 那条 Promise 链虽然还挂着，但 WebView 一旦被回收、
 *      或者用户从最近任务划掉 App，JS 直接停摆。已经传上去的 blob 全废，
 *      下次得从第一个文件重新来。
 *
 *      用户看到的正是「点提交之后不能退出这个页面，退出就看不到百分之多少了，
 *      它就不继续上传了」。
 *
 *   2. **一个失败，整批作废**。原来的逻辑是失败的收集起来最后一起抛错，
 *      整批不合成提交 —— 于是 234 个文件传成功了、第 235 个抖了一下，
 *      294MB 白传，还得重来一遍。
 *
 * 搬到服务里之后：
 *   · 上传在原生线程跑，跟页面、跟 WebView 完全解耦，退出照传；
 *   · 通知栏常驻一条进度通知，百分比、已传字节随时可看；
 *   · 单个文件失败自动重试，重试不成也只是跳过这一个，
 *     其余成功的 blob sha 全部记下来 —— 点「继续」从断点接着传。
 *
 * ═══ 关于速度 ═══
 *
 * 慢的根因有三个，逐个处理：
 *
 *   · **等待被摊平**：原来是「第 N 个等第 N-1 个彻底结束才出发」，
 *     总耗时 = Σ(传输 + 一次完整往返)。改成固定 6 路并发之后，等待只剩
 *     一轮往返，文件越多提速越明显。
 *   · **失败不再导致重传全部**：断点续传把已经拿到 sha 的文件跳过。
 *   · **重试**：网络抖动这种瞬时错误，重试一次就好了，不必惊动用户。
 *
 * Base64 那 33% 的膨胀躲不掉 —— /git/blobs 的 content 字段只收 base64，
 * 这是 GitHub API 的规定。所以界面上会如实写出「编码后约 X MB」。
 */
public class UploadService extends Service {

    static final String ACTION_START = "com.hubmobile.upload.START";
    static final String ACTION_CANCEL = "com.hubmobile.upload.CANCEL";
    static final String EXTRA_JSON = "task";

    private static final String CHANNEL = "githup_upload";
    private static final int NOTIFY_ID = 7402;
    private static final String PREFS = "githup_upload";
    private static final String KEY_DONE = "done";
    private static final String KEY_TASK = "last_task";

    /** 并发路数（默认）。移动端 6 路基本能吃满带宽，再往上 TLS 握手和重传会互相打架。 */
    private static final int LANES = 6;

    /* 小文件多的时候可以再多开几路。

       235 个文件里大部分只有几百 KB，这类请求的时间几乎全花在
       「一次往返」上（连接 + 握手 + 等响应），真正传数据只占很小一部分。
       这时候并发路数是决定性的：路数翻倍，总耗时接近减半。

       反过来，大文件（几 MB 以上）是带宽受限的，路数开多了只会互相抢带宽、
       还把每个都拖慢 —— 那种情况就该少开几路。

       所以按平均大小定：越小越敢开。 */
    private static final int LANES_SMALL = 12;
    private static final long SMALL_FILE = 1024 * 1024;      // 1MB 以下算小文件

    /** 单个文件最多试几次。瞬时抖动一次就过，真不行的三次也够了。 */
    private static final int MAX_TRY = 3;

    /* ══════════ 任务状态（静态：供 JsBridge 随时查询）══════════ */

    private static final Object LOCK = new Object();
    private static volatile boolean running = false;
    private static volatile boolean cancelRequested = false;
    private static volatile int sTotal = 0;
    private static volatile int sDone = 0;
    private static volatile int sFailed = 0;
    private static volatile long sBytes = 0;
    private static volatile long sBytesTotal = 0;
    private static volatile String sRepo = "";
    private static volatile String sBranch = "";
    private static volatile String sDir = "";
    private static volatile String sError = "";
    private static volatile String sCommit = "";

    /** 当前这一批里已经拿到 sha 的：path → sha */
    private static final Map<String, String> sShas = new HashMap<>();

    /**
     * 给前端的状态快照。
     *
     * 页面在的时候可以直接拿它画进度条；页面不在也无所谓 ——
     * 通知栏那条才是主渠道，这里只是「顺便看看」。
     */
    static String statusJson() {
        JSONObject o = new JSONObject();
        try {
            synchronized (LOCK) {
                o.put("running", running);
                o.put("total", sTotal);
                o.put("done", sDone);
                o.put("failed", sFailed);
                o.put("bytes", sBytes);
                o.put("bytesTotal", sBytesTotal);
                o.put("repo", sRepo);
                o.put("branch", sBranch);
                o.put("dir", sDir);
                o.put("error", sError);
                o.put("commit", sCommit);
                o.put("percent", sTotal > 0 ? (int) (sDone * 100L / sTotal) : 0);
            }
        } catch (Throwable ignored) { }
        return o.toString();
    }

    static boolean isRunning() {
        return running;
    }

    /** 还有没传完的（供「继续上传」按钮判断要不要显示） */
    static boolean hasPending(Context ctx) {
        if (running) return false;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String task = p.getString(KEY_TASK, "");
            if (task == null || task.isEmpty()) return false;
            JSONObject t = new JSONObject(task);
            JSONArray files = t.optJSONArray("files");
            if (files == null || files.length() == 0) return false;
            Map<String, String> done = loadDone(ctx);
            return done.size() < files.length();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读断点：已完成 path → sha */
    static Map<String, String> loadDone(Context ctx) {
        Map<String, String> m = new HashMap<>();
        try {
            String raw = prefs(ctx).getString(KEY_DONE, "");
            if (raw == null || raw.isEmpty()) return m;
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.optString(k, ""));
            }
        } catch (Throwable ignored) { }
        return m;
    }

    private static void saveDone(Context ctx, Map<String, String> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            prefs(ctx).edit().putString(KEY_DONE, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static void clearDone(Context ctx) {
        try {
            prefs(ctx).edit().remove(KEY_DONE).remove(KEY_TASK).apply();
        } catch (Throwable ignored) { }
    }

    /** 断点表的 key：同一个仓库同一目录同一路径才算同一个文件 */
    private static String doneKey(String repo, String branch, String dir, String path) {
        return repo + "|" + branch + "|" + dir + "|" + path;
    }

    /* ══════════ 服务本体 ═══════════ */

    private Handler handler;
    private ExecutorService pool;
    private boolean foregroundReady = false;
    private long lastNotifyAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        ensureChannels();
        /*
         * 与 TotpService 同样的教训：startForeground 绝不能 try-catch 吞掉
         * 异常继续跑 —— 「必须进前台」的标记会一直挂着，10 秒后系统看门狗
         * 抛 ForegroundServiceDidNotStartInTimeException 杀进程。
         * 失败就立刻 stopSelf，让系统尽快移除 ServiceRecord。
         */
        if (!promote(buildProgress(0, 0, 0, 0))) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (!foregroundReady && !promote(buildProgress(0, 0, 0, 0))) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        String json = intent != null ? intent.getStringExtra(EXTRA_JSON) : null;
        if (json == null || json.isEmpty()) {
            /* 没有带任务 → 当成「继续上次没传完的」 */
            json = prefs(this).getString(KEY_TASK, "");
        }
        if (json == null || json.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        /* 任务参数落一份：中途失败 / 进程被杀，下次「继续」还找得回来 */
        try {
            prefs(this).edit().putString(KEY_TASK, json).apply();
        } catch (Throwable ignored) { }

        if (running) return START_STICKY;      // 已经在传了，别开第二遍

        pool = Executors.newFixedThreadPool(LANES);
        final String task = json;
        new Thread(() -> runTask(task)).start();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        /*
         * 用户从最近任务划掉 App —— 上传**不跟着停**。
         *
         * 这正是搬到服务的意义：他要的就是退出后还能接着传。
         * 真正想取消走通知栏 / 上传页的「取消」，那会发 ACTION_CANCEL。
         */
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { if (pool != null) pool.shutdownNow(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ══════════ 上传主流程 ══════════ */

    private void runTask(String json) {
        String repo = "", branch = "main", dir = "", message = "", token = "";
        final List<String[]> files = new ArrayList<>();     // [uri, path, size]
        try {
            JSONObject t = new JSONObject(json);
            repo = t.optString("repo", "");
            branch = t.optString("branch", "main");
            dir = t.optString("dir", "");
            message = t.optString("message", "Add files via upload");
            token = t.optString("token", "");
            JSONArray arr = t.optJSONArray("files");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null) continue;
                    files.add(new String[]{
                            f.optString("uri", ""),
                            f.optString("path", ""),
                            String.valueOf(f.optLong("size", 0L))
                    });
                }
            }
        } catch (Throwable e) {
            finish(false, "任务参数解析失败");
            return;
        }

        if (repo.isEmpty() || token.isEmpty() || files.isEmpty()) {
            finish(false, "任务参数不完整");
            return;
        }

        synchronized (LOCK) {
            running = true;
            cancelRequested = false;
            sTotal = files.size();
            sDone = 0;
            sFailed = 0;
            sBytes = 0;
            sBytesTotal = 0;
            sRepo = repo;
            sBranch = branch;
            sDir = dir;
            sError = "";
            sCommit = "";
        }
        for (String[] f : files) {
            long sz = 0;
            try { sz = Long.parseLong(f[2]); } catch (Throwable ignored) { }
            sBytesTotal += sz;
        }

        /* 断点：上次没传完的这次直接跳过 */
        final Map<String, String> doneMap = loadDone(this);
        for (String[] f : files) {
            String k = doneKey(repo, branch, dir, f[1]);
            String sha = doneMap.get(k);
            if (sha != null && !sha.isEmpty()) {
                synchronized (LOCK) {
                    sShas.put(f[1], sha);
                    sDone++;
                    try { sBytes += Long.parseLong(f[2]); } catch (Throwable ignored) { }
                }
            }
        }
        notifyProgress(true);

        final String fRepo = repo, fBranch = branch, fDir = dir, fMsg = message, fToken = token;
        final AtomicInteger cursor = new AtomicInteger(0);
        final AtomicInteger fails = new AtomicInteger(0);
        final AtomicLong bytes = new AtomicLong(sBytes);

        /* 按平均文件大小定并发路数：小文件多就多开几路（省往返），
           大文件为主就少开（别互相抢带宽）。 */
        int lanes = LANES;
        if (!files.isEmpty()) {
            long sumSize = 0;
            for (String[] f : files) {
                try { sumSize += Long.parseLong(f[2]); } catch (Throwable ignored) { }
            }
            long avg = sumSize / files.size();
            if (avg < SMALL_FILE) lanes = LANES_SMALL;
        }

        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            fs.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        if (cancelRequested || Thread.currentThread().isInterrupted()) return;
                        int i = cursor.getAndIncrement();
                        if (i >= files.size()) return;
                        String[] f = files.get(i);
                        String k = doneKey(fRepo, fBranch, fDir, f[1]);
                        if (doneMap.containsKey(k)) continue;   // 断点已过

                        String sha = null;
                        for (int tryNo = 1; tryNo <= MAX_TRY; tryNo++) {
                            if (cancelRequested) return;
                            try {
                                sha = postBlob(fRepo, fToken, f[0]);
                                if (sha != null && !sha.isEmpty()) break;
                            } catch (Throwable ignored) {
                                sha = null;
                            }
                            /* 退避：1.5s / 3s，瞬时抖动一次就过 */
                            try { Thread.sleep(1500L * tryNo); } catch (Throwable ignored) { }
                        }

                        if (sha == null || sha.isEmpty()) {
                            fails.incrementAndGet();
                        } else {
                            synchronized (LOCK) {
                                sShas.put(f[1], sha);
                            }
                            doneMap.put(k, sha);
                            /* 每成功一个就落一次盘 —— 中途被杀也不白传 */
                            saveDone(UploadService.this, doneMap);
                            long sz = 0;
                            try { sz = Long.parseLong(f[2]); } catch (Throwable ignored) { }
                            bytes.addAndGet(sz);
                        }
                        synchronized (LOCK) {
                            sDone++;
                            sFailed = fails.get();
                            sBytes = bytes.get();
                        }
                        notifyProgress(false);
                    }
                }
            }));
        }
        for (java.util.concurrent.Future<?> fu : fs) {
            try { fu.get(); } catch (Throwable ignored) { }
        }

        if (cancelRequested) {
            running = false;
            return;
        }

        int failed = fails.get();
        if (failed > 0) {
            synchronized (LOCK) { sFailed = failed; running = false; }
            /* 成功的 sha 已经落盘了，不清除 —— 下次「继续」从断点接着来 */
            notifyDone(false, failed + " 个文件没传成功，已保留进度，可继续上传");
            stopSelf();
            return;
        }

        /* 全部拿到 sha → 合成一个提交（与官网一致，历史里只有一条） */
        try {
            String sha = commitAll(fRepo, fToken, fBranch, fDir, fMsg, files);
            synchronized (LOCK) { sCommit = sha; running = false; }
            clearDone(this);
            notifyDone(true, "已上传 " + files.size() + " 个文件");
        } catch (Throwable t) {
            synchronized (LOCK) {
                running = false;
                sError = t.getMessage() == null ? "提交失败" : t.getMessage();
            }
            /* blob 都还在，不清除断点 —— 修好再「继续」不用重传 */
            notifyDone(false, "上传完了，但合成提交失败：" + sError);
        }
        stopSelf();
    }

    /** 单个文件 → POST /git/blobs，返回 blob sha */
    private String postBlob(String repo, String token, String uriStr) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");

        Uri uri = Uri.parse(uriStr);
        long fileLen = FilePick.sizeOf(this, uri);
        byte[] head = "{\"content\":\"".getBytes("UTF-8");
        byte[] tail = "\",\"encoding\":\"base64\"}".getBytes("UTF-8");
        long total = head.length + FilePick.base64Length(fileLen) + tail.length;
        InputStream in = null;
        try {
            in = new java.io.SequenceInputStream(
                    new java.io.ByteArrayInputStream(head),
                    new java.io.SequenceInputStream(
                            FilePick.base64Encoding(FilePick.open(this, uri)),
                            new java.io.ByteArrayInputStream(tail)));
            Http.Response r = Http.requestMultipart("POST",
                    "https://api.github.com/repos/" + repo + "/git/blobs",
                    in, total, "application/json", headers);
            if (r == null || r.code < 200 || r.code >= 300) {
                throw new Exception(r == null ? "no response" : ("HTTP " + r.code));
            }
            String sha = null;
            try { sha = new JSONObject(r.body == null ? "" : r.body).optString("sha", ""); }
            catch (Throwable ignored) { }
            if (sha == null || sha.isEmpty()) throw new Exception("没有返回 sha");
            return sha;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 建 tree → commit → 更新 ref。全程原子：失败仓库不会有半套文件。 */
    private String commitAll(String repo, String token, String branch, String dir,
                             String message, List<String[]> files) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("Content-Type", "application/json");

        String base = "https://api.github.com/repos/" + repo;

        Http.Response ref = Http.request("GET", base + "/git/ref/heads/"
                + uriSafe(branch), null, headers);
        if (ref == null || ref.code >= 400) throw new Exception("读分支失败 HTTP "
                + (ref == null ? -1 : ref.code));
        String parentSha = new JSONObject(ref.body).getJSONObject("object").optString("sha", "");
        if (parentSha.isEmpty()) throw new Exception("拿不到分支顶端");

        Http.Response cr = Http.request("GET", base + "/git/commits/" + parentSha,
                null, headers);
        if (cr == null || cr.code >= 400) throw new Exception("读提交失败 HTTP "
                + (cr == null ? -1 : cr.code));
        String baseTree = new JSONObject(cr.body).getJSONObject("tree").optString("sha", "");

        JSONArray tree = new JSONArray();
        for (String[] f : files) {
            String sha;
            synchronized (LOCK) { sha = sShas.get(f[1]); }
            if (sha == null || sha.isEmpty()) throw new Exception("缺少 " + f[1] + " 的 sha");
            JSONObject e = new JSONObject();
            e.put("path", dir.isEmpty() ? f[1] : dir + "/" + f[1]);
            e.put("mode", "100644");
            e.put("type", "blob");
            e.put("sha", sha);
            tree.put(e);
        }

        JSONObject treeBody = new JSONObject();
        if (!baseTree.isEmpty()) treeBody.put("base_tree", baseTree);
        treeBody.put("tree", tree);
        Http.Response tr = Http.request("POST", base + "/git/trees",
                treeBody.toString(), headers);
        if (tr == null || tr.code >= 400) throw new Exception("建 tree 失败 HTTP "
                + (tr == null ? -1 : tr.code));
        String treeSha = new JSONObject(tr.body).optString("sha", "");

        JSONObject cmBody = new JSONObject();
        cmBody.put("message", message);
        cmBody.put("tree", treeSha);
        JSONArray parents = new JSONArray();
        parents.put(parentSha);
        cmBody.put("parents", parents);
        Http.Response cm = Http.request("POST", base + "/git/commits",
                cmBody.toString(), headers);
        if (cm == null || cm.code >= 400) throw new Exception("建提交失败 HTTP "
                + (cm == null ? -1 : cm.code));
        String commitSha = new JSONObject(cm.body).optString("sha", "");

        JSONObject rfBody = new JSONObject();
        rfBody.put("sha", commitSha);
        rfBody.put("force", false);
        Http.Response rf = Http.request("PATCH", base + "/git/refs/heads/"
                + uriSafe(branch), rfBody.toString(), headers);
        if (rf == null || rf.code >= 400) throw new Exception("更新分支失败 HTTP "
                + (rf == null ? -1 : rf.code));

        return commitSha;
    }

    private static String uriSafe(String s) {
        return String.valueOf(s).replace(" ", "%20");
    }

    /* ══════════ 通知 ══════════ */

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel c = new NotificationChannel(CHANNEL, "文件上传",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("文件夹上传的进度与结果");
            c.setSound(null, null);
            c.enableVibration(false);
            nm.createNotificationChannel(c);
        } catch (Throwable ignored) { }
    }

    private Notification.Builder newBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL);
        }
        return new Notification.Builder(this);
    }

    private boolean promote(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, n);
            }
            foregroundReady = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private PendingIntent openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, 7403, i, flags);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 进度通知。force=true 时不管节流立刻刷一次。 */
    private void notifyProgress(boolean force) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                /* 235 个文件逐个完成，每次都 notify 太吵 —— 600ms 刷一次足够 */
                if (!force && now - lastNotifyAt < 600) return;
                lastNotifyAt = now;
                int done, total, failed;
                long bytes, bytesTotal;
                String repo;
                synchronized (LOCK) {
                    done = sDone; total = sTotal; failed = sFailed;
                    bytes = sBytes; bytesTotal = sBytesTotal; repo = sRepo;
                }
                try {
                    Notification.Builder b = newBuilder();
                    b.setSmallIcon(android.R.drawable.stat_sys_upload)
                            .setContentTitle("正在上传到 " + repo)
                            .setContentText(done + " / " + total + " 个文件"
                                    + (failed > 0 ? "（" + failed + " 个失败）" : "")
                                    + " · " + human(bytes) + " / " + human(bytesTotal))
                            .setProgress(100, total > 0 ? (int) (done * 100L / total) : 0, false)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setCategory(Notification.CATEGORY_PROGRESS);
                    PendingIntent pi = openApp();
                    if (pi != null) b.setContentIntent(pi);
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFY_ID, b.build());
                } catch (Throwable ignored) { }
            }
        });
    }

    private void notifyDone(boolean ok, String text) {
        final boolean ok2 = ok;
        final String text2 = text;
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Notification.Builder b = newBuilder();
                    b.setSmallIcon(ok2 ? android.R.drawable.stat_sys_upload_done
                                    : android.R.drawable.stat_notify_error)
                            .setContentTitle(ok2 ? "上传完成" : "上传中断")
                            .setContentText(text2)
                            .setOnlyAlertOnce(false)
                            .setOngoing(false)
                            .setAutoCancel(true);
                    PendingIntent pi = openApp();
                    if (pi != null) b.setContentIntent(pi);
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFY_ID, b.build());
                } catch (Throwable ignored) { }
            }
        });
    }

    private void finish(boolean ok, String text) {
        running = false;
        notifyDone(ok, text);
        final Runnable stop = new Runnable() {
            @Override
            public void run() { stopSelf(); }
        };
        handler.post(stop);
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
