package com.gt8d.launcher.edge

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.GestureConfig
import com.gt8d.launcher.safety.SafetyGuard
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeStripWidthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var safety: ByteArray? = null
    private var controller: ServiceController<EdgeStripService>? = null
    private class RecordingFacade : WindowManagerFacade {
        val widths = mutableListOf<Int>()
        var removes = 0
        override fun addView(view: View, params: WindowManager.LayoutParams) { widths += params.width }
        override fun removeView(view: View) { removes++ }
    }
    @Before fun prepare() {
        val file = File(context.filesDir, "safety.json")
        safety = file.takeIf { it.exists() }?.readBytes()
        SafetyGuard.clear(context)
        EdgeStripService.gestureConfig = GestureConfig()
    }
    @After fun cleanup() {
        controller?.destroy()
        EdgeStripService.gestureConfig = GestureConfig()
        val file = File(context.filesDir, "safety.json")
        safety?.let { file.writeBytes(it) } ?: file.delete()
    }
    private fun start(): RecordingFacade {
        val facade = RecordingFacade()
        controller = Robolectric.buildService(EdgeStripService::class.java)
        controller!!.get().windowManagerFactory = { facade }
        controller!!.get().overlayAllowed = { true }
        controller!!.create()
        assertEquals(listOf(8, 8), facade.widths)
        return facade
    }
    @Test fun applyGesture_widthChanged_reattachesWithNewWidth() {
        val facade = start()
        val thread = Thread { EdgeStripService.applyGesture(GestureConfig(stripWidthPx = 24)) }
        thread.start(); thread.join()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(8, 8, 24, 24), facade.widths); assertEquals(2, facade.removes)
    }
    @Test fun applyGesture_sameWidth_doesNotReattach() {
        val facade = start()
        EdgeStripService.applyGesture(GestureConfig(glow = false, longPressMs = 750))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(8, 8), facade.widths); assertEquals(0, facade.removes)
        assertEquals(GestureConfig(glow = false, longPressMs = 750), EdgeStripService.gestureConfig)
    }
    @Test fun applyGesture_noService_onlyStores() {
        val cfg = GestureConfig(stripWidthPx = 32)
        EdgeStripService.applyGesture(cfg)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(cfg, EdgeStripService.gestureConfig)
    }
}
