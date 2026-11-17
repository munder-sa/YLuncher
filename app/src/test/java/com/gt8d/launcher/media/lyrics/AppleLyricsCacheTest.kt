package com.gt8d.launcher.media.lyrics

import org.junit.Assert.*
import org.junit.Test

class AppleLyricsCacheTest {
    private val request = LyricsRequest(LyricsQuery("Synthetic song", "Synthetic artist", "Album", 120), LyricsIdentity("123"))
    private val connection = AppleMusicConnection(AppleMusicStatus.CONNECTED, 0, "jp")
    private val key = requireNotNull(AppleLyricsCache.key(request, connection))
    private fun success(text: String = "Synthetic lyric") = AppleLyricsResult(
        LyricsState.Synced(listOf(LyricLine(1000, text, listOf(LyricWord(1000, 0))))), AppleLyricsReason.OK,
    )

    @Test fun keysSeparateIdentityQueryStorefrontAndGeneration() {
        assertEquals(AppleLyricsCacheKey(0, "jp", "123", request.query.cacheKey, 1), key)
        val variants = listOf(
            AppleLyricsCache.key(request.copy(identity = LyricsIdentity("124")), connection),
            AppleLyricsCache.key(request.copy(query = request.query.copy(title = "Other")), connection),
            AppleLyricsCache.key(request.copy(query = request.query.copy(artist = "Other")), connection),
            AppleLyricsCache.key(request.copy(query = request.query.copy(album = "Other")), connection),
            AppleLyricsCache.key(request.copy(query = request.query.copy(durationSec = 121)), connection),
            AppleLyricsCache.key(request, connection.copy(storefront = "us")),
            AppleLyricsCache.key(request, connection.copy(generation = 1)), key.copy(parserVersion = 2),
        )
        assertEquals(variants.size + 1, (variants + key).toSet().size)
        assertTrue(variants.all { it != null })
        for (id in listOf(null, "", "0", "000", "１２３", "١٢٣", "-1", "+1", "1 2", "123\n", "1".repeat(21))) {
            assertNull("id=$id", AppleLyricsCache.key(request.copy(identity = LyricsIdentity(id)), connection))
        }
        for (id in listOf("1", "0001", "9".repeat(20))) {
            assertNotNull(AppleLyricsCache.key(request.copy(identity = LyricsIdentity(id)), connection))
        }
        for (storefront in listOf(null, "", "JP", "j", "jpn", "j1", " jp", "jp\n", "日本")) {
            assertNull(AppleLyricsCache.key(request, connection.copy(storefront = storefront)))
        }
        for (status in AppleMusicStatus.entries.filter { it != AppleMusicStatus.CONNECTED }) {
            assertNull(AppleLyricsCache.key(request, connection.copy(status = status)))
        }
        assertNull(AppleLyricsCache.key(request, connection.copy(generation = -1)))
        for (blank in listOf("", " \t")) {
            assertNull(AppleLyricsCache.key(request.copy(query = request.query.copy(title = blank)), connection))
            assertNull(AppleLyricsCache.key(request.copy(query = request.query.copy(artist = blank)), connection))
        }
        assertEquals(key, AppleLyricsCache.key(request, connection.copy(reason = AppleLyricsReason.WAIT, retryAfterSec = 60)))
        assertEquals(key, AppleLyricsCache.key(request.copy(identity = request.identity.copy(isrc = "Ignored")), connection))
    }

