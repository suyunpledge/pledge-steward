#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PC 侧仿真 rootfs 内 /opt/forge 布局并校验（与 build-rootfs.sh 同一铺设逻辑）。

- build/forge-src（pin 内核树）排除 .git / forge-android 后拷入临时目录
- 本仓 bridge/*.py 铺到顶层（forge_bridge 按顶层平铺 import 兄弟模块）
- 断言：forge_bridge 可导入；assistant_mode/onboarding/mcp_connectors/
  remote_client 真实加载（rootfs 旧版只铺 2 个文件时它们静默失效）；
  /assistant/ask、/onboarding/status 等路由注册；TestClient /health == 200。
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = Path(os.environ.get("FORGE_ROOT", str(REPO / "build" / "forge-src")))
if not (SRC / "forge" / "loop.py").exists():
    raise SystemExit("[skip] 未找到 forge 内核 %s —— 先运行 python scripts/fetch-forge.py" % SRC)


def _ignore(directory, names):
    return [n for n in names if n in {".git", "forge-android"}]


tmp = Path(tempfile.mkdtemp(prefix="steward-layout-"))
stage = tmp / "opt" / "forge"
shutil.copytree(SRC, stage, ignore=_ignore)
for py in (REPO / "bridge").glob("*.py"):
    shutil.copyfile(py, stage / py.name)
home = tmp / "home"
ws = tmp / "workspace"
os.environ.update(FORGE_ROOT=str(stage), FORGE_HOME=str(home), FORGE_WORKSPACE=str(ws))
sys.path.insert(0, str(stage))

import forge_bridge  # noqa: E402

missing = [m for m in ("assistant_mode", "onboarding", "mcp_connectors", "remote_client")
           if m not in sys.modules]
assert not missing, "桥模块未加载: %r" % missing
paths = {getattr(r, "path", "") for r in forge_bridge.app.routes}
for need in ("/assistant/ask", "/assistant/catalog", "/onboarding/status", "/onboarding/vendors"):
    assert need in paths, "缺少路由 %s（现有 %d 条）" % (need, len(paths))

def _asgi_get(app, path):
    """零依赖的 ASGI GET（starlette TestClient 需要 httpx，这里避免额外依赖）。"""
    import asyncio, json as _json

    scope = {"type": "http", "asgi": {"version": "2.1"}, "http_version": "1.1",
             "method": "GET", "path": path, "raw_path": path.encode(),
             "query_string": b"", "root_path": "", "scheme": "http",
             "headers": [(b"host", b"verify")], "client": ("127.0.0.1", 0),
             "server": ("127.0.0.1", 8000)}
    out = {}

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(m):
        if m["type"] == "http.response.start":
            out["status"] = m["status"]
        elif m["type"] == "http.response.body":
            out["body"] = out.get("body", b"") + m.get("body", b"")

    asyncio.run(app(scope, receive, send))
    return out["status"], _json.loads(out.get("body", b"{}"))

status, payload = _asgi_get(forge_bridge.app, "/health")
assert status == 200 and payload.get("status") == "ok", (status, payload)

print("[verify-rootfs-layout] OK — 内核 + 全量桥模块 + 路由 + /health 全部通过")
print("  staged layout:", stage)
