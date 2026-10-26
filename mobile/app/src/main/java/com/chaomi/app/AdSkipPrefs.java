package com.chaomi.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 去广告设置：开关、关键词、单应用黑名单 */
class AdSkipPrefs {

    private static final String P = "chaomi_adskip";

    // 默认关键词。顺序有讲究：长的放前面，避免"跳过"先命中却点到"跳过广告 5s"的父节点
    private static final Set<String> DEFAULT_WORDS = new HashSet<>(Arrays.asList(
            "跳过广告", "跳过此广告", "关闭广告", "跳过开屏", "跳过片头",
            "跳过", "关闭", "skip", "Skip", "SKIP"
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

    static Set<String> words(Context c) {
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

    static int hits(Context c) {
        return sp(c).getInt("hits", 0);
    }

    static String lastHit(Context c) {
        return sp(c).getString("lastHit", "");
    }
}
