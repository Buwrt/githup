package com.hubmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件夹上传的前台服务。
 *
 * 干什么 ——
 *   把「整批文件的编排」从 WebView 的 JS 搬到原生。
 *
 * 为什么非搬不可（这是用户最原始的那个抱怨）——
 *   上传跑在 JS 的 Promise 链上时，只要**退出仓库页、切到微信、或者
 *   WebView 被回收**，那条链就断了，已经传上去的 blob 全废。传 294MB
 *   要二十几分钟，随便切一下就前功尽弃。
 *
 *   搬到原生前台服务之后：
 *   · 退出 Activity、切后台、锁屏，上传照跑；
 *   · 前台服务能解除 Android 对后台应用的网络限制，不会因为「后台」被掐网；
 *   · 断了也不怕 —— 成功的 blob sha 记进 UploadLedger，下次只补缺的。
 *
 * 关于「A+ 而不是完整 B」——
 *   这里只搬**核心编排**（低并发循环 + 重试退避 + 账本 + tree/commit/ref），
 *   不搬 UI 进度回调、前端缓存、无原生环境的兼容分支那堆东西。工作量
 *   大约是两个方案之间，覆盖真实场景的绝大多数（切走/锁屏/来电话）。
 *   用户在最近任务里**主动划掉** App 仍然会停 —— 为此做完整移植不划算。
 */
public class UploadService extends Service {

    private static final String CHANNEL = "githup_upload";
    private static final int NOTIFY_ID = 7310;

    /** 并发路数。3 路 —— 一次几百个 POST 时，并发越高越像请求洪水。 */
    private static final int LANES = 3;
    /** 同一仓库同一目录同一路径，才算同一个文件（账本 key 用） */
    private static final long MIN_GAP_MS = 60;
    /** 一批里挂多少个文件进同一个 tree */
    private static final int BATCH_SIZE = 150;
    private static final int MAX_TRY = 3;

    private static volatile boolean sRunning = false;
    private static final AtomicInteger sDone = new AtomicInteger(0);
    private static final AtomicInteger sTotal = new AtomicInteger(0);
    private static final AtomicInteger sFailed = new AtomicInteger(0);
    private static volatile boolean sCommitted = false;
    private static volatile String sError = null;

