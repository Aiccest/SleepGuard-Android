# SleepGuard V1.0（基础工程）

## 当前实现
- Kotlin + Jetpack Compose 原生 Android 工程
- Android 9+，针对 Android 13 测试
- 前台服务启动/停止与常驻通知
- 设备管理员激活入口
- 用户手动测试锁屏

## 当前明确未实现
- 摄像头检测、人脸/眼睛识别
- 加速度/陀螺仪融合判断
- 每20分钟自动启动摄像头
- 自动暂停第三方媒体
- 自动睡眠判定及自动锁屏

当前服务里的20分钟循环只是调度占位，不会自动锁屏。这样可避免检测尚未实现时误锁手机。

## 编译环境
- JDK 17
- Android SDK 35（compileSdk）
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- Gradle 8.9

## 在手机上尝试编译
可使用支持现代 Android Gradle Plugin 的 Android IDE/构建环境导入本目录。
若 IDE 不支持 AGP 8.7.3、Gradle 8.9 或 JDK 17，需要升级构建环境；不要随意降低版本后直接发布。
首次同步需要联网下载 Gradle、Android Gradle Plugin 和依赖。

## 小米/Redmi Android 13 测试
1. 安装后打开 SleepGuard。
2. 按提示允许通知。
3. 点击“激活锁屏权限”，在系统页面确认激活。
4. 返回 App，刷新授权状态。
5. 点击“测试锁屏”验证设备管理员锁屏。
6. 点击“启动基础保护服务”，确认通知栏出现 SleepGuard 常驻通知。
7. 点击“停止服务”验证可正常停止。

小米后台设置建议：
- 设置 → 应用设置 → 应用管理 → SleepGuard → 自启动（如系统提供）允许。
- 电池相关设置中将 SleepGuard 设为不限制（若有该选项）。
- 最近任务中可按需锁定应用，但这不是可靠的系统保证。

## 安全说明
设备管理员权限只用于 force-lock。卸载前需要先在系统设置中停用 SleepGuard 的设备管理员权限。
