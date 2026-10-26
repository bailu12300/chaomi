package com.chaomi.app;

import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题：固定浅色。
 *
 * 深色主题已移除（用户要求统一浅色，避免 WebView 注入样式等复杂情况）。
 * 保留此类是为了让调用方无需大改，同时集中管理深色关闭。
 */
class ThemeManager {

    static final String LIGHT = "light";

    /** 永远浅色，忽略系统设置 */
    static String mode(Context c) {
        return LIGHT;
    }

    static void setMode(Context c, String m) {
        // 已固定浅色，忽略切换请求
    }

    static void apply(Context c) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }

    static boolean isDark(Context c) {
        return false;
    }

    static void recreate(android.app.Activity a) {
        // 不再需要
    }

    static String label(Context c) {
        return "浅色";
    }
}
