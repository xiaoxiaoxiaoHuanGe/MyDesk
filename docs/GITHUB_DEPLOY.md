# GitHub Actions 一键更新服务器

适用于已有 Ubuntu amd64 / Docker Compose / `/opt/mydesk` 部署。Actions 构建镜像并通过 SSH 上传；服务器只接收 MyDesk 镜像，不需要拉取私有镜像仓库，不上传账号数据库、邮箱密码或服务 Token。

## 首次配置一次

先把包含本说明的 PR 合并到 `main`。在 1Panel 的服务器终端，以 root 执行下面这一条命令。将 `你的服务器公网IP` 替换为实际 IP；SSH 非 22 端口时，在 `bash` 前再加 `MYDESK_DEPLOY_PORT=实际端口`。

```bash
curl -fsSL https://raw.githubusercontent.com/xiaoxiaoxiaoHuanGe/MyDesk/main/scripts/setup_actions_deploy.sh | MYDESK_DEPLOY_HOST=你的服务器公网IP bash
```

初始化会检查当前部署，安装固定的接收程序，生成专用 ed25519 密钥，并在 root 的 `authorized_keys` 中追加带 `restrict` 和强制命令的授权。它保留其他登录公钥，不重启 MyDesk，不修改 SSH 策略或防火墙。专用密钥只能调用 `deploy 完整提交SHA`，不能使用交互式终端、SFTP 或端口转发。服务器须已允许 root 公钥登录；脚本遇到不兼容策略会退出，不会放开 root 登录。

打开 [仓库 Environments](https://github.com/xiaoxiaoxiaoHuanGe/MyDesk/settings/environments)，创建 `production`，部署分支限定为 `main`。在该环境添加以下 Secrets；也可使用仓库的 [Actions Secrets](https://github.com/xiaoxiaoxiaoHuanGe/MyDesk/settings/secrets/actions)。

| Secret 名称 | 内容 |
| --- | --- |
| `DEPLOY_HOST` | 服务器公网 IP 或域名 |
| `DEPLOY_SSH_KEY` | 服务器 `/root/.mydesk-actions/id_ed25519` 的完整内容，包含 BEGIN/END 行 |
| `DEPLOY_KNOWN_HOSTS` | 服务器 `/root/.mydesk-actions/known_hosts` 的完整内容 |
| `DEPLOY_PORT` | 可选，默认 `22` |

可以使用 1Panel 文件管理器查看上述两个文件并直接复制到 GitHub；私钥不要粘贴到聊天、Issue、PR 或仓库文件。初始化输出仅包含文件位置，不显示私钥。服务端生成的 known_hosts 使用现有主机公钥，工作流会严格校验服务器身份。

## 日常更新

代码更新到 `main` 后，打开 [部署工作流](https://github.com/xiaoxiaoxiaoHuanGe/MyDesk/actions/workflows/deploy.yml)，点击 **Run workflow**，选择 `main`。其他分支不会执行部署，PR 只执行构建验证。初始化不必重复运行，公钥也不必在每次更新时重新授权。

流程：Python / 网页测试 → Linux amd64 镜像构建及健康检查 → 上传镜像 → 检查现有容器与数据挂载 → 停止 MyDesk → 备份 → 替换镜像 → 等待容器健康。

- 镜像以提交 SHA 标记，服务器部署时使用不可变镜像 ID；构建镜像时不接触生产 Secrets。
- 原 `/opt/mydesk/compose.yaml`、域名、反向代理及 `/opt/mydesk/data` 继续使用。只增加 `/opt/mydesk/compose.actions.json` 记录当前镜像。
- 镜像接收和加载期间旧容器继续运行，备份和切换期间会有短暂停服。备份在应用停止后进行，以保证 SQLite 与配置一致。
- 备份在 `/opt/mydesk/backups/actions-*`，目录权限 `700`、数据备份权限 `600`。包含数据压缩包、旧镜像 ID、Compose 配置和提交记录。
- 备份或新镜像启动失败时，会尝试重启旧镜像，工作流仍标记失败。回退的是镜像，不会自动覆盖新版本启动后产生的数据；若更新涉及不兼容的数据迁移，需要停服后人工从升级前备份恢复。
- GitHub 和服务器分别限制同时只能进行一个更新。缺少 Secrets、主机指纹不匹配或连接失败时，不会开始服务器更新。
- 更新镜像只更新服务端和网页；Android APK 安装需要单独处理。

## 后续维护

部署成功后，在服务器手动使用 Compose 时应包含镜像覆盖文件，避免重新启动旧镜像：

```bash
cd /opt/mydesk
docker compose -f compose.yaml -f compose.actions.json ps
docker compose -f compose.yaml -f compose.actions.json logs --tail 100 mydesk
```

原服务器源码目录不是 Actions 镜像的来源，不会被同步；不要再用旧源码执行 `docker compose up --build`。后续更新统一通过 Actions。

备份与旧镜像不会自动删除。定期检查磁盘，确认不再需要回退的版本后再清理。每次上传限制压缩包 1 GiB、归档成员总大小 4 GiB；服务器应有足够空间存放新旧镜像、临时包及数据备份。

授权是持久的部署权限，仓库写入者及能够使用 production Secrets 的工作流可以更新服务器代码，应保护 `main` 和工作流修改权限。撤销部署时，只移除 `authorized_keys` 中带 `mydesk-github-actions` 的那一行，并删除对应 GitHub Secrets，保留其他公钥。

初始化脚本和接收程序不会在日常部署时自动更新。如果这两个脚本发生修订，核对变更后再执行一次初始化；现有专用密钥会复用。
