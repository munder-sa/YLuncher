package com.gt8d.launcher.media.lyrics

import android.util.Xml
import java.io.StringReader
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

sealed interface TtmlParseResult {
    data class WordSynced(val lines: List<LyricLine>) : TtmlParseResult
    data object Unsupported : TtmlParseResult
    data object Invalid : TtmlParseResult
}

object TtmlParser {
    private const val TT = "http://www.w3.org/ns/ttml"
    private const val METADATA = "$TT#metadata"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private val seconds = Regex("([0-9]+)(?:\\.([0-9]{1,3}))?s?")
    private val clock = Regex("(?:([0-9]{2,}):)?([0-9]{1,2}):([0-9]{2})(?:\\.([0-9]{1,3}))?")
    private class InvalidData : RuntimeException()
    private data class Word(val start: Long, val end: Long?, val offset: Int)
    private class Line(val start: Long, val end: Long?, val depth: Int) {
        val text = StringBuilder()
        val words = mutableListOf<Word>()
    }
    private data class Frame(val excluded: Boolean, val preserve: Boolean, val timed: Boolean,
        val start: Long? = null, val end: Long? = null)

    private fun time(value: String): Long {
        val s = seconds.matchEntire(value)
        if (s != null) {
            val whole = s.groupValues[1].toLongOrNull() ?: throw InvalidData()
            val fraction = s.groupValues[2].padEnd(3, '0').toLong()
            if (whole > (Long.MAX_VALUE - fraction) / 1000) throw InvalidData()
            return whole * 1000 + fraction
        }
        val c = clock.matchEntire(value) ?: throw InvalidData()
        val hours = c.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: throw InvalidData()
        val minutes = c.groupValues[2].toInt()
        val secs = c.groupValues[3].toInt()
        if (minutes > 59 || secs > 59) throw InvalidData()
        val remainder = minutes * 60000L + secs * 1000L + c.groupValues[4].padEnd(3, '0').toLong()
        if (hours > (Long.MAX_VALUE - remainder) / 3600000) throw InvalidData()
        return hours * 3600000 + remainder
    }

