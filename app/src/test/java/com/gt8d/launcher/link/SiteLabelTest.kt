package com.gt8d.launcher.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteLabelTest {
    @Test fun fromTitle_lastSegment() {
        assertEquals("Wikipedia", SiteLabel.fromTitle("Android (OS) - Wikipedia"))
        assertEquals("日本経済新聞", SiteLabel.fromTitle("記事 | 日本経済新聞"))
        assertEquals("最後", SiteLabel.fromTitle("記事 - 中間 — 最後"))
        assertEquals("最後", SiteLabel.fromTitle("記事 – 最後"))
        assertEquals("最後", SiteLabel.fromTitle("記事 ｜ 最後"))
        assertEquals("Wikipedia", SiteLabel.fromTitle("記事 -  Wikipedia  "))
        assertEquals("a".repeat(30), SiteLabel.fromTitle("記事 - " + "a".repeat(30)))
    }

    @Test fun fromTitle_noSeparator_null() {
        assertNull(SiteLabel.fromTitle("Wikipedia"))
        assertNull(SiteLabel.fromTitle(null))
        assertNull(SiteLabel.fromTitle("記事 -   "))
    }

    @Test fun fromTitle_tooLong_null() {
        assertNull(SiteLabel.fromTitle("記事 - " + "a".repeat(31)))
    }

    @Test fun label_priority() {
        val url = "https://www.example.com/a"
        assertEquals("Site", SiteLabel.label(" Site ", " App ", "記事 - Title", url))
        assertEquals("App", SiteLabel.label("  ", " App ", "記事 - Title", url))
        assertEquals("Title", SiteLabel.label(null, "  ", "記事 - Title", url))
        assertEquals("example.com", SiteLabel.label(null, null, "記事", url))
    }

    @Test fun siteName_wins() {
        assertEquals("Wikipedia", SiteLabel.label(" Wikipedia ", "https://example.com/a"))
    }

    @Test fun noSiteName_usesHostWithoutWww() {
        assertEquals("example.com", SiteLabel.label(null, "https://www.example.com/a"))
        assertEquals("example.com", SiteLabel.label("   ", "https://www.example.com/a"))
    }

    @Test fun subdomainKept() {
        assertEquals("ja.wikipedia.org", SiteLabel.label(null, "https://ja.wikipedia.org/wiki/X"))
    }

    @Test fun badUrl_empty() {
        assertEquals("", SiteLabel.label(null, "not a url"))
        assertEquals("", SiteLabel.label(null, null))
        assertEquals("", SiteLabel.label(null, ""))
    }

    @Test fun parseJsString_cases() {
        assertEquals("Wikipedia", SiteLabel.parseJsString("\"Wikipedia\""))
        assertNull(SiteLabel.parseJsString("null"))
        assertNull(SiteLabel.parseJsString("\"\""))
        assertNull(SiteLabel.parseJsString(null))
        assertNull(SiteLabel.parseJsString(""))
        assertEquals("A \"B\"", SiteLabel.parseJsString("\"A \\\"B\\\"\""))
    }
}
