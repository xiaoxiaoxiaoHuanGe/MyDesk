# Android 通知

## 已同步提醒

用户创建提醒后，APP 将记录同步到手机，使用 AlarmManager 调度本地闹钟。通知权限和准确提醒权限需由用户开启；横幅、声音、锁屏内容由 Android 通知渠道和系统设置决定。

Android 设备及厂商策略可能影响长时间待机、重启后重新预约和后台行为。部署后应在自己的手机上分别测试前台、切到其他 APP、锁屏和重启场景。未同步到手机的新提醒不能依靠本地调度触发。

## 服务器即时推送

工作台结果同步不等同于 APP 关闭时的即时通知。源码保留 FCM 发送、设备注册和回执逻辑，但公开 APK 未编入维护者的 Firebase 项目配置。

如需使用自己的 FCM 项目，需自行构建 APK：

1. 在 Firebase 创建项目，注册包名 app.mydesk.android 的 Android 应用。
2. 下载 google-services.json，放入 android/app/；此文件已被 .gitignore 排除。
3. 按自己的项目配置构建 APP，服务端把服务账号文件保存为 data/fcm-service-account.json，权限仅供服务读取。
4. 重启后端，重新注册 APP，测试发送和回执，并确认通知中心实际收到。

服务账号私钥必须留在后端，禁止打包进 APK 或提交 Git。Firebase 项目配置及发送凭据必须属于同一项目。设备的 Google 服务、网络和系统权限也会影响 FCM 可用性。

正式 APK 不内置个人设备标识、Google 配置或服务端私钥，不保证未经配置的 FCM 推送可用。
