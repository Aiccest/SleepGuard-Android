package com.sleepmate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedHashSet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProtectionService : LifecycleService(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var detector: FaceDetector
    private lateinit var sensorManager: SensorManager
    private lateinit var usageStatsManager: UsageStatsManager

    private var targetAppForeground = false
    private var lastForegroundPackage: String? = null
    private var lastUsageEventQueryAt = 0L
    private val processedUsageEvents = LinkedHashSet<String>()
    private var foregroundMonitorJob: Job? = null
    private var sensorRegistered = false
    private var lastActivityAt = 0L
    private var stillSince = 0L
    private var eyesClosedSince = 0L
    @Volatile private var faceDetectedInWindow = false
    @Volatile private var validFrameAnalyzedInWindow = false
    private var consecutiveOpenEyeChecks = 0
    private val triggered = AtomicBoolean(false)
    @Volatile private var screenInteractive = true

    private var detectionJob: Job? = null
    private var cameraJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    stopForegroundMonitor()
                    triggered.set(false)
                    detectionJob?.cancel()
                    detectionJob = null
                    cameraJob?.cancel()
                    cameraJob = null
                    eyesClosedSince = 0L
                    stillSince = 0L
                    unregisterMotionSensor()
                    scope.launch { withContext(Dispatchers.Main) { cameraProvider?.unbindAll() } }
                    updateNotification("屏幕已关闭；睡眠检测与摄像头已停止")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    if (hasUsageAccess()) startForegroundMonitor()
                    refreshForegroundState()
                    resumeMonitoringIfEligible()
                }
            }
        }
    }

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
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        startForeground(NOTIFICATION_ID, notification("低功耗保护已启动"))
        if (intent?.action == ACTION_SELECTION_CHANGED) {
            refreshForegroundState()
            return START_STICKY
        }
        if (!hasUsageAccess()) {
            updateNotification("请开启使用情况访问权限")
            return START_STICKY
        }
        startForegroundMonitor()
        refreshForegroundState()
        return START_STICKY
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return try { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED }
        catch (_: Exception) { false }
    }

    private fun startForegroundMonitor() {
        if (!screenInteractive || foregroundMonitorJob?.isActive == true) return
        foregroundMonitorJob = scope.launch {
            while (isActive) {
                refreshForegroundState()
                delay(2000L)
            }
        }
    }

    private fun stopForegroundMonitor() {
        foregroundMonitorJob?.cancel()
        foregroundMonitorJob = null
    }

    private fun refreshForegroundState() {
        if (!screenInteractive) return
        if (!hasUsageAccess()) { setTargetAppForeground(false); return }
        val selected = MediaAppPreferences.getSelectedPackages(this)
        if (selected.isEmpty()) { setTargetAppForeground(false); return }
        val now = System.currentTimeMillis()
        // Initialize from a short recent window; thereafter query incrementally with
        // a small overlap so delayed UsageEvents are less likely to be missed.
        val eventWindowStart = if (lastUsageEventQueryAt == 0L) {
            now - INITIAL_USAGE_EVENT_WINDOW_MS
        } else {
            (lastUsageEventQueryAt - USAGE_EVENT_OVERLAP_MS).coerceAtLeast(0L)
        }
        val events = usageStatsManager.queryEvents(eventWindowStart, now)
        val event = UsageEvents.Event()
        var foreground = lastForegroundPackage
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventKey = "${event.timeStamp}:${event.eventType}:${event.packageName}"
            if (!processedUsageEvents.add(eventKey)) continue
            if (processedUsageEvents.size > MAX_REMEMBERED_USAGE_EVENTS) {
                val oldest = processedUsageEvents.firstOrNull()
                if (oldest != null) processedUsageEvents.remove(oldest)
            }
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED ->
                    foreground = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (foreground == event.packageName) foreground = null
            }
        }
        lastUsageEventQueryAt = now
        lastForegroundPackage = foreground
        setTargetAppForeground(foreground in selected)
    }

    private fun setTargetAppForeground(active: Boolean) {
        if (targetAppForeground == active) return
        targetAppForeground = active
        if (active) {
            lastActivityAt = SystemClock.elapsedRealtime()
            stillSince = 0L
            triggered.set(false)
            if (screenInteractive) {
                registerMotionSensor()
                updateNotification("已进入所选应用；开始低功耗等待")
                scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
            }
        } else {
            detectionJob?.cancel()
            cameraJob?.cancel()
            unregisterMotionSensor()
            scope.launch { withContext(Dispatchers.Main) { cameraProvider?.unbindAll() } }
            triggered.set(false)
            eyesClosedSince = 0L
            consecutiveOpenEyeChecks = 0
            updateNotification("已离开所选应用，睡眠检测已暂停")
        }
    }

    private fun resumeMonitoringIfEligible() {
        if (!screenInteractive || !targetAppForeground || triggered.get()) return
        lastActivityAt = SystemClock.elapsedRealtime()
        stillSince = 0L
        registerMotionSensor()
        updateNotification("屏幕已亮起且所选应用仍在前台；重新开始低功耗等待")
        scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
    }

    private fun registerMotionSensor() {
        if (sensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorRegistered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        stillSince = 0L
    }

    private fun unregisterMotionSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
        stillSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!screenInteractive || !targetAppForeground || triggered.get() ||
            event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = kotlin.math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        val now = SystemClock.elapsedRealtime()
        if (kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH) > MOVEMENT_THRESHOLD) {
            lastActivityAt = now
            stillSince = 0L
            consecutiveOpenEyeChecks = 0
            cameraJob?.cancel()
            scope.launch { withContext(Dispatchers.Main) { cameraProvider?.unbindAll() } }
            scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
        } else if (stillSince == 0L) {
            stillSince = now
        }
    }

    private fun scheduleDetection(delayMs: Long) {
        detectionJob?.cancel()
        if (!screenInteractive || !targetAppForeground || triggered.get()) return
        detectionJob = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            if (!screenInteractive || !targetAppForeground || triggered.get()) return@launch
            val now = SystemClock.elapsedRealtime()
            val idleFor = now - lastActivityAt
            if (idleFor < NO_ACTIVITY_REQUIRED_MS) {
                scheduleDetection(NO_ACTIVITY_REQUIRED_MS - idleFor)
                return@launch
            }
            val stillFor = if (stillSince == 0L) 0L else now - stillSince
            if (stillFor < PHONE_STILL_REQUIRED_MS) {
                updateNotification("等待手机保持静止")
                scheduleDetection((PHONE_STILL_REQUIRED_MS - stillFor).coerceAtLeast(1000L))
                return@launch
            }
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isInteractive || !screenInteractive) return@launch
            startCameraCheck()
        }
    }

    private fun startCameraCheck() {
        if (cameraJob?.isActive == true || !screenInteractive || !targetAppForeground || triggered.get()) return
        cameraJob = scope.launch {
            eyesClosedSince = 0L
            updateNotification("正在进行短时睡眠确认")
            try {
                val result = runCameraWindow()
                if (!result.second) {
                    updateNotification("摄像头未取得有效画面；本轮不作睡眠判断")
                } else if (!result.first && screenInteractive && targetAppForeground &&
                    !triggered.get() && stillSince > 0L &&
                    SystemClock.elapsedRealtime() - stillSince >= PHONE_STILL_REQUIRED_MS) {
                    updateNotification("连续30秒未检测到人脸；判定睡眠并执行保护")
                    if (triggered.compareAndSet(false, true)) performProtection()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateNotification("本轮摄像头检测未能启动；稍后重试")
            }
            if (!triggered.get() && screenInteractive && targetAppForeground && isActive) {
                consecutiveOpenEyeChecks = (consecutiveOpenEyeChecks + 1).coerceAtMost(4)
                val minutes = when (consecutiveOpenEyeChecks) {
                    1 -> 15
                    2 -> 20
                    3 -> 25
                    else -> 30
                }
                updateNotification("本轮未确认睡眠；约${minutes}分钟后复查")
                scheduleDetection(minutes * 60_000L)
            }
        }
    }

    private suspend fun runCameraWindow(): Pair<Boolean, Boolean> {
        faceDetectedInWindow = false
        validFrameAnalyzedInWindow = false
        val future = ProcessCameraProvider.getInstance(this)
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            future.addListener({
                try { cont.resume(future.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, cameraExecutor)
        }
        cameraProvider = provider
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { analyzeFrame(it) }
        withContext(Dispatchers.Main) {
            provider.unbindAll()
            provider.bindToLifecycle(this@ProtectionService, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        try {
            delay(CAMERA_WINDOW_MS)
        } finally {
            analysis.clearAnalyzer()
            withContext(Dispatchers.Main) { provider.unbindAll() }
        }
        return Pair(faceDetectedInWindow, validFrameAnalyzedInWindow)
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || triggered.get() || !screenInteractive || !targetAppForeground) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener(cameraExecutor) { faces ->
                if (!screenInteractive || !targetAppForeground || triggered.get()) return@addOnSuccessListener
                validFrameAnalyzedInWindow = true
                if (faces.isNotEmpty()) faceDetectedInWindow = true
                val face = faces.firstOrNull()
                val left = face?.leftEyeOpenProbability
                val right = face?.rightEyeOpenProbability
                val closed = left != null && right != null && left < 0.25f && right < 0.25f
                val now = SystemClock.elapsedRealtime()
                if (closed) {
                    if (eyesClosedSince == 0L) eyesClosedSince = now
                    val eyesClosedLongEnough = now - eyesClosedSince >= EYES_CLOSED_REQUIRED_MS
                    val phoneStillLongEnough = stillSince > 0L && now - stillSince >= PHONE_STILL_REQUIRED_MS
                    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (eyesClosedLongEnough && phoneStillLongEnough &&
                        screenInteractive && power.isInteractive && targetAppForeground &&
                        triggered.compareAndSet(false, true)) {
                        scope.launch { performProtection() }
                    }
                } else {
                    eyesClosedSince = 0L
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private suspend fun performProtection() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        updateNotification("确认符合睡眠条件；正在锁定屏幕")
        detectionJob?.cancel()
        unregisterMotionSensor()
        withContext(Dispatchers.Main) { cameraProvider?.unbindAll() }
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))) {
            dpm.lockNow()
        } else {
            updateNotification("已确认睡眠；尝试返回桌面")
            try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        running.value = false
        detectionJob?.cancel()
        cameraJob?.cancel()
        foregroundMonitorJob?.cancel()
        scope.cancel()
        unregisterMotionSensor()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        cameraProvider?.unbindAll()
        detector.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SleepMate 保护状态", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(contentPendingIntent)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SleepMate")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        val running = MutableStateFlow(false)

        const val ACTION_START = "com.sleepmate.app.START"
        const val ACTION_STOP = "com.sleepmate.app.STOP"
        const val ACTION_SELECTION_CHANGED = "com.sleepmate.app.SELECTION_CHANGED"
        private const val EYES_CLOSED_REQUIRED_MS = 3000L
        private const val PHONE_STILL_REQUIRED_MS = 8000L
        private const val NO_ACTIVITY_REQUIRED_MS = 5 * 60_000L
        private const val INITIAL_USAGE_EVENT_WINDOW_MS = 10_000L
        private const val USAGE_EVENT_OVERLAP_MS = 5_000L
        private const val MAX_REMEMBERED_USAGE_EVENTS = 128
        private const val CAMERA_WINDOW_MS = 30_000L
        private const val MOVEMENT_THRESHOLD = 1.0f
        private const val CHANNEL_ID = "sleepguard_protection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ProtectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}
