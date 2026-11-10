# 真机验收记录（2026-11-03 · libra / Mi 4c · LineageOS 11 / API 30）

对象：7e29800 + 本日修复（CatalogScreen Saver）。清单出处：AUDIT-2026-11-02.md「真机验收清单（交给 OpenClaw）」。

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 全新安装首屏：转圈→引导页，不直进聊天 | ✅ | 首屏为「配置模型 API Key / 跳过」引导页 |
| 2 | 配 DeepSeek 问「北京今天天气」：联网搜索+来源 | ✅ | web_search+fetch_url 真执行；回答「晴转阴 5~18℃」附中国天气网来源（2026-11-03 11:30 更新）；steps=2 实数、无 cost 字段 |
| 3 | 读 example.com；读 127.0.0.1:8000/health 应拒绝 | ✅ | 前者 ok=true（模型准确复述 Example Domain）；后者 ok=false，原话 `127.0.0.1 is a private or local address; not fetched`，任务正常收尾 |
| 4 | 开无障碍后截图描述：回执不含图片、不卡 35s | ✅ | Accessibility 截图成功（API 30 实证）；回执仅 `capture-*.png` 文件引用零像素；模型诚实声明看不到图，转用 UI 树准确描述引导页实况 |
| 5 | 点击确认 20s 不点：报未完成、屏幕无点击 | ✅ | 两条路径：①点自家 UI 被宿主直接拒绝（"A visible external accessibility target is required"）②外部目标（系统设置「显示」项）两次 "Not approved or approval expired"、not_started、页面零变化、模型如实上报 |
| 6 | 助理/主对话切换不打断在跑任务 | ✅ | 800 字长文任务运行中完成 4 次页面往返（目录→对话→遥控→对话），run_done final |
| 7 | forge.patch.json apiKey 以 ks1: 开头 | ✅ | `/onboarding/apply` 后落盘 `ks1:r4Px5y…`（Android Keystore AES-GCM）；has_provider=true |

## 本轮发现并已修复

- **CatalogScreen 崩溃（FATAL，11-02 23:35 崩溃栈实证）**：`rememberSaveable { mutableStateMapOf() }` —— SnapshotStateMap 不能进 Bundle，状态保存时 `IllegalArgumentException` 炸进程，连带无障碍服务进入 Crashed 状态无法重绑。修复：显式 `Saver(save={HashMap(it.toMap())}, restore={…putAll})`。重装后 Bound services 恢复、全量能力（含写工具）上报。

## 遗留观察（不阻断）

1. **确认气泡在外部 App 前台时未渲染**：点击系统设置条目时走满超时语义（not_started、零点击），但屏幕上没有出现确认气泡——用户没有视觉机会批准。建议改为常驻 Overlay Service 而非 Activity 生命周期绑定的气泡。
2. 引导页大标题文案仍为 "forge"（应用 label 已是 Steward，dumpsys 显示「Steward 设备操作」）——硬编码文案，改名未尽。
3. Shizuku 未安装（本机条件所限），Shizuku 通道（app.list/app.launch 之外的提权面）未验证。
4. 取消（用户主动 cancel）在宿主亚秒执行语义下仍无自然触发窗口，`run_failed(cancelled)` 事件维持代码级验证。

测试配方：adb forward 18000→8000；token=shared_prefs/bridge-private.xml（run-as 读取）；真模型=deepseek-chat（key 来自桌面 ~/.forge/keys.json，经 /onboarding/apply 写入）；无障碍=adb settings put secure + appops SYSTEM_ALERT_WINDOW allow；重装可清除无障碍 Crashed 状态。

## 2026-11-03 后端复核补充

复核设备：`ebb4d24f`（`libra / Mi 4c / API 30`）。安装并启动
`app/build/outputs/apk/debug/app-debug.apk` 后，`AgentBridgeService` 保持
`isForeground=true`，转发到 `127.0.0.1:18000` 的 `/health` 返回
`status=ok`、`native=true`、`agent_ready=true`。

