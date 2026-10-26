package com.zaoba.community;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 登录会话：token + 用户资料，落在 SharedPreferences。
 * 所有 API 请求通过 Api 自动带上 Authorization 头。
 */
class Session {

    private static final String PREF = "zaoba";
    private static final String K_TOKEN = "token";
    private static final String K_NICK = "nick";
    private static final String K_EMAIL = "email";
    private static final String K_UID = "uid";
    private static final String K_BIO = "bio";

    static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return pref(c).getString(K_TOKEN, "");
    }

    static boolean loggedIn(Context c) {
        String t = token(c);
        return t != null && !t.isEmpty();
    }

    static long uid(Context c) {
        return pref(c).getLong(K_UID, 0);
    }

    /** 展示用昵称：已登录用账号昵称，否则「匿名」 */
    static String nick(Context c) {
        if (loggedIn(c)) {
            String n = pref(c).getString(K_NICK, "");
            if (n != null && !n.isEmpty()) return n;
        }
        return "匿名";
    }

    static String email(Context c) {
        return pref(c).getString(K_EMAIL, "");
    }

    static void save(Context c, String token, User u) {
        SharedPreferences.Editor e = pref(c).edit();
        e.putString(K_TOKEN, token == null ? "" : token);
        if (u != null) {
            e.putLong(K_UID, u.id);
            e.putString(K_NICK, u.nick == null ? "" : u.nick);
            e.putString(K_EMAIL, u.email == null ? "" : u.email);
            e.putString(K_BIO, u.bio == null ? "" : u.bio);
        }
        e.apply();
        Api.setToken(token);
    }

    static void saveUser(Context c, User u) {
        if (u == null) return;
        pref(c).edit()
                .putLong(K_UID, u.id)
                .putString(K_NICK, u.nick == null ? "" : u.nick)
                .putString(K_EMAIL, u.email == null ? "" : u.email)
                .putString(K_BIO, u.bio == null ? "" : u.bio)
                .apply();
    }

    static void clear(Context c) {
        pref(c).edit()
                .remove(K_TOKEN).remove(K_UID).remove(K_NICK)
                .remove(K_EMAIL).remove(K_BIO).apply();
        Api.setToken("");
    }

    /** 从磁盘恢复 token（App 启动时调一次） */
    static void restore(Context c) {
        Api.setToken(token(c));
    }
}
