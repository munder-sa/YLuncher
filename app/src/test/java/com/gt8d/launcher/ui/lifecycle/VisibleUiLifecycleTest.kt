package com.gt8d.launcher.ui.lifecycle

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.media.lyrics.LyricWord
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.home.HomeClockHeader
import com.gt8d.launcher.ui.home.LocalLyricsPaneObserver
import com.gt8d.launcher.ui.home.LyricsPane
import com.gt8d.launcher.ui.home.LyricsPaneObserver
import com.gt8d.launcher.ui.home.rememberLyricIndex
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VisibleUiLifecycleTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            Shadows.shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = cn.className; packageName = cn.packageName
            })
            Shadows.shadowOf(app.packageManager).addIntentFilterForActivity(cn,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private val owner = Owner()
    private val composed = mutableStateOf(true)
    private val now = NowPlaying("dummy", "Dummy", "Dummy", null, 120_000L, true, false,
        positionMs = 0, positionUpdatedAt = 0, speed = 1f)

    private fun state(state: Lifecycle.State) {
        rule.runOnIdle { owner.registry.currentState = state }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
    }

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.setContent {
            if (composed.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) { content() }
        }
        rule.mainClock.advanceTimeBy(64)
    }

    @Test fun delayedWork_startsWithoutResume_stopsAndRestartsImmediately_disposesOnce() {
        var ticks = 0
        var starts = 0
        var stops = 0
        var source = 1
        var displayed = 0
        host {
            VisibleUiEffect(Unit) {
                starts++
                try {
                    while (true) {
                        ticks++
                        displayed = source
                        delay(500)
                    }
                } finally { stops++ }
            }
        }
        assertEquals(0, ticks)
        state(Lifecycle.State.STARTED)
        assertEquals(1, ticks)
        assertEquals(1, displayed)
        state(Lifecycle.State.RESUMED)
        state(Lifecycle.State.STARTED) // Split-screen pause must keep the same job.
        assertEquals(1, starts)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(2, ticks)
        state(Lifecycle.State.CREATED)
        val stopped = ticks
        source = 9
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(stopped, ticks)
        assertEquals(1, stops)
        state(Lifecycle.State.STARTED)
        assertEquals(9, displayed)
        assertEquals(stopped + 1, ticks)
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.DESTROYED
            composed.value = false
        }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(starts, stops)
        val disposed = ticks
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(disposed, ticks)
        assertEquals(0, owner.registry.observerCount)
    }

    @Test fun lyricIndex_doesNotSearchWhileStopped_resyncsAtStart_andUpdatesWhileOnlyStarted() {
        var time = 500L
        var reads = 0
        lateinit var index: State<Int>
        val lines = listOf(1_000L, 2_000L, 3_000L).map { LyricLine(it, "Dummy") }
        host { index = rememberLyricIndex(now, lines) { reads++; time } }
        assertEquals(0, reads)
        state(Lifecycle.State.STARTED)
        assertEquals(-1, index.value)
        time = 2_100
        rule.mainClock.advanceTimeBy(200)
        assertEquals(1, index.value)
        state(Lifecycle.State.CREATED)
        val stopped = reads
        time = 3_100
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(stopped, reads)
        assertEquals(1, index.value)
        state(Lifecycle.State.STARTED)
        assertEquals(2, index.value)
        assertEquals(stopped + 1, reads)
    }

    @Test fun lyricPositionFrames_stopEvenWhenComposeFrameClockKeepsRunning() {
        val observer = LyricsPaneObserver()
        var time = 1_000L
        var reads = 0
        val lines = listOf(LyricLine(0, "Dummy", listOf(LyricWord(0, 0))))
        host {
            CompositionLocalProvider(LocalLyricsPaneObserver provides observer) {
                LyricsPane(LyricsState.Synced(lines), now, clock = { reads++; time })
            }
        }
        state(Lifecycle.State.STARTED)
        rule.mainClock.advanceTimeBy(200)
        assertTrue(observer.frameUpdates > 0)
        state(Lifecycle.State.CREATED)
        val stoppedFrames = observer.frameUpdates
        val stoppedReads = reads
        time = 4_000
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(stoppedFrames, observer.frameUpdates)
        assertEquals(stoppedReads, reads)
        state(Lifecycle.State.STARTED)
        assertTrue(observer.frameUpdates > stoppedFrames)
        assertTrue(reads > stoppedReads)
    }

    @Test fun lyricIntroDelay_stopsAndRechecksLatestPositionOnStart() {
        val observer = LyricsPaneObserver()
        var time = 1_000L
        var reads = 0
        val lines = listOf(LyricLine(20_000, "Dummy"))
        host {
            CompositionLocalProvider(LocalLyricsPaneObserver provides observer) {
                LyricsPane(LyricsState.Synced(lines), now, clock = { reads++; time })
            }
        }
        state(Lifecycle.State.STARTED)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(0, observer.frameUpdates)
        state(Lifecycle.State.CREATED)
        val stopped = reads
        time = 18_000 // Intro dots are no longer needed, but the first line has not started.
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(stopped, reads)
        state(Lifecycle.State.STARTED)
        val frames = observer.frameUpdates
        val resumedReads = reads
        rule.mainClock.advanceTimeBy(400)
        assertEquals(frames, observer.frameUpdates)
        assertTrue(reads > resumedReads) // The 200 ms checks remain active in STARTED.
    }

    @Test fun homeClock_stopsPollingAndReadsLatestTimeOnStart() {
        var reads = 0
        var time = LocalDateTime.of(2026, 1, 1, 12, 0)
        val clock = { reads++; time }
        host { HomeClockHeader(100.dp, now = clock) }
        state(Lifecycle.State.STARTED)
        rule.mainClock.advanceTimeBy(1_000)
        state(Lifecycle.State.CREATED)
        val stopped = reads
        time = time.plusHours(1)
        rule.mainClock.advanceTimeBy(3_000)
        assertEquals(stopped, reads)
        state(Lifecycle.State.STARTED)
        assertEquals(stopped + 1, reads)
    }
}
