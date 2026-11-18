package com.gt8d.launcher.media.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtmlParserTest {
    @Test fun parsesRealStructureFixture() = runBlocking {
        val source = requireNotNull(javaClass.getResource("/ttml/structure-like-real.ttml")).readText()
        val result = TtmlParser.parse(source)
        assertTrue("Expected WordSynced, got $result", result is TtmlParseResult.WordSynced)
        val lines = (result as TtmlParseResult.WordSynced).lines
        assertEquals(54, lines.size)
        assertTrue(lines.all { it.words.isNotEmpty() })
        assertEquals(1372L, lines.first().timeMs)
        assertEquals(238431L, lines.last().timeMs)
    }

    @Test fun headSubtreeIsIgnored() = runBlocking {
        val source = xml(p()).replace("<body>",
            """<head><unknown m:role="custom"><p begin="bad"><span begin="bad">metadata</span></p></unknown></head><body>""")
        assertEquals(parse(p()), TtmlParser.parse(source))
    }

    @Test fun timedDivAcceptsAbsoluteTimesAndRejectsContradictions() {
        assertEquals(listOf(LyricLine(62300, "A", listOf(LyricWord(62300, 0)))),
            lines("<div begin=\"1:00.000\" end=\"1:10.000\">" +
                p("<span begin=\"1:02.300\">A</span>", "1:02.300", "1:05.000") + "</div>"))
        invalidDiagnostic(xml("<div begin=\"2\" end=\"1\">${p()}</div>"), "element-range")
        assertEquals(lines(p()), lines("<div begin=\"2\" end=\"4\">${p()}</div>"))
        assertEquals(lines(p()), lines("<div begin=\"0\" end=\"2\">${p()}</div>"))
    }

    private fun xml(body: String) = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata"><body><div>$body</div></body></tt>"""
    private fun p(content: String = "<span begin=\"1\">A</span>", begin: String = "1", end: String = "3") =
        "<p begin=\"$begin\" end=\"$end\">$content</p>"
    private fun parse(body: String, duration: Long? = null): TtmlParseResult = runBlocking { TtmlParser.parse(xml(body), duration) }
    private fun lines(body: String) = (parse(body) as TtmlParseResult.WordSynced).lines
    private fun invalid(body: String) = assertEquals(TtmlParseResult.Invalid, parse(body))
    private fun invalidDiagnostic(source: String, reason: String, duration: Long? = null,
        allowLineSynced: Boolean = false) = runBlocking {
        val diagnostics = mutableListOf<String>()
        assertEquals(TtmlParseResult.Invalid, TtmlParser.parse(source, duration, allowLineSynced, diagnostics::add))
        assertEquals(listOf("Invalid $reason"), diagnostics)
    }

    @Test fun mapsLineAndWordStarts() {
        assertEquals(LyricLine(1000, "AB", listOf(LyricWord(1000, 0), LyricWord(1500, 1))),
            lines(p("<span begin=\"1\">A</span><span begin=\"1.5\">B</span>")).single())
    }
    @Test fun utf16OffsetsPreserveWhitespace() {
        assertEquals(LyricLine(1000, "あ😀 B", listOf(LyricWord(1000, 0), LyricWord(1500, 4))),
            lines(p("<span begin=\"1\">あ😀</span> <span begin=\"1.5\">B</span>")).single())
    }
    @Test fun prefixDoesNotAffectNamespace() = runBlocking {
        val source = """<t:tt xmlns:t="http://www.w3.org/ns/ttml" xmlns:z="http://www.w3.org/ns/ttml#metadata"><t:body><t:div><t:p begin="1"><t:span z:role="x-bg">X</t:span><t:span begin="1">A</t:span></t:p></t:div></t:body></t:tt>"""
        assertEquals(TtmlParseResult.WordSynced(listOf(LyricLine(1000, "A", listOf(LyricWord(1000, 0))))), TtmlParser.parse(source, null))
    }
    @Test fun backgroundTranslationAndRomanizationExcluded() {
        val excluded = listOf("x-bg", "x-translation", "x-roman").joinToString("") { "<span m:role=\"$it\"><span begin=\"bad\">X</span></span>" }
        assertEquals(LyricLine(1000, "AB", listOf(LyricWord(1000, 0), LyricWord(2000, 1))),
            lines(p("<span begin=\"1\">A</span>$excluded<span begin=\"2\">B</span>")).single())
    }
    @Test fun agentIsNotDisplayed() {
        assertEquals("A", lines("<p begin=\"1\" m:agent=\"singer\"><span begin=\"1\">A</span></p>").single().text)
    }
    @Test fun clockAndSecondsTimes() {
        for (time in listOf("00:01:02.3", "01:02.300", "62.3", "62.300s")) {
            assertEquals(62300L, lines(p("<span begin=\"$time\">A</span>", time, "70")).single().timeMs)
        }
        assertEquals(1025L, lines(p("<span begin=\"1.025s\">A</span>")).single().words.single().timeMs)
    }
    @Test fun rejectsInvalidAndOverflowTimes() {
        for (time in listOf("00:00:60", "00:60:00", "-1", "9223372036854775808", "9223372036854776s", "1f", "1t", "NaN")) {
            invalid(p("<span begin=\"$time\">A</span>"))
        }
        assertEquals(LyricLine(1000, "A", listOf(LyricWord(1000, 0))),
            lines(p("<span begin=\"1.0001\">A</span>")).single())
    }
    @Test fun preservesExplicitSpacesAndEntities() {
        assertEquals(" A & B ", lines(p(" <span begin=\"1\">A &amp; B</span> ")).single().text)
        assertEquals("\n A\n", lines(p("<span xml:space=\"preserve\" begin=\"1\">\n A\n</span>")).single().text)
        assertEquals("A", lines(p("\n  <span begin=\"1\">A</span>\n  ")).single().text)
    }
    @Test fun nestedTimedSpanRejected() {
        assertEquals(TtmlParseResult.Unsupported, parse(p("<span begin=\"1\"><span begin=\"2\">A</span></span>")))
    }
    @Test fun wordOrderAndDuplicateTimesRejected() {
        assertEquals(LyricLine(1000, "AB", listOf(LyricWord(1000, 0), LyricWord(1000, 1))),
            lines(p("<span begin=\"1\">A</span><span begin=\"1\">B</span>")).single())
        invalidDiagnostic(xml(p("<span begin=\"1\">A</span><span begin=\"0.5\">B</span>")), "word-order")
    }
    @Test fun lineStartAndNextLineBounds() {
        assertEquals(LyricLine(900, "A", listOf(LyricWord(900, 0))),
            lines(p("<span begin=\"0.9\">A</span>")).single())
        assertEquals(listOf(LyricLine(1000, "A", listOf(LyricWord(2000, 0))), LyricLine(2000, "B")),
            lines("<p begin=\"1\"><span begin=\"2\">A</span></p>" + p("B", "2", "3")))
        assertTrue(parse(p(), 999) is TtmlParseResult.WordSynced)
        invalidDiagnostic(xml(p("<span begin=\"6\">A</span>", "6", "7")), "beyond-duration", 999)
        assertTrue(parse(p(), 1000) is TtmlParseResult.WordSynced)
        assertEquals(listOf(1000L, 3000L), lines(p("<span begin=\"3\">B</span>", "3", "4") + p()).map { it.timeMs })
    }
    @Test fun overlappingLeadLinesRejected() {
        val first = LyricLine(1000, "A", listOf(LyricWord(1000, 0)))
        assertEquals(listOf(first, LyricLine(2000, "B")), lines(p() + p("B", "2", "4")))
        assertEquals(listOf(first, LyricLine(1000, "B")), lines(p() + p("B")))
        invalidDiagnostic(xml(p() + p("B", "2", "1")), "element-range")
    }
    @Test fun endValidatedButNotStored() {
        assertEquals(lines(p(end = "2")), lines(p(end = "3")))
        assertEquals(lines(p()), lines(p(end = "1")))
        assertEquals(lines(p()), lines(p("<span begin=\"1\" end=\"1\">A</span>")))
        assertEquals(lines(p()), lines(p("<span begin=\"1\" end=\"4\">A</span>")))
        assertEquals(LyricLine(1000, "A", listOf(LyricWord(3000, 0))),
            lines(p("<span begin=\"3\">A</span>")).single())
        invalidDiagnostic(xml(p(end = "0.999")), "element-range")
        invalidDiagnostic(xml(p("<span begin=\"1\" end=\"0.999\">A</span>")), "element-range")
    }
    @Test fun noWordsIsUnsupported() = assertEquals(TtmlParseResult.Unsupported, parse(p("A")))
    @Test fun lineOnlyTtmlIsLineSyncedOnlyWhenAllowed() = runBlocking {
        val body = """<p begin="1"> A &amp; <span>B</span> </p><p begin="2.5">あ😀</p>"""
        assertEquals(TtmlParseResult.Unsupported, TtmlParser.parse(xml(body)))
        val expected = listOf(LyricLine(1000, " A & B "), LyricLine(2500, "あ😀"))
        val actual = TtmlParser.parse(xml(body), allowLineSynced = true)
        assertEquals(TtmlParseResult.LineSynced(expected), actual)
        assertTrue((actual as TtmlParseResult.LineSynced).lines.all { it.words.isEmpty() })
        val mixed = xml(p() + p("B", "3", "4"))
        val words = TtmlParser.parse(mixed, allowLineSynced = true)
        assertTrue(words is TtmlParseResult.WordSynced)
        assertEquals(TtmlParser.parse(mixed), words)
    }

    @Test fun lineSyncedStillRejectsUnsupportedStructureAndInvalidTiming() = runBlocking {
        for (body in listOf(
            """<p begin="1" m:role="custom">A</p>""",
            """<p begin="1" timeContainer="seq">A</p>""",
            "<p>A</p>",
            """<p begin="1"><span end="2">A</span></p>""",
            """<p begin="1"><br/>A</p>""",
            "", p(" "), p("<span m:role=\"x-bg\">X</span>"),
        )) {
            assertEquals(body, TtmlParseResult.Unsupported, TtmlParser.parse(xml(body), allowLineSynced = true))
        }
        for (body in listOf(
            p("A", "3", "1"), p("A", "bad", "3"),
        )) {
            assertEquals(body, TtmlParseResult.Invalid, TtmlParser.parse(xml(body), allowLineSynced = true))
        }
        assertEquals(TtmlParseResult.LineSynced(listOf(LyricLine(1000, "A"))),
            TtmlParser.parse(xml(p("A", "1", "1")), allowLineSynced = true))
        assertEquals(TtmlParseResult.LineSynced(listOf(LyricLine(1000, "A"), LyricLine(2000, "B"))),
            TtmlParser.parse(xml(p("A") + p("B", "2", "4")), allowLineSynced = true))
        assertEquals(TtmlParseResult.LineSynced(listOf(LyricLine(1000, "A"), LyricLine(1000, "B"))),
            TtmlParser.parse(xml(p("A") + p("B")), allowLineSynced = true))
        assertEquals(TtmlParseResult.LineSynced(listOf(LyricLine(1000, "A"))),
            TtmlParser.parse(xml(p("A")), 999, allowLineSynced = true))
        invalidDiagnostic(xml(p("A", "6", "7")), "beyond-duration", 999, allowLineSynced = true)
        assertEquals(TtmlParseResult.LineSynced(listOf(LyricLine(1000, "A"))),
            TtmlParser.parse(xml(p("A")), 1000, allowLineSynced = true))
    }

    @Test fun plainLineWithinWordSyncedSongHasNoWords() {
        assertEquals(LyricLine(3000, "B"), lines(p() + p("B", "3", "4"))[1])
    }

    @Test fun overlappingAndSimultaneousLinesKeepStableOrder() = runBlocking {
        val entries = listOf(Triple("B", "2", "6"), Triple("A", "1", "4"), Triple("C", "2", "3"))
        val wordBody = entries.joinToString("") { (text, begin, end) ->
            p("<span begin=\"$begin\" end=\"$end\">$text</span>", begin, end)
        }
        val expected = listOf(
            LyricLine(1000, "A", listOf(LyricWord(1000, 0))),
            LyricLine(2000, "B", listOf(LyricWord(2000, 0))),
            LyricLine(2000, "C", listOf(LyricWord(2000, 0))),
        )
        assertEquals(expected, lines(wordBody))
        assertEquals(TtmlParseResult.WordSynced(expected), TtmlParser.parse(xml(wordBody), allowLineSynced = true))
        val lineBody = entries.joinToString("") { (text, begin, end) -> p(text, begin, end) }
        assertEquals(TtmlParseResult.LineSynced(expected.map { LyricLine(it.timeMs, it.text) }),
            TtmlParser.parse(xml(lineBody), allowLineSynced = true))
    }

    @Test fun zeroLengthAndOutlyingElementsPreserveWordOffsets() {
        val space = """<span begin="00:00.000" end="00:00.000"> </span>"""
        val content = space + """<span begin="1" end="1"><span>あ😀</span></span>""" + space +
            """<span begin="1" end="2">B</span>""" + space +
            """<span begin="1.002" end="1.003">C</span>""" + space
        val body = """<div begin="1" end="1">${p(content, "1.001", "1.001")}</div>"""
        assertEquals(LyricLine(1000, " あ😀 B C ",
            listOf(LyricWord(1000, 1), LyricWord(1000, 5), LyricWord(1002, 7))), lines(body).single())
    }

    @Test fun effectiveLineStartsDriveStableSortAndDuration() {
        val body = p("<span begin=\"1.5\">B</span>", "2") +
            p("<span begin=\"1.6\">C</span>", "1.6") + p("<span begin=\"1.5\">A</span>", "1.7")
        val actual = lines(body)
        assertEquals(listOf(
            LyricLine(1500, "B", listOf(LyricWord(1500, 0))),
            LyricLine(1500, "A", listOf(LyricWord(1500, 0))),
            LyricLine(1600, "C", listOf(LyricWord(1600, 0))),
        ), actual)
        assertTrue(actual.all { line -> line.words.all { it.timeMs >= line.timeMs } })
        assertEquals(TtmlParseResult.WordSynced(listOf(LyricLine(165999, "A", listOf(LyricWord(165999, 0))))),
            parse(p("<span begin=\"165.999\">A</span>", "166.001", "167"), 161_000))
    }

    @Test fun acceptsSingleDigitHoursAndTruncatesSubMillisecondFractions() {
        for ((time, expected) in listOf("0:01:02.345" to 62345L, "1.2349" to 1234L,
            "1.234999s" to 1234L, "00:42.1234" to 42123L, "0:01:02.34567" to 62345L)) {
            assertEquals(LyricLine(expected, "A", listOf(LyricWord(expected, 0))),
                lines(p("<span begin=\"$time\" end=\"$time\">A</span>", time, time)).single())
        }
    }

    @Test fun durationAllowsFiveSecondsButRejectsAnythingLater() {
        for ((time, expected) in listOf("165.999s" to 165999L, "166.000s" to 166000L)) {
            assertEquals(TtmlParseResult.WordSynced(listOf(LyricLine(expected, "A", listOf(LyricWord(expected, 0))))),
                parse(p("<span begin=\"$time\">A</span>", time, "170"), 161_000))
        }
        invalidDiagnostic(xml(p("<span begin=\"166.001s\">A</span>", "166.001s", "170")),
            "beyond-duration", 161_000)
    }

    @Test fun durationAllowanceSaturatesAndIgnoresNegativeValues() {
        val time = "9223372036854775.807"
        val body = p("<span begin=\"$time\">A</span>", time, time)
        val expected = TtmlParseResult.WordSynced(listOf(LyricLine(Long.MAX_VALUE, "A", listOf(LyricWord(Long.MAX_VALUE, 0)))))
        for (duration in listOf(null, -1L, Long.MIN_VALUE, Long.MAX_VALUE - 5000, Long.MAX_VALUE - 4999, Long.MAX_VALUE)) {
            assertEquals(expected, parse(body, duration))
        }
        assertTrue(parse(p("<span begin=\"5\">A</span>", "5", "6"), 0) is TtmlParseResult.WordSynced)
        invalidDiagnostic(xml(p("<span begin=\"5.001\">A</span>", "5.001", "6")), "beyond-duration", 0)
    }

    @Test fun invalidTimeFormatsCarryOnlyFixedReasonCode() {
        for (time in listOf("1.", "1.s", "0:01:02.", "-1", "00:60:00", "00:00:60",
            "9223372036854775808", "9223372036854775.808", "9223372036854775808:00:00",
            "2562047788016:00:00")) {
            invalidDiagnostic(xml(p("<span begin=\"$time\">A</span>")), "time-format")
        }
    }

    @Test fun doctypeAndMalformedXmlCarryOnlyFixedReasonCodes() {
        for (declaration in listOf("<!DOCTYPE tt>", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///unreadable-ttml-entity'>]>",
            "<!DOCTYPE tt SYSTEM 'https://invalid.example/ttml'>")) {
            invalidDiagnostic(declaration + xml(p()), "doctype")
        }
        for (source in listOf("", xml(p()).dropLast(1), xml(p("&unknown;")), xml(p(p())))) {
            invalidDiagnostic(source, "xml")
        }
    }

    @Test fun everyResourceLimitCarriesLimitReasonCode() {
        invalidDiagnostic(xml("<div>".repeat(60) + p() + "</div>".repeat(60)), "limit")
        invalidDiagnostic(xml(p().repeat(2001)), "limit")
        // Empty timed spans still count at START_TAG even though they add no words or text.
        invalidDiagnostic(xml(p("<span begin=\"0\" end=\"0\"/>".repeat(20001))), "limit")
        invalidDiagnostic(xml(p("<span begin=\"1\">${"A".repeat(16385)}</span>")), "limit")
    }

    @Test fun doctypeAndExternalEntitiesRejected() = runBlocking {
        for (declaration in listOf("<!DOCTYPE tt>", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///unreadable-ttml-entity'>]>", "<!DOCTYPE tt SYSTEM 'https://invalid.example/ttml'>")) {
            assertEquals(TtmlParseResult.Invalid, TtmlParser.parse(declaration + xml(p()), null))
        }
    }
    @Test fun malformedXmlIsInvalid() = runBlocking {
        assertEquals(TtmlParseResult.Invalid, TtmlParser.parse(xml(p()).dropLast(1), null))
        invalid(p("&unknown;"))
    }
    @Test fun depthLineWordTextLimits() = runBlocking {
        fun deep(n: Int) = xml("<div>".repeat(n) + p() + "</div>".repeat(n))
        assertTrue(TtmlParser.parse(deep(59), null) is TtmlParseResult.WordSynced)
        assertEquals(TtmlParseResult.Invalid, TtmlParser.parse(deep(60), null))
        fun manyLines(n: Int) = (0 until n).joinToString("") { p("<span begin=\"$it\">A</span>", "$it", "${it + 1}") }
        assertTrue(parse(manyLines(2000)) is TtmlParseResult.WordSynced)
        invalid(manyLines(2001))
        fun words(n: Int) = (0 until n).chunked(10000).mapIndexed { index, values -> p(values.joinToString("") { "<span begin=\"$it\">A</span>" }, "${index * 10000}", "${(index + 1) * 10000}") }.joinToString("")
        assertTrue(parse(words(20000)) is TtmlParseResult.WordSynced)
        invalid(words(20001))
        assertEquals(16384, lines(p("<span begin=\"1\">${"A".repeat(16384)}</span>")).single().text.length)
        invalid(p("<span begin=\"1\">${"A".repeat(16385)}</span>"))
    }
    @Test fun emptyLeadIsUnsupported() {
        assertEquals(TtmlParseResult.Unsupported, parse(""))
        assertEquals(TtmlParseResult.Unsupported, parse(p("<span m:role=\"x-bg\">X</span>")))
    }
    @Test fun cancelStopsParsing() {
        val job = Job().apply { cancel() }
        try {
            runBlocking(job) { TtmlParser.parse(xml(p()), null) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }
}
