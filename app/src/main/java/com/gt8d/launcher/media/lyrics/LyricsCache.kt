package com.gt8d.launcher.media.lyrics

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LyricsCache(private val dir: File, private val clock: () -> Long, private val maxFiles: Int = 300) {
    fun get(q: LyricsQuery): LyricsState? {
        var file: File? = null
        return try {
            file = File(dir, q.cacheKey + ".json")
            if (!file.exists()) return null
            val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
            require(json["v"] == JsonPrimitive(3))
            val savedAt = number(json["savedAt"])
            when (string(json["kind"])) {
                "synced" -> LyricsState.Synced((json["lines"] as JsonArray).map { element ->
                    val line = element as JsonArray
                    require(line.size == 3)
                    val words = (line[2] as JsonArray).map { element ->
                        val word = element as JsonArray
                        require(word.size == 2)
                        val charStart = number(word[1])
                        require(charStart in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                        LyricWord(number(word[0]), charStart.toInt())
                    }
                    LyricLine(number(line[0]), string(line[1]), words)
                })
                "plain" -> LyricsState.Plain(string(json["text"]))
                "missing" -> if (clock() - savedAt >= 24 * 60 * 60 * 1000L) {
                    file.delete()
                    null
                } else LyricsState.Missing
                else -> {
                    file.delete()
                    null
                }
            }
        } catch (_: Exception) {
            try { file?.delete() } catch (_: Exception) { }
            null
        }
    }

    fun put(q: LyricsQuery, state: LyricsState) {
        if (state !is LyricsState.Synced && state !is LyricsState.Plain && state != LyricsState.Missing) return
        try {
            val fields = mutableMapOf<String, JsonElement>("v" to JsonPrimitive(3), "savedAt" to JsonPrimitive(clock()))
            when (state) {
                is LyricsState.Synced -> {
                    fields["kind"] = JsonPrimitive("synced")
                    fields["lines"] = JsonArray(state.lines.map {
                        JsonArray(listOf(JsonPrimitive(it.timeMs), JsonPrimitive(it.text),
                            JsonArray(it.words.map { word ->
                                JsonArray(listOf(JsonPrimitive(word.timeMs), JsonPrimitive(word.charStart)))
                            })))
                    })
                }
                is LyricsState.Plain -> {
                    fields["kind"] = JsonPrimitive("plain")
                    fields["text"] = JsonPrimitive(state.text)
                }
                else -> fields["kind"] = JsonPrimitive("missing")
            }
            if (!dir.exists() && !dir.mkdirs()) return
            File(dir, q.cacheKey + ".json").writeText(JsonObject(fields).toString(), Charsets.UTF_8)
            val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return
            if (files.size > maxFiles) {
                files.sortedBy { file ->
                    try {
                        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                        number(json["savedAt"])
                    } catch (_: Exception) {
                        file.lastModified()
                    }
                }.take((files.size - maxFiles).coerceAtMost(files.size)).forEach { it.delete() }
            }
        } catch (_: Exception) {
            // Cache failures must not prevent displaying fetched lyrics.
        }
    }

    private fun string(element: JsonElement?): String {
        val primitive = element as JsonPrimitive
        require(primitive.isString)
        return primitive.content
    }

    private fun number(element: JsonElement?): Long {
        val primitive = element as JsonPrimitive
        require(!primitive.isString)
        return primitive.content.toLong()
    }
}
