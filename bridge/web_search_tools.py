# -*- coding: utf-8 -*-
"""web_search_tools — 内置联网搜索（不再是连接器，Agent 直接可用）。

把搜索服务「内置」的落点：注册为 forge Agent 工具面的 web_search 工具，
按环境变量/配置自动选择 provider（有 key 用付费强的，没有用免费源），
全部失败时返回明确错误（不静默空结果）。

Provider 优先级（谁配了用谁，都配了用第一个）：
  1. bocha   （博查，中文最佳，付费）
  2. brave   （国际，免费额度 2000 次/月）
  3. bing    （零凭据兜底——cn.bing.com RSS，国内直连无墙）
"""

import json
import os
import re
import urllib.parse
import urllib.request
from typing import Any

TIMEOUT = 15


def _bocha(query: str, key: str, count: int) -> dict[str, Any] | None:
    try:
        req = urllib.request.Request(
            "https://api.bochaai.com/v1/web-search",
            data=json.dumps({"query": query, "count": count}).encode(),
            headers={"Authorization": f"Bearer {key}",
                     "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = json.loads(r.read())
        pages = data.get("data", {}).get("webPages", {}).get("value", [])
        return {"engine": "bocha", "results": [
            {"title": p.get("name", ""), "url": p.get("url", ""),
             "snippet": p.get("snippet", "")[:300]} for p in pages[:count]]}
    except Exception:
        return None


def _brave(query: str, key: str, count: int) -> dict[str, Any] | None:
    try:
        req = urllib.request.Request(
            "https://api.search.brave.com/res/v1/web/search?q=" + urllib.parse.quote(query),
            headers={"X-Subscription-Token": key, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = json.loads(r.read())
        items = data.get("web", {}).get("results", [])
        return {"engine": "brave", "results": [
            {"title": i.get("title", ""), "url": i.get("url", ""),
             "snippet": i.get("description", "")[:300]} for i in items[:count]]}
    except Exception:
        return None


def _bing_rss(query: str, count: int) -> dict[str, Any] | None:
    """零凭据兜底：Bing 中国 RSS（国内直连、无需 Key、真搜索结果）。"""
    import xml.etree.ElementTree as ET
    try:
        # mkt 参数跟随查询语言（中文 zh-CN 显著改善结果；count 参数
        # 对 RSS 常被忽略，靠下面截断控制）
        mkt = "zh-CN" if re.search(r"[\u4e00-\u9fff]", query) else None
        url = ("https://cn.bing.com/search?q=" + urllib.parse.quote(query)
               + "&format=rss" + (f"&mkt={mkt}" if mkt else "")
               + "&count=" + str(count * 2))
        # Bing 风控：朴素 UA 对中文查询返回空 RSS——用完整 Chrome UA
        # + ensearch=0（强制国内版）才有结果
        req = urllib.request.Request(
            url.replace("format=rss", "format=rss&ensearch=0"),
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                     "AppleWebKit/537.36 (KHTML, like Gecko) "
                     "Chrome/130.0.0.0 Safari/537.36",
                     "Accept": "application/rss+xml, application/xml"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            body = r.read()
        root = ET.fromstring(body)
        results = []
        for item in root.iter("item"):
            title = (item.findtext("title") or "").strip()
            link = (item.findtext("link") or "").strip()
            desc = re.sub(r"<[^>]+>", "", item.findtext("description") or "").strip()
            if title or link:
                results.append({"title": title, "url": link,
                                "snippet": desc[:300]})
            if len(results) >= count:
                break
        if results:
            return {"engine": "bing", "results": results}
        return None
    except Exception:
        return None


def web_search(query: str, count: int = 5) -> dict[str, Any]:
    """Agent 工具入口：联网搜索。

    Provider 自动选择：BOCHA_API_KEY / BRAVE_API_KEY 环境变量（连接器
    配置里同名 env 同步过来），否则 DuckDuckGo 免费兜底。
    """
    count = max(1, min(int(count or 5), 10))
    bocha_key = os.environ.get("BOCHA_API_KEY", "")
    brave_key = os.environ.get("BRAVE_API_KEY", "")
    if bocha_key:
        r = _bocha(query, bocha_key, count)
        if r:
            return {"ok": True, **r}
    if brave_key:
        r = _brave(query, brave_key, count)
        if r:
            return {"ok": True, **r}
    r = _bing_rss(query, count)
    if r:
        return {"ok": True, **r}
    return {"ok": False,
            "error": "all search providers failed/unconfigured"}


WEB_SEARCH_TOOL = {
    "name": "web_search",
    "description": "联网搜索（自动选择可用引擎；返回标题/链接/摘要）",
    "parameters": {"type": "object", "properties": {
        "query": {"type": "string"},
        "count": {"type": "integer", "description": "结果数 1-10，默认 5"},
    }, "required": ["query"]},
    "readOnly": True,
    "fn": web_search,
}
