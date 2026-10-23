import json, subprocess, sys, time, urllib.request, threading
import websocket  # pip install websocket-client

proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "forge_bridge:app",
    "--host", "127.0.0.1", "--port", "8300", "--app-dir",
    r"C:\Users\匡溯昀\.openclaw-autoclaw\workspace\agent-f5hc9\.openclaw\tmp\forge-android\bridge"],
    env={**__import__('os').environ, "FORGE_ROOT": r"C:\Users\匡溯昀\pledge-evolving",
         "FORGE_BRIDGE_FAKE_AGENT": "1"},
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

ok = False
for _ in range(40):
    time.sleep(0.5)
    try:
        urllib.request.urlopen("http://127.0.0.1:8300/health", timeout=2)
        ok = True; break
    except Exception: pass
print("health:", ok)
if not ok:
    print(proc.stdout.read()[-1500:] if proc.poll() is not None else "server not up")
    proc.kill(); sys.exit(1)

FAILS = []
def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c: FAILS.append(m)

try:
    r = urllib.request.urlopen("http://127.0.0.1:8300/health", timeout=5)
    check(json.loads(r.read())["status"] == "ok", "/health ok")
    req = urllib.request.Request("http://127.0.0.1:8300/capabilities",
        data=json.dumps({"tools": [], "shizuku": "online"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    body = json.loads(urllib.request.urlopen(req, timeout=5).read())
    check("android.screen.inspect" in body["registered"], "capabilities 基线上报")

    ws = websocket.create_connection("ws://127.0.0.1:8300/agent/ws?session=rt", timeout=10)
    ready = json.loads(ws.recv())
    check(ready["type"] == "ready", "WS ready")
    ws.send(json.dumps({"type": "ping"}))
    pong = json.loads(ws.recv())
    check(pong["type"] == "pong", "pong")
    ws.send(json.dumps({"type": "run", "task": "hi"}))
    done = False
    for _ in range(20):
        ev = json.loads(ws.recv())
        if ev["type"] == "run_done":
            check(ev["answer"] == "done-answer" and ev["steps"] == 3, f"run_done {ev}")
            done = True; break
    check(done, "收到 run_done")
    ws.close()
finally:
    proc.kill()
print()
print("FAILED:" if FAILS else "RT SMOKE PASSED", len(FAILS))
sys.exit(1 if FAILS else 0)
