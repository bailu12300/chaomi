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
import androidx.core.app.NotificationManagerCompat

/**
 * 采集保活前台服务。
 *
 * 通过常驻通知 + 前台服务，让采集任务在 App 退到后台/被系统回收时仍能存活。
 * 前台服务是 Android 官方认可的「强保活」手段（配合 WAKE_LOCK 保持唤醒）。
 */
class CrawlForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 保活：确保采集调度在运行
        CrawlScheduler.schedule(applicationContext)
        return START_STICKY  // 被杀后系统会尽量重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "crawl_keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "采集保活", NotificationManager.IMPORTANCE_LOW)
            channel.description = "保持硬件行情采集在后台运行"
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("早八 · 行情采集中")
            .setContentText("后台持续采集硬件行情，保持应用运行可确保数据更新")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001

        /** 启动保活服务 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, CrawlForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 停止保活服务 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CrawlForegroundService::class.java))
        }
    }
}
