package com.sleepguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProtectionService : LifecycleService(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var detector: FaceDetector
    private lateinit var sensorManager: SensorManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var mediaPlaying = false
    private var sensorRegistered = false
    private var lastActivityAt = 0L
    private var stillSince = 0L
    private var eyesClosedSince = 0L
    private var consecutiveOpenEyeChecks = 0
    @Volatile private var triggered = false

    private var detectionJob: Job? = null
    private var cameraJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateControllers(controllers.orEmpty())
        }

    private val playbackCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshPlaybackState()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    cameraJob?.cancel()
                    scope.launch { withContext(Dispatchers.Main) { cameraProvider?.unbindAll() } }
                    updateNotification("屏幕已关闭；等待下次允许检测的时机")
                }
                Intent.ACTION_SCREEN_ON -> scheduleDetection(0L)
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
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
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
        startForeground(NOTIFICATION_ID, notification("低功耗保护已启动：等待媒体播放"))
        if (hasMediaAccess()) {
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    ComponentName(this, SleepGuardNotificationListener::class.java)
                )
                updateControllers(mediaSessionManager.getActiveSessions(
                    ComponentName(this, SleepGuardNotificationListener::class.java)
                ))
            } catch (_: SecurityException) {
                updateNotification("请开启媒体通知访问权限")
            }
        } else {
            updateNotification("请开启媒体通知访问权限，才能识别媒体播放状态")
        }
        return START_STICKY
    }

    private fun hasMediaAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun updateControllers(controllers: List<MediaController>) {
        controllerCallbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        controllerCallbacks.clear()
        controllers.forEach { controller ->
            controller.registerCallback(playbackCallback)
            controllerCallbacks[controller] = playbackCallback
        }
        refreshPlaybackState()
    }

    private fun refreshPlaybackState() {
        val playing = controllerCallbacks.keys.any { controller ->
            val state = controller.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        }
        setMediaPlaying(playing)
    }

    private fun setMediaPlaying(playing: Boolean) {
        if (mediaPlaying == playing) return
        mediaPlaying = playing
        if (playing) {
            lastActivityAt = SystemClock.elapsedRealtime()
            stillSince = 0L
            registerMotionSensor()
            updateNotification("检测到媒体播放；进入低功耗等待")
            scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
        } else {
            detectionJob?.cancel()
            cameraJob?.cancel()
            unregisterMotionSensor()
            scope.launch { withContext(Dispatchers.Main) { cameraProvider?.unbindAll() } }
            consecutiveOpenEyeChecks = 0
            updateNotification("没有媒体播放；摄像头保持关闭")
        }
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
        if (!mediaPlaying || event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
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
            updateNotification("检测到设备活动；重新计算空闲时间")
        } else if (stillSince == 0L) {
            stillSince = now
        }
    }

    private fun scheduleDetection(delayMs: Long) {
        detectionJob?.cancel()
        if (!mediaPlaying || triggered) return
        detectionJob = scope.launch {
            delay(delayMs.coerceAtLeast(0L))
            if (!mediaPlaying || triggered) return@launch
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
            if (!power.isInteractive) {
                updateNotification("屏幕关闭；等待屏幕重新亮起后检测")
                return@launch
            }
            startCameraCheck()
        }
    }

    private fun startCameraCheck() {
        if (cameraJob?.isActive == true || !mediaPlaying || triggered) return
        cameraJob = scope.launch {
            eyesClosedSince = 0L
            updateNotification("正在进行短时睡眠确认")
            try {
                runCameraWindow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateNotification("本轮摄像头检测未能启动；稍后重试")
            }
            if (!triggered && mediaPlaying && isActive) {
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

    private suspend fun runCameraWindow() {
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
                val now = SystemClock.elapsedRealtime()
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
        updateNotification("确认符合睡眠条件；正在暂停媒体并锁屏")
        pauseActiveMediaSessions()
        delay(500)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))) {
            dpm.lockNow()
        } else {
            updateNotification("已暂停媒体，但未激活设备管理员，无法自动锁屏")
            triggered = false
        }
    }

    private fun pauseActiveMediaSessions() {
        controllerCallbacks.keys.forEach { controller ->
            try { controller.transportControls.pause() } catch (_: Exception) { }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        detectionJob?.cancel()
        cameraJob?.cancel()
        scope.cancel()
        unregisterMotionSensor()
        controllerCallbacks.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        controllerCallbacks.clear()
        try { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) } catch (_: Exception) { }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
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
        private const val EYES_CLOSED_REQUIRED_MS = 3000L
        private const val PHONE_STILL_REQUIRED_MS = 8000L
        private const val NO_ACTIVITY_REQUIRED_MS = 5 * 60_000L
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
            context.startService(Intent(context, ProtectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
