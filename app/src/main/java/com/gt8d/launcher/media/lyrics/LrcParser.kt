package com.gt8d.launcher.media.lyrics

object LrcParser {
    private val timestamp = Regex("\\[([0-9]+):([0-9]{2})(?:\\.([0-9]{2,3}))?\\]")
    private val offsetTag = Regex("\\[offset:([+-]?[0-9]+)\\]")
    private val wordTimestamp = Regex("<([0-9]+):([0-9]{2})(?:\\.([0-9]{2,3}))?>")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        var offset: Long? = null
        for (line in lrc.lineSequence()) {
            val tag = offsetTag.matchEntire(line)
            if (tag != null) {
                if (offset == null) offset = tag.groupValues[1].toLongOrNull()
                continue
            }
            val times = mutableListOf<Long>()
            var cursor = 0
            var invalid = false
            while (true) {
                val match = timestamp.matchAt(line, cursor) ?: break
                val minutes = match.groupValues[1].toLongOrNull()
                val seconds = match.groupValues[2].toInt()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    2 -> fraction.toInt() * 10
                    3 -> fraction.toInt()
                    else -> 0
                }
                val remainder = seconds * 1000L + millis
                if (seconds > 59 || minutes == null || minutes > (Long.MAX_VALUE - remainder) / 60000) {
                    invalid = true
                    break
                }
                times.add(minutes * 60000 + remainder)
                cursor = match.range.last + 1
            }
            if (invalid || times.isEmpty()) continue
            val body = line.substring(cursor)
            val clean = StringBuilder()
            val words = mutableListOf<LyricWord>()
            var bodyCursor = 0
            for (match in wordTimestamp.findAll(body)) {
                clean.append(body.substring(bodyCursor, match.range.first))
                val minutes = match.groupValues[1].toLongOrNull()
                val seconds = match.groupValues[2].toInt()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    2 -> fraction.toInt() * 10
                    3 -> fraction.toInt()
                    else -> 0
                }
                val remainder = seconds * 1000L + millis
                if (seconds <= 59 && minutes != null && minutes <= (Long.MAX_VALUE - remainder) / 60000) {
                    words.add(LyricWord(minutes * 60000 + remainder, clean.length))
                }
                bodyCursor = match.range.last + 1
            }
            clean.append(body.substring(bodyCursor))
            val leading = clean.length - clean.trimStart().length
            val text = clean.trim().toString().ifEmpty { "♪" }
            val positioned = words.map { it.copy(charStart = (it.charStart - leading).coerceIn(0, text.length)) }
            times.forEach { lines.add(LyricLine(it, text, positioned)) }
        }
        val adjustment = offset ?: 0L
        fun adjusted(time: Long): Long = when {
                adjustment > time -> 0L
                adjustment < 0 && time > Long.MAX_VALUE + adjustment -> Long.MAX_VALUE
                else -> time - adjustment
            }
        return lines.map {
            it.copy(timeMs = adjusted(it.timeMs), words = it.words.map { word ->
                word.copy(timeMs = adjusted(word.timeMs))
            }.sortedBy { word -> word.timeMs })
        }.sortedBy { it.timeMs }
    }

    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }
}
