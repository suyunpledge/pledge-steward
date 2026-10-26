# -*- coding: utf-8 -*-
"""connector_catalog — 预置连接器目录（一键接入的数据源）。

设计：每条预置 = 用户只需要补凭据（或零凭据）即可连接。
- transport: "http"（远程 MCP，填 url+token）或 "stdio"（npx/pip 命令）
- auth.kind: none / api_key / oauth —— 决定 Kotlin 表单渲染哪些字段
- auth.console_url：取 key 的控制台直达链接（表单里「去获取」按钮）
- env_map：表单字段 → 连接器 env 变量名的映射

数据来源：awesome-china-mcp（2026-10-10 复核）+ 各官方文档，官方优先。
"""

CATALOG = [
    # ── 地图与出行 ─────────────────────────────────────────
    {
        "id": "amap", "name": "高德地图", "category": "出行", "icon": "🗺️",
        "desc": "地理编码/路线规划/POI 搜索/天气（官方，免费额度）",
        "transport": "stdio",
        "command": ["npx", "-y", "@amap/amap-maps-mcp-server"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "AMAP_MAPS_API_KEY", "label": "高德 Web 服务 Key"}],
            "console_url": "https://console.amap.com/dev/key/app"},
    },
    {
        "id": "baidu_maps", "name": "百度地图", "category": "出行", "icon": "🗺️",
        "desc": "定位/路线/天气/地点搜索（官方，⭐447）",
        "transport": "stdio",
        "command": ["npx", "-y", "@baidu-maps/mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "BAIDU_MAPS_API_KEY", "label": "百度地图 AK"}],
            "console_url": "https://lbs.baidu.com/apiconsole/key"},
    },
    {
        "id": "qq_maps", "name": "腾讯地图", "category": "出行", "icon": "🗺️",
        "desc": "地理编码/POI/路线/天气（官方远程 MCP）",
        "transport": "http",
        "url": "https://mcp.map.qq.com/mcp?key={TENCENT_MAPS_KEY}",
        "auth": {"kind": "api_key", "fields": [
            {"env": "TENCENT_MAPS_KEY", "label": "腾讯位置服务 Key"}],
            "console_url": "https://lbs.qq.com/dev/console/key/manage"},
    },
    {
        "id": "train12306", "name": "12306 火车票", "category": "出行", "icon": "🚄",
        "desc": "车票查询（社区 ⭐2.3k，零凭据）",
        "transport": "stdio",
        "command": ["uvx", "mcp-server-12306"],
        "auth": {"kind": "none", "fields": []},
    },
    {
        "id": "didi", "name": "滴滴出行", "category": "出行", "icon": "🚕",
        "desc": "叫车/估价/行程状态（官方远程，需企业开通）",
        "transport": "http",
        "url": "https://mcp.didichuxing.com/mcp-servers?key={DIDI_KEY}",
        "auth": {"kind": "api_key", "fields": [
            {"env": "DIDI_KEY", "label": "滴滴开放平台 Key"}],
            "console_url": "https://open.didichuxing.com/"},
    },
    # ── 办公协作 ─────────────────────────────────────────
    {
        "id": "feishu", "name": "飞书", "category": "办公", "icon": "🐦",
        "desc": "文档/表格/IM/日历（官方 ⭐838）",
        "transport": "stdio",
        "command": ["npx", "-y", "@larksuiteoapi/lark-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "APP_ID", "label": "飞书应用 App ID"},
            {"env": "APP_SECRET", "label": "飞书应用 App Secret"}],
            "console_url": "https://open.feishu.cn/app"},
    },
    {
        "id": "yuque", "name": "语雀", "category": "办公", "icon": "📖",
        "desc": "知识库增删查/搜索/看板（官方 ⭐248）",
        "transport": "stdio",
        "command": ["npx", "-y", "@yuque/yuque-mcp-server"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "YUQUE_TOKEN", "label": "语雀 Token"}],
            "console_url": "https://www.yuque.com/settings/tokens"},
    },
    {
        "id": "tencent_docs", "name": "腾讯文档", "category": "办公", "icon": "📄",
        "desc": "文档/表格/幻灯片读写（官方远程）",
        "transport": "http",
        "url": "https://docs.qq.com/openapi/mcp",
        "auth": {"kind": "oauth", "fields": [
            {"env": "TENCENT_DOCS_TOKEN", "label": "腾讯文档开放平台凭据"}],
            "console_url": "https://docs.qq.com/openapi/"},
    },
    {
        "id": "wemeet", "name": "腾讯会议", "category": "办公", "icon": "📹",
        "desc": "预约会议/参会人/录制/转写（官方远程）",
        "transport": "http",
        "url": "https://mcp.meeting.tencent.com/mcp/wemeet-open/v1",
        "auth": {"kind": "api_key", "fields": [
            {"env": "WEMEET_TOKEN", "label": "腾讯会议开放平台凭据"}],
            "console_url": "https://meeting.tencent.com/support/api.html"},
    },
    {
        "id": "wps", "name": "WPS 365", "category": "办公", "icon": "📝",
        "desc": "企业云文档搜索/读取/分享/权限（官方远程）",
        "transport": "http",
        "url": "https://openapi.wps.cn/mcp/v2/kso-yundoc/message",
        "auth": {"kind": "api_key", "fields": [
            {"env": "WPS_TOKEN", "label": "WPS 开放平台凭据"}],
            "console_url": "https://openapi.wps.cn/"},
    },
    {
        "id": "notion", "name": "Notion", "category": "办公", "icon": "🗒️",
        "desc": "页面/数据库搜索读写（官方远程，含 Notion AI 能力）",
        "transport": "http",
        "url": "https://mcp.notion.com/mcp",
        "auth": {"kind": "oauth", "fields": [
            {"env": "AUTH_TOKEN", "label": "Notion OAuth Token"}],
            "console_url": "https://www.notion.so/my-integrations"},
    },
    # ── 企业信息 ─────────────────────────────────────────
    {
        "id": "qichacha", "name": "企查查", "category": "企业", "icon": "🏢",
        "desc": "工商/风险/知产/司法/招投标（官方远程×10，按次计费）",
        "transport": "http",
        "url": "https://agent.qcc.com/mcp/company/stream",
        "auth": {"kind": "api_key", "fields": [
            {"env": "AUTH_TOKEN", "label": "企查查 API Key"}],
            "console_url": "https://openapi.qcc.com/"},
    },
    {
        "id": "tianyancha", "name": "天眼查", "category": "企业", "icon": "🏢",
        "desc": "工商/股东/风险/诉讼（官方远程，OAuth）",
        "transport": "http",
        "url": "https://mcp.tianyancha.com/mcp",
        "auth": {"kind": "oauth", "fields": [
            {"env": "AUTH_TOKEN", "label": "天眼查凭据"}],
            "console_url": "https://open.tianyancha.com/"},
    },
    # ── 内容与社交 ─────────────────────────────────────────
    {
        "id": "xiaohongshu", "name": "小红书", "category": "内容", "icon": "📕",
        "desc": "读/搜索/发布笔记（社区 ⭐16.2k，需扫码 Cookie）",
        "transport": "stdio",
        "command": ["uvx", "xiaohongshu-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "XHS_COOKIE", "label": "小红书登录 Cookie"}],
            "console_url": "https://github.com/xpzouying/xiaohongshu-mcp"},
    },
    {
        "id": "bilibili", "name": "Bilibili", "category": "内容", "icon": "📺",
        "desc": "视频搜索/信息（社区）",
        "transport": "stdio",
        "command": ["npx", "-y", "bilibili-mcp-js"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "BILIBILI_COOKIE", "label": "B 站 Cookie（可选）"}],
            "console_url": "https://github.com/34892002/bilibili-mcp-js"},
    },
    {
        "id": "weibo", "name": "微博", "category": "内容", "icon": "🌊",
        "desc": "用户/内容/热搜（社区）",
        "transport": "stdio",
        "command": ["uvx", "mcp-server-weibo"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "WEIBO_COOKIE", "label": "微博 Cookie"}],
            "console_url": "https://github.com/qinyuanpei/mcp-server-weibo"},
    },
    {
        "id": "weread", "name": "微信读书", "category": "内容", "icon": "📚",
        "desc": "书架/笔记/划线（社区 ⭐579）",
        "transport": "stdio",
        "command": ["uvx", "mcp-server-weread"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "WEREAD_COOKIE", "label": "微信读书 Cookie"}],
            "console_url": "https://weread.qq.com/"},
    },
    # ── 搜索与 AI ─────────────────────────────────────────
    {
        "id": "bocha", "name": "博查 AI 搜索", "category": "搜索", "icon": "🔍",
        "desc": "中文网页/新闻/图片搜索（官方 ⭐185，给 Agent 实时信源）",
        "transport": "stdio",
        "command": ["npx", "-y", "bocha-search-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "BOCHA_API_KEY", "label": "博查 API Key"}],
            "console_url": "https://open.bochaai.com/"},
    },
    {
        "id": "zhipu_search", "name": "智谱 Web 搜索", "category": "搜索", "icon": "🧠",
        "desc": "Web 搜索/网页阅读/Zread（官方远程，GLM Coding Plan key）",
        "transport": "http",
        "url": "https://open.bigmodel.cn/api/mcp/web_search_prime/mcp",
        "auth": {"kind": "api_key", "fields": [
            {"env": "AUTH_TOKEN", "label": "智谱 API Key"}],
            "console_url": "https://open.bigmodel.cn/usercenter/apikeys"},
    },
    {
        "id": "tencent_websearch", "name": "腾讯 Web 搜索", "category": "搜索", "icon": "🔍",
        "desc": "网页搜索（官方）",
        "transport": "stdio",
        "command": ["npx", "-y", "@tencent/websearch-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "TENCENT_SEARCH_KEY", "label": "腾讯搜索 Key"}],
            "console_url": "https://github.com/Tencent/WebSearchMCP"},
    },
    # ── 金融 ─────────────────────────────────────────
    {
        "id": "akshare", "name": "A股行情 (AKShare)", "category": "金融", "icon": "📈",
        "desc": "A股/加密/宏观数据（社区 ⭐390，免费）",
        "transport": "stdio",
        "command": ["uvx", "mcp-aktools"],
        "auth": {"kind": "none", "fields": []},
    },
    {
        "id": "xuangubao", "name": "选股宝", "category": "金融", "icon": "📊",
        "desc": "大涨股板块+原因/全球财经全文（官方远程）",
        "transport": "http",
        "url": "https://xgb-mcp-api.xuangubao.cn/mcp",
        "auth": {"kind": "api_key", "fields": [
            {"env": "AUTH_TOKEN", "label": "选股宝 Token"}],
            "console_url": "https://xuangubao.cn/"},
    },
    # ── 电商比价 ─────────────────────────────────────────
    {
        "id": "taobao", "name": "淘宝比价", "category": "电商", "icon": "🛒",
        "desc": "淘宝/天猫/京东商品数据与比价（社区）",
        "transport": "stdio",
        "command": ["npx", "-y", "taobaoscraper-mcp"],
        "auth": {"kind": "none", "fields": []},
    },
    {
        "id": "jd_deals", "name": "京东超级秒杀", "category": "电商", "icon": "🛍️",
        "desc": "京东优惠/秒杀信息（社区）",
        "transport": "stdio",
        "command": ["npx", "-y", "mcp-jd-super-deals"],
        "auth": {"kind": "none", "fields": []},
    },
    {
        "id": "xianyu", "name": "闲鱼", "category": "电商", "icon": "🐟",
        "desc": "闲鱼发布/管理/IM（社区，需 Cookie）",
        "transport": "stdio",
        "command": ["uvx", "xianyu-buyer-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "XIANYU_COOKIE", "label": "闲鱼 Cookie"}],
            "console_url": "https://github.com/xhjiang1998/xianyu-buyer-mcp"},
    },
    # ── 生活服务 ─────────────────────────────────────────
    {
        "id": "luckin", "name": "瑞幸咖啡", "category": "生活", "icon": "☕",
        "desc": "附近门店/点单（官方远程，开放平台）",
        "transport": "http",
        "url": "https://gwmcp.lkcoffee.com/order/user/mcp",
        "auth": {"kind": "oauth", "fields": [
            {"env": "AUTH_TOKEN", "label": "瑞幸开放平台凭据"}],
            "console_url": "https://open.lkcoffee.com/"},
    },
    {
        "id": "mcd", "name": "麦当劳", "category": "生活", "icon": "🍔",
        "desc": "营养/附近门店/菜单（官方远程）",
        "transport": "http",
        "url": "https://mcp.mcd.cn",
        "auth": {"kind": "oauth", "fields": [
            {"env": "AUTH_TOKEN", "label": "麦当劳授权 Token"}],
            "console_url": "https://mcp.mcd.cn"},
    },
    {
        "id": "kuaidi100", "name": "快递100", "category": "生活", "icon": "📦",
        "desc": "全快递商包裹轨迹查询（官方，只读，企业账号）",
        "transport": "stdio",
        "command": ["uvx", "kuaidi100-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "KUAIDI100_KEY", "label": "快递100 Key"},
            {"env": "KUAIDI100_CUSTOMER", "label": "Customer 编号"}],
            "console_url": "https://api.kuaidi100.com/"},
    },
    # ── 开发 ─────────────────────────────────────────
    {
        "id": "github", "name": "GitHub", "category": "开发", "icon": "🐙",
        "desc": "仓库/Issue/PR/Actions（官方远程）",
        "transport": "http",
        "url": "https://api.githubcopilot.com/mcp/",
        "auth": {"kind": "api_key", "fields": [
            {"env": "AUTH_TOKEN", "label": "GitHub PAT (copilot plan)"}],
            "console_url": "https://github.com/settings/tokens"},
    },
    {
        "id": "gitee", "name": "Gitee", "category": "开发", "icon": "🐢",
        "desc": "仓库/Issue/PR（官方 ⭐66）",
        "transport": "stdio",
        "command": ["uvx", "mcp-gitee"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "GITEE_TOKEN", "label": "Gitee 私人令牌"}],
            "console_url": "https://gitee.com/profile/personal_access_tokens"},
    },
    # ── 天气与工具 ─────────────────────────────────────────
    {
        "id": "caiyun", "name": "彩云天气", "category": "工具", "icon": "🌦️",
        "desc": "分钟级降水预报（官方，免费额度）",
        "transport": "stdio",
        "command": ["uvx", "mcp-caiyun-weather"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "CAIYUN_TOKEN", "label": "彩云天气 Token"}],
            "console_url": "https://dashboard.caiyunapp.com/"},
    },
    {
        "id": "textin", "name": "TextIn 文档解析", "category": "工具", "icon": "📑",
        "desc": "PDF/图片→Markdown，保留表格与版式（合合信息官方）",
        "transport": "stdio",
        "command": ["npx", "-y", "@intsig-textin/textin-mcp"],
        "auth": {"kind": "api_key", "fields": [
            {"env": "TEXTIN_API_KEY", "label": "TextIn API Key"}],
            "console_url": "https://www.textin.com/"},
    },
]


def by_id(cid: str):
    for item in CATALOG:
        if item["id"] == cid:
            return item
    return None
