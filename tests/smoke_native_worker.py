"""Minimal offline contract check for the Python side of the native bridge."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(os.environ.get("FORGE_ROOT", Path(__file__).resolve().parents[1] / "build" / "forge-src"))
if not (ROOT / "forge" / "loop.py").exists():
    raise SystemExit("[skip] forge source is not available")

with tempfile.TemporaryDirectory(prefix="native-worker-") as tmp:
    env = {**os.environ, "FORGE_ROOT": str(ROOT), "FORGE_HOME": str(Path(tmp) / "home"),
           "FORGE_WORKSPACE": str(Path(tmp) / "workspace")}
    proc = subprocess.Popen([sys.executable, str(Path(__file__).parents[1] / "bridge" / "forge_worker.py")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, env=env)
    try:
        ready = json.loads(proc.stdout.readline())
        assert ready.get("type") == "worker_ready", ready
        proc.stdin.write(json.dumps({"type": "capabilities", "tools": ["android.device.info"], "approval": False}) + "\n")
        proc.stdin.write(json.dumps({"type": "ping"}) + "\n")
        proc.stdin.flush()
        pong = json.loads(proc.stdout.readline())
        assert pong.get("type") == "pong", pong
        print("[smoke-native-worker] OK")
    finally:
        proc.stdin.write(json.dumps({"type": "shutdown"}) + "\n")
        proc.stdin.flush()
        proc.wait(timeout=10)
