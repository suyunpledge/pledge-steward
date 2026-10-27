from pathlib import Path
p = Path(r"bridge\remote-host.py")
s = p.read_text(encoding="utf-8")
old = """            first = json.loads(await ws.recv())
            if first.get("type") == "pair":
                ok = rh.pair(first.get("device_id", "?"), first.get("code", ""))
                await ws.send(json.dumps({"type": "paired" if ok else "pair_failed"}))"""
new = """            first = json.loads(await ws.recv())
            if first.get("type") == "pair":
                dev = first.get("device_id", "?")
                # 已配对设备重连免码（首次配对才验证码；重置码=踢出全部）
                if rh.is_paired(dev):
                    ok = True
                else:
                    ok = rh.pair(dev, first.get("code", ""))
                await ws.send(json.dumps({"type": "paired" if ok else "pair_failed"}))"""
assert old in s
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8", newline="")
import ast; ast.parse(s)
print("[OK] reconnect free")
