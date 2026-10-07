# 三项功能云端交付记录

日期：2026-10-07。初始分支 `work`，基线 `3beee7d`，初始工作区干净。Python/aiohttp + SQLite、Kotlin/Compose、Web 的现有结构与上传计划一致；初始 Android 版本 1.2.1（8），本次升为 1.3.0（9）。没有改动正式签名配置或私钥，没有部署生产环境、连接真实 CPE、投递真实飞书消息或安装手机。

## 已交付

| 功能 | 后端 | Android / Web | 核心验证 |
| --- | --- | --- | --- |
| 随机步数 | 严格 0/10 参数、可注入随机源、同事务保存下一值、慢速跨日校验、30000 封顶与旧字段默认固定 | 开关、实际增量范围、预计次数/耗时/末值范围、预设字段往返、历史和能力提示 | 固定回归、上下界/中间/重复、重启/重复确认/失败恢复、封顶、最慢估算 |
| 应用内更新 | 认证清单/APK、不可变登记、schema/path/size 验证、独立持久目录 | 按渠道与 versionCode 检查、六小时/退避/单次提示、流式下载、摘要/包信息/签名验证、系统授权和安装 | 无发布/旧后端、重复点击、重定向/JSON/大小/摘要拒绝、错误包信息/签名/系统要求、真实 debug 发布准备及流式下载 |
| CPE 收件箱 | 来源密钥摘要、来源管理、文本持久化、并发唯一约束、严格成功 ACK、限流/保留/分页/已读、原子派发待办 | 最近三条、全文、来源筛选、分页与已读、离线缓存/已读队列、外部通知渠道及点击定位；Web 同步管理 | 完整计划样本、并发幂等、密钥/停用/删除、ACK/大小/CSRF、保留分页、失败推送/重启复用事件、客户端缓存隔离与数据库迁移 |

发送方 64 位幂等 ID 与 MyDesk 32 位事件 ID 独立；原 webhook 和业务任务不复用。原生推送继承设备/session/registration/expiry 校验与既有 outbox。数据库收件箱待办保证不因最近三条窗口漏消息，接收成功不等待网络推送。

后端只新增兼容表/索引；随机字段保存在既有参数 JSON。Android Room 从版本 1 迁移至 2，只新增缓存/已读队列表，实测旧提醒、离线操作、已投递记录保持。新增服务端能力标志避免新 APP 对旧后端宣称随机已有效。

## 实际验证结果

| 命令/检查 | 结果 |
| --- | --- |
| `.local/venv/bin/python -m unittest discover -s tests -v` | 202 项：199 通过、3 Windows DPAPI/PowerShell 正式构建测试在 Linux 跳过，无失败 |
| `node --test tests/frontend.test.mjs tests/standalone.test.mjs tests/step-plan.test.mjs tests/inbox.test.mjs` | 16 项全部通过 |
| `./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process --max-workers=2` | 134 项全部通过，debug APK 构建成功 |
| `docker compose -f deploy/compose.yaml config --quiet` | 通过 |
| `docker compose -f deploy/compose.server.yaml config --quiet` | 通过 |
| `python scripts/build_release.py` | 白名单源码压缩包生成成功，不包括 .local、签名私钥、Firebase 配置或运行数据库 |
| 实际 debug APK → `prepare_android_update.py` | aapt/apksigner 验证通过，从真实产物读取版本 9、包名、minSdk 26、19152701 字节、SHA-256 |
| 实际 debug APK 标为 release | 明确拒绝，没有生成 release.json |
| 实际 APK 经本地测试服务器鉴权并流式下载 | 未登录 401；登录后 200；字节数与 SHA-256 全部一致 |
| `git diff --check` | 通过 |

Android 使用项目 Wrapper 8.13、Temurin JDK 17.0.16、Android SDK 36/Build Tools 36.0.0。云端原来只有 Java 运行时，已在工作目录补齐工具；网络沿用代理与 CA 验证，没有关闭 TLS。Python 依赖在独立 `.local/venv`，未改系统环境。Robolectric/Compose 覆盖旧功能和新增输入、随机能力、更新控制器、下载失败、点击导航、离线队列、迁移；没有把自动化当作真实系统安装器或手机锁屏验收。

