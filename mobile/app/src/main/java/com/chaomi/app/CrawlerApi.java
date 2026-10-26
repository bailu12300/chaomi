package com.chaomi.app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 慢慢买采集（原 Python 脚本迁移到 Java）。
 *
 * 原脚本走的是 maishou88 的 App 接口：
 *   POST https://appapi.maishou88.com/api/v1/homepage/searchList
 * 用固定 openid + 版本号取搜索结果，字段含 title / actualPrice / shopName / goodsId。
 *
 * 采集跑在手机端（手机 IP），采完 POST 回服务器 /api/hw/ingest。
 */
class CrawlerApi {

    private static final Gson gson = new Gson();

    // 与 taobao skill 的 main.py 保持一致
    private static final String SEARCH_URL =
            "https://appapi.maishou88.com/api/v1/homepage/searchList";
    private static final String OPENID = "564bdce0fa408fc9e1d5d42fd022ef0b";
    private static final String APP_VER = "3.7.7.2";
    private static final String UA_APP =
            "MaiShouApp/3.7.7 (iPhone; iOS 26.3; Scale/3.00)";

    /** 采集目标 */
    static class Target {
        String keyword;
        String cat;
        String brand;
        String model;
        String spec;
    }

    /** 采集结果 */
    static class Item {
        String keyword;
        String cat;
        String brand;
        String model;
        String spec;
        String title;
        double price;
        String shop;
        String platform;
        String goodsId;
        String picUrl;
    }

    /** 服务端下发的任务包 */
    static class Plan {
        List<Target> targets = new ArrayList<>();
        long intervalMs = 8000;
        long roundRestMs = 21600000L;
    }

