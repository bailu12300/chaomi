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
    /** 当前窗口的 Activity 类名，供规则的 activityIds 匹配用 */
    private String lastActivity = null;

    /** 摇一摇守视：检测到摇动后立刻找「取消/关闭」把跳转掐断 */
    private ShakeGuard shakeGuard;
    /** 摇动后多久内积极扫描（毫秒），来自服务端配置 */
    private long shakeWindowMs = 6000;

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

        // 摇一摇守视：从服务端配置的阈值/窗口起步
        shakeWindowMs = RuleSync.shakeWindow(this);
        shakeGuard = new ShakeGuard(this);
        shakeGuard.setThreshold(RuleSync.shakeThreshold(this));
        shakeGuard.setCallback(mag -> {
            Log.i(TAG, "摇动触发，开始主动关闭");
            // 摇动后立刻在当前窗口找关闭入口
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) {
                String p = lastPkg == null ? "" : lastPkg;
                tryAntiShake(r, p);
            }
        });
        if (RuleSync.shakeEnabled(this)) shakeGuard.start();
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

        // 记录当前 Activity，规则的 activityIds 靠它匹配
        CharSequence cls = event.getClassName();
        if (cls != null && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastActivity = cls.toString();
        }

        // 不处理自己与系统界面
        if (pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("android")) {
            return;
        }
        if (AdSkipPrefs.whitelist(this).contains(pkg)) return;

        // ★ 工具类 App 绝不自动点击（计算器 ×、输入法按键、设置开关都不该被碰）
        if (isToolApp(pkg, lastActivity)) return;

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

    /**
     * 优先走规则：规则命中就按规则动作执行（click / clickCenter / back / swipe）。
     * 规则不命中再退回关键词兜底。这样导入的成熟规则优先，兜底保证不漏。
     */
    private boolean tryClick(AccessibilityNodeInfo root, String pkg, long now) {
        if (tryRules(root, pkg, now)) return true;
        return tryKeywords(root, pkg, now);
    }

    /**
     * 摇一摇善后：检测到摇动后，立刻把当前页面上的
     * 「取消 / 关闭 / × / 不感兴趣」点掉，掐断即将发生的跳转。
     *
     * 顺序有讲究：先点「取消」类（摇一摇弹窗通常是取消），再点关闭符号。
     */
    private boolean tryAntiShake(AccessibilityNodeInfo root, String pkg) {
        long now = System.currentTimeMillis();
        String[][] stages = {
                {"取消", "关闭广告", "不感兴趣", "以后再说", "跳过", "关闭"},
                {"×", "✕", "✖", "╳", "✗", "⨯", "❌", "❎", "我知道了"},
        };
        for (String[] stage : stages) {
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            collectMulti(root, java.util.Arrays.asList(stage), found, 0);
            for (AccessibilityNodeInfo n : found) {
                if (clickNode(n, 0)) {
                    markHit(pkg, "摇一摇善后:" + textOf(n), now);
                    AdSkipPrefs.addShakeStop(this);
                    return true;
                }
            }
        }
        return false;
    }

    /** 规则引擎 */
    private boolean tryRules(AccessibilityNodeInfo root, String pkg, long now) {
        List<SkipRule> rules = RuleStore.enabledCached(this);
        if (rules.isEmpty()) return false;

        String activity = lastActivity;
        for (SkipRule r : rules) {
            if (!r.matchPackage(pkg)) continue;
            // Activity 限定
            if (!r.activityIds.isEmpty() && activity != null) {
                boolean hit = false;
                for (String a : r.activityIds) {
                    if (activity.contains(a)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) continue;
            }
            // Activity 排除
            boolean excluded = false;
            if (activity != null) {
                for (String a : r.excludeActivityIds) {
                    if (activity.contains(a)) {
                        excluded = true;
                        break;
                    }
                }
            }
            if (excluded) continue;

            for (SkipRule.Action a : r.rules) {
                if (execAction(root, a, pkg, r.name, now)) return true;
            }
        }
        return false;
    }

    /** 执行一个规则动作 */
    private boolean execAction(AccessibilityNodeInfo root, SkipRule.Action a,
                              String pkg, String ruleName, long now) {
        int delay = a.delayMs > 0 ? a.delayMs : AdSkipPrefs.delayMs(this);

        // back / swipe 这类无目标动作
        if (a.isBack()) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                markHit(pkg, "back ← " + ruleName, now);
                return true;
            }
            return false;
        }
        if (a.isSwipe()) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            if (swipe(w / 2, (int) (h * 0.75), w / 2, (int) (h * 0.25), delay)) {
                markHit(pkg, "swipe ← " + ruleName, now);
                return true;
            }
            return false;
        }

        // 按 viewId 找
        for (String vid : a.viewIds) {
            List<AccessibilityNodeInfo> ns = null;
            try {
                ns = root.findAccessibilityNodeInfosByViewId(
                        vid.contains(":id/") ? vid : pkg + ":id/" + vid);
            } catch (Exception ignored) {
            }
            if (ns == null) continue;
            for (AccessibilityNodeInfo n : ns) {
                if (clickNode(n, delay)) {
                    markHit(pkg, "id:" + vid + " ← " + ruleName, now);
                    return true;
                }
            }
        }

        // 按文本 / desc 找
        if (!a.matches.isEmpty()) {
            List<AccessibilityNodeInfo> found = new ArrayList<>();
            collectMulti(root, a.matches, found, 0);
            for (AccessibilityNodeInfo n : found) {
                if (clickNode(n, delay)) {
                    markHit(pkg, textOf(n) + " ← " + ruleName, now);
                    return true;
                }
            }
        }

        // 按归一化坐标点
        if (a.position != null && a.position.length >= 2) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            double x = a.position[0], y = a.position[1];
            int px = (int) (x <= 1 ? x * w : x);
            int py = (int) (y <= 1 ? y * h : y);
            if (tap(px, py, delay)) {
                markHit(pkg, "pos(" + px + "," + py + ") ← " + ruleName, now);
                return true;
            }
        }
        return false;
    }

    /** 点一个节点：先 ACTION_CLICK，失败再手势 */
    private boolean clickNode(AccessibilityNodeInfo n, int delay) {
        AccessibilityNodeInfo target = clickableAncestor(n);
        Rect r = new Rect();
        (target != null ? target : n).getBoundsInScreen(r);
        if (!plausible(r)) return false;

        boolean ok = false;
        if (target != null) {
            try {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {
            }
        }
        if (!ok) ok = tap(r.centerX(), r.centerY(), delay);
        return ok;
    }

    private void markHit(String pkg, String what, long now) {
        lastPkg = pkg;
        lastClickAt = now;
        AdSkipPrefs.addHit(this, pkg);
        Log.i(TAG, "规则命中: " + what + " @ " + pkg);
    }

    /** 按多个关键词收集节点 */
    private void collectMulti(AccessibilityNodeInfo node, List<String> words,
                             List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 16 || out.size() > 60) return;
        String t = textOf(node);
        if (!t.isEmpty()) {
            for (String w : words) {
                if (matchWord(t, java.util.Collections.singleton(w))) {
                    out.add(node);
                    break;
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                collectMulti(node.getChild(i), words, out, depth + 1);
            } catch (Exception ignored) {
            }
        }
    }

    /** 关键词兜底（规则没命中时） */
    private boolean tryKeywords(AccessibilityNodeInfo root, String pkg, long now) {
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
            // 纯符号（× ✕ 等）额外要求是小控件：广告的关闭叉很小，
            // 计算器乘号 / 播放器按钮 / 大号图标都远大于 52dp
            if (isCloseSymbol(textOf(n)) && !smallSymbolNode(r)) continue;

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

    /** 滑动手势 */
    private boolean swipe(int x1, int y1, int x2, int y2, int delayMs) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, Math.max(0, delayMs), 220));
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
    /**
     * 纯符号按钮（× ✕ ✖ 等），这类没有文字，必须单独识别。
     *
     * ★ 绝不含字母 x / X：计算器的乘号键、输入法的 x 键、正文里的小写 x
     *   都会命中，导致在计算器/键盘里乱点。只认真正的排版符号。
     */
    private static boolean isCloseSymbol(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 2) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ("×✕✖╳✗⨯❌❎".indexOf(c) < 0) return false;
        }
        return true;
    }

    /**
     * 工具类 App 一律不自动点击。
     * 计算器 / 输入法 / 设置 / 文件管理 / 相机 等界面上的 × 和数字键
     * 不是广告，点了是纯粹的破坏。
     */
    private static boolean isToolApp(String pkg, String activity) {
        if (pkg == null) return false;
        String s = pkg.toLowerCase();
        String a = activity == null ? "" : activity.toLowerCase();

        // 计算器
        if (s.contains("calculator") || s.contains(".calc") || a.contains("calculator")) return true;
        // 输入法 / 键盘
        if (s.contains("inputmethod") || s.contains("keyboard") || s.contains("pinyin")
                || s.endsWith(".ime") || s.contains(".ime.") || a.contains("inputmethod")) return true;
        // 系统工具
        if (s.contains("settings") || s.contains("filemanager") || s.contains("deskclock")
                || s.contains("calendar") || s.contains("contacts") || s.contains("mms")
                || s.contains("camera") || s.contains("gallery") || s.contains("soundrecorder")) return true;
        return false;
    }

    /** 纯符号按钮必须是"小控件"，排除大号 UI 按键（计算器数字/乘号普遍 ≥ 60dp） */
    private boolean smallSymbolNode(Rect r) {
        if (r.width() <= 0 || r.height() <= 0) return false;
        float d = getResources().getDisplayMetrics().density;
        int maxPx = (int) (52 * d);   // 52dp 以内才算"关闭小叉"
        return r.width() <= maxPx && r.height() <= maxPx;
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
    public void onDestroy() {
        if (shakeGuard != null) shakeGuard.stop();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // 系统回收无障碍时的回调，无需处理
    }
}
