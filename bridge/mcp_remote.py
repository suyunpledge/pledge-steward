# -*- coding: utf-8 -*-
"""mcp_remote — 远程 MCP 传输（Streamable HTTP），补足 stdio 之外的一半生态。

官方托管的 GitHub / Microsoft 365 / Notion / Google 逐产品 MCP 都是
HTTP 流式端点（+/mcp?器=stdio 的老 SSE 也有）。本模块给 Connector
体系加第二种传输：同一 JSON-RPC 会话语义，走 urllib 请求-响应 +
可选 SSE 流。

设计纪律：
- 凭据（PAT/OAuth token）只存连接器 env，不落日志
- 401/403 → failed + 明确 error（不静默重试）
- 每请求独立 HTTP 调用（MCP streamable-http 的无状态模式），
  Accept: application/json, text/event-stream 双协议兼容
"""
from __future__ import annotations

import json
import os
import time
import urllib.request
from typing import Any


class RemoteConnector:
    """远程（HTTP）MCP 连接器：与 stdio Connector 同接口子集。"""

    def __init__(self, name: str, url: str, env: dict[str, str] | None = None,
                 enabled: bool = True):
        self.name = name
        self.url = url.rstrip("/")
        self.env = env or {}
        self.enabled = enabled
        self.status = "stopped"
        self.tools: list[dict[str, Any]] = []
        self.error: str | None = None
        self._session_header: dict[str, str] = {}
        self._req_id = 0
        self._started_at = 0.0

    def _headers(self) -> dict[str, str]:
        h = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            **self._session_header,
        }
        tok = self.env.get("AUTH_TOKEN") or self.env.get("GITHUB_TOKEN") \
            or self.env.get("NOTION_TOKEN") or ""
        if tok:
            auth = self.env.get("AUTH_SCHEME", "Bearer")
            h["Authorization"] = f"{auth} {tok}"
        return h

    def _post(self, payload: dict[str, Any], timeout: float = 30.0) -> dict[str, Any] | None:
        """POST 一次。notification（无 id）返回 None，不做 body 解析。"""
        req = urllib.request.Request(
            self.url, data=json.dumps(payload).encode(),
            headers=self._headers(), method="POST")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            # 会话保持（streamable-http 的 Mcp-Session-Id）
            sid = resp.headers.get("Mcp-Session-Id")
            if sid:
                self._session_header["Mcp-Session-Id"] = sid
            if payload.get("id") is None:
                return None                    # notification：不期待 body
            ctype = resp.headers.get("Content-Type", "")
            body = resp.read().decode("utf-8", errors="replace")
        if "application/json" in ctype:
            return json.loads(body)
        for line in body.splitlines():
            if line.startswith("data:"):
                data = line[5:].strip()
                if data:
                    return json.loads(data)
        raise RuntimeError(f"unparseable response ({ctype}): {body[:120]}")

    def _rpc(self, method: str, params: dict[str, Any] | None = None,
             timeout: float = 30.0) -> dict[str, Any]:
        self._req_id += 1
        resp = self._post({"jsonrpc": "2.0", "id": self._req_id,
                           "method": method, "params": params or {}}, timeout)
        if "error" in resp:
            raise RuntimeError(f"mcp error: {resp['error']}")
        return resp.get("result", {})

    # 与 stdio Connector 同形的方法面（mcp_connectors 复用）
    def start(self) -> bool:
        self.status = "starting"
        self._started_at = time.time()
        return True

    def stop(self):
        self.status = "stopped"
        self.tools = []
        self._session_header = {}

    def handshake(self) -> bool:
        try:
            self._rpc("initialize", {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "forge-bridge", "version": "0.4.0"},
            }, timeout=20)
            self._post({"jsonrpc": "2.0", "method": "notifications/initialized"},
                       timeout=10)
            tools_result = self._rpc("tools/list", {}, timeout=20)
            from mcp_connectors import _NAME_TOOL_RE
            from mcp_bridge import _is_valid_name, _guess_read_only
            keep = []
            for t in tools_result.get("tools", []):
                tn = t.get("name", "")
                if _is_valid_name(tn) and _NAME_TOOL_RE.match(tn):
                    keep.append({
                        "name": tn,
                        "description": str(t.get("description", ""))[:500],
                        "readOnly": _guess_read_only(tn),
                        "inputSchema": t.get("inputSchema")
                        or {"type": "object", "properties": {}},
                    })
            self.tools = keep
            self.status = "ready"
            self.error = None
            return True
        except Exception as exc:            # noqa: BLE001
            self.status = "failed"
            self.error = str(exc)[:300]
            return False

    def call_tool(self, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        result = self._rpc("tools/call", {"name": tool, "arguments": arguments},
                           timeout=45.0)
        content = result.get("content", [])
        text_parts = [c.get("text", "") for c in content if isinstance(c, dict)]
        return {"ok": not result.get("isError", False),
                "text": "\n".join(text_parts)[:32_000]}

    def snapshot(self) -> dict[str, Any]:
        return {
            "name": self.name, "status": self.status, "enabled": self.enabled,
            "command": [self.url],     # UI 复用 command 列显示
            "tools": [{"name": t["name"], "readOnly": t["readOnly"]}
                      for t in self.tools],
            "error": self.error,
            "uptime": round(time.time() - self._started_at, 1)
            if self.status in ("ready", "starting") else 0,
        }
