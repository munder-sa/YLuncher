package com.gt8d.launcher.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteLabelTest {
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
