package com.gt8d.launcher.link

import com.gt8d.launcher.time.Clock

class PendingUrl(private val clock: Clock) {
    companion object { const val VALID_MS = 15_000L }
    private var url: String? = null
    private var storedAtMs = 0L

    @Synchronized
    fun set(url: String) {
        this.url = url
        storedAtMs = clock.nowMs()
    }

    @Synchronized
    fun take(): String? {
        val value = url
        url = null
        return value?.takeIf { clock.nowMs() - storedAtMs <= VALID_MS }
    }
}
