> 历史记录（98b512a）。当前状态和对旧结论的修正见 [AUDIT-2026-10-24](AUDIT-2026-10-24.md)，不得据此认定真机验收通过。

# 灰度测试版发布清单（GRAY-RELEASE.md）

> 基线：fix/android-rootfs-build @ 6176c10 + 本轮灰度补齐批。
> 目标：可安装、可启动、对话链路可用的灰度版（gray release），真机设备控制闭环允许未完成——但已实现的部分必须真实。

## 已就绪（本轮补齐后）

| 项 | 状态 | 说明 |
|---|---|---|
| APK 构建 | ✅ | `gradlew :app:assembleDebug` BUILD SUCCESSFUL；APK 199MB（内嵌 190.8MB rootfs，ZIP_STORED，SHA-256 与外部 tar 一致） |
| rootfs 镜像 | ✅ | arm64 noble 全套：python3/FastAPI/Uvicorn/forge 全仓；QEMU 第二阶段验证、镜像内 uvicorn 真启动 /health 通过 |
| 桥认证 | ✅ | 安装级随机 token，HTTP/WS Bearer 验证；loopback only |
| 桥契约 | ✅ | smoke 6/6（真实 Agent 构造、真实报告字段、认证、路径穿越拒绝） |
| tool_call 接线 | ✅ 本轮 | AgentBridgeService.onMessage → DeviceTools.handle → postToolResult 回传；写操作 fail-closed（无确认 UI 时拒绝，不伪装批准） |
| Shizuku 接线 | ✅ 本轮 | ShizukuGate 三态机挂入 Service 生命周期；WS open + 状态变化时 POST /capabilities 上报真实工具面 |
| 解包原子性 | ✅ 本轮 | .forge-extracted = tar SHA-256 指纹；tmp 目录 + 原子 rename；镜像升级自动重解；中断不留半成品 |
| proot/busybox | ✅ 本轮 | arm64 bionic 二进制已落 jniLibs（Termux 官方包解出，ELF machine=183 验证） |
| 依赖 so | ⏳ | libtalloc/libandroid-shmem/libtermux-exec 三个小 so 因网络 SSL 限流未拉齐——`scripts/fetch-host-bins.py` 幂等一键补齐 |

## 灰度版已知边界（不假装完成）

1. **Termux 二进制 INTERP 问题**：proot 的 ELF interpreter 指向 `/data/data/com.termux/.../linker64`，我们的 App 路径不同——真机首启需要：把 Termux bootstrap 里的 linker64 一并放 jniLibs，并 patchelf 改 INTERP，或用 `LD_LIBRARY_PATH` + 显式 loader 调起。**这是真机首启阻断项**，PC 侧无法验证。
2. **设备写操作保持拒绝**：确认气泡 UI 未实现（审计遗留 #3），android.ui.click/input 在无确认通道时 fail-closed。灰度版设备侧只有只读工具（inspect/device.info）。
3. **无障碍/Shizuku 未真机联调**：bindUserService、Accessibility dump、手势派发全部待真机。
4. **PRoot 冷启动耗时未测**：200MB 镜像解包 + uvicorn 冷启预计 1-3 分钟（首启进度条已有）。

## 灰度发布步骤

```bash
cd forge-android-build/forge-android
python scripts/fetch-host-bins.py          # ① 补齐依赖 so（网络好时）
./gradlew :app:assembleDebug               # ② 重新打包（jniLibs 变化）
adb install app/build/outputs/apk/debug/app-debug.apk
```

真机首启流程：解包（进度条）→ PRoot 起 uvicorn → /health → 对话页。
灰度用户反馈渠道：设置页日志导出（PythonSupervisor 环形日志 + 审计日志）。

## 灰度后待办（按优先级）

1. linker64/patchelf 真机方案验证（阻断项）
2. 操作确认气泡 UI（解锁写操作）
3. capabilities 变更时 Agent 重建（refresh_tools 已有协议，Kotlin 侧触发未接）
4. 多 WS 会话生命周期（PROTOCOL §4）
5. llama.cpp 本地推理进镜像（可选）
