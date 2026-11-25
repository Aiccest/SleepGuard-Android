package com.sleepguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    var cameraGranted by mutableStateOf(false)
    var adminActive by mutableStateOf(false)
    var listenerEnabled by mutableStateOf(false)
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent { SleepGuardScreen(this) }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    fun refreshPermissions() {
        cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminActive = dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))
        listenerEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
    }

    fun requestCamera() = cameraPermission.launch(android.Manifest.permission.CAMERA)
    fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

private const val PREFS = "sleepguard_settings"
private const val KEY_MONITOR = "monitor_seconds"
private const val KEY_INTERVAL = "interval_minutes"

@Composable
private fun SleepGuardScreen(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var monitor by remember { mutableIntStateOf(prefs.getInt(KEY_MONITOR, 10).coerceIn(5, 15)) }
    var interval by remember { mutableIntStateOf(prefs.getInt(KEY_INTERVAL, 5).coerceIn(1, 30)) }
    var running by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SleepGuard", style = MaterialTheme.typography.headlineLarge)
                Text("智能防睡眠耗电管家 · 完整功能测试版")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("权限状态", style = MaterialTheme.typography.titleMedium)
                        Text("摄像头：${if (activity.cameraGranted) "已授权" else "未授权"}")
                        Text("媒体通知访问：${if (activity.listenerEnabled) "已授权" else "未授权（不能自动暂停媒体）"}")
                        Text("设备管理员：${if (activity.adminActive) "已激活" else "未激活（不能自动锁屏）"}")
                        OutlinedButton(onClick = { activity.requestCamera() }, Modifier.fillMaxWidth()) { Text("授权摄像头") }
                        OutlinedButton(onClick = { activity.openNotificationAccess() }, Modifier.fillMaxWidth()) { Text("开启媒体通知访问") }
                        OutlinedButton(onClick = {
                            val c = ComponentName(activity, SleepGuardAdminReceiver::class.java)
                            activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, c)
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "允许 SleepGuard 在检测到符合条件的睡眠状态后锁定屏幕。")
                            })
                        }, Modifier.fillMaxWidth()) { Text("激活自动锁屏权限") }
                        OutlinedButton(onClick = { activity.refreshPermissions() }, Modifier.fillMaxWidth()) { Text("刷新权限状态") }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("监测设置", style = MaterialTheme.typography.titleMedium)
                        Text("单次监测时长：$monitor 秒")
                        Slider(value = monitor.toFloat(), onValueChange = {
                            monitor = it.toInt().coerceIn(5, 15)
                            prefs.edit().putInt(KEY_MONITOR, monitor).apply()
                        }, valueRange = 5f..15f, steps = 9, enabled = !running)
                        Text("两次监测间隔：$interval 分钟")
                        Slider(value = interval.toFloat(), onValueChange = {
                            interval = it.toInt().coerceIn(1, 30)
                            prefs.edit().putInt(KEY_INTERVAL, interval).apply()
                        }, valueRange = 1f..30f, steps = 28, enabled = !running)
                        Text("运行时会按设定间隔启动前置摄像头进行短时检测。检测画面只在本机处理，不上传。")
                    }
                }
                Text(if (running) "保护状态：运行中" else "保护状态：已停止", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    activity.refreshPermissions()
                    if (!activity.cameraGranted) activity.requestCamera()
                    else {
                        ProtectionService.start(activity)
                        running = true
                    }
                }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("启动智能保护") }
                OutlinedButton(onClick = {
                    ProtectionService.stop(activity)
                    running = false
                }, enabled = running, modifier = Modifier.fillMaxWidth()) { Text("停止保护") }
                OutlinedButton(onClick = {
                    if (activity.adminActive) {
                        (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("测试锁屏") }
                Text("提示：小米系统请为 SleepGuard 开启自启动，并将电池省电策略设为无限制。锁屏后系统可能暂停摄像头；本应用会在下一轮重新尝试。")
            }
        }
    }
}
