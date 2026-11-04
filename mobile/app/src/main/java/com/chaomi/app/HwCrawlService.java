package com.chaomi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 硬件行情采集前台服务。
 *
 * 循环：拉型号清单 → 逐个采集（间隔节流）→ 回传服务器 → 一轮完休息 roundRestMs 再下一轮。
 * 断点存 SharedPreferences，服务被系统回收后 START_STICKY 重启后续采。
 *
 * 注意：采集跑在 App 端（手机 IP），服务器只做清洗入库。
 */
public class HwCrawlService extends Service {

    private static final String TAG = "chaomi-hwcrawl";
    private static final String CHANNEL_ID = "chaomi_hw_crawl";
    private static final int NOTIF_ID = 4001;

    private static final String PREFS = "hw_crawl";
    private static final String KEY_DONE = "done_index";

    private static volatile boolean running = false;
    private volatile boolean stopFlag = false;
    private Thread worker;

    public static boolean isRunning() {
        return running;
    }

    public static void startCompat(Context ctx) {
        try {
            Intent i = new Intent(ctx, HwCrawlService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "startCompat 失败: " + e);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, HwCrawlService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        stopFlag = false;
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "chaomi-hwcrawl");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 主循环 ----------

    private void loop() {
        while (!stopFlag) {
            try {
                List<Target> targets = fetchTargets();
                if (targets.isEmpty()) {
                    log("无采集目标，稍后重试");
                    sleep(60_000);
                    continue;
                }
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                int done = sp.getInt(KEY_DONE, 0);
                long intervalMs = lastIntervalMs;

                for (int i = done; i < targets.size(); i++) {
                    if (stopFlag) return;
                    Target t = targets.get(i);
                    try {
                        int accepted = crawlOne(t);
                        log("[" + (i + 1) + "/" + targets.size() + "] " + t.model
                                + " 回传 " + accepted + " 条");
                        sp.edit().putInt(KEY_DONE, i + 1).apply();
                    } catch (Exception e) {
                        log("采集 " + t.model + " 异常: " + e);
                    }
                    sleep(intervalMs);
                }
                // 一轮完，重置断点，休息 roundRestMs 再下一轮
                sp.edit().putInt(KEY_DONE, 0).apply();
                log("一轮完成，休息 " + (lastRoundRestMs / 3600_000) + " 小时");
                sleep(lastRoundRestMs);
            } catch (Exception e) {
                log("循环异常: " + e);
                sleep(60_000);
            }
        }
    }

    // 服务器下发的节流参数（默认值兜底）
    private long lastIntervalMs = 8000;
    private long lastRoundRestMs = 6 * 3600_000L;

    private List<Target> fetchTargets() throws Exception {
        String key = BuildConfig.CRAWLER_KEY;
        String resp = Http.get(Api.BASE + "/api/crawler/targets", key);
        JSONObject root = new JSONObject(resp);
        if (root.has("error")) throw new Exception(root.optString("error"));
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new ArrayList<>();

        if (data.has("intervalMs")) lastIntervalMs = data.optLong("intervalMs", 8000);
        if (data.has("roundRestMs")) lastRoundRestMs = data.optLong("roundRestMs", 6 * 3600_000L);

        JSONArray arr = data.optJSONArray("targets");
        List<Target> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Target t = new Target();
                t.keyword = o.optString("keyword");
                t.cat = o.optString("cat");
                t.brand = o.optString("brand");
                t.model = o.optString("model");
                t.spec = o.optString("spec");
                out.add(t);
            }
        }
        return out;
    }

    /** 采集单个型号：抓慢慢买 → 单型号过滤 → 回传服务器 */
    private int crawlOne(Target t) {
        List<Map<String, Object>> items = HwCrawler.fetchModel(t.keyword, t.model);
        if (items.isEmpty()) return 0;
        // 补全回传字段（keyword/cat/brand/spec 由服务器下发，这里带上）
        for (Map<String, Object> it : items) {
            it.put("keyword", t.keyword);
            it.put("cat", t.cat);
            it.put("brand", t.brand);
            it.put("spec", t.spec);
            it.put("shop", "");
            it.put("goodsId", "");
            it.put("picUrl", "");
        }
        try {
            return HwCrawler.ingest(items, "manmanbuy");
        } catch (Exception e) {
            log("回传失败: " + e);
            return 0;
        }
    }

    private static class Target {
        String keyword, cat, brand, model, spec;
    }

    // ---------- 工具 ----------

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void log(String msg) {
        android.util.Log.i(TAG, msg);
    }

    private void startForegroundCompat() {
        createChannel();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("硬件行情采集")
                .setContentText("正在后台采集显卡价格…")
                .setContentIntent(pi)
                .setOngoing(true);
        startForeground(NOTIF_ID, b.build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "硬件行情采集", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("后台采集显卡价格");
                nm.createNotificationChannel(ch);
            }
        }
    }
}
