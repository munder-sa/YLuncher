package com.gt8d.launcher.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleWebTokenLogicTest {
    @Test fun preservesMusicTokenOriginalChecks() {
        // The original Java test's 21 inputs and expectations, in their original order.
        assertEquals(null, AppleWebTokenLogic.findToken(null))
        assertEquals(null, AppleWebTokenLogic.findToken("other=x; not-media-user-token=wrong"))
        assertEquals(null, AppleWebTokenLogic.findToken("media-user-token="))
        assertEquals("0.a%2Fb+c==", AppleWebTokenLogic.findToken("other=x; media-user-token=0.a%2Fb+c==; after=y"))
        assertEquals("test=value", AppleWebTokenLogic.findToken("media-user-token=test=value"))
        assertEquals(null, AppleWebTokenLogic.findToken("media-user-token=one\ntwo"))
        val header = "media-user-token=fake-test-only; Domain=.music.apple.com; Expires=Mon, 03 May 2027 01:40:44 GMT; HttpOnly; Secure"
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(header, "fake-test-only"))
        assertEquals(null, AppleWebTokenLogic.expirationFromSetCookie(header, "different"))
        assertEquals(null, AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Max-Age=15552000", "x"))
        assertEquals(null, AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Expires=bad", "x"))
        assertEquals(true, AppleWebTokenLogic.isAppleHttpsUrl("https://music.apple.com/"))
        assertEquals(true, AppleWebTokenLogic.isAppleHttpsUrl("https://idmsa.apple.com/appleauth/auth/signin"))
        assertEquals(true, AppleWebTokenLogic.isAppleHttpsUrl("https://account.apple.com:443/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("https://apple.com.evil.example/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("https://evilapple.com/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("https://evil@apple.com/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("http://music.apple.com/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("javascript:alert(1)"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl("https://apple.com:1234/"))
        assertEquals(false, AppleWebTokenLogic.isAppleHttpsUrl(null))
        assertEquals("https://idmsa.apple.com", AppleWebTokenLogic.originLabel("https://idmsa.apple.com/path?secret=never-display"))
    }

    @Test fun rejectsControlsMalformedUnicodeAndBounds() {
        // All 65 ISO controls, independently enumerated, at boundaries and inside input.
        for (code in (0x00..0x1f) + (0x7f..0x9f)) {
            assertRejectedText(code.toChar().toString())
        }
        for (malformed in listOf("\uD800", "\uDBFF", "\uDC00", "\uDFFF", "\uD800x", "\uD800\uD800", "\uDC00\uD800")) {
            assertRejectedText(malformed)
        }

        val tokenLimit = 16 * 1024
        val boundaryTokens = listOf(
            "a".repeat(tokenLimit),
            "é".repeat(tokenLimit / 2),
            "あ".repeat(tokenLimit / 3) + "a",
            "\uD83C\uDFB5".repeat(tokenLimit / 4),
        )
        for (token in boundaryTokens) {
            assertEquals(tokenLimit, token.toByteArray(Charsets.UTF_8).size)
            assertEquals(token, AppleWebTokenLogic.findToken("media-user-token=$token"))
            assertEquals(token, AppleWebTokenLogic.findToken("  media-user-token =  $token  "))
            assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
                "media-user-token=$token; Expires=$VALID_EXPIRY", token,
            ))
            assertNull(AppleWebTokenLogic.findToken("media-user-token=${token}a"))
            assertNull(AppleWebTokenLogic.expirationFromSetCookie(
                "media-user-token=${token}a; Expires=$VALID_EXPIRY", token + "a",
            ))
            assertEquals("next", AppleWebTokenLogic.findToken("media-user-token=${token}a; media-user-token=next"))
        }
        // Neighbors outside the control ranges and valid surrogate pairs remain opaque data.
        val validUnicode = "~\u00A0éあ\uD7FF\uE000\uD800\uDC00\uDBFF\uDFFF"
        assertEquals(validUnicode, AppleWebTokenLogic.findToken("media-user-token=$validUnicode"))

        val headerLimit = 64 * 1024
        val prefix = "media-user-token=x; Expires=$VALID_EXPIRY; other="
        val remaining = headerLimit - prefix.length
        val boundaryHeaders = listOf(
            prefix + "a".repeat(remaining),
            prefix + "é".repeat(remaining / 2) + "a".repeat(remaining % 2),
            prefix + "あ".repeat(remaining / 3) + "a".repeat(remaining % 3),
            prefix + "\uD83C\uDFB5".repeat(remaining / 4) + "a".repeat(remaining % 4),
        )
        for (header in boundaryHeaders) {
            assertEquals(headerLimit, header.toByteArray(Charsets.UTF_8).size)
            assertEquals("x", AppleWebTokenLogic.findToken(header))
            assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(header, "x"))
            assertNull(AppleWebTokenLogic.findToken(header + "a"))
            assertNull(AppleWebTokenLogic.expirationFromSetCookie(header + "a", "x"))
        }
        val oversizedPrefix = " ".repeat(headerLimit + 1)
        assertNull(AppleWebTokenLogic.findToken(oversizedPrefix + "media-user-token=x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x", oversizedPrefix))
        assertEquals("x", AppleWebTokenLogic.findToken("  media-user-token = x  ; other=y  "))
        // Java String.trim() did not strip Unicode spaces; do not silently rewrite a token.
        assertEquals("\u2003x\u2003", AppleWebTokenLogic.findToken("media-user-token= \u2003x\u2003 "))

        val urlPrefix = "https://music.apple.com/"
        val url = urlPrefix + "a".repeat(8192 - urlPrefix.length)
        assertEquals(8192, url.length)
        assertTrue(AppleWebTokenLogic.isAppleHttpsUrl(url))
        assertEquals("https://music.apple.com", AppleWebTokenLogic.originLabel(url))
        assertFalse(AppleWebTokenLogic.isAppleHttpsUrl(url + "a"))
        assertEquals("Apple Music", AppleWebTokenLogic.originLabel(url + "a"))
    }

    @Test fun keepsOpaqueValuesAndExactCookieNames() {
        for (token in listOf("+/%/==", "0.a%2Fb+c==", "test=value", "=", "==", "%2f%ZZ%00", "one two", "\"quoted\"")) {
            assertEquals(token, AppleWebTokenLogic.findToken("other=before; media-user-token= $token ; after=after"))
        }
        for (name in listOf("Media-user-token", "MEDIA-USER-TOKEN", "not-media-user-token", "media-user-token-extra", "media-user-tokenX", "media_user_token")) {
            assertNull(AppleWebTokenLogic.findToken("$name=wrong"))
        }
        assertEquals("first==", AppleWebTokenLogic.findToken("media-user-token=first==; media-user-token=second"))
        assertEquals("valid", AppleWebTokenLogic.findToken("media-user-token; media-user-token= ; media-user-token=valid; media-user-token=later"))
        assertEquals("valid", AppleWebTokenLogic.findToken("; other=x; ; media-user-token = valid ;"))
        for (header in listOf(null, "", " ", ";;", "media-user-token", "media-user-token=", "media-user-token=   ", "other=x")) {
            assertNull(AppleWebTokenLogic.findToken(header))
        }
    }

    @Test fun expiryUsesOnlyMatchingCookieAndStrictCompleteDates() {
        for (date in listOf(
            "Mon, 03 May 2027 01:40:44 GMT",
            "Mon, 03-May-2027 01:40:44 GMT",
            "Monday, 03-May-27 01:40:44 GMT",
        )) {
            assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
                "media-user-token=x; Expires=$date", "x",
            ))
        }
        // Absolute metadata is returned even for dates in the past; no comparison with now.
        for ((date, epochMillis) in listOf(
            "Thu, 01 Jan 1970 00:00:00 GMT" to 0L,
            "Sat, 01 Jan 2000 00:00:00 GMT" to 946684800000L,
            "Thu, 01 Jan 2009 00:00:00 GMT" to 1230768000000L,
            "Tue, 19 Jan 2038 03:14:07 GMT" to 2147483647000L,
        )) {
            assertEquals(epochMillis, AppleWebTokenLogic.expirationFromSetCookie(
                "media-user-token=x; Expires=$date", "x",
            ))
        }
        for (date in listOf(
            "", "bad", "$VALID_EXPIRY trailing", "$VALID_EXPIRY,", "prefix $VALID_EXPIRY",
            "Mon, 31 Feb 2027 01:40:44 GMT", "Mon, 29 Feb 2027 01:40:44 GMT",
            "Mon, 03 May 2027 24:40:44 GMT", "Mon, 03 May 2027 01:60:44 GMT",
            "Mon, 03 May 2027 01:40:60 GMT", "Mon, 03 May 2027 01:40:44",
            "Mon, 03 May 2027 01:40:44 UnknownZone", "Tue, 03 May 2027 01:40:44 GMT",
            "Wed, 31 Dec 1969 23:59:59 GMT", "Wed, 31-Dec-1969 23:59:59 GMT",
            "Wednesday, 31-Dec-69 23:59:59 GMT",
        )) {
            assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Expires=$date", "x"))
        }
        val header = "media-user-token=x; Expires=$VALID_EXPIRY"
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(null, "x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(header, null))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(header, ""))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(header, "different"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(header, " x "))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=; Expires=$VALID_EXPIRY", ""))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("other=x; Expires=$VALID_EXPIRY", "x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x", "x"))
        for (maxAge in listOf("0", "15552000", "-1", "999999999999999999999999999999")) {
            assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Max-Age=$maxAge", "x"))
        }
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=x; Max-Age=0; eXpIrEs = $VALID_EXPIRY  ; Secure", "x",
        ))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; not-expires=$VALID_EXPIRY", "x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Expires", "x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=other; media-user-token=x; Expires=$VALID_EXPIRY", "x",
        ))
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token= ; media-user-token=x; Expires=$VALID_EXPIRY", "x",
        ))
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=x; Expires=bad; Expires=$VALID_EXPIRY", "x",
        ))
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=x; Expires=$VALID_EXPIRY; Expires=Tue, 19 Jan 2038 03:14:07 GMT", "x",
        ))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=a%2Fb+c==; Expires=$VALID_EXPIRY", "a/b c==",
        ))
        assertEquals(1809308444000L, AppleWebTokenLogic.expirationFromSetCookie(
            "media-user-token=a%2Fb+c==; Expires=$VALID_EXPIRY", "a%2Fb+c==",
        ))
    }

    @Test fun navigationRejectsLookalikesAndUnsafeOrigins() {
        for ((url, origin) in listOf(
            "https://apple.com" to "https://apple.com",
            "https://music.apple.com/" to "https://music.apple.com",
            "https://idmsa.apple.com/appleauth/auth/signin" to "https://idmsa.apple.com",
            "https://a.b.apple.com:443/path?secret=query#secret-fragment" to "https://a.b.apple.com",
            "HTTPS://MUSIC.APPLE.COM:443/private-token?token=query-token#fragment-token" to "https://music.apple.com",
            "https://account.apple.com:443/" to "https://account.apple.com",
        )) {
            assertTrue(AppleWebTokenLogic.isAppleHttpsUrl(url))
            assertEquals(origin, AppleWebTokenLogic.originLabel(url))
        }
        for (url in listOf(
            null, "", " ", "https://", "/relative", "//apple.com/", "https:apple.com",
            "https:///apple.com", "https://apple.com.evil.example/", "https://music.apple.com.evil.example/",
            "https://evilapple.com/", "https://apple.com-evil.example/", "https://apple-com/",
            "https://apple.com./", "https://.apple.com/", "https://a..apple.com/",
            "https://evil@apple.com/", "https://@apple.com/", "https://user:pass@apple.com/",
            "https://apple.com@evil.example/", "https://apple.com:80/", "https://apple.com:444/",
            "https://apple.com:0/", "https://apple.com:-1/", "https://apple.com:999999999999/",
            "http://apple.com/", "intent://apple.com/", "javascript:alert(1)",
            "file:///apple.com/", "data:text/html,apple.com", "about:blank", "about:srcdoc",
            "https://аpple.com/", "https://apple。com/", "https://ａｐｐｌｅ.com/",
            "https://é.apple.com/", "https://apple.com\u200B/", "https://%61pple.com/",
            "https://apple%2Ecom/", "https://[apple.com]/", "https://[::1]/",
            " https://apple.com/", "https://apple.com/ ", "https://apple.com/a b",
            "https://apple.com/%", "https://apple.com/%zz", "https://apple.com\\@evil.example/",
            "https://apple.com/\nprivate", "https://apple.com/\u007Fprivate", "https://apple.com/\u0085private",
        )) {
            assertFalse(AppleWebTokenLogic.isAppleHttpsUrl(url))
            assertEquals("Apple Music", AppleWebTokenLogic.originLabel(url))
        }
    }

    private fun assertRejectedText(invalid: String) {
        for (header in listOf(
            "${invalid}media-user-token=x", "media-user-token=x$invalid", "media-user-token=x${invalid}y",
            "media-user-token=${invalid}x", "media-user-token=x; other=$invalid", "other=$invalid; media-user-token=x",
            "media-user-${invalid}token=x",
        )) {
            assertNull(AppleWebTokenLogic.findToken(header))
            assertNull(AppleWebTokenLogic.expirationFromSetCookie("$header; Expires=$VALID_EXPIRY", "x"))
        }
        for (token in listOf("${invalid}x", "x$invalid", "x${invalid}y")) {
            assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Expires=$VALID_EXPIRY", token))
        }
        val header = "media-user-token=x; Expires=$VALID_EXPIRY"
        assertNull(AppleWebTokenLogic.expirationFromSetCookie(header + invalid, "x"))
        assertNull(AppleWebTokenLogic.expirationFromSetCookie("media-user-token=x; Expires=${invalid}$VALID_EXPIRY", "x"))
        for (url in listOf(
            "${invalid}https://apple.com/", "https://apple.com/$invalid", "https://apple.com/x${invalid}y",
            "https://app${invalid}le.com/", "https://apple.com/?x=$invalid", "https://apple.com/#$invalid",
        )) {
            assertFalse(AppleWebTokenLogic.isAppleHttpsUrl(url))
            assertEquals("Apple Music", AppleWebTokenLogic.originLabel(url))
        }
    }

    private companion object {
        const val VALID_EXPIRY = "Mon, 03 May 2027 01:40:44 GMT"
    }
}
