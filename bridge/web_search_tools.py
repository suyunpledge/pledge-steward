# -*- coding: utf-8 -*-
"""web_search_tools — 内置联网搜索（默认美国端点，不再是连接器，Agent 直接可用）。

把搜索服务「内置」的落点：注册为 forge Agent 工具面的 web_search 工具，
按环境变量自动选择 provider（有 key 用付费强的，没有用免费源），
全部失败时返回明确错误（不静默空结果）。

地区策略：默认市场是美国（REGION_LOCK）——零凭据兜底固定带
mkt=en-US / cc=US / kl=us-en 与 Accept-Language: en-US，不随查询
语言切换默认市场。网络互通不设限：付费/区域 provider（博查、Bing
中国市场）按 key 与兜底位次可选，任意出口都可达。

Provider 优先级（谁配了用谁，都配了用第一个）：
  1. bocha       （博查 Search API，付费，配 BOCHA_API_KEY 启用）
  2. brave       （Brave Search API，免费额度 2000 次/月）
  3. bing        （Bing 国际版 RSS，www.bing.com，零凭据兜底）
  4. duckduckgo  （DuckDuckGo HTML，零凭据第二兜底）
  5. bing_cn     （Bing 中国市场 RSS，中文查询的末位兜底）
"""

import json
import os
import re
import urllib.parse
import urllib.request
from typing import Any

TIMEOUT = 15
REGION_LOCK = "US"          # 默认市场：免费兜底固定美国端点；付费/区域源按 key 可选
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
       "AppleWebKit/537.36 (KHTML, like Gecko) "
       "Chrome/130.0.0.0 Safari/537.36")
_HEADERS = {"User-Agent": _UA,
            "Accept-Language": "en-US,en;q=0.9"}


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
        url = ("https://api.search.brave.com/res/v1/web/search?q="
               + urllib.parse.quote(query)
               + f"&country={REGION_LOCK.lower()}&search_lang=en")
        req = urllib.request.Request(
            url,
            headers={"X-Subscription-Token": key, "Accept": "application/json",
                     "Accept-Language": "en-US,en;q=0.9"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = json.loads(r.read())
        items = data.get("web", {}).get("results", [])
        return {"engine": "brave", "results": [
            {"title": i.get("title", ""), "url": i.get("url", ""),
             "snippet": i.get("description", "")[:300]} for i in items[:count]]}
    except Exception:
        return None


def _bing_rss(query: str, count: int) -> dict[str, Any] | None:
    """零凭据兜底一：Bing 国际版 RSS（www.bing.com，美国市场参数）。"""
    import xml.etree.ElementTree as ET
    try:
        # mkt/cc 固定 en-US/US：美国市场结果，不随查询语言漂移。
        # count 对 RSS 常被忽略，靠下面截断控制。
        url = ("https://www.bing.com/search?q=" + urllib.parse.quote(query)
               + f"&format=rss&mkt=en-{REGION_LOCK}&cc={REGION_LOCK}"
               + "&count=" + str(count * 2))
        req = urllib.request.Request(
            url, headers={"Accept": "application/rss+xml, application/xml",
                          **_HEADERS})
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


def _duckduckgo(query: str, count: int) -> dict[str, Any] | None:
    """零凭据兜底二：DuckDuckGo HTML（kl=us-en，美国结果）。"""
    try:
        url = ("https://html.duckduckgo.com/html/?q="
               + urllib.parse.quote(query) + "&kl=us-en")
        req = urllib.request.Request(url, headers=_HEADERS)
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            html = r.read().decode("utf-8", "replace")
        results = []
        for m in re.finditer(
                r'class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>',
                html, re.S):
            href, title = m.group(1), re.sub(r"<[^>]+>", "", m.group(2)).strip()
            # DDG 跳转链接形如 //duckduckgo.com/l/?uddg=<encoded>&rut=...
            split = urllib.parse.urlsplit(
                href if href.startswith("http") else "https:" + href)
            target = urllib.parse.parse_qs(split.query).get("uddg", [href])[0]
            if title and target:
                results.append({"title": title, "url": target, "snippet": ""})
            if len(results) >= count:
                break
        snippets = re.findall(
            r'class="result__snippet"[^>]*>(.*?)</a>', html, re.S)
        for i, s in enumerate(snippets[:len(results)]):
            results[i]["snippet"] = re.sub(r"<[^>]+>", "", s).strip()[:300]
        if results:
            return {"engine": "duckduckgo", "results": results}
        return None
    except Exception:
        return None


def _bing_cn(query: str, count: int) -> dict[str, Any] | None:
    """末位兜底：Bing 中国市场 RSS（中文内容覆盖好，无需 Key）。"""
    import xml.etree.ElementTree as ET
    try:
        # mkt 跟随查询语言（中文查询 zh-CN 结果更准）；ensearch=0 取
        # 中国结果版。count 对 RSS 常被忽略，靠下面截断控制。
        mkt = "zh-CN" if any(0x4E00 <= ord(ch) <= 0x9FFF for ch in query) else "en-US"
        url = ("https://cn.bing.com/search?q=" + urllib.parse.quote(query)
               + "&format=rss&ensearch=0" + f"&mkt={mkt}"
               + "&count=" + str(count * 2))
        # Bing 风控：朴素 UA 可能返回空 RSS——用完整 Chrome UA
        req = urllib.request.Request(
            url,
            headers={"User-Agent": _UA,
                     "Accept": "application/rss+xml, application/xml",
                     "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8"})
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
            return {"engine": "bing_cn", "results": results}
        return None
    except Exception:
        return None


def web_search(query: str, count: int = 5) -> dict[str, Any]:
    """Agent 工具入口：联网搜索（默认美国端点）。

    Provider 自动选择：BOCHA_API_KEY / BRAVE_API_KEY 环境变量（连接
    器配置里同名 env 同步过来），无 key 依次落到 Bing 国际版 /
    DuckDuckGo / Bing 中国市场兜底。
    """
    count = max(1, min(int(count or 5), 10))
    bocha_key = os.environ.get("BOCHA_API_KEY", "")
    if bocha_key:
        r = _bocha(query, bocha_key, count)
        if r:
            return {"ok": True, **r}
    brave_key = os.environ.get("BRAVE_API_KEY", "")
    if brave_key:
        r = _brave(query, brave_key, count)
        if r:
            return {"ok": True, **r}
    r = _bing_rss(query, count)
    if r:
        return {"ok": True, **r}
    r = _duckduckgo(query, count)
    if r:
        return {"ok": True, **r}
    r = _bing_cn(query, count)
    if r:
        return {"ok": True, **r}
    return {"ok": False,
            "error": "all search providers failed/unconfigured"}


WEB_SEARCH_TOOL = {
    "name": "web_search",
    "description": "联网搜索（默认美国端点：博查/Brave 可选，Bing 国际版/DuckDuckGo 兜底；返回标题/链接/摘要）",
    "parameters": {"type": "object", "properties": {
        "query": {"type": "string"},
        "count": {"type": "integer", "description": "结果数 1-10，默认 5"},
    }, "required": ["query"]},
    "readOnly": True,
    "fn": web_search,
}
