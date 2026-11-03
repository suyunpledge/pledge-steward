# -*- coding: utf-8 -*-
"""Small stdio worker for the native Android bridge.

The Android app owns HTTP, authentication, WebSocket sessions and device
capabilities.  This process only hosts the Forge Python agent and speaks a
newline-delimited JSON protocol on stdin/stdout.  Keeping this boundary small
means the mobile runtime can be Kotlin-only without changing the Forge core.
"""
from __future__ import annotations

import asyncio
import json
import os
import queue
import sys
import threading
import time
from pathlib import Path
from typing import Any

FORGE_ROOT = Path(os.environ.get("FORGE_ROOT", "/opt/forge"))
sys.path.insert(0, str(FORGE_ROOT))

from forge.config import load_config  # noqa: E402
from forge.execution import RunRequest  # noqa: E402
from forge.loop import Agent, LoopLimits, build_agent  # noqa: E402
from forge.model import ModelRouter  # noqa: E402

HOME = Path(os.environ.get("FORGE_HOME", "/opt/forge/.forge-home"))
WORKSPACE = Path(os.environ.get("FORGE_WORKSPACE", "/opt/forge/workspace"))
HOME.mkdir(parents=True, exist_ok=True)
WORKSPACE.mkdir(parents=True, exist_ok=True)
(HOME / "sessions").mkdir(parents=True, exist_ok=True)

_print_lock = threading.Lock()
_agent_lock = threading.Lock()
_agent: Agent | None = None
_capabilities: set[str] = set()
_approval = False
_cancel = threading.Event()
_busy = False
_tool_waiters: dict[str, asyncio.Future] = {}
_call_counter = 0


def emit(event: dict[str, Any]) -> None:
    with _print_lock:
        sys.stdout.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
        sys.stdout.flush()


def build_session(session: str) -> Agent:
    bundles = sorted((FORGE_ROOT / "bundles").glob("*.json"))
    cfg = load_config(HOME, bundles=bundles)
    router = ModelRouter.from_config(cfg)
    return build_agent(
        home=HOME,
        workspace=WORKSPACE,
        config=cfg,
        router=router,
        limits=LoopLimits(max_steps=12, max_depth=2, spawn_budget=8),
        session_path=HOME / "sessions" / f"phone-{session}.jsonl",
        mount_contrib=True,
        expose=sorted(_capabilities),
    )


async def dispatch(name: str, args: dict[str, Any], emit_host, loop: asyncio.AbstractEventLoop,
                   identity: dict[str, Any]) -> dict[str, Any]:
    global _call_counter
    if _cancel.is_set():
        return {"ok": False, "error": "Cancelled before device dispatch",
                "execution_state": "not_started", "error_code": "cancelled"}
    _call_counter += 1
    call_id = f"tc-{time.monotonic_ns()}-{_call_counter}"
    fut = loop.create_future()
    _tool_waiters[call_id] = fut
    try:
        emit({"type": "tool_call", "call_id": call_id, "tool": name,
              "arguments": args, **identity})
        return await asyncio.wait_for(fut, timeout=30.0)
    except asyncio.TimeoutError:
        return {"ok": False, "error": "device tool timeout (30s)",
                "execution_state": "unknown" if name.startswith("android.ui.") or name == "android.app.launch" else "not_started",
                "error_code": "device_timeout"}
    finally:
        _tool_waiters.pop(call_id, None)


def run_agent(session: str, task: str, history: list[Any], loop: asyncio.AbstractEventLoop) -> None:
    global _agent
    from bridge_tools import register_android_tools

    request = RunRequest(entry="android")
    agent = _agent
    if agent is None:
        with _agent_lock:
            if _agent is None:
                _agent = build_session(session)
            agent = _agent
    assert agent is not None
    agent.cancellation_event = _cancel

    def observer(event: dict[str, Any]) -> None:
        data = dict(event)
        event_type = data.pop("type", "step")
        emit({"type": f"agent.{event_type}", **data})

    async def host_dispatch(name: str, args: dict[str, Any], host_emit) -> dict[str, Any]:
        return await dispatch(name, args, host_emit, loop, request.as_dict())

    try:
        register_android_tools(
            agent.registry,
            _capabilities,
            host_dispatch,
            emit,
            loop,
            approval_available=_approval,
            available=lambda: _capabilities,
        )
        agent._observer = observer
        emit({"type": "run_started", **request.as_dict()})
        with agent.session:
            report = agent.run(task, history=history, run_request=request)
        emit({"type": "run_done", **request.as_dict(),
              "execution_state": getattr(report, "stopped", "final"),
              "answer": getattr(report, "text", getattr(report, "answer", "")) or "",
              "steps": len(report.steps) if isinstance(report.steps, list) else report.steps,
              "cost": float(getattr(report, "usage", {}).get("cost", 0.0) or 0.0)})
    except Exception as exc:  # noqa: BLE001
        emit({"type": "run_failed", **request.as_dict(),
              "execution_state": "failed", "error": str(exc)})


async def main() -> None:
    global _busy, _cancel, _agent, _capabilities, _approval
    loop = asyncio.get_running_loop()
    messages: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

    def read_stdin() -> None:
        for line in sys.stdin:
            try:
                msg = json.loads(line)
                if isinstance(msg, dict):
                    loop.call_soon_threadsafe(messages.put_nowait, msg)
            except (json.JSONDecodeError, TypeError):
                emit({"type": "error", "error": "invalid worker message"})
        loop.call_soon_threadsafe(messages.put_nowait, {"type": "shutdown"})

    threading.Thread(target=read_stdin, name="forge-worker-stdin", daemon=True).start()
    emit({"type": "worker_ready"})
    while True:
        msg = await messages.get()
        kind = msg.get("type")
        if kind == "shutdown":
            return
        if kind == "capabilities":
            _capabilities = set(msg.get("tools") or [])
            _approval = bool(msg.get("approval"))
            continue
        if kind == "tool_result":
            call_id = str(msg.get("call_id", ""))
            fut = _tool_waiters.get(call_id)
            if fut and not fut.done():
                fut.set_result({"ok": msg.get("ok") is True,
                                "result": msg.get("result") or {},
                                "error": msg.get("error"),
                                "execution_state": msg.get("execution_state"),
                                "error_code": msg.get("error_code")})
            continue
        if kind in ("cancel", "cancel_tools"):
            _cancel.set()
            for fut in list(_tool_waiters.values()):
                if not fut.done():
                    fut.set_result({"ok": False, "error": "cancelled",
                                    "execution_state": "unknown",
                                    "error_code": "cancelled"})
            continue
        if kind == "reset":
            if _busy:
                emit({"type": "error", "error": "session busy"})
            else:
                _agent = None
                emit({"type": "reset_ok"})
            continue
        if kind == "run":
            if _busy:
                emit({"type": "error", "error": "session busy"})
                continue
            task = msg.get("task", "")
            history = msg.get("history") or []
            if not isinstance(task, str) or not task.strip() or len(task) > 32000 or not isinstance(history, list):
                emit({"type": "error", "error": "invalid task/history"})
                continue
            _busy = True
            _cancel = threading.Event()

            async def execute() -> None:
                global _busy
                await loop.run_in_executor(None, run_agent, str(msg.get("session", "phone")), task, history, loop)
                _busy = False

            asyncio.create_task(execute())
            continue
        if kind == "ping":
            emit({"type": "pong", "t": time.time()})
            continue
        emit({"type": "error", "error": "unknown worker message"})


if __name__ == "__main__":
    asyncio.run(main())