构建仍有既有 Kotlin/Android 弃用提示，以及新页面使用兼容 ClipboardManager 的弃用提示，不影响测试。没有关闭测试或签名检查来通过构建。

## 产物

- `dist/MyDesk-1.3.0-cloud-debug.apk`，独立云端 debug 签名，仅用于开发验收。
- `dist/MyDesk-1.3.0-source.zip`，后端、Web、Android 源码、测试、发布脚本及交付文档。
- `dist/app-update-debug/`，真实 debug APK 对应不可变登记/清单，供本地模拟发布验证；没有对外发布。
- Gradle 测试报告：`android/app/build/reports/tests/testDebugUnitTest/index.html`。

APK SHA-256：`1aab2116410f272b429ebc55c81e4462c15d7d9bb06e02e4c8bc3ce5be0cf6ce`。若后续重新构建，重新核对摘要和生成清单；不要直接手改版本元数据。

## 未完成的外部验收与接续顺序

1. **协议与设备状态**：初始分支没有两份文档，用户先后补充了功能计划和完整 CPE 协议；均已按原样保存到指定路径。接收端契约与原文一致，自动化直接读取完整协议样本，并验证丢失 ACK 后重试。原文所述发送端/手机状态保留未改写，真实设备状态仍需本地核实。
2. **正式签名/Windows**：在维护者本机用原有 DPAPI/正式密钥构建。核对现装包渠道和证书，禁止换密钥、卸载或清空数据。三项 Windows 构建脚本测试需本地运行。
3. **维护者部署**：先使用 SQLite 在线备份备份完整数据，保留旧镜像和数据；部署新后端、独立只读更新挂载和可信 HTTPS。云端未执行生产部署。
4. **首次新 APP**：用原签名 1.3.0 覆盖安装，确认服务器/会话/设置/提醒/数据库/离线操作保持。后续使用更高 versionCode 同签名测试包验收应用内更新。
5. **安装器/磁盘/生命周期**：真机检查旋转/切页、未知来源授权返回、安装确认、安装后重新打开与数据保持；故障注入磁盘不足/不可写、进程中断和断网。自动化验证了元数据/下载失败拒绝安装状态，未在真实设备耗尽磁盘。
6. **CPE/代理**：创建来源复制地址、按实际启用时间填写 UTC enabled_at，核对代理域名/端口白名单和第二目标程序是否已部署。双发、不可达补发、ACK 丢失重试、密钥 URL 切换期间短信都需要实际联调。
7. **Firebase/手机**：维护者提供真实 Android Firebase 配置和后端凭据后本地构建/部署。真机验证后台/锁屏通知、权限和厂商策略、点击定位、过期/已清理通知、注销后旧通知处理。没有真实凭据及手机，本次没有宣称 FCM 或锁屏送达。

具体部署准备、数据备份/回退和覆盖升级见 [APP_UPDATES.md](APP_UPDATES.md)；CPE 接续、日志脱敏与协议见 [CPE_INBOX.md](CPE_INBOX.md)。现有配置导出不包含通知来源/消息、步数轮次或账户数据库，迁移和回退应备份完整服务数据目录与更新产物目录。Android 降级不要卸载，应发布同签名、更高 versionCode 的修复版本。

## 2026-10-07 收件箱浏览器回归修复

维护者补充的本地测试报告指出：Web“全部已读”的成功/失败恢复，以及详情“标为已读”的失败恢复，在异步请求后访问已被浏览器清空的 `event.currentTarget`，造成按钮持续禁用。两处处理函数现在进入事件时保存按钮引用，并在 `finally` 恢复按钮；失败保留反馈与详情，允许重试。

新增 `tests/inbox-browser.test.mjs`，用真实 Chromium、实际前端模块和延后响应的隔离接口验证四条路径。修复前 4 项中 3 项失败，修复后全部通过，已有 16 项 Web 测试也全部通过。浏览器回归已加入 GitHub CI 和服务器部署前检查；运行步骤见 [WEB.md](WEB.md)。本次不修改 Android 源码或版本，原签名 1.3.0 候选 APK 无需因本次网页修复重新构建；网页修复须部署新的服务端镜像后才在服务器生效。
