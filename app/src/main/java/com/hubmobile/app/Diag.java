package com.hubmobile.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 全层诊断入口 —— 「不管哪一层、哪个算法出问题，都要写进错误日志」。
 *
 * 以前 LogBook 只接了三路：前端全局错误、API 请求失败、少量手写 error。
 * 下载链路、系统组件状态、测速/换道/校验这些算法分支、原生层被 catch 住
 * 的异常，全都只进 logcat，用户导出日志只能看到「今天没有记录到任何问题」。
 * 最典型的就是下载管理器被冻结：App 弹「下载失败」，日志一个字都没有。
 *
 * 这里做四件事：
 *   1) 统一入口 e()/n()，带层标签（原生/前端/网络/下载/系统/算法/引擎/安全）
 *   2) install() 启动时做设备自检，把「手机侧」的问题主动写进当天日志
 *      （不用等用户去点下载），环境快照同时进导出抬头
 *   3) 缓存 applicationContext，所有插桩点只写 Diag.e(...)，不依赖 activity
 *   4) 相同问题 2 秒内合并计数，避免看门狗轮询把日志刷爆，但不丢次数
 *
 * 落盘仍由 LogBook 负责：平时只写 App 内部，按天分文件、保留 7 天；
 * 用户点「保存到本地 / 分享」时才导出到公共目录。
 */
public final class Diag {

    private Diag() { }

    public static final String NATIVE = "原生";
    public static final String WEB    = "前端";
    public static final String NET    = "网络";
    public static final String DL     = "下载";
    public static final String SYS    = "系统";
    public static final String ALGO   = "算法";
    public static final String ENGINE = "引擎";
    public static final String SEC    = "安全";

    private static volatile Context appCtx = null;
    private static volatile boolean installed = false;
    private static final Object LOCK = new Object();
    private static final Map<String, Long> LAST = new HashMap<String, Long>();
    private static final Map<String, Integer> DUP = new HashMap<String, Integer>();
    /** 合并窗口：同一个问题 2 秒内只写一行，但累计次数不丢 */
    private static final long WINDOW_MS = 2000L;

    /** Application.onCreate 调一次；也会在 MainActivity 兜底再调（幂等） */
    public static void install(Context ctx) {
        if (ctx == null) return;
        appCtx = ctx.getApplicationContext();
        if (installed) return;
        installed = true;
        try {
            /* 环境快照进当天日志：哪怕一整天没出错，导出来也有设备状态 */
            LogBook.raw(appCtx, "【启动自检】\n" + envText(appCtx));
            checkEnv(appCtx);
            n(SYS, "启动自检完成");
        } catch (Throwable ignored) { }
    }

    /* ==================================================================
     *  写日志：所有层、所有算法分支都调这里
     * ================================================================== */

