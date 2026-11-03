package com.gt8d.launcher.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsQueryTest {
    @Test fun blankTitleOrArtistIsNull() {
        assertNull(LyricsQuery.of("  ", "b", null, null))
        assertNull(LyricsQuery.of("a", null, null, null))
        assertNull(LyricsQuery.of(null, "b", null, null))
        assertNull(LyricsQuery.of("a", "  ", null, null))
    }

    @Test fun trimsFields() {
        assertEquals(LyricsQuery("a", "b", null, null), LyricsQuery.of(" a ", " b ", " ", null))
        assertEquals("c", LyricsQuery.of("a", "b", " c ", null)?.album)
    }

    @Test fun durationRange() {
        for ((ms, seconds) in listOf(0L to null, 999L to 1L, 3600400L to 3600L, 3601000L to null,
            null to null, 499L to null, 500L to 1L, -1000L to null, Long.MAX_VALUE to null)) {
            assertEquals(seconds, LyricsQuery.of("a", "b", null, ms)?.durationSec)
        }
    }

    @Test fun cacheKeyNormalizes() {
        val first = LyricsQuery(" A ", " B ", " C ", 10)
        val second = LyricsQuery("a", "b", "c", 10)
        assertEquals(first.cacheKey, second.cacheKey)
        assertNotEquals(first.cacheKey, second.copy(album = "d").cacheKey)
        assertEquals(64, first.cacheKey.length)
        assertTrue(first.cacheKey.matches(Regex("[0-9a-f]{64}")))
        assertEquals(LyricsQuery("a", "b", null, null).cacheKey, LyricsQuery("a", "b", "", null).cacheKey)
    }
}
