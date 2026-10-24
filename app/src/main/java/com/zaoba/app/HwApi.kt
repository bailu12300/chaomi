package com.zaoba.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

    // ---------- 账号体系 ----------

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            val s = resp.body?.string() ?: throw Exception("空响应")
            JSONObject(s)
        }
    }

    /** 发送邮箱验证码 */
    suspend fun sendCode(email: String): JSONObject = withContext(Dispatchers.IO) {
        post("/api/auth/send-code", JSONObject().put("email", email))
    }

    /** 注册（邮箱验证码） */
    suspend fun register(username: String, password: String, email: String, code: String, nickname: String, device: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            post("/api/auth/register", JSONObject()
                .put("username", username)
                .put("password", password)
                .put("email", email)
                .put("code", code)
                .put("nickname", nickname)
                .put("device", device))
        }

    /** 账号密码登录 */
    suspend fun login(username: String, password: String): JSONObject = withContext(Dispatchers.IO) {
        post("/api/auth/password", JSONObject()
            .put("username", username)
            .put("password", password))
    }

    // ---------- 文章体系 ----------

    data class Article(val id: String, val title: String, val category: String, val authorName: String, val viewCount: Int, val createdAt: Long)

    /** 文章列表 */
    suspend fun articleList(page: Int = 1, size: Int = 20): List<Article> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/article/list?page=$page&size=$size")
            .get().build()
        client.newCall(req).execute().use { resp ->
            val root = JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            if (root.optInt("code") != 0) throw Exception(root.optString("msg"))
            val arr = root.optJSONObject("data")?.optJSONArray("rows") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Article(
                    o.optString("id"), o.optString("title"), o.optString("category"),
                    o.optString("authorName"), o.optInt("viewCount"), o.optLong("createdAt")
                )
            }
        }
    }

    /** 文章详情 */
    suspend fun articleDetail(id: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/article/detail?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
            .get().build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    /** 发布文章（需登录 token） */
    suspend fun articleCreate(token: String, title: String, content: String, category: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("title", title)
                .put("content", content)
                .put("category", category)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/api/article/create")
                .header("X-Session-Token", token)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }
}