- 带安装级 Bearer token 的 `/onboarding/status` 返回 `has_provider=true`；
  当前 Provider 为 `deepseek-chat`，API Key 以 `ks1:` Keystore 密文形式落盘。
- WebSocket `/agent/ws?session=ws-check` 收到 `ready`，发送 `tool_result` 后继续收到
  `pong`；重复 session 被拒绝，`../../escape` session 被拒绝。
- 未认证的 `/assistant/catalog` 返回 401；非法工具回执返回 422；空任务的
  `/agent/run` 返回 422。
- `uiautomator dump` 显示主界面 `桥: ready`、对话/目录/遥控三项导航和任务输入框；
  最近 500 条 logcat 未出现 `FATAL EXCEPTION` 或 `AndroidRuntime` 崩溃。

本次工具进程执行 Gradle 时遇到 Java/Gradle 本机 loopback 连接异常；使用仓库已有的
已构建 debug APK 完成了上述安装和真机验证，未将该环境故障误判为应用运行时故障。

## 后端任务生命周期优化（本轮构建与真机复测）

- `AgentRunCoordinator` 统一任务准入、会话取消和完成后的释放；清理结束前拒绝新任务，
  避免旧任务清掉新任务状态，并处理尚未开始执行就被取消的任务。
- 设备回执等待中的取消继续抛出 `CancellationException`，不再转换为普通的
  `unknown` 回执；工具循环增加取消检查。
- 模型请求使用 OkHttp 异步回调和 `suspendCancellableCoroutine`，取消直接关闭对应请求，
  移除共享 `activeCall` 的注册/取消竞态。
- 引导页和模型加载器共用 `ProviderPolicy`；统一 keyless/本地模型判断，并检查端点及解密后的密钥。

验证：`:app:testDebugUnitTest :app:assembleDebug` 成功；26 项测试，0 失败、0 错误。
新增 6 项测试覆盖任务交接、快速完成、执行前取消、停止后拒绝任务以及 Provider 配置边界。
APK SHA-256：`7239770b99c6a47cf48edbdfc939f4009cdf0356858602a0959a5dae3fd31b26`。

本次通过指定独立 socket 临时目录绕过此前的 Gradle loopback 故障，使用的 PowerShell 环境为：

```powershell
$env:JAVA_HOME = 'C:\jdk'
$env:ANDROID_HOME = 'C:\sdk'
$env:ANDROID_SDK_ROOT = 'C:\sdk'
$env:GRADLE_USER_HOME = 'C:\gradle-fresh'
New-Item -ItemType Directory -Force C:\gradle-fresh\socket-tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\gradle-fresh\socket-tmp'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

首次安装时设备断开；重新连接 `ebb4d24f` 后，同一 SHA-256 的新版 APK 已通过
`adb install -r` 安装成功，并重新启动应用。新增真机结果如下：

- `/health` 返回 `status=ok`、`native=true`、`agent_ready=true`；
  `/onboarding/status` 返回 `has_provider=true`。
- WS 会话 `backend-lifecycle-test` 收到 `run_started` 后发送取消，约 63 ms 收到
  `run_failed(execution_state=cancelled)`，没有误报 `run_done(unknown)`。
- 同一会话随后执行 `1 + 1`，收到 `run_done(execution_state=final, steps=1, answer=2)`。
- 发送无匹配待处理调用的 `tool_result` 后，仍能正常收到 `pong`。
- `AgentBridgeService` 为 `isForeground=true`；本次过滤的 AndroidRuntime、
  NativeAgentEngine、NativeBridgeServer 错误日志无记录。

边界：上述取消在收到 `run_started` 后立即发出，未单独覆盖长时间等待设备工具回执的
取消场景；该路径仍需专门真机用例。测试脚本位于本地构建目录
`app/build/backend-regression/verify_device.py`（不纳入版本控制，不包含凭据）。
