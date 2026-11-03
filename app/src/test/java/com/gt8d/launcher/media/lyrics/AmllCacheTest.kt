package com.gt8d.launcher.media.lyrics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmllCacheTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var now = 1000L
    private fun request(title: String = "架空", id: String? = "123") =
        LyricsRequest(LyricsQuery(title, "歌手", null, null), LyricsIdentity(id))
    private fun success(text: String = "あ😀 B") = AmllResult(
        LyricsState.Synced(listOf(LyricLine(1000, text, listOf(LyricWord(1000, 0), LyricWord(1500, 4))))),
        AmllReason.DIRECT_ID, AmllTarget("am-lyrics", "123"), cacheable = true)
    private val missing = AmllResult(LyricsState.Missing, AmllReason.NOT_FOUND, cacheable = true)

    @Test fun successRoundTripsWordsAndSource() {
        val dir = temporaryFolder.newFolder()
        AmllCache(dir, { now }).put(request(), "g1", success())
        now += 100 * 86400000L
        assertEquals(success(), AmllCache(dir, { now }).get(request(), "g2"))
        val body = dir.listFiles()!!.single().readText()
        assertTrue(body.contains("\"v\":1"))
        assertFalse(body.contains("<tt"))
    }

    @Test fun negativeExpiresAt24Hours() {
        val dir = temporaryFolder.newFolder()
        val cache = AmllCache(dir, { now })
        cache.put(request(), "g1", missing)
        now += 86400000L - 1
        assertEquals(missing, cache.get(request(), "g1"))
        assertEquals(missing, AmllCache(dir, { now }).get(request(), "g1"))
        now++
        assertNull(cache.get(request(), "g1"))
        assertNull(AmllCache(dir, { now }).get(request(), "g1"))
    }

    @Test fun generationOrIdentityInvalidatesNegative() {
        val cache = AmllCache(temporaryFolder.newFolder(), { now })
        cache.put(request(), "g1", missing)
        assertNull(cache.get(request(), "g2"))
        assertNull(cache.get(request(id = "456"), "g1"))
        assertEquals(missing, cache.get(request(), "g1"))
    }

    @Test fun noNegativeOnNetworkOrMalformedData() {
        val dir = temporaryFolder.newFolder()
        val cache = AmllCache(dir, { now })
        for (reason in listOf(AmllReason.NETWORK, AmllReason.HTTP, AmllReason.INVALID, AmllReason.UNAVAILABLE, AmllReason.WAIT)) {
            cache.put(request(), null, AmllResult(LyricsState.Failed, reason))
            cache.put(request(), null, AmllResult(LyricsState.Missing, reason, cacheable = true))
            assertNull(cache.get(request(), null))
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun prunesOldestAt300And32MiB() {
        val dir = temporaryFolder.newFolder()
        val cache = AmllCache(dir, { now })
        for (i in 1..301) { now++; cache.put(request("$i"), null, missing) }
        assertNull(cache.get(request("1"), null))
        for (i in 2..301) assertEquals(missing, cache.get(request("$i"), null))
        assertEquals(300, dir.listFiles()!!.size)

        val largeDir = temporaryFolder.newFolder()
        val largeCache = AmllCache(largeDir, { now })
        val large = success("A".repeat(900000))
        for (i in 1..38) { now++; largeCache.put(request("$i"), null, large) }
        assertTrue(largeDir.listFiles()!!.sumOf { it.length() } <= 32 * 1024 * 1024L)
        assertNull(largeCache.get(request("1"), null))
        assertEquals(large, largeCache.get(request("38"), null))
        val sizes = largeDir.listFiles()!!.map { it.length() }
        assertTrue(sizes.sum() + sizes.first() > 32 * 1024 * 1024L)
    }

    @Test fun entryOver1MiBNotSaved() {
        val dir = temporaryFolder.newFolder()
        val cache = AmllCache(dir, { now })
        cache.put(request(), null, success("A".repeat(1024 * 1024)))
        assertNull(cache.get(request(), null))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun corruptEntryIsMiss() {
        val dir = temporaryFolder.newFolder()
        val cache = AmllCache(dir, { now })
        val file = File(dir, AmllCache.key(request()) + ".json")
        for (body in listOf("{", "[]", "{}", """{"v":99}""")) {
            file.writeText(body)
            assertNull(cache.get(request(), null))
        }
        cache.put(request(), null, success())
        file.writeText(file.readText().replace("[1500,4]", "[1500,-1]"))
        assertNull(cache.get(request(), null))
    }

    @Test fun writeFailureDoesNotLoseFetchedLyrics() {
        val file = temporaryFolder.newFile()
        val result = success()
        AmllCache(file, { now }).put(request(), null, result)
        assertEquals(success(), result)
        assertNull(AmllCache(file, { now }).get(request(), null))
        assertTrue(file.isFile)
    }

    @Test fun cacheKeySeparatesIdentity() {
        val cache = AmllCache(temporaryFolder.newFolder(), { now })
        val a = request()
        val b = request(id = "456")
        val c = a.copy(identity = a.identity.copy(isrc = "JPABC1234567"))
        assertEquals(3, setOf(AmllCache.key(a), AmllCache.key(b), AmllCache.key(c)).size)
        cache.put(a, null, success())
        assertNull(cache.get(b, null)); assertNull(cache.get(c, null))
        assertEquals(success(), cache.get(a, null))
    }

    @Test fun legacyV3IsUntouched() {
        val dir = temporaryFolder.newFolder()
        val legacy = LyricsCache(dir, { now })
        legacy.put(request().query, LyricsState.Plain("架空"))
        val file = File(dir, request().query.cacheKey + ".json")
        val before = file.readBytes()
        val cache = AmllCache(File(dir, "amll"), { now })
        for (i in 1..301) { now++; cache.put(request("$i"), null, missing) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(LyricsState.Plain("架空"), legacy.get(request().query))
    }
}
