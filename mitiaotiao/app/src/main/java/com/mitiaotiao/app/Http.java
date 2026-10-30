package com.mitiaotiao.app;

import java.util.concurrent.TimeUnit;

/**
 * 轻量 HTTP 工具（规则同步等场景复用）。
 *
 * 走 Api 的共享 OkHttp 客户端：DNS 由 DoH 解析，
 * 不受本地路由/运营商缓存过期 IP 的影响。
 */
class Http {

    static String get(String u, String crawlerKey) throws Exception {
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(u)
                .header("User-Agent", "mitiaotiao/1.0");
        if (crawlerKey != null && !crawlerKey.isEmpty()) {
            rb.header("X-Crawler-Key", crawlerKey);
        }
        return exec(rb.get().build());
    }

    static String postJson(String u, String body, String crawlerKey) throws Exception {
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(u)
                .header("User-Agent", "mitiaotiao/1.0")
                .post(okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json; charset=utf-8"), body));
        if (crawlerKey != null && !crawlerKey.isEmpty()) {
            rb.header("X-Crawler-Key", crawlerKey);
        }
        return exec(rb.build());
    }

    private static String exec(okhttp3.Request req) throws Exception {
        okhttp3.OkHttpClient cl = Api.client().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        okhttp3.Response r = cl.newCall(req).execute();
        try {
            return r.body() == null ? "" : r.body().string();
        } finally {
            r.close();
        }
    }
}
