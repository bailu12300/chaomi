package com.mitiaotiao.app;

/**
 * 米跳跳 · 精简网络层。
 *
 * 只保留规则同步所需的：共享 OkHttp 客户端（DoH DNS）+ 后台线程池。
 * 社区 / 资讯 / 天气 / 硬件行情等接口全部不属于本 App，已移除。
 */
public class Api {
    public static final String BASE = "https://cm01.zy1080.xyz";

    /** 后台线程池：规则同步 / 上报等少量网络任务 */
    private static final java.util.concurrent.ThreadPoolExecutor POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 4, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(64),
                    r -> {
                        Thread t = new Thread(r, "mitiaotiao-net");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    /** 全局 OkHttp 客户端：DNS 走 DoH（见 NetDns），绕开本地 DNS 缓存污染 */
    private static volatile okhttp3.OkHttpClient CLIENT;

    static okhttp3.OkHttpClient client() {
        if (CLIENT == null) {
            synchronized (Api.class) {
                if (CLIENT == null) {
                    okhttp3.OkHttpClient.Builder b = new okhttp3.OkHttpClient.Builder()
                            .dns(NetDns.get())
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true);
                    CLIENT = b.build();
                }
            }
        }
        return CLIENT;
    }

    /** 后台线程执行（网络禁止主线程） */
    public static void bg(Runnable r) {
        if (r == null) return;
        try {
            POOL.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    android.util.Log.w("mitiaotiao", "bg task failed: " + t);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("mitiaotiao", "bg submit failed: " + t);
        }
    }
}
