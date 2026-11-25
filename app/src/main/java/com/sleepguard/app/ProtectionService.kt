package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 当前是功能测试版：验证前台服务周期调度和管理员锁屏链路。
 * 摄像头睡眠识别、传感器判断及媒体自动暂停尚未接入。
 */
class ProtectionService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("保护服务运行中：功能测试模式"))
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                while (isActive) {
                    val minutes = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
                        .coerceIn(1, 30)
                    updateNotification("测试模式：下次检测计划间隔 $minutes 分钟")
                    delay(minutes * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SleepGuard 保护状态", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "显示 SleepGuard 后台保护服务状态" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SleepGuard")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        const val ACTION_START = "com.sleepguard.app.START"
        const val ACTION_STOP = "com.sleepguard.app.STOP"
        private const val PREFS_NAME = "sleepguard_settings"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val DEFAULT_INTERVAL_MINUTES = 5
        private const val CHANNEL_ID = "sleepguard_protection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ProtectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProtectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
