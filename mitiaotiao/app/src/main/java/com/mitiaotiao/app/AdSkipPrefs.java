package com.mitiaotiao.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 去广告设置：开关、关键词、单应用黑名单 */
class AdSkipPrefs {

    private static final String P = "mt_adskip";

    /**
     * 默认关键词 —— **只保留「跳过」类**。
     *
     * ★ 这里做过一次彻底的减法：
     *   以前还有「关闭 / 取消 / 我知道了 / ×/✕/✖」等，
     *   但这些都是通用界面词，在表单、设置页、播放器、购物车里到处都是，
     *   点下去就是破坏用户操作（用户反馈「只要有 × 就会异常点击」）。
     *
     *   「跳过」几乎只出现在广告/开屏上，这才是能安全识别的信号。
     *   宁可漏掉一个只有 × 的广告，也不能在用户自己的界面乱点。
     */
    private static final Set<String> DEFAULT_WORDS = new HashSet<>(Arrays.asList(
            "跳过", "跳過",
            "跳过广告", "跳過廣告", "跳过此广告", "跳过开屏", "跳过片头", "跳过公告",
            "skip", "skipad", "skip ad", "skip ads"
    ));

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    /** 延迟多少毫秒后再点，太快容易点空 */
    static int delayMs(Context c) {
        return sp(c).getInt("delay", 120);
    }

    static void setDelayMs(Context c, int v) {
        sp(c).edit().putInt("delay", v).apply();
    }

    /** 词表规则版本：每次调整默认词表的语义就 +1，用于强制覆盖旧词表 */
    private static final int WORDS_VER = 2;

    static Set<String> words(Context c) {
        // ★ 迁移：1.x 版本默认词表里含「关闭 / 取消 / ×」等通用词，
        //   已安装用户本地存着那份旧表，不清理的话新逻辑等于没生效。
        int ver = sp(c).getInt("words_ver", 1);
        if (ver < WORDS_VER) {
            String old = sp(c).getString("words", null);
            if (old != null) {
                // 只有在用户没自定义过（与旧默认值一致）时才清空，保留用户手工添加的
                String cleaned = old;
                String[] junk = {"关闭", "取消", "我知道了", "知道了", "以后再说",
                        "稍后再说", "残忍拒绝", "放弃更新", "不同意", "暂不更新", "close",
                        "×", "✕", "✖", "╳", "✗", "⨯", "❌", "❎"};
                for (String j : junk) {
                    cleaned = cleaned.replace(j + ",", "").replace("," + j, "")
                            .replace(j, "");
                }
                sp(c).edit().putString("words", cleaned.trim()).apply();
            }
            sp(c).edit().putInt("words_ver", WORDS_VER).apply();
        }

        String raw = sp(c).getString("words", null);
        if (raw == null || raw.trim().isEmpty()) {
            return new HashSet<>(DEFAULT_WORDS);
        }
        Set<String> out = new HashSet<>();
        for (String s : raw.split("[,，\\n]")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out.isEmpty() ? new HashSet<>(DEFAULT_WORDS) : out;
    }

    static void setWords(Context c, String csv) {
        sp(c).edit().putString("words", csv).apply();
    }

    static Set<String> whitelist(Context c) {
        String raw = sp(c).getString("allow", "");
        Set<String> out = new HashSet<>();
        for (String s : raw.split("[,，\\n]")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static void setWhitelist(Context c, String csv) {
        sp(c).edit().putString("allow", csv).apply();
    }

    static void addHit(Context c, String pkg) {
        int n = sp(c).getInt("hits", 0);
        sp(c).edit().putInt("hits", n + 1)
                .putString("lastHit", pkg + " @ " + System.currentTimeMillis())
                .apply();
    }

    /** 摇一摇拦截计数 */
    static void addShakeStop(Context c) {
        sp(c).edit().putInt("shakeStops", sp(c).getInt("shakeStops", 0) + 1).apply();
    }

    static int shakeStops(Context c) {
        return sp(c).getInt("shakeStops", 0);
    }

    static int hits(Context c) {
        return sp(c).getInt("hits", 0);
    }

    static String lastHit(Context c) {
        return sp(c).getString("lastHit", "");
    }
}