    private ExecutorService pool;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        pool = Executors.newFixedThreadPool(LANES);
        /* 必须尽快进前台：Android 要求 startForegroundService 之后几秒内
           调用 startForeground，否则看门狗直接杀进程。 */
        promote();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        if (intent != null) {
            final String task = intent.getStringExtra("task");
            if (task != null && !sRunning) {
                sRunning = true;
                sDone.set(0);
                sFailed.set(0);
                sCommitted = false;
                sError = null;
                pool.execute(() -> run(task));
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ───────────────────────── 前台通知 ─────────────────────────

    private void promote() {
        boolean ok = false;
        try {
            Notification n = buildProgress(sDone.get(), sTotal.get());
            if (n != null) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFY_ID, n,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFY_ID, n);
                }
                ok = true;
            }
        } catch (Throwable t) {
            ok = false;
        }
        /*
         * 关键：通知没造出来 / startForeground 抛异常，都【必须】立刻停服务。
         *
         * 只要走了 startForegroundService，系统就会在 ServiceRecord 上挂一个
         * 「必须进前台」的标记，并要求 10 秒内调用 startForeground。没做到，
         * 系统看门狗就抛 ForegroundServiceDidNotStartInTimeException 把进程杀掉
         * —— 异常投递到主线程，用户看到的就是「点什么都没反应，然后闪退」。
         *
         * 所以这里不能只是 catch 掉异常继续跑：继续跑等于等着被杀。
         * 收摊反而能让系统尽快移除 ServiceRecord，把那条超时消息一并取消。
         */
        if (!ok) {
            try { stopSelf(); } catch (Throwable ignored) { }
        }
    }

    private NotificationChannel ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return null;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return null;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
        if (ch == null) {
            ch = new NotificationChannel(CHANNEL, "上传", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("文件夹上传到 GitHub 的进度");
            nm.createNotificationChannel(ch);
        }
        return ch;
    }

    private Notification.Builder newBuilder() {
        ensureChannel();
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL);
        }
        return new Notification.Builder(this);
    }

    private Notification buildProgress(int done, int total) {
        try {
            Notification.Builder b = newBuilder();
            b.setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle("正在上传")
                    .setContentText(total > 0 ? (done + " / " + total + " 个文件") : "准备中…")
                    .setProgress(100, total > 0 ? (int) (done * 100L / total) : 0, total <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            PendingIntent pi = openApp();
            if (pi != null) b.setContentIntent(pi);
            return b.build();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PendingIntent openApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i == null) return null;
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int f = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, 7311, i, f);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void tick() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFY_ID, buildProgress(sDone.get(), sTotal.get()));
        } catch (Throwable ignored) { }
    }

    private void notifyDone(boolean ok, String text) {
        try {
            Notification.Builder b = newBuilder();
            b.setSmallIcon(ok ? android.R.drawable.stat_sys_upload_done
                            : android.R.drawable.stat_notify_error)
                    .setContentTitle(ok ? "上传完成" : "上传失败")
                    .setContentText(text)
                    .setOngoing(false)
                    .setAutoCancel(true);
            PendingIntent pi = openApp();
            if (pi != null) b.setContentIntent(pi);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID + 1, b.build());
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── 上传编排 ─────────────────────────

    private void run(String taskJson) {
        try {
            JSONObject task = new JSONObject(taskJson);
            String repo = task.optString("repo", "");
            String branch = task.optString("branch", "main");
            String dir = task.optString("dir", "");
            String msg = task.optString("message", "Add files via upload");
            String token = task.optString("token", "");
            JSONArray files = task.optJSONArray("files");
            if (repo.isEmpty() || token.isEmpty() || files == null || files.length() == 0) {
                finish(false, "任务参数不完整");
                return;
            }

            List<String[]> list = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                list.add(new String[]{
                        f.optString("uri", ""),
                        f.optString("path", ""),
                        String.valueOf(f.optLong("size", 0L))
                });
            }
            sTotal.set(list.size());
            tick();

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + token);
            headers.put("Accept", "application/vnd.github+json");
            headers.put("X-GitHub-Api-Version", "2022-11-28");
            headers.put("Content-Type", "application/json");

            /* 账本 key：同一个仓库同一目录同一分支，才算同一批活 */
            String ledgerKey = repo + "|" + branch + "|" + dir;
            /* 必须落成 final：下面的 lane 线程是 lambda，只能捕 effectively
               final 的局部变量。在 try/catch 里赋值的变量不算 —— 编译器
               会报 "must be final or effectively final"。 */
            JSONObject rawLedger;
            try {
                rawLedger = new JSONObject(UploadLedger.read(this, ledgerKey));
            } catch (Throwable ignored) {
                rawLedger = new JSONObject();
            }
            final JSONObject ledger = rawLedger;

            AtomicInteger idx = new AtomicInteger(0);
            List<Thread> lanes = new ArrayList<>();
            List<String> entryPaths = new ArrayList<>();
            List<String> entryShas = new ArrayList<>();
            Object lock = new Object();
            AtomicInteger failed = new AtomicInteger(0);
            JSONObject gained = new JSONObject();

            for (int lane = 0; lane < LANES && lane < list.size(); lane++) {
                Thread t = new Thread(() -> {
                    int i;
                    while ((i = idx.getAndIncrement()) < list.size()) {
                        String[] f = list.get(i);
                        String full = dir.isEmpty() ? f[1] : dir + "/" + f[1];
                        try {
                            /* 账本命中：上次已经传过这个内容，直接复用 sha */
                            JSONObject hit = ledger.optJSONObject(full);
                            if (hit != null) {
                                String sha = hit.optString("s", "");
                                long ts = hit.optLong("t", 0L);
                                if (!sha.isEmpty()
                                        && System.currentTimeMillis() - ts <= UploadLedger.TTL_MS) {
                                    synchronized (lock) {
                                        entryPaths.add(full);
                                        entryShas.add(sha);
                                    }
                                    sDone.incrementAndGet();
                                    postTick();
                                    continue;
                                }
                            }
                            String sha = postBlob(repo, f[0], headers);
                            synchronized (lock) {
                                entryPaths.add(full);
                                entryShas.add(sha);
                                try { gained.put(full, sha); } catch (Throwable ignored) { }
                            }
                            sDone.incrementAndGet();
                            postTick();
                        } catch (Throwable e) {
                            failed.incrementAndGet();
                            sFailed.incrementAndGet();
                        }
                    }
                });
                t.start();
                lanes.add(t);
            }
            for (Thread t : lanes) {
                try { t.join(); } catch (Throwable ignored) { }
            }

            /* 成功的先记账 —— 哪怕下面提交失败，下次也能接着用 */
            if (gained.length() > 0) UploadLedger.write(this, ledgerKey, gained.toString());

            int okCount;
            synchronized (lock) { okCount = entryShas.size(); }
            if (okCount == 0) {
                finish(false, failed.get() + " 个文件都没传成功，可重试");
                return;
            }

            /* 分批合成提交。每批都要重读分支顶端 —— 上一批提交后 HEAD 变了 */
            List<String> paths;
            List<String> shas;
            synchronized (lock) {
                paths = new ArrayList<>(entryPaths);
                shas = new ArrayList<>(entryShas);
            }
            for (int i = 0; i < paths.size(); i += BATCH_SIZE) {
                JSONArray tree = new JSONArray();
                for (int j = i; j < Math.min(i + BATCH_SIZE, paths.size()); j++) {
                    JSONObject e = new JSONObject();
                    e.put("path", paths.get(j));
                    e.put("mode", "100644");
                    e.put("type", "blob");
                    e.put("sha", shas.get(j));
                    tree.put(e);
                }
                commitBatch(repo, branch, msg, tree, headers);
            }

            sCommitted = true;
            /* 全成了，账本没用了 */
            UploadLedger.clear(this, ledgerKey);
            if (failed.get() > 0) {
                finish(true, "已提交 " + okCount + " 个文件，" + failed.get() + " 个没传成功");
            } else {
                finish(true, "已上传 " + okCount + " 个文件");
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m == null) m = t.getClass().getSimpleName();
            finish(false, m);
        }
    }

    private void postTick() {
        if (handler != null) {
            handler.post(this::tick);
        }
    }

    private void finish(boolean ok, String text) {
        sRunning = false;
        sError = ok ? null : text;
        notifyDone(ok, text);
        if (handler != null) {
            handler.post(() -> {
                try {
                    stopForeground(true);
                } catch (Throwable ignored) { }
                stopSelf();
            });
        }
    }

    /** 单个文件 POST 成 blob，带退避重试 */
    private String postBlob(String repo, String uriStr, Map<String, String> headers)
            throws Exception {
        String url = "https://api.github.com/repos/" + repo + "/git/blobs";
        String head = "{\"content\":\"";
        String tail = "\",\"encoding\":\"base64\"}";
        byte[] headBytes = head.getBytes("UTF-8");
        byte[] tailBytes = tail.getBytes("UTF-8");

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_TRY; attempt++) {
            if (attempt > 1) {
                long d = 2000L * (1L << (attempt - 1)) + (long) (Math.random() * 800);
                try { Thread.sleep(d); } catch (Throwable ignored) { }
            }
            try {
                long fileLen = FilePick.sizeOf(this, android.net.Uri.parse(uriStr));
                long total = headBytes.length + FilePick.base64Length(fileLen) + tailBytes.length;
                InputStream in = new SequenceInputStream(
                        new ByteArrayInputStream(headBytes),
                        new SequenceInputStream(
                                FilePick.base64Encoding(FilePick.open(this, android.net.Uri.parse(uriStr))),
                                new ByteArrayInputStream(tailBytes)));
                Http.Response r = Http.requestMultipart("POST", url, in, total,
                        "application/json", headers);
                if (r.code >= 200 && r.code < 300) {
                    JSONObject o = new JSONObject(r.body == null ? "" : r.body);
                    String sha = o.optString("sha", "");
                    if (!sha.isEmpty()) return sha;
                    last = new Exception("没有返回 sha");
                } else {
                    String m = "";
                    try { m = new JSONObject(r.body == null ? "" : r.body).optString("message", ""); } catch (Throwable ignored) { }
                    Exception e = new Exception(m.isEmpty() ? ("HTTP " + r.code) : m);
                    if (!isTransient(r.code, r.body)) throw e;   // 4xx 不重试
                    last = e;
                }
            } catch (Throwable t) {
                if (t instanceof Exception) last = (Exception) t;
                else last = new Exception(String.valueOf(t.getMessage()));
                /* 网络层异常（超时/断链）当作瞬时错误继续重试 */
            }
        }
        throw (last != null ? last : new Exception("上传失败"));
    }

    /** 只重试瞬时错误。4xx（除 429）一律不重试 —— 重试一万次结果一样。 */
    private static boolean isTransient(int code, String body) {
        if (code == 0 || code == 429) return true;
        if (code == 500 || code == 502 || code == 503 || code == 504) return true;
        if (code == 403) {
            /* 次级限流的 403 正文写着 secondary rate limit，remaining 可能还是正数 */
            if (body != null) {
                String b = body.toLowerCase(Locale.US);
                if (b.contains("secondary rate limit") || b.contains("abuse detection")) return true;
            }
        }
        return false;
    }

    /** 一次提交：get ref → get commit → post tree → post commit → patch ref，带重试 */
    private void commitBatch(String repo, String branch, String msg, JSONArray tree,
                             Map<String, String> headers) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_TRY; attempt++) {
            if (attempt > 1) {
                try { Thread.sleep(1500L * attempt + (long) (Math.random() * 500)); } catch (Throwable ignored) { }
            }
            try {
                String refUrl = "https://api.github.com/repos/" + repo + "/git/ref/heads/" + branch;
                Http.Response rr = Http.request("GET", refUrl, null, headers);
                if (rr.code >= 400) throw new Exception("读分支失败 HTTP " + rr.code);
                String parentSha = new JSONObject(rr.body).getJSONObject("object").optString("sha", "");

                Http.Response cr = Http.request("GET",
                        "https://api.github.com/repos/" + repo + "/git/commits/" + parentSha,
                        null, headers);
                if (cr.code >= 400) throw new Exception("读提交失败 HTTP " + cr.code);
                String baseTree = new JSONObject(cr.body).getJSONObject("tree").optString("sha", "");

                JSONObject treeBody = new JSONObject();
                treeBody.put("base_tree", baseTree);
                treeBody.put("tree", tree);
                Http.Response tr = Http.request("POST",
                        "https://api.github.com/repos/" + repo + "/git/trees",
                        treeBody.toString(), headers);
                if (tr.code >= 400) {
                    String m = "";
                    try { m = new JSONObject(tr.body).optString("message", ""); } catch (Throwable ignored) { }
                    throw new Exception("建 tree 失败：" + (m.isEmpty() ? ("HTTP " + tr.code) : m));
                }
                String newTree = new JSONObject(tr.body).optString("sha", "");

                JSONObject cmBody = new JSONObject();
                cmBody.put("message", msg);
                cmBody.put("tree", newTree);
                JSONArray parents = new JSONArray();
                parents.put(parentSha);
                cmBody.put("parents", parents);
                Http.Response cm = Http.request("POST",
                        "https://api.github.com/repos/" + repo + "/git/commits",
                        cmBody.toString(), headers);
                if (cm.code >= 400) throw new Exception("建提交失败 HTTP " + cm.code);
                String newCommit = new JSONObject(cm.body).optString("sha", "");

                JSONObject refBody = new JSONObject();
                refBody.put("sha", newCommit);
                refBody.put("force", false);
                Http.Response pr = Http.request("PATCH", refUrl, refBody.toString(), headers);
                /* 409 = 分支刚被别人推了一下。重试时会重新 get ref，自愈 */
                if (pr.code >= 400) throw new Exception("更新分支失败 HTTP " + pr.code);
                return;
            } catch (Throwable t) {
                last = (t instanceof Exception) ? (Exception) t : new Exception(String.valueOf(t.getMessage()));
            }
        }
        throw (last != null ? last : new Exception("提交失败"));
    }

    // ───────────────────────── 给 JS 查状态 ─────────────────────────

    /** 供 JsBridge 转给前端：是否支持原生整批上传 */
    static boolean supported() {
        return true;
    }
}
