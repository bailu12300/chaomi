package com.zaoba.app

import android.content.Context
import org.json.JSONObject

/**
 * 登录态持久化（SharedPreferences）。
 * token、openid、username、nickname、email 登录后保存，重启恢复。
 */
object Session {
    private const val SP = "zaoba_session"

    data class User(
        val token: String,
        val openid: String,
        val username: String,
        val nickname: String,
        val email: String
    ) {
        val isLoggedIn: Boolean get() = token.isNotBlank()
    }

    fun save(ctx: Context, resp: JSONObject) {
        val token = resp.optString("token", "")
        val openid = resp.optString("openid", "")
        val username = resp.optString("username", "")
        val nickname = resp.optString("nickname", "")
        val email = resp.optString("email", "")
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString("token", token)
            .putString("openid", openid)
            .putString("username", username)
            .putString("nickname", nickname)
            .putString("email", email)
            .apply()
    }

    fun load(ctx: Context): User {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return User(
            token = sp.getString("token", "") ?: "",
            openid = sp.getString("openid", "") ?: "",
            username = sp.getString("username", "") ?: "",
            nickname = sp.getString("nickname", "") ?: "",
            email = sp.getString("email", "") ?: ""
        )
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
