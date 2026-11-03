#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PC 侧仿真 rootfs 内 /opt/forge 布局并校验（与 build-rootfs.sh 同一铺设逻辑）。

- build/forge-src（pin 内核树）排除 .git / forge-android 后拷入临时目录
- 本仓 forge_worker.py + bridge_tools.py 铺到顶层，forge_worker 作为唯一 Python 入口
- 断言：forge_worker 可导入；Android Kotlin 负责 HTTP/WebSocket 路由。
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
for name in ("forge_worker.py", "bridge_tools.py"):
    shutil.copyfile(REPO / "bridge" / name, stage / name)
home = tmp / "home"
ws = tmp / "workspace"
os.environ.update(FORGE_ROOT=str(stage), FORGE_HOME=str(home), FORGE_WORKSPACE=str(ws))
sys.path.insert(0, str(stage))

import forge_worker  # noqa: E402
print("[verify-rootfs-layout] OK — 内核 + Forge worker 模块通过")
print("  staged layout:", stage)
