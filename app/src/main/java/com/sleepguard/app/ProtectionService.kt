package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * V1 基础保护服务。
 * 当前只验证前台服务、周期调度和管理员锁屏链路。
 * 摄像头/传感器判断尚未接入，严禁将本版本当作已具备自动睡眠识别能力。
 */
class ProtectionService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("保护服务运行中：基础测试模式"))
        if (loopJob?.isActive != true) {
            loopJob = scope.launch {
                // V1 仅做调度占位，不会自动锁屏，避免尚未接入检测时误锁用户手机。
                while (isActive) {
                    val startedAt = SystemClock.elapsedRealtime()
                    delay(INTERVAL_MS)
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    updateNotification("基础测试模式：已运行约 ${elapsed / 60000} 分钟")
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
        private const val CHANNEL_ID = "sleepguard_protection"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVAL_MS = 20L * 60L * 1000L

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
