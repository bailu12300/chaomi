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

public class Api {
    public static final String BASE = "https://cm01.zy1080.xyz";
    private static final Gson gson = new Gson();

    /** 当前登录令牌；由 Session 恢复与写入 */
    private static volatile String TOKEN = "";

    public static void setToken(String t) {
        TOKEN = t == null ? "" : t;
    }

    /**
     * 后台线程池。
     *
     * 原来是每次 new Thread(r).start() —— 列表滚动 + 图片 + 心跳 + WebSocket
     * 同时触发时会瞬间创建几十个线程，低端机上直接卡顿甚至 OOM。
     * 改成固定 4 线程 + 队列：并发受控、线程复用、任务排队不丢。
     */
    private static final java.util.concurrent.ThreadPoolExecutor POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    4, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(256) {
                        @Override
                        public boolean offer(Runnable r) {
                            // 队列满了就丢弃最老的任务，保证新请求不被饿死
                            if (size() >= 256) poll();
                            return super.offer(r);
                        }
                    },
                    r -> {
                        Thread t = new Thread(r, "chaomi-net");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * 全局 HTTP 客户端：DNS 走 DoH（见 NetDns）。
     *
     * 为什么不用 HttpURLConnection：它无法替换 DNS 解析，
     * 一旦用户路由器/运营商缓存了过期 IP，App 就完全连不上服务器。
     * OkHttp 可以注入自定义 Dns，彻底绕开本地 DNS。
     */
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
                    // 后台任务异常不能影响 UI 线程，也不能拖垮整个池
                    android.util.Log.w("chaomi", "bg task failed: " + t);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("chaomi", "bg submit failed: " + t);
        }
    }

    // ---------- 账号 ----------

    public static AuthResult register(String email, String password, String nick) throws Exception {
        return register(email, password, nick, "");
    }

    /** 带邮箱验证码的注册 */
    public static AuthResult register(String email, String password, String nick,
                                      String code) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", email);
        b.put("password", password);
        b.put("nick", nick);
        b.put("code", code == null ? "" : code);
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

    /** 发送验证码。返回值仅为兼容保留（正常情况恒为空，验证码只发到邮箱） */
    public static String sendCode(String email, String purpose) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", email);
        b.put("purpose", purpose);
        String json = post(BASE + "/api/auth/sendcode", gson.toJson(b));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        Object d = root.get("data");
        if (!(d instanceof Map)) return "";
        Object dc = ((Map<String, Object>) d).get("devCode");
        return dc == null ? "" : String.valueOf(dc);
    }

    /** 忘记密码：验证码 + 新密码 */
    public static void resetPassword(String email, String code, String newPass) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("email", email);
        b.put("code", code);
        b.put("password", newPass);
        String json = post(BASE + "/api/auth/reset", gson.toJson(b));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
    }

    /** 上传头像，返回新头像 URL */
    public static String uploadAvatar(byte[] data, String name) throws Exception {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(data, name));
        // 复用 multipart 逻辑但打到头像接口
        String boundary = "----chaomi" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection)
                new URL(BASE + "/api/auth/avatar").openConnection();
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
        Object d = root.get("data");
        if (!(d instanceof Map)) return "";
        Object a = ((Map<String, Object>) d).get("avatar");
        return a == null ? "" : String.valueOf(a);
    }

    /** 查唯一号是否可用 */
    public static boolean uidAvailable(String uid) throws Exception {
        String json = get(BASE + "/api/auth/checkuid?uid=" + URLEncoder.encode(uid, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) return false;
        Object d = root.get("data");
        if (!(d instanceof Map)) return false;
        Object a = ((Map<String, Object>) d).get("available");
        return a instanceof Boolean && (Boolean) a;
    }

    /** 用唯一号读主页 */
    public static Map<String, Object> userByUid(String uid) throws Exception {
        String json = get(BASE + "/api/user/uid/" + URLEncoder.encode(uid, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        Object d = root.get("data");
        return d instanceof Map ? (Map<String, Object>) d : new LinkedHashMap<>();
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

    // ---------- 链接卡片 ----------

    public static LinkCard linkPreview(String url) throws Exception {
        String json = get(BASE + "/api/link/preview?url=" + URLEncoder.encode(url, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), LinkCard.class);
    }

    // ---------- 文章解析 ----------

    public static Article parseArticle(String url) throws Exception {
        String json = get(BASE + "/api/article/parse?url=" + URLEncoder.encode(url, "UTF-8"));
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
        return gson.fromJson(gson.toJson(root.get("data")), Article.class);
    }

    // ---------- RSS 资讯（服务端拉取 + 预翻译） ----------

    public static class RssPage {
        public List<RssItem> list = new ArrayList<>();
        public long nextBefore = 0;
        public boolean hasMore = false;
    }

    /** 二级分类（含每类条数） */
    public static List<RssCat> rssCats() throws Exception {
        String json = get(BASE + "/api/rss/cats");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));
        Object d = root.get("data");
        if (!(d instanceof Map)) return new ArrayList<>();
        Object cats = ((Map<String, Object>) d).get("cats");
        if (cats == null) return new ArrayList<>();
        List<RssCat> out = gson.fromJson(gson.toJson(cats),
                new TypeToken<List<RssCat>>() {}.getType());
        return out == null ? new ArrayList<>() : out;
    }

    /** 资讯列表：cat 为空表示全部；before=上一页最后一条的 pubAt（首页传 0） */
    public static RssPage rssItems(String cat, long before, int limit) throws Exception {
        StringBuilder u = new StringBuilder(BASE + "/api/rss/items?limit=" + limit);
        if (cat != null && !cat.isEmpty()) {
            u.append("&cat=").append(URLEncoder.encode(cat, "UTF-8"));
        }
        if (before > 0) u.append("&before=").append(before);

        String json = get(u.toString());
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));

        RssPage page = new RssPage();
        Object d = root.get("data");
        if (!(d instanceof Map)) return page;
        Map<String, Object> dm = (Map<String, Object>) d;
        Object list = dm.get("list");
        if (list != null) {
            List<RssItem> parsed = gson.fromJson(gson.toJson(list),
                    new TypeToken<List<RssItem>>() {}.getType());
            if (parsed != null) page.list = parsed;
        }
        page.nextBefore = numL(dm.get("nextBefore"));
        Object hm = dm.get("hasMore");
        page.hasMore = hm instanceof Boolean && (Boolean) hm;
        return page;
    }

    /** 触发服务端拉取新资讯（5 分钟节流，服务端返回 429 时抛异常） */
    public static void rssRefresh() throws Exception {
        String json = post(BASE + "/api/rss/refresh", "{}");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (err(root) != null) throw new Exception(err(root));
    }

    // ---------- 天气（Open-Meteo） ----------

    /** 按坐标取天气；lat/lon 传 0 则服务端按请求 IP 定位 */
    public static WeatherCard weather(double lat, double lon) throws Exception {
        String u = BASE + "/api/weather";
        if (lat != 0 || lon != 0) {
            u += "?lat=" + lat + "&lon=" + lon;
        }
        String json = get(u);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));
        Object d = root.get("data");
        if (d == null) throw new Exception("天气数据为空");
        return gson.fromJson(gson.toJson(d), WeatherCard.class);
    }

    /** 按城市名取天气 */
    public static WeatherCard weatherByCity(String city) throws Exception {
        String u = BASE + "/api/weather?city=" + URLEncoder.encode(city, "UTF-8");
        String json = get(u);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) throw new Exception("空响应");
        if (err(root) != null) throw new Exception(err(root));
        Object d = root.get("data");
        if (d == null) throw new Exception("天气数据为空");
        return gson.fromJson(gson.toJson(d), WeatherCard.class);
    }

    /** 天气结果序列化（本地缓存用，避免多存一份解析逻辑） */
    public static String gsonToCache(WeatherCard c) {
        return gson.toJson(c);
    }

    public static WeatherCard gsonFromCache(String json) {
        try {
            return gson.fromJson(json, WeatherCard.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static long numL(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    // 行情接口已随行情模块移除（/api/gpu/* 服务端已下线）

    // ---------- 节点监控 ----------

    /** 打一次心跳，返回服务端处理耗时（毫秒）。失败抛异常。 */
    public static long pingOnce() throws Exception {
        long t0 = System.currentTimeMillis();
        String json = get(BASE + "/api/ping?t=" + t0);
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null) return -1;
        Object data = root.get("data");
        if (!(data instanceof Map)) return -1;
        Object cost = ((Map<String, Object>) data).get("costMs");
        return cost instanceof Number ? ((Number) cost).longValue() : 0;
    }

    /** 回传实测延迟，供服务端统计 */
    public static void pingReport(int rtt) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("rtt", rtt);
        post(BASE + "/api/ping/report", gson.toJson(b));
    }

    /** 节点信息（区域/负载/心跳统计） */
    public static Map<String, Object> nodeInfo() throws Exception {
        String json = get(BASE + "/api/node");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null || err(root) != null) throw new Exception("节点信息获取失败");
        Object d = root.get("data");
        return d instanceof Map ? (Map<String, Object>) d : new LinkedHashMap<>();
    }

    /** 服务监控总览：第三方服务（DeepSeek/GPT/CF 等）在线状态 */
    public static WatchStatus watchStatus() throws Exception {
        String json = get(BASE + "/api/watch/status");
        Map<String, Object> root = gson.fromJson(json, Map.class);
        if (root == null || err(root) != null) throw new Exception("监控数据获取失败");
        WatchStatus s = new WatchStatus();
        Object d = root.get("data");
        if (!(d instanceof Map)) return s;
        Map<String, Object> dm = (Map<String, Object>) d;
        Object list = dm.get("list");
        if (list != null) {
            s.list = gson.fromJson(gson.toJson(list), new TypeToken<List<WatchResult>>() {}.getType());
        }
        if (s.list == null) s.list = new ArrayList<>();
        s.up = (int) num(dm.get("up"));
        s.down = (int) num(dm.get("down"));
        s.interval = (int) num(dm.get("interval"));
        return s;
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
        // 上轮对比
        Object pr = d.get("prevRound");
        if (pr instanceof Map) {
            Map<String, Object> pm = (Map<String, Object>) pr;
            h.prevPrice = num(pm.get("price"));
            h.prevDiff = num(pm.get("diff"));
            h.prevPct = num(pm.get("pct"));
            h.prevAt = (long) num(pm.get("at"));
            Object hp = pm.get("hasPrev");
            h.hasPrev = hp instanceof Boolean && (Boolean) hp;
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
        // 用 OkHttp 组 multipart：DNS 走 DoH，且不用手写 boundary 拼接
        okhttp3.MultipartBody.Builder mb = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM);
        for (Part p : parts) {
            mb.addFormDataPart("file", p.name,
                    okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("image/*"), p.data));
        }
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(BASE + "/api/community/upload")
                .post(mb.build());
        if (TOKEN != null && !TOKEN.isEmpty()) {
            rb.header("Authorization", "Bearer " + TOKEN);
        }
        String resp;
        okhttp3.Response r = client().newCall(rb.build()).execute();
        try {
            resp = r.body() == null ? "" : r.body().string();
        } finally {
            r.close();
        }
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

    /** 短视频去水印：提交分享内容，返回解析结果 */
    public static WmResult watermark(String raw) throws Exception {
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", raw);
        String json = post(BASE + "/api/watermark", gson.toJson(body));
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception("解析失败");
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) throw new Exception("解析失败");
        return gson.fromJson(gson.toJson(d), WmResult.class);
    }

    /** 去水印下载地址（走服务端代理，绕过平台 Referer 防盗链） */
    public static String watermarkDownloadUrl(String videoUrl, String referer, String fileName) {
        try {
            String u = java.net.URLEncoder.encode(videoUrl == null ? "" : videoUrl, "UTF-8");
            String r = java.net.URLEncoder.encode(referer == null ? "" : referer, "UTF-8");
            String n = java.net.URLEncoder.encode(fileName == null ? "video.mp4" : fileName, "UTF-8");
            return BASE + "/api/watermark/dl?u=" + u + "&r=" + r + "&name=" + n;
        } catch (Exception e) {
            return BASE + "/api/watermark/dl?u=";
        }
    }

    // ---------- 账号体系延展 ----------

    /** 修改密码，返回服务端提示语 */
    public static String changePassword(String oldPw, String newPw) throws Exception {
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", oldPw);
        body.put("newPassword", newPw);
        String json = post(BASE + "/api/auth/password", gson.toJson(body));
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "修改失败"));
        Object d = root.get("data");
        if (d instanceof java.util.Map) {
            Object m = ((java.util.Map) d).get("message");
            if (m != null) return String.valueOf(m);
        }
        return "密码已修改";
    }

    /** 登录设备信息 */
    static class SessionInfo {
        String tokenMasked;
        long createdAt;
        long expiresAt;
        boolean current;
    }

    /** 列出登录设备 */
    public static List<SessionInfo> sessions() throws Exception {
        String json = get(BASE + "/api/auth/sessions");
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "获取失败"));
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) return new ArrayList<>();
        Object list = ((java.util.Map) d).get("list");
        if (list == null) return new ArrayList<>();
        List<SessionInfo> out = gson.fromJson(gson.toJson(list),
                new TypeToken<List<SessionInfo>>() {}.getType());
        return out == null ? new ArrayList<>() : out;
    }

    /** 踢掉其它设备，返回提示语 */
    public static String kickOtherSessions() throws Exception {
        String json = request("DELETE", BASE + "/api/auth/sessions", null);
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "操作失败"));
        Object d = root.get("data");
        if (d instanceof java.util.Map) {
            Object m = ((java.util.Map) d).get("message");
            if (m != null) return String.valueOf(m);
        }
        return "已退出其它设备";
    }

    /** 我的内容（posts/comments/likes），返回已格式化的展示行 */
    public static List<String> myContents(String kind) throws Exception {
        String json = get(BASE + "/api/me/contents?kind=" + kind + "&limit=50");
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "获取失败"));
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) return new ArrayList<>();
        Object list = ((java.util.Map) d).get("list");
        if (list == null) return new ArrayList<>();
        List<java.util.Map<String, Object>> rows = gson.fromJson(gson.toJson(list),
                new TypeToken<List<java.util.Map<String, Object>>>() {}.getType());
        List<String> out = new ArrayList<>();
        if (rows == null) return out;
        for (java.util.Map<String, Object> r : rows) {
            String line;
            if ("comments".equals(kind)) {
                String body = str(r.get("body"));
                String pt = str(r.get("postTitle"));
                if (body.length() > 60) body = body.substring(0, 60) + "…";
                line = body + "\n在《" + (pt.isEmpty() ? "帖子" : pt) + "》下";
            } else {
                String t = str(r.get("title"));
                if (t.isEmpty()) t = str(r.get("body"));
                if (t.length() > 60) t = t.substring(0, 60) + "…";
                if (t.isEmpty()) t = "（无正文）";
                line = t + "\n" + num(r.get("likes")) + " 赞 · "
                        + num(r.get("commentCount")) + " 评论 · "
                        + num(r.get("views")) + " 阅读";
            }
            out.add(line);
        }
        return out;
    }

    /** 注销账号 */
    public static void deleteAccount(String password, String confirm) throws Exception {
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        body.put("confirm", confirm);
        String json = post(BASE + "/api/auth/delete", gson.toJson(body));
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "注销失败"));
    }

    private static String msgOf(java.util.Map<String, Object> root, String def) {
        if (root != null) {
            Object e = root.get("error");
            if (e != null) return String.valueOf(e);
        }
        return def;
    }

    // ---------- 万能格式转换 ----------

    /** 能力清单 */
    static class Caps {
        java.util.List<String> dataFrom = new ArrayList<>();
        java.util.List<String> dataTo = new ArrayList<>();
        java.util.List<String[]> codec = new ArrayList<>(); // [op, name]
    }

    public static Caps convertCaps() throws Exception {
        String json = get(BASE + "/api/convert/caps");
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        Caps c = new Caps();
        if (root == null || err(root) != null) return c;
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) return c;
        java.util.Map<String, Object> dm = (java.util.Map<String, Object>) d;

        Object dataObj = dm.get("data");
        if (dataObj instanceof java.util.Map) {
            java.util.Map<String, Object> dd = (java.util.Map<String, Object>) dataObj;
            c.dataFrom = toStringList(dd.get("from"));
            c.dataTo = toStringList(dd.get("to"));
        }
        Object codecObj = dm.get("codec");
        if (codecObj instanceof java.util.List) {
            for (Object o : (java.util.List) codecObj) {
                if (o instanceof java.util.Map) {
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                    c.codec.add(new String[]{str(m.get("op")), str(m.get("name"))});
                }
            }
        }
        return c;
    }

    private static java.util.List<String> toStringList(Object o) {
        java.util.List<String> out = new ArrayList<>();
        if (o instanceof java.util.List) {
            for (Object x : (java.util.List) o) out.add(str(x));
        }
        return out;
    }

    /** 数据格式转换，返回 [结果, 说明] */
    public static String[] convertData(String from, String to, String text) throws Exception {
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", to);
        body.put("text", text);
        String json = post(BASE + "/api/convert/data", gson.toJson(body));
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "转换失败"));
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) throw new Exception("转换失败");
        java.util.Map<String, Object> dm = (java.util.Map<String, Object>) d;
        String result = str(dm.get("result"));
        int rows = (int) num(dm.get("rows"));
        int cols = (int) num(dm.get("cols"));
        String info = from.toUpperCase() + " → " + to.toUpperCase()
                + (rows > 0 ? "，" + rows + " 行 " + cols + " 列" : "");
        return new String[]{result, info};
    }

    /** 编码/文本工具 */
    public static String codec(String op, String text) throws Exception {
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", op);
        body.put("text", text);
        String json = post(BASE + "/api/convert/codec", gson.toJson(body));
        java.util.Map<String, Object> root = gson.fromJson(json, java.util.Map.class);
        if (root == null || err(root) != null) throw new Exception(msgOf(root, "执行失败"));
        Object d = root.get("data");
        if (!(d instanceof java.util.Map)) throw new Exception("执行失败");
        return str(((java.util.Map<String, Object>) d).get("result"));
    }

    private static String get(String u) throws Exception {
        return request("GET", u, null);
    }

    private static String post(String u, String body) throws Exception {
        return request("POST", u, body);
    }

    private static String request(String method, String u, String body) throws Exception {
        return executeWithRetry(method, u, body, true);
    }

    /**
     * 发起请求；连接类失败时清掉 DoH 缓存重试一次。
     *
     * 场景：DoH 拿到的 IP 恰好下线/切换，清缓存重解析能自愈。
     */
    private static String executeWithRetry(String method, String u, String body,
                                           boolean allowRetry) throws Exception {
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(u);
        if (!TOKEN.isEmpty()) {
            rb.header("Authorization", "Bearer " + TOKEN);
        }
        if (body != null) {
            rb.method(method, okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"), body));
        } else {
            rb.method(method, null);
        }

        try {
            okhttp3.Response resp = client().newCall(rb.build()).execute();
            try {
                okhttp3.ResponseBody bd = resp.body();
                String text = bd == null ? "" : bd.string();
                if (resp.code() >= 400 && (text == null || text.trim().isEmpty())) {
                    // 保留状态码语义，交给上层 err() 判断
                    return "{\"error\":\"HTTP " + resp.code() + "\"}";
                }
                return text;
            } finally {
                resp.close();
            }
        } catch (java.io.IOException ioe) {
            if (allowRetry) {
                // DNS 可能过期 / 网络切换：清缓存重试一次
                NetDns.invalidateAll();
                return executeWithRetry(method, u, body, false);
            }
            throw ioe;
        }
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
