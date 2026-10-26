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
import secrets
import re
import threading
from typing import Literal
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel, StrictBool

FORGE_ROOT = Path(os.environ.get("FORGE_ROOT", "/opt/forge"))
import sys

sys.path.insert(0, str(FORGE_ROOT))

from forge.config import load_config             # noqa: E402
from forge.loop import Agent, LoopLimits, build_agent  # noqa: E402
from forge.execution import RunRequest
from forge.model import ModelRouter              # noqa: E402

HOME = Path(os.environ.get("FORGE_HOME", "/opt/forge/.forge-home"))
WORKSPACE = Path(os.environ.get("FORGE_WORKSPACE", "/opt/forge/workspace"))
for p in (HOME, WORKSPACE, HOME / "sessions"):
    p.mkdir(parents=True, exist_ok=True)

BOOT_T = time.time()
app = FastAPI(title="forge-bridge", version="0.2.0")
TOKEN = os.environ.get('FORGE_BRIDGE_TOKEN', '')


def authorized(value):
    return len(TOKEN) >= 32 and secrets.compare_digest(
        (value or '').encode('utf-8'), ('Bearer ' + TOKEN).encode('utf-8'))


@app.middleware('http')
async def authenticate(request, call_next):
    # Android loopback is shared with other apps, not an app-private namespace.
    if request.url.path != '/health' and not authorized(request.headers.get('authorization')):
        return JSONResponse({'detail':'unauthorized'},status_code=401)
    return await call_next(request)

_sessions: dict[str, Agent] = {}
_agent_lock = asyncio.Lock()
_connected_sessions=set()
_sessions_busy=set()
_device_approval=False


# android.* 工具面：Kotlin 经 /capabilities 上报（Shizuku 状态决定子集）
_android_tools: set[str] = set()  # No device tools until the authenticated host reports readiness.
# Shizuku 增量（在线时由 Kotlin 上报追加）
_SHIZUKU_ONLY = {"android.screen.capture", "android.app.list", "android.app.launch"}


class CapabilitiesPayload(BaseModel):
    tools: list[str]
    approval: bool = False
    shizuku: str = "offline"     # online/offline/denied


@app.post("/capabilities")
async def set_capabilities(payload: CapabilitiesPayload):
    global _android_tools, _device_approval
    from bridge_tools import READ_TOOLS, WRITE_TOOLS
    _device_approval = payload.approval
    _android_tools = set(payload.tools) & (READ_TOOLS | (WRITE_TOOLS if payload.approval else set()))
    return {"registered": sorted(_android_tools), "shizuku": payload.shizuku}


def _build_session_agent(session_id: str, exposed_tools: set[str] | None = None) -> Agent:
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
    ok: StrictBool
    result: dict[str, Any] | None = None
    error: str | None = None
    execution_state: Literal["completed","not_started","unknown"] | None = None
    error_code: str | None = None


@app.post("/tool/call")
async def tool_result(payload: ToolResultPayload):
    fut = _tool_waiters.get(payload.call_id)
    if fut is None or fut.done():
        raise HTTPException(404, "unknown or expired call_id")
    fut.set_result({"ok": payload.ok, "result": payload.result or {},
                    "error": payload.error, "execution_state":payload.execution_state, 'error_code':payload.error_code})
    return {"accepted": True}


DEVICE_TOOL_TIMEOUT = 30.0
CANCEL_RECEIPT_TIMEOUT = 3.0


