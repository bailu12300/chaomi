package com.chaomi.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 开屏 / 弹窗广告自动跳过（无障碍方案，同「李跳跳」思路）。
 *
 * 原理：监听界面变化 → 遍历节点树找「跳过 / 关闭 / skip」类按钮 → 模拟点击。
 *
 * 为避免误点，命中必须同时满足：
 *   1) 文本长度 ≤ 12（"跳过"是按钮，超过这个长度多半是正文描述）
 *   2) 节点自身可点击，或最近的可点击祖先是小控件（不是整块页面）
 *   3) 命中位置在屏幕上半部或下半部边缘（开屏广告的跳过键总在角落），
 *      且不是横跨整个屏幕的大块区域
 *   4) 同一包名 1.5 秒内只点一次，防止连点
 */
public class SkipAdService extends AccessibilityService {

    private static final long DEBOUNCE_MS = 1500;
    private String lastPkg = "";
    private long lastClickAt = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 80;
            setServiceInfo(info);
        }
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

        // 不处理自己和系统界面，避免把本 App 的通知/设置页误点
        if (pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("android")) {
            return;
        }

        // 黑名单：加入白名单的应用直接跳过不处理
        Set<String> allow = AdSkipPrefs.whitelist(this);
        if (allow.contains(pkg)) return;

        // 同包名防抖
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastClickAt < DEBOUNCE_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // 开屏广告常在独立窗口，退回多窗口遍历
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return;
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    if (tryClick(r, pkg, now)) return;
                } finally {
                    r.recycle();
                }
            }
            return;
        }
        try {
            tryClick(root, pkg, now);
        } finally {
            root.recycle();
        }
    }

    /** 在给定根节点里找跳过按钮并点击，成功返回 true */
    private boolean tryClick(AccessibilityNodeInfo root, String pkg, long now) {
        Set<String> words = AdSkipPrefs.words(this);
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collect(root, words, found, 0);

        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo target = clickableAncestor(n);
            if (target == null) continue;
            if (!plausible(target)) continue;

            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!ok) {
                // 有些 View 只有 parent 能点
                AccessibilityNodeInfo p = target.getParent();
                if (p != null) {
                    ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    p.recycle();
                }
            }
            if (ok) {
                lastPkg = pkg;
                lastClickAt = now;
                AdSkipPrefs.addHit(this, pkg);
                return true;
            }
        }
        return false;
    }

    /** 递归收集文本命中关键词的节点 */
    private void collect(AccessibilityNodeInfo node, Set<String> words,
                         List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 14 || out.size() > 40) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String t = text == null ? "" : text.toString().trim();
        String d = desc == null ? "" : desc.toString().trim();

        if (matchWord(t, words) || matchWord(d, words)) {
            out.add(node);
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                collect(c, words, out, depth + 1);
                c.recycle();
            }
        }
    }

    /**
     * 关键词匹配：短文本必须"整体就是这个按钮"，
     * 不能是"跳过本章"这种正文里的长句。
     */
    private boolean matchWord(String s, Set<String> words) {
        if (s == null || s.isEmpty()) return false;
        // 去掉「5s」「5秒」「关闭 |」这类装饰后再比
        String core = s.replaceAll("[0-9]+\\s*[sS秒]?", "")
                .replaceAll("[|丨\\-—·•:：]+", "")
                .replaceAll("\\s+", "")
                .trim();
        if (core.isEmpty()) return false;
        // 长度限制：超过 12 个字符基本不是按钮
        if (core.length() > 12) return false;

        for (String w : words) {
            if (w.isEmpty()) continue;
            if (core.equalsIgnoreCase(w)) return true;
            // 按钮上常带装饰，如「跳过广告」「Skip Ad」
            if (core.length() <= w.length() + 4 && core.contains(w)) return true;
        }
        return false;
    }

    /** 向上找可点击的祖先，最多 4 层 */
    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent();
            if (p == null) return null;
            if (cur != n) cur.recycle();
            cur = p;
        }
        return cur;
    }

    /**
     * 位置合理性：跳过按钮一般小、且在角落。
     * 排除横跨屏幕的大块（那是整页广告容器，点了会误触）。
     */
    private boolean plausible(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) return false;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0 || sh <= 0) return true;

        // 占屏超过 60% 宽或 40% 高 → 不是按钮
        if (r.width() > sw * 0.6f) return false;
        if (r.height() > sh * 0.4f) return false;

        // 必须落在屏幕上沿 45% 或下沿 35% 区域内（开屏广告跳过键的常见位置）
        boolean topArea = r.centerY() < sh * 0.45f;
        boolean bottomArea = r.centerY() > sh * 0.65f;
        return topArea || bottomArea;
    }

    @Override
    public void onInterrupt() {
        // 服务被中断（系统回收无障碍），无需处理，系统会重连
    }
}
