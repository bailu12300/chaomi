package com.chaomi.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 硬件行情采集器 —— 慢慢买免登录抓取（单型号）。
 *
 * 架构：采集跑在 App 端（手机 IP，规避慢慢买对服务器 IP 的风控），
 * 服务器只负责「下发型号清单 + 校验清洗入库」。
 *
 * 数据源：https://s.manmanbuy.com/m/search/result?keyword=<型号>
 *   免登录、SSR 直出，商品数据内嵌在 HTML 的 DiscountItemH5_box 卡片里。
 *
 * 过滤原则（宁可无价，不可错价）：
 *   1. 只保留「单型号」：标题命中一个型号，且不出现第二个型号/CPU 打包
 *   2. 剔除整机/主机/套装/板U
 *   3. 剔除二手/拆机/矿卡/翻新
 */
class HwCrawler {

    private static final String TAG = "chaomi-hwcrawler";

    private static final String SEARCH_URL = "https://s.manmanbuy.com/m/search/result?keyword=";
    private static final String UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    // ---- 单型号过滤规则（与服务器 crawler.go 的过滤口径一致）----

    // 收录型号 core token（归一化后），长 token 优先匹配
    private static final String[] MODEL_TOKENS = {
            "RTX4070TISUPER", "RTX4080SUPER", "RTX4060TI", "RTX4070SUPER",
            "RTX4070TI", "RTX5060TI", "RTX5070TI", "RTX5090D",
            "RTX4060", "RTX4070", "RTX4080", "RTX4090",
            "RTX5060", "RTX5070", "RTX5080", "RTX5090",
    };
    private static final Map<String, String> TOKEN_TO_MODEL = new LinkedHashMap<>();
    static {
        TOKEN_TO_MODEL.put("RTX4060", "RTX 4060");
        TOKEN_TO_MODEL.put("RTX4060TI", "RTX 4060 Ti");
        TOKEN_TO_MODEL.put("RTX4070", "RTX 4070");
        TOKEN_TO_MODEL.put("RTX4070SUPER", "RTX 4070 SUPER");
        TOKEN_TO_MODEL.put("RTX4070TI", "RTX 4070 Ti");
        TOKEN_TO_MODEL.put("RTX4070TISUPER", "RTX 4070 Ti SUPER");
        TOKEN_TO_MODEL.put("RTX4080", "RTX 4080");
        TOKEN_TO_MODEL.put("RTX4080SUPER", "RTX 4080 SUPER");
        TOKEN_TO_MODEL.put("RTX4090", "RTX 4090");
        TOKEN_TO_MODEL.put("RTX5060", "RTX 5060");
        TOKEN_TO_MODEL.put("RTX5060TI", "RTX 5060 Ti");
        TOKEN_TO_MODEL.put("RTX5070", "RTX 5070");
        TOKEN_TO_MODEL.put("RTX5070TI", "RTX 5070 Ti");
        TOKEN_TO_MODEL.put("RTX5080", "RTX 5080");
        TOKEN_TO_MODEL.put("RTX5090", "RTX 5090");
        TOKEN_TO_MODEL.put("RTX5090D", "RTX 5090 D");
    }

    private static final String[] WHOLE_MACHINE_WORDS = {
            "整机", "主机", "组装机", "组装电脑", "台式整机", "全套", "套装",
            "板U", "板u", "CPU套装", "处理器套装", "全家桶",
            "显示器", "键盘", "鼠标", "机箱", "电源", "散热器", "内存", "固态", "硬盘",
            "水冷", "风冷", "游戏本", "笔记本", "迷你主机", "一体机",
    };

    private static final String[] USED_WORDS = {
            "二手", "拆机", "矿卡", "翻新", "维修", "回收", "工包", "测试卡", "样品",
    };

    // CPU 型号特征（用于检测 "R9 9950X3D + RTX 5090D" 这类 CPU+GPU 打包）
    private static final Pattern CPU_PAT = Pattern.compile(
            "(锐龙|酷睿|至强|R\\d\\s?[- ]?\\d{4}|I\\d\\s?[- ]?\\d{3,4}\\w*|\\d{4}X3D)",
            Pattern.CASE_INSENSITIVE);

    // ---- 页面解析正则 ----
    private static final Pattern CARD_RE = Pattern.compile(
            "<div class=\"flex m-row DiscountItemH5_box__[^\"]*\">(.*?)(?=<div class=\"flex m-row DiscountItemH5_box__|$)",
            Pattern.DOTALL);
    private static final Pattern TITLE_RE = Pattern.compile(
            "DiscountItemH5_title__[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern DESC_RE = Pattern.compile(
            "DiscountItemH5_desc__[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern MALL_RE = Pattern.compile(
            "DiscountItemH5_mall__[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern MALL_FIRST_RE = Pattern.compile(
            "<span[^>]*>([^<]*)</span>");
    private static final Pattern URL_RE = Pattern.compile(
            "DiscountItemH5_go__[^\"]*\"\\s*href=\"([^\"]+)\"");
    private static final Pattern PRICE_RE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");