    /** Call off the main thread. Cancellation is checked for every XML token. */
    suspend fun parse(xml: String, durationMs: Long? = null,
        diagnostic: (String) -> Unit = {}): TtmlParseResult {
        coroutineContext.ensureActive()
        // Reject before handing any DTD (including external declarations) to the parser.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            diagnostic("Invalid doctype")
            return TtmlParseResult.Invalid
        }
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(StringReader(xml))
            val stack = mutableListOf<Frame>()
            val lines = mutableListOf<Line>()
            var current: Line? = null
            var lineCount = 0
            var wordCount = 0
            var unsupported = false
            var unsupportedReason = "no-timed-words"
            fun unsupported(reason: String) {
                if (!unsupported) unsupportedReason = reason
                unsupported = true
            }
            var rootSeen = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                coroutineContext.ensureActive()
                when (event) {
                    XmlPullParser.DOCDECL -> throw InvalidData()
                    XmlPullParser.START_TAG -> {
                        if (parser.depth > 64) throw InvalidData()
                        if (!rootSeen) {
                            rootSeen = true
                            if (parser.namespace != TT || parser.name != "tt") unsupported("root")
                        }
                        val parent = stack.lastOrNull()
                        val role = parser.getAttributeValue(METADATA, "role")
                        val excluded = parent?.excluded == true ||
                            (parser.namespace == TT && parser.name == "head") ||
                            role in setOf("x-bg", "x-translation", "x-roman")
                        if (!excluded && role != null) unsupported("role")
                        val preserve = when (parser.getAttributeValue(XML, "space")) {
                            "preserve" -> true
                            "default" -> false
                            else -> parent?.preserve == true
                        }
                        val isP = parser.namespace == TT && parser.name == "p"
                        val isSpan = parser.namespace == TT && parser.name == "span"
                        val isDiv = parser.namespace == TT && parser.name == "div"
                        val begin = parser.getAttributeValue(null, "begin")
                        val end = parser.getAttributeValue(null, "end")
                        if (isP && ++lineCount > 2000) throw InvalidData()
                        if (isSpan && begin != null && ++wordCount > 20000) throw InvalidData()
                        if (!excluded) {
                            if (parser.getAttributeValue(null, "timeContainer") != null) unsupported("timeContainer")
                            if (isDiv || isP || isSpan) {
                                val start = begin?.let(::time)
                                val finish = end?.let(::time)
                                if (start != null && finish != null && finish <= start ||
                                    start != null && parent?.start?.let { start < it } == true ||
                                    start != null && parent?.end?.let { start >= it } == true ||
                                    finish != null && parent?.end?.let { finish > it } == true) throw InvalidData()
                            }
                            if (isP) {
                                if (current != null) throw InvalidData()
                                if (begin == null) unsupported("line-without-begin")
                                else {
                                    val start = time(begin)
                                    val finish = end?.let(::time)
                                    if (finish != null && finish <= start) throw InvalidData()
                                    current = Line(start, finish, parser.depth)
                                }
                            } else if (isSpan && current != null) {
                                if (begin != null) {
                                    if (stack.any { it.timed }) unsupported("nested-timed-span")
                                    val start = time(begin)
                                    val finish = end?.let(::time)
                                    if (finish != null && finish <= start) throw InvalidData()
                                    current.words.add(Word(start, finish, current.text.length))
                                } else if (end != null) unsupported("span-end-without-begin")
                            } else if (current != null || !isDiv && (begin != null || end != null) && parser.namespace == TT) {
                                unsupported("element-or-timing")
                            }
                        }
                        stack.add(Frame(excluded, preserve, !excluded && isSpan && begin != null,
                            if (!excluded && (isDiv || isP || isSpan)) begin?.let(::time) ?: parent?.start else parent?.start,
                            if (!excluded && (isDiv || isP || isSpan)) end?.let(::time) ?: parent?.end else parent?.end))
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        val frame = stack.lastOrNull()
                        if (current != null && frame?.excluded == false) {
                            val text = parser.text ?: throw InvalidData()
                            val indentation = !frame.preserve && text.isBlank() && ('\n' in text || '\r' in text)
                            if (!indentation) {
                                if (text.length > 16384 - current.text.length) throw InvalidData()
                                current.text.append(text)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (current?.depth == parser.depth) {
                            if (current.text.isNotBlank()) lines.add(current)
                            current = null
                        }
                        stack.removeAt(stack.lastIndex)
                    }
                }
                event = parser.nextToken()
            }
            if (!rootSeen) throw InvalidData()
            val sorted = lines.sortedBy { it.start }
            sorted.forEachIndexed { index, line ->
                coroutineContext.ensureActive()
                val next = sorted.getOrNull(index + 1)?.start
                if (durationMs != null && durationMs >= 0 && line.start > durationMs) throw InvalidData()
                if (next != null && (line.start == next || line.end != null && line.end > next)) throw InvalidData()
                val bound = line.end ?: next
                var previous: Long? = null
                for (word in line.words) {
                    if (word.start < line.start || previous?.let { word.start <= it } == true ||
                        bound?.let { word.start >= it || word.end?.let { end -> end > it } == true } == true) throw InvalidData()
                    previous = word.start
                }
            }
            if (unsupported || sorted.none { it.words.isNotEmpty() }) {
                diagnostic("Unsupported $unsupportedReason")
                TtmlParseResult.Unsupported
            } else TtmlParseResult.WordSynced(sorted.map { line ->
                LyricLine(line.start, line.text.toString(), line.words.map { LyricWord(it.start, it.offset) })
            }).also { diagnostic("WordSynced lines=${it.lines.size}") }
        } catch (_: InvalidData) {
            diagnostic("Invalid timing-or-limit")
            TtmlParseResult.Invalid
        } catch (_: XmlPullParserException) {
            diagnostic("Invalid xml")
            TtmlParseResult.Invalid
        }
    }
}
