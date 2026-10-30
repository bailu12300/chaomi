package com.mitiaotiao.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 保活调度器 —— 用 AlarmManager 做「服务被杀了还能拉回来」的兜底。
 *
 * ★ 为什么必须有它：
 *   之前 KeepAliveService 的注释写着"双进程互拉（AlarmManager 定时兜底）"，
 *   但代码里**根本没有 AlarmManager**，只有 onDestroy 里直接 start()。
 *   而 Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
 *   那句 start() 被 catch 吞掉，等于什么都没做。
 *   结果就是：被系统/ROM 清掉之后永不复活，用户以为是"没效果"。
 *
 * AlarmManager 是系统级的，进程死了它照样到点触发 —— 这是唯一可靠的外部拉起手段。
 * 用 setAndAllowWhileIdle 而不是 setExact：前者不需要 SCHEDULE_EXACT_ALARM 权限，
 * 在 Doze 下也能唤醒（可能被延迟几分钟，对保活完全够用）。
 */
public final class KeepAliveScheduler {

    private static final String TAG = "MtKeepAlive";
    private static final int REQ_CODE = 0x4B41;          // 固定 requestCode，覆盖式重排
    private static final long INTERVAL_MS = 15 * 60 * 1000L;  // 15 分钟巡检一次
    static final String ACTION_TICK = "com.mitiaotiao.app.KEEPALIVE_TICK";

    private KeepAliveScheduler() {}

    /** 安排下一次巡检（重复调用会覆盖上一次，不会堆积） */
    public static void schedule(Context c) {
        schedule(c, INTERVAL_MS);
    }

    /** 延迟 delayMs 后巡检一次 */
    public static void schedule(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pendingIntent(c);
            long at = SystemClock.elapsedRealtime() + Math.max(1000L, delayMs);

            // setAndAllowWhileIdle：无需特殊权限，Doze 下也可唤醒。
            // Android 6+ 起 setExact 在 Doze 下会被压制，这里不需要精确到秒，
            // 只要"十几分钟内能醒来看一眼"就够。
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
            Log.i(TAG, "已安排巡检，+" + (delayMs / 1000) + "s");
        } catch (Throwable t) {
            Log.w(TAG, "安排巡检失败: " + t);
        }
    }

    /** 取消巡检（用户主动关闭守护时调用） */
    public static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pendingIntent(c));
            Log.i(TAG, "已取消巡检");
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pendingIntent(Context c) {
        Intent i = new Intent(c, KeepAliveReceiver.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQ_CODE, i, flags);
    }
}
