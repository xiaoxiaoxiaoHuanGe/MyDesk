# App 登录与会话

Android App 继续通过服务器地址、账号和密码登录。登录凭证由 Android Keystore 加密保存在手机上，后端 App 会话不设置时间期限。关闭 App、重启手机或重启后端不会主动使会话到期。退出账号、修改密码、移除设备会使相应会话失效；清除 App 数据或卸载也会删除本机凭证。

升级后的 App 首次成功连接时，会将仍有效的旧会话转为永久会话。已经到期的旧会话需要再用账号密码登录一次。后端和 App 都需要更新。

## 接口与维护

- `POST /api/login`：保留账密登录；App 请求携带 `client=android`。
- 已登录 App 请求携带 `X-MyDesk-Client: android`，用于升级仍有效的旧会话。

后端保留已发布客户端使用的扫码接口。当前源码不再提供电脑登录页。
- `POST /api/qr-login`：建立短时登录请求，返回本地生成的二维码。创建频率限制为每个客户端地址每分钟十次。
- `GET /api/qr-login/{id}`：只有持有对应 HttpOnly 浏览器 Cookie 的发起浏览器能查询并兑换登录结果。
- `POST /api/qr-login/preview`、`POST /api/qr-login/confirm`：需要有效 App 会话与 CSRF 校验；预览不会直接登录电脑。

SQLite `sessions.expires` 为 NULL 表示 App 会话永久有效，网页会话继续保存到期时间。扫码凭证和浏览器兑换凭证仅保存 SHA-256 摘要；二维码由后端 qrcode 库生成，不依赖外部二维码服务。Android 使用 ZXing Android Embedded 在手机本地识别二维码，不需要 Google Play 服务。
