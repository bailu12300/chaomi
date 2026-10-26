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

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集服务：在手机端跑慢慢买抓取，采完实时回传服务器。
 *
 * 为什么放前台服务：采集是长任务（40+ 型号 × 8 秒 ≈ 6 分钟），
 * 后台普通线程会被系统随时冻结，必须前台保活。
 *
 * 断点续采：进度写 SharedPreferences，被杀后从断点继续，不重复跑已完成的。
 */
public class CrawlService extends Service {

    public static final String CH_ID = "chaomi_crawl";
    private static final int NOTI_ID = 1002;

    private static final String P_STATE = "chaomi_crawl";
    private static final String K_RUNNING = "running";
    private static final String K_INDEX = "done_index";
    private static final String K_OK = "ok_count";
    private static final String K_FAIL = "fail_count";
    private static final String K_LAST = "last_msg";

    private volatile boolean running;
    private Thread worker;

    // ---- 对外控制 ----

    public static void start(Context c) {
        Intent i = new Intent(c, CrawlService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CrawlService.class));
    }

    public static boolean isRunning(Context c) {
        return c.getSharedPreferences(P_STATE, MODE_PRIVATE).getBoolean(K_RUNNING, false);
    }

    public static String status(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(P_STATE, MODE_PRIVATE);
        int done = sp.getInt(K_INDEX, 0);
        int ok = sp.getInt(K_OK, 0);
        int fail = sp.getInt(K_FAIL, 0);
        String last = sp.getString(K_LAST, "");
        return "已处理 " + done + " 个 · 成功 " + ok + " · 失败 " + fail
                + (last.isEmpty() ? "" : "\n" + last);
    }

    public static void resetProgress(Context c) {
        c.getSharedPreferences(P_STATE, MODE_PRIVATE).edit()
                .putInt(K_INDEX, 0).putInt(K_OK, 0).putInt(K_FAIL, 0).apply();
    }

    // ---- Service ----

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTI_ID, noti("准备采集…"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        getSharedPreferences(P_STATE, MODE_PRIVATE).edit().putBoolean(K_RUNNING, true).apply();
        worker = new Thread(this::loop, "chaomi-crawl");
        worker.start();
        return START_STICKY;
    }

    private void loop() {
        try {
            CrawlerApi.Plan plan;
            try {
                plan = CrawlerApi.fetchTargets();
            } catch (Exception e) {
                done("下发清单失败：" + e.getMessage());
                return;
            }
            if (plan.targets.isEmpty()) {
                done("服务端没有下发型号清单");
                return;
            }

            android.content.SharedPreferences sp =
                    getSharedPreferences(P_STATE, MODE_PRIVATE);
            int startAt = sp.getInt(K_INDEX, 0);
            if (startAt >= plan.targets.size()) startAt = 0;
            int ok = sp.getInt(K_OK, 0);
            int fail = sp.getInt(K_FAIL, 0);

            List<CrawlerApi.Item> batch = new ArrayList<>();

            for (int i = startAt; i < plan.targets.size(); i++) {
                if (!running) break;
                CrawlerApi.Target t = plan.targets.get(i);

                CrawlerApi.Item it = CrawlerApi.searchOne(t);
                if (it != null) {
                    batch.add(it);
                    ok++;
                } else {
                    fail++;
                }

                // 每采到 5 条就实时回传，用户能在 App 里马上看到行情更新
                if (batch.size() >= 5) {
                    String r = flush(batch);
                    batch.clear();
                    update(Math.min(i + 1, plan.targets.size()), plan.targets.size(), ok, fail, r);
                } else {
                    update(Math.min(i + 1, plan.targets.size()), plan.targets.size(), ok, fail, "");
                }

                sp.edit().putInt(K_INDEX, i + 1).putInt(K_OK, ok).putInt(K_FAIL, fail).apply();

                // 慢一点，避免慢慢买风控返回 0 结果
                if (i < plan.targets.size() - 1) {
                    Thread.sleep(Math.max(3000, plan.intervalMs));
                }
            }

            // 收尾：把剩下的回传
            if (!batch.isEmpty()) {
                String r = flush(batch);
                done("本轮完成 · " + r);
            } else {
                done("本轮完成 · 成功 " + ok + " · 失败 " + fail);
            }
            // 重置断点，下一轮从头开始
            sp.edit().putInt(K_INDEX, 0).apply();

        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            done("采集异常：" + e.getMessage());
        } finally {
            running = false;
            getSharedPreferences(P_STATE, MODE_PRIVATE).edit()
                    .putBoolean(K_RUNNING, false).apply();
            stopSelf();
        }
    }

    private String flush(List<CrawlerApi.Item> batch) {
        try {
            return CrawlerApi.ingest(new ArrayList<>(batch));
        } catch (Exception e) {
            return "回传失败：" + e.getMessage();
        }
    }

    private void update(int done, int total, int ok, int fail, String extra) {
        String msg = "采集中 " + done + "/" + total + " · 成功 " + ok + " · 失败 " + fail;
        if (extra != null && !extra.isEmpty()) msg += " · " + extra;
        getSharedPreferences(P_STATE, MODE_PRIVATE).edit()
                .putString(K_LAST, msg).apply();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, noti(msg));
    }

    private void done(String msg) {
        getSharedPreferences(P_STATE, MODE_PRIVATE).edit()
                .putString(K_LAST, msg).apply();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, noti(msg));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "超米采集", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("在手机端抓取价格并回传");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification noti(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("超米 · 价格采集")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        getSharedPreferences(P_STATE, MODE_PRIVATE).edit()
                .putBoolean(K_RUNNING, false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
