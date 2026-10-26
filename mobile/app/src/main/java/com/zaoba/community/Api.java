package com.zaoba.community;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Api {
    public static final String BASE = "https://cm01.zy1080.xyz";
    private static final Gson gson = new Gson();

    /** 后台线程执行（网络禁止主线程） */
    public static void bg(Runnable r) {
        new Thread(r).start();
    }

    // ---------- 信息流 ----------
    public static List<Post> feed(long before) throws Exception {
        String json = get(BASE + "/api/community/feed?before=" + before);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        return gson.fromJson(gson.toJson(data), new TypeToken<List<Post>>() {}.getType());
    }

    public static PostAndComments postDetail(long id) throws Exception {
        String json = get(BASE + "/api/community/post/" + id);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        PostAndComments pc = new PostAndComments();
        pc.post = gson.fromJson(gson.toJson(root.get("data")), Post.class);
        Object c = root.get("comments");
        pc.comments = c == null ? new ArrayList<>() :
                gson.fromJson(gson.toJson(c), new TypeToken<List<Comment>>() {}.getType());
        if (pc.comments == null) pc.comments = new ArrayList<>();
        return pc;
    }

    public static Post createPost(Post p) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", p.type);
        body.put("title", p.title);
        body.put("body", p.body);
        body.put("url", p.url);
        body.put("images", p.images);
        body.put("author", p.author);
        String json = post(BASE + "/api/community/posts", gson.toJson(body));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        return gson.fromJson(gson.toJson(root.get("data")), Post.class);
    }

    public static Comment addComment(long id, String author, String body) throws Exception {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("author", author);
        b.put("body", body);
        String json = post(BASE + "/api/community/post/" + id + "/comments", gson.toJson(b));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        return gson.fromJson(gson.toJson(root.get("data")), Comment.class);
    }

    public static int like(long id) throws Exception {
        String json = post(BASE + "/api/community/post/" + id + "/like", "");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        Object d = root.get("data");
        if (d instanceof Double) return ((Double) d).intValue();
        if (d instanceof Integer) return (Integer) d;
        return 0;
    }

    public static Article parseArticle(String url) throws Exception {
        String json = get(BASE + "/api/article/parse?url=" + URLEncoder.encode(url, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        return gson.fromJson(gson.toJson(root.get("data")), Article.class);
    }

    // ---------- 硬件行情 ----------
    public static List<HwModel> hwModels() throws Exception {
        return hwModels("", "", "");
    }

    /** 行情列表：cat 品类（空=全部）、q 关键字、sort = price_asc/price_desc/change_desc/change_asc */
    public static List<HwModel> hwModels(String cat, String q, String sort) throws Exception {
        StringBuilder u = new StringBuilder(BASE + "/api/hw/models?limit=500");
        if (cat != null && !cat.isEmpty()) u.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
        if (q != null && !q.isEmpty()) u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        if (sort != null && !sort.isEmpty()) u.append("&sort=").append(sort);
        String json = get(u.toString());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        Map<String, Object> d = (Map<String, Object>) data;
        Object models = d.get("models");
        if (models == null) return new ArrayList<>();
        List<HwModel> out = gson.fromJson(gson.toJson(models), new TypeToken<List<HwModel>>() {}.getType());
        return out == null ? new ArrayList<>() : out;
    }

    /** 行情总览：型号数 / 采集轮次 / 更新时间 */
    public static class HwStatusRes {
        public int count;
        public int rounds;
        public long updatedAt;
    }

    public static HwStatusRes hwStatus() throws Exception {
        String json = get(BASE + "/api/hw/status");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        HwStatusRes s = new HwStatusRes();
        Object data = root.get("data");
        if (data == null) return s;
        Map<String, Object> d = (Map<String, Object>) data;
        s.count = (int) num(d.get("count"));
        s.rounds = (int) num(d.get("rounds"));
        s.updatedAt = (long) num(d.get("updatedAt"));
        return s;
    }

    /** 品类列表（已归一化并带数量） */
    public static List<HwCat> hwCats() throws Exception {
        String json = get(BASE + "/api/hw/cats");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        Object data = root.get("data");
        List<HwCat> out = new ArrayList<>();
        if (data == null) return out;
        Map<String, Object> d = (Map<String, Object>) data;
        List<HwCat> list = gson.fromJson(gson.toJson(d.get("cats")), new TypeToken<List<HwCat>>() {}.getType());
        return list == null ? out : list;
    }

    /** 单型号价格走势 */
    public static HwHistory hwHistory(String key) throws Exception {
        String json = get(BASE + "/api/hw/history/" + URLEncoder.encode(key, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        HwHistory h = new HwHistory();
        h.points = new ArrayList<>();
        Object data = root.get("data");
        if (data == null) return h;
        Map<String, Object> d = (Map<String, Object>) data;
        List<HwPoint> pts = gson.fromJson(gson.toJson(d.get("points")), new TypeToken<List<HwPoint>>() {}.getType());
        if (pts != null) h.points = pts;
        Object st = d.get("stats");
        if (st != null) {
            Map<String, Object> s = (Map<String, Object>) st;
            h.min = num(s.get("min"));
            h.max = num(s.get("max"));
            h.avg = num(s.get("avg"));
            h.last = num(s.get("last"));
            h.change = num(s.get("change"));
            h.changePct = num(s.get("changePct"));
            h.count = (int) num(s.get("count"));
        }
        return h;
    }

    /** 涨跌榜：window = 1d/7d/30d/空（全部） */
    public static List<HwModel> hwMovers(String window, String cat) throws Exception {
        StringBuilder u = new StringBuilder(BASE + "/api/hw/movers?limit=50");
        if (window != null && !window.isEmpty()) u.append("&window=").append(window);
        if (cat != null && !cat.isEmpty()) u.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
        String json = get(u.toString());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        Map<String, Object> d = (Map<String, Object>) data;
        List<HwModel> out = gson.fromJson(gson.toJson(d.get("movers")), new TypeToken<List<HwModel>>() {}.getType());
        return out == null ? new ArrayList<>() : out;
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0;
    }

    // ---------- 图片上传 ----------
    public static class Part {
        byte[] data;
        String name;
        Part(byte[] d, String n) { data = d; name = n; }
    }

    public static List<String> upload(List<Part> parts) throws Exception {
        String boundary = "----zaoba" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/community/upload").openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        OutputStream os = conn.getOutputStream();
        for (Part p : parts) {
            os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + p.name + "\"\r\n").getBytes("UTF-8"));
            os.write("Content-Type: image/*\r\n\r\n".getBytes("UTF-8"));
            os.write(p.data);
            os.write("\r\n".getBytes("UTF-8"));
        }
        os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();
        String resp = read(conn);
        Map<String, Object> root = gson.fromJson(resp, Map.class);
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        Map<String, Object> dm = (Map<String, Object>) data;
        Object urls = dm.get("urls");
        if (urls == null) return new ArrayList<>();
        return gson.fromJson(gson.toJson(urls), new TypeToken<List<String>>() {}.getType());
    }

    // ---------- 底层 HTTP ----------
    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        return read(c);
    }

    private static String post(String u, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        if (body != null && !body.isEmpty()) {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
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
