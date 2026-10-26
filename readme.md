# forge 手机版（Android）— 个人助理 + MCP 连接器市场

> 桌面版（Windows：Work 对话 + Coding Mode）见 [仓库根 README](../README.zh.md)。
> 本目录是**手机版**：Kotlin Compose 原生应用 + 设备端 Python Agent。

## 一句话

手机上跑一个真正的 AI 个人助理：**内置联网搜索（零配置）、47 个服务一键接入（高德/12306/飞书/Notion/小红书/淘宝比价/GitHub 置顶）、手机原生工具（短信/通话/联系人）**——Python Agent 原样运行在设备上的 PRoot Ubuntu 24 用户态，免 root、免 Termux。

## 架构

```
┌──────────── Android ────────────┐
│ Kotlin Compose App              │
│  💬 对话 │ 🤝 助理 │ 🧩 目录     │
│  AgentBridgeService（前台服务）   │
├────────────────────────────────┤
│ FastAPI 桥 127.0.0.1:8000       │
│  Bearer token · WS 双向流       │
│  内置 web_search 工具            │
│  MCP 连接器（stdio + HTTP 双传输）│
├────────────────────────────────┤
│ forge Agent（PRoot Ubuntu 24）  │
│  纯标准库内核 · Policy 裁决一切   │
├────────────────────────────────┤
│ 设备工具：Accessibility+Shizuku  │
│  只读直行 · 写操作确认气泡        │
└────────────────────────────────┘
```

## 三种用法

### 1. 直接问（零配置）
打开「🤝 助理」输入问题——`web_search` 已内置：博查 Key → Brave Key → Bing RSS 免费兜底（国内直连）。不用装任何连接器。

### 2. 一键接入服务（🧩 目录）
置顶 7 个常用：**高德地图 / 12306 / 飞书 / Notion / 小红书 / 淘宝比价 / GitHub**。点卡片 → 弹授权表单（「去获取 Key →」直跳控制台）→ 安装并连接。免费直连的（12306/A股/淘宝比价/京东秒杀/Playwright/Context7/Airbnb）点一下就装。

其余按场景折叠：办公协作(10) / 生活日常(14) / 开发运维(8) / 专业服务(8)。

### 3. 手机原生工具
`phone.sms.list/search/send`、`phone.call.dial/log`、`phone.contacts.search`——读短信/查通话/搜联系人直接用；发短信/拨号弹确认。

## 构建

```bash
# 1) rootfs 镜像（PC/WSL 一次性，见 docs/ROOTFS.md）
# 2) 宿主二进制（proot/busybox arm64）
python scripts/fetch-host-bins.py
# 3) APK
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

灰度版状态与真机待办（INTERP/linker 方案等）：[`docs/GRAY-RELEASE.md`](docs/GRAY-RELEASE.md)

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/forge/android/` | Compose 壳（对话/助理/目录三 Tab）+ 桥服务 + Shizuku/无障碍 |
| `bridge/forge_bridge.py` | FastAPI 桥：WS/SSE、Bearer 认证、工具分发 |
| `bridge/mcp_connectors.py` + `mcp_remote.py` | MCP 运行时：stdio 子进程 + 官方远程 HTTP 双传输 |
| `bridge/connector_catalog.py` | 47 服务预置目录（场景分组数据源） |
| `bridge/web_search_tools.py` | 内置联网搜索（三级降级） |
| `bridge/phone_tools.py` | 手机原生工具定义 |
| `bridge/PROTOCOL.md` | 壳↔Agent 契约（含远程控制电脑预留 §6） |
| `docs/` | ROOTFS 制作 / 连接器目录 / 灰度状态 |
| `tests/` | 冒烟套件（桥/助理/远程/目录，全绿） |

## 与桌面版的关系

同一 `forge/` 内核零改动。桌面版管代码与电脑控制；手机版管个人助理与设备工具。远程互通（手机控制电脑 forge）协议已留，v0.2+ 接通。

## 安全纪律

- 桥仅监听 127.0.0.1 + 安装级随机 token；`/tool/call` 不出 App 沙箱
- 所有工具走 forge Policy（mode×sandbox）；连接器「能做」≠ Agent「被允许」
- 设备写操作（点击/输入/发短信/拨号）必须确认；截图不落盘
- 连接器崩溃只摘除自身工具面，不炸桥；重连自动恢复