async def _dispatch_android_tool(tool: str, arguments: dict[str, Any], emit, *, cancellation=None, identity=None) -> dict[str, Any]:
    from bridge_tools import WRITE_TOOLS
    if cancellation is not None and cancellation.is_set():
        return {'ok':False, 'error':'Cancelled before device dispatch', 'execution_state':'not_started', 'error_code':'cancelled'}
    call_id = f"tc-{time.monotonic_ns()}"
    loop = asyncio.get_running_loop()
    fut = loop.create_future()
    _tool_waiters[call_id] = fut
    watcher = None
    sent = False
    cancel_sent = False
    async def revoke():
        nonlocal cancel_sent
        if not cancel_sent:
            cancel_sent = True
            await emit({'type':'cancel_tool','call_id':call_id, **(identity or {})})
    async def watch_cancel():
        while not fut.done():
            if cancellation is not None and cancellation.is_set():
                await revoke()
                # The host can still prove approval was pending or input completed.
                await asyncio.sleep(CANCEL_RECEIPT_TIMEOUT)
                if not fut.done():
                    fut.set_result({'ok':False, 'error':'Cancelled without a device receipt; inspect before retry',
                        'execution_state':'unknown' if tool in WRITE_TOOLS else 'not_started',
                        'error_code':'device_receipt_lost' if tool in WRITE_TOOLS else 'cancelled'})
                return
            await asyncio.sleep(.05)
    try:
        delivered = await emit({**(identity or {}), "type": "tool_call", "call_id": call_id, "tool": tool,
                    "arguments": arguments})
        if delivered is False:
            return {'ok':False,'error':'Device host disconnected before dispatch','execution_state':'not_started','error_code':'host_disconnected'}
        sent = True
        watcher = asyncio.create_task(watch_cancel())
        return await asyncio.wait_for(fut, timeout=DEVICE_TOOL_TIMEOUT)
    except asyncio.TimeoutError:
        await revoke()
        return {"ok": False, "error": "device tool timeout (30s)",
                "execution_state":"unknown" if tool in WRITE_TOOLS else "not_started", 'error_code':'device_timeout'}
    except asyncio.CancelledError:
        if sent: await revoke()
        raise
    finally:
        if watcher is not None:
            watcher.cancel()
            try: await watcher
            except asyncio.CancelledError: pass
        if not fut.done(): fut.cancel()
        _tool_waiters.pop(call_id, None)


# ── WS 主通道 ───────────────────────────────────────────────────
@app.websocket("/agent/ws")
async def agent_ws(ws: WebSocket):
    session_id=ws.query_params.get("session","default")
    if not authorized(ws.headers.get("authorization")) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',session_id):
        await ws.close(code=1008);return
    if session_id in _connected_sessions or session_id in _sessions_busy:
        await ws.close(code=1008);return
    _connected_sessions.add(session_id)
    opened=True;active=None;cancel=None
    async def emit(event):
        if opened:
            try:
                await ws.send_text(json.dumps(event,ensure_ascii=False))
                return True
            except (RuntimeError,WebSocketDisconnect):
                if cancel:cancel.set()
                return None  # A failed send may have reached the host; await cancellation evidence.
        return False
    try:
        await ws.accept()
        agent=_sessions.get(session_id)
        if agent is None:
            agent=_build_session_agent(session_id);_sessions[session_id]=agent
        await emit({"type":"ready","session":session_id,"tools":sorted(_android_tools)})
        async def drive(task,history,token):
            try:await _run_agent(agent,task,history,emit,cancellation=token)
            finally:_sessions_busy.discard(session_id)
        while True:
            try:
                msg=json.loads(await ws.receive_text())
                if not isinstance(msg,dict):raise ValueError()
            except (ValueError,TypeError):
                await emit({"type":"error","error":"invalid message"});continue
            kind=msg.get("type")
            if kind=='run':
                task=msg.get('task','');history=msg.get('history') or []
                if not isinstance(task,str) or not task.strip() or len(task)>32000 or not isinstance(history,list):
                    await emit({"type":"error","error":"invalid task/history"});continue
                if session_id in _sessions_busy:
                    await emit({"type":"error","error":"session busy"});continue
                cancel=threading.Event();_sessions_busy.add(session_id)
                active=asyncio.create_task(drive(task,history,cancel))
            elif kind=='cancel':
                if cancel:cancel.set()
                await emit({"type":"cancel_tools"})
                await emit({"type":"cancel_requested"})
            elif kind=='ping':await emit({"type":"pong","t":time.time()})
            elif kind=='reset':
                if session_id in _sessions_busy:
                    await emit({"type":"error","error":"cancel and wait before resetting"});continue
                agent=_build_session_agent(session_id);_sessions[session_id]=agent
                await emit({"type":"reset_ok"})
            elif kind=='refresh_tools':await emit({"type":"capabilities","tools":sorted(_android_tools)})
            else:await emit({"type":"error","error":"unknown type"})
    except WebSocketDisconnect:pass
    except Exception as exc:
        await emit({'type':'fatal','error':str(exc)})
    finally:
        opened=False
        if cancel:cancel.set()
        _connected_sessions.discard(session_id)



