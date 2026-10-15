# 后台实时接收

## 原因与行为

2026-10-16 真机反馈：APP 在桌面后台时不出现新消息，重新进入 APP 才同时显示横幅和系统通知。已确认原 MainActivity.onStop → AppGraph.stopForeground 会取消 WebSocket；后台只剩 15 分钟周期 WorkManager。同一条测试消息已通过 HTTP 200/code=0 写入收件箱，消息 ID 为 fc2bbbba84c54334a01ea96975546685。问题位于后台接收连接，而非 NotificationManager 展示。

用户已明确接受常驻状态通知及增加耗电，选择增加后台实时接收。

1.4.3 / versionCode 14 在设置 → 后台实时接收提供“桌面与锁屏接收”开关。默认关闭，由用户手动开启。开启后使用静音、低重要级别、PRIVATE 的常驻状态通知；新消息沿用外部通知频道及既有去重、隐私和详情跳转。常驻通知提供“停止接收”。

前台 Activity 与接收服务共享同一条实时连接。开启服务后离开 Activity 不再取消连接；网络断开后 2 秒起指数退避，最大 30 秒，重连前同步补齐。关闭后台接收、退出登录、会话失效或服务销毁时释放对应连接；前台仍可继续使用自己的连接。后台不会触发应用更新提示。

前台服务使用 specialUse 类型，并在 Manifest 声明持续接收自托管收件箱通知的用途，不将长期连接伪装为短时 dataSync。服务不导出，不新增接收密钥、供应商或常驻唤醒锁，不修改提醒的 AlarmManager。已有 WorkManager 继续作为补偿同步。

## 系统与真机验证

开启时要求已登录，并允许手机通知和后台状态通知。服务运行状态、连接状态和启动失败原因在设置页显示。用户可从“后台与电池设置”进入系统应用详情，手动允许后台运行。

Android Doze 与厂商省电策略仍可能限制网络，常驻服务不能保证任何手机在任何省电状态下即时送达；如锁屏延迟，需按手机系统设置允许后台运行。系统可以重建 sticky 服务；用户强行停止 APP 后需重新打开。不开启开关时保留原有定时同步行为。

真机验收：安装同签名新版 → 打开并等待同步 → 开启后台实时接收 → 确认常驻通知显示已连接 → 返回桌面或锁屏 → 服务器发送一条唯一测试消息 → 不进入 APP 即出现消息通知。再验证关闭开关、退出登录和网络恢复。

## 验证记录

2026-10-16：全部 169 项 Android 测试、36 个测试套件通过，0 失败、0 错误、0 跳过。新增三项真实 WebSocket 回归测试先在旧行为下出现预期的断言失败，再在修复后通过；另有四项后台接收控制和通知测试通过。原有提醒、收件箱、应用更新与 UI 测试一并通过。

正式 release 构建、R8 和 lintVitalRelease 通过。原签名 APK：dist/MyDesk-1.4.3-release.apk，versionCode=14，大小 5,458,079 字节。证书 SHA-256：`c3eb54d9aa145f3a96c41b566a507de2e8dbc4e44d51e561d5dc033b5b73f180`。APK SHA-256：`f437fd7b8b47c84232d3af58fcbe5e0c5d1ef24a9a2777dde2f4c9246bdc0fbc`。

已发布至现有服务器 release 更新渠道。原 1.4.2 安装包保留，旧清单已备份；未重启业务容器。公网 HTTPS 未登录更新接口返回 401，短期验证会话读取清单和完整下载 APK 均为 200，下载大小与摘要一致；验证会话已删除。

2026-10-16 北京时间真机验收完成：01:02:54 发送独立后台测试消息，用户确认未进入 MyDesk 即出现横幅和通知栏提醒；01:03:55 发送另一条锁屏测试消息，用户确认未进入 MyDesk 即在锁屏收到提醒。两条消息分别使用独立去重标识，均在服务器 HTTP 200/code=0 接收后查询到唯一持久化记录。手机展示结果来自用户明确确认，服务端记录不替代手机确认。本轮临时 SSH 授权已精确撤回，另外 11 条授权文件记录保留，新连接确认被拒绝。 验证证据保存在本机 .local/deployment/background-reception-143/。

## Android 官方资料

- https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
