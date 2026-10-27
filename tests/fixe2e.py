from pathlib import Path
p = Path("e2e_remote.py")
s = p.read_text(encoding="utf-8")
s = s.replace('sys.path.insert(0, r"bridge")',
              'sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "bridge"))')
p.write_text(s, encoding="utf-8", newline="")
print("[OK] e2e path")
