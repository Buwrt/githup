package com.hubmobile.app;

import android.app.Application;
import android.content.Intent;

/**
 * 最早的埋点：进程一起来就先把防护链跑一遍。
 *
 * 放在 Application 而不是 Activity 里，是为了让校验发生在任何界面出现之前 ——
 * 别人就算改了入口 Activity，这一层也还在。
 */
public class App extends Application {

    /** 最近一次校验断在哪一环（0 = 通过） */
    static int sBrokenRing = 0;
    static String sBrokenDetail = "";
    static String sBrokenCode = "";

    @Override
    public void onCreate() {
        super.onCreate();
        check();
    }

    /** 跑防护链，把结果记下来给各个界面用 */
    static void check() {
        Guard.Result r = Guard.verify(app());
        sBrokenRing = r.ok ? 0 : r.brokenRing;
        sBrokenDetail = r.detail;
        sBrokenCode = r.code;
    }

    /** 当前这次运行是不是通过了防护链 */
    static boolean passed() {
        return sBrokenRing == 0;
    }

    private static App sInstance;
    static App app() { return sInstance; }

    public App() { sInstance = this; }

    /** 被判定为篡改时，跳到强制下载页（不返回、不可取消） */
    static void goBlocked(android.content.Context ctx) {
        try {
            Intent i = new Intent(ctx, BlockedActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) { }
    }
}
