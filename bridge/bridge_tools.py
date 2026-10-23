# -*- coding: utf-8 -*-
"""bridge_tools — android.* 工具的注册适配层（不动 forge 原码）。

forge 的 ToolRegistry 支持运行时注册工具对象（带 name/desc/参数 schema/
invoke）。这里把每个 android.* 包成这样的对象；invoke 即把请求推给
_dispatch_android_tool（→ WS → Kotlin 双通道 → /tool/call 回传）。

Agent.attach_tools(list) 是桥侧给 Agent 加的薄方法（见 forge_bridge 的
monkey-patch 安装，仅当 forge.loop.Agent 无此方法时打上——对 forge 的
改动为零：方法挂在外部子类上，build_agent 返回值原样可用）。
"""
from __future__ import annotations

import asyncio
from typing import Any, Callable


class BridgeTool:
    """forge ToolRegistry 兼容的最小工具对象。"""

    def __init__(self, name: str, description: str, parameters: dict[str, Any],
                 invoke: Callable[[dict[str, Any]], Any]):
        self.name = name
        self.description = description
        self.parameters = parameters      # JSON-Schema 子集：{name: {type, description, required}}
        self._invoke = invoke

    def invoke(self, arguments: dict[str, Any]) -> Any:
        return self._invoke(arguments)


_PT = {"type": "object", "properties": {}}


def make_android_tools(dispatch, emit) -> list[BridgeTool]:
    """dispatch: async (tool, args, emit) -> dict。返回注册表兼容列表。"""

    def _sync_wrap(tool: str):
        def run(args: dict[str, Any]) -> dict[str, Any]:
            return asyncio.run(dispatch(tool, args, emit))
        return run

    def simple(name: str, desc: str, params: dict[str, Any] | None = None) -> BridgeTool:
        return BridgeTool(
            name=name, description=desc,
            parameters=params or {"type": "object", "properties": {}},
            invoke=_sync_wrap(name),
        )

    xy = {
        "type": "object",
        "properties": {
            "x": {"type": "integer", "description": "屏幕像素 X"},
            "y": {"type": "integer", "description": "屏幕像素 Y"},
        },
        "required": ["x", "y"],
    }
    swipe = {
        "type": "object",
        "properties": {
            "x1": {"type": "integer"}, "y1": {"type": "integer"},
            "x2": {"type": "integer"}, "y2": {"type": "integer"},
            "duration_ms": {"type": "integer", "description": "默认 300"},
        },
        "required": ["x1", "y1", "x2", "y2"],
    }
    text_p = {
        "type": "object",
        "properties": {"text": {"type": "string"}},
        "required": ["text"],
    }
    pkg_p = {
        "type": "object",
        "properties": {"package": {"type": "string"}},
        "required": ["package"],
    }

    return [
        simple("android.screen.inspect", "读取当前屏幕 UI 树（控件/文本/坐标），只读"),
        simple("android.screen.capture", "截取当前屏幕（返回缩略 PNG base64，≤1MP）"),
        simple("android.ui.click", "点击屏幕坐标", xy),
        simple("android.ui.swipe", "滑动手势", swipe),
        simple("android.ui.input", "向焦点输入框输入文本", text_p),
        simple("android.app.list", "列出已安装应用包名"),
        simple("android.app.launch", "启动指定应用", pkg_p),
        simple("android.device.info", "设备信息（型号/SDK/分辨率/DPI）"),
    ]


def install_attach_tools() -> None:
    """给 forge.loop.Agent 挂 attach_tools（子类法，零侵入）。"""
    from forge.loop import Agent

    if hasattr(Agent, "attach_tools"):
        return

    def attach_tools(self, tools: list[Any]) -> None:
        """桥侧注册：把工具对象并入本 Agent 的注册表（幂等）。"""
        registry = getattr(self, "_bridge_tools", None)
        if registry is None:
            registry = []
            self._bridge_tools = registry
        existing = {getattr(t, "name", "") for t in registry}
        for t in tools:
            if getattr(t, "name", "") not in existing:
                registry.append(t)
        # forge Agent 若暴露 tools 合并面（不同版本字段名可能为
        # _extra_tools / extensions），统一挂 _bridge_tools；run 前由
        # build 侧合并。这里保守：只存不并，执行面见 forge_bridge._run_agent。

    Agent.attach_tools = attach_tools
