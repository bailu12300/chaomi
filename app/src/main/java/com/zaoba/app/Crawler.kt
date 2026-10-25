package com.zaoba.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 硬件行情采集器（App 端，手机 IP）。
 *
 * 架构：App 用手机 IP + 移动 UA 请求慢慢买比价接口，拿到 HTML 原文，
 *      直接把 {modelKey, keyword, text} 上报后端，由服务器解析入库。
 *      App 端不做解析（慢慢买是 Next.js，DOM 结构随发版变化，解析放服务器更易迭代）。
 *
 * 关键约束（用户要求，写死不可改）：
 *   · 100 秒/型号（THROTTLE_MS）—— 重试同样计入，不得绕过
 *   · 12 小时一轮（自然周期，服务端约束）
 *   · 断点续传：本地记录进度，被杀后台后重启继续
 *
 * 本次增量（P0-AP-01 / P0-AP-02）：
 *   · 关键词轮询：`kwHit` 命中即锁定；未命中 `kwAttempt++` 换下一个候选词（持久化，杀进程不丢）
 *   · 结果分类：adopted / noMatch / parseEmpty / 网络失败 四类，重试节奏各不相同
 */
object Crawler {
    private const val BASE = "https://zaoba.zy1080.xyz"
    // 采集密钥从 local.properties 注入（不硬编码，避免公开仓库泄露）
    private val CRAWLER_KEY: String = BuildConfig.CRAWLER_KEY
    private const val MM_SEARCH = "https://s.manmanbuy.com/pc/search/result"

    /** 节流：100 秒/型号（用户定，写死）。43 型号一轮约 72 分钟。 */
    const val THROTTLE_MS = 100 * 1000L

    /** 命中词的锁定时长：7 天后重新放开轮询（防数据源变化后把型号锁死在旧词上） */
    private const val KW_HIT_TTL_MS = 7L * 24 * 3600 * 1000

    /** 移动端 UA（让慢慢买返回 H5 结构） */
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 拉取型号清单（含 keyword / keywords / nextDueAt …） */
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

