package com.gt8d.launcher.media.lyrics

import android.util.Xml
import java.io.StringReader
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

sealed interface TtmlParseResult {
    data class WordSynced(val lines: List<LyricLine>) : TtmlParseResult
    data class LineSynced(val lines: List<LyricLine>) : TtmlParseResult
    data object Unsupported : TtmlParseResult
    data object Invalid : TtmlParseResult
}

object TtmlParser {
    private const val TT = "http://www.w3.org/ns/ttml"
    private const val METADATA = "$TT#metadata"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private val seconds = Regex("([0-9]+)(?:\\.([0-9]+))?s?")
    private val clock = Regex("(?:([0-9]+):)?([0-9]{1,2}):([0-9]{2})(?:\\.([0-9]+))?")
    private class InvalidData(val reason: String) : RuntimeException()
    private data class Word(val start: Long, val offset: Int)
    private class Line(val start: Long, val depth: Int) {
        val text = StringBuilder()
        val words = mutableListOf<Word>()
        val effectiveStart: Long get() = minOf(start, words.firstOrNull()?.start ?: start)
    }
    private data class Frame(val excluded: Boolean, val preserve: Boolean, val timed: Boolean,
        val word: Word? = null, val wordIndex: Int = 0)

    private fun time(value: String): Long {
        val s = seconds.matchEntire(value)
        if (s != null) {
            val whole = s.groupValues[1].toLongOrNull() ?: throw InvalidData("time-format")
            val fraction = s.groupValues[2].take(3).padEnd(3, '0').toLong()
            if (whole > (Long.MAX_VALUE - fraction) / 1000) throw InvalidData("time-format")
            return whole * 1000 + fraction
        }
        val c = clock.matchEntire(value) ?: throw InvalidData("time-format")
        val hours = c.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: throw InvalidData("time-format")
        val minutes = c.groupValues[2].toInt()
        val secs = c.groupValues[3].toInt()
        if (minutes > 59 || secs > 59) throw InvalidData("time-format")
        val remainder = minutes * 60000L + secs * 1000L + c.groupValues[4].take(3).padEnd(3, '0').toLong()
        if (hours > (Long.MAX_VALUE - remainder) / 3600000) throw InvalidData("time-format")
        return hours * 3600000 + remainder
    }

    /** Call off the main thread. Cancellation is checked for every XML token. */
    suspend fun parse(xml: String, durationMs: Long? = null, allowLineSynced: Boolean = false,
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
                    XmlPullParser.DOCDECL -> throw InvalidData("doctype")
                    XmlPullParser.START_TAG -> {
                        if (parser.depth > 64) throw InvalidData("limit")
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
                        if (isP && ++lineCount > 2000) throw InvalidData("limit")
                        if (isSpan && begin != null && ++wordCount > 20000) throw InvalidData("limit")
                        var word: Word? = null
                        val wordIndex = current?.words?.size ?: 0
                        if (!excluded) {
                            if (parser.getAttributeValue(null, "timeContainer") != null) unsupported("timeContainer")
                            if (isDiv || isP || isSpan) {
                                val start = begin?.let(::time)
                                val finish = end?.let(::time)
                                if (start != null && finish != null && finish < start) throw InvalidData("element-range")
                            }
                            if (isP) {
                                if (current != null) throw InvalidData("xml")
                                if (begin == null) unsupported("line-without-begin")
                                else {
                                    current = Line(time(begin), parser.depth)
                                }
                            } else if (isSpan && current != null) {
                                if (begin != null) {
                                    if (stack.any { it.timed }) unsupported("nested-timed-span")
                                    word = Word(time(begin), current.text.length)
                                } else if (end != null) unsupported("span-end-without-begin")
                            } else if (current != null || !isDiv && (begin != null || end != null) && parser.namespace == TT) {
                                unsupported("element-or-timing")
                            }
                        }
                        stack.add(Frame(excluded, preserve, !excluded && isSpan && begin != null,
                            word, wordIndex))
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        val frame = stack.lastOrNull()
                        if (current != null && frame?.excluded == false) {
                            val text = parser.text ?: throw InvalidData("xml")
                            val indentation = !frame.preserve && text.isBlank() && ('\n' in text || '\r' in text)
                            if (!indentation) {
                                if (text.length > 16384 - current.text.length) throw InvalidData("limit")
                                current.text.append(text)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val frame = stack.last()
                        val word = frame.word
                        if (current != null && word != null && current.text.substring(word.offset).isNotBlank()) {
                            // Keep opening-tag order even for nested spans classified as Unsupported.
                            current.words.add(frame.wordIndex, word)
                        }
                        if (current?.depth == parser.depth) {
                            if (current.text.isNotBlank()) lines.add(current)
                            current = null
                        }
                        stack.removeAt(stack.lastIndex)
                    }
                }
                event = parser.nextToken()
            }
            if (!rootSeen) throw InvalidData("xml")
            val sorted = lines.sortedBy { it.effectiveStart }
            val durationLimit = durationMs?.takeIf { it >= 0 }?.let {
                if (it > Long.MAX_VALUE - 5_000L) Long.MAX_VALUE else it + 5_000L
            }
            sorted.forEach { line ->
                coroutineContext.ensureActive()
                if (durationLimit != null && line.effectiveStart > durationLimit) throw InvalidData("beyond-duration")
                var previous: Long? = null
                for (word in line.words) {
                    if (previous != null && word.start < previous) throw InvalidData("word-order")
                    previous = word.start
                }
            }
            if (allowLineSynced && !unsupported && sorted.isNotEmpty() && sorted.all { it.words.isEmpty() }) {
                TtmlParseResult.LineSynced(sorted.map { line -> LyricLine(line.effectiveStart, line.text.toString()) })
            } else if (unsupported || sorted.none { it.words.isNotEmpty() }) {
                diagnostic("Unsupported $unsupportedReason")
                TtmlParseResult.Unsupported
            } else TtmlParseResult.WordSynced(sorted.map { line ->
                LyricLine(line.effectiveStart, line.text.toString(), line.words.map { LyricWord(it.start, it.offset) })
            }).also { diagnostic("WordSynced lines=${it.lines.size}") }
        } catch (e: InvalidData) {
            diagnostic("Invalid ${e.reason}")
            TtmlParseResult.Invalid
        } catch (_: XmlPullParserException) {
            diagnostic("Invalid xml")
            TtmlParseResult.Invalid
        }
    }
}
