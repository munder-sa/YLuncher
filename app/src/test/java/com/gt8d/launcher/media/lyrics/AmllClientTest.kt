package com.gt8d.launcher.media.lyrics

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmllClientTest {
    @Test fun rawLyricsUsesConfirmedMirrorsAnd404Stops() = runBlocking {
        val target = AmllTarget("raw-lyrics", "one.ttml")
        for (code in listOf(500, 404)) {
            val urls = mutableListOf<String>()
            val client = AmllClient(AmllTransport { url, _ -> urls += url; if (urls.size == 1) HttpResponse(code, "", null) else ok }, "a",
                AmllIndexLookup { _, _ -> AmllMatch.Unique(target) })
            val result = client.fetch(request(null))
            val expected = listOf("https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/raw-lyrics/one.ttml", "https://amll-ttml-db.gbclstudio.cn/raw-lyrics/one.ttml")
            assertEquals(if (code == 404) expected.take(1) else expected, urls)
            assertEquals(if (code == 404) AmllReason.NOT_FOUND else AmllReason.INDEX_TEXT, result.reason)
        }
    }
    private val query = LyricsQuery("架空曲", "架空歌手", null, 10)
    private fun request(id: String? = "123", isrc: String? = null) = LyricsRequest(query, LyricsIdentity(id, isrc))
    private val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1" end="3"><span begin="1">A</span><span begin="2"> B</span></p></div></body></tt>"""
    private val ok get() = HttpResponse(200, xml, null)

    @Test fun verifiedIdSkipsIndex() = runBlocking {
        val urls = mutableListOf<String>()
        var lookups = 0
        val result = AmllClient(AmllTransport { url, headers ->
            urls += url
            assertEquals("agent", headers["User-Agent"])
            ok
        }, "agent", AmllIndexLookup { _, _ -> lookups++; AmllMatch.Unavailable }).fetch(request())
        assertEquals(0, lookups)
        assertEquals(listOf(AmllClient.urls("123")[0]), urls)
        assertEquals(AmllReason.DIRECT_ID, result.reason)
        assertTrue(result.cacheable)
        assertEquals(listOf(LyricWord(1000, 0), LyricWord(2000, 1)), (result.state as LyricsState.Synced).lines.single().words)
    }

    @Test fun unverifiedIdUsesIndex() = runBlocking {
        for (id in listOf(null, "abc", "../1", "１２３", "0", "")) {
            var lookups = 0
            var calls = 0
            val client = AmllClient(AmllTransport { _, _ -> calls++; ok }, "a", AmllIndexLookup { q, isrc ->
                assertEquals(query, q); assertNull(isrc); lookups++
                AmllMatch.Unique(AmllTarget("am-lyrics", "456"))
            })
            assertEquals(AmllReason.INDEX_TEXT, client.fetch(request(id)).reason)
            assertEquals(1, lookups); assertEquals(1, calls)
        }
    }

    @Test fun direct404TriesIndexOnce() = runBlocking {
        val urls = mutableListOf<String>()
        var lookups = 0
        val client = AmllClient(AmllTransport { url, _ ->
            urls += url
            if (url.endsWith("123.ttml")) HttpResponse(404, "", null) else ok
        }, "a", AmllIndexLookup { _, _ -> lookups++; AmllMatch.Unique(AmllTarget("am-lyrics", "456")) })
        assertTrue(client.fetch(request()).state is LyricsState.Synced)
        assertEquals(1, lookups)
        assertEquals(listOf(AmllClient.urls("123")[0], AmllClient.urls("456")[0]), urls)
    }

    @Test fun directIoFailureFallsBackWithoutIndex() = runBlocking {
        var calls = 0
        var lookups = 0
        val client = AmllClient(AmllTransport { _, _ -> calls++; throw IOException() }, "a",
            AmllIndexLookup { _, _ -> lookups++; AmllMatch.Unavailable }, { 1000 })
        val result = client.fetch(request())
        assertEquals(LyricsState.Offline, result.state)
        assertFalse(result.cacheable)
        assertEquals(3, calls); assertEquals(0, lookups)
        assertEquals(AmllReason.WAIT, client.fetch(request()).reason)
        assertEquals(3, calls)
    }

    @Test fun isrcPrecedesTextMatch() = runBlocking {
        var lookups = 0
        val client = AmllClient(AmllTransport { _, _ -> ok }, "a", AmllIndexLookup { q, isrc ->
            assertEquals(query, q); assertEquals("JPABC1234567", isrc); lookups++
            AmllMatch.Unique(AmllTarget("am-lyrics", "456"))
        })
        assertEquals(AmllReason.INDEX_ISRC, client.fetch(request(null, "jp-abc-12-34567")).reason)
        assertEquals(1, lookups)
    }

    @Test fun wordlessOrAmbiguousNotAdopted() = runBlocking {
        val wordless = xml.replace("<span begin=\"1\">A</span><span begin=\"2\"> B</span>", "A B")
        val client = AmllClient(AmllTransport { _, _ -> HttpResponse(200, wordless, null) }, "a")
        val result = client.fetch(request())
        assertEquals(LyricsState.Missing, result.state)
        assertEquals(AmllReason.UNSUPPORTED, result.reason)
        assertFalse(result.cacheable)
        val ambiguous = AmllClient(AmllTransport { _, _ -> error("must not fetch") }, "a",
            AmllIndexLookup { _, _ -> AmllMatch.Ambiguous }).fetch(request(null))
        assertEquals(AmllReason.AMBIGUOUS, ambiguous.reason)
        assertEquals(LyricsState.Missing, ambiguous.state)
        assertFalse(ambiguous.cacheable)
        val notFound = AmllClient(AmllTransport { _, _ -> error("must not fetch") }, "a",
            AmllIndexLookup { _, _ -> AmllMatch.NotFound }).fetch(request(null))
        assertEquals(LyricsState.Missing, notFound.state)
        assertFalse(notFound.cacheable)
    }

    @Test fun hostWaitDoesNotAffectLrclib() = runBlocking {
        var now = 1000L
        val urls = mutableListOf<String>()
        val client = AmllClient(AmllTransport { url, _ -> urls += url; HttpResponse(429, "", 120) }, "a", clock = { now })
        val result = client.fetch(request())
        assertEquals(3, result.hostWaitUntil.size)
        assertEquals(120L, result.retryAfterSec)
        client.fetch(request("456"))
        assertEquals(3, urls.size)
        var lrclibCalls = 0
        val lrclib = LrclibClient(HttpTransport { _, _ -> lrclibCalls++; HttpResponse(200, """{"plainLyrics":"架空"}""", null) }, "a", sleep = {})
        assertEquals(LyricsState.Plain("架空"), lrclib.fetch(query).state)
        assertEquals(2, lrclibCalls)
        now += 120000
        client.fetch(request("789"))
        assertEquals(6, urls.size)
    }

    @Test fun retryAfterInvalidUses60Seconds() = runBlocking {
        for (retry in listOf(null, 0L, -1L)) {
            val result = AmllClient(AmllTransport { _, _ -> HttpResponse(503, "", retry) }, "a", clock = { 1000 })
                .fetch(request())
            assertEquals(60L, result.retryAfterSec)
            assertEquals(setOf(61000L), result.hostWaitUntil.values.toSet())
            assertFalse(result.cacheable)
        }
    }

    @Test fun mirrorsFollowSpecifiedOrderAndInvalidResponsesAdvance() = runBlocking {
        val urls = mutableListOf<String>()
        val client = AmllClient(AmllTransport { url, _ ->
            urls += url
            when (urls.size) {
                1 -> HttpResponse(200, "<html>error</html>", null)
                2 -> HttpResponse(200, "<tt xmlns=\"http://www.w3.org/ns/ttml\">", null)
                else -> ok
            }
        }, "a")
        assertTrue(client.fetch(request()).state is LyricsState.Synced)
        assertEquals(AmllClient.urls("123"), urls)
    }

    @Test fun notFoundStopsMirrorsAndUnavailableIndexIsNotNegative() = runBlocking {
        var calls = 0
        val client = AmllClient(AmllTransport { _, _ -> calls++; HttpResponse(404, "", null) }, "a")
        assertFalse(client.fetch(request()).cacheable)
        assertEquals(1, calls)
        val unavailable = client.fetch(request(null))
        assertEquals(AmllReason.UNAVAILABLE, unavailable.reason)
        assertFalse(unavailable.cacheable)
    }

    @Test fun unsafeIndexTargetAndCancellationAreRejected() = runBlocking {
        val client = AmllClient(AmllTransport { _, _ -> error("must not fetch") }, "a",
            AmllIndexLookup { _, _ -> AmllMatch.Unique(AmllTarget("../raw", "123")) })
        assertEquals(AmllReason.INVALID, client.fetch(request(null)).reason)
        try {
            AmllClient(AmllTransport { _, _ -> throw CancellationException("cancel") }, "a").fetch(request())
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun sameIndexTargetDoesNotRetryDirect404() = runBlocking {
        var calls = 0
        var lookups = 0
        val client = AmllClient(AmllTransport { _, _ -> calls++; HttpResponse(404, "", null) }, "a",
            AmllIndexLookup { _, _ -> lookups++; AmllMatch.Unique(AmllTarget("am-lyrics", "123")) })
        assertEquals(AmllReason.NOT_FOUND, client.fetch(request()).reason)
        assertEquals(1, calls); assertEquals(1, lookups)
    }

    @Test fun malformedAndNonTtmlNeverBecomeNegative() = runBlocking {
        for (body in listOf("<tt xmlns=\"http://www.w3.org/ns/ttml\">", "<tt xmlns=\"urn:other\"/>", "<html><tt/></html>", "<!DOCTYPE tt><tt/>")) {
            var calls = 0
            val result = AmllClient(AmllTransport { _, _ -> calls++; HttpResponse(200, body, null) }, "a")
                .fetch(request())
            assertEquals(AmllReason.INVALID, result.reason)
            assertFalse(result.cacheable)
            assertEquals(3, calls)
        }
    }
}
