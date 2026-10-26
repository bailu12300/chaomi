package com.chaomi.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 轻量 HTTP 工具。
 *
 * 原先这些方法挂在 CrawlerApi 里，App 端采集移除后抽出为独立工具类，
 * 供规则同步、链接解析等场景复用。不引第三方库，保持 APK 体积。
 */
class Http {

    static String get(String u, String crawlerKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "chaomi/1.0");
        if (crawlerKey != null && !crawlerKey.isEmpty()) {
            c.setRequestProperty("X-Crawler-Key", crawlerKey);
        }
        return read(c);
    }

    static String postJson(String u, String body, String crawlerKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (crawlerKey != null && !crawlerKey.isEmpty()) {
            c.setRequestProperty("X-Crawler-Key", crawlerKey);
        }
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        return read(c);
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
