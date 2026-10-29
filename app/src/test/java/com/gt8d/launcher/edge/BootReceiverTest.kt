package com.gt8d.launcher.edge

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.safety.SafetyGuard
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = mutableListOf<String>()
    private val recordingContext = object : ContextWrapper(context) {
        override fun startForegroundService(service: Intent): ComponentName? {
            assertEquals(EdgeStripService::class.java.name, service.component?.className)
            events.add("service")
            return service.component
        }
    }
    private val receiver = BootReceiver().apply { bootModeCheck = { events.add("mode") } }
    private var safetyState: ByteArray? = null
    private val oldCurtain = EdgeStripService.curtainView
    private val oldListener = EdgeStripService.listener
    private val oldConfig = EdgeStripService.gestureConfig

    @Before fun setUp() {
        val file = File(context.filesDir, "safety.json")
        safetyState = if (file.exists()) file.readBytes() else null
        SafetyGuard.clear(context)
    }

    @After fun tearDown() {
        val file = File(context.filesDir, "safety.json")
        safetyState?.let { file.writeBytes(it) } ?: file.delete()
        EdgeStripService.curtainView = oldCurtain
        EdgeStripService.listener = oldListener
        EdgeStripService.gestureConfig = oldConfig
    }

    @Test fun bootCompleted_fallback_skipsServiceAndModeCheck() {
        repeat(3) { SafetyGuard.recordStart(context); SafetyGuard.recordCrash(context) }
        assertTrue(SafetyGuard.isFallbackActive(context))
        receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun bootCompleted_normal_startsServiceThenChecksMode() {
        assertFalse(SafetyGuard.isFallbackActive(context))
        receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(listOf("service", "mode"), events)
    }

    @Test fun otherAction_doesNothing() {
        receiver.onReceive(recordingContext, Intent("unrelated"))
        assertEquals(emptyList<String>(), events)
    }
}
