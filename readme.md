# pledge-steward — Android 个人助理（原 forge-android / forge 手机版）

> Kotlin Compose 原生应用 + Kotlin 原生桥 + Kotlin Agent 核心。
> 2026-11-02 从 [pledge-evolving](https://github.com/suyunpledge/pledge-evolving) 单仓的 `forge-android/` 拆出独立存仓，首个独立版本 v0.5.0。
> 桌面版（Windows：Work 对话 + Coding Mode）仍在 pledge-evolving。

## 一句话

手机上跑一个真正的 AI 个人助理：**内置模型路由、连接器目录、手机原生工具（短信/通话/联系人）**——桥、Agent 循环、会话日志和设备工具全部运行在 Android Kotlin 进程中，免 root、免 Termux。

## 架构

```
┌──────────── Android ────────────┐
│ Kotlin Compose App              │
│  💬 对话 │ 🤝 助理 │ 🧩 目录     │
│  AgentBridgeService（前台服务）   │
├────────────────────────────────┤
│ Kotlin NativeBridge 127.0.0.1:8000│
│  Bearer token · WS 双向流       │
│  引导/目录/遥控/设备回执原生处理    │
├────────────────────────────────┤
│ NativeAgentEngine（Kotlin）      │
│  模型路由 · Policy · 工具循环      │
├────────────────────────────────┤
│ 设备工具：Accessibility+Shizuku  │
│  只读直行 · 写操作确认气泡        │
└────────────────────────────────┘
```

## 与 forge 内核的关系（pin 机制）

本仓只含手机侧 Kotlin 壳/原生桥/Agent 核心；历史 Python Forge 源码仅用于桌面兼容和对照，不进入 APK 运行路径。
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
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# Python/rootfs 校验仅用于历史桌面兼容路径
```

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/forge/android/` | Compose 壳（对话/助理/目录三 Tab）+ 桥服务 + Shizuku/无障碍 |
| `app/src/main/java/com/forge/android/bridge/NativeBridgeServer.kt` | 原生 HTTP/WS、Bearer 认证、路由与回执 |
| `app/src/main/java/com/forge/android/bridge/NativeAgentEngine.kt` | Kotlin Agent 核心：配置、模型路由、工具循环、会话日志、取消 |
| `app/src/main/assets/connector_catalog.json` | 47 服务预置目录（Kotlin 原生场景分组数据源） |
| `bridge/*.py` | 历史桌面兼容源码；Android 主路径不启动 Python |
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
