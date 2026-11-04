package com.gt8d.launcher.media.lyrics

import java.util.Collections

data class AppleLyricsCacheKey(
    val generation: Long,
    val storefront: String,
    val appleMusicId: String,
    val queryKey: String,
    val parserVersion: Int = 1,
)

/** Memory only. The byte bound counts UTF-8 lyric text, not JVM heap usage. */
class AppleLyricsCache(private val clock: () -> Long) {
    private class Entry(val result: AppleLyricsResult, val expiresAt: Long, val textBytes: Long)

    private val entries = LinkedHashMap<AppleLyricsCacheKey, Entry>(8, 0.75f, true)
    private var generation = 0L
    private var lastTime: Long? = null

    @Synchronized fun activateGeneration(generation: Long) {
        if (generation <= this.generation) return
        this.generation = generation
        entries.clear()
    }

    @Synchronized fun get(key: AppleLyricsCacheKey): AppleLyricsResult? {
        if (key.generation != generation) return null
        pruneExpired(now())
        return entries[key]?.result
    }

    @Synchronized fun put(key: AppleLyricsCacheKey, result: AppleLyricsResult) {
        if (key.generation != generation) return
        val now = now()
        pruneExpired(now)
        val seconds = when {
            result.reason == AppleLyricsReason.OK && result.state is LyricsState.Synced &&
                result.state.lines.any { it.words.isNotEmpty() } -> 30 * 60L
            result.state == LyricsState.Missing && result.reason in setOf(
                AppleLyricsReason.NOT_FOUND, AppleLyricsReason.UNSUPPORTED,
            ) -> 60L
            (result.state == LyricsState.Failed || result.state == LyricsState.Offline) &&
                result.reason in TRANSIENT_REASONS -> maxOf(60L, result.retryAfterSec ?: 0L)
            else -> return
        }
        val state = result.state
        var textBytes = 0L
        if (state is LyricsState.Synced) {
            for (line in state.lines) {
                textBytes += line.text.toByteArray(Charsets.UTF_8).size
                if (textBytes > MAX_TEXT_BYTES) return
            }
        }
        // Protect both against mutation of the input and mutation through a returned list.
        val snapshot = if (state is LyricsState.Synced) LyricsState.Synced(
            Collections.unmodifiableList(state.lines.map { line ->
                line.copy(words = Collections.unmodifiableList(line.words.map { it.copy() }))
            }),
        ) else state
        val ttl = if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
        val expiresAt = if (now > Long.MAX_VALUE - ttl) Long.MAX_VALUE else now + ttl
        entries[key] = Entry(AppleLyricsResult(snapshot, result.reason, result.retryAfterSec), expiresAt, textBytes)
        var totalBytes = entries.values.sumOf { it.textBytes }
        val oldest = entries.entries.iterator()
        while (entries.size > 8 || totalBytes > MAX_TEXT_BYTES) {
            totalBytes -= oldest.next().value.textBytes
            oldest.remove()
        }
    }

    private fun now(): Long = clock().also { now ->
        // A clock reset invalidates existing deadlines; never revive old entries.
        if (lastTime?.let { now < it } == true) entries.clear()
        lastTime = now
    }

    private fun pruneExpired(now: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) if (now >= iterator.next().expiresAt) iterator.remove()
    }

    companion object {
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024L
        private val STOREFRONT = Regex("[a-z]{2}")
        private val APPLE_ID = Regex("[0-9]{1,20}")
        private val TRANSIENT_REASONS = setOf(
            AppleLyricsReason.NETWORK, AppleLyricsReason.HTTP, AppleLyricsReason.WAIT,
            AppleLyricsReason.TIMEOUT, AppleLyricsReason.TOKEN_UNAVAILABLE, AppleLyricsReason.INVALID,
        )

        fun key(request: LyricsRequest, connection: AppleMusicConnection): AppleLyricsCacheKey? {
            val storefront = connection.storefront ?: return null
            val id = request.identity.appleMusicId ?: return null
            if (connection.status != AppleMusicStatus.CONNECTED || connection.generation < 0 ||
                !STOREFRONT.matches(storefront) || !APPLE_ID.matches(id) || id.all { it == '0' } ||
                request.query.title.isBlank() || request.query.artist.isBlank()
            ) return null
            return AppleLyricsCacheKey(connection.generation, storefront, id, request.query.cacheKey)
        }
    }
}
