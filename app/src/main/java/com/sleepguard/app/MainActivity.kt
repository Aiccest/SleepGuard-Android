package com.sleepguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InstalledMediaApp(
    val packageName: String,
    val label: String
)

class MainActivity : ComponentActivity() {
    var cameraGranted by mutableStateOf(false)
    var adminActive by mutableStateOf(false)
    var listenerEnabled by mutableStateOf(false)
    var installedMediaApps by mutableStateOf<List<InstalledMediaApp>>(emptyList())

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
        cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminActive = dpm.isAdminActive(ComponentName(this, SleepGuardAdminReceiver::class.java))
        listenerEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        loadLaunchableApps()
    }

    @Suppress("DEPRECATION")
    private fun loadLaunchableApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        installedMediaApps = packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val appPackage = info.activityInfo?.packageName ?: return@mapNotNull null
                if (appPackage == packageName) return@mapNotNull null
                InstalledMediaApp(
                    packageName = appPackage,
                    label = info.loadLabel(packageManager)?.toString()?.ifBlank { appPackage } ?: appPackage
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun requestCamera() = cameraPermission.launch(android.Manifest.permission.CAMERA)
    fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

@Composable
private fun SleepGuardScreen(activity: MainActivity) {
    var running by remember { mutableStateOf(false) }
    var selectedPackages by remember {
        mutableStateOf(MediaAppPreferences.getSelectedPackages(activity))
    }

    fun updateSelection(updated: Set<String>) {
        selectedPackages = updated
        MediaAppPreferences.saveSelectedPackages(activity, updated)
        if (running) {
            activity.startService(
                Intent(activity, ProtectionService::class.java)
                    .setAction(ProtectionService.ACTION_SELECTION_CHANGED)
            )
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SleepGuard", style = MaterialTheme.typography.headlineLarge)
                Text("智能防睡眠 · 低功耗保护")

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("保护状态", style = MaterialTheme.typography.titleMedium)
                        Text(if (running) "运行中：仅监测已选择的媒体应用" else "已停止")
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
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("媒体应用管理", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "只保护你勾选的应用。未勾选的应用不会触发睡眠检测，也不会被 SleepGuard 暂停。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { updateSelection(activity.installedMediaApps.map { it.packageName }.toSet()) },
                                modifier = Modifier.weight(1f)
                            ) { Text("全选") }
                            TextButton(
                                onClick = { updateSelection(emptySet()) },
                                modifier = Modifier.weight(1f)
                            ) { Text("全不选") }
                            Text(
                                "已选 ${selectedPackages.size} 个",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                        if (activity.installedMediaApps.isEmpty()) {
                            Text("没有读取到可启动的应用。请稍后刷新。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            activity.installedMediaApps.forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(
                                        checked = app.packageName in selectedPackages,
                                        onCheckedChange = { checked ->
                                            val updated = selectedPackages.toMutableSet()
                                            if (checked) updated.add(app.packageName) else updated.remove(app.packageName)
                                            updateSelection(updated)
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { activity.refreshPermissions() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("刷新应用列表") }
                        if (selectedPackages.isEmpty()) {
                            Text("当前没有选择任何应用，因此不会启动媒体睡眠检测。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
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
                        Text("• 只有已勾选应用正在播放媒体时，才进入睡眠检测流程。")
                        Text("• 未勾选应用的播放状态不会触发检测，也不会被暂停。")
                        Text("• 设备连续无明显活动5分钟后才进入检测准备。")
                        Text("• 手机静止确认8秒后，摄像头才短暂启动，单次最长30秒。")
                        Text("• 未发现持续闭眼时，复查间隔依次为15、20、25、最多30分钟。")
                        Text("• 检测画面仅在本机处理，不上传。")
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

                Text("提示：应用列表显示具有桌面启动入口的已安装应用。个别没有标准媒体会话的应用，即使勾选也可能无法被识别。小米系统建议为 SleepGuard 开启自启动，并将电池策略设为无限制。")
            }
        }
    }
}
