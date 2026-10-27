# -*- coding: utf-8 -*-
"""remote_host — 桌面端「被遥控」服务：手机 forge 的远程会话入口。

架构（对接协议 PROTOCOL.md §6 预留的落地）：
- 桌面 forge 起 `python run.py remote-host <port>`：
  · loopback 或局域网监听（默认 127.0.0.1，--lan 显式开 0.0.0.0）
  · 配对码认证（6 位数字，控制台显示；手机端输入）——不用管复杂 OAuth
  · 会话白名单：一次配对一个手机；重置配对码随时踢出
- 手机端连 `ws://<电脑IP>:<port>/remote`：
  · 上行：任务（remote.run）→ 桌面 build_agent 执行（computer_* 工具
    可用，走桌面审批 UI）
  · 下行：observer 事件流 + 审批请求（approval.request，手机侧展示
    允许/拒绝 → approval.response 回传）
- 每会话独立 Agent（session_path=remote-<device_id>.jsonl）。

安全边界：
- computer_act 的审批仍在**桌面**（ComputerSession.approve 挂桌面 GUI
  确认气泡）——手机只遥控，不越过桌面的审批闸门
- 桌面完全无凭据透传：配对码只做会话准入，不保存手机信息
- --lan 模式提醒用户开防火墙；默认 loopback 需 adb reverse 才通
"""
from __future__ import annotations

import asyncio
import json
import secrets
import sys
import time
from pathlib import Path
from typing import Any

# forge 可导入：env FORGE_ROOT 优先，然后脚本向上逐级 + /opt/forge
import os as _os
import sys as _sys
from pathlib import Path as _P

_cands = []
_env_root = _os.environ.get("FORGE_ROOT")
if _env_root:
    _cands.append(_P(_env_root))
_d = _P(__file__).resolve()
for _ in range(4):                      # 向上 4 级（bridge/ → forge-android/ → repo）
    _cands.append(_d)
    _d = _d.parent
_cands.append(_P("/opt/forge"))
for _root in _cands:
    if (_root / "forge" / "loop.py").exists():
        if str(_root) not in _sys.path:
            _sys.path.insert(0, str(_root))
        break

from forge.loop import Agent, LoopLimits, build_agent
from forge.config import load_config
from forge.model import ModelRouter


class RemoteHost:
    """被遥控端状态：配对码 + 已配对设备 + 会话 Agent 表。"""

    def __init__(self, home: Path, workspace: Path, forge_root: Path):
        self.home = home
        self.workspace = workspace
        self.forge_root = forge_root
        self.pair_code: str = ""
        self.paired: dict[str, dict[str, Any]] = {}    # device_id -> {paired_at}
        self.sessions: dict[str, Agent] = {}
        self.pending_approvals: dict[str, asyncio.Future] = {}

    def new_pair_code(self) -> str:
        self.pair_code = f"{secrets.randbelow(1000000):06d}"
        return self.pair_code

    def is_paired(self, device_id: str) -> bool:
        return device_id in self.paired

    def pair(self, device_id: str, code: str) -> bool:
        if not self.pair_code or not secrets.compare_digest(self.pair_code, code):
            return False
        self.paired[device_id] = {"paired_at": time.time()}
        return True

    def agent_for(self, device_id: str) -> Agent:
        agent = self.sessions.get(device_id)
        if agent is None:
            bundles = sorted((self.forge_root / "bundles").glob("*.json"))
            cfg = load_config(self.home, bundles=bundles)
            router = ModelRouter.from_config(cfg)
            agent = build_agent(
                home=self.home, workspace=self.workspace, config=cfg,
                router=router,
                limits=LoopLimits(max_steps=12, max_depth=2, spawn_budget=8),
                session_path=self.home / "sessions" / f"remote-{device_id}.jsonl",
                mount_contrib=True,
            )
            self.sessions[device_id] = agent
        return agent


