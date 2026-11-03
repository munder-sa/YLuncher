package com.gt8d.launcher.media.lyrics

object LrcParser {
    private val timestamp = Regex("\\[([0-9]+):([0-9]{2})(?:\\.([0-9]{2,3}))?\\]")
    private val offsetTag = Regex("\\[offset:([+-]?[0-9]+)\\]")

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
            val text = line.substring(cursor).trim().ifEmpty { "♪" }
            times.forEach { lines.add(LyricLine(it, text)) }
        }
        val adjustment = offset ?: 0L
        return lines.map {
            val time = when {
                adjustment > it.timeMs -> 0L
                adjustment < 0 && it.timeMs > Long.MAX_VALUE + adjustment -> Long.MAX_VALUE
                else -> it.timeMs - adjustment
            }
            it.copy(timeMs = time)
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
