import asyncio, json, os, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE = HERE.parent / "bridge"
sys.path.insert(0, str(BRIDGE))
os.environ.setdefault("FORGE_ROOT", str(Path(__file__).resolve().parent.parent / "build" / "forge-src"))
if not (Path(os.environ["FORGE_ROOT"]) / "forge" / "loop.py").exists():
    raise SystemExit("[skip] 未找到 forge 内核 —— 先运行 python scripts/fetch-forge.py")
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))


# 1. 起 remote-host（固定码）
env = dict(os.environ, FORGE_REMOTE_TEST_CODE="424242", PYTHONIOENCODING="utf-8")
host_proc = subprocess.Popen([sys.executable, "-u", str(BRIDGE / "remote-host.py"), "--port", "8462"],
                              env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(4)

# 2. remote_client 配对（直连函数）
import remote_client as rc
import tempfile
from pathlib import Path
tmp = Path(tempfile.mkdtemp())
rc.HOME = tmp; rc.CONFIG_PATH = tmp / "remote_pair.json"

async def main():
    r = await rc.pair("127.0.0.1", 8462, "424242")
    print("pair:", r.get("ok"))
    assert r.get("ok")

    # 3. DesktopLink run（桌面的真 Agent 会 run——无 provider 时空报告或失败，
    #    但事件流要透传：ready/agent.*/run_done|failed）
    events = []
    async def emit(ev):
        events.append(ev)
        print("  ev:", ev.get("type"))
    await rc.DesktopLink(emit).run("hi", [])
    types = [e.get("type") for e in events]
    assert "ready" in types or "run_done" in types or "run_failed" in types, types
    print("E2E DESKTOP LINK OK; events:", types[:6])

asyncio.run(main())
host_proc.kill()
