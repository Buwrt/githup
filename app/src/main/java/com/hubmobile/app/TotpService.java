package com.hubmobile.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台常驻的「动态码」通知。
 *
 * 干什么 ——
 *   用户把 App 退到后台之后，在通知栏留一条常驻通知，上面是两步验证器
 *   里第一个账户的当前动态码，每 30 秒自动刷新。这样在别的应用里要填
 *   验证码时，拉一下通知栏就能看到，不用切回 githup。
 *
 * 什么时候出现 ——
 *   · 两步验证器里至少有一个账户（没账户不占通知栏）
 *   · App 当前不在前台（在前台时页面上就看得见，再挂一条通知是打扰）
 *
 *   早先这里还有一条「用户开了『后台显示动态码』这个开关」的前置条件，
 *   按用户要求那个开关连同设置页里那一行一起删掉了，功能改为默认生效。
 *   挂/收的实际时机由 MainActivity 的 onPause / onResume 驱动。
 *
 * 为什么用前台服务 ——
 *   Android 8 之后，后台进程会被系统随时冻结，定时器根本跑不准，
 *   码会停在旧值上。前台服务带一条可见通知，系统才允许它持续运行。
 *   这也是「手机没打开 APP 也要求常驻」的唯一合规做法：所有后台常驻
 *   都必须在通知栏可见，不能偷偷跑。
 *
 * 关于自启动 ——
 *   开机广播（见 BootReceiver）负责在重启后把通知恢复出来。
 *   部分国产 ROM 需要用户在系统设置里额外允许「自启动」，App 会
 *   在开启开关时给出提示，但不会反复弹窗纠缠。
 */
public class TotpService extends Service {

    private static final String CHANNEL_ID = "githup_totp";
    private static final int NOTIFY_ID = 7301;
    private static final String PREFS = "hub_prefs";
    private static final String KEY_ENABLED = "totp_bg_enabled";

    /** 刷新间隔：动态码默认 30 秒一轮，1 秒刷一次足够跟上倒计时 */
    private static final long TICK_MS = 1000;

    private Handler handler;
    private Runnable ticker;
    /** 已解析的账户（每次刷新时重新读，免得前端改了列表这里还是旧的） */
    private List<JSONObject> accounts = new ArrayList<>();
    /** 上一次画出来的标题，内容没变就不重复 notify，省电 */
    private String lastText = "";

