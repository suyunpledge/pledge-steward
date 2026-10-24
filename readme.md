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
