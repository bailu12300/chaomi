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
        val models = Crawler.fetchModels().filter { it.keyword.isNotBlank() }
        if (models.isEmpty()) { delay(60_000); return }

        val total = models.size
        val index = Crawler.getDoneIndex(ctx).coerceIn(0, total)

        // 一轮采完 → 重置断点，等下一轮
        if (index >= total) {
            Crawler.setDoneIndex(ctx, 0)
            Crawler.setLastRoundAt(ctx, System.currentTimeMillis())
            Crawler.reportProgress(running = false, done = total, total = total, current = "", finished = true)
            updateNotification("本轮已完成 $total/$total，等待下一轮")
            delay(12 * 60 * 60 * 1000L)
            return
        }

        val model = models[index]
        Crawler.reportProgress(running = true, done = index, total = total, current = model.model, finished = false)
        updateNotification("采集中 $index/$total · ${model.model}")

        try {
            val text = Crawler.fetchManmanbuy(model.keyword)
            Crawler.ingest(model, text)
        } catch (e: Exception) {
            // 单型号失败不中断
        }

        // 推进断点（成败都推进，避免卡死同一型号）
        Crawler.setDoneIndex(ctx, index + 1)

        delay(Crawler.THROTTLE_MS)  // 3 分钟节流
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
