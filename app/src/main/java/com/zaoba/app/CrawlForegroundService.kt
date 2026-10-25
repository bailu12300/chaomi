package com.zaoba.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 采集保活前台服务 —— 采集主循环就在这里跑。
 *
 * ★ 为什么采集放服务里而不靠 WorkManager 链：
 *   WorkManager 的延迟任务（setInitialDelay）在 App 被用户划掉、或被国产 ROM
 *   激进清理后台时会被一并清掉，表现就是「采了一个型号就再也不动了」。
 *   前台服务带常驻通知、进程优先级高，服务活着采集循环就不停。
 *
 * 循环：采一个型号（含分级重试）→ 100 秒节流 → 下一个；本轮采完 → 等下一轮。
 * 服务被杀 → START_STICKY 重启 → 从 SharedPreferences 断点继续。
 *
 * 本次增量（P0-AP-02 / P1-AP-03）：
 *   · 重试按失败类型分级：网络 30/60/120s 退避(3 次) / parseEmpty 重试 1 次 / noMatch 不重试
 *   · **所有重试都计入 100 秒全局节流**：有效间隔 = max(backoff, THROTTLE_MS)
 *   · due 过滤：按服务端下发的 nextDueAt（6h 有价 / 12h 无价 / 36h lowPriority）决定要不要采
 *   · 轮内上限：有价型号 ≤2 次请求，其它 ≤3 次（硬护栏，防一个型号吃掉整轮）
 */
class CrawlForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** 单型号单轮请求硬上限（有价 / 其它） */
    private fun roundCap(m: HwModel): Int = if (m.price != null) 2 else 3

    /** 网络失败退避序列（秒）：实际有效间隔还要再取 max(..., THROTTLE_MS) */
    private val netBackoffMs = longArrayOf(30_000L, 60_000L, 120_000L)

    private enum class Outcome { DONE, BLOCKED }

    override fun onCreate() {
        super.onCreate()
        // 清理旧版残留的 WorkManager 任务（v1.0.16 及以前用 OneTimeWork 链采集），
        // 否则升级后新旧两套采集会并行、重复上报。
        try { androidx.work.WorkManager.getInstance(this).cancelUniqueWork("hw_crawl_next") } catch (e: Exception) {}
        try { androidx.work.WorkManager.getInstance(this).cancelUniqueWork("hw_crawl_periodic") } catch (e: Exception) {}
        startForeground(NOTIF_ID, buildNotification("准备采集"))
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                try {
                    crawlOne()
                } catch (e: Exception) {
                    delay(30_000)   // 异常不让循环退出
                }
            }
        }
    }

    private suspend fun crawlOne() {
        val ctx = applicationContext

        // ① 先问服务端：现在允许采吗？（轮次窗口 / 保护期）
        //    节奏由服务端强管控，本地只负责执行 —— 这样杀进程重启也绕不过节流。
        val st = Crawler.fetchStatus()
        if (st != null && !st.canCrawlNow) {
            val waitMs = when {
                st.nextRoundInMs > 0 -> st.nextRoundInMs + 60_000L   // 等到下一轮开始（+1分钟缓冲）
                else -> 5 * 60 * 1000L
            }
            val mins = waitMs / 60000
            val why = st.blockReason.ifBlank { if (st.inProtect) "保护期" else "等待下一轮" }
            updateNotification("$why · ${mins} 分钟后继续")
            Crawler.reportProgress(running = false, done = st.done, total = st.total, current = "", finished = false)
            delay(waitMs)
            return
        }

        val models = Crawler.fetchModels().filter { it.keywordList.isNotEmpty() }
        if (models.isEmpty()) { delay(60_000); return }

        val total = models.size

        // ② 换轮检测：服务端 roundId 变了 → 清空本轮的已采集合 + 轮内计数。
        //    进度由**客户端自己维护**（服务端只管周期约束）。
        if (st != null) {
            val lastRound = Crawler.getLastRoundId(ctx)
            if (lastRound != st.roundId) {
                Crawler.setDoneKeys(ctx, emptySet())
                Crawler.setDoneIndex(ctx, 0)
                Crawler.setLastRoundId(ctx, st.roundId)
                Crawler.resetRoundCounters(ctx)
            }
        }

        val now = System.currentTimeMillis()
        val doneKeys = Crawler.getDoneKeys(ctx)
        val netFailKeys = Crawler.getNetFailKeys(ctx)

        // ③ 本轮已采集合 → 决定这一轮还差哪些型号
        val notDone = models.filter { it.key !in doneKeys }
        if (notDone.isEmpty()) {
            val waitMs = if (st != null && st.nextRoundInMs > 0) st.nextRoundInMs + 60_000L
                         else 10 * 60 * 1000L
            Crawler.reportProgress(running = false, done = total, total = total, current = "", finished = true)
            updateNotification("本轮已完成 $total/$total，${waitMs / 60000} 分钟后开始下一轮")
            delay(waitMs)
            return
        }

        // ④ due 过滤（P1-AP-03）：nextDueAt 未到 → 本轮不发请求；轮内请求数达上限 → 也不再发
        //    ★ 只能用服务端下发的 nextDueAt（其基准是 lastCrawlAt），不要自己用 updatedAt 算，
        //      否则熔断期间（updatedAt 不推进）会反复空刷。
        val pending = notDone.filter { m ->
            (m.nextDueAt <= 0L || now >= m.nextDueAt) &&
                Crawler.getRoundCrawlCount(ctx, m.key) < roundCap(m)
        }
        if (pending.isEmpty()) {
            // 都没到点 → 睡到最近的一个到期时间（或下一轮开始），至少 60 秒
            val earliestDue = notDone.map { if (it.nextDueAt > 0L) it.nextDueAt else Long.MAX_VALUE }.minOrNull()
                ?: Long.MAX_VALUE
            val toRound = if (st != null && st.nextRoundInMs > 0) st.nextRoundInMs else Long.MAX_VALUE
            val raw = min(earliestDue, toRound)
            val waitMs = if (raw == Long.MAX_VALUE) 10 * 60 * 1000L else max(60_000L, raw - now + 60_000L)
            Crawler.reportProgress(running = false, done = total - notDone.size, total = total, current = "", finished = false)
            updateNotification("未到采集时间 · ${waitMs / 60000} 分钟后继续")
            delay(waitMs)
            return
        }

        // ⑤ 选型号：本轮网络失败过的优先补采 → 其余按「最久没更新的优先」（updatedAt 升序）
        val model = pending.sortedWith(
            compareBy<HwModel> { if (it.key in netFailKeys) 0 else 1 }
                .thenBy { it.updatedAt }
        ).first()

        val doneCount = total - notDone.size
        Crawler.reportProgress(running = true, done = doneCount, total = total, current = model.model, finished = false)
        updateNotification("采集中 ${doneCount + 1}/$total · ${model.model}")

        // ⑥ 抓取 + 分级重试
        val outcome = crawlWithRetry(ctx, model)

        if (outcome == Outcome.BLOCKED) {
            val waitMs = (st?.nextRoundInMs ?: 0).let { if (it > 0) it + 60_000L else 10 * 60 * 1000L }
            updateNotification("进入保护期 · ${waitMs / 60000} 分钟后继续")
            delay(waitMs)
            return
        }

        delay(Crawler.THROTTLE_MS)   // 100 秒节流（相邻请求的最小间隔）
    }

    /**
     * 采一个型号，按结果类型分级重试（设计 §8.4 决策表）。
     *
     * | 结果        | 重试            | 记 done | 词序           | 间隔                          |
     * |------------|-----------------|---------|----------------|-------------------------------|
     * | adopted    | 不重试           | ✅      | 锁定 kwHit      | THROTTLE                      |
     * | noMatch    | 不重试           | ✅      | kwAttempt++    | THROTTLE                      |
     * | parseEmpty | 同轮再试 1 次    | 第2次后 ✅| 第2次后 ++      | THROTTLE                      |
     * | 网络/异常   | 30/60/120s 退避  | 3次后 ✅ | 3次后 ++        | max(backoff, THROTTLE)        |
     */
    private suspend fun crawlWithRetry(ctx: Context, model: HwModel): Outcome {
        var parseAttempts = 0
        var netFails = 0

        while (true) {
            // 轮内请求上限（硬护栏）：到顶就不再多发，直接算本轮完成
            if (Crawler.getRoundCrawlCount(ctx, model.key) >= roundCap(model)) {
                Crawler.addDoneKey(ctx, model.key)
                return Outcome.DONE
            }
            Crawler.bumpRoundCrawlCount(ctx, model.key)

            // 每次请求都重新取词（kwHit 可能刚被上一轮锁定）
            val keyword = Crawler.pickKeyword(ctx, model)
            val r: Crawler.IngestResult? = try {
                val text = Crawler.fetchManmanbuy(keyword)
                Crawler.ingest(model, text, keyword)
            } catch (e: Exception) {
                null      // 网络 / HTTP / 解析异常 → 本地归为 A 类失败
            }

            when {
                // ---- 被轮次规则拦下（保护期 4002）→ 不推进进度，等下一轮 ----
                r != null && r.blockedByRound -> {
                    updateNotification("${r.msg} · 等待下一轮")
                    return Outcome.BLOCKED
                }

                // ---- 已采纳 → 记 done + 锁定命中词 ----
                r != null && r.isAdopted -> {
                    Crawler.addDoneKey(ctx, model.key)
                    Crawler.lockKeyword(ctx, model.key, keyword)
                    Crawler.clearNetFail(ctx, model.key)
                    return Outcome.DONE
                }

                // ---- 抓到但无单品 → 不重试，直接记 done + 换下一个词 ----
                r != null && r.isNoMatch -> {
                    Crawler.addDoneKey(ctx, model.key)
                    Crawler.advanceKeyword(ctx, model)
                    return Outcome.DONE
                }

                // ---- 解析 0 条 → 同轮最多重试 1 次（仍失败强制记 done，防饿死整轮）----
                r != null && r.isParseEmpty -> {
                    parseAttempts++
                    if (parseAttempts >= 2) {
                        Crawler.addDoneKey(ctx, model.key)
                        Crawler.advanceKeyword(ctx, model)
                        return Outcome.DONE
                    }
                    delay(Crawler.THROTTLE_MS)   // 有效间隔 = max(0, 100s)
                }

                // ---- 网络 / HTTP / 其它异常 → 指数退避，3 次后记 done 并标 netFail ----
                else -> {
                    netFails++
                    if (netFails >= 3) {
                        Crawler.addDoneKey(ctx, model.key)
                        Crawler.markNetFail(ctx, model.key)
                        Crawler.advanceKeyword(ctx, model)
                        return Outcome.DONE
                    }
                    val backoff = netBackoffMs[min(netFails - 1, netBackoffMs.size - 1)]
                    // ★ 硬约束：重试同样计入 100 秒全局节流，不得绕过 → 取两者较大值
                    delay(max(backoff, Crawler.THROTTLE_MS))
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val channelId = "crawl_keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "采集保活", NotificationManager.IMPORTANCE_LOW)
            channel.description = "保持硬件行情采集在后台运行"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("早八 · 行情采集")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {}
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, CrawlForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CrawlForegroundService::class.java))
        }
    }
}
