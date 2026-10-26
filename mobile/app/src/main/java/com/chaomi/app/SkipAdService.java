package com.chaomi.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 开屏 / 弹窗广告自动跳过（无障碍方案，同「李跳跳」思路）。
 *
 * === 之前为什么不生效（重要，别再踩） ===
 * 旧版 collect() 把命中的 node 塞进 found，随后父级循环对同一个 node 调了
 * `c.recycle()` —— 等于**在点击前把候选节点全销毁**，
 * performAction 直接返回 false，且不抛异常，所以看起来"没反应"。
 * 现在全程不 recycle（API 33+ 已废弃，交给 GC）。
 *
 * === 点击策略（双保险） ===
 * 1) 先 performAction(ACTION_CLICK) —— 最快
 * 2) 失败则用 dispatchGesture 在节点中心真点一下 ——
 *    很多开屏广告是 SurfaceView/自绘控件，只有 ACTION_CLICK 无效，手势才管用
 */
public class SkipAdService extends AccessibilityService {

    private static final String TAG = "ChaomiSkipAd";
    private static final long DEBOUNCE_MS = 1200;

    private String lastPkg = "";
    private long lastClickAt = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.notificationTimeout = 50;
        setServiceInfo(info);
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!AdSkipPrefs.enabled(this)) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        final String pkg = pkgCs.toString();

        // 不处理自己与系统界面
        if (pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("android")) {
            return;
        }
        if (AdSkipPrefs.whitelist(this).contains(pkg)) return;

        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastClickAt < DEBOUNCE_MS) return;

        // 1) 当前活动窗口
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (tryClick(root, pkg, now)) return;
        }
        // 2) 遍历所有窗口（开屏广告常在独立 overlay 窗口里）
        List<AccessibilityWindowInfo> wins = getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (r.equals(root)) continue;
                if (tryClick(r, pkg, now)) return;
            }
        }
    }

    /** 在给定根节点里找跳过按钮并点击 */
    private boolean tryClick(AccessibilityNodeInfo root, String pkg, long now) {
        Set<String> words = AdSkipPrefs.words(this);
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collect(root, words, found, 0);
        if (found.isEmpty()) return false;

        int delay = AdSkipPrefs.delayMs(this);
        final String targetPkg = pkg;
        final long clickAt = now;

        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo target = clickableAncestor(n);
            Rect r = new Rect();
            (target != null ? target : n).getBoundsInScreen(r);
            if (!plausible(r)) continue;

            // 方式一：直接 ACTION_CLICK
            boolean ok = false;
            if (target != null) {
                try {
                    ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } catch (Exception ignored) {
                }
            }
            if (!ok) {
                // 方式二：手势点中心（SurfaceView/自绘控件只能这样点）
                ok = tap(r.centerX(), r.centerY(), delay);
            }
            if (ok) {
                lastPkg = targetPkg;
                lastClickAt = clickAt;
                AdSkipPrefs.addHit(this, targetPkg);
                Log.i(TAG, "已跳过: " + textOf(n) + " @ " + targetPkg);
                return true;
            }
        }
        return false;
    }

    /** 手势点击：无障碍的杀手锏，能点中自绘的跳过按钮 */
    private boolean tap(int x, int y, int delayMs) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, Math.max(0, delayMs), 60));
            return dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** 递归收集文本命中关键词的节点（**不 recycle**） */
    private void collect(AccessibilityNodeInfo node, Set<String> words,
                         List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 16 || out.size() > 60) return;

        String t = textOf(node);
        if (!t.isEmpty() && matchWord(t, words)) {
            out.add(node);
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                collect(node.getChild(i), words, out, depth + 1);
            } catch (Exception ignored) {
            }
        }
    }

    private String textOf(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence tx = node.getText();
        if (tx != null && tx.toString().trim().length() > 0) return tx.toString().trim();
        CharSequence ds = node.getContentDescription();
        if (ds != null && ds.toString().trim().length() > 0) return ds.toString().trim();
        return "";
    }

    /**
     * 关键词匹配：核心文本必须"整体就是这个按钮"。
     * 覆盖常见写法：跳过 5s / 5s跳过 / 跳过广告 / 跳过公告 / 关闭广告 / skip ad 等。
     */
    /** 纯符号按钮（× ✕ ✖ 等），这类没有文字，必须单独识别 */
    private static boolean isCloseSymbol(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 2) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("×✕✖╳✗⨯❌❎xX╳╳".indexOf(c) < 0) return false;
        }
        return true;
    }

    private boolean matchWord(String s, Set<String> words) {
        if (s == null || s.isEmpty()) return false;

        // ① 纯符号：× / ✕ / x 等，直接命中
        if (isCloseSymbol(s)) return true;

        String core = s
                .replaceAll("[0-9]+\\s*[sS秒]?", "")   // 去掉 5s / 5秒
                .replaceAll("[|丨\\-—·•:：,，。.()（）\\[\\]]", "") // 去掉装饰
                .replaceAll("\\s+", "")
                .trim();
        if (core.isEmpty()) return false;
        if (core.length() > 14) return false; // 超过 14 字基本是正文

        for (String w : words) {
            if (w == null || w.isEmpty()) continue;
            if (core.equalsIgnoreCase(w)) return true;
            // 允许按钮带少量前缀后缀，如「跳过广告」「Skip Ad」「跳过 本广告」
            if (core.length() <= w.length() + 4 && core.contains(w)) return true;
        }
        return false;
    }

    /** 向上找可点击的祖先，最多 5 层 */
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * 位置合理性：跳过按钮是小控件，通常在角落。
     * 排除横跨屏幕的大块（那是整页广告容器）。
     * 注意：**不能**只允许上下区域 —— 有些 App 把跳过往中间放。
     */
    private boolean plausible(Rect r) {
        if (r.width() <= 0 || r.height() <= 0) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0 || sh <= 0) return true;

        if (r.width() > sw * 0.65f) return false;
        if (r.height() > sh * 0.30f) return false;
        // 面积过滤：跳过按钮一般 < 屏幕面积 6%
        if ((long) r.width() * r.height() > (long) sw * sh * 0.06f) return false;
        return true;
    }

    @Override
    public void onInterrupt() {
        // 系统回收无障碍时的回调，无需处理
    }
}