    /**
     * 上报采集结果（服务器解析）。
     *
     * ★ X-Client-App 头：服务端靠它识别「这是手机 App」，本机脚本/浏览器一律 403。
     *   业务铁律：只有手机 App 用手机 IP 采集，服务器/本机脚本不参与采集。
     *
     * ★ keyword 必须是**本次实际使用的候选词**（不是清单默认词）：
     *   服务端据此记 kwStats 命中矩阵，运维才能看出「哪个词有用」。
     *
     * @param model 型号（兼容用，提供 modelKey）
     * @param text 慢慢买返回的 HTML
     * @param keyword 本次实际使用的搜索词（默认 = 清单默认词，老调用方不必改）
     */
    suspend fun ingest(model: HwModel, text: String, keyword: String = model.keyword): IngestResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext IngestResult(false, -1, "空内容")
            val body = JSONObject()
                .put("modelKey", model.key)
                .put("keyword", keyword)
                .put("source", "manmanbuy")
                .put("text", text)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE/api/hw/ingest")
                .header("X-Crawler-Key", CRAWLER_KEY)
                .header("X-Client-App", "zaoba/" + BuildConfig.VERSION_NAME)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                val r = JSONObject(raw)
                val code = r.optInt("code")
                val data = r.optJSONObject("data")
                IngestResult(
                    ok = code == 0,
                    code = code,
                    msg = r.optString("msg"),
                    // 老后端没有这两个字段 → 空串 / 0 → 走回退判定（见语义 getter）
                    resultType = r.optString("resultType", ""),
                    resultCode = r.optInt("resultCode", 0),
                    noMatchFlag = data?.optBoolean("noMatch", false) ?: false
                )
            }
        }

    /**
     * 查询服务端状态（轮次窗口 / 是否可采）。
     * 采集前必须先问一次 —— 节奏由服务端说了算，客户端杀进程重启也绕不过去。
     */
    suspend fun fetchStatus(): CrawlStatus? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/api/hw/status").get().build()
            client.newCall(req).execute().use { resp ->
                val root = JSONObject(resp.body?.string() ?: "")
                val d = root.optJSONObject("data") ?: return@withContext null
                CrawlStatus(
                    canCrawlNow = d.optBoolean("canCrawlNow", true),
                    blockReason = d.optString("blockReason"),
                    roundId = d.optString("roundId"),
                    inProtect = d.optBoolean("inProtect"),
                    nextRoundInMs = d.optLong("nextRoundInMs"),
                    roundEndAt = d.optLong("roundEndAt"),
                    total = d.optInt("total"),
                    done = d.optInt("done")
                )
            }
        } catch (e: Exception) { null }
    }

    /** 服务端轮次状态（只表达"能不能采"，不表达"采到第几个"） */
    data class CrawlStatus(
        val canCrawlNow: Boolean,
        val blockReason: String,
        val roundId: String,
        val inProtect: Boolean,
        val nextRoundInMs: Long,
        val roundEndAt: Long,
        val total: Int,
        val done: Int
    )

    /**
     * 上报结果 + 语义分类（唯一真源，见设计 §8.4 决策表）。
     *
     * ★ 向后兼容：老后端只返回 {code, msg, data:{ok,noMatch}}，
     *   resultType 为空 → 靠 `code == 0` + `data.noMatch` 回退判定，与升级前行为一致。
     */
    data class IngestResult(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val resultType: String = "",
        val resultCode: Int = 0,
        val noMatchFlag: Boolean = false
    ) {
        /** 被轮次规则拦下（保护期 4002 / 本轮已采完 4003），应停下等待而非重试 */
        val blockedByRound: Boolean get() = code == 4002 || code == 4003

        /** 已采纳有效价 */
        val isAdopted: Boolean
            get() = resultType == "adopted" || (resultType.isBlank() && ok && !noMatchFlag)

        /** 抓到候选但无可匹配单品 → 不重试，直接记 done */
        val isNoMatch: Boolean
            get() = resultType == "noMatch" || (resultType.isBlank() && ok && noMatchFlag)

        /** 抓到但解析 0 条 → 同轮最多重试 1 次 */
        val isParseEmpty: Boolean
            get() = resultType == "parseEmpty" || code == 4001

        /** 网络/HTTP/解析异常（客户端本地分类）→ 30/60/120s 退避，3 次后记 done */
        val isNetFail: Boolean
            get() = !isAdopted && !isNoMatch && !isParseEmpty && !blockedByRound
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
    private const val KEY_ROUND_ID = "round_id"
    private const val KEY_DONE_KEYS = "done_keys"
    /** 每个型号下次用第几个候选词（换行 + \t 编码，不引入 JSON/Gson） */
    private const val KEY_KW_ATTEMPT = "kw_attempt"
    /** 已命中的关键词（一旦命中即锁定，除过期外不再轮询） */
    private const val KEY_KW_HIT = "kw_hit"
    /** 命中时间（7 天过期判定） */
    private const val KEY_KW_HIT_AT = "kw_hit_at"
    /** 本轮已请求次数（护栏：有价 ≤2 / 其它 ≤3） */
    private const val KEY_ROUND_COUNT = "round_crawl_count"
    /** 本轮网络失败的型号 → 下轮优先 */
    private const val KEY_NET_FAIL = "net_fail_keys"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun getDoneIndex(ctx: Context): Int = sp(ctx).getInt(KEY_DONE_INDEX, 0)

    fun setDoneIndex(ctx: Context, index: Int) {
        sp(ctx).edit().putInt(KEY_DONE_INDEX, index).apply()
    }

    /**
     * 本轮「已采型号 key 集合」。
     *
     * ★ 为什么不用「采到第几号」的 index：
     *   型号清单是固定顺序（显卡→处理器），只要一轮中途被打断（换轮/被杀/保护期），
     *   index 就回不去，结果**排在后面的型号永远采不到**（实测 0 条快照）。
     *   改成记「已采过哪些 key」，每轮从**最久没更新**的型号开始，
     *   无论中途怎么断，冷门型号都不会被饿死。
     */
    fun getDoneKeys(ctx: Context): Set<String> {
        val raw = sp(ctx).getString(KEY_DONE_KEYS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split('\n').filter { it.isNotBlank() }.toSet()
    }

    fun setDoneKeys(ctx: Context, keys: Set<String>) {
        sp(ctx).edit().putString(KEY_DONE_KEYS, keys.joinToString("\n")).apply()
    }

    fun addDoneKey(ctx: Context, key: String) {
        if (key.isBlank()) return
        val s = getDoneKeys(ctx).toMutableSet()
        s.add(key)
        setDoneKeys(ctx, s)
    }

    /** 本地记录的轮次 ID：服务端 roundId 变了就说明进了新一轮，需要重置进度 */
    fun getLastRoundId(ctx: Context): String = sp(ctx).getString(KEY_ROUND_ID, "") ?: ""

    fun setLastRoundId(ctx: Context, roundId: String) {
        sp(ctx).edit().putString(KEY_ROUND_ID, roundId).apply()
    }

    fun getLastRoundAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_ROUND, 0)

    fun setLastRoundAt(ctx: Context, ts: Long) {
        sp(ctx).edit().putLong(KEY_LAST_ROUND, ts).apply()
    }

    // ---------- 换轮清理：本轮的临时计数全部归零 ----------

    /** 进入新一轮：清空轮内计数器（已采集合由调用方决定是否清） */
    fun resetRoundCounters(ctx: Context) {
        sp(ctx).edit()
            .putString(KEY_ROUND_COUNT, "")
            .putString(KEY_NET_FAIL, "")
            .apply()
    }

    // ---------- 关键词轮询（P0-AP-01） ----------

    /** "key\t值\nkey\t值" 形式的单值映射（不引入 JSON 依赖，编码与 done_keys 同风格） */
    private fun readMap(ctx: Context, spKey: String): MutableMap<String, String> {
        val raw = sp(ctx).getString(spKey, "") ?: ""
        val out = mutableMapOf<String, String>()
        if (raw.isBlank()) return out
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val i = line.indexOf('\t')
            if (i <= 0) continue
            out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    private fun writeMap(ctx: Context, spKey: String, map: Map<String, String>) {
        val sb = StringBuilder()
        for ((k, v) in map) {
            if (k.isBlank()) continue
            sb.append(k).append('\t').append(v).append('\n')
        }
        sp(ctx).edit().putString(spKey, sb.toString()).apply()
    }

    fun getKwAttempt(ctx: Context): Map<String, Int> {
        val raw = readMap(ctx, KEY_KW_ATTEMPT)
        val out = mutableMapOf<String, Int>()
        for ((k, v) in raw) out[k] = v.toIntOrNull() ?: 0
        return out
    }

    fun getKwAttemptOf(ctx: Context, key: String): Int = readMap(ctx, KEY_KW_ATTEMPT)[key]?.toIntOrNull() ?: 0

    /** 推进到下一个候选词（取模，不越界） */
    fun advanceKeyword(ctx: Context, model: HwModel) {
        val size = model.keywordList.size
        val map = readMap(ctx, KEY_KW_ATTEMPT)
        val next = if (size <= 0) 0 else ((map[model.key]?.toIntOrNull() ?: 0) + 1) % size
        map[model.key] = next.toString()
        writeMap(ctx, KEY_KW_ATTEMPT, map)
    }

    fun getKwHit(ctx: Context, key: String): String = readMap(ctx, KEY_KW_HIT)[key] ?: ""

    /** 命中即锁定（同时记命中时间，7 天后放开） */
    fun lockKeyword(ctx: Context, key: String, keyword: String) {
        if (key.isBlank() || keyword.isBlank()) return
        val hits = readMap(ctx, KEY_KW_HIT)
        hits[key] = keyword
        writeMap(ctx, KEY_KW_HIT, hits)
        val at = readMap(ctx, KEY_KW_HIT_AT)
        at[key] = System.currentTimeMillis().toString()
        writeMap(ctx, KEY_KW_HIT_AT, at)
    }

    /** 清掉命中锁定（过期或命中词已不在候选组里时用） */
    fun clearKeywordHit(ctx: Context, key: String) {
        val hits = readMap(ctx, KEY_KW_HIT)
        if (hits.containsKey(key)) {
            hits.remove(key)
            writeMap(ctx, KEY_KW_HIT, hits)
        }
        val at = readMap(ctx, KEY_KW_HIT_AT)
        if (at.containsKey(key)) {
            at.remove(key)
            writeMap(ctx, KEY_KW_HIT_AT, at)
        }
    }

    /**
     * 选本次要用的搜索词。
     *   ① kwHit 有值且在候选组内、且未满 7 天 → 用命中词（命中即锁定）
     *   ② 否则用 keywords[kwAttempt % size]（取模不越界）
     *   ③ 老后端没有 keywords → 回退单 keyword（不崩）
     */
    fun pickKeyword(ctx: Context, model: HwModel): String {
        val list = model.keywordList
        if (list.isEmpty()) return model.keyword
        if (list.size == 1) return list[0]

        val hit = getKwHit(ctx, model.key)
        if (hit.isNotBlank() && list.contains(hit)) {
            val at = readMap(ctx, KEY_KW_HIT_AT)[model.key]?.toLongOrNull() ?: 0L
            val fresh = at <= 0L || (System.currentTimeMillis() - at) <= KW_HIT_TTL_MS
            if (fresh) return hit
            clearKeywordHit(ctx, model.key)   // 超过 7 天 → 放开轮询，防数据源变化后锁死
        } else if (hit.isNotBlank()) {
            clearKeywordHit(ctx, model.key)   // 命中词已不在候选组（服务端换了词表）
        }

        val attempt = getKwAttemptOf(ctx, model.key)
        val idx = if (attempt < 0) 0 else attempt % list.size
        return list[idx]
    }

    // ---------- 轮内请求计数（P1-AP-03 护栏） ----------

    fun getRoundCrawlCount(ctx: Context, key: String): Int =
        readMap(ctx, KEY_ROUND_COUNT)[key]?.toIntOrNull() ?: 0

    fun bumpRoundCrawlCount(ctx: Context, key: String): Int {
        val map = readMap(ctx, KEY_ROUND_COUNT)
        val n = (map[key]?.toIntOrNull() ?: 0) + 1
        map[key] = n.toString()
        writeMap(ctx, KEY_ROUND_COUNT, map)
        return n
    }

    // ---------- 本轮网络失败型号（下轮优先） ----------

    fun getNetFailKeys(ctx: Context): Set<String> {
        val raw = sp(ctx).getString(KEY_NET_FAIL, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split('\n').filter { it.isNotBlank() }.toSet()
    }

    fun markNetFail(ctx: Context, key: String) {
        if (key.isBlank()) return
        val s = getNetFailKeys(ctx).toMutableSet()
        s.add(key)
        sp(ctx).edit().putString(KEY_NET_FAIL, s.joinToString("\n")).apply()
    }

    fun clearNetFail(ctx: Context, key: String) {
        val s = getNetFailKeys(ctx).toMutableSet()
        if (s.remove(key)) sp(ctx).edit().putString(KEY_NET_FAIL, s.joinToString("\n")).apply()
    }
}
