package com.gt8d.launcher.media.lyrics

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Pass files/lyrics/amll as dir; the legacy v3 directory is never scanned. Call on IO. */
class AmllCache(private val dir: File, private val clock: () -> Long) {
    /** Test observer for savedAt reads performed while pruning. */
    internal var onPruneSavedAtRead: ((File) -> Unit)? = null

    @Synchronized fun get(request: LyricsRequest, indexGeneration: String?): AmllResult? {
        val file = File(dir, key(request) + ".json")
        return try {
            if (!file.isFile || file.length() > ENTRY_LIMIT) return null
            val json = Json.parseToJsonElement(file.readText()) as JsonObject
            require(json["v"] == JsonPrimitive(1))
            require(string(json["key"]) == key(request))
            val reason = AmllReason.valueOf(string(json["reason"]))
            val target = (json["target"] as? JsonArray)?.let {
                require(it.size == 2)
                AmllTarget(string(it[0]), string(it[1])).also { t -> require(t.safe) }
            }
            val state = when (string(json["kind"])) {
                "missing" -> {
                    require(reason in setOf(AmllReason.NOT_FOUND, AmllReason.AMBIGUOUS, AmllReason.UNSUPPORTED))
                    // Older HTTP 404 entries used NOT_FOUND with a download target.
                    require(reason != AmllReason.NOT_FOUND || target == null)
                    val savedAt = number(json["savedAt"])
                    val now = clock()
                    require(now >= savedAt && now - savedAt < 86_400_000L)
                    LyricsState.Missing
                }
                "synced" -> {
                    val lines = (json["lines"] as JsonArray).map { value ->
                        val line = value as JsonArray
                        require(line.size == 3)
                        LyricLine(number(line[0]), string(line[1]), (line[2] as JsonArray).map { value ->
                            val word = value as JsonArray
                            require(word.size == 2)
                            val offset = number(word[1])
                            require(offset in 0..Int.MAX_VALUE.toLong())
                            LyricWord(number(word[0]), offset.toInt())
                        })
                    }
                    require(validLines(lines))
                    require(target != null)
                    LyricsState.Synced(lines)
                }
                else -> return null
            }
            require(string(json["source"]) == "AMLL TTML DB")
            AmllResult(state, reason, target, cacheable = true)
        } catch (_: Exception) { null }
    }

    @Synchronized fun put(request: LyricsRequest, indexGeneration: String?, result: AmllResult) {
        if (!result.cacheable) return
        val state = result.state
        val missing = state == LyricsState.Missing &&
            result.reason in setOf(AmllReason.NOT_FOUND, AmllReason.AMBIGUOUS, AmllReason.UNSUPPORTED) &&
            (result.reason != AmllReason.NOT_FOUND || result.target == null)
        if (!missing && (state !is LyricsState.Synced || !validLines(state.lines) || result.target?.safe != true)) return
        var temporary: File? = null
        try {
            val fields = mutableMapOf<String, JsonElement>(
                "v" to JsonPrimitive(1), "key" to JsonPrimitive(key(request)),
                "savedAt" to JsonPrimitive(clock()), "generation" to primitive(indexGeneration),
                "source" to JsonPrimitive(result.source), "reason" to JsonPrimitive(result.reason.name),
                "target" to (result.target?.let { JsonArray(listOf(JsonPrimitive(it.folder), JsonPrimitive(it.id))) } ?: JsonNull),
                "kind" to JsonPrimitive(if (missing) "missing" else "synced"),
            )
            if (state is LyricsState.Synced) fields["lines"] = JsonArray(state.lines.map { line ->
                JsonArray(listOf(JsonPrimitive(line.timeMs), JsonPrimitive(line.text), JsonArray(line.words.map {
                    JsonArray(listOf(JsonPrimitive(it.timeMs), JsonPrimitive(it.charStart)))
                })))
            })
            val bytes = JsonObject(fields).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > ENTRY_LIMIT) return
            if (!dir.isDirectory && !dir.mkdirs()) return
            temporary = File.createTempFile("amll-", ".tmp", dir)
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(File(dir, key(request) + ".json"))) return
            prune()
        } catch (_: Exception) {
            // Fetched lyrics remain usable even if storage is unavailable.
        } finally { try { temporary?.delete() } catch (_: Exception) { } }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        if (count <= 300 && bytes <= TOTAL_LIMIT) return
        val oldestFirst = files.map { file ->
            val savedAt = try {
                onPruneSavedAtRead?.invoke(file)
                number((Json.parseToJsonElement(file.readText()) as JsonObject)["savedAt"])
            } catch (_: Exception) { Long.MIN_VALUE }
            file to savedAt
        }.sortedWith(compareBy<Pair<File, Long>> { it.second }.thenBy { it.first.name })
        for ((file, _) in oldestFirst) {
            if (count <= 300 && bytes <= TOTAL_LIMIT) break
            val size = file.length()
            if (file.delete()) { count--; bytes -= size }
        }
    }

    companion object {
        private const val ENTRY_LIMIT = 1024 * 1024
        private const val TOTAL_LIMIT = 32 * 1024 * 1024L

        internal fun key(request: LyricsRequest): String {
            val json = JsonArray(listOf(JsonPrimitive(request.query.cacheKey),
                primitive(validAppleId(request.identity.appleMusicId)), primitive(validIsrc(request.identity.isrc))))
            return MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }

        private fun primitive(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
        private fun string(value: JsonElement?): String = (value as JsonPrimitive).also { require(it.isString) }.content
        private fun number(value: JsonElement?): Long = (value as JsonPrimitive).also { require(!it.isString) }.content.toLong()
        private fun validLines(lines: List<LyricLine>): Boolean = lines.isNotEmpty() &&
            lines.any { it.words.isNotEmpty() } && lines.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs } &&
            lines.withIndex().all { (index, line) ->
                line.timeMs >= 0 && line.text.isNotBlank() &&
                    line.words.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs && a.charStart <= b.charStart } &&
                    line.words.all { it.timeMs >= line.timeMs && it.charStart in 0..line.text.length &&
                        (lines.getOrNull(index + 1)?.timeMs?.let { next -> it.timeMs < next } != false) }
            }
    }
}
