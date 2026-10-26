package com.zaoba.community;

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

public class Api {
    public static final String BASE = "https://cm01.zy1080.xyz";
    private static final Gson gson = new Gson();

    /** 当前登录令牌；由 Session 恢复与写入 */
    private static volatile String TOKEN = "";

    public static void setToken(String t) {
        TOKEN = t == null ? "" : t;
    }

    /** 后台线程执行（网络禁止主线程） */
    public static void bg(Runnable r) {
        new Thread(r).start();
    }

    // ---------- 账号 ----------

    public static AuthResult register(String email, String password, String nick) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", email);
        b.put("password", password);
        b.put("nick", nick);
        String json = post(BASE + "/api/auth/register", gson.toJson(b));
        return parseAuth(json);
    }

    public static AuthResult login(String email, String password) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", email);
        b.put("password", password);
        String json = post(BASE + "/api/auth/login", gson.toJson(b));
        return parseAuth(json);
    }

    private static AuthResult parseAuth(String json) throws Exception {
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));
        Object data = root.get("data");
        if (data == null) throw new Exception("响应缺少 data");
        AuthResult r = new AuthResult();
        Map<String, Object> d = (Map<String, Object>) data;
        r.token = str(d.get("token"));
        r.user = gson.fromJson(gson.toJson(d.get("user")), User.class);
        return r;
    }

    public static void logout() throws Exception {
        post(BASE + "/api/auth/logout", "{}");
        setToken("");
    }

    public static User me() throws Exception {
        String json = get(BASE + "/api/auth/me");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), User.class);
    }

    public static User updateProfile(String nick, String bio, String avatar) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nick", nick);
        b.put("bio", bio);
        b.put("avatar", avatar);
        String json = post(BASE + "/api/auth/profile", gson.toJson(b));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), User.class);
    }

    // ---------- 信息流 ----------

    public static List<Post> feed(long before) throws Exception {
        return feed(before, null, null, null, false);
    }

    /**
     * @param tag   标签（不带 #）
     * @param q     搜索关键字
     * @param sort  "hot" 或 null
     * @param mine  只看我的
     */
    public static List<Post> feed(long before, String tag, String q, String sort, boolean mine) throws Exception {
        StringBuilder u = new StringBuilder(BASE + "/api/community/feed?limit=30");
        if (before > 0) u.append("&before=").append(before);
        if (tag != null && !tag.isEmpty()) u.append("&tag=").append(URLEncoder.encode(tag, "UTF-8"));
        if (q != null && !q.isEmpty()) u.append("&q=").append(URLEncoder.encode(q, "UTF-8"));
        if (sort != null && !sort.isEmpty()) u.append("&sort=").append(sort);
        if (mine) u.append("&mine=1");
        String json = get(u.toString());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        List<Post> list = gson.fromJson(gson.toJson(data), new TypeToken<List<Post>>() {}.getType());
        return list == null ? new ArrayList<Post>() : list;
    }

    public static List<Map<String, Object>> hotTags() throws Exception {
        String json = get(BASE + "/api/community/tags");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) return new ArrayList<>();
        List<Map<String, Object>> list = gson.fromJson(gson.toJson(root.get("data")),
                new TypeToken<List<Map<String, Object>>>() {}.getType());
        return list == null ? new ArrayList<Map<String, Object>>() : list;
    }

    public static PostAndComments postDetail(long id) throws Exception {
        String json = get(BASE + "/api/community/post/" + id);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        PostAndComments pc = new PostAndComments();
        pc.post = gson.fromJson(gson.toJson(root.get("data")), Post.class);
        Object c = root.get("comments");
        pc.comments = c == null ? new ArrayList<>() :
                gson.fromJson(gson.toJson(c), new TypeToken<List<Comment>>() {}.getType());
        if (pc.comments == null) pc.comments = new ArrayList<>();
        Object mine = root.get("mine");
        pc.mine = mine instanceof Boolean && (Boolean) mine;
        return pc;
    }

    public static Post createPost(Post p) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", p.type);
        body.put("title", p.title);
        body.put("body", p.body);
        body.put("url", p.url);
        body.put("images", p.images);
        body.put("author", p.author);
        if (p.blocks != null && !p.blocks.isEmpty()) body.put("blocks", p.blocks);
        if (p.cover != null && !p.cover.isEmpty()) body.put("cover", p.cover);
        String json = post(BASE + "/api/community/posts", gson.toJson(body));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), Post.class);
    }

    public static Comment addComment(long id, String author, String body) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("author", author);
        b.put("body", body);
        String json = post(BASE + "/api/community/post/" + id + "/comments", gson.toJson(b));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), Comment.class);
    }

    /** 点赞结果 */
    public static class LikeResult {
        public int likes;
        public boolean liked;
    }

    public static LikeResult like(long id) throws Exception {
        String json = post(BASE + "/api/community/post/" + id + "/like", "{}");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        LikeResult r = new LikeResult();
        Object d = root.get("data");
        if (d instanceof Number) r.likes = ((Number) d).intValue();
        Object l = root.get("liked");
        r.liked = l instanceof Boolean && (Boolean) l;
        return r;
    }

    public static void deletePost(long id) throws Exception {
        String json = request("DELETE", BASE + "/api/community/post/" + id, null);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
    }

    public static void deleteComment(long commentId) throws Exception {
        String json = request("DELETE", BASE + "/api/community/comment/" + commentId, null);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
    }

    // ---------- 文章解析 ----------

    public static Article parseArticle(String url) throws Exception {
        String json = get(BASE + "/api/article/parse?url=" + URLEncoder.encode(url, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
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
        public Part(byte[] d, String n) { data = d; name = n; }
    }

    public static List<String> upload(List<Part> parts) throws Exception {
        String boundary = "----zaoba" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/community/upload").openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        auth(conn);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
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
        if (err(root) != null) throw new Exception(err(root));
        Object data = root.get("data");
        if (data == null) return new ArrayList<>();
        Map<String, Object> dm = (Map<String, Object>) data;
        Object urls = dm.get("urls");
        if (urls == null) return new ArrayList<>();
        List<String> out = gson.fromJson(gson.toJson(urls), new TypeToken<List<String>>() {}.getType());
        return out == null ? new ArrayList<String>() : out;
    }

    // ---------- 底层 HTTP ----------

    private static void auth(HttpURLConnection c) {
        String t = TOKEN;
        if (t != null && !t.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + t);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 取后端返回的 error 字段（有则说明失败） */
    private static String err(Map<String, Object> root) {
        if (root == null) return "空响应";
        Object e = root.get("error");
        if (e != null && !String.valueOf(e).isEmpty()) return String.valueOf(e);
        return null;
    }

    private static String get(String u) throws Exception {
        return request("GET", u, null);
    }

    private static String post(String u, String body) throws Exception {
        return request("POST", u, body);
    }

    private static String request(String method, String u, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        auth(c);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
