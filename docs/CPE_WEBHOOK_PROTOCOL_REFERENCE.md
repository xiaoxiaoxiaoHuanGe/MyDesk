# CPE 短信 → MyDesk：兼容飞书文本格式

这是第二个独立接收目标的接口约定。CPE 保留飞书转发，并把新短信另发一份到 MyDesk。MyDesk 需要实现 HTTPS 接收端，以及 APP 内的消息列表和通知逻辑。

## 1. 请求地址和 Header

推荐地址形态，路径名称可自行决定：

```http
POST https://mydesk.example.invalid/api/integrations/cpe/YOUR_RANDOM_KEY
Content-Type: application/json; charset=utf-8
Idempotency-Key: <64 位小写十六进制消息 ID>
Authorization: Bearer YOUR_TOKEN
```

示例域名和 Token 都是占位符。`Authorization` 是可选配置；也可以使用 URL 路径中的随机密钥鉴权。地址必须是 HTTPS，证书需要能通过 CPE 的系统 CA 校验，接口直接响应，不跳转到登录页或其他地址。

消息 ID 是 CPE 对该短信记录生成的 SHA-256 指纹。同一条待发短信在超时重试、下一轮检查和服务重启后使用相同 ID。该 Header 只在 MyDesk 副本中添加；现有飞书请求格式保持原样。

## 2. 请求正文

```json
{
  "msg_type": "text",
  "content": {
    "text": "📱 短信通知\n发送方: CPE 测试发送方\n内容: 【示例】验证码 123456，这是测试短信。\n时间: 2026-10-07 20:00:00 +08:00"
  }
}
```

- JSON 使用 UTF-8；`msg_type` 为 `text`，`content.text` 为完整消息字符串。
- 本方案只需要接收文本，不要求支持飞书卡片、事件订阅回调或交互回调。
- 手机号码、短信正文和接收时间已包含在 `content.text` 中；没有另外添加顶层字段。
- 时间是短信接收时间，固定显示北京时间 `+08:00`。正文可能包含换行、引号和中文。
- 建议先完整保存 `content.text`，再按需提取号码、验证码和时间。提取字段不是接收成功的前提。

## 3. 成功响应

完成持久化后返回：

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
```

```json
{"code": 0, "msg": "success"}
```

`code` 必须是 JSON 数字 `0`。`msg` 可省略。

这里的成功指 MyDesk 已可靠接收并保存消息；不需要等手机实际收到推送。建议在一个数据库事务里写入消息和通知待办，再立即返回。FCM 发送和失败重试由后端异步完成，避免把手机暂时离线变成 CPE 重复提交。

不要仅返回 `{"accepted":true}`、空正文、字符串 `"0"` 或 HTTP 204；这些不会被本转发程序判定为成功。

## 4. 去重和失败处理

在数据库中为 `(接入源, Idempotency-Key)` 设置唯一约束。接入源可以由 URL 的随机密钥或鉴权 Token 对应的 CPE 配置确定。

处理顺序：

1. 验证接入密钥或 Token。
2. 校验 `msg_type` 和 `content.text`，读取消息 ID。
3. 消息 ID 已存在时，直接返回同样的成功响应，不重复创建消息或推送任务。
4. 新消息在事务内持久化消息和通知待办，然后返回 `code: 0`。
5. 后端异步向 APP 发送推送，APP 再按消息 ID 去重显示。

如果保存失败，可返回 HTTP 500，或 HTTP 200 加非零业务码，例如：

```json
{"code": 1, "msg": "temporary storage failure"}
```

鉴权失败返回 HTTP 401/403，格式错误返回 HTTP 400。CPE 对超时、非 HTTP 200、无效 JSON、缺少成功码或非零成功码均保留待发记录，在后续检查时重试。当前检查间隔 180 秒，请求超时上限 20 秒，建议接口在数秒内完成保存并响应。

CPE 会在每个接收目标内合并同一号码、相同正文、接收时间相差不超过 60 秒的重复记录。飞书和 MyDesk 各有独立的成功记录：一边成功、另一边失败时，仅失败一边在下一轮重试。

成功响应丢失可能导致重试，因此接收端的幂等处理是必要的；它负责消除“已入库但 CPE 没收到响应”的重复请求。

## 5. APP 接入

MyDesk 当前 `/api/webhook/{secret}` 是任务结果接口，接收 `task_id/task_name/status/message/source/timestamp`，不能直接作为上面的短信接收端。建议新增独立的消息入口和收件箱表，复用现有设备注册与原生通知发送机制。

消息列表至少保存：消息 ID、来源、完整文本、服务器接收时间、已读状态。收到消息后显示未读条目，点击系统通知进入消息详情。

真我 GT5 Pro 已有 Google 服务，可以沿用仓库里的 FCM 路线。公开 APK 未包含个人 Firebase 项目配置，仍需配置自己的项目、重建 APK 和配置后端服务账号，并验证锁屏与后台场景。Webhook 接收成功和手机推送成功应分别记录。

参考：

- [MyDesk 当前 Webhook 实现](https://github.com/xiaoxiaoxiaoHuanGe/MyDesk/blob/main/mydesk/app.py)
- [MyDesk Android 通知说明](https://github.com/xiaoxiaoxiaoHuanGe/MyDesk/blob/main/docs/NOTIFICATIONS.md)

## 6. CPE 和电脑侧的配置

已准备好可选的第二目标源码及独立通道测试。当前运行中的 CPE 服务仍是既有版本；没有配置真实 MyDesk 地址，也没有发送 MyDesk 副本。地址准备好后，再部署新的 CPE 程序及启用配置。

CPE 的私有配置文件位置：`/home/root/feishu-relay/mydesk-webhook.json`，权限 600。字段示例：

```json
{
  "enabled": true,
  "url": "https://mydesk.example.invalid/api/integrations/cpe/YOUR_RANDOM_KEY",
  "headers": {"Authorization": "Bearer YOUR_TOKEN"},
  "enabled_at": "2026-10-07T12:00:00Z"
}
```

`enabled_at` 在实际启用时填写当前 UTC 时间；首次启用会跳过该时间之前的历史短信，并包含启用后到达的新短信。未填写时，以首次检查时间建立历史基线。相同 URL 暂停后再启用沿用已保存的状态；修改 URL 后重新按启用时间建立基线。

MyDesk 状态单独保存在 `data/mydesk-state.json`。现有飞书 `data/state.json` 保留原样。原 Webhook 总开关和 `disabled` 文件仍会暂停整个转发服务。

电脑代理仅增加 MyDesk 的域名和 HTTPS 端口，例如在 `work/proxy-state/proxy-targets.json` 中配置：

```json
{"mydesk_target": "mydesk.example.invalid:443"}
```

这里不保存完整路径或接入密钥。已有代理进程需要重启一次加载新版代码；之后白名单文件可以动态读取。飞书的目标和仅接受 CPE USB 地址的来源限制继续生效。

真实地址、Token 和设备私有配置不得提交到公开 GitHub 仓库。
