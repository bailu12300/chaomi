package com.zaoba.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 硬件行情采集器（App 端，手机 IP）。
 *
 * 架构：App 用手机 IP + 移动 UA 请求慢慢买比价接口，拿到 HTML 原文，
 *      直接把 {keyword, text} 上报后端，由服务器解析入库。
 *      App 端不做解析（慢慢买是 Next.js，DOM 结构随发版变化，解析放服务器更易迭代）。
 *
 * 关键约束（用户要求，写死不可改）：
 *   · 3 分钟/型号（THROTTLE_MS）
 *   · 12 小时一轮
 *   · 断点续传：本地记录进度，被杀后台后重启继续
 */
object Crawler {
    private const val BASE = "https://zaoba.zy1080.xyz"
    // 采集密钥从 local.properties 注入（不硬编码，避免公开仓库泄露）
    private val CRAWLER_KEY: String = BuildConfig.CRAWLER_KEY
    private const val MM_SEARCH = "https://s.manmanbuy.com/pc/search/result"

    /** 节流：3 分钟/型号（写死） */
    const val THROTTLE_MS = 3 * 60 * 1000L

    /** 移动端 UA（让慢慢买返回 H5 结构） */
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 拉取型号清单（含 keyword） */
    suspend fun fetchModels(): List<HwModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/api/hw/models").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw Exception("空响应")
            val root = JSONObject(body)
            if (root.optInt("code") != 0) throw Exception(root.optString("msg"))
            val arr = root.optJSONObject("data")?.optJSONArray("models") ?: throw Exception("无型号")
            (0 until arr.length()).map { HwModel.fromJson(arr.getJSONObject(it)) }
        }
    }

    /** 用手机 IP 抓慢慢买某型号的搜索页 HTML */
    suspend fun fetchManmanbuy(keyword: String): String = withContext(Dispatchers.IO) {
        val url = "$MM_SEARCH?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}&c=search"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", "https://s.manmanbuy.com/")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    /** 上报采集结果（服务器解析） */
    suspend fun ingest(model: HwModel, text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        val body = JSONObject()
            .put("modelKey", model.key)
            .put("keyword", model.keyword)
            .put("source", "manmanbuy")
            .put("text", text)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE/api/hw/ingest")
            .header("X-Crawler-Key", CRAWLER_KEY)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val r = JSONObject(resp.body?.string() ?: "")
            r.optInt("code") == 0
        }
    }

    /** 上报进度 */
    suspend fun reportProgress(running: Boolean, done: Int, total: Int, current: String, finished: Boolean) {
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("running", running)
                .put("done", done)
                .put("total", total)
                .put("current", current)
                .put("finished", finished)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/api/hw/progress")
                .header("X-Crawler-Key", CRAWLER_KEY)
                .post(body)
                .build()
            runCatching { client.newCall(req).execute().close() }
        }
    }

    // ---------- 断点续传：本地进度持久化 ----------

    private const val SP_NAME = "crawler_progress"
    private const val KEY_DONE_INDEX = "done_index"
    private const val KEY_LAST_ROUND = "last_round_at"

    fun getDoneIndex(ctx: Context): Int {
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getInt(KEY_DONE_INDEX, 0)
    }

    fun setDoneIndex(ctx: Context, index: Int) {
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_DONE_INDEX, index).apply()
    }

    fun getLastRoundAt(ctx: Context): Long {
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_ROUND, 0)
    }

    fun setLastRoundAt(ctx: Context, ts: Long) {
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_ROUND, ts).apply()
    }
}
