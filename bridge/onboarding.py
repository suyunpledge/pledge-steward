# -*- coding: utf-8 -*-
"""onboarding — 手机版新手引导（首启 → 配 API key → 主界面）。

桥侧路由：
- GET  /onboarding/status   → {needed: bool, has_provider: bool}
- GET  /onboarding/vendors  → 厂商目录（一键跳官方控制台 + 回填 key）
- POST /onboarding/apply    → {vendor, api_key, base_url?} 写入用户层
  provider（forge.patch.json），与桌面版同结构；写完 load_config 立即生效
- POST /onboarding/skip     → 跳过（记录跳过标记，下次不再打扰）

厂商目录（官方控制台 URL，2026-10 核对）：
  deepseek / zhipu(智谱) / moonshot(Kimi) / siliconflow(硅基流动) /
  openai / anthropic / gemini(需代理) / openrouter / ollama(本地)
"""
from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

HOME = Path(os.environ.get("FORGE_HOME", "/opt/forge/.forge-home"))
PATCH = HOME / "forge.patch.json"
SKIP_MARK = HOME / ".onboarding_skipped"

VENDORS = [
    {"id": "deepseek", "name": "DeepSeek", "icon": "🐳",
     "desc": "性价比最高，deepseek-chat/reasoner",
     "wire": "openai", "baseURL": "https://api.deepseek.com",
     "models": ["deepseek-chat", "deepseek-reasoner", "deepseek-flash"],
     "console": "https://platform.deepseek.com/api_keys"},
    {"id": "zhipu", "name": "智谱 GLM", "icon": "🧠",
     "desc": "GLM-5.3 / glm-4.7，国内直连",
     "wire": "openai", "baseURL": "https://open.bigmodel.cn/api/paas/v4",
     "models": ["GLM-5.3", "glm-4.7", "glm-4.6-air"],
     "console": "https://open.bigmodel.cn/usercenter/apikeys"},
    {"id": "moonshot", "name": "Kimi (月之暗面)", "icon": "🌙",
     "desc": "kimi-k2 系列",
     "wire": "openai", "baseURL": "https://api.moonshot.cn/v1",
     "models": ["kimi-k2", "kimi-k2-turbo"],
     "console": "https://platform.moonshot.cn/console/api-keys"},
    {"id": "siliconflow", "name": "硅基流动", "icon": "🌊",
     "desc": "聚合平台，多模型可选",
     "wire": "openai", "baseURL": "https://api.siliconflow.cn/v1",
     "models": ["deepseek-ai/DeepSeek-V3.2", "Qwen/Qwen3-235B-A22B-Instruct"],
     "console": "https://cloud.siliconflow.cn/account/ak"},
    {"id": "openai", "name": "OpenAI", "icon": "🤖",
     "desc": "GPT 系列（需海外网络）",
     "wire": "openai", "baseURL": "https://api.openai.com/v1",
     "models": ["gpt-5.2", "gpt-5-mini"],
     "console": "https://platform.openai.com/api-keys"},
    {"id": "anthropic", "name": "Anthropic", "icon": "📜",
     "desc": "Claude 系列（需海外网络）",
     "wire": "anthropic", "baseURL": "https://api.anthropic.com",
     "models": ["claude-sonnet-5", "claude-haiku-4-5"],
     "console": "https://console.anthropic.com/settings/keys"},
    {"id": "openrouter", "name": "OpenRouter", "icon": "🛜",
     "desc": "全球模型聚合，一个 key 用百模",
     "wire": "openai", "baseURL": "https://openrouter.ai/api/v1",
     "models": ["anthropic/claude-sonnet-5", "google/gemini-3-pro"],
     "console": "https://openrouter.ai/keys"},
    {"id": "ollama", "name": "Ollama 本地", "icon": "🦙",
     "desc": "手机本地模型（llama.cpp :8101），无需 key",
     "wire": "openai", "baseURL": "http://127.0.0.1:8101/v1",
     "models": ["qwen3:8b"], "keyless": True,
     "console": ""},
]


