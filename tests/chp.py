from pathlib import Path
p = Path(r"smoke_assistant.py")
s = p.read_text(encoding="utf-8")
s = s.replace("8311", "8312")
p.write_text(s, encoding="utf-8", newline="")
print("[OK] port -> 8312")
