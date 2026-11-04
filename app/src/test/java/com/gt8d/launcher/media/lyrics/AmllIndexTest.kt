package com.gt8d.launcher.media.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AmllIndexTest {
    private fun row(title: String = "Song", artist: String = "Artist", album: String = "Album", isrc: String = "JPABC1234567", file: String = "one.ttml") =
        """{"metadata":[["musicName",["$title"]],["artists",["$artist"]],["album",["$album"]],["isrc",["$isrc"]]],"rawLyricFile":"$file"}"""
    private suspend fun match(rows: List<String>, title: String = "Song", artist: String = "Artist", album: String? = null, isrc: String? = null): AmllMatch =
        AmllIndex.resolve(rows.map { AmllIndex.parseRaw(it).encode() }.iterator(), LyricsQuery(title, artist, album, null), isrc)

    @Test fun normalizesNfkcCaseAndWhitespace() = runBlocking {
        assertTrue(match(listOf(row("Ａ  Song", " ARTIST ")), "a\u3000song", "artist") is AmllMatch.Unique)
    }
    @Test fun featRequiresSameContributor() = runBlocking {
        val rows = listOf(row("Song (feat. Guest)"))
        assertEquals(AmllMatch.NotFound, match(rows))
        assertEquals(AmllMatch.NotFound, match(rows, "Song (feat. Other)"))
        assertTrue(match(rows, "Song (feat. Guest)") is AmllMatch.Unique)
        assertTrue(match(rows, "Song(feat. Guest)") is AmllMatch.Unique)
    }
    @Test fun doesNotSplitArtistOrStripVersion() = runBlocking {
        assertEquals(AmllMatch.NotFound, match(listOf(row()), artist = "Artist & Other"))
        assertEquals(AmllMatch.NotFound, match(listOf(row("Song (live)"))))
        assertEquals(AmllMatch.NotFound, match(listOf(row("Song (remix)"))))
    }
    @Test fun albumAndIsrcConflictsReject() = runBlocking {
        assertEquals(AmllMatch.NotFound, match(listOf(row()), album = "Other"))
        assertEquals(AmllMatch.NotFound, match(listOf(row()), isrc = "JPXYZ1234567"))
    }
    @Test fun isrcWinsButTitleConflictRejects() = runBlocking {
        assertTrue(match(listOf(row(), row(file = "two.ttml", isrc = "JPXYZ1234567")), isrc = "JPABC1234567") is AmllMatch.Unique)
        assertEquals(AmllMatch.NotFound, match(listOf(row()), title = "Other", isrc = "JPABC1234567"))
        assertEquals(AmllMatch.NotFound, match(listOf(row()), artist = "Other", isrc = "JPABC1234567"))
    }
    @Test fun uniqueWithoutAlbumOnly() = runBlocking {
        assertTrue(match(listOf(row())) is AmllMatch.Unique)
        assertEquals(AmllMatch.Ambiguous, match(listOf(row(), row(file = "two.ttml"))))
    }
    @Test fun duplicateTargetCountsOnce() = runBlocking { assertTrue(match(listOf(row(), row())) is AmllMatch.Unique) }
    @Test fun unknownRevisionNeverUsesLastRow() = runBlocking {
        val rows = listOf(row(file = "1.ttml"), row(file = "999.ttml"))
        assertEquals(AmllMatch.Ambiguous, match(rows))
        assertEquals(AmllMatch.Ambiguous, match(rows.reversed()))
    }
    @Test fun onlyCurrentCandidatesRetained() = runBlocking {
        var count = 0
        val iterator = generateSequence { if (count++ < 20000) AmllIndex.parseRaw(row(title = "Other$count")).encode() else null }.iterator()
        assertEquals(AmllMatch.NotFound, AmllIndex.resolve(iterator, LyricsQuery("Song", "Artist", null, null), null))
        assertEquals(20001, count)
    }
    @Test fun rejectsUnsafeTarget() {
        for (target in listOf(AmllTarget("raw-lyrics", "../one.ttml"), AmllTarget("raw-lyrics", "https://host/a.ttml"), AmllTarget("other", "one.ttml"), AmllTarget("raw-lyrics", "one"))) assertFalse(target.safe)
        assertTrue(AmllTarget("raw-lyrics", "a_1-2.3.ttml").safe)
    }
    @Test fun realSampleParsesAndKeepsAliases() = runBlocking {
        val rows = javaClass.getResourceAsStream("/ttml/raw-lyrics-index-sample.jsonl")!!.bufferedReader().use { it.readLines() }
        val normalized = rows.map { AmllIndex.parseRaw(it).encode() }
        assertTrue(AmllIndex.resolve(normalized.iterator(), LyricsQuery("Lemon", "米津玄師", "レモン", null), null) is AmllMatch.Unique)
    }
}