def _load_patch() -> dict[str, Any]:
    if PATCH.exists():
        try:
            return json.loads(PATCH.read_text(encoding="utf-8"))
        except Exception:                    # noqa: BLE001
            pass
    return {}


def _save_patch(data: dict[str, Any]) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    PATCH.write_text(json.dumps(data, ensure_ascii=False, indent=1),
                     encoding="utf-8")


def has_provider() -> bool:
    """用户层是否已有至少一个带 key 的 provider。"""
    data = _load_patch()
    rows = data.get("rows") or data.get("providers") or []
    if isinstance(rows, dict):
        rows = list(rows.values())
    for row in rows:
        conf = row.get("config", {}) if isinstance(row, dict) else {}
        if conf.get("apiKey"):
            return True
    return False


def status() -> dict[str, Any]:
    return {"needed": not SKIP_MARK.exists() and not has_provider(),
            "has_provider": has_provider(),
            "skipped": SKIP_MARK.exists()}


def apply_key(vendor_id: str, api_key: str, base_url: str | None = None) -> dict[str, Any]:
    """把厂商 key 写进用户层 provider 行（与桌面 forge.patch.json 同结构）。

    结构：{"rows": [{"id": ..., "name": "provider:...", "config": {...}}]}
    （forge config.apply_patch 兼容 rows 列表形态。）
    """
    vendor = next((v for v in VENDORS if v["id"] == vendor_id), None)
    if vendor is None:
        return {"ok": False, "error": f"unknown vendor {vendor_id!r}"}
    if not vendor.get("keyless") and not (api_key or "").strip():
        return {"ok": False, "error": "empty api key"}
    if base_url and not base_url.startswith(("http://", "https://")):
        return {"ok": False, "error": "base_url must be http(s)"}

    data = _load_patch()
    rows = data.get("rows")
    if rows is None:
        rows = []
        data["rows"] = rows
    row_id = vendor["id"]
    conf = {
        "wire": vendor["wire"],
        "baseURL": base_url or vendor["baseURL"],
        "apiKey": (api_key or "").strip(),
        "model": {"primary": vendor["models"][0], "options": vendor["models"]},
    }
    # 覆盖或追加
    replaced = False
    for i, row in enumerate(rows):
        if isinstance(row, dict) and row.get("id") == row_id:
            rows[i] = {"id": row_id,
                       "name": f"provider:{row_id}",
                       "config": conf}
            replaced = True
            break
    if not replaced:
        rows.append({"id": row_id, "name": f"provider:{row_id}", "config": conf})
    _save_patch(data)
    # 引导完成标记
    SKIP_MARK.parent.mkdir(parents=True, exist_ok=True)
    SKIP_MARK.write_text("done")
    return {"ok": True, "vendor": vendor["name"],
            "model": vendor["models"][0]}


def skip() -> dict[str, Any]:
    SKIP_MARK.parent.mkdir(parents=True, exist_ok=True)
    SKIP_MARK.write_text("skipped")
    return {"ok": True}


def register_routes(app) -> None:
    from pydantic import BaseModel

    class ApplyBody(BaseModel):
        vendor: str
        api_key: str = ""
        base_url: str | None = None

    @app.get("/onboarding/status")
    def onb_status():
        return status()

    @app.get("/onboarding/vendors")
    def onb_vendors():
        # console URL 交给 Kotlin 跳系统浏览器；key 不随目录下发
        return {"vendors": [
            {k: v[k] for k in ("id", "name", "icon", "desc", "models",
                               "console", "keyless")}
            for v in VENDORS]}

    @app.post("/onboarding/apply")
    def onb_apply(body: ApplyBody):
        return apply_key(body.vendor, body.api_key, body.base_url)

    @app.post("/onboarding/skip")
    def onb_skip():
        return skip()