async def _run_agent(agent: Agent, task: str, history: list, emit, ws_loop=None, *, cancellation=None) -> None:
    """Run the real registry in a worker; device inputs need host approval."""
    from bridge_tools import register_android_tools  # 同目录：工具注册适配层

    loop = ws_loop or asyncio.get_running_loop()
    done = loop.create_future()
    request=RunRequest(entry='android')
    agent.cancellation_event=cancellation or threading.Event()
    await emit({'type':'run_started',**request.as_dict()})
    def settle(value=None,error=None):
        if not done.done():
            if error:done.set_exception(error)
            else:done.set_result(value)

    def observer(event: dict[str, Any]) -> None:
        data = dict(event)
        etype = data.pop("type", "step")
        asyncio.run_coroutine_threadsafe(
            emit({"type": f"agent.{etype}", **data}), loop)

    def _worker():
        try:
            if hasattr(agent, "registry"):
                async def dispatch(name, args, emitter):
                    return await _dispatch_android_tool(name,args,emitter,
                        cancellation=agent.cancellation_event, identity=request.as_dict())
                register_android_tools(agent.registry,_android_tools,dispatch,emit,loop,
                    approval_available=_device_approval, available=lambda: _android_tools)
                agent._observer = observer
            with agent.session:
                report = agent.run(task, history=history,run_request=request)
            summary = {
                **request.as_dict(),
                "execution_state":getattr(report,"stopped","final"),
                "answer": getattr(report, "text", getattr(report, "answer", "")) or "",
                "steps": len(report.steps) if isinstance(report.steps,list) else report.steps,
                "cost": float(getattr(report, "usage", {}).get("cost", 0.0) or 0.0),
            }
            loop.call_soon_threadsafe(settle, summary)
        except Exception as exc:            # noqa: BLE001
            loop.call_soon_threadsafe(settle, None, exc)

    loop.run_in_executor(None, _worker)
    try:
        summary = await done
        await emit({"type": "run_done", **summary})
    except Exception as exc:                # noqa: BLE001
        await emit({"type": "run_failed", **request.as_dict(), "execution_state":"failed", "error": str(exc)})


# ── SSE（调试）/ 健康 / 远程预留 ─────────────────────────────────
class RunBody(BaseModel):
    task: str
    history: list[dict[str, Any]] | None = None


@app.post("/agent/run")
async def agent_run(body: RunBody):
    if not body.task.strip() or len(body.task)>32000:
        raise HTTPException(422,"invalid task")
    if 'sse' in _sessions_busy or 'sse' in _connected_sessions:
        raise HTTPException(409,"session busy")
    _sessions_busy.add('sse')
    cancellation=threading.Event()
    async def gen():
        q: asyncio.Queue = asyncio.Queue()

        async def emit(ev):
            await q.put(ev)

        async def drive():
            try:
                agent = _sessions.get("sse")
                if agent is None:
                    agent = _build_session_agent("sse")
                    _sessions["sse"] = agent
                await _run_agent(agent, body.task, body.history or [], emit,cancellation=cancellation)
            finally:
                _sessions_busy.discard('sse')
                await q.put(None)

        asyncio.create_task(drive())
        try:
            while True:
                ev = await q.get()
                if ev is None:
                    yield "event: done\ndata: {}\n\n"
                    return
                yield f"event: {ev.get('type', 'step')}\ndata: {json.dumps(ev, ensure_ascii=False)}\n\n"
        finally:cancellation.set()

    return StreamingResponse(gen(), media_type="text/event-stream")


@app.get("/health")
async def health():
    return {"status": "ok", "pid": os.getpid(),
            "uptime": round(time.time() - BOOT_T, 1),
            "shizuku_tools": sorted(_android_tools & _SHIZUKU_ONLY)}


@app.post("/remote/{path:path}")
async def remote_stub(path: str):
    raise HTTPException(501, "remote control reserved; not in v0.2")
