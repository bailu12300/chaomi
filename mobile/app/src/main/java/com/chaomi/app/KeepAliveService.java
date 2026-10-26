package com.chaomi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * 常驻守护服务：让去广告能力长期在线。
 *
 * 抗杀手段（层层叠加，但要注意国产 ROM 仍可能强杀，见 docs）：
 *   1) startForeground + 前台通知 → 系统优先级最高，不会被普通内存回收
 *   2) START_STICKY → 被杀后系统尝试重建
 *   3) 双进程互拉（AlarmManager 定时兜底）→ 被清后台后能自己回来
 *   4) 引导用户加入电池优化白名单 / 自启动白名单
 */
public class KeepAliveService extends Service {

    public static final String CH_ID = "chaomi_alive";
    private static final int NOTI_ID = 1001;
    private static final long WATCHDOG_MS = 10 * 60 * 1000L; // 10 分钟自检一次

    private android.os.Handler handler;
    private Runnable watchdog;

    public static void start(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
            // 后台启动限制（Android 12+），由 Activity 启动时兜底
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    public static boolean isRunning(Context c) {
        return AdSkipPrefs.sp(c).getBoolean("alive", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        AdSkipPrefs.sp(this).edit().putBoolean("alive", true).apply();
        startWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被杀后重建也要恢复前台状态
        startForeground(NOTI_ID, buildNotification());
        AdSkipPrefs.sp(this).edit().putBoolean("alive", true).apply();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "超米守护", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持去广告与消息接收在线");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("超米")
                .setContentText("去广告守护中")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build();
    }

    /** 定时自检：确认无障碍服务还活着，被系统关了就提示用户 */
    private void startWatchdog() {
        handler = new android.os.Handler(getMainLooper());
        watchdog = () -> {
            boolean acc = isAccessibilityOn(this);
            if (!acc) {
                AdSkipPrefs.sp(this).edit().putBoolean("need_reauth", true).apply();
                updateNotification("无障碍已失效，点这里重新开启");
            } else {
                updateNotification("去广告守护中 · 已跳过 " + AdSkipPrefs.hits(this) + " 次");
            }
            handler.postDelayed(watchdog, WATCHDOG_MS);
        };
        handler.postDelayed(watchdog, 5000);
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = Build.VERSION.SDK_INT >= 23
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
            Notification n = new NotificationCompat.Builder(this, CH_ID)
                    .setContentTitle("超米")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTI_ID, n);
        } catch (Exception ignored) {
        }
    }

    /** 无障碍服务是否已开启（查已启用的服务列表） */
    public static boolean isAccessibilityOn(Context c) {
        try {
            String path = ":" + c.getPackageName() + "/" + SkipAdService.class.getName();
            String flat = android.provider.Settings.Secure.getString(
                    c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(path.substring(1))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 是否已在电池优化白名单（false 表示仍会被省电策略限制） */
    public static boolean isIgnoringBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    @Override
    public void onDestroy() {
        AdSkipPrefs.sp(this).edit().putBoolean("alive", false).apply();
        if (handler != null && watchdog != null) {
            handler.removeCallbacks(watchdog);
        }
        // 被杀后尝试自己回来
        try {
            start(this);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
