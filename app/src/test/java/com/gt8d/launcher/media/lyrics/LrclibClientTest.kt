package com.gt8d.launcher.media.lyrics

import java.io.IOException
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
        url = u
        headers = h
        response
    }, "DummyLauncher/1")

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
        assertEquals(FetchResult(LyricsState.Failed), client.fetch(query))
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
}
