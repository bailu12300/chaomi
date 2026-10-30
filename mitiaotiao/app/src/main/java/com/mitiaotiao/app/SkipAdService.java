package com.mitiaotiao.app;

import android.content.Intent;

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

    private static final String TAG = "MitiaotiaoSkipAd";
    private static final long DEBOUNCE_MS = 1200;
    /** 同一 App 内「内容变化」的最短扫描间隔，防 CPU 飙高 / ANR */
    private static final long CONTENT_SCAN_GAP_MS = 250;

    /**
     * 冷启动爆破扫描（学李跳跳的核心）。
     *
     * 为什么必须有：
     *   开屏广告只显示 3~5 秒，而且**按钮是延迟渲染的**——
     *   应用刚启动时第一帧根本没有「跳过」节点，靠"事件来了扫一次"几乎必然扫空，
     *   等下一个事件再来时广告已经播完或已自动跳走。
     *   李跳跳的做法是：检测到应用切换后，在一个短窗口内**高频重复扫描**，
     *   直到点掉按钮或窗口结束。
     */
    private static final long BURST_DURATION_MS = 5000;  // 持续扫 5 秒（覆盖开屏时长）
    private static final long BURST_INTERVAL_MS = 120;   // 每 120ms 扫一次

    /** 同一次开屏里，同一个目标只点一次，避免反复点击导致误触 */
    private static final long SAME_TARGET_COOLDOWN_MS = 1500;

    private String lastPkg = "";
    private String lastScanPkg = "";
    private long lastScanAt = 0;
    private long lastClickAt = 0;
    /** 当前窗口的 Activity 类名，供规则的 activityIds 匹配用 */
    private String lastActivity = null;

    /** 爆破扫描状态 */
    private final android.os.Handler burstHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable burstTask;
    private long burstUntil = 0;
    private String burstPkg = "";

    /** 最近点击目标指纹，防同一按钮重复点 */
    private String lastTargetKey = "";
    private long lastTargetAt = 0;

    /** 摇一摇守视：检测到摇动后立刻找「取消/关闭」把跳转掐断 */
    private ShakeGuard shakeGuard;
    /** 摇动后多久内积极扫描（毫秒），来自服务端配置 */
    private long shakeWindowMs = 6000;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        resubscribeEvents();
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

        // 无障碍服务被系统重建，说明进程刚活过来 —— 这是拉起守护服务的最佳时机
        // （此刻系统正绑着我们，属于前台服务后台启动的豁免场景，成功率最高）
        AdSkipPrefs.sp(this).edit()
                .putLong("acc_beat", System.currentTimeMillis()).apply();
        if (AdSkipPrefs.sp(this).getBoolean("keepalive", true)) {
            KeepAliveService.startCompat(this);
            KeepAliveScheduler.schedule(this);
        }
    }


    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // ★ 整个事件处理必须包 try/catch。
        //   AccessibilityService 里抛出的任何未捕获异常，系统会直接把这个服务停掉
        //   —— 表现就是"用着用着突然不跳广告了，还得手动去设置里重开"。
        //   之前这里没有保护，扫描逻辑里任何一个 NPE 都能把服务干掉。
        try {
            handleEvent(event);
        } catch (Throwable t) {
            Log.w(TAG, "处理事件异常（已吞掉，服务继续运行）: " + t);
        }
    }

    /** 真正的处理逻辑（由 onAccessibilityEvent 包一层异常保护后调用） */
    private void handleEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!AdSkipPrefs.enabled(this)) return;

        // 记录活跃时间：主界面据此判断服务是否还活着
        AdSkipPrefs.sp(this).edit()
                .putLong("acc_beat", System.currentTimeMillis()).apply();

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
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

        // ★★ 关键：检测到「切到另一个 App」→ 立刻开爆破扫描。
        //    点击图标启动应用时，系统先发 WINDOW_STATE_CHANGED，此时界面还没渲染完，
        //    单次扫描必然扫空；必须在这之后持续扫几百毫秒才能抓到延迟出现的跳过按钮。
        boolean switched = !pkg.equals(burstPkg);
        boolean newWindow = (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED);
        if (newWindow && !pkg.equals(lastPkg)) {
            // 窗口增删时包名可能还是旧的前台包，这里用当前事件包名兜底
            switched = true;
        }
        if (switched && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || newWindow)) {
            startBurst(pkg);
        }

        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastClickAt < DEBOUNCE_MS) return;

        // ★ 同一个 App 内的「内容变化」事件极其频繁（列表滚动、动画、输入框光标
        //   都会触发）。每次都去遍历整棵节点树 + 所有窗口，会造成 CPU 飙升、
        //   耗电、甚至 ANR，而 ANR 又会被系统判定为服务异常。
        //   这里限制：同一包名的内容变化，最短 250ms 才扫一次。
        //   （窗口切换 / 新窗口不计入节流，因为它们才是广告最常见的前兆）
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && pkg.equals(lastScanPkg)
                && now - lastScanAt < CONTENT_SCAN_GAP_MS) {
            return;
        }
        lastScanPkg = pkg;
        lastScanAt = now;

        // 扫一轮（成功会 return）
        if (scanOnce(pkg, now)) return;

        // 没扫到且有窗口切换 → 确保爆破扫描在跑（覆盖按钮延迟渲染）
        if (switched) startBurst(pkg);
    }

    /** 扫一轮：活动窗口 → 所有窗口 */
    private boolean scanOnce(String pkg, long now) {
        // 1) 当前活动窗口
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (tryClick(root, pkg, now)) return true;
        }
        // 2) 遍历所有窗口（开屏广告常在独立 overlay 窗口里）
        List<AccessibilityWindowInfo> wins = getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                if (r.equals(root)) continue;
                if (tryClick(r, pkg, now)) return true;
            }
        }
        return false;
    }

    /**
     * 爆破扫描：在 BURST_DURATION_MS 内每 BURST_INTERVAL_MS 扫一次。
     *
     * 每轮只做「扫描 + 点击」，不做重量级操作，避免拖慢目标应用。
     * 点中就停（广告已关，继续扫没有意义，还会误点后续界面）。
     */
    private void startBurst(final String pkg) {
        burstPkg = pkg;
        burstUntil = System.currentTimeMillis() + BURST_DURATION_MS;
        if (burstTask != null) {
            burstHandler.removeCallbacks(burstTask);
        }
        burstTask = new Runnable() {
            @Override
            public void run() {
                if (!AdSkipPrefs.enabled(SkipAdService.this)) return;
                if (System.currentTimeMillis() > burstUntil) return;
                // 目标已不是这个 App（用户又切走了）→ 停
                String cur = lastPkg != null ? lastPkg : "";
                if (!cur.isEmpty() && !cur.equals(pkg) && !cur.isEmpty()) {
                    // lastPkg 在点中时才更新，这里不能用它判断当前前台，
                    // 所以只靠 burstUntil 兜底即可。
                }
                try {
                    boolean hit = scanOnce(pkg, System.currentTimeMillis());
                    if (hit) {
                        Log.i(TAG, "爆破扫描命中，停止");
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "burst scan err: " + t);
                }
                burstHandler.postDelayed(this, BURST_INTERVAL_MS);
            }
        };
        burstHandler.postDelayed(burstTask, BURST_INTERVAL_MS);
        Log.i(TAG, "启动爆破扫描 @" + pkg);
    }

    private void stopBurst() {
        if (burstTask != null) {
            burstHandler.removeCallbacks(burstTask);
            burstTask = null;
        }
        burstUntil = 0;
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
    /**
     * 摇一摇善后 —— **只找「跳过」类按钮**。
     *
     * 以前会去点「取消 / 关闭 / ×」，但这些词在正常界面里到处都是：
     * 用户晃一下手机，可能把正在填的表单、正在看的对话框直接关掉。
     * 现在只认「跳过」，因为摇一摇广告的关闭入口通常也是「跳过」。
     *
     * 如果广告只有 × 没有「跳过」，那就不动——宁可让它跳转一次，
     * 也不要在用户自己的界面上乱点。
     */
    private boolean tryAntiShake(AccessibilityNodeInfo root, String pkg) {
        if (root == null) return false;
        long now = System.currentTimeMillis();

        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectMulti(root, java.util.Arrays.asList("跳过", "跳過", "skip", "skip ad"), found, 0);
        for (AccessibilityNodeInfo n : found) {
            if (clickNode(n, 0)) {
                markHit(pkg, "摇一摇善后:" + textOf(n), now);
                AdSkipPrefs.addShakeStop(this);
                return true;
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
                // ★ 导入的第三方规则（李跳跳/GKD）里常带 × 选择器，
                //   这类通用符号一律不执行——哪怕用户导入了这样的规则。
                String txt = textOf(n);
                if (isCloseSymbol(txt)) {
                    Log.i(TAG, "跳过规则中的 × 类目标（不安全）: " + txt);
                    continue;
                }
                if (clickNode(n, delay)) {
                    markHit(pkg, txt + " ← " + ruleName, now);
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
        if (n == null) return false;
        // 节点必须可见且未失效 —— 爆破扫描会连续跑几十轮，
        // 不加这层会把已被销毁的旧节点当成目标，白白点击
        try {
            if (!n.isVisibleToUser()) return false;
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo target = clickableAncestor(n);
        Rect r = new Rect();
        (target != null ? target : n).getBoundsInScreen(r);
        if (!plausible(r)) return false;

        // 同一目标冷却：防止同一次开屏里反复点同一个按钮
        String key = textOf(n) + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom;
        long now = System.currentTimeMillis();
        if (key.equals(lastTargetKey) && now - lastTargetAt < SAME_TARGET_COOLDOWN_MS) {
            return false;
        }

        boolean ok = false;
        if (target != null) {
            try {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Exception ignored) {
            }
        }
        if (!ok) ok = tap(r.centerX(), r.centerY(), delay);
        if (ok) {
            lastTargetKey = key;
            lastTargetAt = now;
        }
        return ok;
    }

    private void markHit(String pkg, String what, long now) {
        lastPkg = pkg;
        lastClickAt = now;
        AdSkipPrefs.addHit(this, pkg);
        Log.i(TAG, "规则命中: " + what + " @ " + pkg);
    }

    /**
     * 按多个关键词收集节点（摇一摇善后用）。
     *
     * 这里做**精确短语匹配**，不走 matchWord 的分级逻辑：
     * 善后阶段已经过 looksLikeAdScreen 校验，只需按给定词表找按钮即可。
     */
    private void collectMulti(AccessibilityNodeInfo node, List<String> words,
                             List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 16 || out.size() > 60) return;
        String t = textOf(node);
        if (!t.isEmpty()) {
            String compact = t.replaceAll("\\s+", "");
            for (String w : words) {
                if (w == null || w.isEmpty()) continue;
                if (compact.equals(w) || (compact.length() <= w.length() + 4 && compact.contains(w))) {
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

        // 先判定这屏像不像广告：决定「×」「关闭」这类通用按钮能不能点
        final boolean adContext = looksLikeAdScreen(root);

        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collect(root, words, found, 0, adContext);
        if (found.isEmpty()) return false;

        int delay = AdSkipPrefs.delayMs(this);
        final String targetPkg = pkg;
        final long clickAt = now;

        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo target = clickableAncestor(n);
            Rect r = new Rect();
            (target != null ? target : n).getBoundsInScreen(r);
            if (!plausible(r)) continue;
            // × 系列已完全不再作为点击目标（matchWord 不会返回它们），
            // 这里再兜一层，防止规则库或其它路径把 × 塞进来
            if (isCloseSymbol(textOf(n))) continue;

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
                         List<AccessibilityNodeInfo> out, int depth, boolean adContext) {
        if (node == null || depth > 16 || out.size() > 60) return;

        String t = textOf(node);
        if (!t.isEmpty() && matchWord(t, words, adContext)) {
            out.add(node);
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                collect(node.getChild(i), words, out, depth + 1, adContext);
            } catch (Exception ignored) {
            }
        }
    }

    // ===== 广告上下文判定 =====
    //
    // 目的：只有"这一屏确实像广告"时，才允许点「×」和「关闭」这类通用按钮。
    // 判定方式：扫描界面文本，找广告特有词 + 看是否有倒计时跳过按钮。
    //
    // 为什么不用"包名 + 时间窗"做主判据：
    //   用户在应用里点开广告弹窗时，包名并没有切换，时间窗会漏判；
    //   而文本特征在任何场景下都成立，更可靠。

    /** 广告特征词（出现任意一个就认为这屏可能是广告） */
    private static final String[] AD_HINTS = {
            "广告", "廣告", "赞助", "贊助", "推广", "推廣", "advertisement",
            "sponsored", "跳过", "跳過", "skip ad", "关闭广告", "關閉廣告",
            "了解更多", "了解詳情", "点击查看", "點擊查看", "立即下载", "立即下載",
            "免费下载", "免費下載", "安装应用", "安裝應用", "应用内打开",
            "摇一摇", "搖一搖", "扭一扭", "扭一扭跳转", "晃动手机",
    };

    /**
     * 扫描整屏文本，判断是否像广告界面。
     *
     * @return true 表示具备广告特征，可以放宽到点「×」「关闭」
     */
    private boolean looksLikeAdScreen(AccessibilityNodeInfo root) {
        if (root == null) return false;
        boolean[] found = new boolean[1];
        scanForAdHints(root, 0, found);
        return found[0];
    }

    private void scanForAdHints(AccessibilityNodeInfo node, int depth, boolean[] found) {
        if (node == null || depth > 16 || found[0]) return;

        String t = textOf(node);
        if (!t.isEmpty() && t.length() <= 40) {   // 只看短文本，长段落是正文
            String lower = t.toLowerCase();
            for (String h : AD_HINTS) {
                if (lower.contains(h)) {
                    found[0] = true;
                    return;
                }
            }
            // 独立的 "AD" 标记（避免误判 "load"/"head" 这类单词）
            if (lower.equals("ad") || lower.equals("ads") || lower.equals("广告")) {
                found[0] = true;
                return;
            }
        }

        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                scanForAdHints(node.getChild(i), depth + 1, found);
                if (found[0]) return;
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

    /**
     * 关键词匹配（分级，越往下越严）。
     *
     * ★ 教训：以前「纯符号 ×」和「含关闭字」都无条件命中，
     *   结果在普通界面里到处乱点——对话框的 ×、设置页的关闭、删除按钮……
     *   用户反馈「只要有 x 就会异常点击」，体验极差。
     *
     * 现在的规则：
     *   L1「跳过 / 跳过广告 / 跳过 3 / skip」→ 广告专属词，直接命中
     *   L2「关闭 / × / ✕」→ **只有**在检测到当前界面像广告时才算
     *   L3 其它通用词（不感兴趣/以后再说…）→ 同上，必须有广告上下文
     *
     * @param adContext 当前界面是否具备广告特征（由 looksLikeAdScreen 判定）
     */
    /**
     * 关键词匹配 —— **只认「跳过」类字眼**。
     *
     * ★ 为什么彻底放弃 × 和「关闭」：
     *   用户反馈「只要有 × 就会异常点击」。即使加了"广告上下文"判断，
     *   只要界面上出现一个「广告」字样（很多 App 的隐私政策、会员页都有），
     *   × 就又会被点。这类通用符号无法可靠区分"广告关闭"和"关掉当前页面"，
     *   点了就是破坏用户操作。
     *
     *   相比之下，「跳过」两个字几乎只出现在广告/开屏上，
     *   识别它既准确又安全——这就是李跳跳真正的核心思路。
     *
     * 认的形态：
     *   跳过 / 跳过广告 / 跳过 3 / 3s 跳过 / 5秒后跳过 / 跳過 / skip / skip ad
     * 不认（一律不点）：
     *   × ✕ ✖ 关闭 取消 我知道了 不感兴趣 …
     */
    private boolean matchWord(String s, Set<String> words, boolean adContext) {
        if (s == null || s.isEmpty()) return false;
        String raw = s.trim();
        if (raw.length() > 24) return false;      // 太长是正文，不是按钮

        String compact = raw.replaceAll("\\s+", "");
        String lower = compact.toLowerCase();

        // ① 核心：含「跳过」（简繁都认）
        if (compact.contains("跳过") || compact.contains("跳過")) return true;

        // ② 英文 skip / skip ad / skip ads
        if (lower.contains("skip")) {
            // 排除 "skipped"/"skipping" 这类正文里出现的过去式
            if (!lower.contains("skipped") && !lower.contains("skipping")) return true;
        }

        // ③ 极少数 App 把跳过做成符号：» › ⏭（不含 × 系列）
        if (isSkipSymbol(raw)) return true;

        return false;
    }

    /**
     * 目标是否紧邻输入框，或本身就是危险操作按钮。
     *
     * 用途：拦掉"表单里的 × 清空按钮""搜索框里的 ×""删除按钮"这类误点。
     * 判断依据：节点自身是 EditText / 父级同层有 EditText / 文案含删除类词。
     */
    private boolean nearInputOrDanger(AccessibilityNodeInfo n) {
        try {
            // 自身或祖先链上出现输入框 → 这是表单的清空/关闭按钮，绝不点
            AccessibilityNodeInfo cur = n;
            int guard = 0;
            while (cur != null && guard++ < 6) {
                if (cur.isEditable()) return true;
                CharSequence cn = cur.getClassName();
                if (cn != null) {
                    String cls = cn.toString();
                    if (cls.contains("EditText") || cls.contains("AutoCompleteTextView")
                            || cls.contains("SearchView")) {
                        return true;
                    }
                }
                // 往上走一层，看兄弟节点里有没有输入框
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent != null) {
                    int cnt = parent.getChildCount();
                    for (int i = 0; i < cnt; i++) {
                        AccessibilityNodeInfo sib = null;
                        try {
                            sib = parent.getChild(i);
                        } catch (Exception ignored) {
                        }
                        if (sib == null) continue;
                        if (sib.isEditable()) return true;
                        CharSequence scn = sib.getClassName();
                        if (scn != null && scn.toString().contains("EditText")) return true;
                    }
                }
                cur = parent;
            }

            // 危险词：出现在附近说明这是删除/提交类按钮
            String t = textOf(n).toLowerCase();
            String[] dangers = {"删除", "刪除", "清空", "清除", "移除", "撤销", "撤銷",
                    "退出", "登出", "注销", "註銷", "关闭窗口", "關閉視窗", "取消订单"};
            for (String d : dangers) {
                if (t.contains(d)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 跳过类符号（≈ ≠ 不算，只认真正表示"跳过/下一段"的） */
    private boolean isSkipSymbol(String s) {
        if (s == null) return false;
        String t = s.replaceAll("\\s+", "");
        if (t.isEmpty() || t.length() > 2) return false;
        char c = t.charAt(0);
        return c == '»' || c == '›' || c == '⏭';
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
        stopBurst();
        if (shakeGuard != null) shakeGuard.stop();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // 系统回收无障碍时的回调。
        // 必须清理干净：否则已排队的爆破扫描还会继续跑完，
        // 去点一个此刻不该点的界面（用户可能已经切走了）。
        Log.w(TAG, "无障碍服务被中断");
        try {
            if (burstTask != null) burstHandler.removeCallbacks(burstTask);
            burstTask = null;
            burstPkg = "";
            burstUntil = 0;
            if (shakeGuard != null) shakeGuard.stop();
            lastPkg = "";
            lastScanPkg = "";
            lastScanAt = 0;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 服务被系统解绑（用户关掉开关、或被系统/ROM 清理）。
     *
     * 这里标记一个「需要重新开启」的标志，常驻服务的心跳会读到它并
     * 把通知文案改成「无障碍已失效，点这里重新开启」——
     * 否则用户完全不知道服务已经死了，只会觉得"跳过广告不灵了"。
     */
    @Override
    public boolean onUnbind(Intent intent) {
        try {
            AdSkipPrefs.sp(this).edit()
                    .putBoolean("need_reauth", true)
                    .putLong("acc_off_at", System.currentTimeMillis())
                    .putLong("acc_beat", 0)
                    .apply();
        } catch (Throwable ignored) {
        }
        Log.w(TAG, "无障碍服务被解绑");
        return super.onUnbind(intent);
    }

    /**
     * 重新订阅事件。
     *
     * 为什么需要：部分 ROM（尤其国产定制）会在息屏/内存紧张后
     * 把事件的订阅丢掉，服务对象还在、开关还显示"已开启"，
     * 但再也收不到任何 event —— 表现为"看着开了其实不工作"。
     * 这里在每次连接时强制重设一遍 serviceInfo，并把订阅类型写全。
     */
    private void resubscribeEvents() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    // WebView 里渲染的广告（部分 App 用 H5 做开屏）需要这个才拿得到节点
                    | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
            // ★ 120ms 而不是 50ms：前者已经足够快到抓住 3 秒的开屏，
            //   但能把高频的 contentChanged 回调压力砍掉一半以上，避免 ANR。
            info.notificationTimeout = 120;
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.w(TAG, "resubscribe 失败: " + t);
        }
    }
}
