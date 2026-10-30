package com.mitiaotiao.app;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则仓库：内置规则 + 用户导入规则的存取。
 *
 * 存储：SharedPreferences 里存 JSON 字符串。
 * 内置规则每次启动合并进来（用户可单独禁用，不会被覆盖）。
 */
class RuleStore {

    private static final String P = "mt_rules";
    private static final String K_RULES = "list";
    private static final String K_URLS = "sources";
    private static final String K_BUILTIN_VER = "builtinVer";

    /**
     * 内置规则版本。改了 builtin() 里的内容就 +1，
     * 已安装的老用户下次启动会自动用新内置规则覆盖旧的同名规则。
     * （离线可用的核心：所有内置规则都打包在 APK 里，不依赖服务器）
     */
    private static final int BUILTIN_VERSION = 3;

    private static final Gson GSON = new Gson();

    // ---------- 内置规则 ----------
    //
    // ★ 只剩「跳过」类，一条都不含 × / 关闭 / 取消。
    //
    //   这些通用词在正常界面里到处都是（表单、设置页、播放器、购物车），
    //   内置它们等于给用户埋了一层乱点风险。用户反馈过"只要有 × 就会异常点击"，
    //   所以内置规则做彻底的减法：只保留「跳过」这个广告专属信号。
    //
    //   全部打包在 APK 内，断网也有完整效果。
    static List<SkipRule> builtin() {
        List<SkipRule> out = new ArrayList<>();

        // 唯一一条内置规则：全局「跳过」识别。
        // 不分包名 —— 因为「跳过」这个词在所有 App 里含义一致，不需要按应用区分。
        out.add(make("通用 · 跳过广告", "*",
                new String[]{
                        "跳过", "跳過",
                        "跳过广告", "跳過廣告", "跳过此广告", "跳过开屏",
                        "跳过 3", "跳过3", "3s 跳过", "5s 跳过",
                        "skip", "skip ad", "skip ads", "skipad",
                },
                null));

        return out;
    }

    private static SkipRule make(String name, String pkgPattern, String[] matches, String[] viewIds) {
        SkipRule r = new SkipRule();
        r.name = name;
        r.pkg = pkgPattern;
        r.source = "builtin";
        r.enabled = true;
        SkipRule.Action a = new SkipRule.Action();
        for (String m : matches) a.matches.add(m);
        if (viewIds != null) for (String v : viewIds) a.viewIds.add(v);
        a.action = "click";
        r.rules.add(a);
        r.id = "builtin#" + name;
        return r;
    }

    // ---------- 读写 ----------

    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /**
     * 删掉「服务端已下线」的规则。
     *
     * 场景：服务端规则库更新后，某些 id 不再下发（比如这次的 ×/关闭 类规则）。
     * 如果只做增量导入，客户端会一直保留这些旧规则，继续按它们乱点。
     * 所以每次同步后，把 source=server/url 且 id 不在新名单里的规则清掉。
     *
     * 注意：只清「服务端来源」的规则，**不动内置规则和用户手工导入的**，
     * 避免误删用户自己加的东西。
     *
     * @param keep 服务端本次下发的全部 id
     */
    static void dropServerRulesNotIn(Context c, Set<String> keep) {
        List<SkipRule> stored = load(c);
        List<SkipRule> out = new ArrayList<>();
        boolean changed = false;
        for (SkipRule r : stored) {
            boolean fromServer = "server".equals(r.source);
            boolean isBuiltin = "builtin".equals(r.source)
                    || (r.id != null && r.id.startsWith("builtin#"));
            if (fromServer && !isBuiltin && (r.id == null || !keep.contains(r.id))) {
                changed = true;   // 服务端不再下发 → 删除
                continue;
            }
            out.add(r);
        }
        if (changed) save(c, out);
    }

