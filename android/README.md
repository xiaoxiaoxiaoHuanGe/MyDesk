# Android 客户端开发

Kotlin / Jetpack Compose 原生 APP。Android 8.0+，compileSdk / targetSdk 36，JDK 17+，Android SDK 36 与 Build Tools 36.0.0，Gradle Wrapper 8.13。

## 本机 IP

工作台在“网络”卡片下方独立显示“本机 IP”。原网络卡片检测服务端；本机 IP 从当前 Android 设备直接访问 ip111.cn 首页、us.ip111.cn/ip.php 和 sspanel.net/ip.php，分别展示国内、国外及 Google 出口的公网 IP 与归属地。

每次进入 App 前台并行查询一次，单个出口最多等待 10 秒，超时显示“超时”，其他请求或数据格式错误显示“查询失败”。没有轮询、后台查询或自动重试；切换页签、滚动与屏幕旋转不触发额外请求。卡片提供手动刷新，请求中不可重复刷新，离开前台会取消未完成请求。查询不经过 MyDesk 服务端，也不附带 MyDesk 登录凭据；界面只显示文本，不执行检测页面的脚本。结果可用性及归属地取决于第三方检测服务和当前设备的网络分流。

## 调试

首次使用 scripts/start_local.py 生成新的本地测试 CA。Windows 本地脚本使用自己指定的 SDK 和 JDK 路径：

```powershell
./scripts/Build-AndroidLocal.ps1 -SdkPath '<SDK目录>' -JavaPath '<JDK目录>'
```

测试也可单独使用 Gradle：生成临时测试 CA 并将公开 CA 复制到 app/src/debug/res/raw/mydesk_local_ca.crt 后，运行 `./gradlew :app:testDebugUnitTest`。CI 示例见 .github/workflows/ci.yml。

## 正式签名

Windows 初始化及构建：

```powershell
./scripts/Initialize-AndroidReleaseKey.ps1 -JavaPath '<JDK目录>'
./scripts/Build-AndroidRelease.ps1 -SdkPath '<SDK目录>' -JavaPath '<JDK目录>'
```

私钥保存于 .local/android/release，密码使用当前 Windows 用户的 DPAPI 加密。已有签名身份不自动替换。迁移电脑前需单独安全备份私钥及可恢复密码，单独复制 DPAPI 文件不能保证在另一用户下可解密。

其他平台可自行创建 keystore，通过 MYDESK_RELEASE_KEYSTORE、MYDESK_RELEASE_STORE_PASSWORD、MYDESK_RELEASE_KEY_ALIAS、MYDESK_RELEASE_KEY_PASSWORD 提供给 Gradle，运行 `./gradlew :app:assembleRelease`。不要将密码写入源码、GitHub Actions 或公开命令日志。

正式版启用 R8 和资源压缩，验证系统 CA 与 HTTPS 主机名，使用稳定正式签名。调试 CA 仅在 debug source set 中使用。未配置正式签名时 release 构建会停止，不回退到调试密钥。

维护者发布的正式 APK 与你的自行构建版本可能签名不同；签名不同不能覆盖安装。先同步和备份再切换版本。
