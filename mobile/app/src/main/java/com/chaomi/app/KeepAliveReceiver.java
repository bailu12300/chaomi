package com.chaomi.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 保活兜底：
 *   BOOT_COMPLETED  开机自启
 *   USER_PRESENT    解锁后拉起（被清后台后，用户解锁即恢复）
 *   WATCHDOG        定时闹钟唤醒，把我们自己和守护服务拉起来
 *
 * 注意：Android 12+ 后台启动 ForegroundService 有限制，
 * 这里只做「尝试启动」，失败不崩溃，等下个触发点再试。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final int REQ_WATCHDOG = 2201;
    private static final long INTERVAL = 12 * 60 * 1000L; // 12 分钟

    @Override
    public void onReceive(Context c, Intent intent) {
        if (c == null || intent == null) return;
        String a = intent.getAction();
        if (a == null) return;

        boolean wantAlive = AdSkipPrefs.sp(c).getBoolean("keepalive", true);

        switch (a) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                if (wantAlive) {
                    KeepAliveService.start(c);
                    schedule(c);
                }
                break;

            case "com.chaomi.app.WATCHDOG":
                if (wantAlive) {
                    KeepAliveService.start(c);
                }
                schedule(c); // 继续排下一次
                break;
        }
    }

    /** 排一个不精确闹钟（省电），用于周期性自愈 */
    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction("com.chaomi.app.WATCHDOG");
            int flags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ_WATCHDOG, i, flags);

            long at = SystemClock.elapsedRealtime() + INTERVAL;
            if (Build.VERSION.SDK_INT >= 23) {
                // setAndAllowWhileIdle 才能在 Doze 下也醒来；不精确但够用
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction("com.chaomi.app.WATCHDOG");
            int flags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ_WATCHDOG, i, flags);
            am.cancel(pi);
        } catch (Exception ignored) {
        }
    }
}
