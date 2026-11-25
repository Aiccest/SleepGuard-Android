package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProtectionService : LifecycleService(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var detector: FaceDetector
    private lateinit var sensorManager: SensorManager
    private var stillSince = 0L
    private var eyesClosedSince = 0L
    @Volatile private var triggered = false
    private var loopJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("智能保护运行中：等待检测"))
        if (loopJob?.isActive != true) loopJob = scope.launch { monitoringLoop() }
        return START_STICKY
    }

    private suspend fun monitoringLoop() {
        while (isActive && !triggered) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seconds = prefs.getInt(KEY_MONITOR, 10).coerceIn(5, 15)
            val interval = prefs.getInt(KEY_INTERVAL, 5).coerceIn(1, 30)
            updateNotification("正在进行本轮人脸与闭眼检测（$seconds 秒）")
            eyesClosedSince = 0L
            try {
                runCameraWindow(seconds)
            } catch (e: Exception) {
                updateNotification("摄像头检测失败：请检查权限或系统限制")
            }
            if (triggered) break
            updateNotification("检测结束；约 $interval 分钟后再次检测")
            delay(interval * 60_000L)
        }
    }

    private suspend fun runCameraWindow(seconds: Int) {
        val future = ProcessCameraProvider.getInstance(this)
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, cameraExecutor)
        }
        cameraProvider = provider
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }
        withContext(Dispatchers.Main) {
            provider.unbindAll()
            provider.bindToLifecycle(this@ProtectionService, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        try {
            delay(seconds * 1000L)
        } finally {
            analysis.clearAnalyzer()
            withContext(Dispatchers.Main) { provider.unbindAll() }
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || triggered) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.firstOrNull()
                val left = face?.leftEyeOpenProbability
                val right = face?.rightEyeOpenProbability
                val closed = left != null && right != null && left < 0.25f && right < 0.25f
                val now = System.currentTimeMillis()
                if (closed) {
                    if (eyesClosedSince == 0L) eyesClosedSince = now
                    val eyesClosedLongEnough = now - eyesClosedSince >= EYES_CLOSED_REQUIRED_MS
                    val phoneStillLongEnough = stillSince > 0L && now - stillSince >= PHONE_STILL_REQUIRED_MS
                    if (eyesClosedLongEnough && phoneStillLongEnough && !triggered) {
                        triggered = true
                        scope.launch { performProtection() }
                    }
                } else {
                    eyesClosedSince = 0L
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private suspend fun performProtection() {
        updateNotification("检测到持续闭眼且手机稳定，正在暂停媒体并锁屏")
        pauseActiveMediaSessions()
        delay(500)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))) {
            dpm.lockNow()
        } else {
            updateNotification("检测到疑似睡眠，但未授权设备管理员，无法自动锁屏")
            triggered = false
        }
    }

    private fun pauseActiveMediaSessions() {
        try {
            val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.contains(packageName) == true
            if (!enabled) return
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, SleepGuardNotificationListener::class.java))
            sessions.forEach { session ->
                try { session.transportControls.pause() } catch (_: Exception) { }
            }
        } catch (_: SecurityException) { }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) < 0.8f) {
            if (stillSince == 0L) stillSince = now
        } else {
            stillSince = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        sensorManager.unregisterListener(this)
        cameraProvider?.unbindAll()
        detector.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SleepGuard 保护状态", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SleepGuard")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val ACTION_START = "com.sleepguard.app.START"
        const val ACTION_STOP = "com.sleepguard.app.STOP"
        private const val PREFS = "sleepguard_settings"
        private const val KEY_MONITOR = "monitor_seconds"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val EYES_CLOSED_REQUIRED_MS = 3000L
        private const val PHONE_STILL_REQUIRED_MS = 8000L
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
