package com.gt8d.launcher.ui.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gt8d.launcher.ui.home.HomeClockHeader
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
import org.robolectric.annotation.TextLayoutMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class MinuteClockTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var time = LocalDateTime.of(2026, 10, 31, 23, 58, 45)
    private var reads = 0
    private val clock = mutableStateOf<() -> LocalDateTime>({ reads++; time })
    private val composed = mutableStateOf(true)
    private val registered = mutableListOf<BroadcastReceiver>()
    private val released = mutableListOf<BroadcastReceiver>()
    private lateinit var context: Context

    private fun host() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        context = object : ContextWrapper(controller.get()) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
                permission: String?, scheduler: Handler?, flags: Int): Intent? {
                if (receiver != null && filter.hasAction(Intent.ACTION_TIME_TICK)) {
                    assertEquals(setOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED), filter.actionsIterator().asSequence().toSet())
                    assertEquals(Context.RECEIVER_EXPORTED, flags)
                    registered += receiver
                }
                return super.registerReceiver(receiver, filter, permission, scheduler, flags)
            }
            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                if (receiver in registered) released += receiver
                super.unregisterReceiver(receiver)
            }
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        controller.get().setContent {
            if (composed.value) {
                CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                    LauncherTheme {
                        Box(Modifier.width(700.dp)) { HomeClockHeader(160.dp, now = clock.value) }
                    }
                }
            }
        }
        controller.visible()
        rule.waitForIdle()
    }

    private fun state(state: Lifecycle.State) {
        rule.runOnIdle { owner.registry.currentState = state }
        rule.waitForIdle()
    }

    private fun broadcast(action: String) {
        rule.runOnIdle { context.sendBroadcast(Intent(action)) }
        rule.waitForIdle()
    }

    private fun display(clockText: String, date: String = "10月31日(土)") {
        rule.onNodeWithTag("clock").assertTextEquals(clockText).assertIsDisplayed()
        rule.onNodeWithTag("home_date").assertTextEquals(date).assertIsDisplayed()
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Test fun initialRead_isSeparateFromStartedSync_andStartedDoesNotRequireResume() {
        host()
        assertEquals(1, reads)
        assertTrue(registered.isEmpty())
        display("23:58")
        time = time.plusMinutes(1)
        state(Lifecycle.State.STARTED)
        assertEquals(2, reads)
        assertEquals(1, registered.size)
        display("23:59")
        state(Lifecycle.State.RESUMED)
        state(Lifecycle.State.STARTED)
        rule.mainClock.advanceTimeBy(61_000)
        rule.waitForIdle()
        assertEquals(2, reads)
        assertEquals(1, registered.size)
    }

    @Test fun minuteTimeChangeAndTimezoneBroadcasts_updateClockAndDateTogether() {
        host()
        state(Lifecycle.State.STARTED)
        time = time.plusMinutes(1)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(3, reads)
        display("23:59")
        time = time.plusMinutes(1)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(4, reads)
        display("00:00", "11月1日(日)")
        time = LocalDateTime.of(2026, 10, 30, 8, 12)
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals(5, reads)
        display("08:12", "10月30日(金)")
        time = time.minusHours(9)
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        assertEquals(6, reads)
        display("23:12", "10月29日(木)")
        broadcast(Intent.ACTION_DATE_CHANGED) // Not a subscribed clock event.
        assertEquals(6, reads)
    }

    @Test fun stop_rejectsBroadcastsAndQueuedOldReceiver_restartReadsExactlyOnce() {
        host()
        state(Lifecycle.State.STARTED)
        val old = registered.single()
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            time = time.plusHours(1)
            old.onReceive(context, Intent(Intent.ACTION_TIME_TICK))
        }
        rule.waitForIdle()
        assertEquals(listOf(old), released)
        broadcast(Intent.ACTION_TIME_TICK)
        broadcast(Intent.ACTION_TIME_CHANGED)
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        rule.mainClock.advanceTimeBy(61_000)
        assertEquals(2, reads)
        display("23:58")
        state(Lifecycle.State.STARTED)
        assertEquals(3, reads)
        assertEquals(2, registered.size)
        display("00:58", "11月1日(日)")
        rule.runOnIdle { old.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED)) }
        assertEquals(3, reads)
    }

    @Test fun dispose_unregistersAndRejectsQueuedCallbacks_thenNewCompositionReadsFreshTime() {
        host()
        state(Lifecycle.State.STARTED)
        val old = registered.single()
        rule.runOnIdle { composed.value = false }
        rule.waitForIdle()
        assertEquals(listOf(old), released)
        assertEquals(0, owner.registry.observerCount)
        time = time.plusHours(2)
        broadcast(Intent.ACTION_TIME_TICK)
        rule.runOnIdle { old.onReceive(context, Intent(Intent.ACTION_TIME_TICK)) }
        assertEquals(2, reads)
        rule.runOnIdle { composed.value = true }
        rule.waitForIdle()
        assertEquals(4, reads) // A new initial display read plus its START synchronization.
        display("01:58", "11月1日(日)")
        rule.runOnIdle { old.onReceive(context, Intent(Intent.ACTION_TIME_TICK)) }
        assertEquals(4, reads)
    }

    @Test fun rapidStopStartStop_releasesAllIntervals_andOldCallbacksStayInvalidAfterRestart() {
        host()
        state(Lifecycle.State.STARTED)
        rule.runOnIdle {
            repeat(4) {
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.STARTED
            }
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        rule.waitForIdle()
        assertEquals(registered.toSet(), released.toSet())
        assertEquals(registered.size, released.size)
        val stopped = reads
        val old = registered.toList()
        time = time.plusMinutes(1)
        rule.runOnIdle { old.forEach { it.onReceive(context, Intent(Intent.ACTION_TIME_TICK)) } }
        assertEquals(stopped, reads)
        state(Lifecycle.State.STARTED)
        assertEquals(stopped + 1, reads)
        assertEquals(1, registered.size - released.size)
        display("23:59")
        rule.runOnIdle { old.forEach { it.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED)) } }
        assertEquals(stopped + 1, reads)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(stopped + 2, reads)
    }

    @Test fun replacingClock_resyncsOnceWhileStarted_andDoesNotReadWhileStopped() {
        host()
        state(Lifecycle.State.STARTED)
        val old = registered.single()
        var replacementReads = 0
        var replacement = LocalDateTime.of(2027, 1, 2, 10, 20)
        rule.runOnIdle { clock.value = { replacementReads++; replacement } }
        rule.waitForIdle()
        assertEquals(2, reads)
        assertEquals(1, replacementReads)
        assertEquals(listOf(old), released)
        display("10:20", "1月2日(土)")
        replacement = replacement.plusMinutes(1)
        rule.runOnIdle { old.onReceive(context, Intent(Intent.ACTION_TIME_TICK)) }
        assertEquals(1, replacementReads)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(2, replacementReads)
        display("10:21", "1月2日(土)")
        state(Lifecycle.State.CREATED)
        var stoppedClockReads = 0
        rule.runOnIdle { clock.value = { stoppedClockReads++; LocalDateTime.of(2028, 2, 3, 4, 5) } }
        rule.waitForIdle()
        assertEquals(0, stoppedClockReads)
        assertEquals(2, replacementReads)
        state(Lifecycle.State.STARTED)
        assertEquals(1, stoppedClockReads)
        assertEquals(2, reads)
        assertEquals(2, replacementReads)
        display("04:05", "2月3日(木)")
    }
}
