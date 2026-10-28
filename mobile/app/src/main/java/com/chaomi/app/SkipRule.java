package com.chaomi.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 跳过规则：可导入第三方成熟规则库。
 *
 * === 格式（兼容主流写法）===
 * 支持三种输入形态，解析时自动识别：
 *
 * 1) 单条规则对象
 *    { "name":"某App开屏", "pkg":"com.xxx", "activityIds":["..."],
 *      "rules":[{"matches":["跳过"],"action":"click"}] }
 *
 * 2) 规则数组
 *    [ {...}, {...} ]
 *
 * 3) 带包裹的对象（多数规则库导出成这种）
 *    { "version":1, "rules":[...] }  或  { "data":[...] } 或 { "apps":[...] }
 *
 * === 字段 ===
 *  pkg / packageName      目标包名（必填，支持 * 通配后缀，如 com.tencent.*）
 *  activityIds / activity 只在这些 Activity 生效（可空=全部）
 *  excludeActivityIds     排除的 Activity
 *  rules[]                命中规则列表
 *    matches[]            文本 / contentDescription 关键词
 *    viewIds[]            按 viewId 匹配（如 "btn_skip"）
 *    action               click（默认）| clickCenter | back | swipe
 *    position             仅 clickCenter 用，[x,y] 归一化 0~1
 *    delayMs              点击延迟
 *  enabled                默认 true
 */
class SkipRule {

    String id = "";
    String name = "";
    String pkg = "";
    List<String> activityIds = new ArrayList<>();
    List<String> excludeActivityIds = new ArrayList<>();
    List<Action> rules = new ArrayList<>();
    boolean enabled = true;
    String source = "";   // builtin | import | manual

    static class Action {
        List<String> matches = new ArrayList<>();
        List<String> viewIds = new ArrayList<>();
        String action = "click";        // click | clickCenter | back | swipe
        double[] position;              // 归一化坐标 [x, y]
        int delayMs = 0;

        boolean isBack() { return "back".equalsIgnoreCase(action); }
        boolean isSwipe() { return "swipe".equalsIgnoreCase(action); }
        boolean useCenter() { return "clickCenter".equalsIgnoreCase(action) || position != null; }
    }

    // ---------- 解析 ----------

    private static final Gson GSON = new GsonBuilder().create();

    /** 解析任意形态的规则 JSON，返回规则列表。解析不了返回空表。 */
    static List<SkipRule> parse(String json) {
        List<SkipRule> out = new ArrayList<>();
        if (json == null) return out;
        json = json.trim();
        if (json.isEmpty()) return out;

        // 去掉可能存在的 BOM
        if (json.charAt(0) == '\ufeff') json = json.substring(1);

        try {
            JsonElement root = JsonParser.parseString(json);
            collect(root, out, 0);
        } catch (Exception e) {
            // 抛出交由调用方提示
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
        return out;
    }

    private static void collect(JsonElement el, List<SkipRule> out, int depth) {
        if (el == null || depth > 4) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) collect(e, out, depth + 1);
            return;
        }
        if (!el.isJsonObject()) return;

        JsonObject o = el.getAsJsonObject();

        // 形如 { "rules": [...] } / { "data": [...] } / { "apps": [...] }
        // 但注意：真正的规则自己也有 "rules" 字段（动作列表）。
        // 区分方法：如果同时有 pkg/packageName，它本身是规则；否则当容器拆开。
        boolean selfIsRule = o.has("pkg") || o.has("packageName") || o.has("appName");
        if (!selfIsRule) {
            for (String key : new String[]{"data", "apps", "list", "items", "rules", "appList"}) {
                if (o.has(key) && o.get(key).isJsonArray()) {
                    collect(o.get(key), out, depth + 1);
                }
            }
            return;
        }

