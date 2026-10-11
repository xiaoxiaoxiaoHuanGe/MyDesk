# Web 工作台

Web 与 Android App 使用相同的 MyDesk 后端和服务配置。界面采用暖白背景、灰蓝主色、纸感页头、圆角卡片和线性图标，支持浅色、深色及跟随系统。

## 页面与交互

- **工作台**：需要处理的事项、微信步数、最近邮件、自动任务、服务器和网络。桌面分为两列独立排列；手机按需要处理 → 微信步数 → 邮件 → 自动任务 → 服务器 → 网络依次展示。
- **提醒**：日期 / 小时 / 分钟与小时 / 分钟 / 秒倒计时切换。支持触摸滚动、鼠标滚轮及方向键；切页和调整窗口保留选中的时间与草稿。详情内可延后、完成或取消。
- **设置**：同步所有服务、修改密码、退出账号使用图标按钮。GitHub 任务、多个 Gmail 和服务器分别管理，凭据留空保留原值。提供时区选择、设备管理及加密备份。
- **网络节点**：摘要展示少量节点，完整列表支持名称 / 地址搜索和在线状态筛选。
- **详情窗口**：可通过关闭按钮、Escape 或遮罩关闭；手机端也可在内容顶部向下拉动关闭。正文使用原生滚动，避免滚动抖动。

连接正常时不展示状态提示；连接中断时显示黄色“未连接”，浏览器重新联网后自动恢复状态同步。外观切换即时生效，不触发全局进度条。图标按钮均有可访问名称，动效遵循系统减少动态效果的设置。

## 数据与隐私

GitHub、邮件和服务器同步仍由后端执行，WebSocket 推送最新快照。Web 和 Android 不需要重复配置服务。定时提醒由已登录的 Android App 同步并本地执行；Web 不创建浏览器通知。

浏览器持久存储仅保存 `mydesk-appearance` 外观选项，不保存服务凭据。Token、邮箱应用密码和服务器 API Key 保存在后端；编辑界面始终不回显。备份密码最少 12 个字符，加密文件包含服务配置与凭据，不包含登录账号、提醒或历史。恢复前显示变更预览，并在后端加密保留回退配置。

## 本地 UI 验证

单元测试：

```sh
node --test tests/frontend.test.mjs tests/standalone.test.mjs
```

收件箱异步已读操作的真实浏览器回归测试（不使用生产服务或账号）：

```sh
npm ci --prefix frontend --ignore-scripts --no-audit --no-fund
npm exec --prefix frontend -- playwright-core install --with-deps chromium
npm run --prefix frontend test:browser
```

测试启动临时回环 HTTP 服务，加载实际收件箱和弹窗模块，并延后模拟接口响应，覆盖全部已读/详情已读的成功、失败恢复及再次点击。GitHub CI 和服务器部署前都会执行。已有 Chromium/Chrome 时可设置 `MYDESK_BROWSER_EXECUTABLE` 为可执行文件路径，省略浏览器安装；Windows 默认使用已有 Edge，也可通过 `MYDESK_BROWSER_CHANNEL` 指定其他 Chromium 渠道。

启动隔离的演示服务（只绑定 `127.0.0.1:8790`）：

```sh
python scripts/web_fixture.py
```

演示数据位于 `.local/web-fixture`，不使用生产配置，也不连接 GitHub、Gmail 或监控面板。首次生成的演示登录凭据保存在该目录的 `local-access.json`。这个服务仅用于 UI 验证，不用于部署。

浏览器验证需要 Playwright 和 Edge；可以安装 Playwright，或用 `PLAYWRIGHT_MODULE` 指定已有模块的路径。Linux CI 可设置 `MYDESK_BROWSER_CHANNEL=chromium` 并安装相应浏览器。

PowerShell 示例：

```powershell
$env:MYDESK_BASE='http://127.0.0.1:8790'
$env:MYDESK_ACCESS='.local/web-fixture/local-access.json'
$env:MYDESK_WEB_DEMO='1'
$env:MYDESK_WEB_TEST_ISOLATED='1'
node scripts/browser_check_standalone.cjs
```

浏览器验证会创建临时提醒和测试接入、执行备份恢复及退出会话，只应对隔离演示服务运行。覆盖双标签页实时更新、断线恢复、草稿与时间保留、多邮箱筛选、独立凭据、备份往返、320–1440 像素布局和深色模式。演示截图输出到被 Git 忽略的 `artifacts` 目录。

## 样式维护

- `frontend/app.css`：APP 配色映射、响应式页面、导航和设置。
- `frontend/controls.css`：按钮、表单、状态标签和详情窗口的统一样式。
- `frontend/mydesk.css`：数据卡片和时间滚轮。
- `frontend/icons.js`：统一线性图标。

背景与图标来源于 App 的已有资产，Web 背景转为 WebP，避免加载原尺寸图片。部署源码包会包含新增脚本、样式和 WebP 资源。
