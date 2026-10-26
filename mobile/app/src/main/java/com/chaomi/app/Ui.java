package com.chaomi.app;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 状态栏 / 导航栏适配。
 *
 * targetSdk=35 时 Android 15 强制 edge-to-edge，statusBarColor 会被忽略，
 * 内容默认铺到状态栏和手势条下面。所以必须：
 *   1) 声明让系统栏透明、内容延伸到边缘
 *   2) 给根布局按 systemBars + displayCutout 加 padding，避免被刘海/状态栏遮住
 *
 * 另外 handle 了三种典型场景：
 *   - 刘海屏 / 挖孔屏（displayCutout）
 *   - 手势导航条（navigationBars）
 *   - 输入法弹出时 adjustResize 已由系统处理，这里不额外消费 ime inset
 */
class Ui {

    /** 深色背景 → 状态栏图标用浅色 */
    static void setupBars(Activity a) {
        Window w = a.getWindow();

        // Android 15+ 默认已 edge-to-edge；低版本显式声明，保证行为一致
        if (Build.VERSION.SDK_INT >= 21) {
            WindowCompat.setDecorFitsSystemWindows(w, false);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
        }

        // 状态栏/导航栏图标：深色主题要用亮色图标
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(w, a.getWindow().getDecorView());
        if (c != null) {
            c.setAppearanceLightStatusBars(false);
            c.setAppearanceLightNavigationBars(false);
        }

        // Android 9 起允许内容画进刘海区，再靠 padding 让开
        if (Build.VERSION.SDK_INT >= 28) {
            Window _w = w;
            _w.getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    /**
     * 给根布局按系统栏 + 刘海加内边距。
     * 注意：会覆盖根布局原有 padding，所以根布局自身不要再写 padding。
     */
    static void applyInsets(Activity a, int rootId) {
        View root = a.findViewById(rootId);
        if (root == null) return;
        final int baseL = root.getPaddingLeft();
        final int baseT = root.getPaddingTop();
        final int baseR = root.getPaddingRight();
        final int baseB = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(baseL + sys.left, baseT + sys.top,
                    baseR + sys.right, baseB + sys.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** 一步到位：配置系统栏 + 应用 inset */
    static void fit(Activity a) {
        setupBars(a);
        applyInsets(a, R.id.root);
    }
}
