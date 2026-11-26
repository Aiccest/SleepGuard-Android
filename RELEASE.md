# SleepMate 构建与发布说明

## 构建产物

GitHub Actions 的 **Build SleepMate APKs** 工作流会构建：

- `app-debug.apk`：调试版本。
- `app-release.apk`：Release 变体。
- `SleepMate-release-apk.zip`：将 Release APK 放入 ZIP，便于下载和分发。

GitHub Actions 上传的 artifact 本身也会打包保存。ZIP 只是便于单独下载的外层压缩包，不会改变 APK 内部内容。

## Release 压缩策略

当前 Release 暂不启用 R8 代码混淆和资源收缩（`isMinifyEnabled = false`、`isShrinkResources = false`）。项目使用 Jetpack Compose、CameraX 和 Google ML Kit；在缺少完整设备回归与保留规则验证前，不贸然开启压缩，以免引入运行时崩溃或功能缺失。完成验证后再单独评估启用。

## 配置正式签名

签名密钥不得提交到 Git 仓库。维护者在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置以下 Repository secrets：

| Secret 名称 | 内容 |
|---|---|
| `SLEEPMATE_KEYSTORE_BASE64` | Android keystore 文件的 Base64 编码 |
| `SLEEPMATE_STORE_PASSWORD` | keystore 密码 |
| `SLEEPMATE_KEY_ALIAS` | 签名 key alias |
| `SLEEPMATE_KEY_PASSWORD` | 签名 key 密码 |

配置完整后，工作流会在 runner 临时目录还原 keystore，并使用该密钥签署 Release APK。不要把 keystore、密码或 Base64 内容写入源码、提交记录、Issue 或公开日志。

若未配置完整签名信息，工作流仍会构建 Release APK，但产物为**未签名 APK，仅供开发测试**，不能作为正式发布包。正式发布前必须确认 APK 已使用项目长期保管的正式密钥签名，并妥善备份密钥。

## 版本发布注意事项

- 更新 `versionCode` 和 `versionName` 后再发布新版本。
- 已发布应用必须持续使用同一签名密钥；更换密钥可能导致用户无法直接覆盖升级。
- 当前工作流产物保留 14 天，属于 CI 构建产物，不等于正式 Release。
- 正式发布前仍需完成目标 Android 版本和厂商设备的安装、升级、权限、摄像头、前台服务及锁屏回归测试。
