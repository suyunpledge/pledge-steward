# MCP 连接器目录（forge-android 个人助理模式预置）

> 每条：传输类型（stdio/HTTP）、启动/接入命令、所需凭据、状态（官方/社区/受限）。
> 远程 HTTP 型连接器需要桥 v0.4 的 streamable-http 传输（本轮已加）；
> stdio 型直接可用。手机原生工具（短信/电话）不需要任何第三方 server。

## A. 开发与协作

### GitHub（官方远程）
- 类型：HTTP（官方托管）· 状态：**官方**
- 接入：`https://api.githubcopilot.com/mcp/` + OAuth 或 PAT
- 能力：仓库/Issue/PR/搜索/Actions 全套
- 备注：官方 stdio 版（docker `ghcr.io/github/github-mcp-server`）也有，但手机上远程版更省资源

## B. Microsoft 全家桶

### Microsoft 365（官方，Entra ID）
- 类型：HTTP（`https://api.microsoft.com/mcp/`）· 状态：**官方预览**
- 覆盖：Outlook 邮件/日历、OneDrive、Teams、To Do
- 凭据：Entra ID OAuth（Azure 应用注册）
- 备注：社区 `@guopz/office-word-mcp-server` 等可补充 Word/Excel 本地文件操作

### Outlook
- 并入 Microsoft 365 官方连接器（Outlook 即其一部分）；如只要邮件，Gmail via Google Workspace 更轻

## C. Google 全家桶

### Google Workspace（社区最强全家桶）
- 类型：stdio（npx）· 状态：社区（Google 官方 MCP 已发布，但逐产品分立）
- 命令：`npx -y @gworkspace/mcp`（taylorwilsdon/google_workspace_mcp）
- 覆盖：**Gmail、Drive、Docs、Sheets、Slides、Calendar、Forms、Tasks、Contacts、Chat**
- 凭据：Google OAuth credentials.json（GCP 项目 + Workspace API 启用）
- Google 官方逐个：Gmail `https://mcp.gmail.com/mcp`、Drive/Docs 同域系列（HTTP + OAuth）

### Google Sheets / Slides 单独
- 并入上面全家桶，或官方单产品 HTTP 端点

## D. Notion

### Notion（官方远程）
- 类型：HTTP（`https://mcp.notion.com/mcp`）· 状态：**官方**
- 凭据：Notion OAuth（集成令牌 + 页面授权）
- 能力：搜索/读取/创建/更新页面与数据库
- Notion AI：其 MCP 本身后端即接 Notion AI 能力（写作/总结），无需单独连接器

## E. Norton / Meta

### Norton 全家桶
- 状态：**无公开 MCP**（Gen/Norton 未发布，也没有活跃社区实现）
- 替代：Norton 的设备安全状态可用本地工具读取（后接）；密码管理器无公开 API

### Meta（WhatsApp/Instagram/Facebook）
- 状态：**无官方 MCP**；Meta 平台 API（WhatsApp Business Cloud API、Instagram Graph API）存在但都是 REST，且个人账户基本不可编程访问
- 替代路径：WhatsApp Business API（企业号）可经社区网关桥接——需要商业验证，个人用不上；Instagram 可用 Browser-use 类浏览器代理方案（见 G）
- 结论：**当前不可接**，目录里标记为「无可用连接器」，不假装支持

## F. 手机原生（无需任何 MCP server，走设备通道）

### 手机短信（读取/搜索/发送）
- 通道：Shizuku `content query --uri content://sms`（读）+ `sms send`（写，需确认）
- 桥内实现：`phone.sms` 系工具（v0.4 内置，见 bridge phone_tools.py）

### 手机电话（拨打/通话记录/联系人）
- 通道：Shizuku `service call phone`（拨号）+ `content query call_log/calls`（记录）
- 桥内实现：`phone.call` / `phone.contacts` 系工具
- 写操作（拨号/发短信）走确认气泡，只读（查记录/联系人）直接放行

## G. 国内生态（淘宝比价等）

### 淘宝/京东/拼多多搜索比价
- 状态：**社区，质量分叉**
  1. `taobaoscraper` MCP（mcp.so 收录）：淘宝/天猫/京东商品数据 8 工具，stdio npx
  2. 淘宝客 MCP（`@liuliang520500/sinataoke_cn`）：链接转换/推广/搜索，需淘宝客 API key
  3. 知乎方案（zhuanlan p/2060…）：Cookie-based 浏览器抓取型，稳定性依赖淘宝页面结构
- 推荐：先接 1（无 Cookie、纯数据接口），比价场景够用；2 适合返利场景
- 命令示例：`npx -y taobaoscraper-mcp`（具体包名以目录页为准，接入向导里可配）

### 小红书/知乎/微博（内容侧）
- `mcp-cn-commerce`（TonyWang-hub）：8 个国内电商经营数据连接器（面向商家）
- 知乎/微博：社区浏览器抓取型（Cookie 注入），稳定性一般

### 闲鱼
- `xianyu-buyer-mcp`：发布/管理/IM 消息（自动化卖货向），需要闲鱼 Cookie

### 微信
- 桌面端已有 openclaw-weixin 通道（AutoClaw 生态内），手机端个人微信无官方 API；企业微信有服务端 API 可做 REST→MCP 网关（后续按需）

### 支付宝
- 无公开 MCP；开放平台 API 面向商户。个人账单查询无官方途径

## H. 通用兜底

### 浏览器代理（Browser-use 型）
- 对「无 MCP 的平台」（Instagram/淘宝页面比价深挖）兜底：`@browser-use/mcp-server`（stdio）
- 手机端跑 headless chrome 吃资源——桌面 forge 侧跑、手机经远程会话调用更现实（v0.5 远程通道预留位）

---

## 落地状态

| 服务 | 连接器 | 本轮 |
|---|---|---|
| GitHub | HTTP 官方 | ✅ 目录+HTTP 传输 |
| Microsoft 365/Outlook | HTTP 官方预览 | ✅ 目录 |
| Google 全家桶 | stdio 社区全家桶 + 官方逐产品 | ✅ 目录 |
| Notion/Notion AI | HTTP 官方 | ✅ 目录 |
| 手机短信/电话/联系人 | 原生设备通道 | ✅ phone_tools 实现 |
| 淘宝比价 | stdio 社区 | ✅ 目录+接入向导模板 |
| Norton | 无 | ❌ 明确标注不可用 |
| Meta 全家桶 | 无 | ❌ 明确标注不可用（个人账户） |
| 浏览器兜底 | stdio | 目录（建议桌面侧跑） |
