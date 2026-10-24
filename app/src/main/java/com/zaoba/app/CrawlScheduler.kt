package com.zaoba.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 采集调度（OneTimeWork 链式）。
 *
 * ★ 架构：每个 worker 采「一个型号」（几秒完成），采完后 schedule 下一个（3 分钟延迟）。
 *   - 3 分钟/型号：精确节流
 *   - 断点续传：doneIndex 记录进度
 *   - App 启动时 schedule() 兜底：链断后下次打开 App 从断点继续
 *
 * 注意：PeriodicWork 最小 15 分钟，无法做 3 分钟/型号，故用 OneTimeWork 链式。
 */
object CrawlScheduler {
    private const val WORK_NAME = "hw_crawl_next"

    /** App 启动时调用：从断点继续（若已有 pending 任务则不重复） */
    fun schedule(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CrawlerWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP, request
        )
    }

    /** 采完一个型号后调用：3 分钟后采下一个 */
    fun scheduleNext(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CrawlerWorker>()
            .setConstraints(constraints)
            .setInitialDelay(Crawler.THROTTLE_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }
}
