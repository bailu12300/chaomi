package com.mitiaotiao.app;

import android.content.Context;

import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则库同步：从服务器拉取在线规则，合并进本地。
 *
 * 联动逻辑：
 *   服务端 /api/adrules 下发规则 + 摇一摇参数
 *   → 客户端合并规则（保留用户启停）
 *   → 同步 shake.threshold / shake.windowMs 给 ShakeGuard
 */
class RuleSync {

    private static final String P = "mt_rulesync";
    private static final String K_VER = "version";
    private static final String K_AT = "lastAt";
    private static final String K_SHAKE_ON = "shakeOn";
    private static final String K_SHAKE_WIN = "shakeWin";
    private static final String K_SHAKE_TH = "shakeTh";

    private static final Gson GSON = new Gson();

    static class Result {
        int version;
        int count;
        boolean changed;
        boolean shakeEnabled = true;
        long shakeWindowMs = 6000;
        float shakeThreshold = 2.6f;
        String message = "";
    }

    /** 拉取并合并。force=true 时忽略版本号强制重拉 */
    static Result sync(Context c, boolean force) {
        Result res = new Result();
        int localVer = pref(c).getInt(K_VER, 0);

        String url = Api.BASE + "/api/adrules?version=" + (force ? 0 : localVer);
        String json;
        try {
            json = Http.get(url, null);
        } catch (Exception e) {
            res.message = "网络失败：" + e.getMessage();
            return res;
        }

        Map<String, Object> root;
        try {
            root = GSON.fromJson(json, Map.class);
        } catch (Exception e) {
            res.message = "响应解析失败";
            return res;
        }
        if (root == null || root.get("data") == null) {
            res.message = "空响应";
            return res;
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof Map)) {
            res.message = "响应格式不对";
            return res;
        }
        Map<String, Object> d = (Map<String, Object>) dataObj;

        res.version = (int) num(d.get("version"), 0);
        res.count = (int) num(d.get("count"), 0);
        Object changedObj = d.get("changed");
        res.changed = changedObj instanceof Boolean && (Boolean) changedObj;

        // 摇一摇配置
        Object sh = d.get("shake");
        if (sh instanceof Map) {
            Map<String, Object> sm = (Map<String, Object>) sh;
            Object on = sm.get("enabled");
            res.shakeEnabled = !(on instanceof Boolean) || (Boolean) on;
            res.shakeWindowMs = (long) num(sm.get("windowMs"), 6000);
            Object th = sm.get("threshold");
            if (th instanceof Number) res.shakeThreshold = ((Number) th).floatValue();
        }
        saveShake(c, res.shakeEnabled, res.shakeWindowMs, res.shakeThreshold);

        if (!res.changed && localVer == res.version) {
            res.message = "已是最新（v" + res.version + "）";
            pref(c).edit().putLong(K_AT, System.currentTimeMillis()).apply();
            return res;
        }

        // 合并规则
        Object rulesObj = d.get("rules");
        int n = 0;
        if (rulesObj != null) {
            String rulesJson = GSON.toJson(rulesObj);

            // ★ 先按服务端下发的 id 白名单清掉「已下线」的旧规则。
            //   之前只做增量导入：服务端删了规则，客户端却一直留着，
            //   于是旧的「×/关闭/取消」规则永远在用户手机上继续乱点。
            java.util.Set<String> keep = new java.util.HashSet<>();
            try {
                java.util.List<Map<String, Object>> arr = GSON.fromJson(
                        rulesJson, new com.google.gson.reflect.TypeToken<
                                java.util.List<Map<String, Object>>>() {}.getType());
                if (arr != null) {
                    for (Map<String, Object> m : arr) {
                        Object id = m.get("id");
                        if (id != null) keep.add(String.valueOf(id));
                    }
                }
            } catch (Exception ignored) {
            }
            if (!keep.isEmpty()) {
                RuleStore.dropServerRulesNotIn(c, keep);
            }

            try {
                n = RuleStore.importJson(c, rulesJson, "url");
            } catch (Exception e) {
                res.message = "规则解析失败：" + e.getMessage();
                return res;
            }
        }
        pref(c).edit()
                .putInt(K_VER, res.version)
                .putLong(K_AT, System.currentTimeMillis())
                .apply();
        res.changed = true;
        res.message = "同步 " + n + " 条规则（v" + res.version + "）";
        return res;
    }

    private static android.content.SharedPreferences pref(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    private static void saveShake(Context c, boolean on, long win, float th) {
        pref(c).edit()
                .putBoolean(K_SHAKE_ON, on)
                .putLong(K_SHAKE_WIN, win)
                .putFloat(K_SHAKE_TH, th)
                .apply();
    }

    static boolean shakeEnabled(Context c) {
        return pref(c).getBoolean(K_SHAKE_ON, true);
    }

    static long shakeWindow(Context c) {
        return pref(c).getLong(K_SHAKE_WIN, 6000);
    }

    static float shakeThreshold(Context c) {
        return pref(c).getFloat(K_SHAKE_TH, 2.6f);
    }

    static int localVersion(Context c) {
        return pref(c).getInt(K_VER, 0);
    }

    static String lastSyncText(Context c) {
        long at = pref(c).getLong(K_AT, 0);
        if (at <= 0) return "未同步";
        return "v" + localVersion(c) + " · " + Utils.formatTime(at);
    }

    /** 上报规则命中，服务端据此优化 */
    static void reportHit(String ruleId, String pkg) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("ruleId", ruleId);
        b.put("pkg", pkg);
        try {
            Http.postJson(Api.BASE + "/api/adrules/report",
                    GSON.toJson(b), null);
        } catch (Exception ignored) {
        }
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
