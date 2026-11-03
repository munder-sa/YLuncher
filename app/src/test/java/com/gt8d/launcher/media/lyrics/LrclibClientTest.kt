package com.gt8d.launcher.media.lyrics

import java.io.IOException
import com.gt8d.launcher.diag.DiagLog
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class LrclibClientTest {
    @Test fun tooManyRequestsWithoutHeaderIs60() {
        response = HttpResponse(429, "", null)
        assertEquals(FetchResult(LyricsState.Failed, 60), client.fetch(query))
    }

    @Test fun tooManyRequestsNonPositiveHeaderIs60() {
        for (seconds in listOf(0L, -1L)) {
            response = HttpResponse(429, "", seconds)
            assertEquals(FetchResult(LyricsState.Failed, 60), client.fetch(query))
        }
    }
    private val query = LyricsQuery("ダミー", "サンプル", null, null)
    private var response = HttpResponse(200, "{}", null)
    private var url = ""
    private var headers = emptyMap<String, String>()
    private val client = LrclibClient(HttpTransport { u, h ->
        if (u.startsWith("https://lrclib.net/api/search?")) return@HttpTransport HttpResponse(200, "[]", null)
        url = u
        headers = h
        response
    }, "DummyLauncher/1", sleep = {}, jitter = { 0 })

    @Test fun buildsUrl() {
        client.fetch(LyricsQuery("ダミー ソング&1", "サンプル", "A", 210))
        val title = URLEncoder.encode("ダミー ソング&1", "UTF-8").replace("+", "%20")
        val artist = URLEncoder.encode("サンプル", "UTF-8").replace("+", "%20")
        assertEquals("https://lrclib.net/api/get?track_name=$title&artist_name=$artist&album_name=A&duration=210", url)
        assertTrue(url.contains("%20"))
        assertTrue(url.contains("%26"))
    }

    @Test fun omitsAlbumAndDurationWhenNull() {
        client.fetch(query)
        assertFalse(url.contains("album_name"))
        assertFalse(url.contains("duration"))
    }

    @Test fun sendsUserAgent() {
        client.fetch(query)
        assertEquals("DummyLauncher/1", headers["User-Agent"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test fun ioExceptionIsOffline() {
        val offline = LrclibClient(HttpTransport { _, _ -> throw IOException("dummy") }, "dummy")
        assertEquals(FetchResult(LyricsState.Offline), offline.fetch(query))
    }

    @Test fun tooManyRequestsCarriesRetryAfter() {
        response = HttpResponse(429, "", 30, true)
        assertEquals(FetchResult(LyricsState.Failed, 30), client.fetch(query))
    }

    @Test fun notFoundIsMissing() {
        response = HttpResponse(404, "", null, true)
        assertEquals(FetchResult(LyricsState.Missing), client.fetch(query))
    }

    @Test fun badRequestIsMissing() {
        response = HttpResponse(400, "", null)
        assertEquals(FetchResult(LyricsState.Missing), client.fetch(query))
    }

    @Test fun serverErrorIsFailed() {
        response = HttpResponse(500, "{}", null)
        assertEquals(FetchResult(LyricsState.Failed, cacheable = false), client.fetch(query))
    }

    @Test fun badJsonIsFailed() {
        for (body in listOf("\"oops\"", "{broken", "[]", "null")) {
            response = HttpResponse(200, body, null)
            assertEquals(FetchResult(LyricsState.Failed), client.fetch(query))
        }
    }

    @Test fun tooLargeIsFailed() {
        response = HttpResponse(200, "{}", null, true)
        assertEquals(FetchResult(LyricsState.Failed), client.fetch(query))
    }

    @Test fun instrumentalWinsOverLyrics() {
        response = HttpResponse(200, """{"instrumental":true,"syncedLyrics":"[00:01.00]x","plainLyrics":"x"}""", null)
        assertEquals(FetchResult(LyricsState.Missing), client.fetch(query))
    }

    @Test fun syncedPreferred() {
        response = HttpResponse(200, """{"syncedLyrics":"[00:01.00]ダミー","plainLyrics":"サンプル"}""", null)
        assertEquals(FetchResult(LyricsState.Synced(LrcParser.parse("[00:01.00]ダミー"))), client.fetch(query))
    }

    @Test fun unparsableSyncedFallsBackToPlain() {
        response = HttpResponse(200, """{"syncedLyrics":"no time","plainLyrics":"  ダミー  "}""", null)
        assertEquals(FetchResult(LyricsState.Plain("ダミー")), client.fetch(query))
    }

    @Test fun bothEmptyIsMissing() {
        for (body in listOf("""{"syncedLyrics":null,"plainLyrics":null}""", "{}",
            """{"syncedLyrics":123,"plainLyrics":false}""", """{"plainLyrics":"  "}""")) {
            response = HttpResponse(200, body, null)
            assertEquals(FetchResult(LyricsState.Missing), client.fetch(query))
        }
    }

    private val searchQuery = LyricsQuery("Dummy Song&1", "Dummy Artist", " Album A ", 302)
    private val plainResponse = HttpResponse(200, """{"plainLyrics":"dummy plain"}""", null)
    private val plainResult = FetchResult(LyricsState.Plain("dummy plain"))
    private val calls = mutableListOf<String>()
    private val requestHeaders = mutableListOf<Map<String, String>>()

    private fun searchClient(
        get: HttpResponse = plainResponse,
        search: () -> HttpResponse = { HttpResponse(200, "[]", null) }
    ) = LrclibClient(HttpTransport { u, h ->
        calls.add(u)
        requestHeaders.add(h)
        if (u.startsWith("https://lrclib.net/api/search?")) search() else get
    }, "DummyLauncher/1", sleep = {}, jitter = { 0 })

    private fun entry(text: String, duration: String = "302", album: String = "other") =
        """{"syncedLyrics":"[00:01.00]$text","duration":$duration,"albumName":"$album"}"""

    private fun synced(text: String) = FetchResult(LyricsState.Synced(LrcParser.parse("[00:01.00]$text")))

    private fun searchResponse(body: String) = HttpResponse(200, body, null)

    @Test fun searchUsedWhenGetIsPlain() {
        assertEquals(synced("dummy"), searchClient { searchResponse("[${entry("dummy")}]") }.fetch(searchQuery))
        assertEquals(2, calls.size)
    }

    @Test fun searchUsedWhenGetIsNotFound() {
        assertEquals(synced("dummy"), searchClient(HttpResponse(404, "", null)) {
            searchResponse("[${entry("dummy")}]")
        }.fetch(searchQuery))
        assertEquals(2, calls.size)
    }

    @Test fun searchNotUsedWhenGetIsSynced() {
        assertEquals(synced("dummy"), searchClient(searchResponse(entry("dummy"))).fetch(searchQuery))
        assertEquals(1, calls.size)
        assertTrue(calls.single().startsWith("https://lrclib.net/api/get?"))
    }

    @Test fun searchNotUsedWhenInstrumental() {
        assertEquals(FetchResult(LyricsState.Missing), searchClient(searchResponse("""{"instrumental":true}""")).fetch(searchQuery))
        assertEquals(1, calls.size)
    }

    @Test fun searchNotUsedWhenOffline() {
        val offline = LrclibClient(HttpTransport { u, _ -> calls.add(u); throw IOException("dummy") }, "dummy")
        assertEquals(FetchResult(LyricsState.Offline), offline.fetch(searchQuery))
        assertEquals(1, calls.size)
    }

    @Test fun searchUsedWhenServerError() {
        assertEquals(FetchResult(LyricsState.Failed, cacheable = false), searchClient(HttpResponse(503, "", null)).fetch(searchQuery))
        assertEquals(4, calls.size)
        assertEquals(3, calls.count { it.contains("/get?") })
        assertTrue(calls.last().contains("/search?"))
    }

    @Test fun searchUrl() {
        searchClient().fetch(searchQuery)
        assertEquals("https://lrclib.net/api/search?track_name=Dummy%20Song%261&artist_name=Dummy%20Artist", calls[1])
        assertEquals(requestHeaders[0], requestHeaders[1])
    }

    @Test fun searchSkipsDurationMismatch() {
        assertEquals(plainResult, searchClient {
            searchResponse("[${entry("a", "315")},${entry("b", "233")}]")
        }.fetch(searchQuery))
    }

    @Test fun searchPrefersSameAlbum() {
        assertEquals(synced("b"), searchClient {
            searchResponse("[${entry("a")},${entry("b", "303", "album a")}]")
        }.fetch(searchQuery))
    }

    @Test fun searchPrefersCloserDuration() {
        assertEquals(synced("b"), searchClient {
            searchResponse("[${entry("a", "304")},${entry("b")}]")
        }.fetch(searchQuery))
    }

    @Test fun searchIgnoresEntriesWithoutDuration() {
        assertEquals(plainResult, searchClient {
            searchResponse("""[{"syncedLyrics":"[00:01.00]dummy"}]""")
        }.fetch(searchQuery))
    }

    @Test fun searchWithoutQueryDurationAcceptsAny() {
        val noDuration = LyricsQuery("dummy", "artist", null, null)
        assertEquals(synced("a"), searchClient {
            searchResponse("[${entry("a", "315")},${entry("b", "233")}]")
        }.fetch(noDuration))
        assertEquals(synced("b"), searchClient {
            searchResponse("[${entry("a", "315")},${entry("b", "233", "ALBUM A")}]")
        }.fetch(LyricsQuery("dummy", "artist", "album a", null)))
    }

    @Test fun searchFailureFallsBackToGet() {
        assertEquals(plainResult.copy(cacheable = false), searchClient { throw IOException("dummy") }.fetch(searchQuery))
        for (response in listOf(HttpResponse(500, "", null), searchResponse("{}"), searchResponse("{broken"))) {
            assertEquals(plainResult.copy(cacheable = false), searchClient { response }.fetch(searchQuery))
        }
    }

    @Test fun searchTooManyRequestsCarriesRetryAfter() {
        for (seconds in listOf(null, 0L, -1L, 30L)) {
            assertEquals(plainResult.copy(retryAfterSec = if (seconds == 30L) 30L else 60L, cacheable = false),
                searchClient { HttpResponse(429, "", seconds) }.fetch(searchQuery))
        }
    }

    @Test fun searchSkipsInvalidCandidatesAndKeepsArrayOrder() {
        val invalid = """null,42,{"instrumental":true,"duration":302,"syncedLyrics":"[00:01.00]bad"},{"duration":302,"syncedLyrics":"no timestamp"},{"duration":"302","syncedLyrics":"[00:01.00]bad"}"""
        assertEquals(synced("a"), searchClient {
            searchResponse("[$invalid,${entry("a", "304")},${entry("b", "304")}]")
        }.fetch(searchQuery))
    }

    private val waits = mutableListOf<Long>()
    private fun scripted(vararg responses: HttpResponse) : LrclibClient {
        var index = 0
        return LrclibClient(HttpTransport { u, _ -> calls.add(u); responses[index++] }, "dummy", waits::add, { 17 })
    }
    private val unavailable = HttpResponse(503, "", null)
    private val notFound = HttpResponse(404, "", null)

    @Test fun retries503ThenSucceeds() {
        assertEquals(synced("dummy"), scripted(unavailable, unavailable, searchResponse(entry("dummy"))).fetch(query))
        assertEquals(3, calls.size)
        assertEquals(1, calls.distinct().size)
        assertEquals(listOf(1017L, 2017L), waits)
    }

    @Test fun retryAfterUsedFor503() {
        assertEquals(synced("dummy"), scripted(unavailable.copy(retryAfterSec = 3), searchResponse(entry("dummy"))).fetch(query))
        assertEquals(listOf(3000L), waits)
    }

    @Test fun getServerErrorTriesSearch() {
        assertEquals(synced("dummy"), scripted(unavailable, unavailable, unavailable, searchResponse("[${entry("dummy")}]")).fetch(searchQuery))
        assertEquals(4, calls.size)
        assertTrue(calls.last().contains("/search?"))
    }

    @Test fun getServerErrorAndSearchFails() {
        assertEquals(FetchResult(LyricsState.Failed, cacheable = false),
            scripted(unavailable, unavailable, unavailable, unavailable, unavailable, unavailable).fetch(query))
        assertEquals(6, calls.size)
        assertEquals(2, calls.distinct().size)
        assertEquals(listOf(1017L, 2017L, 300L, 1017L, 2017L), waits)
    }

    @Test fun searchFailureAfterNotFoundIsFailedNotCached() {
        assertEquals(FetchResult(LyricsState.Failed, cacheable = false),
            searchClient(notFound) { throw IOException("dummy") }.fetch(query))
        assertEquals(2, calls.size)
    }

    @Test fun searchFailureAfterPlainKeepsPlainNotCached() {
        assertEquals(plainResult.copy(cacheable = false), scripted(plainResponse, unavailable, unavailable, unavailable).fetch(query))
        assertEquals(4, calls.size)
    }

    @Test fun plainCandidateUsedWhenNoSynced() {
        assertEquals(FetchResult(LyricsState.Plain("dummy")), scripted(notFound,
            searchResponse("""[{"plainLyrics":" dummy ","duration":302}]""")).fetch(searchQuery))
    }

    @Test fun noCandidatesIsMissingCached() {
        assertEquals(FetchResult(LyricsState.Missing), scripted(notFound, searchResponse("[]")).fetch(query))
    }

    @Test fun waitsBetweenGetAndSearch() {
        val client = LrclibClient(HttpTransport { u, _ ->
            if (u.contains("/search?")) {
                assertEquals(listOf(300L), waits)
                searchResponse("[]")
            } else notFound
        }, "dummy", waits::add)
        client.fetch(query)
        assertEquals(listOf(300L), waits)
    }

    @Test fun ioExceptionNotRetried() {
        var count = 0
        val client = LrclibClient(HttpTransport { _, _ -> count++; throw IOException("dummy") }, "dummy", waits::add)
        assertEquals(FetchResult(LyricsState.Offline), client.fetch(query))
        assertEquals(1, count)
        assertTrue(waits.isEmpty())
    }

    @Test fun logsRequests() {
        DiagLog.clearForTest()
        scripted(notFound, searchResponse("[]")).fetch(query)
        assertTrue(DiagLog.dump().any { it.contains("get try=1 code=404") })
        assertTrue(DiagLog.dump().any { it.contains("result Missing cacheable=true") })
    }
}
