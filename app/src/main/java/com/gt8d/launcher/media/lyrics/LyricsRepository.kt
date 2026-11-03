package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsQuery
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
) {
    private val mutableState = MutableStateFlow<LyricsState>(LyricsState.Off)
    val state: StateFlow<LyricsState> = mutableState.asStateFlow()
    private var nextAllowedAt = 0L

    init {
        scope.launch {
            val keys = nowPlaying.map { now ->
                now?.lyricsQuery?.let { Pair(now.pkg, it) }
            }.distinctUntilChanged()
            combine(keys, enabled.distinctUntilChanged()) { key, on -> key to on }
                .collectLatest { (key, on) ->
                    if (!on || key == null) {
                        mutableState.value = LyricsState.Off
                        return@collectLatest
                    }
                    val q = key.second
                    val cached = withContext(io) { cache.get(q) }
                    if (cached != null) {
                        mutableState.value = cached
                        return@collectLatest
                    }
                    mutableState.value = LyricsState.Loading
                    delay(600)
                    repeat(4) { attempt ->
                        delay((nextAllowedAt - clock()).coerceAtLeast(0L))
                        val result = try {
                            fetch(q)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            FetchResult(LyricsState.Failed)
                        }
                        currentCoroutineContext().ensureActive()
                        result.retryAfterSec?.let { nextAllowedAt = clock() + it * 1000 }
                        mutableState.value = result.state
                        when (result.state) {
                            is LyricsState.Synced, is LyricsState.Plain, LyricsState.Missing -> {
                                withContext(io) { cache.put(q, result.state) }
                                return@collectLatest
                            }
                            LyricsState.Offline, LyricsState.Failed -> {
                                if (attempt < 3) delay(30_000)
                            }
                            else -> return@collectLatest
                        }
                    }
                }
        }
    }
}
