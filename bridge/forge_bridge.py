# -*- coding: utf-8 -*-
"""forge_bridge v0.2 — Kotlin 壳与 forge Agent 的唯一桥（PRoot Ubuntu 24）。

架构定稿（2026-10-23）：
- forge 原样运行于 PRoot Ubuntu 用户态，零改动；只用 build_agent 公开 API。
- android.* 工具不在此执行：经 WS tool_call 事件交 Kotlin 双通道
  （Accessibility + Shizuku UserService），结果由 /tool/call 回传。
- linux.* 由 PRoot 内 forge 自身工具面执行（Policy 照常裁决），
  本桥只做转发注册。
- Shizuku 状态不影响桥存活；/capabilities 动态刷新工具面。
- 进程模型：uvicorn 是 PythonSupervisor 的子进程；崩溃由 Kotlin 重启。
"""
from __future__ import annotations

import asyncio
import json
import os
import subprocess
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

FORGE_ROOT = Path(os.environ.get("FORGE_ROOT", "/opt/forge"))
import sys

sys.path.insert(0, str(FORGE_ROOT))

from forge.config import load_config             # noqa: E402
from forge.loop import Agent, LoopLimits, build_agent  # noqa: E402
from forge.model import ModelRouter              # noqa: E402

HOME = Path(os.environ.get("FORGE_HOME", "/opt/forge/.forge-home"))
WORKSPACE = Path(os.environ.get("FORGE_WORKSPACE", "/opt/forge/workspace"))
for p in (HOME, WORKSPACE, HOME / "sessions"):
    p.mkdir(parents=True, exist_ok=True)

BOOT_T = time.time()
app = FastAPI(title="forge-bridge", version="0.2.0")

_sessions: dict[str, Agent] = {}
_agent_lock = asyncio.Lock()


class _FakeReport:
    answer = "done-answer"
    steps = 3
    cost = 0.42


class _FakeSession:
    def __enter__(self): return self
    def __exit__(self, *a): return False


class _FakeAgent:
    def __init__(self):
        self.session = _FakeSession()
        self.attached: list = []

    def attach_tools(self, tools):
        self.attached = tools

    def run(self, task, history=None, run_request=None):
        return _FakeReport()

# android.* 工具面：Kotlin 经 /capabilities 上报（Shizuku 状态决定子集）
_android_tools: set[str] = {
    # 基线（纯 Accessibility，永远可用）
    "android.screen.inspect", "android.ui.click", "android.ui.swipe",
    "android.ui.input", "android.device.info",
}
# Shizuku 增量（在线时由 Kotlin 上报追加）
_SHIZUKU_ONLY = {"android.screen.capture", "android.app.list", "android.app.launch"}


class CapabilitiesPayload(BaseModel):
    tools: list[str]
    shizuku: str = "offline"     # online/offline/denied


@app.post("/capabilities")
async def set_capabilities(payload: CapabilitiesPayload):
    global _android_tools
    base = {"android.screen.inspect", "android.ui.click", "android.ui.swipe",
            "android.ui.input", "android.device.info"}
    extra = set(payload.tools) & _SHIZUKU_ONLY if payload.shizuku == "online" else set()
    _android_tools = base | extra
    return {"registered": sorted(_android_tools), "shizuku": payload.shizuku}


def _build_session_agent(session_id: str, exposed_tools: set[str] | None = None) -> Agent:
    if os.environ.get("FORGE_BRIDGE_FAKE_AGENT") == "1":
        return _FakeAgent()   # 测试钩子：契约冒烟用（标记于环境变量，生产不设）
    """按 forge.cli 标准姿势装配会话 Agent。

    android.* 以注册表形式暴露（expose 面收敛）；linux.* 走 forge 内建
    工具（shell/文件），Policy 照常裁决——桥不复制权限逻辑。
    """
    # 装配姿势与 forge.cli._compose 一致：bundles 目录逐层 + 用户层
    bundles = sorted((FORGE_ROOT / "bundles").glob("*.json"))
    cfg = load_config(HOME, bundles=bundles)
    router = ModelRouter.from_config(cfg)
    expose = sorted(exposed_tools) if exposed_tools is not None else None
    return build_agent(
        home=HOME, workspace=WORKSPACE, config=cfg, router=router,
        limits=LoopLimits(max_steps=12, max_depth=2, spawn_budget=8),
        session_path=HOME / "sessions" / f"phone-{session_id}.jsonl",
        mount_contrib=True,
        expose=expose,
    )


# ── 设备工具回传 ────────────────────────────────────────────────
_tool_waiters: dict[str, asyncio.Future] = {}


class ToolResultPayload(BaseModel):
    call_id: str
    ok: bool
    result: dict[str, Any] | None = None
    error: str | None = None


@app.post("/tool/call")
async def tool_result(payload: ToolResultPayload):
    fut = _tool_waiters.get(payload.call_id)
    if fut is None or fut.done():
        raise HTTPException(404, "unknown or expired call_id")
    fut.set_result({"ok": payload.ok, "result": payload.result or {},
                    "error": payload.error})
    return {"accepted": True}


