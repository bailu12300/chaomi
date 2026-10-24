package com.zaoba.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后端数据访问层。数据来自早八后端（公开行情接口）。
 */
object HwApi {
    // 后端地址：早八行情接口（公开数据）
    private const val BASE_URL = "https://zaoba.zy1080.xyz"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉取硬件行情列表（含价格） */
    suspend fun fetchModels(): List<HwModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/hw/models")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw Exception("空响应")
            val root = JSONObject(body)
            if (root.optInt("code") != 0) throw Exception(root.optString("msg", "接口错误"))
            val arr = root.optJSONObject("data")?.optJSONArray("models") ?: throw Exception("无数据")
            (0 until arr.length()).map { HwModel.fromJson(arr.getJSONObject(it)) }
        }
    }

    /** 采集状态 */
    data class CrawlStatus(val running: Boolean, val done: Int, val total: Int, val current: String)

    suspend fun fetchStatus(): CrawlStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/hw/status")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw Exception("空响应")
            val root = JSONObject(body)
            val d = root.optJSONObject("data") ?: JSONObject()
            CrawlStatus(
                running = d.optBoolean("running"),
                done = d.optInt("done"),
                total = d.optInt("total"),
                current = d.optString("current")
            )
        }
    }
}
