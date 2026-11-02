# pledge-steward — Android 个人助理（原 forge-android / forge 手机版）

> Kotlin Compose 原生应用 + 设备端 Python Agent（forge 内核）。
> 2026-11-02 从 [pledge-evolving](https://github.com/suyunpledge/pledge-evolving) 单仓的 `forge-android/` 拆出独立存仓，首个独立版本 v0.5.0。
> 桌面版（Windows：Work 对话 + Coding Mode）仍在 pledge-evolving。

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

## 与 forge 内核的关系（pin 机制）

本仓只含手机侧：Kotlin 壳、Python 桥、rootfs/构建脚本与测试。
Agent 内核 `forge/` 与 `bundles/` **不入库**，由 [`forge-pin.json`](forge-pin.json) 固定版本，
[`scripts/fetch-forge.py`](scripts/fetch-forge.py) 拉取到 `build/forge-src`（rootfs 打包与冒烟测试共用同一份）。

升级内核 = 改 pin → `python scripts/fetch-forge.py` → 跑冒烟 → 重打 rootfs。

## 构建

```bash
# 0) forge 内核源码（一次性；改 pin 后重跑。离线可用 --local <pledge-evolving 检出路径>）
python scripts/fetch-forge.py
# 1) 宿主二进制（proot/busybox arm64，幂等）
python scripts/fetch-host-bins.py
# 2) APK
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

rootfs 镜像（PC/WSL 一次性）：先 `fetch-forge.py`，再按 [docs/ROOTFS.md](docs/ROOTFS.md) 执行
`scripts/build-rootfs.sh`——**不再需要 pledge-evolving 检出**。

## 验证

```bash
python tools/verify-rootfs-layout.py   # PC 仿真 /opt/forge 布局：桥模块齐全 + 路由注册 + /health
python tests/smoke_rt.py               # 离线契约检查
python tests/smoke_assistant.py        # 助理模式/MCP（起真 uvicorn）
# 其余：smoke_catalog / smoke_onboarding / smoke_remote / smoke_remote_host / e2e_remote
```

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/forge/android/` | Compose 壳（对话/助理/目录三 Tab）+ 桥服务 + Shizuku/无障碍 |
| `bridge/forge_bridge.py` | FastAPI 桥：WS/SSE、Bearer 认证、工具分发 |
| `bridge/mcp_connectors.py` + `mcp_remote.py` | MCP 运行时：stdio 子进程 + 官方远程 HTTP 双传输 |
| `bridge/connector_catalog.py` | 47 服务预置目录（场景分组数据源） |
| `bridge/web_search_tools.py` | 内置联网搜索（多级降级，默认美国端点） |
| `bridge/phone_tools.py` | 手机原生工具定义 |
| `bridge/PROTOCOL.md` | 壳↔Agent 契约（含远程控制电脑预留 §6） |
| `scripts/fetch-forge.py` + `forge-pin.json` | forge 内核版本锚（上游 pledge-evolving） |
| `scripts/build-rootfs.sh` | arm64 PRoot 镜像构建（WSL） |
| `tools/verify-rootfs-layout.py` | PC 侧 /opt/forge 布局仿真校验 |
| `docs/` | ROOTFS 制作 / 连接器目录 / 灰度状态 |
| `tests/` | 冒烟套件（桥/助理/远程/目录） |

## 安全纪律

- 桥仅监听 127.0.0.1 + 安装级随机 token；`/tool/call` 不出 App 沙箱
- 所有工具走 forge Policy（mode×sandbox）；连接器「能做」≠ Agent「被允许」
- 设备写操作（点击/输入/发短信/拨号）必须确认；截图不落盘
- 连接器崩溃只摘除自身工具面，不炸桥；重连自动恢复

## 历史

`git log` 完整保留 forge-android 时期历史（subtree split 自 pledge-evolving@db95193，2026-11-02）。
真机灰度状态与待办见 [docs/GRAY-RELEASE.md](docs/GRAY-RELEASE.md)。
