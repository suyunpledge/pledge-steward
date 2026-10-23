# forge-android 桥协议 v0.2（PROTOCOL.md）

Kotlin 壳 ↔ forge_bridge（Python/FastAPI）唯一通道契约。JSON 文本帧；`t` 为 Unix 秒。

## 1. WebSocket `/agent/ws?session=<id>`（App 主通道）

### App → Python

| type | 字段 | 说明 |
|---|---|---|
| `run` | `task: str`, `history?: list` | 一轮任务 |
| `ping` | — | 保活 |
| `reset` | — | 丢弃会话 Agent |

### Python → App

| type | 字段 | 说明 |
|---|---|---|
| `ready` | `session`, `uptime` | 建连接即发 |
| `agent.<evt>` | forge observer 事件 | step / tool_decision / tool_call / assistant_message / compaction / subagent_spawn … |
| `tool_call` | `call_id`, `tool`, `arguments`, `execution?` | **设备工具请求**（android.*）；Kotlin 执行后 POST /tool/call 回传 |
| `capabilities` | `tools: [str]` | 工具能力上报（Shizuku 上/下线时 Kotlin 主动请求刷新） |
| `run_done` | `answer`, `steps`, `cost` | 一轮结束 |
| `run_failed` | `error` | 一轮异常（进程不崩） |
| `pong` / `error` / `reset_ok` | — | 控制面 |

## 2. HTTP

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| GET | `/health` | — | `{status, pid, uptime}` |
| POST | `/agent/run` | `{task, history?}` | SSE（调试用） |
| POST | `/tool/call` | `{call_id, ok, result?, error?}` | `{accepted}` / 404 |
| POST | `/capabilities` | `{tools: [str]}` | 刷新可注册工具面（Shizuku 状态变化时） |
| POST | `/remote/*` | — | 501 预留 |

## 3. 工具命名空间与通道

| 工具 | 参数 | 通道选择 | result |
|---|---|---|---|
| `android.screen.inspect` | `{}` | Accessibility dump；Shizuku `uiautomator dump` 备选 | `{xml}`（≤64KB 截断） |
| `android.screen.capture` | `{max_side?}` | Shizuku `screencap`；无障碍 takeScreenshot 备选 | `{width,height,base64_png}`（≤1MP） |
| `android.ui.click` | `{x,y}` | Accessibility 手势；Shizuku `input tap` 兜底 | `{performed:true}` |
| `android.ui.swipe` | `{x1,y1,x2,y2,duration_ms?}` | 同上（`input swipe`） | `{performed:true}` |
| `android.ui.input` | `{text}` | Accessibility SET_TEXT；Shizuku `input text`/广播输入法 | `{set:true}` |
| `android.app.list` | `{}` | Shizuku `pm list packages`；离线 packageManager | `{packages:[…]}`（截断 200） |
| `android.app.launch` | `{package}` | Shizuku `monkey -p`/`am start`；离线 launchIntent | `{started:true}` |
| `android.device.info` | `{}` | 系统 API | `{model,sdk,screen,dpi,…}` |
| `linux.shell.execute` | `{cmd, timeout_ms?}` | PRoot 内执行 | `{code, stdout, stderr}`（各 ≤32KB） |
| `linux.file.read/write` | 路径 | PRoot 文件系统 | 内容/ `{written:n}` |

约定：
- `execution.backend`: `auto`/`accessibility`/`shizuku`；`auto` = Kotlin 侧择优。
- Python 侧 30s 超时；超时 `{ok:false,error:"device tool timeout"}` 仍回喂 Agent。
- `ok:false` 结果对模型可见；输入结果未知时不得自动换通道重试，须重新观察并确认。
- 坐标 = 真实屏幕像素，DPI 换算在 Kotlin 完成。

## 4. Shizuku 降级状态机（Kotlin 侧事实，上报给 Python）

| 状态 | 触发 | 工具面变化 |
|---|---|---|
| `shizuku.online` | binder 连通 + 权限授予 | 全量 android.*（含 shell 系） |
| `shizuku.offline` | 重启后未启动/断开 | 移除 shell 依赖项；click/input 仍可用（无障碍）；capture 降级或禁用 |
| `shizuku.denied` | 用户拒绝授权 | 同 offline，UI 提示入口 |
| 恢复 | binder 重连 | Kotlin POST /capabilities 刷新，Agent 下轮可见 |

**铁律：Shizuku 离线 ≠ App 不可用。** 对话与 linux.* 永远可用。

## 5. 安全边界

- uvicorn 只听 127.0.0.1:8000；Android loopback 可被其他 App 访问。
- 除 `/health` 外 HTTP 控制接口及 WS 必须带安装级 `Authorization: Bearer <token>`；不在 URL、图片、日志中传 token。
- forge Policy 照常裁决：android.* 在 Python 侧是注册工具，mode×sandbox 全程生效。
- 点击/输入的 Kotlin 确认气泡尚未实现，当前宿主返回 `approval_unavailable/not_started`；Policy 允许也不能绕过。
- 截图 base64 只在内存流转，不落盘（与桌面 computer_capture 同纪律）。
- 审计：每次 android.* 执行记录 `{ts, tool, args 摘要, backend, ok}` 环形日志（UI 可查）。

## 6. 远程控制电脑 forge（v0.2+ 预留）

- `POST /remote/session`：token 协商建立反向会话。
- `POST /remote/event`：桌面 computer_use 事件流（observe/act/click 审批）转发。
- Kotlin 侧 `RemoteControl.kt` 维护反向 WS；断线重连语义与 §4 同法。
