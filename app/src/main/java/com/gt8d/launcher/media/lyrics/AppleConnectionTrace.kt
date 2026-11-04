package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.diag.DiagLog

/** Accepts only closed labels and bounded counts; never retains request or response data. */
internal class AppleConnectionTrace(
    private val operation: Operation,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit = { DiagLog.d("AppleConnect", it) },
) {
    enum class Operation { CONNECT, PUBLIC_REFRESH }

    enum class Stage {
        START, REQUEST_MUTEX_WAIT, REQUEST_MUTEX_ACQUIRED,
        PUBLIC_BEARER_START, PUBLIC_BEARER_END, ACCOUNT_REQUEST_START, ACCOUNT_REQUEST_END,
        PAGE_REQUEST_START, PAGE_REQUEST_END, PAGE_TOKEN_PARSE_START, PAGE_TOKEN_PARSE_END,
        ASSET_LIST_PARSE_START, ASSET_LIST_PARSE_END, ASSET_REQUEST_START, ASSET_REQUEST_END,
        ASSET_TOKEN_PARSE_START, ASSET_TOKEN_PARSE_END, END,
    }

    enum class Reason {
        READY, CANCELLED, OK, NOT_APPLICABLE, AUTH_REQUIRED, NOT_FOUND, UNSUPPORTED, INVALID,
        NETWORK, HTTP, WAIT, TIMEOUT, TOKEN_UNAVAILABLE, TOO_LARGE, TOKEN_NOT_FOUND,
    }

    private val startedAt = try { nanoTime() } catch (_: Exception) { null }

    init {
        mark(Stage.START)
    }

    fun mark(stage: Stage, httpCode: Int? = null, bodyBytes: Int? = null, asset: Int? = null) {
        emit(stage, httpCode, bodyBytes, asset, null)
    }

    fun finish(reason: Reason) = emit(Stage.END, null, null, null, reason)

    fun finish(reason: AppleLyricsReason) = finish(when (reason) {
        AppleLyricsReason.OK -> Reason.OK
        AppleLyricsReason.NOT_APPLICABLE -> Reason.NOT_APPLICABLE
        AppleLyricsReason.AUTH_REQUIRED -> Reason.AUTH_REQUIRED
        AppleLyricsReason.NOT_FOUND -> Reason.NOT_FOUND
        AppleLyricsReason.UNSUPPORTED -> Reason.UNSUPPORTED
        AppleLyricsReason.INVALID -> Reason.INVALID
        AppleLyricsReason.NETWORK -> Reason.NETWORK
        AppleLyricsReason.HTTP -> Reason.HTTP
        AppleLyricsReason.WAIT -> Reason.WAIT
        AppleLyricsReason.TIMEOUT -> Reason.TIMEOUT
        AppleLyricsReason.TOKEN_UNAVAILABLE -> Reason.TOKEN_UNAVAILABLE
    })

    fun finish(reason: AppleWebTokenFailure) = finish(when (reason) {
        AppleWebTokenFailure.NETWORK -> Reason.NETWORK
        AppleWebTokenFailure.HTTP -> Reason.HTTP
        AppleWebTokenFailure.TOO_LARGE -> Reason.TOO_LARGE
        AppleWebTokenFailure.TOKEN_NOT_FOUND -> Reason.TOKEN_NOT_FOUND
        AppleWebTokenFailure.TIMEOUT -> Reason.TIMEOUT
        AppleWebTokenFailure.WAIT -> Reason.WAIT
    })

    private fun emit(stage: Stage, httpCode: Int?, bodyBytes: Int?, asset: Int?, reason: Reason?) {
        try {
            val elapsedMs = startedAt?.let { (nanoTime() - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L
            val line = buildString {
                append("op=${operation.name} stage=${stage.name} elapsedMs=$elapsedMs")
                httpCode?.takeIf { it in 100..599 }?.let { append(" code=$it") }
                bodyBytes?.let { append(" bytes=${it.coerceIn(0, 16 * 1024 * 1024)}") }
                asset?.takeIf { it in 1..8 }?.let { append(" asset=$it") }
                reason?.let { append(" reason=${it.name}") }
            }
            sink(line)
        } catch (_: Exception) {
            // A diagnostic sink (including one throwing CancellationException) cannot change the operation.
        }
    }

    override fun toString() = "AppleConnectionTrace(op=${operation.name})"
}
