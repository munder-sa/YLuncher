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
import kotlinx.coroutines.flow.onEach
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
    private val appleConnection: StateFlow<AppleMusicConnection> = MutableStateFlow(AppleMusicConnection()),
    private val appleCache: AppleLyricsCache = AppleLyricsCache(clock),
    private val fetchApple: suspend (LyricsRequest, Long) -> AppleLyricsResult = { _, _ ->
        AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_APPLICABLE)
    },
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
            val connections = appleConnection
                // Runs upstream of collectLatest's cancel-and-join, even for a slow old fetch.
                .onEach { appleCache.activateGeneration(it.generation) }
                .map { it.copy(reason = null, retryAfterSec = null) }
                .distinctUntilChanged()
            combine(keys, enabled.distinctUntilChanged(), connections) { key, on, connection ->
                Triple(key, on, connection)
            }.onEach { (key, on, _) ->
                if (!on || key == null) mutableState.value = LyricsState.Off
            }.collectLatest { (key, on, connection) ->
                    if (!on || key == null) {
                        mutableState.value = LyricsState.Off
                        return@collectLatest
                    }
                    val request = key.second
                    val q = request.query
                    suspend fun checkCurrent() {
                        currentCoroutineContext().ensureActive()
                        val current = appleConnection.value
                        if (current.generation != connection.generation || current.status != connection.status ||
                            current.storefront != connection.storefront
                        ) throw CancellationException("Lyrics connection changed")
                    }
                    checkCurrent()
                    DiagLog.d("Lyrics", "track title=${q.title} artist=${q.artist} album=${q.album} dur=${q.durationSec}")
                    val appleKey = if (key.first == "com.apple.android.music") {
                        AppleLyricsCache.key(request, connection)
                    } else null
                    val appleCached = appleKey?.let { appleCache.get(it) }
                    checkCurrent()
                    if (appleCached?.isWordSuccess() == true) {
                        mutableState.value = appleCached.state
                        logApple(appleCached, cacheHit = true, elapsedMs = 0)
                        logResult(appleCached.state, "APPLE")
                        return@collectLatest
                    }
                    val amllCached = withContext(io) { amllCache.get(request, null) }
                    checkCurrent()
                    if (amllCached?.state.hasWords()) {
                        mutableState.value = amllCached!!.state
                        logResult(amllCached.state, "AMLL")
                        if (appleKey == null) return@collectLatest
                    }
                    val cached = if (amllCached?.state.hasWords()) null else withContext(io) { cache.get(q) }
                    checkCurrent()
                    if (!amllCached?.state.hasWords()) {
                        DiagLog.d("Lyrics", if (cached != null) "cache hit ${cached.javaClass.simpleName}" else "cache miss")
                        mutableState.value = cached ?: LyricsState.Loading
                    }
                    delay(600)
                    checkCurrent()
                    if (appleKey != null) {
                        // Recheck expiry after the debounce; this attempt is outside legacy retries.
                        val suppressed = appleCache.get(appleKey)
                        checkCurrent()
                        val startedAt = clock()
                        val apple = suppressed ?: try {
                            withContext(io) {
                                checkCurrent()
                                fetchApple(request, connection.generation)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            AppleLyricsResult(LyricsState.Failed, AppleLyricsReason.NETWORK)
                        }
                        checkCurrent()
                        if (suppressed == null) appleCache.put(appleKey, apple)
                        checkCurrent()
                        logApple(apple, suppressed != null, (clock() - startedAt).coerceAtLeast(0L))
                        if (apple.isWordSuccess()) {
                            checkCurrent()
                            mutableState.value = apple.state
                            logResult(apple.state, "APPLE")
                            return@collectLatest
                        }
                    }
                    var checkedAmll: AmllResult? = amllCached?.takeIf { it.state.hasWords() }
                    repeat(4) { attempt ->
                        val amll = checkedAmll ?: withContext(io) {
                            checkCurrent()
                            amllCache.get(request, null)
                        }.also { checkCurrent() } ?: if (clock() < (amllAllowedAt[request] ?: 0L)) {
                            AmllResult(LyricsState.Failed, AmllReason.WAIT)
                        } else {
                            val result = try {
                                withContext(io) { checkCurrent(); fetchAmll(request) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                AmllResult(LyricsState.Failed, AmllReason.NETWORK)
                            }
                            checkCurrent()
                            if (result.state == LyricsState.Offline || result.state == LyricsState.Failed) {
                                val seconds = maxOf(60L, result.retryAfterSec ?: 0L)
                                val now = clock()
                                amllAllowedAt[request] = if (seconds > (Long.MAX_VALUE - now) / 1000) {
                                    Long.MAX_VALUE
                                } else now + seconds * 1000
                            } else amllAllowedAt.remove(request)
                            if (result.cacheable) withContext(io) {
                                checkCurrent()
                                amllCache.put(request, null, result)
                            }
                            checkCurrent()
                            result
                        }
                        if (amll.state != LyricsState.Offline && amll.state != LyricsState.Failed) {
                            checkedAmll = amll
                        }
                        checkCurrent()
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
                        checkCurrent()
                        val result = if (cached != null) FetchResult(cached) else try {
                            withContext(io) { checkCurrent(); fetch(q) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            FetchResult(LyricsState.Failed)
                        }
                        checkCurrent()
                        result.retryAfterSec?.let { nextAllowedAt = clock() + it * 1000 }
                        val overall = if (result.state == LyricsState.Missing &&
                            (amll.state == LyricsState.Failed || amll.state == LyricsState.Offline)) {
                            amll.state
                        } else result.state
                        if (cached == null && result.cacheable && (result.state is LyricsState.Synced ||
                                result.state is LyricsState.Plain || result.state == LyricsState.Missing)) {
                            withContext(io) {
                                checkCurrent()
                                cache.put(q, result.state)
                            }
                        }
                        checkCurrent()
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

    private fun AppleLyricsResult.isWordSuccess(): Boolean = reason == AppleLyricsReason.OK && state.hasWords()

    private fun logApple(result: AppleLyricsResult, cacheHit: Boolean, elapsedMs: Long) {
        val accepted = result.isWordSuccess()
        val lines = (result.state as? LyricsState.Synced)?.lines.orEmpty()
        DiagLog.d("Lyrics", "apple result accepted=$accepted reason=${result.reason.name} " +
            "cache=${if (cacheHit) "hit" else "miss"} ms=$elapsedMs " +
            "lines=${if (accepted) lines.size else 0} words=${if (accepted) lines.sumOf { it.words.size } else 0}")
    }

    private fun logResult(state: LyricsState, source: String) {
        DiagLog.d("Lyrics", "amll result ${state.javaClass.simpleName} source=$source")
    }
}
