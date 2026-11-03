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
