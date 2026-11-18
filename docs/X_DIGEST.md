# X·速览

Android 1.6.0 增加「X·速览」。从 1.6.1（versionCode 19）起，App 优先读取 MyDesk 保存的配图，图片尚未存档时回退到 X 图片 CDN。详情默认显示完整简体中文译文（若有），可以切换原文；原文已是简体中文时直接显示。译文可搜索，与 AI 摘要分开。断网时可查看最近缓存的一页和该页内已打开过的正文；离线阅读不会补写已读。

## 部署

1. 按 [应用更新](APP_UPDATES.md) 备份数据并部署兼容后端。首次启动在现有 `mydesk.sqlite3` 添加独立的 `x_posts`、`x_briefings`、`x_reader` 表，不要求旧 App 升级。
2. 在 MyDesk 数据目录的 `x-digest-token` 文件中保存独立的 64 位随机十六进制接收令牌，只允许容器用户和服务器管理员读取。采集仓库不需要该令牌。服务器图片目录为 `x-digest-media`，按 SHA-256 去重，最多 512 MiB。
3. 私有采集仓库继续使用已有的 Apify、翻译和飞书配置。采集工作流把已翻译的完整正文和已下载的配图打包到私有分支 `mydesk/x-digest-export`，最多保留最近 12 期用来交接。工作流的 `sync_only` 和 `archive_name` 输入可以重放已有归档，不重新抓取或发送飞书通知。
4. 在服务器生成单独的密钥，只把公钥登记为私有采集仓库的只读部署密钥。将私钥、GitHub 主机的已核对公钥和 `scripts/pull_x_digest.py` 安装至 `/opt/mydesk/x-digest-pull`，安装同名 systemd 服务和计时器。服务每 15 分钟检查一次，校验图片哈希，经本机 API 导入；相同的归档无需重复写入。用 `systemctl status mydesk-x-digest-pull.timer` 查看调度，用 `journalctl -u mydesk-x-digest-pull.service` 查看导入结果。
5. 验证无凭据导入返回 401，已登录账号可读资讯，确认从历史归档导入不会重复创建记录。只有 App 本身变更时才需要发布同签名 Android APK。

`MYDESK_X_DIGEST_RETENTION_DAYS` 默认是 0（保留全部）。设置正数会在成功同步时清理超过天数且无人收藏的帖子及不再使用的简报。旧采集归档可能没有逐帖 AI 摘要或翻译，App 此时显示可用的原文。第三方图片删除或不可访问时，可继续读正文。

## 接口

- `POST /api/x-digest/ingest`：仅独立 Bearer 令牌。帖子用 X 数字 ID 去重，`media` 可将允许的原图 URL 与已经上传的 SHA-256 关联。MyDesk 不主动下载外部 URL。
- `POST /api/x-digest/status`：Bearer 令牌，按帖子 ID 查询已有翻译和图片关联，供采集器跳过重复处理。
- `PUT /api/x-digest/media/{sha256}`：Bearer 令牌，上传 JPEG、PNG 或 WebP 二进制并核对哈希；`GET` 同一路径须用 MyDesk 登录会话。
- `GET /api/x-digest/feed`：支持 `before`、`limit`、`q`、`date`、`category`、`bookmarks=1`；`before` 使用响应的 `next` 原样传回。
- `GET /api/x-digest/briefings`、`GET /api/x-digest/posts/{id}`：读取历史简报、完整帖子。
- `POST /api/x-digest/posts/{id}/read`、`POST /api/x-digest/posts/{id}/bookmark`（`{"saved":true}`）：需要登录与现有 CSRF 令牌。
