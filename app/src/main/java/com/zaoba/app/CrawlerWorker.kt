package com.zaoba.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 单型号采集任务（WorkManager，短任务，几秒内完成）。
 *
 * ★ 关键架构：不在一开始就 delay 3 分钟跑完全部型号（CoroutineWorker 有执行时长上限，
 *   会被系统终止导致"采集停"）。改为：
 *   - 每个 worker 只采「一个型号」（几秒完成）
 *   - 采完后 schedule 下一个型号（延迟 3 分钟）
 *   - 断点续传：本地记录 doneIndex，被杀后从断点继续
 */
class CrawlerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            crawlOne()
            Result.success()
        } catch (e: Exception) {
            // 单型号失败不重试（避免卡住），继续下一个
            Result.success()
        }
    }

    private suspend fun crawlOne() {
        val ctx = applicationContext
        val models = Crawler.fetchModels().filter { it.keyword.isNotBlank() }
        if (models.isEmpty()) return

        val total = models.size
        val index = Crawler.getDoneIndex(ctx).coerceIn(0, total)

        // 一轮已采完 → 重置断点，开始新一轮
        if (index >= total) {
            Crawler.setDoneIndex(ctx, 0)
            Crawler.setLastRoundAt(ctx, System.currentTimeMillis())
            Crawler.reportProgress(running = false, done = total, total = total, current = "", finished = true)
            return
        }

        val model = models[index]

        // 上报进度（running 持续，进度条不消失）
        Crawler.reportProgress(running = true, done = index, total = total, current = model.model, finished = false)

        // 采这一个型号（手机 IP 抓慢慢买 → 上报）
        try {
            val text = Crawler.fetchManmanbuy(model.keyword)
            Crawler.ingest(model, text)
        } catch (e: Exception) {
            // 单个型号失败不中断
        }

        // 推进断点
        Crawler.setDoneIndex(ctx, index + 1)

        // 调度下一个型号（3 分钟后）
        CrawlScheduler.scheduleNext(ctx)
    }
}
