package com.gt8d.launcher.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicWebDataTest {
    @Test fun constructorDoesNotInitializeBackend() = runTest {
        val backend = Backend()
        val data = AppleMusicWebData(backgroundScope, backend)
        assertEquals(0, backend.checks)
        assertEquals(0, backend.deletes)
        assertEquals(AppleMusicWebDataState.IDLE, data.state.value)
        assertTrue(data.canStart)
        // The production default is equally lazy (no provider access in the constructor).
        assertEquals(AppleMusicWebDataState.IDLE, AppleMusicWebData(backgroundScope).state.value)
    }

    @Test fun closesEveryScreenBeforeDeleteAndRejectsNewViewsUntilCallback() = runTest {
        val backend = Backend()
        val data = AppleMusicWebData(backgroundScope, backend)
        var closed = 0
        repeat(2) {
            assertNotNull(data.register {
                assertFalse(data.canStart)
                assertNull(data.register { fail("New screen must not open during deletion") })
                assertEquals(0, backend.deletes)
                closed++
            })
        }
        val job = data.clear()
        assertEquals(AppleMusicWebDataState.CLEARING, data.state.value)
        assertNull(data.register { fail("Late popup must not register") })
        runCurrent()
        assertEquals(2, closed)
        assertEquals(1, backend.deletes)
        assertFalse(job.isCompleted)
        assertEquals(AppleMusicWebDataState.CLEARING, data.state.value)
        backend.complete()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(AppleMusicWebDataState.CLEARED, data.state.value)
        assertNotNull(data.register {})
    }

    @Test fun repeatedRequestsShareOneDeletionIncludingLateCallback() = runTest {
        val backend = Backend()
        val data = AppleMusicWebData(backgroundScope, backend)
        val first = data.clear()
        assertSame(first, data.clear())
        runCurrent()
        repeat(10) { assertSame(first, data.clear()) }
        assertEquals(1, backend.deletes)
        assertEquals(AppleMusicWebDataState.CLEARING, data.state.value)
        assertFalse(data.canStart)
        backend.complete()
        backend.complete()
        runCurrent()
        assertEquals(AppleMusicWebDataState.CLEARED, data.state.value)
        assertTrue(data.canStart)
    }

    @Test fun unregisterReleasesScreenAndDestructionDoesNotCancelDeletion() = runTest {
        val appScope = CoroutineScope(coroutineContext + SupervisorJob())
        val screenScope = CoroutineScope(coroutineContext + SupervisorJob())
        val backend = Backend()
        val data = AppleMusicWebData(appScope, backend)
        var closed = 0
        val registration = data.register { closed++ }!!
        registration.close()
        registration.close()
        screenScope.cancel()
        val deletion = data.clear()
        runCurrent()
        assertEquals(0, closed)
        assertFalse(deletion.isCompleted)
        backend.complete()
        runCurrent()
        assertEquals(AppleMusicWebDataState.CLEARED, data.state.value)
        assertTrue(deletion.isCompleted)
        // The handle itself no longer retains the Activity callback.
        registration.closeForDeletion()
        assertEquals(0, closed)
        appScope.cancel()
    }

    @Test fun unsupportedBackendFailsWithoutDeleting() = runTest {
        val backend = Backend().apply { supported = false }
        val data = AppleMusicWebData(backgroundScope, backend)
        var closed = false
        data.register { closed = true }
        data.clear()
        runCurrent()
        assertTrue(closed)
        assertEquals(1, backend.checks)
        assertEquals(0, backend.deletes)
        assertEquals(AppleMusicWebDataState.FAILED, data.state.value)
    }

    @Test fun featureAndDeleteExceptionsAreFixedFailureStatesAndRetryWorks() = runTest {
        for (featureError in listOf(true, false)) {
            val backend = Backend().apply { throwOnCheck = featureError; throwOnDelete = !featureError }
            val data = AppleMusicWebData(backgroundScope, backend)
            data.clear()
            runCurrent()
            assertEquals(AppleMusicWebDataState.FAILED, data.state.value)
            assertFalse(data.state.value.toString().contains("synthetic-secret"))
            backend.throwOnCheck = false
            backend.throwOnDelete = false
            data.clear()
            runCurrent()
            assertEquals(AppleMusicWebDataState.CLEARING, data.state.value)
            backend.complete()
            runCurrent()
            assertEquals(AppleMusicWebDataState.CLEARED, data.state.value)
        }
    }

    @Test fun screenCloseFailureDoesNotDeleteWhileOtherScreensStillNeedClosing() = runTest {
        val backend = Backend()
        val data = AppleMusicWebData(backgroundScope, backend)
        var otherClosed = false
        data.register { throw IllegalStateException("synthetic-secret") }
        data.register { otherClosed = true }
        data.clear()
        runCurrent()
        assertTrue(otherClosed)
        assertEquals(0, backend.deletes)
        assertEquals(AppleMusicWebDataState.FAILED, data.state.value)
    }

    @Test fun backupRulesExcludeOnlyWebDataInLegacyCloudAndDeviceTransfer() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val expected = setOf(
            "root" to "app_webview",
            "sharedpref" to "WebViewChromiumPrefs.xml",
            "sharedpref" to "WebViewProfilePrefsDefault.xml",
            "sharedpref" to "AwOriginVisitLoggerPrefs.xml",
            "database" to "http_auth.db",
        )
        for ((resource, sections) in listOf(
            R.xml.apple_music_backup_rules to setOf("full-backup-content"),
            R.xml.apple_music_data_extraction_rules to setOf("cloud-backup", "device-transfer"),
        )) {
            val actual = mutableMapOf<String, MutableList<Pair<String, String>>>()
            resources.getXml(resource).use { xml ->
                var section = ""
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG) continue
                    if (xml.name in sections) { section = xml.name; actual[section] = mutableListOf() }
                    assertNotEquals("include", xml.name)
                    if (xml.name == "exclude") actual.getValue(section).add(
                        xml.getAttributeValue(null, "domain") to xml.getAttributeValue(null, "path"))
                }
            }
            assertEquals(sections, actual.keys)
            for (rules in actual.values) {
                assertEquals(5, rules.size)
                assertEquals(expected, rules.toSet())
                assertFalse(rules.any { it.second in listOf(".", "", "config.json", "split_mode.json") })
                assertFalse(rules.any { it.first == "file" })
            }
        }
    }

    private class Backend : AppleMusicWebData.Backend {
        var checks = 0
        var deletes = 0
        var supported = true
        var throwOnCheck = false
        var throwOnDelete = false
        private var callback: (() -> Unit)? = null
        override fun supported(): Boolean {
            checks++
            if (throwOnCheck) throw IllegalStateException("synthetic-secret")
            return supported
        }
        override fun delete(onComplete: () -> Unit) {
            deletes++
            if (throwOnDelete) throw IllegalStateException("synthetic-secret")
            callback = onComplete
        }
        fun complete() { checkNotNull(callback).invoke() }
    }
}
