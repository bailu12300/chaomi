package com.chaomi.app;

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

    private static final String P = "chaomi_rules";
    private static final String K_RULES = "list";
    private static final String K_URLS = "sources";
    private static final String K_BUILTIN_VER = "builtinVer";

    /**
     * 内置规则版本。改了 builtin() 里的内容就 +1，
     * 已安装的老用户下次启动会自动用新内置规则覆盖旧的同名规则。
     * （离线可用的核心：所有内置规则都打包在 APK 里，不依赖服务器）
     */
    private static final int BUILTIN_VERSION = 2;

    private static final Gson GSON = new Gson();

    // ---------- 内置规则 ----------
    // ★ 全部打包在 APK 内，断网也有完整效果（服务器同步只是增量补充，失败不影响）。
    // 用户可逐条禁用。
    static List<SkipRule> builtin() {
        List<SkipRule> out = new ArrayList<>();

        // 通用：绝大多数开屏广告都有「跳过」文本
        out.add(make("通用 · 开屏跳过按钮", "*",
                new String[]{"跳过", "跳过广告", "跳过此广告", "跳过开屏", "skip", "skip ad"},
                null));

        // 通用：纯符号关闭（不含字母 x/X，避免计算器等被误点）
        out.add(make("通用 · 关闭符号", "*",
                new String[]{"×", "✕", "✖", "╳", "✗", "⨯", "❌", "❎"},
                null));

        // 通用：弹窗类（公告、更新、评分、隐私）
        out.add(make("通用 · 弹窗关闭", "*",
                new String[]{"我知道了", "知道了", "以后再说", "暂不更新", "稍后再说",
                        "残忍拒绝", "放弃更新", "不同意", "取消", "关闭"},
                null));

        // ---- 视频类：片头广告 ----
        out.add(make("视频 · 跳过片头",
                "com.qiyi.video|com.tencent.qqlive|com.youku.phone|"
                        + "tv.danmaku.bili|com.hunantv.imgo.activity|com.ss.android.ugc.aweme|"
                        + "com.smile.gifmaker|com.kuaishou.nebula|com.tencent.weishi",
                new String[]{"跳过片头", "跳过广告", "跳过", "关闭广告"},
                null));

        // ---- 电商：开屏 + 弹窗 ----
        out.add(make("电商 · 开屏",
                "com.taobao.taobao|com.tmall.wireless|com.jingdong.app.mall|"
                        + "com.xunmeng.pinduoduo|com.achievo.vipshop|com.suning.mobile.ebuy|"
                        + "com.kaola|com.alibaba.aliexpresshd",
                new String[]{"跳过", "跳过广告", "关闭", "×", "✕", "我知道了"},
                null));

        // ---- 生活服务 / 出行 ----
        out.add(make("生活 · 开屏",
                "com.sankuai.meituan|com.meituan.retail.v.android|me.ele|"
                        + "com.dianping.v1|com.taobao.trip|com.MobileTicket|ctrip.android.view|"
                        + "com.sdu.didi.psnger|com.alibaba.android.rimet",
                new String[]{"跳过", "跳过广告", "关闭", "×", "✕"},
                null));

        // ---- 社交 / 资讯 ----
        out.add(make("社交 · 开屏",
                "com.sina.weibo|com.tencent.mm|com.tencent.mobileqq|"
                        + "com.xingin.xhs|com.zhihu.android|com.netease.newsreader.activity|"
                        + "com.ss.android.article.news|com.tencent.news|com.sohu.newsclient",
                new String[]{"跳过", "跳过广告", "关闭", "×", "✕"},
                null));

        // ---- 工具 / 浏览器（广告弹窗多）----
        out.add(make("工具 · 网页弹窗",
                "com.android.chrome|com.tencent.mtt|com.UCMobile|com.baidu.searchbox|"
                        + "com.quark.browser|org.mozilla.firefox|com.microsoft.emmx",
                new String[]{"关闭", "×", "✕", "我知道了", "以后再说", "取消"},
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
