package com.mitiaotiao.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 保活兜底广播。
 *
 * 触发来源：
 *   BOOT_COMPLETED / LOCKED_BOOT_COMPLETED  开机自启
 *   USER_PRESENT                            解锁（被清后台后用户解锁即恢复）
 *   MY_PACKAGE_REPLACED                     升级后恢复
 *   KEEPALIVE_TICK（AlarmManager）          周期性巡检，进程被杀也能触发
 *   TASK_REMOVED（隐性）                    用户划掉任务后快速拉起
 *
 * ★ 关于 Android 12+ 的后台启动限制：
 *   从 AlarmManager 广播里调 startForegroundService 会抛
 *   ForegroundServiceStartNotAllowedException —— 这是"复活失败"的真正原因。
 *   系统只在有限豁免下放行，其中一条是「应用在电池优化白名单里」。
 *   所以这里先判断白名单：在白名单内直接拉；不在就发通知让用户点一下。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "MtKeepAlive";
    private static final String CH_ID = "mt_alert";
    private static final int NOTI_REVIVE_ID = 1002;

    @Override
    public void onReceive(Context c, Intent intent) {
        if (c == null || intent == null) return;
        String a = intent.getAction();
        if (a == null) return;

        boolean wantAlive = AdSkipPrefs.sp(c).getBoolean("keepalive", true);
        Log.i(TAG, "收到广播: " + a + " wantAlive=" + wantAlive);

        if (!wantAlive) {
            KeepAliveScheduler.cancel(c);
            return;
        }

        switch (a) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case KeepAliveScheduler.ACTION_TICK:
                tryStart(c, a);
                // 无论这次成功与否都继续排下一次，否则链条断了就永不复活
                KeepAliveScheduler.schedule(c);
                break;

            default:
                // 未知 action 也顺手补一次巡检，避免被系统换成别的 action 后彻底静默
                KeepAliveScheduler.schedule(c);
                break;
        }
    }

    /** 尝试拉起守护服务；Android 12+ 拉起失败时降级为「发通知请用户点一下」 */
    private static void tryStart(Context c, String from) {
        // 已经在跑就不重复拉（用心跳判断，而不是可能残留的布尔标志）
        if (KeepAliveService.isRunning(c)) {
            Log.i(TAG, "守护在跑，无需拉起 (from=" + from + ")");
            return;
        }

        boolean started = KeepAliveService.startCompat(c);
        if (started) {
            Log.i(TAG, "已拉起守护 (from=" + from + ")");
            return;
        }

        // 拉起失败：若不在电池优化白名单，系统禁止后台启动前台服务。
        // 这时只能提示用户点一下（点击会让应用短暂进入前台）。
        Log.w(TAG, "拉起失败 (from=" + from + ")，改发通知");
        notifyRevive(c);
    }

    /** 发一条「点一下恢复守护」的通知 */
    private static void notifyRevive(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "米跳跳提醒", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("守护断开时提醒恢复");
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);

            Notification n = new NotificationCompat.Builder(c, CH_ID)
                    .setContentTitle("米跳跳守护已断开")
                    .setContentText("点一下恢复去广告守护")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTI_REVIVE_ID, n);
        } catch (Throwable t) {
            Log.w(TAG, "发恢复通知失败: " + t);
        }
    }
}
