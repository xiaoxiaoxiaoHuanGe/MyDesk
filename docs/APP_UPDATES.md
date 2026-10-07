# Android 应用内更新

1.3.0（versionCode 9）增加设置 → 应用更新。首次带此功能的 APK 仍需手动安装；后续更高 versionCode、同签名的 APK 才能通过应用内更新安装。用户可稍后处理，安装由 Android 系统确认，不做后台下载或静默安装。

登录后前台联网会检查更新，成功检查间隔六小时，失败指数退避。手动检查不受间隔限制。同一会话同版本只提示一次。debug 客户端只查询 debug，release 客户端只查询 release；以 versionCode 判断新旧，并检查 Android 系统要求。

下载需要当前 MyDesk 会话，禁止重定向及跨域地址。客户端按流写入私有临时文件，最大 200 MiB，检查可用空间、字节数、SHA-256、真实包名/版本/minSdk 和当前安装证书。只有校验通过才成为可安装文件。旋转或离开设置页不会产生第二个任务；退出、切换服务器、会话失效会取消任务并删除文件。进程中断的临时文件在下次启动清理，重新下载。授予“安装未知应用”权限后返回，再点击安装即可沿用已下载文件。

FileProvider 仅暴露 `files/app-updates/`，授予系统安装器临时读取权限。更新完成后可自行重新打开 APP。服务器没有发布时返回 `available:false`；旧服务器 404 显示“服务器暂未提供应用更新”。

## 发布准备（维护者本地）

继续使用既有正式签名身份和 Windows DPAPI 密码：

```powershell
./scripts/Build-AndroidRelease.ps1 -SdkPath '<已有SDK目录>' -JavaPath '<已有JDK目录>'
```

不要重新运行签名初始化，不要把私钥/DPAPI 文件上传云端。两个本机构建脚本均从 Gradle `output-metadata.json` 读取版本名，不再写死 APK 文件名。确认现装包是 debug 还是 release，并用 `apksigner verify --print-certs` 比较证书；签名不同不能覆盖安装，不要卸载手机 APP 来通过验收。

跨平台准备已签名 APK（只生成文件，绝不上传、部署或签名）：

```sh
python scripts/prepare_android_update.py path/to/already-signed.apk \
  --output ./app-updates --channel release \
  --notes-file ./release-notes.txt \
  --certificate-sha256 '<既有正式渠道证书SHA256>' \
  --build-tools '<SDK>/build-tools/36.0.0'
```

Windows PowerShell 可将命令写成单行。脚本使用 SDK aapt 和 apksigner，从已复制到临时目录的真实 APK 读取包名、版本、系统要求，验证签名及渠道预期证书，拒绝 release 可调试包；不接受操作者手填版本号。预期证书是公开指纹，不是私钥。debug 验收必须指定 debug 证书并使用 debug 渠道，不能把云端 debug 包发布到 release。

目录格式：

```text
app-updates/
  release.json                 # 已完成发布的当前版本指针
  debug.json                   # 可选，独立渠道
  artifacts/<sha256>.apk       # 不可变 APK
  artifacts/<sha256>.json      # 不可变登记元数据
```

脚本在目标文件系统临时目录完成处理，APK 和登记文件先就位，最后原子切换渠道清单；发布失败不会改变原指针。`.publish.lock` 防止并发发布。异常中断后先确认没有发布进程，再手动移除残留锁；不要修改登记过的 APK。旧产物不自动删除，建议保留至少 30 天，或确认旧下载会话已结束后再维护。不要给原文件名覆盖不同内容。

## 服务端配置与部署顺序

`GET /api/app-update?channel=release|debug` 和 `GET /api/app-update/artifacts/<sha256>.apk` 都要求登录；路由只允许登记的不可变产物，无任意文件路径或公网下载行为。schema、渠道、数值、摘要、包名、登记一致性和文件大小均校验；不完整文件不对客户端可见。

默认目录是服务数据目录内的 `app-updates`，可用 `MYDESK_UPDATE_DIR` 改为独立持久目录。两份 Compose 模板使用宿主 `./app-updates` → 容器 `/app-updates:ro`，环境变量设置为 `/app-updates`。宿主目录先创建；产物需允许容器 UID 10001 读取。正式 HTTPS、现有 TLS 校验和会话必须保留。

维护者先备份并部署向后兼容后端，再用原签名构建和发布新版 APK。切换数据目录前做好完整 SQLite 在线备份（SQLite backup API），不要只复制运行中的主文件而遗漏 WAL。保留旧镜像与原清单；产物目录另做备份。数据库变更仅新增表/索引，新随机字段存于既有 JSON；旧后端不支持随机与收件箱操作，回退前停止新随机轮次并保留新数据库备份。回退清单只影响后续检查，不影响已下载文件和旧产物链接。Android 低 versionCode 降级通常被系统拒绝，故客户端回退应发布同签名、更高 versionCode 的修复版本，不能卸载或换密钥。

## 本地及真机验收

- 原同签名 1.2.1 → 1.3.0 首次手动覆盖安装，确认会话、服务器、外观、提醒、缓存数据库、离线待同步操作保持。
- 再构建同签名、更高 versionCode 测试版本，例如 10；用脚本准备并由维护者部署到对应渠道。确认自动检查/手动检查、稍后处理、大小、更新说明和进度。
- 下载时旋转、切页、重复点击；离线失败后重试；退出及更换服务器后旧文件不能安装。
- 未授权安装来源时先进入系统授权，返回无需重新下载；调起安装器并覆盖更新，随后手动打开，确认数据保持。
- 同版本/旧版本不提示，旧后端/无发布/离线不影响既有工作；签名不符、跨域、重定向、截断、摘要/包名/版本/minSdk 错误不进入待安装状态。

云端构建输出是独立 debug 签名，不代表已安装手机，也不能直接当作已有手机的覆盖升级包。真实系统安装器、磁盘耗尽、安装权限返回和数据保持仍需真机验收。
