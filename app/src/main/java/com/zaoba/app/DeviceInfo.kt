package com.zaoba.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * 设备信息采集（注册时上报，用于账号安全/环境监测）。
 * 不采集敏感信息（IMEI/序列号，Android 10+ 已封禁）。
 */
object DeviceInfo {

    fun collect(ctx: Context): JSONObject {
        return JSONObject().apply {
            put("brand", Build.BRAND ?: "")
            put("model", Build.MODEL ?: "")
            put("os", "Android ${Build.VERSION.RELEASE}")
            put("sdk", Build.VERSION.SDK_INT.toString())
            put("abi", (Build.SUPPORTED_ABIS?.firstOrNull() ?: ""))
            put("app", "zaoba")
            put("ver", "1.0.2")
            put("id", androidId(ctx))
        }
    }

    /** Android ID（设备唯一标识，非敏感） */
    private fun androidId(ctx: Context): String {
        return try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
