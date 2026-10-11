# 部署 MyDesk

已有服务器的日常更新可使用 [GitHub Actions 一键部署](GITHUB_DEPLOY.md)：首次配置专用部署密钥，此后在 GitHub 点一次运行即可完成测试、备份、更新和健康检查。

后端面向个人单账号使用。推荐 Linux、Docker Compose、自己的 HTTPS 域名和反向代理。

## Linux 服务器

在仓库根目录执行：

```sh
mkdir -p data
sudo chown 10001:10001 data
sudo chmod 700 data
docker compose -f deploy/compose.server.yaml up -d --build
curl --fail http://127.0.0.1:8787/health
sudo cat data/LOCAL_ACCESS.md
```

`compose.server.yaml` 使用 host 网络，后端仅监听 127.0.0.1:8787，信任本机反向代理 127.0.0.1。此配置适用于 Linux 上同主机运行的 Nginx/OpenResty；若代理运行在独立容器网络中，请根据实际来源 IP 调整 `--trusted-proxy`，不要信任任意来源。

首次启动会生成随机登录密码。立即登录并在设置中修改密码；保管数据目录和首次登录文件。

## HTTPS 和 WebSocket

域名指向服务器，为反向代理部署可信证书。1Panel 可创建反向代理网站，目标填写 `http://127.0.0.1:8787`，开启 HTTPS 与 WebSocket。

Nginx location 示例（放在自己的 HTTPS server 中）：

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
    proxy_buffering off;
}
```

保留真实 Host，勿改写 `/api/ws` 路径。Android 登录地址填写 `https://mydesk.example.com`。正式 APK 不接受自签名测试证书，不关闭证书或主机名验证。

## 本地开发

安装 requirements-dev.txt 后可原生运行：

```sh
python -m mydesk --data .local/standalone --host 127.0.0.1 --port 8787
```

浏览器打开 http://127.0.0.1:8787；首次凭据位于 `.local/standalone/LOCAL_ACCESS.md`。

Docker 本地测试脚本 `python scripts/start_local.py` 会生成仅用于本项目的测试 CA。`--phone --lan-ip <本机局域网地址>` 可启用 LAN HTTPS，用调试 APK 测试。生成的 CA 和私钥均在 `.local/`，不要提交或混入正式 APK。

## Gmail 代理

每个邮箱可设置后端可访问的 HTTP CONNECT 代理。后端与代理同机且均使用 host 网络时可填写 `127.0.0.1` 和实际 HTTP 端口。bridge 容器中的 127.0.0.1 指容器自身，需要另外配置私有访问通道。

代理本身由部署者自行准备，订阅、代理密码和节点配置不属于公开项目。MyDesk 当前支持无需账号认证的 HTTP 代理，保留 Gmail TLS 验证。

## 网络检测

Ping 失败可能来自目标不回应、DNS 或系统权限。Linux 非 root 容器需要允许其 GID 使用 ICMP echo datagram sockets。先查看：

```sh
cat /proc/sys/net/ipv4/ping_group_range
```

若范围不包含运行 MyDesk 的 GID 10001，可将 `deploy/mydesk-ping.conf` 加入 sysctl.d 并加载。保留已有应用需要的范围，不必给容器 privileged 权限。

## 更新和备份

更新前先在 APP 导出加密配置备份，并另行备份完整 data 目录。APP 配置备份不包含账号数据库、提醒或历史，不能代替完整数据备份。

```sh
git pull
docker compose -f deploy/compose.server.yaml up -d --build
docker compose -f deploy/compose.server.yaml ps
```

不删除 data 或 Docker 数据卷。首次部署和更新后都验证登录、服务接入及提醒。
