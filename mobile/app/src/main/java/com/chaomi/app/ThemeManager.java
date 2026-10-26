package com.chaomi.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题管理：默认浅色，可切深色。
 *
 * 用 AppCompatDelegate.setDefaultNightMode() 驱动 values / values-night，
 * 而不是依赖系统设置 —— 用户能手动选，且默认强制浅色。
 *
 * 注意：切换后要让 Activity 重建（recreate）才能彻底生效；
 * 部分机型不重建也能刷新，但重建最稳。
 */
class ThemeManager {

    private static final String P = "chaomi_theme";
    private static final String K_MODE = "mode";   // light | dark | auto

    static final String LIGHT = "light";
    static final String DARK = "dark";
    static final String AUTO = "auto";

    static String mode(Context c) {
        String m = c.getSharedPreferences(P, Context.MODE_PRIVATE)
                .getString(K_MODE, LIGHT);
        if (m == null) m = LIGHT;
        return m;
    }

    static void setMode(Context c, String m) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE)
                .edit().putString(K_MODE, m).apply();
        apply(c);
    }

    /** 在 Application.onCreate / Activity.onCreate 之前调用 */
    static void apply(Context c) {
        int night;
        switch (mode(c)) {
            case DARK:
                night = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            case AUTO:
                night = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
            default:
                night = AppCompatDelegate.MODE_NIGHT_NO;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(night);
    }

    /** 当前是否深色（按解析后的配置判断，AUTO 时跟随系统） */
    static boolean isDark(Context c) {
        int ui = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 切换后重建当前 Activity，让所有资源重新解析 */
    static void recreate(Activity a) {
        if (a == null || a.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 11) {
            a.recreate();
        }
    }

    static String label(Context c) {
        switch (mode(c)) {
            case DARK: return "深色";
            case AUTO: return "跟随系统";
            default: return "浅色";
        }
    }
}
