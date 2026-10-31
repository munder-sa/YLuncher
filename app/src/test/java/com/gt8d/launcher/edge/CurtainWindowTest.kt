package com.gt8d.launcher.edge

import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.gt8d.launcher.curtain.CurtainController
import com.gt8d.launcher.time.FakeScheduler
import org.junit.Assert.*
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
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
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurtainWindowTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun cancelButton_callsProvidedCallback() {
        var calls = 0
        val wm = AndroidWindowManagerFacade(context.getSystemService(WindowManager::class.java))
        val window = CurtainWindow(context, wm, onCancel = { calls++ })
        try {
            window.show(sampleTarget())
            window.showProgress()
            composeRule.onNodeWithTag("curtain_cancel").performClick()
            assertEquals(1, calls)
        } finally {
            window.removeImmediately()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun cancelledHide_fadesFor150msLikeReady() {
        for (reason in listOf(HideReason.Ready, HideReason.Cancelled)) {
            val wm = FakeWindowManagerFacade()
            val window = CurtainWindow(context, wm)
            window.show(sampleTarget())
            val view = wm.added.single().first
            window.hide(reason)
            val hiddenAtMs = android.os.SystemClock.uptimeMillis()
            assertEquals(150L, view.animate().duration)
            view.animate().cancel()
            advance(149)
            assertTrue("removed before 150ms: $reason start=$hiddenAtMs now=${android.os.SystemClock.uptimeMillis()}", wm.removed.isEmpty())
            advance(1)
            assertEquals(listOf(view), wm.removed)
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun advance(ms: Long) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private class FailingAddFacade : WindowManagerFacade {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        val failure = IllegalStateException("add failed")
        override fun addView(view: View, params: WindowManager.LayoutParams) {
            added.add(view)
            throw failure
        }
        override fun removeView(view: View) { removed.add(view) }
    }

    @Test fun show_readdThrows_removesSameViewOnceAndDestroysOwner() {
        val wm = FakeWindowManagerFacade()
        val failure = IllegalStateException("readd failed")
        val window = CurtainWindow(context, wm, readdStrips = { throw failure })
        assertSame(failure, runCatching { window.show(sampleTarget()) }.exceptionOrNull())
        val view = wm.added.single().first
        assertEquals(listOf(view), wm.removed)
        assertEquals(Lifecycle.State.DESTROYED, view.findViewTreeLifecycleOwner()!!.lifecycle.currentState)
        window.removeImmediately()
        assertEquals(listOf(view), wm.removed)
    }

    @Test fun show_addThrows_attemptsRemovalOnceAndDestroysOwner() {
        val wm = FailingAddFacade()
        val window = CurtainWindow(context, wm)
        assertSame(wm.failure, runCatching { window.show(sampleTarget()) }.exceptionOrNull())
        val view = wm.added.single()
        assertEquals(listOf(view), wm.removed)
        assertEquals(Lifecycle.State.DESTROYED, view.findViewTreeLifecycleOwner()!!.lifecycle.currentState)
        window.removeImmediately()
        assertEquals(listOf(view), wm.removed)
    }

    // Window とその Session・コレクションだけを探索し、Android 内部へは進まない。
    private fun assertNoOwnedReferences(window: CurtainWindow, forbidden: List<Any>) {
        val visited = IdentityHashMap<Any, Boolean>()
        fun visit(value: Any?) {
            if (value == null) return
            assertFalse("retained ${value.javaClass.name}", forbidden.any { it === value })
            if (visited.put(value, true) != null) return
            when (value) {
                is Collection<*> -> value.forEach { visit(it) }
                is Map<*, *> -> value.forEach { (key, item) -> visit(key); visit(item) }
                is Array<*> -> value.forEach { visit(it) }
                else -> if (value === window || value.javaClass.name.startsWith(CurtainWindow::class.java.name + "$")) {
                    value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                        it.isAccessible = true
                        visit(it.get(value))
                    }
                }
            }
        }
        visit(window)
    }

    @Test fun repeatedShowHide_releasesEveryViewAndOwner() {
        val wm = FakeWindowManagerFacade()
        val window = CurtainWindow(context, wm)
        val released = mutableListOf<Any>()
        repeat(100) {
            window.show(sampleTarget())
            val view = wm.added.last().first
            val owner = view.findViewTreeLifecycleOwner()!!
            window.hide(HideReason.Ready)
            advance(150)
            assertEquals(1, wm.removed.count { it === view })
            assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            released.add(view)
            released.add(owner)
            assertNoOwnedReferences(window, released)
        }
    }

    @Test fun showDuringFade_releasesOldSessionWithoutRemovingNewView() {
        val wm = FakeWindowManagerFacade()
        val window = CurtainWindow(context, wm)
        window.show(sampleTarget())
        val a = wm.added.last().first
        val ownerA = a.findViewTreeLifecycleOwner()!!
        val sessionField = window.javaClass.declaredFields.firstOrNull { it.name == "currentSession" }
        val sessionA = sessionField?.apply { isAccessible = true }?.get(window)
        window.hide(HideReason.Ready)
        advance(149)
        window.show(sampleTarget())
        val b = wm.added.last().first
        val ownerB = b.findViewTreeLifecycleOwner()!!
        advance(1)
        assertEquals(1, wm.removed.count { it === a })
        assertEquals(Lifecycle.State.DESTROYED, ownerA.lifecycle.currentState)
        assertEquals(0, wm.removed.count { it === b })
        assertNotNull("each display must have a session", sessionA)
        listOf("view", "owner").forEach { name ->
            val field = sessionA!!.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull(field.get(sessionA))
        }
        window.hide(HideReason.Ready)
        advance(150)
        assertEquals(1, wm.removed.count { it === b })
        assertEquals(Lifecycle.State.DESTROYED, ownerB.lifecycle.currentState)
        assertNoOwnedReferences(window, listOf(a, b, ownerA, ownerB))
    }

    @Test fun safetyHide_removesSynchronously() {
        val wm = FakeWindowManagerFacade()
        val window = CurtainWindow(context, wm)
        window.show(sampleTarget())
        val view = wm.added.single().first
        window.hide(HideReason.Safety)
        assertEquals(listOf(view), wm.removed)
        advance(150)
        assertEquals(listOf(view), wm.removed)
    }

    @Test fun controllerSafety_removesWindowAt4000ms() {
        val wm = FakeWindowManagerFacade()
        val scheduler = FakeScheduler()
        val window = CurtainWindow(context, wm)
        val controller = CurtainController(scheduler, window)
        controller.show(sampleTarget(), 3500, true)
        val view = wm.added.single().first
        scheduler.advanceBy(3999)
        advance(3999)
        assertEquals(0, wm.removed.size)
        scheduler.advanceBy(1)
        assertEquals(listOf(view), wm.removed)
        assertFalse(controller.isShowing)
        advance(1)
        scheduler.advanceBy(150)
        advance(150)
        assertEquals(listOf(view), wm.removed)
    }

    @Test fun realWindow_showHideReshow_attachesAndDestroysOwners() {
        val wm = AndroidWindowManagerFacade(context.getSystemService(WindowManager::class.java))
        val views = mutableListOf<ComposeView>()
        val window = CurtainWindow(context, wm, contentFactory = { ComposeView(it).also(views::add) })
        repeat(2) {
            window.show(sampleTarget())
            advance(120)
            val view = views.last()
            assertTrue(view.isAttachedToWindow)
            assertTrue(view.childCount > 0)
            assertTrue(view.width > 0)
            assertTrue(view.height > 0)
            val owner: LifecycleOwner = view.findViewTreeLifecycleOwner()!!
            assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            window.hide(HideReason.Ready)
            advance(150)
            assertFalse(view.isAttachedToWindow)
            assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        }
        assertNotSame(views[0], views[1])
    }

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
