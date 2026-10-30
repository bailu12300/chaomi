package com.mitiaotiao.app;

import android.app.Application;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 米跳跳 · 应用入口。
 *
 * 职责：
 *   1. 启动即应用浅色主题，避免闪一下深色
 *   2. 全局兜底：主线程未捕获异常时留存堆栈，便于定位「用起来不稳定」
 */
public class MitiaotiaoApp extends Application {

    private static final String TAG = "mitiaotiao";

    static volatile String lastCrash = "";

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.apply(this);
        installCrashGuard();
    }

    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String stack = sw.toString();
                lastCrash = stack.length() > 4000 ? stack.substring(0, 4000) : stack;
                Log.e(TAG, "未捕获异常: " + stack);
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
    }
}
