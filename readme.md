<<<<<<< HEAD
# Forge Android 开发构建

Kotlin Compose 对话壳 → 本机 FastAPI/WebSocket → PRoot Ubuntu arm64 → 现有 Forge Python Agent。
桌面 Forge 内核和 GUI 不在本次修改范围。当前实现是单任务对话页，并非已经具备完整 Work/Code IDE。

当前验证见 [2026-10-24 审查记录](docs/AUDIT-2026-10-24.md)。
原生运行库、工具分发、单次设备操作确认、取消与会话互斥已接线；尚未完成 Android 真机验收。
PC/QEMU 验证不能证明 Android linker、SELinux、Accessibility 或 Shizuku 的实际可用性。

## 构建

1. `python scripts/fetch-host-bins.py --ndk <Android-NDK目录>`：按锁文件下载并校验依赖，编译最小启动器，共 8 个 Android arm64 宿主文件。
2. 在独立 Ubuntu WSL 中以 root 运行 `bash forge-android/scripts/build-rootfs.sh`。
   已完成 debootstrap 的构建可用 `--repack` 刷新全仓源码与桥，保留原软件包。
3. JDK 17、SDK 34 下运行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
4. 产物 `app/build/outputs/apk/debug/app-debug.apk`，镜像 `app/src/main/assets/forge-rootfs-arm64.tar`。

详情见 [ROOTFS](docs/ROOTFS.md)、[原生依赖来源](docs/NATIVE-RUNTIME.md)。
tar/APK 为生成产物，不提交 Git。

## 实际工具面

| 工具 | 条件 / 行为 |
|---|---|
| android.device.info / app.list | 普通系统 API；应用列表受 Android 可见性限制，最多 200 条 |
| android.screen.inspect | 已连接的无障碍，或已绑定的 Shizuku UserService |
| android.screen.capture | API 30+ 无障碍截图，或已绑定的 Shizuku；安全窗口可能拒绝 |
| android.ui.click / swipe / input | 无障碍 + 悬浮窗确认权限；每次人工确认，目标变更则拒绝 |
| android.app.launch | 悬浮窗确认权限；每次人工确认后通过 launchIntent |
| Forge 原有文件 / shell 工具 | PRoot Linux 内执行，仍由 Forge Policy / Sandbox 决定 |

写操作只选择一个后端；回执失败或超时标记 unknown，不自动切换后端重试。
Shizuku 离线不影响普通 API 和 Linux Agent。不能仅凭 Shizuku online 宣称工具已可用。
没有注册任意 Android shell 工具，也没有实现 remote control / llama.cpp。

## 启动与状态

首次解包 staging → 校验 → 保留旧镜像 → 提升新镜像；失败恢复旧镜像。
用户 workspace、Forge 配置和会话绑定到独立 filesDir/workspace、filesDir/forge-home，镜像升级不覆盖它们。
旧镜像内已有状态会迁出；新旧位置均有文件时停止迁移，避免覆盖。

服务仅收到 Python `ready` 后允许发送；连接断开不会自动重放任务。
取消是协作式：发送取消、撤销待确认操作，等待当前内核步骤退出；不能强杀任意模型 HTTP 调用而保证回滚。
退出前会话保持占用，拒绝并发 run / reset / 重连重入。

对话需要用户自己的模型配置：app 私有 `filesDir/forge-home/forge.config.json`。
本镜像没有复制桌面密钥；当前没有模型配置图形页面，调试安装可通过 `adb shell run-as com.forge.android` 管理私有配置。
页面提供无障碍、操作确认权限、Shizuku 授权入口；Shizuku 服务本身须由用户启动。

## 安全与协议

Android loopback 可被其他应用访问；除 `/health` 外，HTTP/WS 都要求安装级 Bearer token。
token 不在 URL、图片或日志中传递。Forge Policy 与宿主能力检查都必须通过。
确认仅授权当次操作，20 秒过期，取消/断线撤销；没有永久批准选项。
详见 [PROTOCOL](bridge/protocol.md)。

## 待验收

Android 真实免 root 冷启动、overlay 目标校验、屏幕旋转/分屏、Shizuku 重绑、实际模型任务、长时间后台运行。
Android targetSdk 仍为 34；尚未做新系统版本和 16KB 页设备兼容验收。
这是 debug 构建，不能作为应用商店正式发布验收。
=======
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
>>>>>>> origin/main
