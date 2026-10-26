# -*- coding: utf-8 -*-
"""mcp_connectors — 桥侧 MCP 连接器运行时（stdio JSON-RPC，子进程隔离）。

个人助理模式的核心扩展点：每个连接器 = 一个 MCP server 子进程
（PRoot 内 npx/uvx/python -m ... 或任意 stdio 命令），桥持有其
JSON-RPC 2.0 会话，把 server 的工具以 <server>__<tool> 名并入
Agent 工具面（与 forge 内建工具同一条 Policy 链裁决）。

设计纪律（对齐 forge contrib 六禁令精神）：
- server 子进程崩溃 → 该连接器标记 failed，工具面摘除，不影响桥
- 工具调用 30s 超时；超时杀进程重启（请求丢失即报错给 Agent）
- 不信任 server 自述的 readOnly——沿用 mcp_bridge 的名字启发式判只读
- 配置持久化在 FORGE_HOME/mcp_connectors.json（含启动命令、环境、
  启用状态；密钥类 env 走 forge secrets，不落这个文件）
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any

FORGE_ROOT = Path(os.environ.get("FORGE_ROOT", "/opt/forge"))
HOME = Path(os.environ.get("FORGE_HOME", "/opt/forge/.forge-home"))

# forge contrib（纯函数复用）：mcp_bridge 位于 <FORGE_ROOT>/forge/contrib/，
# 兼容两种布局（PC 工作区 / 镜像内 /opt/forge）——本地注入 path 后导入。
import sys as _sys
for _cand in (FORGE_ROOT / "forge" / "contrib",
              FORGE_ROOT / "contrib",
              Path(__file__).resolve().parent):
    if (_cand / "mcp_bridge.py").exists():
        if str(_cand) not in _sys.path:
            _sys.path.insert(0, str(_cand))
        break
from mcp_bridge import _guess_read_only, _is_valid_name
CONFIG_PATH = HOME / "mcp_connectors.json"

_NAME_TOOL_RE = re.compile(r"^[A-Za-z0-9_.\-]+$")

# 单实例状态（uvicorn 单进程；重启即清空——冷启动由 ensure_all 恢复）
_connectors: dict[str, "Connector"] = {}


class Connector:
    """一个 MCP server 的 stdio 会话。"""

    def __init__(self, name: str, command: list[str], env: dict[str, str] | None = None,
                 enabled: bool = True):
        self.name = name                    # 合法性由 _is_valid_name 保证
        self.command = command
        self.env = env or {}
        self.enabled = enabled
        self.proc: subprocess.Popen | None = None
        self.status = "stopped"             # stopped/starting/ready/failed
        self.tools: list[dict[str, Any]] = []
        self.error: str | None = None
        self._req_id = 0
        self._started_at = 0.0

    # ── 生命周期 ─────────────────────────────────────────────
    def start(self) -> bool:
        if self.proc and self.proc.poll() is None:
            return True
        try:
            full_env = {**os.environ, **self.env}
            self.proc = subprocess.Popen(
                self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, env=full_env, cwd=str(HOME.parent))
            self.status = "starting"
            self._started_at = time.time()
            self.error = None
            return True
        except Exception as exc:            # noqa: BLE001
            self.status = "failed"
            self.error = f"spawn failed: {exc}"
            return False

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
        self.proc = None
        self.status = "stopped"
        self.tools = []

    # ── JSON-RPC over stdio ──────────────────────────────────
    def _notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        """发 notification（无 id，不等待响应）。"""
        if not self.proc or self.proc.poll() is not None:
            raise RuntimeError(f"connector {self.name} not running")
        note = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        self.proc.stdin.write((json.dumps(note) + "\n").encode())
        self.proc.stdin.flush()

    def _rpc(self, method: str, params: dict[str, Any] | None = None,
             timeout: float = 30.0) -> dict[str, Any]:
        if not self.proc or self.proc.poll() is not None:
            raise RuntimeError(f"connector {self.name} not running")
        self._req_id += 1
        req = {"jsonrpc": "2.0", "id": self._req_id, "method": method,
               "params": params or {}}
        self.proc.stdin.write((json.dumps(req) + "\n").encode())
        self.proc.stdin.flush()
        # 同步读一行（桥在 asyncio 线程池里调本方法）
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError(f"connector {self.name} closed stdout")
            try:
                resp = json.loads(line)
            except json.JSONDecodeError:
                continue    # server 的日志行混进 stdout 时跳过
            if resp.get("id") == self._req_id:
                if "error" in resp:
                    raise RuntimeError(f"mcp error: {resp['error']}")
                return resp.get("result", {})
        raise TimeoutError(f"connector {self.name} rpc timeout ({timeout}s)")

    def handshake(self) -> bool:
        """initialize → tools/list。成功 → ready + tools 快照。"""
        try:
            result = self._rpc("initialize", {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "forge-bridge", "version": "0.3.0"},
            }, timeout=20)
            self._notify("notifications/initialized")
            tools_result = self._rpc("tools/list", {}, timeout=20)
            raw = tools_result.get("tools", [])
            keep, dropped = [], []
            for t in raw:
                tn = t.get("name", "")
                if _is_valid_name(tn) and _NAME_TOOL_RE.match(tn):
                    keep.append({
                        "name": tn,
                        "description": str(t.get("description", ""))[:500],
                        "readOnly": _guess_read_only(tn),
                        "inputSchema": t.get("inputSchema") or {"type": "object", "properties": {}},
                    })
                else:
                    dropped.append(tn)
            self.tools = keep
            self.status = "ready"
            self.error = None
            return True
        except Exception as exc:            # noqa: BLE001
            self.status = "failed"
            self.error = str(exc)[:300]
            self.stop()
            return False

    def call_tool(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        result = self._rpc("tools/call", {"name": tool, "arguments": arguments},
                           timeout=30.0)
        content = result.get("content", [])
        text_parts = [c.get("text", "") for c in content if isinstance(c, dict)]
        return {"ok": not result.get("isError", False),
                "text": "\n".join(text_parts)[:32_000]}

    def snapshot(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "status": self.status,
            "enabled": self.enabled,
            "command": self.command,
            "tools": [{"name": t["name"], "readOnly": t["readOnly"]}
                      for t in self.tools],
            "error": self.error,
            "uptime": round(time.time() - self._started_at, 1)
            if self.status in ("ready", "starting") else 0,
        }


# ── 配置持久化 ─────────────────────────────────────────────
def load_config() -> dict[str, dict[str, Any]]:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:                    # noqa: BLE001 — 坏配置回空
            return {}
    return {}


def save_config(cfg: dict[str, dict[str, Any]]) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(cfg, ensure_ascii=False, indent=1),
                           encoding="utf-8")


def upsert(name: str, command: list[str] | None = None, env: dict[str, str] | None = None,
           enabled: bool = True, *, url: str | None = None) -> dict[str, Any]:
    """新增/更新连接器定义（不自动启动；Kotlin 侧随后调 ensure）。"""
    if not _is_valid_name(name):
        return {"ok": False, "error": f"invalid connector name: {name!r}"}
    cfg = load_config()
    if url:
        if not isinstance(url, str) or not url.startswith(("http://", "https://")):
            return {"ok": False, "error": "url must be http(s)"}
        cfg[name] = {"transport": "http", "url": url, "env": env or {},
                     "enabled": enabled}
    else:
        if not isinstance(command, list) or not command or \
                not all(isinstance(a, str) for a in command):
            return {"ok": False, "error": "command must be a list of strings"}
        cfg[name] = {"command": command, "env": env or {}, "enabled": enabled}
    save_config(cfg)
    if name in _connectors:
        _connectors[name].stop()
        del _connectors[name]
    return {"ok": True}


def remove(name: str) -> dict[str, Any]:
    cfg = load_config()
    if name not in cfg:
        return {"ok": False, "error": f"unknown connector: {name}"}
    del cfg[name]
    save_config(cfg)
    conn = _connectors.pop(name, None)
    if conn:
        conn.stop()
    return {"ok": True}


def _build_connector(name: str, spec: dict[str, Any]):
    """按配置的 transport 选实现：stdio（缺省）或 http（官方远程）。"""
    if spec.get("transport") == "http" and spec.get("url"):
        from mcp_remote import RemoteConnector
        return RemoteConnector(name, spec["url"], spec.get("env") or {})
    return Connector(name, spec.get("command", []), spec.get("env") or {})


def ensure_all(start_enabled: bool = True) -> dict[str, Any]:
    """按配置恢复全部启用的连接器（桥启动/配置变更后调用）。"""
    cfg = load_config()
    started, failed = [], []
    for name, spec in cfg.items():
        if not spec.get("enabled", True) or not start_enabled:
            continue
        conn = _connectors.get(name)
        if conn is None:
            conn = _build_connector(name, spec)
            _connectors[name] = conn
        if conn.status != "ready":
            if conn.start() and conn.handshake():
                started.append(name)
            else:
                failed.append({"name": name, "error": conn.error})
    return {"started": started, "failed": failed}


def ensure_one(name: str) -> dict[str, Any]:
    """启动/重启单个连接器并握手。"""
    cfg = load_config()
    if name not in cfg:
        return {"ok": False, "error": f"unknown connector: {name}"}
    spec = cfg[name]
    conn = _connectors.get(name)
    if conn is None:
        conn = Connector(name, spec.get("command", []), spec.get("env") or {})
        _connectors[name] = conn
    conn.stop()
    ok = conn.start() and conn.handshake()
    return {"ok": ok, **conn.snapshot()}


def call(server: str, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """Agent 工具调用入口：<server>__<tool> 拆开路由。"""
    conn = _connectors.get(server)
    if conn is None or conn.status != "ready":
        return {"ok": False, "error": f"connector {server} not ready"}
    try:
        return conn.call_tool(tool, arguments)
    except Exception as exc:                # noqa: BLE001
        conn.status = "failed"
        conn.error = str(exc)[:300]
        return {"ok": False, "error": str(exc)[:300]}


def all_tools() -> list[dict[str, Any]]:
    """全部 ready 连接器的工具展平（供 Agent 工具面注册）。"""
    out = []
    for conn in _connectors.values():
        if conn.status != "ready":
            continue
        for t in conn.tools:
            out.append({
                "server": conn.name,
                "tool": t["name"],
                "qualified": f"{conn.name}__{t['name']}",
                "description": t["description"],
                "readOnly": t["readOnly"],
                "inputSchema": t["inputSchema"],
            })
    return out


def status() -> list[dict[str, Any]]:
    cfg = load_config()
    snaps = []
    for name in cfg:
        conn = _connectors.get(name)
        snaps.append(conn.snapshot() if conn else {
            "name": name, "status": "stopped", "enabled": cfg[name].get("enabled", True),
            "command": cfg[name].get("command", []), "tools": [],
            "error": None, "uptime": 0})
    return snaps
