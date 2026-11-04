package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.lyricsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsRepository(
    scope: CoroutineScope,
    nowPlaying: StateFlow<NowPlaying?>,
    enabled: Flow<Boolean>,
    private val cache: LyricsCache,
    private val fetch: suspend (LyricsQuery) -> FetchResult,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
    private val amllCache: AmllCache,
    private val fetchAmll: suspend (LyricsRequest) -> AmllResult,
) {
    private val mutableState = MutableStateFlow<LyricsState>(LyricsState.Off)
    val state: StateFlow<LyricsState> = mutableState.asStateFlow()
    private var nextAllowedAt = 0L
    private val amllAllowedAt = mutableMapOf<LyricsRequest, Long>()

    init {
        scope.launch {
            val keys = nowPlaying.map { now ->
                now?.lyricsRequest?.let { Pair(now.pkg, it) }
            }.distinctUntilChanged()
            combine(keys, enabled.distinctUntilChanged()) { key, on -> key to on }
                .collectLatest { (key, on) ->
                    if (!on || key == null) {
                        mutableState.value = LyricsState.Off
                        return@collectLatest
                    }
                    val request = key.second
                    val q = request.query
                    DiagLog.d("Lyrics", "track title=${q.title} artist=${q.artist} album=${q.album} dur=${q.durationSec}")
                    val amllCached = withContext(io) { amllCache.get(request, null) }
                    if (amllCached?.state.hasWords()) {
                        mutableState.value = amllCached!!.state
                        logResult(amllCached.state, "AMLL")
                        return@collectLatest
                    }
                    val cached = withContext(io) { cache.get(q) }
                    DiagLog.d("Lyrics", if (cached != null) "cache hit ${cached.javaClass.simpleName}" else "cache miss")
                    mutableState.value = cached ?: LyricsState.Loading
                    delay(600)
                    var checkedAmll: AmllResult? = null
                    repeat(4) { attempt ->
                        val amll = checkedAmll ?: withContext(io) {
                            currentCoroutineContext().ensureActive()
                            amllCache.get(request, null)
                        } ?: if (clock() < (amllAllowedAt[request] ?: 0L)) {
                            AmllResult(LyricsState.Failed, AmllReason.WAIT)
                        } else {
                            val result = try {
                                withContext(io) { fetchAmll(request) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                AmllResult(LyricsState.Failed, AmllReason.NETWORK)
                            }
                            currentCoroutineContext().ensureActive()
                            if (result.state == LyricsState.Offline || result.state == LyricsState.Failed) {
                                val seconds = maxOf(60L, result.retryAfterSec ?: 0L)
                                val now = clock()
                                amllAllowedAt[request] = if (seconds > (Long.MAX_VALUE - now) / 1000) {
                                    Long.MAX_VALUE
                                } else now + seconds * 1000
                            } else amllAllowedAt.remove(request)
                            if (result.cacheable) withContext(io) {
                                currentCoroutineContext().ensureActive()
                                amllCache.put(request, null, result)
                            }
                            result
                        }
                        if (amll.state != LyricsState.Offline && amll.state != LyricsState.Failed) {
                            checkedAmll = amll
                        }
                        currentCoroutineContext().ensureActive()
                        if (amll.state.hasWords()) {
                            mutableState.value = amll.state
                            logResult(amll.state, "AMLL")
                            return@collectLatest
                        }
                        if (cached is LyricsState.Synced || cached is LyricsState.Plain) {
                            mutableState.value = cached
                            logResult(cached, "LRCLIB")
                            return@collectLatest
                        }
                        delay((nextAllowedAt - clock()).coerceAtLeast(0L))
                        val result = if (cached != null) FetchResult(cached) else try {
                            withContext(io) { fetch(q) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            FetchResult(LyricsState.Failed)
                        }
                        currentCoroutineContext().ensureActive()
                        result.retryAfterSec?.let { nextAllowedAt = clock() + it * 1000 }
                        val overall = if (result.state == LyricsState.Missing &&
                            (amll.state == LyricsState.Failed || amll.state == LyricsState.Offline)) {
                            amll.state
                        } else result.state
                        if (cached == null && result.cacheable && (result.state is LyricsState.Synced ||
                                result.state is LyricsState.Plain || result.state == LyricsState.Missing)) {
                            withContext(io) {
                                currentCoroutineContext().ensureActive()
                                cache.put(q, result.state)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        mutableState.value = overall
                        logResult(overall, if (overall == amll.state && overall != result.state) "AMLL" else "LRCLIB")
                        when (overall) {
                            is LyricsState.Synced, is LyricsState.Plain, LyricsState.Missing -> {
                                return@collectLatest
                            }
                            LyricsState.Offline, LyricsState.Failed -> {
                                if (attempt < 3) {
                                    val wait = maxOf(listOf(10_000L, 30_000L, 60_000L)[attempt], nextAllowedAt - clock())
                                    DiagLog.d("Lyrics", "retry in $wait attempt=${attempt + 1}")
                                    delay(wait)
                                } else DiagLog.d("Lyrics", "retry exhausted")
                            }
                            else -> return@collectLatest
                        }
                    }
                }
        }
    }

    private fun LyricsState?.hasWords(): Boolean =
        this is LyricsState.Synced && lines.any { it.words.isNotEmpty() }

    private fun logResult(state: LyricsState, source: String) {
        DiagLog.d("Lyrics", "amll result ${state.javaClass.simpleName} source=$source")
    }
}
