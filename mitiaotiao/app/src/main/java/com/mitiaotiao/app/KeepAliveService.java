package com.mitiaotiao.app;

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

    public static final String CH_ID = "mt_alive";
    private static final int NOTI_ID = 1001;
    // 心跳自检间隔。★ 从 10 分钟改成 30 秒：
    //   心跳超时判定是 3 分钟，若自检间隔比它还长，会误判服务已死。
    private static final long WATCHDOG_MS = 30 * 1000L;

    private android.os.Handler handler;
    private Runnable watchdog;

    public static void start(Context c) {
        startCompat(c);
    }

    /**
     * 尝试启动守护服务，返回是否成功。
     *
     * ★ Android 12+ 在后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
     *   调用方（KeepAliveReceiver）需要知道失败，才能降级成"发通知让用户点一下"，
     *   而不是像以前那样 catch 掉当没事发生。
     */
    public static boolean startCompat(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.w("MtKeepAlive", "startCompat 失败: " + t);
            return false;
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    /** 心跳写入间隔（毫秒）——服务活着就每 30 秒刷一次时间戳 */
    private static final long BEAT_MS = 30 * 1000L;
    /** 心跳超过这个时间没更新，就认为服务已经死了 */
    private static final long BEAT_TIMEOUT_MS = 3 * 60 * 1000L;

    /**
     * 判断守护是否真的在跑。
     *
     * ★ 不能只读一个 "alive" 布尔值：
     *   进程被 ROM 强杀时 onDestroy 往往不执行，那个标志会永远停在 true，
     *   于是"看起来在跑、实际早死了"，保活逻辑也就永远不会去拉起。
     *   改成看心跳时间戳——服务活着才会刷新它。
     */
    public static boolean isRunning(Context c) {
        long beat = AdSkipPrefs.sp(c).getLong("alive_beat", 0);
        if (beat <= 0) return false;
        return System.currentTimeMillis() - beat < BEAT_TIMEOUT_MS;
    }

    private void beat() {
        AdSkipPrefs.sp(this).edit().putLong("alive_beat", System.currentTimeMillis()).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        AdSkipPrefs.sp(this).edit().putBoolean("alive", true).apply();
        beat();
        startWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被杀后重建也要恢复前台状态
        startForeground(NOTI_ID, buildNotification());
        AdSkipPrefs.sp(this).edit().putBoolean("alive", true).apply();
        beat();
        // 重建后把巡检闹钟补上（可能上次的已被系统清掉）
        KeepAliveScheduler.schedule(this);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "米跳跳守护", NotificationManager.IMPORTANCE_MIN);
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
                .setContentTitle("米跳跳")
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
            beat();   // 每轮自检都刷心跳，证明服务确实活着
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
                    .setContentTitle("米跳跳")
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
        AdSkipPrefs.sp(this).edit().putLong("alive_beat", 0).apply();
        if (handler != null && watchdog != null) {
            handler.removeCallbacks(watchdog);
        }

        // ★ 不在这里直接 start()：
        //   进程正在销毁时启动前台服务，在 Android 12+ 会被
        //   ForegroundServiceStartNotAllowedException 拒绝，等于白写。
        //   改成安排一个 3 秒后的闹钟，由系统在进程彻底退出后重新唤醒我们。
        if (AdSkipPrefs.sp(this).getBoolean("keepalive", true)) {
            KeepAliveScheduler.schedule(this, 3000L);
        }
        super.onDestroy();
    }

    /**
     * 用户从最近任务列表划掉 App 时触发。
     *
     * 很多 ROM 会连带杀掉前台服务。这里安排一次快速巡检，
     * 让守护在 2 秒后自己回来，避免"划一下就彻底失效"。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        android.util.Log.i("MtKeepAlive", "任务被划掉，安排快速恢复");
        if (AdSkipPrefs.sp(this).getBoolean("keepalive", true)) {
            KeepAliveScheduler.schedule(this, 2000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
