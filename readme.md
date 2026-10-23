# forge-android — Kotlin Compose 壳 + PRoot Ubuntu 24 + Shizuku + forge Python Agent

> 安卓原生 App（Compose，非 WebView）。forge Python Agent 原样跑在 PRoot Ubuntu 24.04 用户态里（免 root、免 Termux），不动一行 forge 代码；Android 控制能力走 Shizuku（ADB shell 身份）+ 无障碍双通道；壳与 Agent 之间只加一层 FastAPI/Uvicorn + WebSocket 桥。

## 架构（分层定稿）

```
┌────────────────────────────────────┐
│          Forge Android             │
│     Kotlin + Jetpack Compose       │
│  Chat / Work / Code / Settings     │
└─────────────────┬──────────────────┘
                  ▼
┌────────────────────────────────────┐
│           Agent Bridge             │
│       FastAPI + WebSocket          │
│       ToolCall Dispatcher          │
└─────────────────┬──────────────────┘
                  ▼
┌────────────────────────────────────┐
│           Python Agent             │
│     Forge Core（原样，零改动）       │
└─────────────────┬──────────────────┘
        │ tool: android.*        │ tool: linux.*
        ▼                        ▼
┌──────────────────────┐  ┌──────────────────────┐
│  Android Tool Adapter │  │  PRoot Ubuntu 24.04  │
│  （Kotlin，双通道）     │  │  apt/Python/Node/    │
│  ├ Accessibility      │  │  Git/SSH（标准用户态） │
│  └ Shizuku UserService│  └──────────────────────┘
│    （ADB shell UID）  │
└──────────────────────┘
```

要点：
- **Python Agent 不直接依赖 Shizuku**。它只发 `tools.call("android.ui.click", …)`，由 Kotlin 转发到双通道执行——forge Core 零改动。
- **PRoot 与 Shizuku 是两个独立组件**：Shizuku（ADB 启动 = UID 2000 shell）不会把 PRoot 变成真 chroot；root 设备可选真 chroot 加速（同一套镜像）。
- **Shizuku 断线不挡启动**：在线→完整 Android 工具；离线→普通 API + Linux Agent 继续可用；重连→自动恢复（工具能力上报是动态的）。

## 工具命名空间

| 命名空间 | 执行方 | 通道 |
|---|---|---|
| `android.screen.inspect / capture` | Kotlin | Accessibility dump / Shizuku screencap |
| `android.ui.click / swipe / input` | Kotlin | Accessibility 优先，Shizuku `input` 兜底 |
| `android.app.list / launch` | Kotlin | Shizuku pm（离线时 packageManager 降级） |
| `android.device.info` | Kotlin | 系统 API |
| `linux.shell.execute` | PRoot 内 | forge 自己的 shell 工具（受 Policy 裁决） |
| `linux.file.read / write` | PRoot 内 | forge 文件工具（沙箱照常） |

调用形如：
```json
{"tool": "android.ui.click", "arguments": {"x": 520, "y": 840},
 "execution": {"backend": "auto", "timeout_ms": 5000}}
```
`backend`: `auto`（默认，双通道择优）/ `accessibility` / `shizuku`。

## 目录

```
forge-android/
├── README.md                本文件
├── bridge/
│   ├── PROTOCOL.md          壳↔Agent 契约（WS/HTTP/工具/降级）
│   └── forge_bridge.py      FastAPI 桥（进镜像 /opt/forge）
├── app/
│   ├── build.gradle.kts     Shizuku API 13.1.5 + Compose
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/xml/accessibility_service.xml
│       └── java/com/forge/android/
│           ├── MainActivity.kt          Compose 对话 UI
│           ├── bridge/
│           │   ├── AgentBridgeService.kt  前台服务 + WS 双向流
│           │   ├── ProotManager.kt        rootfs 解包 + PRoot/chroot 执行
│           │   └── PythonSupervisor.kt    子进程 watchdog
│           ├── shizuku/
│           │   ├── ShizukuGate.kt         状态/权限/降级状态机
│           │   └── ForgeToolService.kt    UserService（高权限工具进程）
│           └── tools/
│               └── DeviceTools.kt         android.* 分发（双通道）
└── docs/
    └── ROOTFS.md            镜像制作（debootstrap → proot 适配 tar）
```

## 构建

### 1) rootfs 镜像（PC/WSL 上一次性）

见 `docs/ROOTFS.md`：debootstrap arm64 noble → 装 python3/uvicorn/fastapi + `/opt/forge` 全仓 → tar。产出 `forge-rootfs-arm64.tar` 放 `app/src/main/assets/`。

### 2) APK

```bash
cd forge-android && ./gradlew :app:assembleDebug   # JDK17 + SDK 34
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3) 首启流程

解包 rootfs → PRoot 起 uvicorn（127.0.0.1:8000）→ /health 过 → WS 连接 → 对话页可用。Shizuku/无障碍在「设置页」按向导开启，随时补。

## 权限与安全

- Manifest：悬浮窗、无障碍服务声明、前台服务、电池白名单；Shizuku 权限运行时请求（requestCode 1001）。
- uvicorn 仅 lo；`/tool/call` 不出 App 沙箱。
- forge Policy 照常裁决所有 android.*/linux.* 工具——Kotlin「能做」≠ Python「被允许」。
- 操作分级：点击/输入 = 需前台可见确认气泡（悬浮窗）；inspect/capture = 只读放行；shell = Shizuku 在线才注册。

## 远程控制电脑 forge（预留，v0.2+）

`bridge/RemoteControl.kt` 占位：反向 WS + `/remote/*` 路由组；桌面端 computer_use 套件（observe/act/click + 审批）是现成对接面。契约见 PROTOCOL.md §6。

## 状态

- [x] 工程骨架 / 协议契约 / 桥服务 / Compose 壳 / Shizuku 双通道与降级状态机
- [ ] rootfs 镜像（PC 侧 debootstrap 产出 tar）
- [ ] 真机联调（PRoot 冷启动耗时、无障碍点击闭环、Shizuku 重连恢复）
- [ ] llama.cpp 本地推理（镜像内 apt/源码编，:8101）