    /* ---------------- 外部开关 ---------------- */

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context ctx) {
        /*
         * 默认 true —— 设置页里那个「后台显示动态码」开关已经按用户要求删掉了，
         * 这个值不再由用户翻，所以不能停在 false 上，否则通知栏动态码
         * 永远出不来（第一步就是要它在通知里显示）。
         *
         * 默认开启不会造成骚扰，因为挂通知还有几道前置条件：
         *   · accounts.isEmpty() → stopSelf()，没有账户时一条通知都不留；
         *   · MainActivity.onResume → hideNotification()，回到 App 就收起来，
         *     只有退到后台才挂出来；
         *   · Android 13+ 没给通知权限时通知直接发不出去，不影响 App 本身。
         * 也就是说用户没加过任何两步验证账户的话，这个 true 什么都不做。
         */
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    /**
     * 申请通知权限（Android 13+），只在「还没授权」时静默发起一次。
     *
     * 以前这件事是在用户点设置页那个开关时做的。开关删掉之后，
     * 改成 App 启动 / 回到前台时顺手要一次 —— 不给也不纠缠，
     * 用户只是看不到通知栏里的动态码，App 其余功能一切照常。
     * 通过 JsBridge 走同一条权限申请路径（结果由 MainActivity 转回来）。
     */
    static void ensureNotificationPermission(MainActivity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            activity.requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    JsBridge.REQ_NOTIFY_PERM);
        } catch (Throwable ignored) {
            // 申请失败（比如没有 Activity 窗口）就当没这回事，不打断启动流程
        }
    }

    /**
     * 开 / 关后台通知。
     *
     * 设置页的开关删掉之后，前端已经不再调用这里 —— 保留是因为它仍然是
     * 「强制关掉」最干净的一条路（停服务 + 撤通知 + 落一个 false 的值），
     * 日后若要加回开关不必重写。当前默认值走 isEnabled 里的 true。
     */
    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
        Intent i = new Intent(ctx, TotpService.class);
        if (on) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Throwable ignored) { }
        } else {
            try { ctx.stopService(i); } catch (Throwable ignored) { }
            cancel(ctx);
        }
    }

    /**
     * 账户列表变了：让服务换一副新内容。
     * 服务没在跑就什么都不做（退到后台时 showNotification 会重新拉起来）。
     */
    static void refresh(Context ctx) {
        if (!isEnabled(ctx)) return;
        try {
            Intent i = new Intent(ctx, TotpService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) { }
    }

    /** 按当前是否该显示，决定挂上还是撤掉通知 */
    static void sync(Context ctx) {
        boolean should = isEnabled(ctx);
        Intent i = new Intent(ctx, TotpService.class);
        if (should) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Throwable ignored) { }
        } else {
            try { ctx.stopService(i); } catch (Throwable ignored) { }
            cancel(ctx);
        }
    }

    /**
     * App 退到后台时调用：把动态码通知挂出来。
     *
     * 只有「功能开着」才做（isEnabled 现在默认 true）。真的被关掉时
     * 这里什么都不发生 —— 不申请自启动、不常驻、通知栏干干净净。
     */
    static void showNotification(Context ctx) {
        if (ctx == null) return;
        if (!isEnabled(ctx)) return;
        try {
            Intent i = new Intent(ctx, TotpService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) { }
    }

    /**
     * App 回到前台时调用：把通知收起来。
     *
     * 注意这里是「停服务 + 撤通知」，不是只撤通知 ——
     * 服务留着空跑会一直占着一条前台通知的坑位，
     * 而且每秒还在算码，纯浪费电。回到前台就整个停掉，
     * 下次退后台再拉起来。
     */
    static void hideNotification(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, TotpService.class));
        } catch (Throwable ignored) { }
        cancel(ctx);
    }

    private static void cancel(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_ID);
        } catch (Throwable ignored) { }
    }

    /* ---------------- 服务生命周期 ---------------- */

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        /*
         * 服务一起来就必须立刻进入前台状态，否则系统在 5 秒内会抛
         * 「Context.startForegroundService() did not then call Service.startForeground()」
         * 然后杀掉它。所以这里先无条件挂一条通知，哪怕待会儿就撤。
         */
        startForeground(NOTIFY_ID, buildNotification("正在准备…"));

        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        reload();
        if (accounts.isEmpty()) {
            // 没有账户可展示，就别占着通知栏
            stopSelf();
            return START_NOT_STICKY;
        }

        schedule();
        // START_STICKY：被系统回收后自动重建，通知不会莫名其妙消失
        return START_STICKY;
    }

    /**
     * App 退到后台 / 回到前台时由 MainActivity 调用。
     * 前台时把通知收起来，后台时再挂出来。
     */
    @Override
    public void onDestroy() {
        if (handler != null && ticker != null) handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // 不需要绑定
    }

    /* ---------------- 通知内容 ---------------- */

    private void schedule() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override
            public void run() {
                try {
                    update();
                } catch (Throwable ignored) { }
                // 每秒自查一次：账户被删空、或开关被关掉，就自行收摊
                if (!isEnabled(TotpService.this) || accounts.isEmpty()) {
                    stopSelf();
                    return;
                }
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.post(ticker);
    }

    /** 重新读一遍账户清单 */
    private void reload() {
        accounts = new ArrayList<>();
        try {
            String raw = prefs(this).getString("totp_accounts_cache", "[]");
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String secret = o.optString("secret", "");
                if (secret.isEmpty()) continue;
                if (!"totp".equals(o.optString("type", "totp"))) continue;   // HOTP 没有时间概念，不在这展示
                accounts.add(o);
            }
        } catch (Throwable ignored) { }
    }

    /** 算每个账户当前的码并重画通知 */
    private void update() {
        // 账户清单可能被前端改过，隔几轮重读一次比每次读省事
        if (accounts.isEmpty()) reload();
        if (accounts.isEmpty()) return;

        JSONObject first = accounts.get(0);
        String name = first.optString("issuer", "");
        if (name.isEmpty()) name = first.optString("name", "");
        if (name.isEmpty()) name = "两步验证";

        String code = Totp.compute(first);
        long left = Totp.remaining(first);

        if (code == null || code.isEmpty()) return;

        String title = name + "  " + Totp.group(code);
        String text = accounts.size() > 1
                ? ("还剩 " + left + " 秒 · 还有 " + (accounts.size() - 1) + " 个账户")
                : ("还剩 " + left + " 秒");

        // 内容没变（同一秒内重复触发）就不重复推，省电
        String stamp = title + "|" + text;
        if (stamp.equals(lastText)) return;
        lastText = stamp;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFY_ID, buildNotification(title, text));
    }

    private Notification buildNotification(String title) {
        return buildNotification(title, "在 githup 里点开「我的 → 两步验证器」");
    }

    private Notification buildNotification(String title, String text) {
        ensureChannel();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 直接落到验证器页面：从通知点进来就是要看码，不该让人再找一遍
        open.putExtra("route", "/totp");

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)              // 常驻：不能左右滑掉，否则用户以为坏了
                .setShowWhen(false)
                .setOnlyAlertOnce(true)        // 每秒刷新不能每秒响一声
                .setCategory(Notification.CATEGORY_STATUS);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // 老系统没有渠道，用低优先级避免响铃与悬浮
            b.setPriority(Notification.PRIORITY_LOW);
        }

        // 展开了能一眼看清码，不用眯眼找
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            style.setBigContentTitle(title);
            style.addLine("打开 githup 可复制或管理账户");
            b.setStyle(style);
        }
        return b.build();
    }

    /**
     * Android 8+ 必须先建渠道才会有通知。
     * 渠道优先级设为 LOW：这是状态信息，不该震动或响铃打扰用户。
     */
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "两步验证动态码", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("在通知栏显示两步验证器的动态码（仅在你离开 App 时出现）");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }
}
