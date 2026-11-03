package com.gt8d.launcher.media.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

/** Stateless, line-at-a-time conversion and conservative matching. */
internal object AmllIndex {
    const val LINE_LIMIT = 64 * 1024
    const val TABLE_LIMIT = 8 * 1024 * 1024

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\p{Z}\\s\u0085]+"), " ").trim()

    private fun titleParts(title: String): Pair<String, String?> {
        val suffix = Regex("\\s*\\(feat\\. (.+)\\)$").find(title)
        return if (suffix == null) title to null else title.substring(0, suffix.range.first).trim() to suffix.groupValues[1].trim()
    }

    data class Row(val titles: List<String>, val artists: List<String>, val albums: List<String>, val isrcs: List<String>, val target: AmllTarget) {
        fun encode(): String = JsonArray(listOf(titles, artists, albums, isrcs, listOf(target.folder, target.id))
            .map { values -> JsonArray(values.map(::JsonPrimitive)) }).toString()
    }

    private fun strings(value: JsonElement): List<String> = (value as JsonArray).map {
        (it as JsonPrimitive).also { p -> require(p.isString) }.content
    }

    fun parseRaw(line: String): Row {
        val obj = Json.parseToJsonElement(line) as JsonObject
        val fields = mutableMapOf<String, List<String>>()
        for (element in obj.getValue("metadata") as JsonArray) {
            val pair = element as JsonArray
            require(pair.size == 2)
            val key = (pair[0] as JsonPrimitive).also { require(it.isString) }.content
            require(!fields.containsKey(key))
            fields[key] = strings(pair[1])
        }
        fun normalized(key: String) = fields[key].orEmpty().map(::normalize).distinct().also { values -> require(values.none(String::isBlank)) }
        val file = (obj.getValue("rawLyricFile") as JsonPrimitive).also { require(it.isString) }.content
        val target = AmllTarget("raw-lyrics", file)
        require(target.safe)
        val titles = normalized("musicName"); val artists = normalized("artists")
        require(titles.isNotEmpty() && artists.isNotEmpty())
        return Row(titles, artists, normalized("album"), fields["isrc"].orEmpty().mapNotNull(::validIsrc).distinct(), target)
    }

    private fun decode(line: String): Row {
        val fields = (Json.parseToJsonElement(line) as JsonArray).map(::strings)
        require(fields.size == 5 && fields[4].size == 2)
        val target = AmllTarget(fields[4][0], fields[4][1])
        require(target.safe && fields[0].isNotEmpty() && fields[1].isNotEmpty())
        return Row(fields[0], fields[1], fields[2], fields[3], target)
    }

    suspend fun resolve(lines: Iterator<String>, query: LyricsQuery, isrc: String?): AmllMatch {
        val title = titleParts(normalize(query.title)); val artist = normalize(query.artist)
        val album = query.album?.let(::normalize)?.takeIf(String::isNotEmpty)
        val verifiedIsrc = validIsrc(isrc)
        var candidate: AmllTarget? = null
        var ambiguous = false
        while (lines.hasNext()) {
            coroutineContext.ensureActive()
            val row = decode(lines.next())
            if (row.titles.none { titleParts(it) == title } || artist !in row.artists ||
                (album != null && album !in row.albums) || (verifiedIsrc != null && verifiedIsrc !in row.isrcs)) continue
            if (candidate == null) candidate = row.target else if (candidate != row.target) ambiguous = true
        }
        return if (ambiguous) AmllMatch.Ambiguous else candidate?.let(AmllMatch::Unique) ?: AmllMatch.NotFound
    }

    fun checkTableSize(size: Long) { require(size <= TABLE_LIMIT) }

    /** Counts UTF-8 bytes before decoding, including line delimiters in the receive limit. */
    suspend fun readLines(input: InputStream, consume: suspend (String) -> Unit) {
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        suspend fun emit() {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            consume(decoder.decode(ByteBuffer.wrap(line.toByteArray())).toString())
            line.reset()
        }
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            total += count; checkTableSize(total)
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == 10.toByte()) emit() else {
                    require(line.size() < LINE_LIMIT)
                    line.write(byte.toInt())
                }
            }
        }
        if (line.size() > 0) emit()
    }
}