    @Test fun positiveAndNegativeEntriesExpireAtTheirBoundaries() {
        val cases = listOf(
            success() to 1_800_000L,
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND) to 60_000L,
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.UNSUPPORTED) to 60_000L,
        )
        for ((result, ttl) in cases) {
            var now = 500L
            val cache = AppleLyricsCache { now }
            cache.put(key, result)
            now += ttl - 1
            assertEquals(result.state, cache.get(key)!!.state)
            now++
            assertNull(cache.get(key)) // A read just before expiry did not extend it.
            now = 500
            assertNull(cache.get(key))
            cache.put(key, result)
            now = 499
            assertNull(cache.get(key)) // Reset before savedAt also invalidates the entry.
            now = 500
            assertNull(cache.get(key))
        }
    }

    @Test fun lineSyncedUnsupportedIsKeptThirtyMinutesAndBounded() {
        fun lineResult(lines: List<LyricLine>?) = AppleLyricsResult(
            LyricsState.Missing, AppleLyricsReason.UNSUPPORTED, lineSynced = lines, detail = "no-timed-words",
        )
        var now = 500L
        val cache = AppleLyricsCache { now }
        val words = mutableListOf<LyricWord>()
        val line = LyricLine(1000, "PRIVATE-SYNTHETIC-LINE", words)
        val lines = mutableListOf(line, LyricLine(2000, "Second synthetic line"))
        val expected = lines.map { it.copy(words = it.words.toList()) }
        cache.put(key, lineResult(lines))
        lines.clear()
        words.add(LyricWord(1000, 0))
        val stored = requireNotNull(cache.get(key))
        assertEquals(LyricsState.Missing, stored.state)
        assertEquals(AppleLyricsReason.UNSUPPORTED, stored.reason)
        assertEquals(expected, stored.lineSynced)
        assertEquals("no-timed-words", stored.detail)
        assertNotSame(line, stored.lineSynced!!.first())
        assertFalse(stored.toString().contains("PRIVATE-SYNTHETIC-LINE"))
        assertThrows(UnsupportedOperationException::class.java) {
            (stored.lineSynced as MutableList<LyricLine>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (stored.lineSynced.first().words as MutableList<LyricWord>).add(LyricWord(1000, 0))
        }
        now += 1_800_000L - 1
        assertEquals(expected, cache.get(key)!!.lineSynced)
        now++
        assertNull(cache.get(key))

        for (result in listOf(lineResult(null), lineResult(emptyList()),
            AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND,
                lineSynced = expected, detail = "no-timed-words"))) {
            val negative = AppleLyricsCache { now }
            negative.put(key, result)
            now += 59_999L
            val saved = requireNotNull(negative.get(key))
            assertEquals(result.state, saved.state)
            assertEquals(result.reason, saved.reason)
            assertNull(saved.lineSynced)
            assertEquals(result.detail, saved.detail)
            now++
            assertNull(negative.get(key))
        }

        val limit = 2 * 1024 * 1024
        val fullText = "あ".repeat(limit / 3) + "aa"
        assertEquals(limit, fullText.toByteArray(Charsets.UTF_8).size)
        val bounded = AppleLyricsCache { now }
        bounded.put(key, lineResult(listOf(LyricLine(1000, fullText + "x"))))
        assertNull(bounded.get(key))
        val fullLines = listOf(LyricLine(1000, fullText.dropLast(2)), LyricLine(2000, "aa"))
        bounded.put(key, lineResult(fullLines))
        assertEquals(fullLines, bounded.get(key)!!.lineSynced)
        val otherKey = key.copy(appleMusicId = "124")
        bounded.put(otherKey, success("b"))
        assertNull(bounded.get(key))
        assertNotNull(bounded.get(otherKey))
        bounded.put(key, AppleLyricsResult(success("b").state, AppleLyricsReason.OK, lineSynced = fullLines))
        assertNull(bounded.get(key)) // State text and line text share the same byte bound.
        assertNotNull(bounded.get(otherKey))
    }

    @Test fun transientFailuresRemainFailuresAndRespectCooldown() {
        val reasons = listOf(AppleLyricsReason.NETWORK, AppleLyricsReason.HTTP, AppleLyricsReason.WAIT,
            AppleLyricsReason.TIMEOUT, AppleLyricsReason.TOKEN_UNAVAILABLE, AppleLyricsReason.INVALID)
        for (state in listOf(LyricsState.Failed, LyricsState.Offline)) {
            for (reason in reasons) for (retry in listOf(null, -1L, 0L, 1L, 90L, Long.MAX_VALUE)) {
                var now = 700L
                val cache = AppleLyricsCache { now }
                cache.put(key, AppleLyricsResult(state, reason, retry))
                val deadline = if (retry == Long.MAX_VALUE) Long.MAX_VALUE else 700 + maxOf(60L, retry ?: 0L) * 1000
                now = deadline - 1
                val stored = requireNotNull(cache.get(key))
                assertEquals(state, stored.state)
                assertEquals(reason, stored.reason)
                assertEquals(retry, stored.retryAfterSec)
                now++
                assertNull(cache.get(key))
            }
        }
        val states = listOf(LyricsState.Off, LyricsState.Loading, LyricsState.Plain("private"),
            LyricsState.Synced(emptyList()), LyricsState.Synced(listOf(LyricLine(0, "wordless"))))
        for (state in states) for (reason in AppleLyricsReason.entries) {
            val cache = AppleLyricsCache { 0 }
            cache.put(key, AppleLyricsResult(state, reason, Long.MAX_VALUE))
            assertNull(cache.get(key))
        }
        for (state in listOf(success().state, LyricsState.Failed, LyricsState.Offline, LyricsState.Missing)) {
            for (reason in listOf(AppleLyricsReason.AUTH_REQUIRED, AppleLyricsReason.NOT_APPLICABLE)) {
                val cache = AppleLyricsCache { 0 }
                cache.put(key, AppleLyricsResult(state, reason))
                assertNull(cache.get(key))
            }
        }
    }

    @Test fun boundsEntryCountAndUtf8TextBytes() {
        var now = 0L
        val lru = AppleLyricsCache { now }
        val keys = (1..9).map { key.copy(appleMusicId = "$it") }
        keys.take(8).forEachIndexed { i, k ->
            lru.put(k, when (i % 3) {
                0 -> success()
                1 -> AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND)
                else -> AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.NETWORK)
            })
        }
        assertNotNull(lru.get(keys[0]))
        lru.put(keys[8], success())
        assertNull(lru.get(keys[1]))
        (keys - keys[1]).forEach { assertNotNull(lru.get(it)) }

        val limit = 2 * 1024 * 1024
        for (text in listOf("a".repeat(limit), "あ".repeat(limit / 3) + "aa")) {
            assertEquals(limit, text.toByteArray(Charsets.UTF_8).size)
            val cache = AppleLyricsCache { now }
            cache.put(keys[0], success(text))
            assertNotNull(cache.get(keys[0]))
            cache.put(keys[1], success("b"))
            assertNull(cache.get(keys[0]))
            assertNotNull(cache.get(keys[1]))
            cache.put(keys[2], success(text + "x"))
            assertNull(cache.get(keys[2]))
            assertNotNull(cache.get(keys[1]))
        }
        val multiLine = AppleLyricsCache { now }
        multiLine.put(keys[0], AppleLyricsResult(LyricsState.Synced(listOf(
            LyricLine(0, "a".repeat(limit / 2), listOf(LyricWord(0, 0))),
            LyricLine(1000, "a".repeat(limit / 2)),
        )), AppleLyricsReason.OK))
        assertNotNull(multiLine.get(keys[0]))
        multiLine.put(keys[1], success("あ"))
        assertNull(multiLine.get(keys[0]))

        val expiry = AppleLyricsCache { now }
        expiry.put(keys[0], success("a".repeat(limit / 2)))
        now = 100
        expiry.put(keys[1], success("a".repeat(limit / 2)))
        assertNotNull(expiry.get(keys[0])) // Expired entry will be more recently used.
        now = 1_800_000
        expiry.put(keys[2], success("b"))
        assertNull(expiry.get(keys[0]))
        assertNotNull(expiry.get(keys[1]))
        assertNotNull(expiry.get(keys[2]))

        // Capacity includes all result types; expiry must not evict a live older LRU.
        now = 0
        val mixed = AppleLyricsCache { now }
        mixed.put(keys[0], success())
        keys.subList(1, 8).forEach { mixed.put(it, AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND)) }
        now = 60_000
        mixed.put(keys[8], success())
        assertNotNull(mixed.get(keys[0]))
        keys.subList(1, 8).forEach { assertNull(mixed.get(it)) }
    }

    @Test fun generationChangeRejectsOldReadsAndWrites() {
        val cache = AppleLyricsCache { 0 }
        val newer = key.copy(generation = 1)
        cache.put(newer, success())
        assertNull(cache.get(newer))
        cache.put(key, success())
        cache.activateGeneration(0)
        assertNotNull(cache.get(key))
        cache.activateGeneration(1)
        assertNull(cache.get(key))
        cache.put(key, success())
        cache.put(newer, success())
        cache.activateGeneration(0)
        cache.activateGeneration(-1)
        assertNull(cache.get(key))
        assertNotNull(cache.get(newer))
        cache.activateGeneration(1)
        assertNotNull(cache.get(newer))
        cache.activateGeneration(2)
        assertNull(cache.get(newer))
    }

    @Test fun copiesMutableInputsWithoutPersistingSecrets() {
        val cache = AppleLyricsCache { 0 }
        val words = mutableListOf(LyricWord(1000, 0), LyricWord(1500, 3))
        val line = LyricLine(1000, "PRIVATE-SYNTHETIC-LYRIC", words)
        val lines = mutableListOf(line, LyricLine(2000, "Second synthetic line"))
        val expected = lines.map { it.copy(words = it.words.toList()) }
        cache.put(key, AppleLyricsResult(LyricsState.Synced(lines), AppleLyricsReason.OK))
        words.clear()
        lines.clear()
        val stored = cache.get(key)!!
        assertEquals(LyricsState.Synced(expected), stored.state)
        assertNotSame(line, (stored.state as LyricsState.Synced).lines.first())
        assertFalse(cache.toString().contains("PRIVATE-SYNTHETIC-LYRIC"))
        assertFalse(stored.toString().contains("PRIVATE-SYNTHETIC-LYRIC"))
        assertFalse(key.toString().contains(request.query.title))
        assertNull(AppleLyricsCache { 0 }.get(key))
    }
}
