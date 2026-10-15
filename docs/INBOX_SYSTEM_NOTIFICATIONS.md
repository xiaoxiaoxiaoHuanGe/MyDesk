# 最近通知的系统提醒

“最近通知”通过 WebSocket、REST 或现有后台同步到手机后，APP 调用 Android NotificationManager 展示系统通知。点击通知进入对应收件箱消息详情；完整正文仍保留在收件箱。

展示沿用“提醒”的通知基础能力：系统通知权限、默认提示音和振动、重要级别 HIGH、锁屏 PRIVATE 隐私显示。横幅、声音及锁屏可见性由手机的系统设置决定。“外部通知”使用独立频道，用户可以单独关闭。

旧版 `inbox` 频道默认是 DEFAULT，Android 不允许应用直接修改已有频道的重要级别，因此新版使用 `inbox_messages`。迁移时保留旧频道的关闭或手动重要级别选择、声音和振动设置；已有新频道的用户设置也不会被覆盖。

## 触发与去重

- 首次安装、升级或登录时建立当前消息序号基线，不补弹历史消息。
- 后续同步发现新消息时，通过现有收件箱分页接口补齐，不局限于工作台展示的最近三条。
- 已读、待同步已读和权限关闭时处理过的消息不重放。
- 同一消息在原生推送、实时快照、REST 同步之间共享本机投递记录；重复同步、重新连接或进程重启不再次弹出。
- 本机序号与去重记录复用既有 delivery 存储，键使用独立前缀；退出登录清除，没有增加数据库版本或改动提醒记录。
- 提醒的 AlarmManager 调度、准确提醒权限及完成/延后操作保持原逻辑。

## 后台时机

1.4.2 前台沿用 WebSocket，后台仅使用 15 分钟周期 WorkManager。1.4.3 增加用户手动开启的“后台实时接收”：使用带静音状态通知的前台服务，在返回桌面后继续维持同一条 WebSocket；关闭时仍使用既有 WorkManager。网络与省电策略影响实际送达时间，真机锁屏效果须单独验收。具体行为、限制与测试见 [后台实时接收](BACKGROUND_RECEIVING.md)。

## 验证

`InboxSystemNotificationsTest` 覆盖首次基线、新消息的频道/隐私/点击跳转、重复同步与进程重启、权限关闭、已读、退出登录、REST 分页补齐、推送与同步两种到达顺序，以及旧频道关闭状态的迁移。

同时运行既有提醒调度、仓库同步、NativeStore、原生推送和回执测试。正式 APK 使用已有签名身份构建，手机实机横幅和锁屏展示需要覆盖安装后验收。

参考：
- https://developer.android.com/develop/ui/compose/notifications/channels
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

## 本次验证结果

2026-10-15：36 项相关 Android 测试通过，0 失败、0 错误、0 跳过，含 7 项新增系统通知测试。测试临时文件使用项目 D 盘私有目录，避免 C 盘临时空间不足影响 Robolectric 原生运行库。

原正式签名预检通过。当次构建没有通过 ADB 安装；1.4.3 的桌面后台横幅、通知栏和锁屏提醒已由用户在手机上确认，见 [后台实时接收](BACKGROUND_RECEIVING.md)。

正式签名 APK 已构建并验证：`dist/MyDesk-1.4.2-release.apk`，包名 `app.mydesk.android`，versionCode=13，versionName=1.4.2，大小 5,457,823 字节。签名证书 SHA-256 为 `c3eb54d9aa145f3a96c41b566a507de2e8dbc4e44d51e561d5dc033b5b73f180`，与原正式版一致。APK SHA-256：`5e4acccd1c1749ea57d07f666843a61e363e21a7872a033d4c7890375cbe58a5`。release 构建及 lintVitalRelease 通过。2026-10-15（北京时间）已发布至现有服务器 release 更新渠道，versionCode=13；旧版 1.4.1 产物保留，旧清单已备份。实际 HTTPS 更新接口未登录返回 401，短期验证会话读取新清单返回 200，完整下载 APK 返回 200，大小和 SHA-256 与本机原签名包一致。验证会话已删除。用户可在设置 → 应用更新 → 检查更新中安装；真机安装及横幅、锁屏显示待手机端验收。1.4.2 未单独发行 GitHub Release；相关源码并入 1.4.3 正式版。
