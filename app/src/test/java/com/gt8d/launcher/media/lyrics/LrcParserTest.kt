package com.gt8d.launcher.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesThreeTimeFormats() {
        assertEquals(listOf(LyricLine(62000, "c"), LyricLine(62340, "a"), LyricLine(62345, "b")),
            LrcParser.parse("[01:02.34]a\n[01:02.345]b\n[01:02]c"))
    }

    @Test fun dropsOutOfRangeTime() {
        assertEquals(emptyList<LyricLine>(), LrcParser.parse("[01:75.00]x\n[00:10][01:60]x"))
    }

    @Test fun multipleTimestamps() {
        assertEquals(listOf(LyricLine(10000, "x"), LyricLine(20000, "x")),
            LrcParser.parse("[00:10.00][00:20.00]x"))
    }

    @Test fun dropsTagLines() {
        assertEquals(emptyList<LyricLine>(), LrcParser.parse("[ar:foo]\n[ti:bar]\nダミー文\n"))
    }

    @Test fun appliesOffset() {
        for ((offset, expected) in listOf("+500" to 9500L, "-500" to 10500L, "+20000" to 0L, "500" to 9500L)) {
            assertEquals(listOf(LyricLine(expected, "x")), LrcParser.parse("[offset:$offset]\n[00:10.00]x"))
        }
        assertEquals(listOf(LyricLine(9500, "x")),
            LrcParser.parse("[00:10.00]x\n[offset:+500]\n[offset:+9000]"))
    }

    @Test fun emptyTextBecomesNote() {
        assertEquals(listOf(LyricLine(5000, "♪")), LrcParser.parse("[00:05.00]  "))
    }

    @Test fun sameTimeKeepsOrder() {
        assertEquals(listOf(LyricLine(5000, "a"), LyricLine(5000, "b")),
            LrcParser.parse("[00:05.00]a\n[00:05.00]b"))
    }

    @Test fun sortsByTime() {
        assertEquals(listOf(LyricLine(10000, "a"), LyricLine(20000, "b")),
            LrcParser.parse("[00:20.00] b \r\n[00:10.00] a "))
    }

    @Test fun currentIndexBoundaries() {
        val lines = listOf(1000L, 2000L, 3000L).map { LyricLine(it, "x") }
        for ((position, index) in listOf(999L to -1, 1000L to 0, 2500L to 1, 99999L to 2)) {
            assertEquals(index, LrcParser.currentIndex(lines, position))
        }
        assertEquals(-1, LrcParser.currentIndex(emptyList(), 1000))
        assertEquals(1, LrcParser.currentIndex(listOf(LyricLine(1000, "a"), LyricLine(1000, "b")), 1000))
    }
}
