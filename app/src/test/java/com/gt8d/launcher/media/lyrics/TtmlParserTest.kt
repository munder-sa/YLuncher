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
        invalid("<div begin=\"2\" end=\"1\">${p()}</div>")
        invalid("<div begin=\"2\" end=\"4\">${p()}</div>")
        invalid("<div begin=\"0\" end=\"2\">${p()}</div>")
    }

    private fun xml(body: String) = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata"><body><div>$body</div></body></tt>"""
    private fun p(content: String = "<span begin=\"1\">A</span>", begin: String = "1", end: String = "3") =
        "<p begin=\"$begin\" end=\"$end\">$content</p>"
    private fun parse(body: String, duration: Long? = null): TtmlParseResult = runBlocking { TtmlParser.parse(xml(body), duration) }
    private fun lines(body: String) = (parse(body) as TtmlParseResult.WordSynced).lines
    private fun invalid(body: String) = assertEquals(TtmlParseResult.Invalid, parse(body))

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
        for (time in listOf("00:00:60", "00:60:00", "-1", "9223372036854775808", "9223372036854776s", "1f", "1t", "1.0001", "NaN")) {
            invalid(p("<span begin=\"$time\">A</span>"))
        }
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
        for (second in listOf("1", "0.5")) invalid(p("<span begin=\"1\">A</span><span begin=\"$second\">B</span>"))
    }
    @Test fun lineStartAndNextLineBounds() {
        invalid(p("<span begin=\"0.9\">A</span>"))
        invalid("<p begin=\"1\"><span begin=\"2\">A</span></p>" + p("B", "2", "3"))
        assertEquals(TtmlParseResult.Invalid, parse(p(), 999))
        assertTrue(parse(p(), 1000) is TtmlParseResult.WordSynced)
        assertEquals(listOf(1000L, 3000L), lines(p("<span begin=\"3\">B</span>", "3", "4") + p()).map { it.timeMs })
    }
    @Test fun overlappingLeadLinesRejected() {
        invalid(p() + p("B", "2", "4"))
        invalid(p() + p("B"))
    }
    @Test fun endValidatedButNotStored() {
        assertEquals(lines(p(end = "2")), lines(p(end = "3")))
        invalid(p(end = "1"))
        invalid(p("<span begin=\"1\" end=\"1\">A</span>"))
        invalid(p("<span begin=\"1\" end=\"4\">A</span>"))
        invalid(p("<span begin=\"3\">A</span>"))
    }
    @Test fun noWordsIsUnsupported() = assertEquals(TtmlParseResult.Unsupported, parse(p("A")))
    @Test fun plainLineWithinWordSyncedSongHasNoWords() {
        assertEquals(LyricLine(3000, "B"), lines(p() + p("B", "3", "4"))[1])
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
