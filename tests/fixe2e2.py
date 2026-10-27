from pathlib import Path
p = Path("e2e_remote.py")
s = p.read_text(encoding="utf-8")
lines = s.splitlines()
# 把 from pathlib import Path 提到 sys.path.insert 之前（第一行 import 之后）
for i, ln in enumerate(lines):
    if ln.startswith("import asyncio"):
        lines.insert(i + 4, "from pathlib import Path")
        break
s = "\n".join(lines) + "\n"
s = s.replace('sys.path.insert(0, r"bridge")',
              'sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))')
p.write_text(s, encoding="utf-8", newline="")
print("[OK] import order")
