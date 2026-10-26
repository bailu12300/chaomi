package com.chaomi.app;

import android.app.Application;

/**
 * 应用入口：在 Activity 创建前应用主题，避免启动瞬间闪一下深色。
 *
 * 默认浅色（ThemeManager.mode 缺省为 LIGHT），用户在 App 内可切深色/跟随系统。
 */
public class ChaomiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.apply(this);
    }
}
