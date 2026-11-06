package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.positionAt
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.media.lyrics.LyricWord
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterludeBoundaryUiTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val owner = Owner()
    private val observer = LyricsPaneObserver()
    private val snapshots = SnapshotStateObserver { it() }
    private val drawn = linkedSetOf<InterludeDrawProbe>()
    private val invalidations = mutableMapOf<InterludeDrawProbe, Int>()
    private val changed: (InterludeDrawProbe) -> Unit = { invalidations[it] = invalidations.getOrDefault(it, 0) + 1 }
    private var reads = 0
    private var clockOffset = 0L
    private val time: Long get() = rule.mainClock.currentTime + clockOffset
    private var now by mutableStateOf(NowPlaying("dummy", "曲", "人", null, 3000L, true, false,
        positionUpdatedAt = 0L, metaTitle = "曲"))
    private var lyrics by mutableStateOf(LyricsState.Synced(listOf(LyricLine(0, "♪"))))

    private fun show(detail: Boolean = false) {
        rule.mainClock.autoAdvance = false
        snapshots.start()
        observer.observeInterludeDraw = { probe, draw ->
            snapshots.observeReads(probe, changed, draw)
            drawn += probe
        }
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        controller.get().setContent {
            LauncherTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalLyricsPaneObserver provides observer, LocalLyricsDetailSemantics provides detail,
                    LocalDensity provides Density(1f)) {
                    LyricsPane(lyrics, now, Modifier.size(329.dp, 240.dp)) { reads++; time }
                }
            }
        }
        controller.visible()
        advance(64)
    }

    @After fun cleanup() {
        observer.observeInterludeDraw = null
        snapshots.stop()
        snapshots.clear()
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
        rule.waitForIdle()
    }

    private fun advanceTo(ms: Long) {
        assertTrue("time=${rule.mainClock.currentTime}, target=$ms", ms >= rule.mainClock.currentTime)
        advance(ms - rule.mainClock.currentTime)
    }

    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun change(update: () -> Unit) {
        rule.runOnIdle { update(); Snapshot.sendApplyNotifications() }
        advance(64)
    }

    private fun draw(): InterludeDrawProbe? {
        rule.runOnIdle {
            drawn.clear()
            val view = controller.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try { view.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        return drawn.singleOrNull { it.current }
    }

    private fun checkBoundaryFrames(detail: Boolean) {
        show(detail)
        val probe = requireNotNull(draw())
        assertEquals(0, probe.lit)
        assertEquals(0, observer.frameUpdates)
        val initialReads = reads
        val reservations = observer.interludeReservations
        val changes = invalidations.getOrDefault(probe, 0)
        advance(800)
        assertEquals(initialReads + 4, reads) // Only the unchanged 200 ms index checks.
        assertEquals(reservations, observer.interludeReservations)
        assertEquals(0, observer.frameUpdates)
        assertEquals(changes, invalidations.getOrDefault(probe, 0))
        assertSame(probe, draw())
        assertEquals(0, probe.lit)
        for (lit in 1..3) {
            advanceTo(lit * 1000L - 8L)
            assertEquals(lit - 1, observer.frameUpdates)
            advanceTo(lit * 1000L)
            // delay alone never publishes a position or pretends to be a frame.
            assertEquals(lit - 1, observer.frameUpdates)
            frame()
            assertEquals(lit, observer.frameUpdates)
            assertSame(probe, draw())
            assertEquals(lit, probe.lit)
            assertEquals(changes + lit, invalidations.getOrDefault(probe, 0))
        }
        val finalReservations = observer.interludeReservations
        advance(2000)
        assertEquals(3, observer.frameUpdates)
        assertEquals(finalReservations, observer.interludeReservations)
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
    }

    @Test fun windowSleepsBetweenDotsAndPublishesOnlyOnBoundaryFrames() = checkBoundaryFrames(false)
    @Test fun detailSleepsBetweenDotsAndPublishesOnlyOnBoundaryFrames() = checkBoundaryFrames(true)

    @Test fun introKeepsBoth200msChecksButSleepsUntilTheDotBoundary() {
        lyrics = LyricsState.Synced(listOf(LyricLine(12000, "最初")))
        now = now.copy(durationMs = 20000)
        show()
        val probe = requireNotNull(draw())
        assertEquals(0, probe.lit)
        val initialReads = reads
        advance(800)
        assertEquals(initialReads + 8, reads) // Intro and index, each every 200 ms.
        assertEquals(0, observer.frameUpdates)
        advanceTo(4000)
        frame()
        assertEquals(1, observer.frameUpdates)
        assertEquals(1, requireNotNull(draw()).lit)
        advanceTo(7500)
        assertNull(draw()) // Existing intro cutoff cancels the pending 8000 ms boundary.
        val reservations = observer.interludeReservations
        advanceTo(9000)
        assertEquals(1, observer.frameUpdates)
        assertEquals(reservations, observer.interludeReservations)
    }

    @Test fun pauseSeekAndResumeCancelTheOldBoundaryAndSynchronizeImmediately() {
        show()
        val first = requireNotNull(draw())
        advanceTo(400)
        change { now = now.copy(playing = false, positionMs = 1500) }
        assertSame(first, draw())
        assertEquals(1, first.lit)
        val pausedReads = reads
        val reservations = observer.interludeReservations
        advanceTo(1400)
        assertEquals(pausedReads, reads)
        assertEquals(0, observer.frameUpdates)
        assertEquals(reservations, observer.interludeReservations)
        change { now = now.copy(positionMs = 2500) }
        assertEquals(2, requireNotNull(draw()).lit)
        val resumedAt = time
        change { now = now.copy(playing = true, positionUpdatedAt = resumedAt) }
        assertEquals(0, observer.frameUpdates)
        advanceTo(resumedAt + 500)
        frame()
        assertEquals(1, observer.frameUpdates)
        assertEquals(3, requireNotNull(draw()).lit)
    }

    @Test fun seekWhilePlayingCancelsAPendingFrameAsWellAsItsDelay() {
        show()
        advanceTo(1000) // The old delay has completed, but its frame has not run.
        assertEquals(0, observer.frameUpdates)
        change { now = now.copy(positionMs = 100, positionUpdatedAt = time) }
        val probe = requireNotNull(draw())
        assertEquals(0, probe.lit)
        val frames = observer.frameUpdates
        advanceTo(1800)
        assertEquals(frames, observer.frameUpdates)
        assertEquals(0, requireNotNull(draw()).lit)
        advanceTo(1900)
        frame()
        assertEquals(frames + 1, observer.frameUpdates)
        assertEquals(1, requireNotNull(draw()).lit)
    }

    @Test fun speedAndMetadataChangesReplaceThePendingDelay() {
        show()
        advanceTo(400)
        change { now = now.copy(positionMs = now.positionAt(time), positionUpdatedAt = time, speed = 0.5f) }
        val reservations = observer.interludeReservations
        change { now = now.copy(album = "更新", positionMs = now.positionAt(time), positionUpdatedAt = time) }
        assertTrue(observer.interludeReservations > reservations)
        advanceTo(1200) // Old speed would have crossed 1000 already.
        assertEquals(0, observer.frameUpdates)
        assertEquals(0, requireNotNull(draw()).lit)
        advanceTo(1600)
        frame()
        assertEquals(1, observer.frameUpdates)
        assertEquals(1, requireNotNull(draw()).lit)
        change { now = now.copy(positionMs = now.positionAt(time), positionUpdatedAt = time, speed = 2f) }
        val boundaryTime = now.positionUpdatedAt + (2000L - now.positionMs) / 2L
        advanceTo(boundaryTime)
        frame()
        assertEquals(2, observer.frameUpdates)
        assertEquals(2, requireNotNull(draw()).lit)
    }

    @Test fun trackAndLyricChangesCancelReservationsAndReplaceDrawIdentity() {
        show()
        val old = requireNotNull(draw())
        advanceTo(400)
        change {
            now = now.copy(title = "次", metaTitle = "次", positionMs = 0, positionUpdatedAt = time,
                durationMs = 9000)
            lyrics = LyricsState.Synced(listOf(LyricLine(0, "♪"), LyricLine(9000, "次")))
        }
        val next = requireNotNull(draw())
        assertNotSame(old, next)
        assertEquals(9000L, next.endMs)
        advanceTo(1400)
        assertEquals(0, observer.frameUpdates)
        assertEquals(0, requireNotNull(draw()).lit)
        change { lyrics = LyricsState.Synced(listOf(LyricLine(0, "固定"), LyricLine(9000, "次"))) }
        assertNull(draw())
        val reservations = observer.interludeReservations
        advanceTo(4000)
        assertEquals(0, observer.frameUpdates)
        assertEquals(reservations, observer.interludeReservations)
    }

    @Test fun stopCancelsReadsAndPendingFrameAndStartSynchronizesWithoutResume() {
        show()
        advanceTo(1000)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        val stoppedReads = reads
        val reservations = observer.interludeReservations
        advanceTo(2500)
        assertEquals(stoppedReads, reads)
        assertEquals(0, observer.frameUpdates)
        assertEquals(reservations, observer.interludeReservations)
        change { owner.registry.currentState = Lifecycle.State.STARTED }
        assertEquals(2, requireNotNull(draw()).lit)
        assertEquals(0, observer.frameUpdates) // Initial synchronization is not a frame callback.
        assertTrue(reads > stoppedReads)
        advanceTo(3000)
        frame()
        assertEquals(1, observer.frameUpdates)
        assertEquals(3, requireNotNull(draw()).lit)
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
    }

    @Test fun zeroSpeedAndUnreachableDurationDoNotKeepReserving() {
        now = now.copy(speed = 0f, durationMs = 500)
        lyrics = LyricsState.Synced(listOf(LyricLine(0, "♪"), LyricLine(3000, "次")))
        show()
        assertEquals(0, requireNotNull(draw()).lit)
        assertEquals(0, observer.interludeReservations)
        advance(2000)
        assertEquals(0, observer.frameUpdates)
        assertEquals(0, observer.interludeReservations)
        change { now = now.copy(speed = 1f, positionUpdatedAt = time) }
        advance(2000)
        assertEquals(0, observer.frameUpdates)
        assertEquals(0, observer.interludeReservations)
        assertEquals(0, requireNotNull(draw()).lit)
    }

    @Test fun earlyWakeRechecksPositionAndWaitsWithoutPublishingOrSpinning() {
        show()
        rule.runOnIdle { clockOffset = -1 }
        advanceTo(1000)
        assertEquals(0, observer.frameUpdates)
        val reservations = observer.interludeReservations
        val earlyReads = reads
        assertTrue(reservations > 1)
        rule.waitForIdle()
        assertEquals(reservations, observer.interludeReservations)
        assertEquals(earlyReads, reads)
        advance(1)
        assertEquals(0, observer.frameUpdates)
        frame()
        assertEquals(1, observer.frameUpdates)
        assertEquals(1, requireNotNull(draw()).lit)
    }

    @Test fun unsupportedSpeedsAndWordsStillUseFrameInterpolation() {
        now = now.copy(positionMs = 2500, speed = -1f)
        show()
        assertEquals(2, requireNotNull(draw()).lit)
        var frames = observer.frameUpdates
        advance(64)
        assertEquals(frames + 4, observer.frameUpdates)
        assertEquals(0, observer.interludeReservations)
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            change { now = now.copy(speed = speed, positionUpdatedAt = time) }
            frames = observer.frameUpdates
            advance(64)
            assertEquals(frames + 4, observer.frameUpdates)
            assertEquals(interludeLitDots(0, 3000, now.positionAt(time)), requireNotNull(draw()).lit)
        }
        change {
            now = now.copy(speed = 1f, positionMs = 0, positionUpdatedAt = time)
            lyrics = LyricsState.Synced(listOf(LyricLine(0, "単語", listOf(LyricWord(0, 0)))))
        }
        frames = observer.frameUpdates
        advance(64)
        assertEquals(frames + 4, observer.frameUpdates)
        assertEquals(0, observer.interludeReservations)
    }
}
