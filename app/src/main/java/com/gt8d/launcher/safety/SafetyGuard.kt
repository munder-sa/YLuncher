package com.gt8d.launcher.safety

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object SafetyGuard {
    private const val FILE_NAME = "safety.json"
    private const val QUICK_CRASH_WINDOW_MS = 10_000L
    private const val FALLBACK_STREAK_COUNT = 3
    private const val MAX_CRASHES = 10

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class SafetyState(
        val startElapsed: Long = 0L,
        val crashes: List<Long> = emptyList(),
        val fallback: Boolean = false,
    )

    fun shouldFallback(crashTimesSinceStart: List<Long>): Boolean {
        var streak = 0
        for (ms in crashTimesSinceStart.asReversed()) {
            if (ms <= QUICK_CRASH_WINDOW_MS) {
                streak++
                if (streak >= FALLBACK_STREAK_COUNT) return true
            } else {
                break
            }
        }
        return false
    }

    @Synchronized
    fun recordStart(context: Context) {
        val current = load(context)
        val now = SystemClock.elapsedRealtime()
        val updated = current.copy(
            startElapsed = now,
            fallback = current.fallback || shouldFallback(current.crashes),
        )
        save(context, updated)
    }

    @Synchronized
    fun recordCrash(context: Context) {
        val current = load(context)
        val elapsed = (SystemClock.elapsedRealtime() - current.startElapsed).coerceAtLeast(0L)
        val crashes = (current.crashes + elapsed).takeLast(MAX_CRASHES)
        val updated = current.copy(
            crashes = crashes,
            fallback = current.fallback || shouldFallback(crashes),
        )
        save(context, updated)
    }

    @Synchronized
    fun clear(context: Context) {
        val current = load(context)
        save(
            context,
            current.copy(
                crashes = emptyList(),
                fallback = false,
            ),
        )
    }

    @Synchronized
    fun isFallbackActive(context: Context): Boolean = load(context).fallback

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): SafetyState = runCatching {
        val f = file(context)
        if (!f.exists()) return SafetyState()
        json.decodeFromString(SafetyState.serializer(), f.readText())
    }.getOrDefault(SafetyState())

    private fun save(context: Context, state: SafetyState) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(SafetyState.serializer(), state))
        }
    }
}
