package com.gt8d.launcher.edge

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.curtain.SlotPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurtainWindowTest {

    private class FakeWindowManagerFacade(
        private val throwOnRemove: Boolean = false,
    ) : WindowManagerFacade {
        val added = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
        val removed = mutableListOf<View>()

        override fun addView(view: View, params: WindowManager.LayoutParams) {
            added.add(view to params)
        }

        override fun removeView(view: View) {
            removed.add(view)
            if (throwOnRemove) {
                throw IllegalArgumentException("View not attached")
            }
        }
    }

    private fun sampleTarget(): CurtainTarget = CurtainTarget(
        setName = "セット1",
        left = SlotPreview.App("com.google.android.youtube", "YouTube"),
        right = SlotPreview.Panel,
        ratio = SplitRatio.R6_4,
        position = 0,
        count = 3,
    )

    @Test
    fun show_addsTouchBlockingOverlay_andReaddsStrips() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val wm = FakeWindowManagerFacade()
        var readdCount = 0
        val window = CurtainWindow(
            context = context,
            wm = wm,
            contentFactory = { ComposeView(it) },
            readdStrips = { readdCount++ },
        )

        window.show(sampleTarget())

        assertEquals(1, wm.added.size)
        val params = wm.added.single().second
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertEquals(1, readdCount)
    }

    @Test
    fun hide_removesWindow() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val wm = FakeWindowManagerFacade()
        val window = CurtainWindow(
            context = context,
            wm = wm,
            contentFactory = { ComposeView(it) },
            readdStrips = {},
        )

        window.show(sampleTarget())
        window.hide(HideReason.Ready)
        Shadows.shadowOf( android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertEquals(1, wm.removed.size)
    }

    @Test
    fun hide_removesWindow_evenIfRemoveThrowsOnce() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val wm = FakeWindowManagerFacade(throwOnRemove = true)
        val window = CurtainWindow(
            context = context,
            wm = wm,
            contentFactory = { ComposeView(it) },
            readdStrips = {},
        )

        window.show(sampleTarget())
        window.hide(HideReason.Ready)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertEquals(1, wm.removed.size)
    }
}