    /**
     * 抓取单个型号，返回该型号下的商品（已过滤、精确型号匹配）。
     * keyword 形如 "RTX 5090"，modelStd 形如 "RTX 5090"（标准名）。
     */
    static List<Map<String, Object>> fetchModel(String keyword, String modelStd) {
        List<Map<String, Object>> out = new ArrayList<>();
        String html;
        try {
            html = httpGet(SEARCH_URL + java.net.URLEncoder.encode(keyword, "UTF-8"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "抓取 " + keyword + " 失败: " + e);
            return out;
        }
        if (html == null) return out;

        Matcher cm = CARD_RE.matcher(html);
        while (cm.find()) {
            String card = cm.group(1);
            String title = stripTags(first(TITLE_RE, card)).trim();
            // 去掉「已结束/历史新低」等状态标签
            title = title.replaceFirst("^(已结束|已过期|已售罄|历史新低|历史最低|新低|最低|促销)\\s*", "").trim();
            if (title.isEmpty()) continue;

            // 过滤：只看单型号 + 非整机 + 非二手
            if (!shouldKeep(title)) continue;
            String model = extractModel(title);
            if (model == null || !model.equals(modelStd)) continue;

            String desc = stripTags(first(DESC_RE, card)).trim();
            Double price = parsePrice(desc);
            if (price == null) continue;

            String mall = first(MALL_FIRST_RE, first(MALL_RE, card));
            String url = first(URL_RE, card);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", title);
            item.put("price", price);
            item.put("platform", mall == null ? "" : mall.trim());
            item.put("url", url == null ? "" : url);
            item.put("model", model);
            out.add(item);
        }
        return out;
    }

    // ---------- 过滤逻辑 ----------

    /** 最终判定：单型号 + 非整机 + 非二手 */
    static boolean shouldKeep(String title) {
        if (title == null || title.isEmpty()) return false;
        if (extractModel(title) == null) return false;
        if (isWholeMachine(title)) return false;
        if (isUsed(title)) return false;
        return true;
    }

    /** 从标题识别唯一型号，返回标准名（如 "RTX 5090"）或 null */
    static String extractModel(String title) {
        List<String> tokens = findModelTokens(title);
        if (tokens.isEmpty()) return null;
        // 多个不同 token → 多型号打包
        String first = tokens.get(0);
        for (String t : tokens) {
            if (!t.equals(first)) return null;
        }
        return TOKEN_TO_MODEL.get(first);
    }

    /** 找出标题里所有型号 token（归一化，如 "RTX5070TI"） */
    private static List<String> findModelTokens(String title) {
        List<String> found = new ArrayList<>();
        String t = norm(title);
        // 按 "RTX" 切分，每段里找 40xx/50xx [+ 后缀]
        String[] segs = t.split("RTX");
        Pattern numPat = Pattern.compile("(40\\d0|50\\d0)\\s*(TI|SUPER|D)?");
        for (String seg : segs) {
            Matcher m = numPat.matcher(seg);
            while (m.find()) {
                String core = m.group(1);
                String suffix = m.group(2) == null ? "" : m.group(2);
                // 继续吞后续后缀（处理 "4070 Ti SUPER"）
                int end = m.end();
                String rest = seg.substring(end);
                Matcher m2 = Pattern.compile("\\s*(TI|SUPER|D)").matcher(rest);
                StringBuilder extra = new StringBuilder();
                while (m2.find()) {
                    extra.append(m2.group(1));
                    // 只吞紧邻的第一个后缀链
                    break;
                }
                found.add("RTX" + core + suffix + extra.toString());
            }
        }
        return found;
    }

    static boolean isWholeMachine(String title) {
        String t = norm(title);
        for (String w : WHOLE_MACHINE_WORDS) {
            if (t.contains(norm(w))) return true;
        }
        // CPU + RTX 同时出现 → 板U/整机打包
        boolean hasCpu = CPU_PAT.matcher(t).find();
        boolean hasRtx = t.contains("RTX");
        return hasCpu && hasRtx;
    }

    static boolean isUsed(String title) {
        String t = norm(title);
        for (String w : USED_WORDS) {
            if (t.contains(norm(w))) return true;
        }
        return false;
    }

    // ---------- 工具 ----------

    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "").toUpperCase();
    }

    private static String first(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        return TAG_RE.matcher(s).replaceAll("");
    }

    private static Double parsePrice(String text) {
        if (text == null) return null;
        Matcher m = PRICE_RE.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 抓慢慢买搜索页 HTML。App 端直连，用移动端 UA。 */
    private static String httpGet(String url) throws Exception {
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        okhttp3.OkHttpClient cl = Api.client().newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        okhttp3.Response r = cl.newCall(rb.get().build()).execute();
        try {
            if (!r.isSuccessful()) return null;
            okhttp3.ResponseBody b = r.body();
            return b == null ? null : b.string();
        } finally {
            r.close();
        }
    }

    /**
     * 回传一批采集结果给服务器（/api/hw/ingest），服务器负责清洗入库。
     * 返回服务器应答的 accepted 数，失败抛异常。
     */
    static int ingest(List<Map<String, Object>> items, String source) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Map<String, Object> it : items) {
            JSONObject o = new JSONObject();
            o.put("keyword", str(it.get("keyword")));
            o.put("cat", str(it.get("cat")));
            o.put("brand", str(it.get("brand")));
            o.put("model", str(it.get("model")));
            o.put("spec", str(it.get("spec")));
            o.put("title", str(it.get("title")));
            o.put("price", it.get("price") == null ? JSONObject.NULL : it.get("price"));
            o.put("shop", str(it.get("shop")));
            o.put("platform", str(it.get("platform")));
            o.put("goodsId", str(it.get("goodsId")));
            o.put("picUrl", str(it.get("picUrl")));
            arr.put(o);
        }
        body.put("items", arr);
        body.put("source", source == null ? "manmanbuy" : source);

        String key = BuildConfig.CRAWLER_KEY;
        String resp = Http.postJson(Api.BASE + "/api/hw/ingest", body.toString(), key);
        JSONObject root = new JSONObject(resp);
        if (root.has("error")) throw new Exception(root.optString("error"));
        JSONObject data = root.optJSONObject("data");
        if (data == null) return 0;
        return data.optInt("accepted", 0);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
