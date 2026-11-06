package com.gt8d.launcher.foreground

import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.sets.SetRunnerDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AsyncForegroundReadTest {
    private class Events : ForegroundEvents {
        var permitted = true
        var fails = false
        override fun available() = permitted
        override fun resumedSince(sinceMs: Long): List<ResumeEvent> {
            if (fails) throw ForegroundEventsUnavailableException()
            return emptyList()
        }
    }

    @Test fun olderSuccessCannotRestoreAvailabilityAfterNewerFailure() {
        val events = Events()
        val watcher = ForegroundWatcher(events)
        val old = watcher.prepareRead(0).read()
        events.fails = true
        assertNull(watcher.lastForegroundSince(0))
        assertFalse(watcher.available())
        assertFalse(watcher.commitRead(old))
        assertFalse(watcher.available())
    }

    @Test fun olderFailureCannotOverrideNewerEmptySuccess() {
        val events = Events().apply { fails = true }
        val watcher = ForegroundWatcher(events)
        val old = watcher.prepareRead(0).read()
        events.fails = false
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        assertTrue(watcher.available())
        assertFalse(watcher.commitRead(old))
        assertTrue(watcher.available())
    }

    @Test fun availabilityChecksDoNotInvalidateRequestAndEachResultCommitsOnce() {
        val watcher = ForegroundWatcher(Events())
        val result = watcher.prepareRead(0).read()
        repeat(5) { assertTrue(watcher.available()) }
        assertTrue(watcher.commitRead(result))
        assertFalse(watcher.commitRead(result))
    }

    @Test fun issuedNewerRequestAlreadyInvalidatesOlderResult() {
        val watcher = ForegroundWatcher(Events())
        val old = watcher.prepareRead(0).read()
        val fresh = watcher.prepareRead(10)
        assertFalse(watcher.commitRead(old))
        assertTrue(watcher.commitRead(fresh.read()))
    }

    @Test fun unavailableSnapshotAndFailureAreDistinctFromSuccessfulEmptyRead() {
        val events = Events().apply { permitted = false }
        val watcher = ForegroundWatcher(events)
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        events.permitted = true
        assertFalse(watcher.available())
        assertEquals(emptySet<String>(), watcher.foregroundPkgsSince(0))
        assertTrue(watcher.available())
    }

    @Test fun workerAndMainDeliveryAreSeparateAndWorkerDoesNotCommit() {
        val main = TestCoroutineScheduler()
        val worker = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(main))
        try {
            val events = Events()
            val watcher = ForegroundWatcher(events)
            val query = watcher.prepareRead(0)
            var reads = 0
            var deliveries = 0
            AsyncForegroundRead(scope, StandardTestDispatcher(worker), StandardTestDispatcher(main))
                .launch({ reads++; query.read() }) {
                    deliveries++
                    assertTrue(watcher.commitRead(it))
                }
            events.fails = true
            main.runCurrent()
            assertEquals(0, reads)
            worker.runCurrent()
            assertEquals(1, reads)
            assertEquals(0, deliveries)
            assertTrue(watcher.available())
            main.runCurrent()
            assertEquals(1, deliveries)
            assertFalse(watcher.available())
        } finally {
            scope.cancel()
        }
    }

    @Test fun appGraphRunnerUsesApplicationPollWithRealWorkerAndMainDelivery() {
        val main = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(main))
        try {
            AppGraph.setFrequentAppsForTest(null, false)
            AppGraph.init(ApplicationProvider.getApplicationContext())
            val deps = AppGraph.runner.javaClass.getDeclaredField("d").apply { isAccessible = true }
                .get(AppGraph.runner) as SetRunnerDeps
            val poll = deps.foregroundPoll ?: deps.watcher.poll
            assertTrue(poll is AsyncForegroundRead)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val callingThread = Thread.currentThread()
            var workerThread: Thread? = null
            var deliveryThread: Thread? = null
            val query = ForegroundWatcher(Events()).prepareRead(0)
            poll.launch({
                workerThread = Thread.currentThread()
                started.countDown()
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    query.read()
                } finally {
                    finished.countDown()
                }
            }) { deliveryThread = Thread.currentThread() }
            try {
                main.runCurrent()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertNotSame(callingThread, workerThread)
                assertNull(deliveryThread)
            } finally {
                release.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            // The worker enqueues its continuation after returning from read().
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (deliveryThread == null && System.nanoTime() < deadline) {
                main.runCurrent()
                Thread.yield()
            }
            assertSame(callingThread, deliveryThread)
        } finally {
            AppGraph.clearFrequentAppsForTest()
            Dispatchers.resetMain()
        }
    }
}
