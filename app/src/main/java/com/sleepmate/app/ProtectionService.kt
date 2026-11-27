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
import android.provider.Settings
import android.util.Log
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
    @Volatile private var detector: FaceDetector? = null
    private val detectorLock = Any()
    private lateinit var sensorManager: SensorManager
    private lateinit var usageStatsManager: UsageStatsManager

    private var targetAppForeground = false
    private var lastForegroundPackage: String? = null
    private var lastUsageEventQueryAt = 0L
    private val processedUsageEvents = LinkedHashSet<String>()
    private var foregroundMonitorJob: Job? = null
    private var lastMonitorHeartbeatAt = 0L
    private var lastLoggedForegroundPackage: String? = null
    private var sensorRegistered = false
    private var lastActivityAt = 0L
    private var stillSince = 0L
    private var eyesClosedSince = 0L
    @Volatile private var faceDetectedInWindow = false
    @Volatile private var validFrameAnalyzedInWindow = false
    @Volatile private var eyeStateDetectedInWindow = false
    private var consecutiveOpenEyeChecks = 0
    private val triggered = AtomicBoolean(false)
    @Volatile private var screenInteractive = true

    private var detectionJob: Job? = null
    private var cameraJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var cameraWindowId = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    SleepMateLog.add(this@ProtectionService, "屏幕关闭广播；暂停前台应用监测")
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
                    SleepMateLog.add(this@ProtectionService, "屏幕亮起广播；检查并恢复前台应用监测")
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
        startRequestPending.set(false)
        if (intent?.action == ACTION_STOP) {
            setProtectionDesired(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !isProtectionDesired(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        SleepMateLog.add(this, "保护服务启动；action=${intent?.action ?: "系统重建"}；期望保持开启=${isProtectionDesired(this)}")
        startForeground(NOTIFICATION_ID, notification("睡眠保护已启动，正在等待监测条件"))
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
        if (!screenInteractive) {
            SleepMateLog.add(this, "未启动前台监测循环：屏幕处于关闭状态")
            return
        }
        if (foregroundMonitorJob?.isActive == true) return
        lastMonitorHeartbeatAt = SystemClock.elapsedRealtime()
        SleepMateLog.add(this, "前台应用监测循环已启动")
        foregroundMonitorJob = scope.launch {
            while (isActive) {
                try {
                    refreshForegroundState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SleepMateLog.add(this@ProtectionService, "前台监测循环异常，稍后继续：${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Foreground monitor iteration failed", e)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastMonitorHeartbeatAt >= MONITOR_HEARTBEAT_LOG_INTERVAL_MS) {
                    lastMonitorHeartbeatAt = now
                    SleepMateLog.add(this@ProtectionService, "前台监测心跳正常；目标应用前台=$targetAppForeground；最近识别=$lastForegroundPackage")
                }
                delay(if (targetAppForeground) ACTIVE_FOREGROUND_POLL_MS else IDLE_FOREGROUND_POLL_MS)
            }
        }
    }

    private fun stopForegroundMonitor() {
        if (foregroundMonitorJob != null) SleepMateLog.add(this, "前台应用监测循环已请求停止/暂停")
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
        val events = try {
            usageStatsManager.queryEvents(eventWindowStart, now)
        } catch (e: Exception) {
            SleepMateLog.add(this, "UsageStats前台事件查询失败：${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "UsageStats queryEvents failed", e)
            return
        }
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
        if (lastLoggedForegroundPackage != foreground) {
            SleepMateLog.add(this, "前台应用识别变化：${lastLoggedForegroundPackage ?: "无/未知"} → ${foreground ?: "无/未知"}；所选应用前台=${foreground in selected}")
            lastLoggedForegroundPackage = foreground
        }
        setTargetAppForeground(foreground in selected)
    }

    private fun setTargetAppForeground(active: Boolean) {
        if (targetAppForeground == active) return
        targetAppForeground = active
        SleepMateLog.add(this, if (active) "所选监测应用进入前台；恢复睡眠检测" else "已离开全部所选监测应用；暂停睡眠检测")
        if (active) {
            lastActivityAt = SystemClock.elapsedRealtime()
            stillSince = 0L
            triggered.set(false)
            if (screenInteractive) {
                registerMotionSensor()
                updateNotification("已进入所选应用；开始等待睡眠检测条件")
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
        updateNotification("屏幕已亮起且所选应用仍在前台；重新等待睡眠检测条件")
        scheduleDetection(NO_ACTIVITY_REQUIRED_MS)
    }

    private fun registerMotionSensor() {
        if (sensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // Sample at approximately 3.3 Hz without batching so movement callbacks are not delayed.
        sensorRegistered = sensorManager.registerListener(
            this,
            sensor,
            MOTION_SENSOR_SAMPLING_PERIOD_US,
            MOTION_SENSOR_MAX_REPORT_LATENCY_US
        )
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
                } else if (result.first && !result.third && screenInteractive && targetAppForeground &&
                    !triggered.get() && stillSince > 0L &&
                    SystemClock.elapsedRealtime() - stillSince >= PHONE_STILL_REQUIRED_MS) {
                    updateNotification("连续30秒检测到人脸但始终无法识别双眼；判定睡眠并执行保护")
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

    private suspend fun runCameraWindow(): Triple<Boolean, Boolean, Boolean> {
        faceDetectedInWindow = false
        validFrameAnalyzedInWindow = false
        eyeStateDetectedInWindow = false
        val windowId = ++cameraWindowId
        var analysis: ImageAnalysis? = null
        var provider: ProcessCameraProvider? = null
        try {
            val future = ProcessCameraProvider.getInstance(this)
            val resolvedProvider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                future.addListener({
                    try {
                        if (cont.isActive) cont.resume(future.get())
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, cameraExecutor)
            }
            provider = resolvedProvider
            cameraProvider = resolvedProvider
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis = imageAnalysis
            imageAnalysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy, windowId) }
            withContext(Dispatchers.Main) {
                resolvedProvider.unbindAll()
                resolvedProvider.bindToLifecycle(this@ProtectionService, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalysis)
            }
            delay(CAMERA_WINDOW_MS)
            return Triple(faceDetectedInWindow, validFrameAnalyzedInWindow, eyeStateDetectedInWindow)
        } finally {
            // Invalidate callbacks before detaching the analyzer and camera use case.
            if (cameraWindowId == windowId) cameraWindowId++
            withContext(NonCancellable + Dispatchers.Main) {
                analysis?.clearAnalyzer()
                provider?.unbindAll()
            }
        }
    }

    private fun getOrCreateFaceDetector(): FaceDetector = synchronized(detectorLock) {
        detector ?: run {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()
            FaceDetection.getClient(options).also { detector = it }
        }
    }

    private fun analyzeFrame(proxy: ImageProxy, windowId: Long) {
        if (windowId != cameraWindowId || triggered.get() || !screenInteractive || !targetAppForeground) {
            proxy.close()
            return
        }
        try {
            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            getOrCreateFaceDetector().process(image)
                .addOnSuccessListener(cameraExecutor) { faces ->
                    if (windowId != cameraWindowId || !screenInteractive || !targetAppForeground || triggered.get()) return@addOnSuccessListener
                    validFrameAnalyzedInWindow = true
                    if (faces.isNotEmpty()) faceDetectedInWindow = true
                    val face = faces.firstOrNull()
                    val left = face?.leftEyeOpenProbability
                    val right = face?.rightEyeOpenProbability
                    if (left != null && right != null) eyeStateDetectedInWindow = true
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
        } catch (_: Exception) {
            // InputImage creation or detector submission can fail before a Task exists.
            proxy.close()
        }
    }

    private suspend fun performProtection() {
        val currentJob = currentCoroutineContext()[Job]
        cameraJob?.takeIf { it !== currentJob }?.cancel()
        val activeWindowId = cameraWindowId
        cameraWindowId = activeWindowId + 1L
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        updateNotification("确认符合睡眠条件；正在锁定屏幕")
        detectionJob?.cancel()
        unregisterMotionSensor()
        withContext(NonCancellable + Dispatchers.Main) { cameraProvider?.unbindAll() }
        if (!screenInteractive || !power.isInteractive || !targetAppForeground) {
            triggered.set(false)
            return
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminActive = dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))
        val overlayAllowed = Settings.canDrawOverlays(this)
        Log.i(TAG, "HOME_DIAG: SYSTEM_ALERT_WINDOW granted=$overlayAllowed")
        SleepMateLog.add(this, "悬浮窗特殊权限（SYSTEM_ALERT_WINDOW）已授权=$overlayAllowed")
        Log.i(TAG, "HOME_DIAG: sleep confirmed; adminActive=$adminActive; currentForeground=$lastForegroundPackage; targetForeground=$targetAppForeground")
        SleepMateLog.add(this, "确认睡眠；设备管理员已启用=$adminActive；当前前台=$lastForegroundPackage；目标应用前台=$targetAppForeground")

        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val homeHandler = homeIntent.resolveActivity(packageManager)
        Log.i(TAG, "HOME_DIAG: resolvedHomeActivity=$homeHandler")
        SleepMateLog.add(this, "解析到的默认桌面=$homeHandler")

        try {
            if (homeHandler == null) {
                throw android.content.ActivityNotFoundException("No HOME activity resolved")
            }
            // Always return to the launcher first. Lock only after UsageStats confirms
            // that the launcher is actually foreground, so we do not lock over a
            // still-active media browser merely because startActivity returned.
            homeIntent.component = homeHandler
            updateNotification("已确认睡眠；正在先返回桌面")
            startActivity(homeIntent)
            Log.i(TAG, "HOME_DIAG: explicit launcher startActivity returned without exception")
            SleepMateLog.add(this, "已向默认桌面发送明确组件跳转请求；等待确认前台切换")

            var returnedToLauncher = false
            var previousCheckAt = 0L
            for (checkAtMs in listOf(500L, 1500L, 3000L)) {
                delay(checkAtMs - previousCheckAt)
                previousCheckAt = checkAtMs
                refreshForegroundState()
                returnedToLauncher = lastForegroundPackage == homeHandler.packageName
                val message = "发出HOME请求后${checkAtMs}毫秒：前台=$lastForegroundPackage；默认桌面=${homeHandler.packageName}；已回桌面=$returnedToLauncher；屏幕亮起=$screenInteractive"
                Log.i(TAG, "HOME_DIAG: $message")
                SleepMateLog.add(this, message)
                if (returnedToLauncher || !screenInteractive) break
            }

            if (!screenInteractive || !power.isInteractive) {
                SleepMateLog.add(this, "返回桌面等待期间屏幕已关闭；不再执行后续锁屏")
                return
            }

            if (!returnedToLauncher) {
                // Returning to the launcher is attempted first, but it must not block
                // the existing automatic-lock behavior: some apps pause playback
                // or stop foreground activity when the screen is locked.
                SleepMateLog.add(this, "3秒内未确认返回桌面；仍按原有流程继续执行自动锁屏")
                updateNotification("未确认返回桌面；继续执行自动锁屏")
            } else {
                SleepMateLog.add(this, "已确认返回桌面")
                updateNotification("已返回桌面")
            }

            if (adminActive) {
                SleepMateLog.add(this, if (returnedToLauncher)
                    "已返回桌面；现在执行设备管理员锁屏"
                else
                    "未确认返回桌面；仍执行设备管理员锁屏")
                updateNotification("正在锁定屏幕")
                dpm.lockNow()
            } else {
                updateNotification(if (returnedToLauncher)
                    "已返回桌面；设备管理员未启用，无法自动锁屏"
                else
                    "未确认返回桌面；设备管理员未启用，无法自动锁屏")
                SleepMateLog.add(this, if (returnedToLauncher)
                    "已确认返回桌面；设备管理员未启用，本次仅返回桌面"
                else
                    "未确认返回桌面；设备管理员未启用，本次无法自动锁屏")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HOME_DIAG: launcher-first protection failed: ${e.javaClass.simpleName}: ${e.message}", e)
            SleepMateLog.add(this, "先返回桌面流程异常：${e.javaClass.simpleName}: ${e.message}；本次不执行锁屏")
            updateNotification("返回桌面失败；本次暂不锁屏，请查看诊断日志")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onTaskRemoved(rootIntent: Intent?) {
        SleepMateLog.add(this, "SleepMate最近任务被划掉；服务仍在运行，检查前台监测状态")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SleepMateLog.add(this, "保护服务 onDestroy；服务进程内的前台监测循环将结束")
        running.value = false
        detectionJob?.cancel()
        cameraJob?.cancel()
        foregroundMonitorJob?.cancel()
        scope.cancel()
        unregisterMotionSensor()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        cameraWindowId++
        cameraProvider?.unbindAll()
        synchronized(detectorLock) {
            detector?.close()
            detector = null
        }
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
        private val startRequestPending = AtomicBoolean(false)
        private const val PREFERENCES_NAME = "sleepmate_protection_state"
        private const val KEY_PROTECTION_DESIRED = "protection_enabled"

        private fun isProtectionDesired(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PROTECTION_DESIRED, false)

        private fun setProtectionDesired(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PROTECTION_DESIRED, enabled)
                .apply()
        }

        const val ACTION_START = "com.sleepmate.app.START"
        const val ACTION_STOP = "com.sleepmate.app.STOP"
        const val ACTION_RECOVER = "com.sleepmate.app.RECOVER"
        const val ACTION_SELECTION_CHANGED = "com.sleepmate.app.SELECTION_CHANGED"
        private const val ACTIVE_FOREGROUND_POLL_MS = 2500L
        private const val IDLE_FOREGROUND_POLL_MS = 4500L
        private const val MOTION_SENSOR_SAMPLING_PERIOD_US = 300_000
        private const val MOTION_SENSOR_MAX_REPORT_LATENCY_US = 0
        private const val EYES_CLOSED_REQUIRED_MS = 3000L
        private const val PHONE_STILL_REQUIRED_MS = 8000L
        private const val NO_ACTIVITY_REQUIRED_MS = 5 * 60_000L
        private const val INITIAL_USAGE_EVENT_WINDOW_MS = 10_000L
        private const val USAGE_EVENT_OVERLAP_MS = 5_000L
        private const val MAX_REMEMBERED_USAGE_EVENTS = 128
        private const val MONITOR_HEARTBEAT_LOG_INTERVAL_MS = 60_000L
        private const val CAMERA_WINDOW_MS = 30_000L
        private const val MOVEMENT_THRESHOLD = 1.0f
        private const val TAG = "SleepMate"
        private const val CHANNEL_ID = "sleepguard_protection"
        private const val NOTIFICATION_ID = 1001

        private fun requestServiceStart(context: Context, action: String) {
            if (running.value || !startRequestPending.compareAndSet(false, true)) return
            val intent = Intent(context, ProtectionService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                startRequestPending.set(false)
                throw e
            }
        }

        // User-initiated start: persist the user's intent before starting protection.
        fun start(context: Context) {
            setProtectionDesired(context, true)
            requestServiceStart(context, ACTION_START)
        }

        // Recovery never changes the saved preference; it only reconciles a missing service.
        fun restoreIfEnabled(context: Context) {
            if (!isProtectionDesired(context) || running.value) return
            requestServiceStart(context, ACTION_RECOVER)
        }

        fun stop(context: Context) {
            setProtectionDesired(context, false)
            startRequestPending.set(false)
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}
