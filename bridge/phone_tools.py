# -*- coding: utf-8 -*-
"""phone_tools — 手机原生工具（短信/电话/联系人），走设备通道不依赖任何 MCP server。

为什么不做 MCP：短信/通话记录/联系人是设备本地数据，Shizuku（ADB shell
身份）一条 content query 就能读；引第三方 MCP server 反而多一层进程、
多一层信任。这些工具直接注册进 Agent 工具面，与 android.* 同通道：
  - 读取（短信/记录/联系人搜索）→ 只读，直接执行
  - 写入（发短信/拨号）→ 走确认（与 android.ui.click 同纪律）

经 WS tool_call 事件由 Kotlin 执行（DeviceTools 路由），Python 侧只
定义工具与回传语义——实现纪律与 android.* 完全一致。
"""

PHONE_TOOLS = [
    {
        "name": "phone.sms.list",
        "description": "读取最近短信（发件人/时间/正文摘要），只读",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "description": "条数，默认 20，上限 100"},
            "sender": {"type": "string", "description": "按发件人号码过滤（可选）"},
        }},
        "readOnly": True,
    },
    {
        "name": "phone.sms.search",
        "description": "按关键词搜短信正文，只读",
        "parameters": {"type": "object", "properties": {
            "keyword": {"type": "string"},
            "limit": {"type": "integer"},
        }, "required": ["keyword"]},
        "readOnly": True,
    },
    {
        "name": "phone.sms.send",
        "description": "发送短信（需确认）",
        "parameters": {"type": "object", "properties": {
            "to": {"type": "string"}, "body": {"type": "string"},
        }, "required": ["to", "body"]},
        "readOnly": False,
    },
    {
        "name": "phone.call.dial",
        "description": "拨打电话（需确认，拉起拨号界面）",
        "parameters": {"type": "object", "properties": {
            "number": {"type": "string"},
        }, "required": ["number"]},
        "readOnly": False,
    },
    {
        "name": "phone.call.log",
        "description": "读取最近通话记录（号码/类型/时间），只读",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer"},
        }},
        "readOnly": True,
    },
    {
        "name": "phone.contacts.search",
        "description": "搜索联系人（姓名/号码模糊匹配），只读",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string"},
        }, "required": ["query"]},
        "readOnly": True,
    },
]

# Kotlin DeviceTools 侧的实现路由（PROTOCOL 增补）：
#   phone.sms.list   → Shizuku: content query --uri content://sms/inbox
#                      --projection address:date:body --sort "date DESC"
#   phone.sms.search → 同上 + 全量扫（上限 500 条）关键词过滤
#   phone.sms.send   → 确认气泡 → am start -a android.intent.action.SENDTO
#                      sms:<number> --es sms_body <body>（拉起系统短信确认发送）
#   phone.call.dial  → 确认气泡 → am start -a android.intent.action.DIAL -d tel:<n>
#   phone.call.log   → content query --uri content://call_log/calls
#   phone.contacts.search → content query --uri content://com.android.contacts/
#                      phone_lookup --projection display_name:number
#
# 权限前提：READ_SMS / READ_CALL_LOG / READ_CONTACTS（Shizuku shell 身份
# 天然持有；无障碍降级时这些工具不注册——不虚报能力）。
