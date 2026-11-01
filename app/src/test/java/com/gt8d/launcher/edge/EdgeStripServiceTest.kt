package com.gt8d.launcher.edge

import android.app.Service
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.view.Gravity
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.SlotPreview
import com.gt8d.launcher.safety.SafetyGuard
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeStripServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var safetyState: ByteArray? = null
    private val oldCurtain = EdgeStripService.curtainView
    private val oldListener = EdgeStripService.listener
    private val oldConfig = EdgeStripService.gestureConfig
    private var controller: ServiceController<EdgeStripService>? = null

    @Before fun setUp() {
        val file = File(context.filesDir, "safety.json")
        safetyState = if (file.exists()) file.readBytes() else null
        SafetyGuard.clear(context)
        EdgeStripService.curtainView = null
        EdgeStripService.listener = null
    }

    @After fun tearDown() {
        controller?.destroy()
        val file = File(context.filesDir, "safety.json")
        safetyState?.let { file.writeBytes(it) } ?: file.delete()
        EdgeStripService.curtainView = oldCurtain
        EdgeStripService.listener = oldListener
        EdgeStripService.gestureConfig = oldConfig
    }

    private fun fallback() {
        repeat(3) { SafetyGuard.recordStart(context); SafetyGuard.recordCrash(context) }
        assertTrue(SafetyGuard.isFallbackActive(context))
    }

    private class RecordingFacade(val real: WindowManagerFacade? = null) : WindowManagerFacade {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        var failFirstLeft = false
        override fun addView(view: View, params: WindowManager.LayoutParams) {
            added.add(view)
            if (failFirstLeft && added.size == 1) throw IllegalStateException("left failed")
            real?.addView(view, params)
        }
        override fun removeView(view: View) {
            removed.add(view)
            real?.removeView(view)
        }
    }

    private fun build(): EdgeStripService {
        val built = Robolectric.buildService(EdgeStripService::class.java)
        controller = built
        return built.get()
    }

    private fun target() = CurtainTarget("test", SlotPreview.Panel, SlotPreview.Panel, SplitRatio.R5_5, 0, 1)

    @Test fun strips_havePersistentWhiteHandlesAndWhiteGlow() {
        val service = build()
        val facade = RecordingFacade()
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        assertEquals(2, facade.added.size)
        facade.added.forEachIndexed { index, strip ->
            val container = strip as FrameLayout
            assertEquals(1f, container.alpha, 0f)
            assertEquals(2, container.childCount)
            val glow = container.getChildAt(0)
            assertEquals(0f, glow.alpha, 0f)
            assertEquals(Color.argb(77, 255, 255, 255), (glow.background as ColorDrawable).color)
            val handle = container.getChildAt(1)
            assertEquals(View.VISIBLE, handle.visibility)
            assertEquals(0.25f, handle.alpha, 0f)
            val params = handle.layoutParams as FrameLayout.LayoutParams
            assertEquals(4, params.width)
            assertEquals(96, params.height)
            assertEquals(Gravity.CENTER_VERTICAL or if (index == 0) Gravity.RIGHT else Gravity.LEFT, params.gravity)
            assertEquals(if (index == 0) 8 else 0, params.rightMargin)
            assertEquals(if (index == 0) 0 else 8, params.leftMargin)
            val background = handle.background as GradientDrawable
            assertEquals(Color.WHITE, background.color!!.defaultColor)
            assertEquals(2f, background.cornerRadius, 0f)
        }
    }

    @Test fun strips_glowDisabled_keepsHandlesVisibleOnTouch() {
        EdgeStripService.gestureConfig = oldConfig.copy(glow = false)
        val service = build()
        val facade = RecordingFacade()
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        facade.added.forEach { strip ->
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            try { assertTrue(strip.dispatchTouchEvent(event)) } finally { event.recycle() }
            val container = strip as FrameLayout
            assertEquals(1f, container.alpha, 0f)
            assertEquals(0f, container.getChildAt(0).alpha, 0f)
            assertEquals(0.25f, container.getChildAt(1).alpha, 0f)
            assertEquals(View.VISIBLE, container.getChildAt(1).visibility)
        }
    }

    @Test fun ensureRunning_fallback_doesNotStartService() {
        fallback()
        var starts = 0
        val recording = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                starts++
                return service.component
            }
        }
        EdgeStripService.ensureRunning(recording)
        assertEquals(0, starts)
    }

    @Test fun create_fallback_stopsWithoutOverlayInitialization() {
        fallback()
        val service = build()
        var factories = 0
        service.windowManagerFactory = { factories++; RecordingFacade() }
        service.overlayAllowed = { true }
        controller!!.create()
        assertEquals(0, factories)
        assertNull(EdgeStripService.curtainView)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test fun startCommand_fallback_releasesOverlaysAndReturnsNotSticky() {
        val service = build()
        val facade = RecordingFacade()
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        val notifications = service.getSystemService(NotificationManager::class.java)
        assertEquals(1, notifications.activeNotifications.size)
        EdgeStripService.curtainView!!.show(target())
        val views = facade.added.distinct()
        assertEquals(3, views.size)
        facade.removed.clear()
        fallback()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        views.forEach { view -> assertEquals(1, facade.removed.count { it === view }) }
        assertNull(EdgeStripService.curtainView)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertEquals(0, notifications.activeNotifications.size)
        controller!!.destroy()
        controller = null
        views.forEach { view -> assertEquals(1, facade.removed.count { it === view }) }
    }

    @Test fun startCommand_permissionGranted_attachesMissingStrips() {
        val service = build()
        val facade = RecordingFacade()
        var allowed = false
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { allowed }
        controller!!.create()
        assertEquals(0, facade.added.size)
        allowed = true
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(2, facade.added.size)
        assertNotSame(facade.added[0], facade.added[1])
        service.onStartCommand(null, 0, 2)
        assertEquals(2, facade.added.size)
    }

    @Test fun release_cancelsPendingLongPress() {
        val service = build()
        val facade = RecordingFacade()
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        val actions = mutableListOf<EdgeAction>()
        EdgeStripService.listener = { actions.add(it) }
        facade.added.forEach { strip ->
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            try {
                strip.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        controller!!.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(EdgeStripService.gestureConfig.longPressMs.toLong() + 1),
        )
        assertTrue(actions.isEmpty())
    }

    @Test fun startCommand_retriesOnlyFailedStrip() {
        val service = build()
        val facade = RecordingFacade().apply { failFirstLeft = true }
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        assertEquals(2, facade.added.size)
        val left = facade.added[0]
        val right = facade.added[1]
        service.onStartCommand(null, 0, 1)
        assertEquals(2, facade.added.count { it === left })
        assertEquals(1, facade.added.count { it === right })
        service.onStartCommand(null, 0, 2)
        assertEquals(3, facade.added.size)
    }

    @Test fun destroy_removesAttachedCurtainAndStrips() {
        val service = build()
        val facade = RecordingFacade(AndroidWindowManagerFacade(service.getSystemService(WindowManager::class.java)))
        service.windowManagerFactory = { facade }
        service.overlayAllowed = { true }
        controller!!.create()
        EdgeStripService.curtainView!!.show(target())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        val views = facade.added.distinct()
        assertEquals(3, views.size)
        views.forEach { assertTrue(it.isAttachedToWindow) }
        val curtain = views.filterIsInstance<ComposeView>().single()
        assertTrue(curtain.childCount > 0)
        val owner = curtain.findViewTreeLifecycleOwner()!!
        controller!!.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
        views.forEach { assertFalse(it.isAttachedToWindow) }
        assertNull(EdgeStripService.curtainView)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