    /** 读全部规则（内置 + 导入），按 id 去重，保留用户的 enabled 状态 */
    static List<SkipRule> all(Context c) {
        List<SkipRule> stored = load(c);
        List<SkipRule> out = new ArrayList<>(stored);
        Set<String> ids = new LinkedHashSet<>();
        for (SkipRule r : stored) ids.add(r.id);

        // 内置规则升级：版本变了就用新的内置内容覆盖同名规则（保留用户启停状态）
        int localBuiltinVer = sp(c).getInt(K_BUILTIN_VER, 0);
        boolean upgrade = localBuiltinVer < BUILTIN_VERSION;

        boolean changed = false;

        // ★ 升级时必须先删掉「已不存在的旧内置规则」。
        //   只做"同名覆盖"是不够的：1.x 的内置规则有 8 条（含 ×、关闭、取消），
        //   新版本只剩 1 条「跳过」。旧的 6 条内置规则 id 不同，
        //   不会被覆盖，会一直残留在用户手机上继续乱点。
        //   所以这里按 source=="builtin" 清一遍，只保留新版 builtin() 里有的 id。
        if (upgrade) {
            Set<String> keep = new LinkedHashSet<>();
            for (SkipRule b : builtin()) keep.add(b.id);
            List<SkipRule> filtered = new ArrayList<>();
            for (SkipRule r : out) {
                boolean isBuiltin = "builtin".equals(r.source)
                        || (r.id != null && r.id.startsWith("builtin#"));
                if (isBuiltin && !keep.contains(r.id)) {
                    changed = true;   // 丢弃：这是已下线的旧内置规则
                    continue;
                }
                filtered.add(r);
            }
            out = filtered;
            ids.clear();
            for (SkipRule r : out) ids.add(r.id);
        }

        for (SkipRule b : builtin()) {
            if (!ids.contains(b.id)) {
                out.add(b);
                changed = true;
            } else if (upgrade) {
                for (int i = 0; i < out.size(); i++) {
                    SkipRule old = out.get(i);
                    if (old.id != null && old.id.equals(b.id)) {
                        b.enabled = old.enabled;   // 保留用户是否启用
                        out.set(i, b);
                        changed = true;
                        break;
                    }
                }
            }
        }
        if (changed) save(c, out);
        if (upgrade) sp(c).edit().putInt(K_BUILTIN_VER, BUILTIN_VERSION).apply();
        return out;
    }

    /** 只取启用的规则，供引擎每次事件使用（性能考虑会缓存） */
    static List<SkipRule> enabled(Context c) {
        List<SkipRule> out = new ArrayList<>();
        for (SkipRule r : all(c)) {
            if (r.enabled) out.add(r);
        }
        return out;
    }

    private static List<SkipRule> load(Context c) {
        String json = sp(c).getString(K_RULES, "");
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            List<SkipRule> list = GSON.fromJson(json, new TypeToken<List<SkipRule>>() {}.getType());
            return list == null ? new ArrayList<SkipRule>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static void save(Context c, List<SkipRule> list) {
        sp(c).edit().putString(K_RULES, GSON.toJson(list)).apply();
        bumpVersion();
    }

    static void setEnabled(Context c, String id, boolean on) {
        List<SkipRule> list = all(c);
        for (SkipRule r : list) {
            if (r.id.equals(id)) r.enabled = on;
        }
        save(c, list);
    }

    static void remove(Context c, String id) {
        List<SkipRule> list = all(c);
        List<SkipRule> keep = new ArrayList<>();
        for (SkipRule r : list) {
            if (!r.id.equals(id)) keep.add(r);
        }
        save(c, keep);
    }

    /**
     * 导入规则 JSON（三种形态都支持）。返回新增/更新的条数。
     * 同 id 覆盖，不同 id 追加。
     */
    static int importJson(Context c, String json, String source) {
        List<SkipRule> incoming = SkipRule.parse(json);
        if (incoming.isEmpty()) return 0;

        List<SkipRule> cur = all(c);
        java.util.Map<String, SkipRule> byId = new java.util.LinkedHashMap<>();
        for (SkipRule r : cur) byId.put(r.id, r);

        int n = 0;
        for (SkipRule r : incoming) {
            if (source != null && !source.isEmpty()) r.source = source;
            SkipRule old = byId.get(r.id);
            if (old != null) {
                r.enabled = old.enabled;   // 保留用户的启停选择
            }
            byId.put(r.id, r);
            n++;
        }
        save(c, new ArrayList<>(byId.values()));
        return n;
    }

    /** 导入来源 URL 记录，方便下次刷新 */
    static Set<String> sources(Context c) {
        String raw = sp(c).getString(K_URLS, "");
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw.split("\n")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    static void addSource(Context c, String url) {
        Set<String> s = sources(c);
        s.add(url);
        sp(c).edit().putString(K_URLS, String.join("\n", s)).apply();
    }

    static int count(Context c) {
        return all(c).size();
    }

    static int enabledCount(Context c) {
        int n = 0;
        for (SkipRule r : all(c)) if (r.enabled) n++;
        return n;
    }

    // ---------- 缓存 ----------
    // 无障碍事件非常频繁（每秒数十次），不能每次都反序列化 JSON

    private static volatile List<SkipRule> cache;
    private static volatile int cacheVer = -1;
    private static volatile int version = 0;

    static void bumpVersion() {
        version++;
    }

    static List<SkipRule> enabledCached(Context c) {
        int v = version;
        if (cache != null && cacheVer == v) return cache;
        List<SkipRule> list = enabled(c);
        cache = list;
        cacheVer = v;
        return list;
    }
}
