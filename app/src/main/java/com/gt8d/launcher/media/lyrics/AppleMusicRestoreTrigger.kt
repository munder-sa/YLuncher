package com.gt8d.launcher.media.lyrics

import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

internal fun observeAppleMusicRestore(
    scope: CoroutineScope,
    nowPlaying: StateFlow<NowPlaying?>,
    enabled: Flow<Boolean>,
    requestRestore: () -> Unit,
): Job = nowPlaying.map { it?.let { playing -> playing.pkg to playing.lyricsRequest } }
    .distinctUntilChanged()
    .combine(enabled.distinctUntilChanged()) { metadata, on -> metadata to on }
    .onEach { (metadata, on) ->
        val id = metadata?.second?.identity?.appleMusicId
        if (on && metadata?.first == "com.apple.android.music" && !id.isNullOrEmpty() && id.length <= 20 &&
            id.all { it in '0'..'9' } && id.any { it != '0' }
        ) requestRestore()
    }.launchIn(scope)
