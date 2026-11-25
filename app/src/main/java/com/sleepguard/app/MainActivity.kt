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

@Composable
private fun SleepGuardScreen(activity: MainActivity) {
    var running by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SleepGuard", style = MaterialTheme.typography.headlineLarge)
                Text("智能防睡眠 · 低功耗保护")

                // 保护状态放在权限状态之前，打开应用先看到运行状态。
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("保护状态", style = MaterialTheme.typography.titleMedium)
                        Text(if (running) "运行中：等待媒体播放与设备静止条件" else "已停止")
                        Button(
                            onClick = {
                                activity.refreshPermissions()
                                if (!activity.cameraGranted) activity.requestCamera()
                                else if (!activity.listenerEnabled) activity.openNotificationAccess()
                                else {
                                    ProtectionService.start(activity)
                                    running = true
                                }
                            },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("启动智能保护") }
                        OutlinedButton(
                            onClick = {
                                ProtectionService.stop(activity)
                                running = false
                            },
                            enabled = running,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("停止保护") }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("权限状态", style = MaterialTheme.typography.titleMedium)
                        Text("摄像头：${if (activity.cameraGranted) "已授权" else "未授权"}")
                        Text("媒体通知访问：${if (activity.listenerEnabled) "已授权" else "未授权（无法判断媒体播放状态）"}")
                        Text("设备管理员：${if (activity.adminActive) "已激活" else "未激活（无法自动锁屏）"}")
                        OutlinedButton(onClick = { activity.requestCamera() }, Modifier.fillMaxWidth()) {
                            Text("授权摄像头")
                        }
                        OutlinedButton(onClick = { activity.openNotificationAccess() }, Modifier.fillMaxWidth()) {
                            Text("开启媒体通知访问")
                        }
                        OutlinedButton(onClick = {
                            val component = ComponentName(activity, SleepGuardAdminReceiver::class.java)
                            activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                                putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "允许 SleepGuard 在确认符合睡眠条件后锁定屏幕。"
                                )
                            })
                        }, Modifier.fillMaxWidth()) {
                            Text("激活自动锁屏权限")
                        }
                        OutlinedButton(onClick = { activity.refreshPermissions() }, Modifier.fillMaxWidth()) {
                            Text("刷新权限状态")
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("低功耗监测策略", style = MaterialTheme.typography.titleMedium)
                        Text("• 没有媒体播放时不启动摄像头。")
                        Text("• 媒体播放期间，设备连续无明显活动5分钟后才进入检测准备。")
                        Text("• 手机静止确认8秒后，摄像头才短暂启动，单次最长30秒。")
                        Text("• 未发现持续闭眼时，复查间隔依次为15、20、25、最多30分钟。")
                        Text("• 检测到设备活动或媒体停止时，取消本轮候选并重新计时。")
                        Text("• 为兼顾省电，使用媒体会话和设备运动作为活动信号；Android普通应用无法可靠读取其他应用内的全部触摸事件。")
                        Text("检测画面仅在本机处理，不上传。")
                    }
                }

                OutlinedButton(
                    onClick = {
                        activity.refreshPermissions()
                        if (activity.adminActive) {
                            (activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("测试锁屏") }

                Text("提示：小米系统建议为 SleepGuard 开启自启动，并将电池策略设为无限制。系统可能限制后台摄像头启动；应用会在允许时再尝试。")
            }
        }
    }
}
