# Android Bridge v0.2 实际契约（2026-10-24）

仅监听 127.0.0.1:8000。除 GET /health 外，HTTP 与 WS 都需要安装级 `Authorization: Bearer <token>`。
loopback 不是 App 私有网络；token 不能放入 URL。

## WS /agent/ws?session=<id>

session 为 1～64 位字母、数字、下划线或连字符。同会话只允许一个连接和一个活跃任务。
断开时设置协作取消；工作线程实际退出前继续占用会话，拒绝重入，不自动重放。

| 方向 | type | 字段 / 行为 |
|---|---|---|
| App→桥 | run | task 非空且≤32000字符，history 可选列表；忙时 error |
| App→桥 | cancel | 设置取消 token，发 cancel_tools 和 cancel_requested；不保证当前模型请求立即返回 |
| App→桥 | ping | 随时返回 pong |
| App→桥 | reset | 空闲时重建 Agent，保留磁盘会话文件；忙时拒绝 |
| App→桥 | refresh_tools | 返回当前宿主能力快照 |
| 桥→App | ready | session、tools；实际 Agent 构造成功后发送 |
| 桥→App | run_started | RunRequest 的 run_id/task_id/parent_run_id 等公开字段 |
| 桥→App | agent.<type> | 现有核心 observer 事件，不另行解析工具协议 |
| 桥→App | tool_call | call_id、tool、arguments；设备工具宿主请求 |
| 桥→App | cancel_tool | call_id；调用超时，撤销待处理操作 |
| 桥→App | cancel_tools | 撤销当前连接的待处理设备操作 |
| 桥→App | run_done | 同一 RunRequest 字段、execution_state、answer、steps、cost |
| 桥→App | run_failed | 同一 RunRequest 字段、execution_state=failed、error |
| 桥→App | error / fatal | 协议拒绝 / Agent 构造失败等错误 |

run_done 表示执行返回，不代表任务成功：execution_state 可为 cancelled、unknown_outcome 或核心其他停止原因。

## HTTP

| 路径 | 请求 / 行为 |
|---|---|
| GET /health | 未认证可用，仅健康信息 |
| POST /capabilities | tools:[str]、approval:bool（默认false）、shizuku:str；只保留已知工具，approval=false 移除写工具 |
| POST /tool/call | call_id、ok、result?、error?、execution_state?:completed/not_started/unknown；只结算未过期 waiter 一次，迟到/重复 404 |
| POST /agent/run | task/history；SSE 调试入口，会话 sse 互斥（忙时409），断连协作取消 |
| POST /remote/* | 501，尚未实现 |

Kotlin 每 3 秒核对实际能力，只在变化或重连时上报；Shizuku online 和 UserService 已绑定分别判断。
Python 每轮执行前按最新快照注册 ToolSpec，核心 Policy 先裁决，宿主执行时再次检查当前能力。
能力变化不会重建正在运行的 Agent，也不会扩大其权限。

## 设备工具与输入安全

schema 由 bridge_tools.py 定义，宿主再次验证坐标、时长、文本长度和包名。
click/swipe/input 仅在无障碍与确认权限均就绪时暴露；launch 需确认权限。
弹窗显示完整操作参数，用户只能批准当次，20秒过期；取消/断线撤销。
UI 输入要求外部无障碍目标，拒绝 Forge 自身；批准前后 UI 树不一致则拒绝，需要重新观察。

每次输入只调用一个后端，失败不自动换通道。completed 仅表示收到后端回执，不代表业务目标完成。
Python 等待设备结果30秒，超时发 cancel_tool，写操作记 unknown；适配器等待上限35秒。
未知结果须重新观察并由用户重新确认，不能声称 exactly-once。

screen.inspect 返回 xml；capture 返回 base64_png/source，max_side限制320～1440。
坐标为真实屏幕像素；缩小的截图不能直接作为点击坐标，应结合 device.info 的原始屏幕尺寸换算。
应用列表最多200条且受平台可见性限制。当前未开放任意 Android shell。
截图主要在内存传输；Shizuku screencap 临时文件在 finally 删除，进程异常终止仍可能留下缓存文件。
内部环形审计并非持久审计系统，尚无图形日志导出页。
