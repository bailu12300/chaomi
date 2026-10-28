package com.chaomi.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class Utils {
    /** 相对 / 绝对时间 */
    static String formatTime(long ms) {
        if (ms <= 0) return "";
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000) return "刚刚";
        if (diff < 3_600_000) return (diff / 60_000) + " 分钟前";
        if (diff < 86_400_000) return (diff / 3_600_000) + " 小时前";
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return sdf.format(new Date(ms));
    }

    /**
     * 作者行下方的元信息：「广东 · 3 小时前」
     * 归属地缺失时只显示时间，不显示占位的「未知」，避免看起来像出错。
     */
    static String metaLine(long ms, String ipLoc) {
        String t = formatTime(ms);
        String loc = ipLoc == null ? "" : ipLoc.trim();
        if (loc.isEmpty()) return t;
        if (t.isEmpty()) return loc;
        return loc + " · " + t;
    }

    /** 把后端返回的相对路径拼成完整 URL */
    static String fullUrl(String u) {
        if (u == null) return null;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        return Api.BASE + u;
    }

    /** 价格对象（Double 或 null）格式化 */
    static String priceStr(Object price) {
        if (price == null) return "暂无报价";
        if (price instanceof Double) return String.format(Locale.CHINA, "¥%.0f", (Double) price);
        if (price instanceof Integer) return String.format(Locale.CHINA, "¥%d", (Integer) price);
        return "¥" + price;
    }
}
