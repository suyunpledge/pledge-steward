# -*- coding: utf-8 -*-
"""smoke_remote — 远程 MCP 传输 + 目录配置冒烟。

用本地起一个 HTTP MCP 假端点（模拟官方远程 server 的 JSON-RPC-over-HTTP
行为，含 Mcp-Session-Id 会话头），验证 RemoteConnector 的握手/工具发现/
调用/会话保持；以及 mcp_connectors 对 remote 型配置的统一管理。
"""
import json
import os
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE = HERE.parent / "bridge"
sys.path.insert(0, str(BRIDGE))
FORGE_ROOT = os.environ.setdefault("FORGE_ROOT", r"C:\Users\匡溯昀\pledge-evolving")
sys.path.insert(0, FORGE_ROOT)
sys.path.insert(0, str(Path(FORGE_ROOT) / "forge" / "contrib"))

FAILS = []


def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c:
        FAILS.append(m)


# ── 假远程 MCP 端点 ──────────────────────────────────────────
class FakeMCP(BaseHTTPRequestHandler):
    sessions = set()
    tools = [{"name": "search_code", "description": "search code",
              "inputSchema": {"type": "object", "properties": {}}},
             {"name": "create_issue", "description": "create issue",
              "inputSchema": {"type": "object", "properties": {}}}]

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        rid = body.get("id")
        method = body.get("method", "")
        if method == "initialize":
            result = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}}
        elif method == "tools/list":
            result = {"tools": FakeMCP.tools}
        elif method == "tools/call":
            result = {"content": [{"type": "text",
                                   "text": "did:" + body["params"]["name"]}],
                      "isError": False}
        elif method == "notifications/initialized":
            self.send_response(202)
            sid = "sess-123"
            self.send_header("Mcp-Session-Id", sid)
            self.send_header("Content-Length", "0")
            self.end_headers()
            FakeMCP.sessions.add(sid)
            return
        else:
            result = {}
        if rid is not None:
            # 会话检查：initialize 之后必须带 session 头（模拟服务端纪律）
            if method == "tools/list" and "Mcp-Session-Id" not in self.headers:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                out = json.dumps({"error": "no session"}).encode()
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)
                return
            resp = {"jsonrpc": "2.0", "id": rid, "result": result}
            out = json.dumps(resp).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out)))
            self.end_headers()
            self.wfile.write(out)

    def log_message(self, *a):
        pass


srv = HTTPServer(("127.0.0.1", 0), FakeMCP)
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
print(f"fake remote MCP on :{port}")

from mcp_remote import RemoteConnector

c = RemoteConnector("gh-fake", f"http://127.0.0.1:{port}/mcp")
check(c.start() and c.handshake(), "HTTP 握手 ready")
check(c.status == "ready" and len(c.tools) == 2, f"工具发现（{len(c.tools)}）")
ro = {t["name"]: t["readOnly"] for t in c.tools}
check(ro["search_code"] is True and ro["create_issue"] is False, "只读启发式")
check("sess-123" in FakeMCP.sessions and "Mcp-Session-Id" in c._session_header,
      "会话头保持（streamable-http）")
r = c.call_tool("search_code", {})
check(r["ok"] and r["text"] == "did:search_code", "HTTP 工具调用往返")
snap = c.snapshot()
check(snap["command"] == [f"http://127.0.0.1:{port}/mcp"], "快照 command=url")
c.stop()
check(c.status == "stopped", "停止")

# ── 目录集成：remote 型连接器写进 mcp_connectors 配置形态 ──
import mcp_connectors as mc
tmp = Path(os.environ.get("TEMP", ".")) / "mcp-remote-smoke"
tmp.mkdir(parents=True, exist_ok=True)
mc.HOME = tmp
mc.CONFIG_PATH = tmp / "mcp_connectors.json"
cfg = {"gh-fake": {"transport": "http", "url": f"http://127.0.0.1:{port}/mcp",
                   "env": {}, "enabled": True}}
mc.save_config(cfg)
check(mc.load_config().get("gh-fake", {}).get("transport") == "http",
      "remote 型配置持久化")

srv.shutdown()
print()
print("FAILED:" if FAILS else "ALL REMOTE SMOKE PASSED", len(FAILS))
sys.exit(1 if FAILS else 0)
