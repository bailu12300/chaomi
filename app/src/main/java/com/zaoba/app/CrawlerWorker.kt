package com.zaoba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

/**
 * 后台采集任务（WorkManager）。
 *
 * 逻辑：
 *   1. 拉型号清单
 *   2. 从本地断点（doneIndex）继续采集
 *   3. 每采集一个型号，等 3 分钟（节流），并上报进度 + 更新本地断点
 *   4. 跑完一轮 → 记录完成时间，12 小时后下一轮（由 WorkManager 调度）
 *   5. 意外被杀后台 → 下次启动从 doneIndex 继续（断点续传）
 */
class CrawlerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            runCrawl()
            Result.success()
        } catch (e: Exception) {
            // 失败不崩溃，交给 WorkManager 重试（retry）
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun runCrawl() {
        val ctx = applicationContext
        val models = Crawler.fetchModels().filter { it.keyword.isNotBlank() }
        if (models.isEmpty()) return

        val total = models.size
        val startIndex = Crawler.getDoneIndex(ctx).coerceIn(0, total)

        // 上报开始
        Crawler.reportProgress(running = true, done = startIndex, total = total, current = "", finished = false)

        for (i in startIndex until total) {
            val model = models[i]
            // 上报当前进度
            Crawler.reportProgress(running = true, done = i, total = total, current = model.model, finished = false)

            // 手机 IP 抓慢慢买
            val ok = try {
                val text = Crawler.fetchManmanbuy(model.keyword)
                Crawler.ingest(model, text)
            } catch (e: Exception) {
                false
            }

            // 更新本地断点（无论成功失败都推进，避免卡在同一型号）
            Crawler.setDoneIndex(ctx, i + 1)

            // 3 分钟节流（最后一个型号不用等）
            if (i < total - 1) {
                delay(Crawler.THROTTLE_MS)
            }
        }

        // 一轮完成
        Crawler.setDoneIndex(ctx, 0)          // 重置断点，下一轮从头开始
        Crawler.setLastRoundAt(ctx, System.currentTimeMillis())
        Crawler.reportProgress(running = false, done = total, total = total, current = "", finished = true)
    }
}
