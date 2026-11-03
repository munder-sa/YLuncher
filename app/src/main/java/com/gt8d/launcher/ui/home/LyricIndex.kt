package com.gt8d.launcher.ui.home

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.media.lyrics.LrcParser
import com.gt8d.launcher.media.lyrics.LyricLine
import kotlinx.coroutines.delay

@Composable
fun rememberLyricIndex(
    now: NowPlaying,
    lines: List<LyricLine>,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
): State<Int> {
    val index = remember { mutableIntStateOf(-1) }
    LaunchedEffect(now, lines) {
        index.intValue = LrcParser.currentIndex(lines, now.positionAt(clock()))
        while (now.playing) {
            delay(200)
            index.intValue = LrcParser.currentIndex(lines, now.positionAt(clock()))
        }
    }
    return index
}
