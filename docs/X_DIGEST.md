# X·速览

Android 1.6.0（versionCode 18）增加「X·速览」一级导航。已有用户登录后可阅读、按采集日期和分类筛选、搜索及收藏。已读和收藏保存在服务器。断网时可查看最近缓存的一页和该页内已经打开过的正文；离线阅读不会补写已读。图片由 Android 按需直接读取 X 图片 CDN，端内有有界内存缓存。

## 部署

1. 按 [应用更新](APP_UPDATES.md) 备份数据并部署兼容后端。首次启动在现有 `mydesk.sqlite3` 添加独立的 `x_posts`、`x_briefings`、`x_reader` 表，不要求旧 App 升级。
2. 在后端配置独立的随机接收令牌（至少 32 个 ASCII 字符）。设为 `MYDESK_X_DIGEST_TOKEN`，或写入 MyDesk 数据目录的 `x-digest-token` 文件，让容器用户可读且不对其他用户开放。重启 MyDesk 后生效。反向代理放行该接收路径最多 2 MiB 请求体。
3. 在私有采集仓库的 Actions Secrets 中保存 `MYDESK_X_DIGEST_URL`（MyDesk HTTPS 站点根地址）和 `MYDESK_X_DIGEST_TOKEN`（与前一步相同）。保留 Apify、OpenAI、飞书配置。首次可以用工作流的 `sync_only` 输入从已有归档同步，无需重新抓取；`archive_name` 支持指定历史文件。
4. 验证无凭据导入返回 401，已登录账号可读资讯，确认从历史归档导入不会重复创建记录，然后发布同签名 Android APK 到现有 release 渠道并验证完整下载。

`MYDESK_X_DIGEST_RETENTION_DAYS` 默认是 0（保留全部）。设置正数会在成功同步时清理超过天数且无人收藏的帖子及不再使用的简报。旧采集归档可能没有逐帖 AI 摘要或翻译，App 此时显示可用的原文。第三方图片删除或不可访问时，可继续读正文。

## 接口

- `POST /api/x-digest/ingest`：仅独立 Bearer 令牌。传入稳定批次 `id`、带时区的 `collected_at`、可选 `summary`、`trends` 以及 `posts`。每条帖子使用 X 数字 ID 去重。后端只校验并保存允许的照片 URL，不下载远程对象。
- `GET /api/x-digest/feed`：支持 `before`、`limit`、`q`、`date`、`category`、`bookmarks=1`；`before` 使用响应的 `next` 原样传回。
- `GET /api/x-digest/briefings`、`GET /api/x-digest/posts/{id}`：读取历史简报、完整帖子。
- `POST /api/x-digest/posts/{id}/read`、`POST /api/x-digest/posts/{id}/bookmark`（`{"saved":true}`）：需要登录与现有 CSRF 令牌。