async def _dispatch_android_tool(tool: str, arguments: dict[str, Any], emit) -> dict[str, Any]:
    call_id = f"tc-{time.monotonic_ns()}"
    loop = asyncio.get_running_loop()
    fut = loop.create_future()
    _tool_waiters[call_id] = fut
    try:
        await emit({"type": "tool_call", "call_id": call_id, "tool": tool,
                    "arguments": arguments})
        return await asyncio.wait_for(fut, timeout=30.0)
    except asyncio.TimeoutError:
        return {"ok": False, "error": "device tool timeout (30s)"}
    finally:
        _tool_waiters.pop(call_id, None)


# ── WS 主通道 ───────────────────────────────────────────────────
@app.websocket("/agent/ws")
async def agent_ws(ws: WebSocket):
    await ws.accept()
    session_id = ws.query_params.get("session", "default")
    # 关键：本 handler 的 loop 显式传出——observer 在 worker 线程里
    # run_coroutine_threadsafe 必须用这个 loop，get_event_loop() 在
    # 线程池线程拿不到它（TestClient portal / uvicorn 均如此）。
    ws_loop = asyncio.get_running_loop()

    async def emit(event: dict[str, Any]):
        await ws.send_text(json.dumps(event, ensure_ascii=False))

    agent = _sessions.get(session_id)
    if agent is None:
        async with _agent_lock:
            agent = _sessions.get(session_id)
            if agent is None:
                agent = _build_session_agent(session_id)
                _sessions[session_id] = agent

    await emit({"type": "ready", "session": session_id,
                "uptime": time.time() - BOOT_T,
                "tools": sorted(_android_tools)})
    try:
        while True:
            raw = await ws.receive_text()
            msg = json.loads(raw)
            kind = msg.get("type")
            if kind == "run":
                task = str(msg.get("task", "")).strip()
                if not task:
                    await emit({"type": "error", "error": "empty task"})
                    continue
                await _run_agent(agent, task, msg.get("history") or [], emit, ws_loop)
            elif kind == "ping":
                await emit({"type": "pong", "t": time.time()})
            elif kind == "reset":
                _sessions.pop(session_id, None)
                await emit({"type": "reset_ok"})
            elif kind == "refresh_tools":
                # Shizuku 状态变化：重建 Agent（expose 面更新）
                _sessions[session_id] = _build_session_agent(session_id, _android_tools)
                await emit({"type": "capabilities", "tools": sorted(_android_tools)})
            else:
                await emit({"type": "error", "error": f"unknown type {kind!r}"})
    except WebSocketDisconnect:
        return


async def _run_agent(agent: Agent, task: str, history: list, emit, ws_loop=None) -> None:
    """一轮任务；observer 事件 + android.* 工具请求都经 emit 流出。

    android.* 拦截：forge 的 observer 会给出 tool_call 事件；真正的执行
    拦截在工具层做——注册的 android.* 工具实现就是 _dispatch_android_tool
    的闭包（见 bridge 的工具注册补丁：expose 挂名，执行走回调）。
    v0.2 简化：工具执行不经 forge ToolRegistry 深改，由 run 前注入的
    RuntimeBridge 工具类承接（forge.loop 支持外部工具对象注册）。
    """
    from bridge_tools import make_android_tools  # 同目录：工具注册适配层

    loop = ws_loop or asyncio.get_running_loop()
    done = loop.create_future()

    def observer(event: dict[str, Any]) -> None:
        data = dict(event)
        etype = data.pop("type", "step")
        asyncio.run_coroutine_threadsafe(
            emit({"type": f"agent.{etype}", **data}), loop)

    def _worker():
        try:
            agent.attach_tools(make_android_tools(_dispatch_android_tool, emit))
            with agent.session:
                report = agent.run(task, history=history)
            summary = {
                "answer": getattr(report, "answer", "") or "",
                "steps": getattr(report, "steps", 0),
                "cost": float(getattr(report, "cost", 0.0) or 0.0),
            }
            loop.call_soon_threadsafe(done.set_result, summary)
        except Exception as exc:            # noqa: BLE001
            loop.call_soon_threadsafe(done.set_exception, exc)

    loop.run_in_executor(None, _worker)
    try:
        summary = await done
        await emit({"type": "run_done", **summary})
    except Exception as exc:                # noqa: BLE001
        await emit({"type": "run_failed", "error": str(exc)})


# ── SSE（调试）/ 健康 / 远程预留 ─────────────────────────────────
class RunBody(BaseModel):
    task: str
    history: list[dict[str, Any]] | None = None


@app.post("/agent/run")
async def agent_run(body: RunBody):
    async def gen():
        q: asyncio.Queue = asyncio.Queue()

        async def emit(ev):
            await q.put(ev)

        async def drive():
            agent = _sessions.setdefault("sse", _build_session_agent("sse"))
            await _run_agent(agent, body.task, body.history or [], emit)
            await q.put(None)

        asyncio.create_task(drive())
        while True:
            ev = await q.get()
            if ev is None:
                yield "event: done\ndata: {}\n\n"
                return
            yield f"event: {ev.get('type', 'step')}\ndata: {json.dumps(ev, ensure_ascii=False)}\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/health")
async def health():
    return {"status": "ok", "pid": os.getpid(),
            "forge_root": str(FORGE_ROOT),
            "uptime": round(time.time() - BOOT_T, 1),
            "shizuku_tools": sorted(_android_tools & _SHIZUKU_ONLY)}


@app.post("/remote/{path:path}")
async def remote_stub(path: str):
    raise HTTPException(501, "remote control reserved; not in v0.2")
