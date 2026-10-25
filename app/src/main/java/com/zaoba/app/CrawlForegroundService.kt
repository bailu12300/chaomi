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

/**
 * 采集保活前台服务 —— 采集主循环就在这里跑。
 *
 * ★ 为什么采集放服务里而不靠 WorkManager 链：
 *   WorkManager 的延迟任务（setInitialDelay 3 分钟）在 App 被用户划掉、或被国产 ROM
 *   激进清理后台时会被一并清掉，表现就是「采了一个型号就再也不动了」。
 *   前台服务带常驻通知、进程优先级高，服务活着采集循环就不停。
 *
 * 循环：采一个型号 → delay 3 分钟 → 下一个；一轮采完 → 重置断点 → 等 12 小时 → 下一轮。
 * 服务被杀 → START_STICKY 重启 → 从 SharedPreferences 断点继续。
 */
class CrawlForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

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

        // ① 先问服务端：现在允许采吗？（轮次窗口 / 保护期 / 本轮是否已采完）
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

        val models = Crawler.fetchModels().filter { it.keyword.isNotBlank() }
        if (models.isEmpty()) { delay(60_000); return }

        val total = models.size

        // ② 换轮检测：服务端 roundId 变了 → 清空本轮的"已采集合"，新一轮重来。
        //    进度由**客户端自己维护**（服务端只管周期约束）。
        if (st != null) {
            val lastRound = Crawler.getLastRoundId(ctx)
            if (lastRound != st.roundId) {
                Crawler.setDoneKeys(ctx, emptySet())
                Crawler.setDoneIndex(ctx, 0)
                Crawler.setLastRoundId(ctx, st.roundId)
            }
        }

        // ③ 本轮已采集合 → 决定这一轮还差哪些型号
        val doneKeys = Crawler.getDoneKeys(ctx)
        val pending = models.filter { it.key !in doneKeys }

        // 全部采完 → 等下一轮
        if (pending.isEmpty()) {
            val waitMs = if (st != null && st.nextRoundInMs > 0) st.nextRoundInMs + 60_000L
                         else 10 * 60 * 1000L
            Crawler.reportProgress(running = false, done = total, total = total, current = "", finished = true)
            updateNotification("本轮已完成 $total/$total，${waitMs / 60000} 分钟后开始下一轮")
            delay(waitMs)
            return
        }

        // ★ 选型号策略：**最久没更新的优先**（updatedAt 升序，0 = 从未采过排最前）。
        //   这样即使每轮都跑不完，也会轮着把冷门品类（内存条等）补齐，
        //   而不是永远只采清单最前面那批。
        val model = pending.minByOrNull { it.updatedAt }!!

        val doneCount = total - pending.size
        Crawler.reportProgress(running = true, done = doneCount, total = total, current = model.model, finished = false)
        updateNotification("采集中 ${doneCount + 1}/$total · ${model.model}")

        // ④ 抓慢慢买 → 上报
        var needWaitRound = false
        try {
            val text = Crawler.fetchManmanbuy(model.keyword)
            val r = Crawler.ingest(model, text)
            if (r.blockedByRound) {
                // 服务端说进入保护期了 → 不推进进度，等下一轮
                updateNotification("${r.msg} · 等待下一轮")
                needWaitRound = true
            } else if (r.ok) {
                // ⑤ 入库成功 → 记入本轮已采集合（失败下次重试同一型号）
                Crawler.addDoneKey(ctx, model.key)
            }
        } catch (e: Exception) {
            // 网络/解析异常：不记入集合，下次还会优先重试这个型号
        }

        if (needWaitRound) {
            val waitMs = (st?.nextRoundInMs ?: 0).let { if (it > 0) it + 60_000L else 10 * 60 * 1000L }
            delay(waitMs)
            return
        }

        delay(Crawler.THROTTLE_MS)   // 100 秒节流
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
