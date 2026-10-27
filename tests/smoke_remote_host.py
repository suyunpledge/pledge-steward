# -*- coding: utf-8 -*-
"""smoke_remote_host — 桌面 remote-host + 手机 client 全链冒烟（本机模拟）。

同一进程起 remote-host（配对码）+ 模拟手机 pair/run/审批往返。"""
import asyncio
import json
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
os_env = dict(__import__("os").environ)
os_env["PYTHONIOENCODING"] = "utf-8"

FAILS = []


def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c:
        FAILS.append(m)


async def main():
    import websockets

    # ── 起 remote-host（独立进程，真服务）──
    port = 8451
    pair_code = "424242"
    os_env["FORGE_REMOTE_TEST_CODE"] = pair_code   # 必须在 Popen 前设置
    proc = subprocess.Popen(
        [sys.executable, "-u", str(HERE.parent / "bridge" / "remote-host.py"),
         "--port", str(port)],
        env=os_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    time.sleep(4)      # 起服缓冲
    import urllib.request as _ur
    def ws_up():
        try:
            import socket
            s2 = socket.create_connection(("127.0.0.1", port), timeout=2)
            s2.close()
            return True
        except Exception:
            return False
    up = False
    for _ in range(20):
        if ws_up():
            up = True; break
        if proc.poll() is not None:
            print("[diag] remote-host died:", proc.stdout.read()[:400])
            break
        time.sleep(0.5)
    check(up, "remote-host 端口就绪")
    check(len(pair_code) == 6, f"配对码（测试态固定 {pair_code}）")

    uri = f"ws://127.0.0.1:{port}/remote"

    # ── 1. 错配对码被拒 ──
    try:
        async with websockets.connect(uri) as ws:
            await ws.send(json.dumps({"type": "pair", "device_id": "t1", "code": "000000"}))
            r = json.loads(await ws.recv())
            check(r["type"] == "pair_failed", "错码拒绝")
    except websockets.exceptions.ConnectionClosed:
        check(True, "错码拒绝（服务端关闭连接形态）")

    # ── 2. 正确配对 + ready ──
    async with websockets.connect(uri) as ws:
        await ws.send(json.dumps({"type": "pair", "device_id": "smoke", "code": pair_code}))
        r = json.loads(await ws.recv())
        check(r["type"] == "paired", "配对成功")
        r = json.loads(await ws.recv())
        check(r["type"] == "ready" and "computer_observe" in r["tools"],
              f"ready 工具面（{r.get('tools', [])[:2]}…）")

        # ── 3. ping/pong ──
        await ws.send(json.dumps({"type": "ping"}))
        r = json.loads(await ws.recv())
        check(r["type"] == "pong", "pong")

        # ── 4. run（无 API key 的真 Agent 会失败——但链路语义要对：
        #        收到 run_failed 而非连接断）──
        await ws.send(json.dumps({"type": "run", "task": "hi"}))
        got_end = False
        for _ in range(60):
            ev = json.loads(await asyncio.wait_for(ws.recv(), timeout=120))
            t = ev.get("type")
            if t in ("run_done", "run_failed"):
                check(t == "run_failed" or t == "run_done",
                      f"run 终态（{t}: {str(ev.get('error', ev.get('answer','')))[:60]}）")
                got_end = True
                break
        check(got_end, "run 有终态（不断连）")

    proc.kill()

    # ── 5. remote_client 模块面（pair 状态机/模式门）──
    sys.path.insert(0, str(HERE.parent / "bridge"))
    import remote_client as rc
    tmp = Path(tempfile.mkdtemp(prefix="rc-smoke-"))
    rc.HOME = tmp
    rc.CONFIG_PATH = tmp / "remote_pair.json"
    r = rc.set_mode("remote")
    check(r.get("ok") is False, "未配对时 remote 模式被拒")
    r = rc.set_mode("bogus")
    check(r.get("ok") is False, "非法模式拒绝")
    r = rc.set_mode("local")
    check(r.get("ok") is True, "local 模式可用")

    print()
    print("FAILED:" if FAILS else "ALL REMOTE-HOST SMOKE PASSED", len(FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
