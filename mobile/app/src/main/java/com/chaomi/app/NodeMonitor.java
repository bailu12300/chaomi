package com.chaomi.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点心跳：每 3 秒打一次 /api/ping（每分钟 20 次），
 * 用「往返耗时 - 服务端处理耗时」估算单程延迟。
 *
 * 全部在后台线程跑，UI 通过 Listener 回调拿结果。
 */
class NodeMonitor {

    /** 心跳间隔：20 次/分钟 → 3000ms */
    static final long INTERVAL_MS = 3000L;
    private static final int KEEP = 40;      // 保留最近 40 个样本

    interface Listener {
        void onSample(int rttMs, boolean ok);
        void onTarget(String target);
    }

    private Thread worker;
    private volatile boolean running;
    private final List<Integer> rtts = new ArrayList<>();

    private int lastRtt;
    private int lost, sent;

    String serverHost() {
        String b = Api.BASE;
        try {
            java.net.URL u = new java.net.URL(b);
            return u.getHost();
        } catch (Exception e) {
            return b;
        }
    }

    void start(Listener l) {
        if (running) return;
        running = true;
        if (l != null) l.onTarget(serverHost());

        worker = new Thread(() -> {
            while (running) {
                long t0 = System.currentTimeMillis();
                int rtt = -1;
                try {
                    long serverCost = Api.pingOnce();
                    rtt = (int) (System.currentTimeMillis() - t0);
                    // 去掉服务端自身处理耗时，得到近似网络往返
                    if (serverCost > 0 && serverCost < rtt) {
                        rtt = (int) (rtt - serverCost);
                    }
                    if (rtt < 0) rtt = 0;
                } catch (Exception e) {
                    rtt = -1;
                }

                sent++;
                boolean ok = rtt >= 0;
                if (ok) {
                    lastRtt = rtt;
                    synchronized (rtts) {
                        rtts.add(rtt);
                        while (rtts.size() > KEEP) rtts.remove(0);
                    }
                } else {
                    lost++;
                }

                final int r = rtt;
                final boolean fok = ok;
                final Listener fl = l;
                if (fl != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(
                            () -> fl.onSample(r, fok));
                }

                // 顺便把样本回传，服务端能统计整体延迟分布
                if (ok) {
                    try {
                        Api.pingReport(r);
                    } catch (Exception ignored) {
                    }
                }

                long spent = System.currentTimeMillis() - t0;
                long sleep = INTERVAL_MS - spent;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "node-monitor");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    boolean isRunning() {
        return running;
    }

    int lastRtt() {
        return lastRtt;
    }

    int sent() {
        return sent;
    }

    int lost() {
        return lost;
    }

    /** 丢包率百分比 */
    double lossRate() {
        return sent == 0 ? 0 : lost * 100.0 / sent;
    }

    int avg() {
        synchronized (rtts) {
            if (rtts.isEmpty()) return 0;
            long s = 0;
            for (int v : rtts) s += v;
            return (int) (s / rtts.size());
        }
    }

    int min() {
        synchronized (rtts) {
            if (rtts.isEmpty()) return 0;
            int m = Integer.MAX_VALUE;
            for (int v : rtts) m = Math.min(m, v);
            return m;
        }
    }

    int max() {
        synchronized (rtts) {
            if (rtts.isEmpty()) return 0;
            int m = 0;
            for (int v : rtts) m = Math.max(m, v);
            return m;
        }
    }

    /** 抖动：相邻样本差值的平均绝对值 */
    int jitter() {
        synchronized (rtts) {
            if (rtts.size() < 2) return 0;
            long s = 0;
            for (int i = 1; i < rtts.size(); i++) {
                s += Math.abs(rtts.get(i) - rtts.get(i - 1));
            }
            return (int) (s / (rtts.size() - 1));
        }
    }

    /** 最近 40 个样本（给折线图） */
    int[] series() {
        synchronized (rtts) {
            int[] out = new int[rtts.size()];
            for (int i = 0; i < out.length; i++) out[i] = rtts.get(i);
            return out;
        }
    }

    void reset() {
        synchronized (rtts) {
            rtts.clear();
        }
        sent = 0;
        lost = 0;
        lastRtt = 0;
    }
}
