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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

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

@Composable
private fun SleepGuardScreen(activity: MainActivity) {
    var running by remember { mutableStateOf(false) }
    var adminActive by remember { mutableStateOf(isAdminActive(activity)) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("SleepGuard", style = MaterialTheme.typography.headlineLarge)
                Text("智能防睡眠耗电保护 · V1.0 基础测试版")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (running) "服务状态：运行中" else "服务状态：未启动",
                            style = MaterialTheme.typography.titleMedium)
                        Text("当前版本只验证后台服务和锁屏授权，不会自动判断睡眠，也不会自动锁屏。")
                        Text("后续版本接入摄像头与传感器后，才会启用自动保护。")
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
                Text("小米手机提示：安装后如服务容易被系统停止，可在系统设置中允许自启动并将电池策略设为不限制。不要为此关闭系统安全保护。")
            }
        }
    }
}

private fun isAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(ComponentName(context, SleepGuardAdminReceiver::class.java))
}
