package com.gt8d.launcher.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesWordTimestamps() {
        assertEquals(listOf(LyricLine(10000, "あいうえ", listOf(LyricWord(10000, 0), LyricWord(11000, 2)))),
            LrcParser.parse("[00:10.00]<00:10.00>あい<00:11.00>うえ"))
    }

    @Test fun wordTimestampsWithOffset() {
        assertEquals(listOf(LyricLine(9500, "あいうえ", listOf(LyricWord(9500, 0), LyricWord(10500, 2)))),
            LrcParser.parse("[offset:+500]\n[00:10.00]<00:10.00>あい<00:11.00>うえ"))
        assertEquals(listOf(LyricWord(0, 0)),
            LrcParser.parse("[00:01]<00:01>あ\n[offset:+2000]").single().words)
    }

    @Test fun noWordTimestampsMeansEmpty() {
        assertEquals(emptyList<LyricWord>(), LrcParser.parse("[00:10]架空の朝").single().words)
    }

    @Test fun wordFormatsTrimAndInvalidTimes() {
        assertEquals(LyricLine(10000, "あいう", listOf(LyricWord(10000, 0), LyricWord(11345, 1))),
            LrcParser.parse("[00:10]  <00:10>あ<00:11.345>い<00:75>う<999999999999999999999:00>  ").single())
    }

    @Test fun sortsWordTimesAndAdjustsOverflow() {
        assertEquals(listOf(LyricWord(10000, 1), LyricWord(12000, 0)),
            LrcParser.parse("[00:10]<00:12>あ<00:10>い").single().words)
        assertEquals(Long.MAX_VALUE,
            LrcParser.parse("[offset:-9223372036854775808]\n[00:10]<00:10>あ").single().words.single().timeMs)
    }

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