    /** 记一条问题。t 可为 null；有异常就附完整堆栈（给开发者看） */
    public static void e(String layer, String what, String why, Throwable t) {
        Context c = appCtx;
        if (c == null || what == null) return;
        String tag = tag(layer);
        String detail = nz(why, "");
        String sig = tag + "|" + what + "|" + detail;
        int dup = 0;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            Long last = LAST.get(sig);
            if (last != null && now - last.longValue() < WINDOW_MS) {
                Integer d = DUP.get(sig);
                DUP.put(sig, d == null ? 1 : d.intValue() + 1);
                return;                       // 合并，不重复刷屏
            }
            Integer d = DUP.remove(sig);
            dup = d == null ? 0 : d.intValue();
            LAST.put(sig, Long.valueOf(now));
            if (LAST.size() > 200) LAST.clear();
            if (DUP.size() > 200) DUP.clear();
        }
        String tail = dup > 0 ? "（这期间同样的问题又出现了 " + dup + " 次）" : "";
        boolean dl = isDownloadLog(layer, what, detail);
        if (dl) LogBook.markDownload();
        try {
            LogBook.error(c, tag + what, detail + tail + (t == null ? "" : "；异常：" + brief(t)), dl);
            if (t != null) LogBook.raw(c, stack(t), dl);
        } catch (Throwable ignored) { }
    }

    /** 记一条过程（关键操作、算法决策），用来还原「当时在干什么」 */
    public static void n(String layer, String what) {
        Context c = appCtx;
        if (c == null || what == null) return;
        boolean dl = isDownloadLog(layer, what, "");
        if (dl) LogBook.markDownload();
        try { LogBook.note(c, tag(layer) + what, dl); } catch (Throwable ignored) { }
    }

    /** 是不是「下载过程中」的日志：下载层，或算法层里讲下载换道/校验/通道的 */
    private static boolean isDownloadLog(String layer, String what, String why) {
        if (DL.equals(layer)) return true;
        if (ALGO.equals(layer)) {
            String s = nz(what, "") + nz(why, "");
            return s.contains("下载") || s.contains("通道") || s.contains("换道")
                    || s.contains("SHA") || s.contains("校验");
        }
        return false;
    }

    /** 算法结论/决策：不成异常但值得留痕的分支 */
    public static void algo(String what, String why) { n(ALGO, what + (isBlank(why) ? "" : "；" + why)); }

    /** 只记异常、不写大白话（给批量 catch 插桩用，最省事） */
    public static void caught(String layer, Throwable t) {
        if (t == null) return;
        e(layer, "捕获到异常", nz(t.getMessage(), t.getClass().getName()), t);
    }

    /* ==================================================================
     *  设备自检：手机侧的问题也写进日志
     * ================================================================== */

    /** 导出抬头用的环境快照（多行、可读） */
    public static String envText(Context ctx) {
        if (ctx == null) return "    （拿不到设备信息）\n";
        StringBuilder sb = new StringBuilder();
        sb.append("  设备        : ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("  语言/时区   : ").append(Locale.getDefault()).append(" / ")
                .append(java.util.TimeZone.getDefault().getID()).append("\n");
        sb.append("  系统下载组件: ").append(downloadManagerState(ctx)).append("\n");
        sb.append("  下载服务    : ").append(downloadServiceState(ctx)).append("\n");
        sb.append("  外部存储    : ").append(storageState()).append("\n");
        sb.append("  可用空间    : ").append(freeSpace(ctx)).append("\n");
        sb.append("  网络        : ").append(networkState(ctx)).append("\n");
        sb.append("  电池优化    : ").append(batteryState(ctx)).append("\n");
        sb.append("  悬浮窗权限  : ").append(overlayState(ctx)).append("\n");
        sb.append("  WebView     : ").append(webViewState()).append("\n");
        sb.append("  无障碍服务  : ").append(a11yState(ctx)).append("\n");
        sb.append("  通知权限    : ").append(notifState(ctx)).append("\n");
        sb.append("  Keystore    : ").append(keystoreState()).append("\n");
        sb.append("  可用内存    : ").append(memoryState(ctx)).append("\n");
        sb.append("  低内存/Doze : ").append(dozeState(ctx)).append("\n");
        sb.append("  剪贴板服务  : ").append(clipboardState(ctx)).append("\n");
        sb.append("  自动存日志  : ").append(
                LogBook.downloadActive() ? "开（下载进行中，自动临时开启）"
                        : (LogBook.autoSaveOn(ctx) ? "开（用户手动开启）" : "关（只存 App 内部）")).append("\n");
        return sb.toString();
    }

    /** 自检异常项：主动写 error，不等用户触发 */
    private static void checkEnv(Context ctx) {
        String dm = downloadManagerState(ctx);
        if (dm.startsWith("已停用") || dm.startsWith("未找到")) {
            e(SYS, "手机的系统下载组件不可用",
                    dm + "；点下载会失败，请到 设置 → 应用管理 → 显示系统 → 下载管理器 启用它", null);
        }
        if (!"已挂载（可读写）".equals(storageState())) {
            e(SYS, "外部存储状态不正常", storageState() + "；下载和导出可能失败", null);
        }
        long free = freeBytes(ctx);
        if (free >= 0 && free < 50L * 1024 * 1024) {
            e(SYS, "手机可用空间不足 50MB", "剩余 " + (free / 1024 / 1024) + "MB；下载/写日志可能失败", null);
        }
        if (networkState(ctx).startsWith("无网络")) {
            e(NET, "启动时没有可用网络", "所有 API 与下载都会失败；请检查 Wi-Fi / 移动数据 / VPN", null);
        }
        String ks = keystoreState();
        if (ks.startsWith("不可用")) {
            e(SEC, "设备 Keystore 不可用", ks + "；已保存的令牌会解不开，需要重新登录", null);
        }
        String mem = memoryState(ctx);
        if (mem.contains("低内存")) {
            e(SYS, "手机处于低内存状态", mem + "；引擎/大文件下载可能被系统杀掉", null);
        }
        if (batteryState(ctx).startsWith("未忽略")) {
            n(SYS, "电池优化未忽略（后台下载可能被系统暂停）");
        }
        if (overlayState(ctx).startsWith("未授权")) {
            n(SYS, "悬浮窗权限未授予（桌宠/悬浮球不可用）");
        }
    }

    /** 关键：能否查出 com.android.providers.downloads 被冻结 */
    public static String downloadManagerState(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            int st;
            try {
                st = pm.getApplicationEnabledSetting("com.android.providers.downloads");
            } catch (Throwable t) {
                return "未找到该组件（" + nz(t.getMessage(), t.getClass().getSimpleName()) + "）";
            }
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return "已停用/冻结（enabledSetting=" + st + "），下载会失败";
            }
            if (pm.resolveContentProvider("downloads", 0) == null) {
                return "已停用/冻结（downloads Provider 解析不到），下载会失败";
            }
            return "正常";
        } catch (Throwable t) {
            return "检查失败：" + nz(t.getMessage(), t.getClass().getSimpleName());
        }
    }

    private static String downloadServiceState(Context ctx) {
        try {
            Object o = ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            return o == null ? "拿不到 DOWNLOAD_SERVICE（系统侧异常）" : "可用";
        } catch (Throwable t) {
            return "检查失败：" + nz(t.getMessage(), t.getClass().getSimpleName());
        }
    }

    private static String storageState() {
        try {
            String s = Environment.getExternalStorageState();
            return Environment.MEDIA_MOUNTED.equals(s) ? "已挂载（可读写）" : s;
        } catch (Throwable t) { return "检查失败"; }
    }

    private static long freeBytes(Context ctx) {
        try {
            java.io.File d = ctx.getFilesDir();
            StatFs st = new StatFs(d.getAbsolutePath());
            return st.getAvailableBlocksLong() * st.getBlockSizeLong();
        } catch (Throwable t) {
            try {
                StatFs st = new StatFs(Environment.getDataDirectory().getAbsolutePath());
                return st.getAvailableBlocksLong() * st.getBlockSizeLong();
            } catch (Throwable t2) { return -1L; }
        }
    }

    private static String freeSpace(Context ctx) {
        long b = freeBytes(ctx);
        return b < 0 ? "检查失败" : (b / 1024 / 1024) + "MB";
    }

    private static String networkState(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "无网络（拿不到连接服务）";
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "无网络（未连接）";
            return "已连接：" + ni.getTypeName();
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String batteryState(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return "不适用（Android 6 以下）";
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return "检查失败";
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName()) ? "已忽略" : "未忽略";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String overlayState(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return "不适用";
            return android.provider.Settings.canDrawOverlays(ctx) ? "已授权" : "未授权";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String a11yState(Context ctx) {
        try {
            android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return "拿不到服务";
            String list = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return am.isEnabled()
                    ? "已开启（" + nz(list, "有服务") + "）"
                    : "未开启（AI 无法读屏/点按，属正常可选项）";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String notifState(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 33) return "不适用（Android 13 以下无需运行时授权）";
            return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String keystoreState() {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return "可用（" + ks.size() + " 个别名）";
        } catch (Throwable t) {
            return "不可用：" + nz(t.getMessage(), t.getClass().getSimpleName()) + "（令牌会解不开）";
        }
    }

    private static String memoryState(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return "检查失败";
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (mi.availMem / 1024 / 1024) + "MB 可用" + (mi.lowMemory ? "（系统已判定低内存）" : "");
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String dozeState(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return "不适用";
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return "检查失败";
            return pm.isDeviceIdleMode() ? "处于 Doze 闲置（后台任务会被推迟）" : "非闲置";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static String clipboardState(Context ctx) {
        try {
            return ctx.getSystemService(Context.CLIPBOARD_SERVICE) != null ? "可用" : "拿不到服务";
        } catch (Throwable t) { return "检查失败"; }
    }

    private static boolean isBlank(String x) { return x == null || x.trim().isEmpty(); }

    private static String webViewState() {
        try {
            if (Build.VERSION.SDK_INT < 26) return "系统 WebView（版本信息需 API 26+）";
            android.content.pm.PackageInfo p = android.webkit.WebView.getCurrentWebViewPackage();
            return p == null ? "未知" : nz(p.versionName, "?") + " (" + p.versionCode + ")";
        } catch (Throwable t) { return "检查失败"; }
    }

    /* ==================================================================
     *  小工具
     * ================================================================== */

    private static String tag(String layer) {
        return "〔" + nz(layer, "其它") + "〕";
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : "：" + m);
    }

    private static String stack(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String s = sw.toString();
            String[] ls = s.split("\n");
            StringBuilder sb = new StringBuilder("    堆栈（最多 12 行）：");
            for (int i = 0; i < Math.min(12, ls.length); i++) {
                sb.append("\n      ").append(ls[i].trim());
            }
            return sb.toString();
        } catch (Throwable ignored) { return ""; }
    }

    private static String nz(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
    }
}
