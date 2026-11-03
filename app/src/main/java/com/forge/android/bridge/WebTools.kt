package com.forge.android.bridge

import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Built-in web_search / fetch_url, mirroring desktop forge/web_backends.py.
 *
 * Search regions: "cn" = Bocha (BOCHA_API_KEY) -> Bing China; "global" = Brave
 * (BRAVE_API_KEY) -> Bing US -> DuckDuckGo. "auto" picks cn for queries with Chinese
 * characters; "both" interleaves the two. Keys come from installed connectors' env.
 *
 * fetch_url only reads public pages: the resolver itself refuses loopback, private,
 * link-local and similar addresses for every hop, redirects included.
 */
class WebTools(private val keyLookup: (String) -> String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .build()
    // No proxy: with an HTTP proxy the resolver would see the proxy, not the target.
    // VPN-mode proxies (Clash/sing-box TUN, fake-IP 198.18/15) still work.
    // OkHttp skips Dns for IP-literal hosts, so the resolver alone is not enough: the network
    // interceptor checks the address actually connected to, on every hop, before any byte of
    // the request is sent.
    private val pageClient = client.newBuilder().proxy(Proxy.NO_PROXY).dns(PublicDns)
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || !isPublic(address))
                throw java.io.IOException("${chain.request().url.host} is a private or local address (${address?.hostAddress}); not fetched")
            chain.proceed(chain.request())
        }.build()

    fun search(query: String, num: Int, site: String, region: String): JSONObject {
        val count = num.coerceIn(1, 10)
        val (q, domain) = siteQuery(query, site)
        val regions = resolveRegion(q, if (site.trim().lowercase() == "baidu" && region == "auto") "cn" else region)
        val batches = mutableListOf<List<JSONObject>>()
        val errors = mutableListOf<String>()
        for (name in regions) {
            val rows = oneRegion(q, count, name, domain, errors)
            if (rows.isNotEmpty()) batches += rows
        }
        if (batches.isEmpty()) throw IllegalStateException("all search providers failed: " + errors.joinToString(" | "))
        return JSONObject().put("results", JSONArray(interleave(batches)))
    }

    fun fetch(url: String, maxChars: Int): JSONObject {
        checkUrlShape(url)
        val request = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7").build()
        pageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val type = response.header("Content-Type", "").orEmpty()
            val kind = type.substringBefore(';').trim().lowercase()
            val source = response.body?.source() ?: throw IllegalStateException("empty response")
            source.request(MAX_BYTES + 1L)
            val raw = source.buffer.snapshot().toByteArray().let { if (it.size > MAX_BYTES) it.copyOf(MAX_BYTES) else it }
            if (kind == "application/pdf" || raw.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
                throw IllegalStateException("this is a PDF; fetch_url only reads web pages and plain text")
            if (kind.isNotEmpty() && !(kind.startsWith("text/") || kind in TEXT_TYPES))
                throw IllegalStateException("unsupported content type $kind")
            val text = decode(raw, type)
            val html = kind == "text/html" || kind == "application/xhtml+xml" || (kind.isEmpty() && "<html" in text.take(2000).lowercase())
            val (title, body) = if (html) htmlTitle(text) to htmlToText(text) else "" to text.trim()
            val limit = maxChars.coerceIn(200, 50_000)
            val shown = if (body.length > limit) body.take(limit) +
                "\n\n[showing the first $limit of ${body.length} characters; raise max_chars to read more]" else body
            return JSONObject().put("title", title).put("text", shown)
                .put("url", response.request.url.toString()).put("truncated_download", raw.size >= MAX_BYTES)
        }
    }

    private fun oneRegion(query: String, count: Int, region: String, domain: String?, errors: MutableList<String>): List<JSONObject> {
        val providers: List<Pair<String, () -> List<JSONObject>?>> = if (region == "cn") listOf(
            "bocha" to { keyLookup("BOCHA_API_KEY")?.let { bocha(query, count, it) } },
            "bing-cn" to { bing(query, count, china = true) })
        else listOf(
            "brave" to { keyLookup("BRAVE_API_KEY")?.let { brave(query, count, it) } },
            "bing-us" to { bing(query, count, china = false) },
            "duckduckgo" to { duckDuckGo(query) })
        for ((name, provider) in providers) {
            val rows = try { provider() } catch (t: Throwable) { errors += "$name: ${t.message}"; continue } ?: continue
            val kept = rows.filter { row ->
                val url = row.optString("url")
                url.startsWith("http://") || url.startsWith("https://")
            }.filter { domain == null || onDomain(it.optString("url"), domain) }  // some engines ignore site:
            if (kept.isNotEmpty()) return kept.take(count).map { it.put("snippet", it.optString("snippet").take(300)).put("engine", name) }
            errors += "$name: no results"
        }
        return emptyList()
    }

    private fun bocha(query: String, count: Int, key: String): List<JSONObject> {
        val body = JSONObject().put("query", query).put("count", count).toString().toRequestBody("application/json".toMediaType())
        val json = getJson(Request.Builder().url("https://api.bochaai.com/v1/web-search").post(body)
            .header("Authorization", "Bearer $key").build())
        val pages = json.optJSONObject("data")?.optJSONObject("webPages")?.optJSONArray("value") ?: JSONArray()
        return (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.map {
            JSONObject().put("title", it.optString("name")).put("url", it.optString("url")).put("snippet", it.optString("snippet"))
        }
    }

    private fun brave(query: String, count: Int, key: String): List<JSONObject> {
        val json = getJson(Request.Builder().url("https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=$count")
            .header("X-Subscription-Token", key).header("Accept", "application/json").build())
        val items = json.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map {
            JSONObject().put("title", it.optString("title")).put("url", it.optString("url"))
                .put("snippet", stripTags(it.optString("description")))
        }
    }

    private fun bing(query: String, count: Int, china: Boolean): List<JSONObject> {
        val params = if (china) "mkt=zh-CN&ensearch=0" else "mkt=en-US&setlang=en-US&cc=US"
        val host = if (china) "cn.bing.com" else "www.bing.com"
        // A bare UA gets empty RSS for Chinese queries; a full browser UA does not.
        val text = getText(Request.Builder().url("https://$host/search?q=${enc(query)}&format=rss&count=${count * 2}&$params")
            .header("User-Agent", UA).header("Accept", "application/rss+xml, application/xml").build())
        return parseBingRss(text)
    }

    private fun duckDuckGo(query: String): List<JSONObject> {
        val text = getText(Request.Builder().url("https://html.duckduckgo.com/html/")
            .post(FormBody.Builder().add("q", query).add("kl", "us-en").build()).header("User-Agent", UA).build())
        return parseDuckDuckGo(text)
    }

    private fun getText(request: Request): String = client.newCall(request).execute().use {
        if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
        it.body?.string().orEmpty()
    }

    private fun getJson(request: Request) = JSONObject(getText(request))

    /** Resolver used for page fetches: only public addresses (and the fake-IP proxy range). */
    object PublicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val bad = addresses.firstOrNull { !isPublic(it) }
            if (bad != null) throw UnknownHostException("$hostname resolves to a private or local address (${bad.hostAddress}); not fetched")
            return addresses
        }
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private const val MAX_BYTES = 3_000_000
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/xhtml+xml", "application/rss+xml")
        val REGIONS = listOf("auto", "cn", "global", "both")
        val SITES = mapOf("zhihu" to "zhihu.com", "xiaohongshu" to "xiaohongshu.com", "weixin" to "mp.weixin.qq.com",
            "bilibili" to "bilibili.com", "github" to "github.com", "reddit" to "reddit.com",
            "stackoverflow" to "stackoverflow.com", "wikipedia" to "wikipedia.org")
        private val CJK = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff]")
        private val DOMAIN = Regex("[a-z0-9.-]+\\.[a-z]{2,}")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun resolveRegion(query: String, region: String): List<String> = when (region) {
            "auto" -> listOf(if (CJK.containsMatchIn(query)) "cn" else "global")
            "both" -> listOf("cn", "global")
            "cn", "global" -> listOf(region)
            else -> throw IllegalArgumentException("region must be one of ${REGIONS.joinToString()}")
        }

        /** Returns the query with a site: operator and the domain to filter by, or null. */
        fun siteQuery(query: String, site: String): Pair<String, String?> {
            val s = site.trim().lowercase()
            if (s.isEmpty() || s in setOf("all", "baidu", "bing")) return query to null
            val domain = SITES[s] ?: s
            if (!DOMAIN.matches(domain)) throw IllegalArgumentException("site must be all, one of ${SITES.keys.joinToString()}, or a domain like github.com")
            return "$query site:$domain" to domain
        }

        fun onDomain(url: String, domain: String): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
            return host == domain || host.endsWith(".$domain")
        }

        fun interleave(batches: List<List<JSONObject>>): List<JSONObject> {
            val out = mutableListOf<JSONObject>(); val seen = mutableSetOf<String>()
            for (i in 0 until (batches.maxOfOrNull { it.size } ?: 0)) for (batch in batches) {
                val row = batch.getOrNull(i) ?: continue
                if (seen.add(row.optString("url").trimEnd('/'))) out += row
            }
            return out
        }

        fun checkUrlShape(url: String) {
            val uri = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("invalid URL")
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw IllegalArgumentException("only http(s) URLs with a host can be fetched")
            if (uri.userInfo != null) throw IllegalArgumentException("URLs with embedded credentials are not fetched")
            literalAddress(uri.host)?.let { if (!isPublic(it)) throw IllegalArgumentException("${uri.host} is a private or local address; not fetched") }
        }

        /** The address for an IP-literal host (no DNS lookup), or null for names. */
        fun literalAddress(host: String): InetAddress? {
            val h = host.removePrefix("[").removeSuffix("]")
            val ipv4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(h)
            return if (ipv4 || ':' in h) runCatching { InetAddress.getByName(h) }.getOrNull() else null
        }

        /** Public internet address? Fake-IP proxy range 198.18.0.0/15 counts as public. */
        fun isPublic(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 0xff }
            if (address is Inet4Address) {
                if (b[0] == 198 && (b[1] == 18 || b[1] == 19)) return true          // fake-IP (RFC 2544)
                if (b[0] == 0 || b[0] >= 224) return false
                if (b[0] == 100 && b[1] in 64..127) return false                   // CGNAT
                if (b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) return false
                if (b[0] == 198 && b[1] == 51 && b[2] == 100) return false
                if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false
                return true
            }
            if (address is Inet6Address) {
                if ((b[0] and 0xfe) == 0xfc) return false                          // ULA fc00::/7
                if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false  // documentation
                if (b.take(10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff)   // IPv4-mapped
                    return isPublic(InetAddress.getByAddress(b.drop(12).map { it.toByte() }.toByteArray()))
                return true
            }
            return false
        }

        fun parseBingRss(xml: String): List<JSONObject> =
            Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { item ->
                fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                    .find(item.groupValues[1])?.groupValues?.get(1)?.let { unescape(stripCdata(it)).trim() }.orEmpty()
                JSONObject().put("title", tag("title")).put("url", tag("link")).put("snippet", stripTags(tag("description")))
            }.filter { it.optString("title").isNotBlank() || it.optString("url").isNotBlank() }.toList()

        fun parseDuckDuckGo(page: String): List<JSONObject> {
            val links = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(page).toList()
            val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(page).toList()
            return links.mapIndexed { i, m ->
                val href = unescape(m.groupValues[1])
                val target = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                JSONObject().put("title", stripTags(m.groupValues[2])).put("url", target ?: href)
                    .put("snippet", snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]) }.orEmpty())
            }
        }

        private fun stripCdata(s: String) = s.removePrefix("<![CDATA[").removeSuffix("]]>")
        fun stripTags(s: String) = unescape(s.replace(Regex("<[^>]+>"), "")).trim()

        fun unescape(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp|#39);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"; "nbsp" -> " "
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }

        fun htmlTitle(html: String) = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()

        private val CHROME_TAGS = listOf("script", "style", "noscript", "svg", "template", "iframe", "head", "nav", "aside", "footer", "form", "button", "select")
        private val CHROME_ATTR = Regex("<(div|section|ul|header|span)\\b[^>]*(?:role=\"(?:navigation|banner|contentinfo|search|menu|complementary)\"|(?:class|id)=\"[^\"]*\\b(?:nav|navbar|menu|sidebar|breadcrumbs?|footer|toc|cookie|advert|ads|share|social)\\b[^\"]*\")[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        /** Readable text: chrome removed, the main/article region preferred, block tags as lines. */
        fun htmlToText(html: String): String {
            var s = html
            for (tag in CHROME_TAGS) s = s.replace(Regex("<$tag\\b.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            s = s.replace(CHROME_ATTR, " ")
            val main = Regex("<(main|article)\\b[^>]*>(.*)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(s)?.groupValues?.get(2)
            fun render(part: String): String {
                var t = part.replace(Regex("<h([1-3])\\b[^>]*>", RegexOption.IGNORE_CASE)) { "\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
                t = t.replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
                t = t.replace(Regex("</?(p|div|br|tr|section|article|header|h[1-6]|pre|blockquote|table|ul|ol|main)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
                t = unescape(t.replace(Regex("<[^>]+>"), ""))
                val lines = t.split('\n').map { it.replace(Regex("[ \\t\\r\\u000b\\f\\u00a0]+"), " ").trim() }
                    .filter { it !in setOf("-", "#", "##", "###") }
                return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
            }
            val article = main?.let(::render).orEmpty()
            return if (article.length >= 200) article else render(s)
        }

        fun decode(raw: ByteArray, contentType: String): String {
            val header = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            val meta = Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(String(raw.copyOf(minOf(raw.size, 4096)), Charsets.ISO_8859_1))?.groupValues?.get(1)
            var name = (header ?: meta ?: "utf-8").lowercase()
            if (name == "gb2312" || name == "gbk") name = "gb18030"   // GB2312 labels routinely carry GBK characters
            return runCatching { String(raw, charset(name)) }.getOrElse { String(raw, Charsets.UTF_8) }
        }
    }
}