async def serve(host: str, port: int, home: Path, workspace: Path,
                forge_root: Path) -> None:
    """remote-host 主循环（run.py remote-host 入口调用）。"""
    import websockets

    rh = RemoteHost(home, workspace, forge_root)
    # 测试态：FORGE_REMOTE_TEST_CODE 固定配对码（冒烟用；生产随机）
    rh.pair_code = _os.environ.get("FORGE_REMOTE_TEST_CODE") or rh.new_pair_code()
    print(f"[remote-host] listening on ws://{host}:{port}/remote", flush=True)
    print(f"[remote-host] pair code: {rh.pair_code}", flush=True)

    async def handler(ws):
        device_id = None
        try:
            # ① 配对面：首条消息必须是 pair 或已配对设备的 ping
            first = json.loads(await ws.recv())
            if first.get("type") == "pair":
                dev = first.get("device_id", "?")
                # 已配对设备重连免码（首次配对才验证码；重置码=踢出全部）
                if rh.is_paired(dev):
                    ok = True
                else:
                    ok = rh.pair(dev, first.get("code", ""))
                await ws.send(json.dumps({"type": "paired" if ok else "pair_failed"}))
                if not ok:
                    return
                device_id = first["device_id"]
                print(f"[remote-host] paired: {device_id}")
            else:
                await ws.send(json.dumps({"type": "error", "error": "pair first"}))
                return

            loop = asyncio.get_running_loop()
            run_lock = asyncio.Lock()
            agent = None      # 惰性：首次 run 才构建（无 provider 配置的
                              # 机器上也能握手+ping，run 时才暴露配置问题）

            async def emit(ev: dict[str, Any]):
                await ws.send(json.dumps(ev, ensure_ascii=False))

            await emit({"type": "ready", "device": device_id,
                        "tools": ["computer_observe", "computer_act",
                                  "run", "approval.response"]})

            while True:
                raw = await ws.recv()
                msg = json.loads(raw)
                kind = msg.get("type")

                if kind == "run":
                    task = str(msg.get("task", "")).strip()
                    if not task:
                        await emit({"type": "error", "error": "empty task"})
                        continue
                    if run_lock.locked():
                        await emit({"type": "error",
                                    "error": "a task is already running"})
                        continue
                    async with run_lock:
                        if agent is None:
                            try:
                                agent = rh.agent_for(device_id)
                            except Exception as exc:        # noqa: BLE001
                                await emit({"type": "run_failed",
                                            "error": f"agent init: {exc}"[:200]})
                                continue
                        await run_task(agent, task, msg.get("history") or [],
                                       emit, rh, ws, loop)

                elif kind == "approval.response":
                    fut = rh.pending_approvals.get(msg.get("request_id", ""))
                    if fut and not fut.done():
                        fut.set_result(bool(msg.get("approved")))
                    # 结果由 run_task 内消化

                elif kind == "ping":
                    await emit({"type": "pong"})

                else:
                    await emit({"type": "error", "error": f"unknown {kind!r}"})
        except websockets.ConnectionClosed:
            pass
        except Exception as exc:              # noqa: BLE001 — handler 全捕获排障
            import traceback
            print(f"[remote-host] handler error: {exc}", flush=True)
            traceback.print_exc()
        finally:
            if device_id:
                print(f"[remote-host] disconnected: {device_id}", flush=True)

    async with websockets.serve(handler, host, port, max_size=8 << 20):
        await asyncio.Future()   # run forever


async def run_task(agent: Agent, task: str, history: list, emit,
                   rh: RemoteHost, ws, loop) -> None:
    """跑一轮远程任务；审批走手机（approval.request/response 往返）。"""
    done = loop.create_future()

    def observer(event: dict[str, Any]) -> None:
        data = dict(event)
        etype = data.pop("type", "step")
        asyncio.run_coroutine_threadsafe(
            emit({"type": f"agent.{etype}", **data}), loop)

    # 桌面 computer_act 审批桥：桌面 approve 回调被 ComputerSession 调用
    # （同步）——转发到手机 WS 等允许/拒绝。v0.5：若桌面 GUI 审批已配置
    # 则手机端也可见（双通道记录）；此处用手机作为远程审批人。
    def approve_via_phone(target_id: str, args: dict) -> bool:
        request_id = f"ap-{time.monotonic_ns()}"
        fut = loop.create_future()
        rh.pending_approvals[request_id] = fut
        try:
            asyncio.run_coroutine_threadsafe(emit({
                "type": "approval.request",
                "request_id": request_id,
                "target": target_id,
                "action": args,
            }), loop).result(timeout=5)
            # 等手机答复（run_task 的循环外线程里——用 future 等待）
            return fut.result(timeout=120)     # 2 分钟不答复视为拒绝
        except Exception:                       # noqa: BLE001 — 超时/断线=拒绝
            return False
        finally:
            rh.pending_approvals.pop(request_id, None)

    def _worker():
        try:
            # 挂上远程审批（若 Agent 有 computer 会话句柄）
            comp = getattr(agent, "_computer_session", None)
            if comp is not None:
                comp.approve = approve_via_phone
            with agent.session:
                report = agent.run(task, history=history)
            summary = {
                "answer": getattr(report, "answer", "") or "",
                "steps": getattr(report, "steps", 0),
                "cost": float(getattr(report, "cost", 0.0) or 0.0),
            }
            loop.call_soon_threadsafe(done.set_result, summary)
        except Exception as exc:                # noqa: BLE001
            loop.call_soon_threadsafe(done.set_exception, exc)

    loop.run_in_executor(None, _worker)
    try:
        summary = await done
        await emit({"type": "run_done", **summary})
    except Exception as exc:                    # noqa: BLE001
        await emit({"type": "run_failed", "error": str(exc)})


# ── CLI 接线（run.py remote-host）──────────────────────────────
def cli_main(argv: list[str] | None = None) -> int:
    import argparse
    from pathlib import Path
    ap = argparse.ArgumentParser(prog="remote-host",
                                 description="桌面被遥控服务（手机 forge 遥控入口）")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--lan", action="store_true",
                    help="监听 0.0.0.0（手机直连；默认 127.0.0.1 需 adb reverse）")
    ap.add_argument("--home", default=None)
    ap.add_argument("--workspace", default=".")
    args = ap.parse_args(argv)

    forge_root = Path(__file__).resolve().parent
    home = Path(args.home) if args.home else forge_root / ".forge-home"
    workspace = Path(args.workspace).resolve()
    host = "0.0.0.0" if args.lan else "127.0.0.1"
    if args.lan:
        print("[remote-host] LAN 模式：请确认防火墙放行该端口")
    try:
        asyncio.run(serve(host, args.port, home, workspace, forge_root))
    except KeyboardInterrupt:
        print("\n[remote-host] bye")
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
