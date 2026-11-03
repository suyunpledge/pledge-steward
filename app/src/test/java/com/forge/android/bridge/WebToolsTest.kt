package com.forge.android.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebToolsTest {
    @Test
    fun region_followsQueryLanguage_andBothCoversTwo() {
        assertEquals(listOf("cn"), WebTools.resolveRegion("北京天气", "auto"))
        assertEquals(listOf("global"), WebTools.resolveRegion("weather boston", "auto"))
        assertEquals(listOf("cn", "global"), WebTools.resolveRegion("x", "both"))
        assertEquals(listOf("global"), WebTools.resolveRegion("北京", "global"))
        assertThrows { WebTools.resolveRegion("x", "mars") }
    }

    @Test
    fun site_addsOperator_andFiltersOffDomainResults() {
        assertEquals("forge site:github.com" to "github.com", WebTools.siteQuery("forge", "github"))
        assertEquals("x" to null, WebTools.siteQuery("x", "all"))
        assertEquals("x site:docs.python.org" to "docs.python.org", WebTools.siteQuery("x", "docs.python.org"))
        assertTrue(WebTools.onDomain("https://gist.github.com/a", "github.com"))
        assertFalse(WebTools.onDomain("https://notgithub.com/a", "github.com"))
        assertThrows { WebTools.siteQuery("x", "not a domain") }
    }

    @Test
    fun both_interleavesAndDropsDuplicates() {
        fun row(u: String) = JSONObject().put("url", u)
        val out = WebTools.interleave(listOf(listOf(row("https://a.cn/"), row("https://same.com/")),
            listOf(row("https://b.com"), row("https://same.com"))))
        assertEquals(listOf("https://a.cn/", "https://b.com", "https://same.com/"), out.map { it.getString("url") })
    }

    @Test
    fun onlyPublicAddresses_areFetched_fakeIpProxyAllowed() {
        fun ip(s: String) = InetAddress.getByName(s)
        for (bad in listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.5", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"))
            assertFalse(bad, WebTools.isPublic(ip(bad)))
        for (good in listOf("93.184.216.34", "198.18.1.47", "2606:4700::6810:84e5"))
            assertTrue(good, WebTools.isPublic(ip(good)))
        assertThrows { WebTools.checkUrlShape("file:///etc/passwd") }
        assertThrows { WebTools.checkUrlShape("https://u:p@site.example/") }
        // IP literals bypass the resolver: they must be rejected up front.
        for (literal in listOf("http://127.0.0.1:8765/", "http://192.168.1.1/", "http://[::1]/", "http://169.254.169.254/latest"))
            assertThrows { WebTools.checkUrlShape(literal) }
        WebTools.checkUrlShape("http://93.184.216.34/")
        WebTools.checkUrlShape("https://site.example/a?b=1")
    }

    @Test
    fun bingRss_andDuckDuckGo_parse() {
        val rss = "<rss><channel><item><title>A &amp; B</title><link>https://a.com/x</link>" +
            "<description>&lt;b&gt;hi&lt;/b&gt; there</description></item><item><title>天气</title>" +
            "<link>https://weather.com.cn/</link><description>晴</description></item></channel></rss>"
        val rows = WebTools.parseBingRss(rss)
        assertEquals(2, rows.size)
        assertEquals("A & B", rows[0].getString("title"))
        assertEquals("hi there", rows[0].getString("snippet"))
        val ddg = "<a rel=\"nofollow\" class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fx&amp;rut=1\">" +
            "<b>X</b> repo</a><a class=\"result__snippet\" href=\"#\">An &amp; snippet</a>"
        val d = WebTools.parseDuckDuckGo(ddg).single()
        assertEquals("https://github.com/x", d.getString("url"))
        assertEquals("X repo", d.getString("title"))
        assertEquals("An & snippet", d.getString("snippet"))
    }

    @Test
    fun pageText_skipsChrome_prefersArticle() {
        val page = "<html><head><title>T &amp; co</title><style>x{}</style></head><body>" +
            "<nav>Home | About</nav><div class=\"site-menu\">Menu</div><div role=\"navigation\">Links</div>" +
            "<main><h1>Heading</h1><p>" + "正文内容。".repeat(60) + "</p><ul><li>one</li></ul>" +
            "<script>evil()</script></main><footer>(c)</footer></body></html>"
        assertEquals("T & co", WebTools.htmlTitle(page))
        val text = WebTools.htmlToText(page)
        assertTrue(text, text.startsWith("# Heading"))
        assertTrue(text.contains("- one"))
        for (chrome in listOf("Home", "Menu", "Links", "evil", "(c)", "x{}")) assertFalse(chrome, text.contains(chrome))
    }

    @Test
    fun gbkPages_decode() {
        val html = "<html><head><meta charset='gb2312'><title>标题</title></head><body>中文</body></html>"
        val decoded = WebTools.decode(html.toByteArray(charset("GBK")), "text/html")
        assertTrue(decoded.contains("标题"))
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("expected IllegalArgumentException")
    }
}
