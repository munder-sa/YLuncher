package com.gt8d.launcher.apps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.config.DockMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FrequentAppsRefresherTest {
    private val owners = mutableListOf<FrequentAppsRefresher>()

    private class Source {
        var elapsed = 0L
        var wall = 10L * 24 * 60 * 60 * 1000
        var granted = true
        var permissionCalls = 0
        var eligibleCalls = 0
        val begins = mutableListOf<Long>()
        var launches: List<UsageLaunch>? = listOf(UsageLaunch("a", 1))
        var check: (suspend () -> Boolean)? = null
        var read: (suspend () -> List<UsageLaunch>?)? = null
        var ranked: List<FrequentApp>? = null
        var available = false
        val logs = mutableListOf<Pair<Int?, List<FrequentApp>?>>()

        fun create(
            dispatcher: CoroutineDispatcher,
            publish: (List<FrequentApp>?, Boolean) -> Unit = { value, allowed ->
                ranked = value
                available = allowed
            },
        ) = FrequentAppsRefresher(
            available = { permissionCalls++; check?.invoke() ?: granted },
            launchesSince = {
                begins.add(it)
                val query = read
                if (query == null) launches else query()
            },
            eligible = { eligibleCalls++; setOf("a", "b") },
            publish = publish,
            dispatcher = dispatcher,
            elapsedRealtime = { elapsed },
            wallTime = { wall },
            log = { count, value -> logs.add(count to value) },
        )
    }

    private fun owner(source: Source, dispatcher: CoroutineDispatcher) =
        source.create(dispatcher).also { owners.add(it) }

    @After fun close() {
        AppGraph.setFrequentRefresherFactoryForTest(null)
        AppGraph.clearFrequentAppsForTest()
        owners.forEach { it.close() }
    }

    @Test fun firstRequestIsImmediateAndTenHomeReturnsReuseSuccess() = runTest {
        val source = Source()
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        assertNull(source.ranked)
        assertFalse(source.available)
        refresher.refresh()
        assertEquals(0, source.permissionCalls)
        assertEquals(0, source.begins.size)
        runCurrent()
        val first = source.ranked
        assertEquals(listOf(FrequentApp("a", 1)), first)
        repeat(10) {
            source.elapsed += 5_000
            source.launches = listOf(UsageLaunch("b", 2))
            refresher.refresh()
            runCurrent()
            assertSame(first, source.ranked)
        }
        assertEquals(11, source.permissionCalls)
        assertEquals(1, source.begins.size)
        assertEquals(1, source.eligibleCalls)
        assertEquals(listOf(1 to first), source.logs)
    }

    @Test fun successTtlStartsAtCompletionAndExpiresAtExactlySixtySeconds() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.read = { release.await(); source.launches }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        assertNull(source.ranked)
        source.elapsed = 20_000
        release.complete(Unit); runCurrent()
        source.launches = listOf(UsageLaunch("b", 2))
        source.elapsed = 79_999
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
        source.elapsed = 80_000
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        assertEquals(listOf(FrequentApp("b", 1)), source.ranked)
    }

    @Test fun wallClockJumpsDoNotChangeTtlAndEveryQueryUsesSevenDays() = runTest {
        val source = Source()
        val initialWall = source.wall
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        source.wall += 100L * 24 * 60 * 60 * 1000
        refresher.refresh(); runCurrent()
        source.wall = -100_000L
        source.elapsed = 59_999
        refresher.refresh(); runCurrent()
        assertEquals(listOf(initialWall - 604_800_000L), source.begins)
        source.elapsed = 60_000
        refresher.refresh(); runCurrent()
        assertEquals(listOf(initialWall - 604_800_000L, source.wall - 604_800_000L), source.begins)
    }

    @Test fun overlappingRequestsCheckPermissionButShareOnePendingQuery() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.read = { release.await(); source.launches }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        repeat(10) { refresher.refresh() }
        runCurrent()
        assertEquals(10, source.permissionCalls)
        assertEquals(1, source.begins.size)
        source.elapsed = 100_000
        repeat(10) { refresher.refresh() }
        runCurrent()
        assertEquals(20, source.permissionCalls)
        assertEquals(1, source.begins.size)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
        assertEquals(1, source.logs.size)
    }

    @Test fun revokedPermissionClearsCachedRankingAndRegrantRefreshesInsideTtl() = runTest {
        val source = Source()
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        source.elapsed = 1_000
        source.granted = false
        refresher.refresh(); runCurrent()
        assertNull(source.ranked)
        assertFalse(source.available)
        assertEquals(1, source.begins.size)
        source.granted = true
        source.launches = listOf(UsageLaunch("b", 2))
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        assertTrue(source.available)
        assertEquals(listOf(FrequentApp("b", 1)), source.ranked)
    }

    @Test fun initialDenialDoesNotQueryOrLogAndRegrantIsImmediate() = runTest {
        val source = Source().apply { granted = false }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        repeat(10) { refresher.refresh(); runCurrent() }
        assertEquals(10, source.permissionCalls)
        assertEquals(0, source.begins.size)
        assertEquals(0, source.logs.size)
        assertNull(source.ranked)
        assertFalse(source.available)
        source.granted = true
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
    }

    @Test fun nullResultFallsBackToManualAndRetriesAtFiveSecondsFromCompletion() = runTest {
        val source = Source()
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        val release = CompletableDeferred<Unit>()
        source.launches = null
        source.read = { release.await(); null }
        source.elapsed = 60_000
        refresher.refresh(); runCurrent()
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
        source.elapsed = 80_000
        release.complete(Unit); runCurrent()
        assertNull(source.ranked)
        assertTrue(source.available)
        assertEquals(listOf("b"), dockAppsFor(DockMode.Auto, listOf("b"), source.ranked, setOf("a", "b")))
        repeat(10) { refresher.refresh(); runCurrent() }
        source.elapsed = 84_999
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        source.elapsed = 85_000
        refresher.refresh(); runCurrent()
        assertEquals(3, source.begins.size)
        assertNull(source.ranked)
        source.read = null
        source.launches = listOf(UsageLaunch("b", 2))
        source.elapsed = 89_999
        refresher.refresh(); runCurrent()
        assertEquals(3, source.begins.size)
        source.elapsed = 90_000
        refresher.refresh(); runCurrent()
        assertEquals(4, source.begins.size)
        assertEquals(listOf(FrequentApp("b", 1)), source.ranked)
    }

    @Test fun exceptionIsNotCachedAsSuccessAndPermissionChangesClearRetryDelay() = runTest {
        val source = Source().apply { read = { error("query failed") } }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        assertNull(source.ranked)
        assertTrue(source.available)
        source.elapsed = 4_999
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
        source.elapsed = 5_000
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        source.granted = false
        refresher.refresh(); runCurrent()
        assertFalse(source.available)
        source.granted = true
        source.read = null
        refresher.refresh(); runCurrent()
        assertEquals(3, source.begins.size)
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
    }

    @Test fun thrownCancellationPropagatesAndRetriesOnlyAfterFiveSeconds() = runTest {
        val source = Source()
        lateinit var queryJob: Job
        source.read = {
            queryJob = currentCoroutineContext()[Job]!!
            throw CancellationException("query cancelled")
        }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        assertTrue(queryJob.isCancelled)
        assertNull(source.ranked)
        assertTrue(source.available)
        assertEquals(0, source.logs.size)
        source.read = null
        source.elapsed = 4_999
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
        source.elapsed = 5_000
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        assertEquals(listOf(FrequentApp("a", 1)), source.ranked)
    }

    @Test fun cancelledPendingQueryUsesCancellationCompletionForRetry() = runTest {
        val source = Source()
        lateinit var queryJob: Job
        source.read = {
            queryJob = currentCoroutineContext()[Job]!!
            awaitCancellation()
        }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        source.elapsed = 30_000
        queryJob.cancel(); runCurrent()
        assertTrue(queryJob.isCancelled)
        assertNull(source.ranked)
        source.read = null
        source.elapsed = 34_999
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
        source.elapsed = 35_000
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
    }

    @Test fun emptyRankingIsSuccessfulAndRetained() = runTest {
        val source = Source().apply { launches = emptyList() }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        assertEquals(emptyList<FrequentApp>(), source.ranked)
        assertTrue(source.available)
        source.elapsed = 5_000
        refresher.refresh(); runCurrent()
        assertEquals(1, source.begins.size)
    }

    @Test fun lateRevokedQueryCannotOverwriteRegrantOrClearNewPendingQuery() = runTest {
        val source = Source()
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        lateinit var oldJob: Job
        source.read = {
            oldJob = currentCoroutineContext()[Job]!!
            withContext(NonCancellable) { oldRelease.await() }
            listOf(UsageLaunch("a", 1))
        }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        source.granted = false
        refresher.refresh(); runCurrent()
        assertNull(source.ranked)
        assertFalse(source.available)
        source.granted = true
        source.read = { newRelease.await(); listOf(UsageLaunch("b", 2)) }
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        oldRelease.complete(Unit); runCurrent()
        assertTrue(oldJob.isCancelled)
        assertNull(source.ranked)
        source.elapsed = 10_000
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
        newRelease.complete(Unit); runCurrent()
        assertEquals(listOf(FrequentApp("b", 1)), source.ranked)
        refresher.refresh(); runCurrent()
        assertEquals(2, source.begins.size)
    }

    @Test fun slowPermissionCheckCannotUndoNewerRevocation() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.check = { release.await(); true }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        source.check = null
        source.granted = false
        refresher.refresh(); runCurrent()
        release.complete(Unit); runCurrent()
        assertNull(source.ranked)
        assertFalse(source.available)
        assertEquals(0, source.begins.size)
    }

    @Test fun closePreventsLatePublicationAndFurtherReads() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.read = { withContext(NonCancellable) { release.await() }; source.launches }
        val refresher = owner(source, StandardTestDispatcher(testScheduler))
        refresher.refresh(); runCurrent()
        refresher.close()
        source.ranked = listOf(FrequentApp("replacement", 9))
        release.complete(Unit); runCurrent()
        refresher.refresh(); runCurrent()
        assertEquals(listOf(FrequentApp("replacement", 9)), source.ranked)
        assertEquals(1, source.permissionCalls)
        assertEquals(1, source.begins.size)
    }

    @Test fun permissionQueryEligibilityAndRankingRunOnWorker() = runTest {
        val caller = Thread.currentThread()
        val threads = CopyOnWriteArrayList<Thread>()
        val completed = CompletableDeferred<List<FrequentApp>>()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val events = object : AbstractList<UsageLaunch>() {
            override val size = 2
            override fun get(index: Int): UsageLaunch {
                threads.add(Thread.currentThread())
                return UsageLaunch(if (index == 0) "a" else "b", index.toLong())
            }
        }
        val refresher = FrequentAppsRefresher(
            available = { threads.add(Thread.currentThread()); true },
            launchesSince = { threads.add(Thread.currentThread()); events },
            eligible = { threads.add(Thread.currentThread()); setOf("a", "b") },
            publish = { ranked, _ -> if (ranked != null) completed.complete(ranked) },
            dispatcher = dispatcher,
            elapsedRealtime = { 0L },
            wallTime = { 0L },
            log = { _, _ -> },
        )
        try {
            refresher.refresh()
            val ranked = withContext(kotlinx.coroutines.Dispatchers.Default) {
                withTimeout(5_000) { completed.await() }
            }
            assertEquals(listOf(FrequentApp("b", 1), FrequentApp("a", 1)), ranked)
            assertTrue(threads.size > 3)
            assertTrue(threads.all { it !== caller })
            assertEquals(1, threads.toSet().size)
        } finally {
            refresher.close()
            dispatcher.close()
        }
    }

    @Test fun appGraphRetainsHelperOnSameApplicationAndRefreshesAfterContextChange() = runTest {
        val source = Source()
        val contexts = mutableListOf<Application>()
        AppGraph.setFrequentRefresherFactoryForTest { context, publish ->
            contexts.add(context)
            source.create(StandardTestDispatcher(testScheduler), publish)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppGraph.init(app); runCurrent()
        val first = AppGraph.frequentApps.value
        repeat(10) { AppGraph.init(app); AppGraph.refreshFrequentApps(); runCurrent() }
        assertEquals(1, contexts.size)
        assertEquals(1, source.begins.size)
        assertEquals(21, source.permissionCalls)
        assertSame(first, AppGraph.frequentApps.value)
        assertTrue(AppGraph.usageAvailable.value)
        val replacement = Application()
        source.launches = listOf(UsageLaunch("b", 2))
        AppGraph.init(replacement)
        assertNull(AppGraph.frequentApps.value)
        assertFalse(AppGraph.usageAvailable.value)
        runCurrent()
        assertEquals(listOf(app, replacement), contexts)
        assertEquals(2, source.begins.size)
        assertEquals(listOf(FrequentApp("b", 1)), AppGraph.frequentApps.value)
    }

    @Test fun appGraphContextChangeRejectsLateOldResult() = runTest {
        val old = Source()
        val fresh = Source().apply { launches = listOf(UsageLaunch("b", 2)) }
        val release = CompletableDeferred<Unit>()
        old.read = { withContext(NonCancellable) { release.await() }; old.launches }
        var created = 0
        AppGraph.setFrequentRefresherFactoryForTest { _, publish ->
            (if (created++ == 0) old else fresh).create(StandardTestDispatcher(testScheduler), publish)
        }
        AppGraph.init(ApplicationProvider.getApplicationContext()); runCurrent()
        AppGraph.init(Application()); runCurrent()
        assertEquals(listOf(FrequentApp("b", 1)), AppGraph.frequentApps.value)
        release.complete(Unit); runCurrent()
        AppGraph.refreshFrequentApps(); runCurrent()
        assertEquals(listOf(FrequentApp("b", 1)), AppGraph.frequentApps.value)
        assertEquals(1, old.begins.size)
        assertEquals(1, fresh.begins.size)
    }

    @Test fun appGraphOverrideAndResetRejectOldJobsAndResetCachedSuccess() = runTest {
        val source = Source()
        val release = CompletableDeferred<Unit>()
        source.read = { withContext(NonCancellable) { release.await() }; listOf(UsageLaunch("a", 1)) }
        AppGraph.setFrequentRefresherFactoryForTest { _, publish ->
            source.create(StandardTestDispatcher(testScheduler), publish)
        }
        AppGraph.init(ApplicationProvider.getApplicationContext()); runCurrent()
        val override = listOf(FrequentApp("override", 9))
        AppGraph.setFrequentAppsForTest(override, false)
        AppGraph.refreshFrequentApps(); runCurrent()
        assertEquals(1, source.begins.size)
        assertSame(override, AppGraph.frequentApps.value)
        assertFalse(AppGraph.usageAvailable.value)
        AppGraph.clearFrequentAppsForTest()
        assertNull(AppGraph.frequentApps.value)
        source.read = null
        source.launches = listOf(UsageLaunch("b", 2))
        AppGraph.refreshFrequentApps(); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(listOf(FrequentApp("b", 1)), AppGraph.frequentApps.value)
        assertEquals(2, source.begins.size)
        AppGraph.clearFrequentAppsForTest()
        AppGraph.refreshFrequentApps(); runCurrent()
        assertEquals(3, source.begins.size)
        assertEquals(listOf(FrequentApp("b", 1)), AppGraph.frequentApps.value)
    }
}
