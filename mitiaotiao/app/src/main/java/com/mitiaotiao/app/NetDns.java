package com.mitiaotiao.app;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Dns;

/**
 * 带 DoH（DNS over HTTPS）的解析器。
 *
 * 为什么需要它：
 *   实测遇到过用户的路由器/运营商 DNS 缓存了**旧服务器 IP**，
 *   导致 App 全部请求打到已下线的服务器，报「failed to connect to
 *   cm01.zy1080.xyz/154.201.88.194」。用户重启路由前完全无法使用。
 *   另外国内运营商 DNS 劫持/污染很常见，也会造成同类问题。
 *
 * 做法：
 *   1. 优先用 DoH 直接问权威（阿里 / Cloudflare / Google），跳过本地 DNS
 *   2. DoH 不可用时回落到系统 DNS（保证可用性，不因 DoH 故障而全挂）
 *   3. 结果带 TTL 缓存，避免每次请求都发 DoH
 *
 * 注意：DoH 请求本身用 HttpURLConnection + 系统 DNS 引导（鸡生蛋问题），
 *      但 dns.alidns.com / cloudflare-dns.com 这些域名本身不会被劫持，所以安全。
 */
class NetDns implements Dns {

    private static final String TAG = "mitiaotiao-dns";

    /** DoH 结果缓存：host -> 记录 */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** 缓存有效期。权威 TTL 是 300 秒，这里取 120 秒，兼顾及时性与省流量 */
    private static final long TTL_MS = 120_000L;

    private static class Entry {
        final List<InetAddress> addrs;
        final long at;

        Entry(List<InetAddress> a) {
            this.addrs = a;
            this.at = System.currentTimeMillis();
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < TTL_MS;
        }
    }

    private static final NetDns INSTANCE = new NetDns();

    static NetDns get() {
        return INSTANCE;
    }

    /** 手动失效（连接失败时调用，强制下次重新解析） */
    static void invalidate(String host) {
        if (host != null) CACHE.remove(host);
    }

    static void invalidateAll() {
        CACHE.clear();
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
        if (hostname == null) {
            throw new java.net.UnknownHostException("hostname == null");
        }

        Entry e = CACHE.get(hostname);
        if (e != null && e.fresh()) {
            return e.addrs;
        }

        List<InetAddress> viaDoh = resolveDoh(hostname);
        if (viaDoh != null && !viaDoh.isEmpty()) {
            CACHE.put(hostname, new Entry(viaDoh));
            return viaDoh;
        }

        // DoH 不可用 → 退回系统 DNS（可用性优先）
        List<InetAddress> viaSystem = resolveSystem(hostname);
        if (viaSystem != null && !viaSystem.isEmpty()) {
            CACHE.put(hostname, new Entry(viaSystem));
            return viaSystem;
        }

        throw new java.net.UnknownHostException("无法解析 " + hostname);
    }

    // ---------- DoH ----------

    /**
     * 多个 DoH 端点依次尝试。
     * 阿里放在第一位：国内访问最快、最稳；Cloudflare/Google 作为兜底。
     */
    private static final String[][] DOH_ENDPOINTS = {
            // {url 模板, 名称}
            {"https://dns.alidns.com/resolve?name=%s&type=A", "AliDNS"},
            {"https://cloudflare-dns.com/dns-query?name=%s&type=A", "Cloudflare"},
            {"https://dns.google/resolve?name=%s&type=A", "Google"},
    };

    private List<InetAddress> resolveDoh(String host) {
        for (String[] ep : DOH_ENDPOINTS) {
            try {
                String url = String.format(ep[0], java.net.URLEncoder.encode(host, "UTF-8"));
                String json = httpGetJson(url);
                if (json == null) continue;

                List<String> ips = parseDohAnswer(json);
                if (!ips.isEmpty()) {
                    List<InetAddress> out = new ArrayList<>(ips.size());
                    for (String ip : ips) {
                        try {
                            // 用字面量地址构造，不再触发解析
                            out.add(InetAddress.getByName(ip));
                        } catch (Exception ignored) {
                        }
                    }
                    if (!out.isEmpty()) {
                        Log.i(TAG, host + " via " + ep[1] + " -> " + ips);
                        return out;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, ep[1] + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    private String httpGetJson(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Accept", "application/dns-json");
            c.setRequestProperty("User-Agent", "mitiaotiao/1.0");
            if (c.getResponseCode() != 200) return null;

            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 解析 DoH JSON。
     *
     * 返回格式（RFC 8484 JSON）：
     *   {"Status":0,"Answer":[{"name":"...","type":1,"TTL":300,"data":"1.2.3.4"}, ...]}
     *
     * 只取 type==1（A 记录），忽略 CNAME（5）。
     */
    private List<String> parseDohAnswer(String json) {
        List<String> out = new ArrayList<>();
        try {
            // 不引 JSON 库做严格解析：这里有 Gson，但 DoH 响应极小，
            // 用轻量定位足够，也避免依赖注入顺序问题。
            int idx = json.indexOf("\"Answer\"");
            if (idx < 0) return out;

            int i = idx;
            while (true) {
                int t = json.indexOf("\"type\"", i);
                if (t < 0) break;
                int tv = typeValueAt(json, t);
                int d = json.indexOf("\"data\"", t);
                if (d < 0) break;
                String data = stringValueAt(json, d);
                // type 1 = A 记录；也接受没写 type 的情况
                if ((tv == 1 || tv == -1) && data != null && isIpv4(data)) {
                    out.add(data);
                }
                i = d + 6;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private int typeValueAt(String s, int typeKeyPos) {
        int colon = s.indexOf(':', typeKeyPos);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        int start = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (Exception e) {
            return -1;
        }
    }

    private String stringValueAt(String s, int dataKeyPos) {
        int colon = s.indexOf(':', dataKeyPos);
        if (colon < 0) return null;
        int q1 = s.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = s.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return s.substring(q1 + 1, q2);
    }

    private boolean isIpv4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) return false;
        for (String x : p) {
            try {
                int v = Integer.parseInt(x);
                if (v < 0 || v > 255) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // ---------- 系统 DNS ----------

    private List<InetAddress> resolveSystem(String host) {
        try {
            InetAddress[] arr = InetAddress.getAllByName(host);
            if (arr == null || arr.length == 0) return null;
            List<InetAddress> out = new ArrayList<>(arr.length);
            Collections.addAll(out, arr);
            Log.i(TAG, host + " via system -> " + out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
