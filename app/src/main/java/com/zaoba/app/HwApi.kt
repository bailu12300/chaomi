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
    data class CrawlStatus(val running: Boolean, val done: Int, val total: Int, val current: String, val lastDoneAt: Long)

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
                current = d.optString("current"),
                lastDoneAt = d.optLong("lastDoneAt")
            )
        }
    }

    fun fmtTime(ts: Long): String {
        if (ts <= 0) return ""
        val d = java.util.Date(ts)
        val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return f.format(d)
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

    data class Article(
        val id: String, val title: String, val category: String, val authorName: String,
        val viewCount: Int, val createdAt: Long, val tags: List<String>, val source: String
    )

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
                val tagsArr = o.optJSONArray("tags")
                val tags = if (tagsArr == null) emptyList() else (0 until tagsArr.length()).map { tagsArr.optString(it) }
                Article(
                    o.optString("id"), o.optString("title"), o.optString("category"),
                    o.optString("authorName"), o.optInt("viewCount"), o.optLong("createdAt"),
                    tags, o.optString("source", "local")
                )
            }
        }
    }

    /** 公众号文章导入（需登录） */
    suspend fun importWechat(token: String, url: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("url", url)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/api/article/import-wechat")
            .header("X-Session-Token", token)
            .post(body).build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    /** 文章详情（带 token 时返回 canEdit，用于显示编辑/删除入口） */
    suspend fun articleDetail(id: String, token: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url("$BASE_URL/api/article/detail?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
        if (!token.isNullOrBlank()) b.header("X-Session-Token", token)
        client.newCall(b.get().build()).execute().use { resp ->
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

    /** 编辑文章（仅作者本人/管理员） */
    suspend fun articleUpdate(
        token: String, id: String, title: String, content: String, category: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("content", content)
            .put("category", category)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/api/article/update")
            .header("X-Session-Token", token)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    /** 删除文章（仅作者本人/管理员，软删除） */
    suspend fun articleDelete(token: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id", id)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/api/article/delete")
            .header("X-Session-Token", token)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    /** 我的文章 */
    suspend fun articleMine(token: String, page: Int = 1): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/article/mine?page=$page&size=20")
            .header("X-Session-Token", token)
            .get().build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    // ---------- 评论 + 点赞 ----------

    data class Comment(val id: String, val content: String, val authorName: String, val createdAt: Long)

    /** 评论列表 */
    suspend fun articleComments(articleId: String): List<Comment> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/article/comments?articleId=${java.net.URLEncoder.encode(articleId, "UTF-8")}")
            .get().build()
        client.newCall(req).execute().use { resp ->
            val root = JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            val arr = root.optJSONObject("data")?.optJSONArray("rows") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Comment(o.optString("id"), o.optString("content"), o.optString("authorName"), o.optLong("createdAt"))
            }
        }
    }

    /** 发评论 */
    suspend fun addComment(token: String, articleId: String, content: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("articleId", articleId).put("content", content)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE_URL/api/article/comment")
                .header("X-Session-Token", token).post(body).build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }

    /** 点赞/取消赞 */
    suspend fun toggleLike(token: String, articleId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("articleId", articleId)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE_URL/api/article/like")
                .header("X-Session-Token", token).post(body).build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }

    /** 点赞数 */
    suspend fun likeInfo(articleId: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/api/article/like-info?articleId=${java.net.URLEncoder.encode(articleId, "UTF-8")}")
            .get().build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    // ---------- 用户资料 ----------

    /** 获取用户资料 */
    suspend fun getProfile(token: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE_URL/api/user/profile")
            .header("X-Session-Token", token).get().build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: throw Exception("空响应"))
        }
    }

    /** 更新用户资料 */
    suspend fun updateProfile(token: String, nickname: String?, avatar: String?, signature: String?, background: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
            if (nickname != null) body.put("nickname", nickname)
            if (avatar != null) body.put("avatar", avatar)
            if (signature != null) body.put("signature", signature)
            if (background != null) body.put("background", background)
            val req = Request.Builder().url("$BASE_URL/api/user/profile")
                .header("X-Session-Token", token)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }

    /** 上传图片（头像等） */
    suspend fun uploadImage(token: String, bytes: ByteArray, path: String): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE_URL/api/upload?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                .header("X-Session-Token", token)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }

    /** 上传视频（≤1分钟，≤250MB） */
    suspend fun uploadVideo(token: String, bytes: ByteArray, name: String): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$BASE_URL/api/upload/media?name=${java.net.URLEncoder.encode(name, "UTF-8")}")
                .header("X-Session-Token", token)
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string() ?: throw Exception("空响应"))
            }
        }
}