        SkipRule r = fromObject(o);
        if (r != null) out.add(r);
    }

    private static SkipRule fromObject(JsonObject o) {
        SkipRule r = new SkipRule();
        r.pkg = optString(o, "pkg");
        if (r.pkg.isEmpty()) r.pkg = optString(o, "packageName");
        if (r.pkg.isEmpty()) r.pkg = optString(o, "package");
        if (r.pkg.isEmpty()) return null;

        r.name = optString(o, "name");
        if (r.name.isEmpty()) r.name = optString(o, "appName");
        if (r.name.isEmpty()) r.name = r.pkg;

        r.activityIds = optStringList(o, "activityIds");
        if (r.activityIds.isEmpty()) r.activityIds = optStringList(o, "activities");
        r.excludeActivityIds = optStringList(o, "excludeActivityIds");
        r.enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
        r.source = optString(o, "source");
        if (r.source.isEmpty()) r.source = "import";

        // 动作列表：优先 rules，其次 actions，再次 lookFor
        JsonArray acts = null;
        for (String k : new String[]{"rules", "actions", "lookFor", "steps"}) {
            if (o.has(k) && o.get(k).isJsonArray()) {
                acts = o.getAsJsonArray(k);
                break;
            }
        }
        if (acts != null) {
            for (JsonElement ae : acts) {
                if (!ae.isJsonObject()) continue;
                Action a = fromAction(ae.getAsJsonObject());
                if (a != null) r.rules.add(a);
            }
        }
        // 兼容最简写法：顶层直接给 matches
        if (r.rules.isEmpty() && o.has("matches")) {
            Action a = fromAction(o);
            if (a != null) r.rules.add(a);
        }
        if (r.rules.isEmpty()) return null;

        r.id = r.pkg + "#" + Integer.toHexString((r.name + r.activityIds).hashCode());
        return r;
    }

    private static Action fromAction(JsonObject o) {
        Action a = new Action();
        a.matches = optStringList(o, "matches");
        if (a.matches.isEmpty()) a.matches = optStringList(o, "text");
        if (a.matches.isEmpty()) a.matches = optStringList(o, "keywords");
        a.viewIds = optStringList(o, "viewIds");
        if (a.viewIds.isEmpty()) a.viewIds = optStringList(o, "ids");

        String act = optString(o, "action");
        if (!act.isEmpty()) a.action = act;

        if (o.has("position") && o.get("position").isJsonArray()) {
            JsonArray p = o.getAsJsonArray("position");
            if (p.size() >= 2) {
                double x = p.get(0).getAsDouble();
                double y = p.get(1).getAsDouble();
                // 归一化：>1 视为像素，转成比例前先不处理，交给引擎按屏幕换算
                a.position = new double[]{x, y};
            }
        }
        if (o.has("delayMs")) {
            try {
                a.delayMs = o.get("delayMs").getAsInt();
            } catch (Exception ignored) {
            }
        }
        if (a.matches.isEmpty() && a.viewIds.isEmpty() && a.position == null
                && !a.isBack() && !a.isSwipe()) {
            return null;
        }
        return a;
    }

    private static String optString(JsonObject o, String k) {
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return "";
            JsonElement e = o.get(k);
            return e.isJsonPrimitive() ? e.getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> optStringList(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        try {
            if (!o.has(k) || o.get(k).isJsonNull()) return out;
            JsonElement e = o.get(k);
            if (e.isJsonPrimitive()) {
                String s = e.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            } else if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonPrimitive()) {
                        String s = x.getAsString().trim();
                        if (!s.isEmpty()) out.add(s);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- 序列化 ----------

    String toJson() {
        return GSON.toJson(this);
    }

    static String toJson(List<SkipRule> list) {
        return GSON.toJson(list);
    }

    /** 包名匹配：支持精确、通配后缀 com.xxx.* */
    /**
     * 包名匹配。
     * 支持三种写法（导入的第三方规则里都常见）：
     *   1. 精确包名          com.example.app
     *   2. 通配前缀          com.example.*        /  *
     *   3. 管道分隔多包名    a.com|b.com|c.com
     *
     * ★ 之前只处理了 1 和 2，导致内置里用 `|` 写的多包名规则永远不命中，
     *   等于这些规则完全失效。
     */
    boolean matchPackage(String p) {
        if (p == null || pkg == null) return false;
        if (pkg.equals("*")) return true;

        // 管道分隔：任意一个子模式命中即可
        if (pkg.indexOf('|') >= 0) {
            for (String part : pkg.split("\\|")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                if (matchOne(p, s)) return true;
            }
            return false;
        }
        return matchOne(p, pkg.trim());
    }

    private static boolean matchOne(String p, String pat) {
        if (pat.isEmpty()) return false;
        if (pat.equals("*")) return true;
        if (pat.equals(p)) return true;
        if (pat.endsWith("*")) {
            return p.startsWith(pat.substring(0, pat.length() - 1));
        }
        return false;
    }
}
