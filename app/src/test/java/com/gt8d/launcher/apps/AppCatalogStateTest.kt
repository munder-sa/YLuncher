package com.gt8d.launcher.apps

import android.content.ComponentName
import com.gt8d.launcher.ui.apps.AppListEntry
import com.gt8d.launcher.ui.panel.AppInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCatalogStateTest {
    private class Source : AppCatalogSource {
        var lists = 0
        var infos = 0
        var fail = false
        var label = "A"
        var hasA = true
        var failInfo = false
        var onEntries: (suspend () -> Unit)? = null
        var onInfo: (suspend () -> Unit)? = null
        override suspend fun entries(): List<AppListEntry> {
            lists++
            check(!fail)
            val captured = label
            onEntries?.also { onEntries = null }?.invoke()
            return if (hasA) listOf(AppListEntry("a", "Main", captured)) else emptyList()
        }
        override suspend fun info(pkg: String, installed: Set<String>): AppInfo {
            infos++
            check(!failInfo)
            val captured = label
            onInfo?.also { onInfo = null }?.invoke()
            return AppInfo(pkg, captured, null, pkg in installed)
        }
        override suspend fun settings(entries: List<AppListEntry>): ComponentName? = null
    }

    @Test fun initialRequest_isDeferredAndShared_resumeKeepsSuccessfulData() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); state.start()
        assertEquals(0, source.lists)
        assertFalse(state.snapshot.value.ready)
        advanceUntilIdle()
        assertEquals(1, source.lists)
        state.request(setOf("a")); state.request(setOf("a"))
        advanceUntilIdle()
        assertEquals(1, source.infos)
        val before = state.snapshot.value
        state.resume(); advanceUntilIdle()
        assertEquals(before, state.snapshot.value)
        assertEquals(1, source.lists)
        state.close()
    }

    @Test fun invalidation_refreshesOnlyTarget_andCloseStopsRequests() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        state.request(setOf("a", "b")); advanceUntilIdle()
        val b = state.snapshot.value.infos["b"]
        source.label = "B"
        state.invalidate("a"); advanceUntilIdle()
        assertEquals("B", state.snapshot.value.infos["a"]?.label)
        assertSame(b, state.snapshot.value.infos["b"])
        assertEquals(3, source.infos)
        state.close()
        state.invalidate("a"); state.resume(); state.request(setOf("c"))
        advanceUntilIdle()
        assertEquals(2, source.lists)
        assertEquals(3, source.infos)
    }

    @Test fun failure_retainsSuccess_initialFailureRetriesOnResume() = runTest {
        val source = Source().apply { fail = true }
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        assertFalse(state.snapshot.value.ready)
        assertFalse(state.snapshot.value.loading)
        source.fail = false
        state.resume(); advanceUntilIdle()
        val before = state.snapshot.value
        source.fail = true
        state.invalidate("a"); advanceUntilIdle()
        assertTrue(state.snapshot.value.ready)
        assertEquals(before.entries, state.snapshot.value.entries)
        assertEquals(before.installed, state.snapshot.value.installed)
        state.close()
    }

    @Test fun slowList_doesNotPublishOldGeneration_orDropLatestRequest() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        source.onEntries = { source.label = "B"; state.invalidate("a") }
        state.start(); advanceUntilIdle()
        assertEquals(2, source.lists)
        assertEquals("B", state.snapshot.value.entries.single().label)
        state.close()
    }

    @Test fun slowIcon_replacementAndAtoBtoA_discardOldCompletion() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        source.onInfo = {
            source.label = "B"; state.invalidate("a")
            source.label = "A"; state.invalidate("a")
        }
        state.request(setOf("a")); advanceUntilIdle()
        assertEquals(2, source.infos)
        assertEquals("A", state.snapshot.value.infos["a"]?.label)
        assertEquals("A", state.snapshot.value.entries.single().label)
        assertEquals(2, source.lists)
        state.close()
    }

    @Test fun oldIconFinishingAfterNewIcon_cannotOverwriteNewSnapshot() = runTest {
        val source = Source()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        source.onInfo = { release.await() }
        state.request(setOf("a")); advanceUntilIdle()
        assertFalse("a" in state.snapshot.value.infos)
        source.label = "B"
        state.invalidate("a"); advanceUntilIdle()
        assertEquals("B", state.snapshot.value.info("a").label)
        val revision = state.snapshot.value.appRevision
        release.complete(Unit); advanceUntilIdle()
        assertEquals("B", state.snapshot.value.info("a").label)
        assertEquals(revision, state.snapshot.value.appRevision)
        state.close()
    }

    @Test fun close_cancelsSuspendedWork_withoutPublishingItsResult() = runTest {
        val source = Source()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        source.onEntries = {
            try { release.await() } finally { cancelled = true }
        }
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        assertTrue(state.snapshot.value.loading)
        state.close(); advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(AppCatalogSnapshot(), state.snapshot.value)
        release.complete(Unit); advanceUntilIdle()
        assertEquals(AppCatalogSnapshot(), state.snapshot.value)
    }

    @Test fun removalAndReplacement_invalidateMembershipAndPendingInfo() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); state.request(setOf("a")); advanceUntilIdle()
        assertTrue(state.snapshot.value.info("a").installed)
        source.hasA = false
        state.invalidate("a"); advanceUntilIdle()
        assertFalse(state.snapshot.value.info("a").installed)
        assertTrue(state.snapshot.value.entries.isEmpty())
        source.hasA = true
        source.label = "Replacement"
        state.invalidate("a"); advanceUntilIdle()
        assertTrue(state.snapshot.value.info("a").installed)
        assertEquals("Replacement", state.snapshot.value.info("a").label)
        assertEquals(3, source.infos)
        state.close()
    }

    @Test fun iconFailure_isSharedUntilItsGenerationIsInvalidated() = runTest {
        val source = Source().apply { failInfo = true }
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); state.request(setOf("a")); advanceUntilIdle()
        state.request(setOf("a")); state.resume(); advanceUntilIdle()
        assertEquals(1, source.infos)
        assertNull(state.snapshot.value.info("a").icon)
        assertTrue(state.snapshot.value.info("a").installed)
        source.failInfo = false
        state.invalidate("a"); advanceUntilIdle()
        assertEquals(2, source.infos)
        assertEquals("A", state.snapshot.value.info("a").label)
        state.close()
    }

    @Test fun configurationInvalidatesIcons_butResumeDoesNot() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        state.start(); advanceUntilIdle()
        state.request(setOf("a")); advanceUntilIdle()
        source.label = "Localized"
        state.invalidateConfiguration(); advanceUntilIdle()
        assertEquals(2, source.infos)
        assertEquals("Localized", state.snapshot.value.info("a").label)
        state.resume(); advanceUntilIdle()
        assertEquals(2, source.infos)
        assertEquals(2, source.lists)
        state.close()
    }

    @Test fun cancellation_isNotPublishedAsSuccessfulEmptyList_andCanRetry() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        source.onEntries = { throw kotlinx.coroutines.CancellationException("cancelled") }
        state.start(); advanceUntilIdle()
        assertFalse(state.snapshot.value.ready)
        assertFalse(state.snapshot.value.loading)
        state.resume(); advanceUntilIdle()
        assertTrue(state.snapshot.value.ready)
        assertEquals(setOf("a"), state.snapshot.value.installed)
        state.close()
    }

    @Test fun cancellationOfOldList_doesNotDropNewerEvent() = runTest {
        val source = Source()
        val state = AppCatalogState(source, StandardTestDispatcher(testScheduler))
        source.onEntries = {
            state.invalidate("a")
            throw kotlinx.coroutines.CancellationException("old request cancelled")
        }
        state.start(); advanceUntilIdle()
        assertTrue(state.snapshot.value.ready)
        assertEquals(setOf("a"), state.snapshot.value.installed)
        assertEquals(2, source.lists)
        state.close()
    }

    @Test fun queryAndIconConversion_runOnInjectedWorker() = runTest {
        val caller = Thread.currentThread()
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val source = Source().apply {
            onEntries = { threads.add(Thread.currentThread()) }
            onInfo = { threads.add(Thread.currentThread()) }
        }
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val state = AppCatalogState(source, dispatcher)
            state.start(); state.request(setOf("a"))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5000) { state.snapshot.first { "a" in it.infos } }
            }
            assertEquals(2, threads.size)
            assertTrue(threads.all { it !== caller })
            state.close()
        } finally { dispatcher.close() }
    }
}
