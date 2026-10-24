package com.zaoba.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 采集调度：每 12 小时一轮（PeriodicWorkRequest 最小间隔 15 分钟，12 小时没问题）。
 * 后台运行：需联网约束即可（不强求充电，采集耗电可控）。
 */
object CrawlScheduler {
    private const val WORK_NAME = "hw_crawl_periodic"

    fun schedule(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CrawlerWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,  // 已存在则不重复
            request
        )
    }

    /** 立即触发一次（手动/测试用） */
    fun runNow(ctx: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<CrawlerWorker>().build()
        WorkManager.getInstance(ctx).enqueue(request)
    }
}
