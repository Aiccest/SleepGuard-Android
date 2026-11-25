# 用手机通过 GitHub Actions 构建 APK

本仓库是 SleepGuard V1.0 基础测试工程，并附带云端构建工作流。

## 构建步骤
1. 在手机浏览器登录 GitHub，创建一个新仓库，建议设为 Private。
2. 将本 ZIP 解压到手机。
3. 在 GitHub 仓库网页选择 Add file → Upload files，将解压后的**所有项目文件和 .github 文件夹内容**上传到仓库根目录，然后 Commit changes。
   - 确保 `.github/workflows/build.yml` 路径正确；手机网页若隐藏点号文件夹，可先在文件管理器开启显示隐藏文件。
4. 打开仓库的 Actions 页面，选择 `Build SleepGuard debug APK`，点击 Run workflow；或者提交到 main 后自动构建。
5. 构建成功后打开对应运行记录，在 Artifacts 区域下载 `SleepGuard-debug-apk`，解压得到 `app-debug.apk`。
6. 在小米手机上点击 APK 安装。若提示，允许浏览器/文件管理器安装未知应用。

## 重要
- 这是 debug 测试包，不是正式发布签名包。
- 代码目前只包含基础服务、设备管理员激活入口和手动锁屏测试。
- 摄像头、传感器融合、自动暂停媒体、自动睡眠识别尚未实现。
- 首次构建需要 GitHub Actions 在线下载 Gradle 和 Android SDK。
- 若上传 ZIP 本身而不是解压后的文件，工作流无法找到根目录的 `settings.gradle.kts`。
