# -*- coding: utf-8 -*-
"""smoke_onboarding — 引导流程冒烟：status 判定 / vendors 目录 / apply 写用户层 /
skip 标记 / keyless 通道 / 重复 apply 覆盖。"""
import json
import os
import sys
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIDGE = HERE.parent / "bridge"
sys.path.insert(0, str(BRIDGE))
FORGE_ROOT = os.environ.setdefault("FORGE_ROOT", r"C:\Users\匡溯昀\pledge-evolving")

FAILS = []


def check(c, m):
    print(("PASS " if c else "FAIL ") + m)
    if not c:
        FAILS.append(m)


import onboarding as ob

tmp = Path(tempfile.mkdtemp(prefix="onb-"))
ob.HOME = tmp
ob.PATCH = tmp / "forge.patch.json"
ob.SKIP_MARK = tmp / ".onboarding_skipped"

# 1. 初始态：needed
st = ob.status()
check(st["needed"] is True and st["has_provider"] is False, "首启需要引导")

# 2. 厂商目录完整性
vs = ob.VENDORS
check(len(vs) >= 8, f"厂商目录（{len(vs)}）")
ids = {v["id"] for v in vs}
check({"deepseek", "zhipu", "openai", "ollama"} <= ids, "核心厂商在列")
for v in vs:
    assert v["console"].startswith("http") or v["keyless"], v["id"]
    assert v["models"], v["id"]
check(True, "console/models 字段合法")
kl = [v for v in vs if v.get("keyless")]
check(len(kl) == 1 and kl[0]["id"] == "ollama", "Ollama 免 key 通道")

# 3. apply：写用户层 + 完成标记
r = ob.apply_key("deepseek", "sk-test-123")
check(r.get("ok") is True and r.get("model") == "deepseek-chat", "deepseek key 落盘")
patch = json.loads(ob.PATCH.read_text(encoding="utf-8"))
row = patch["rows"][0]
check(row["id"] == "deepseek" and row["config"]["apiKey"] == "sk-test-123"
      and row["config"]["baseURL"] == "https://api.deepseek.com", "patch.json 结构正确")

# 4. has_provider → True；status → 不再需要
check(ob.has_provider() is True, "有 provider 判定")
st = ob.status()
check(st["needed"] is False, "配好后引导不再触发")

# 5. 重复 apply 覆盖（换 key）
r = ob.apply_key("deepseek", "sk-test-456")
patch = json.loads(ob.PATCH.read_text(encoding="utf-8"))
check(len(patch["rows"]) == 1 and patch["rows"][0]["config"]["apiKey"] == "sk-test-456",
      "覆盖不重复")

# 6. 多厂商追加
ob.apply_key("zhipu", "zp-key")
patch = json.loads(ob.PATCH.read_text(encoding="utf-8"))
check(len(patch["rows"]) == 2, "第二厂商追加")

# 7. 空 key 拒绝（非 keyless）
r = ob.apply_key("openai", "")
check(r.get("ok") is False, "空 key 拒绝")

# 8. keyless 直接过
r = ob.apply_key("ollama", "")
check(r.get("ok") is True, "Ollama 免 key 通过")

# 9. 未知厂商拒绝
r = ob.apply_key("nope", "x")
check(r.get("ok") is False, "未知厂商拒绝")

# 10. skip 流（独立 home）
tmp2 = Path(tempfile.mkdtemp(prefix="onb2-"))
ob.HOME = tmp2
ob.PATCH = tmp2 / "forge.patch.json"
ob.SKIP_MARK = tmp2 / ".onboarding_skipped"
r = ob.skip()
st = ob.status()
check(r.get("ok") and st["needed"] is False and st["skipped"] is True, "skip 标记生效")

print()
print("FAILED:" if FAILS else "ALL ONBOARDING SMOKE PASSED", len(FAILS))
sys.exit(1 if FAILS else 0)
