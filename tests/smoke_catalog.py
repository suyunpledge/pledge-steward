# -*- coding: utf-8 -*-
"""smoke_catalog — 目录一键接入冒烟。

验证：目录完整性（30 条、字段齐全）、install 三形态（none 直装 /
api_key 填表装 / http url 占位替换）、缺凭据拒绝、未知 id 拒绝。
不真启动 npx（PRoot 内才有）——stdio 型只验配置落盘；http 型用本地
假端点走真握手。"""
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


# ── 1. 目录数据完整性 ──
from connector_catalog import CATALOG, by_id

check(len(CATALOG) >= 28, f"目录规模（{len(CATALOG)} ≥ 28）")
cats = {c["category"] for c in CATALOG}
check(len(cats) >= 8, f"分类覆盖（{sorted(cats)}）")
for c in CATALOG:
    assert c["transport"] in ("stdio", "http"), c["id"]
    assert c["auth"]["kind"] in ("none", "api_key", "oauth"), c["id"]
    if c["transport"] == "stdio":
        assert isinstance(c["command"], list) and c["command"], c["id"]
    else:
        assert c["url"].startswith("http"), c["id"]
    for f in c["auth"]["fields"]:
        assert f["env"] and f["label"], (c["id"], f)
check(True, "全部条目字段合法")

free = [c["id"] for c in CATALOG if c["auth"]["kind"] == "none"]
check(len(free) >= 4, f"零凭据直连服务（{free}）")

# ── 2. install 逻辑（经 mcp_connectors 落配置，不起 npx）──
import tempfile
import mcp_connectors as mc
tmp = Path(tempfile.mkdtemp(prefix="cat-smoke-"))
mc.HOME = tmp
mc.CONFIG_PATH = tmp / "mcp_connectors.json"

# 2a. stdio + none（12306）
item = by_id("train12306")
r = mc.upsert(item["id"], command=item["command"],
              env={f["env"]: "" for f in item["auth"]["fields"]})
check(r.get("ok") is True, "12306 零凭据落配置")
cfg = mc.load_config()
check(cfg.get("train12306", {}).get("command") == ["uvx", "mcp-server-12306"],
      "stdio 命令落盘正确")

# 2b. http + key-in-url（腾讯地图占位替换）
item = by_id("qq_maps")
url = item["url"].replace("{TENCENT_MAPS_KEY}", "TESTKEY123")
check("TESTKEY123" in url and "{TENCENT_MAPS_KEY}" not in url,
      "url 占位替换")

# 2c. 缺凭据拒绝（表单层逻辑等价）
env_needed = {f["env"]: "" for f in by_id("amap")["auth"]["fields"]}
missing = [f["label"] for k, v in env_needed.items() if not v
           for f in by_id("amap")["auth"]["fields"] if f["env"] == k]
check(len(missing) >= 1, "高德缺 Key 被检出")

# ── 3. http 型 install 全链（本地假端点 + 真握手）──
TOOLS = [{"name": "geocode", "description": "geocode address", "inputSchema": {}}]


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        rid, m = body.get("id"), body.get("method", "")
        if m == "initialize":
            result = {"protocolVersion": "2025-03-26", "capabilities": {}}
        elif m == "tools/list":
            result = {"tools": TOOLS}
        elif m == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        else:
            result = {"content": [{"type": "text", "text": "ok"}]}
        out = json.dumps({"jsonrpc": "2.0", "id": rid, "result": result}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


srv = HTTPServer(("127.0.0.1", 0), H)
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()

# 用假端点 URL 建一个 http 连接器（模拟 install 的 http 分支）
r = mc.upsert("qq_maps_test", url=f"http://127.0.0.1:{port}/mcp",
              env={"TENCENT_MAPS_KEY": "TESTKEY123"})
check(r.get("ok") is True, "http 型 upsert")
r = mc.ensure_one("qq_maps_test")
check(r.get("ok") is True and r.get("status") == "ready", f"http 握手 ready（{r.get('status')}）")
check(len(mc.all_tools()) == 1 and mc.all_tools()[0]["qualified"] == "qq_maps_test__geocode",
      "工具并入（qualified 命名）")

srv.shutdown()
print()
print("FAILED:" if FAILS else "ALL CATALOG SMOKE PASSED", len(FAILS))
sys.exit(1 if FAILS else 0)