    /** 拉取采集清单（带密钥） */
    static Plan fetchTargets() throws Exception {
        String json = Http.get(Api.BASE + "/api/crawler/targets", crawlerKey());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null || root.get("data") == null) {
            throw new Exception(errOf(root, "下发清单失败"));
        }
        Map<String, Object> d = (Map<String, Object>) root.get("data");
        Plan p = new Plan();
        Object t = d.get("targets");
        if (t != null) {
            List<Target> list = gson.fromJson(gson.toJson(t), new TypeToken<List<Target>>() {}.getType());
            if (list != null) p.targets = list;
        }
        p.intervalMs = num(d.get("intervalMs"), 8000);
        p.roundRestMs = num(d.get("roundRestMs"), 21600000L);
        return p;
    }

    /**
     * 搜一个关键词，取第一条可用结果。
     * 失败返回 null（网络/风控/无结果都算）。
     */
    static Item searchOne(Target t) {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("isCoupon", "0");
            form.put("keyword", t.keyword);
            form.put("openid", OPENID);
            form.put("order", "desc");
            form.put("page", "1");
            form.put("pddListId", "");
            form.put("sort", "");
            form.put("sourceType", "0");  // 0=全部平台
            form.put("user_id", "");

            String body = formEncode(form);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Referer", "https://hnbc018.kuaizhan.com/");
            headers.put("User-Agent", UA_APP);
            headers.put("openid", OPENID);
            headers.put("version", APP_VER);

            String resp = Http.postForm(SEARCH_URL, headers, body);
            if (resp == null || resp.isEmpty()) return null;

            // 响应可能带 BOM
            resp = resp.trim();
            if (resp.startsWith("\ufeff")) resp = resp.substring(1);

            Map<String, Object> root = gson.fromJson(resp, Map.class);
            if (root == null) return null;
            Object dataObj = root.get("data");
            if (!(dataObj instanceof List)) return null;
            List<Map<String, Object>> rows = (List<Map<String, Object>>) dataObj;
            if (rows.isEmpty()) return null;

            for (Map<String, Object> r : rows) {
                String title = str(r.get("title"));
                double price = pickPrice(r);
                if (title.isEmpty() || price <= 0) continue;
                // 型号必须出现在标题里，否则是搜偏了
                if (!titleMatches(title, t.model)) continue;

                Item it = new Item();
                it.keyword = t.keyword;
                it.cat = t.cat;
                it.brand = t.brand;
                it.model = t.model;
                it.spec = t.spec == null ? "" : t.spec;
                it.title = title;
                it.price = price;
                it.shop = str(r.get("shopName"));
                it.platform = platformOf(r.get("sourceType"));
                it.goodsId = str(r.get("goodsId"));
                it.picUrl = str(r.get("picUrl"));
                return it;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 价格优先级：券后价 > 到手价 > 原价 */
    private static double pickPrice(Map<String, Object> r) {
        double v = dbl(r.get("actualPrice"));
        if (v > 0) return v;
        v = dbl(r.get("couponPrice"));
        if (v > 0) return v;
        return dbl(r.get("originalPrice"));
    }

    private static String platformOf(Object src) {
        String s = str(src);
        switch (s) {
            case "1": return "淘宝";
            case "2": return "京东";
            case "3": return "拼多多";
            case "4": return "苏宁";
            case "5": return "唯品会";
            case "7": return "抖音";
            case "8": return "快手";
            default: return "慢慢买";
        }
    }

    /**
     * 标题必须含型号。用「核心数字型号」匹配，而不是整串：
     *   model "R7 7800X3D"  → core "7800X3D"  → 命中「AMD 锐龙7 7800X3D」
     *   model "RTX 5070"    → core "5070"     → 命中「RTX5070」也命中「RTX 5070」
     * 同时拒绝更高阶型号：核心数字后面紧跟 Ti/Super/X3D/KF 的，判为不同型号。
     */
    private static boolean titleMatches(String title, String model) {
        if (model == null || model.isEmpty()) return true;
        String t = norm(title);
        String core = coreModel(model);
        if (core.isEmpty()) return true;
        int idx = t.indexOf(core);
        if (idx < 0) return false;
        String rest = t.substring(idx + core.length());
        for (String suf : new String[]{"TI", "SUPER", "XT", "X3D", "KF", "3D"}) {
            if (rest.startsWith(suf) && !core.contains(suf)) return false;
        }
        return true;
    }

    /** 取型号里「数字最多」的那一段作为核心，如 R7 7800X3D → 7800X3D */
    private static String coreModel(String model) {
        String[] parts = model.toUpperCase().split("[\\s\\-_/]+");
        String best = "";
        int bestDigits = 0;
        for (String p : parts) {
            int d = 0;
            for (int i = 0; i < p.length(); i++) if (Character.isDigit(p.charAt(i))) d++;
            if (d >= 2 && d > bestDigits) {
                best = p;
                bestDigits = d;
            }
        }
        if (best.isEmpty()) {
            // 没有数字（如 "990 EVO Plus" 已被上面覆盖，兜底用全串）
            best = norm(model);
        }
        return best;
    }

    /** 归一化：大写 + 去掉全部空白，兼容 "RTX 5070" / "RTX5070" */
    private static String norm(String s) {
        return s.toUpperCase().replaceAll("\\s+", "").trim();
    }

    /** 批量回传，返回服务端处理结果摘要 */
    static String ingest(List<Item> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "manmanbuy");
        body.put("items", items);
        String json = Http.postJson(Api.BASE + "/api/hw/ingest", gson.toJson(body), crawlerKey());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) return "空响应";
        Object err = root.get("error");
        if (err != null) throw new Exception(String.valueOf(err));
        Object d = root.get("data");
        if (!(d instanceof Map)) return "已提交";
        Map<String, Object> dm = (Map<String, Object>) d;
        return "入库 " + num(dm.get("accepted"), 0)
                + " 条 · 剔除 " + num(dm.get("rejected"), 0)
                + " 条 · 重复 " + num(dm.get("duplicate"), 0);
    }

    // ---- 工具 ----

    static String crawlerKey() {
        return BuildConfig.CRAWLER_KEY;
    }

    private static String errOf(Map<String, Object> root, String def) {
        if (root != null) {
            Object e = root.get("error");
            if (e != null) return String.valueOf(e);
        }
        return def;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double dbl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(str(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long num(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        return def;
    }

    private static String formEncode(Map<String, String> f) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            try {
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    /** 极简 HTTP，避免额外依赖 */
    static class Http {
        static String get(String u, String key) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (key != null && !key.isEmpty()) c.setRequestProperty("X-Crawler-Key", key);
            return read(c);
        }

        static String postJson(String u, String body, String key) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (key != null && !key.isEmpty()) c.setRequestProperty("X-Crawler-Key", key);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            return read(c);
        }

        static String postForm(String u, Map<String, String> headers, String body) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
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
}
