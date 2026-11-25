package com.sleepguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SleepGuardScreen(this) }
    }
}

private const val PREFS_NAME = "sleepguard_settings"
private const val KEY_MONITOR_SECONDS = "monitor_seconds"
private const val KEY_INTERVAL_MINUTES = "interval_minutes"

@Composable
private fun SleepGuardScreen(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var running by remember { mutableStateOf(false) }
    var adminActive by remember { mutableStateOf(isAdminActive(activity)) }
    var monitorSeconds by remember { mutableIntStateOf(prefs.getInt(KEY_MONITOR_SECONDS, 10).coerceIn(5, 15)) }
    var intervalMinutes by remember { mutableIntStateOf(prefs.getInt(KEY_INTERVAL_MINUTES, 5).coerceIn(1, 30)) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("SleepGuard", style = MaterialTheme.typography.headlineLarge)
                Text("智能防睡眠耗电保护 · 功能测试版")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (running) "服务状态：运行中" else "服务状态：未启动",
                            style = MaterialTheme.typography.titleMedium)
                        Text("当前版本用于测试后台服务和锁屏授权。摄像头睡眠识别、媒体自动暂停尚未接入。")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("监测参数", style = MaterialTheme.typography.titleMedium)
                        Text("单次监测时长：${monitorSeconds} 秒")
                        Slider(
                            value = monitorSeconds.toFloat(),
                            onValueChange = {
                                monitorSeconds = it.toInt().coerceIn(5, 15)
                                prefs.edit().putInt(KEY_MONITOR_SECONDS, monitorSeconds).apply()
                            },
                            valueRange = 5f..15f,
                            steps = 9,
                            enabled = !running
                        )
                        Text("两次监测间隔：${intervalMinutes} 分钟")
                        Slider(
                            value = intervalMinutes.toFloat(),
                            onValueChange = {
                                intervalMinutes = it.toInt().coerceIn(1, 30)
                                prefs.edit().putInt(KEY_INTERVAL_MINUTES, intervalMinutes).apply()
                            },
                            valueRange = 1f..30f,
                            steps = 28,
                            enabled = !running
                        )
                        Text("测试期间请先停止服务再调整参数；重新启动后生效。")
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("自动锁屏授权", style = MaterialTheme.typography.titleMedium)
                        Text(if (adminActive) "设备管理员：已激活" else "设备管理员：未激活")
                        Button(onClick = {
                            val component = ComponentName(activity, SleepGuardAdminReceiver::class.java)
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "用于在你确认开启保护后锁定手机屏幕。")
                            }
                            activity.startActivity(intent)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (adminActive) "重新查看授权状态" else "激活锁屏权限")
                        }
                        OutlinedButton(onClick = {
                            adminActive = isAdminActive(activity)
                        }, modifier = Modifier.fillMaxWidth()) { Text("刷新授权状态") }
                    }
                }
                Button(
                    onClick = {
                        ProtectionService.start(activity)
                        running = true
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("启动基础保护服务") }

                OutlinedButton(
                    onClick = {
                        ProtectionService.stop(activity)
                        running = false
                    },
                    enabled = running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("停止服务") }

                OutlinedButton(
                    onClick = {
                        if (isAdminActive(activity)) {
                            val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            dpm.lockNow()
                        } else {
                            adminActive = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("测试锁屏（请先保存工作）") }

                Spacer(Modifier.height(4.dp))
                Text("小米手机提示：如服务容易被系统停止，可在系统设置中允许自启动并将电池策略设为不限制。")
            }
        }
    }
}

private fun isAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(ComponentName(context, SleepGuardAdminReceiver::class.java))
}
