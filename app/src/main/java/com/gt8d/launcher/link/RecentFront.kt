package com.gt8d.launcher.link

object RecentFront {
    const val RECENT_MS = 1_000L

    // リンクの受け口が開くとき、下の画面はその直前に一時停止するため。
    fun isFront(resumed: Boolean, pausedAtMs: Long?, nowMs: Long): Boolean =
        resumed || (pausedAtMs != null && nowMs - pausedAtMs in 0L..RECENT_MS)
}
