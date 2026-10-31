package com.gt8d.launcher.notice

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.curtain.HideReason
import com.gt8d.launcher.settings.SettingsActivity
import com.gt8d.launcher.time.FakeScheduler
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeOverlayTest {
    @Test fun cancelled_discardsPendingWithoutDismissingVisibleNotice() {
        overlay.show(Notice.Slow)
        val visible = overlay.visibleView
        curtain = true
        overlay.show(Notice.NoBrowser)
        curtain = false
        overlay.onCurtainHidden(HideReason.Cancelled)
        assertSame(visible, overlay.visibleView)
        assertEquals(Notice.Slow.text, text())
        scheduler.advanceBy(5000)
        assertSame(visible, overlay.visibleView)
        assertEquals(Notice.Slow.text, text())
    }

    @Test fun cancelled_pendingNeverAppearsAfterFiveSeconds() {
        curtain = true
        overlay.show(Notice.NoBrowser)
        curtain = false
        overlay.onCurtainHidden(HideReason.Cancelled)
        assertNull(overlay.visibleView)
        scheduler.advanceBy(5000)
        overlay.onCurtainHidden(HideReason.Ready)
        assertNull(overlay.visibleView)
    }
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val scheduler = FakeScheduler()
    private var curtain = false
    private var homeCalls = 0
    private val intents = mutableListOf<Intent>()
    private val overlay by lazy { NoticeOverlay(app, { curtain }, { homeCalls++ }, scheduler,
        canOverlay = { true }, startActivity = { intents.add(it) }) }
    @After fun cleanup() { scheduler.advanceBy(20_000) }

    private fun text() = (overlay.visibleView!!.getChildAt(0) as TextView).text.toString()
    private fun button() = overlay.visibleView!!.getChildAt(1) as Button

    @Test fun actionButton_hasTransparentOutlineAndWhiteText_withoutAllCaps() {
        overlay.show(Notice.AppMissing("動画"))
        val button = button()
        val background = button.background as GradientDrawable
        assertEquals(Color.TRANSPARENT, background.color!!.defaultColor)
        assertEquals(Color.WHITE, button.currentTextColor)
        assertFalse(button.isAllCaps)
        assertNull(button.backgroundTintList)
        assertNull(button.stateListAnimator)
        assertEquals(0f, button.elevation, 0f)
        val density = app.resources.displayMetrics.density
        assertTrue(button.minimumHeight >= 72 * density)
        assertTrue(button.minimumWidth >= 72 * density)
    }

    @Test fun showsTextAndButton_withWindowFlagsAndBottomCenterPosition() {
        overlay.show(Notice.AppMissing("動画"))
        assertEquals("動画が見つかりません", text())
        assertEquals("セットを編集", button().text.toString())
        val density = app.resources.displayMetrics.density
        val row = overlay.visibleView!!
        val params = row.layoutParams as WindowManager.LayoutParams
        row.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(button().measuredHeight >= 72 * density)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        // 窓の管理は FLAG_HARDWARE_ACCELERATED などを足すため、必要なビットを確かめる。
        val required = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        assertEquals(required, params.flags and required)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, params.gravity)
        assertEquals((24 * density).toInt(), params.y)
        assertEquals(noticeWidthPx(app.resources.displayMetrics.widthPixels, density), params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
    }

    @Test fun disappearsExactlyAfterSixSeconds() {
        overlay.show(Notice.Slow)
        assertEquals(1, overlay.visibleView!!.childCount)
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun replacementRestartsSixSeconds() {
        overlay.show(Notice.Slow)
        scheduler.advanceBy(4_000)
        overlay.show(Notice.BrowserFailed)
        assertEquals(Notice.BrowserFailed.text, text())
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun homeButton_callsOnceAndDismisses() {
        overlay.show(Notice.OpenFailed("動画"))
        val button = button()
        button.performClick(); button.performClick()
        assertEquals(1, homeCalls)
        assertTrue(intents.isEmpty())
        assertNull(overlay.visibleView)
    }

    @Test fun settingsButtons_startOnceAndDismiss() {
        for (notice in listOf(Notice.AppMissing("動画"), Notice.NoBrowser)) {
            overlay.show(notice)
            val button = button()
            val before = intents.size
            button.performClick(); button.performClick()
            assertEquals(before + 1, intents.size)
            assertEquals(if (notice == Notice.NoBrowser) "link" else "sets", intents.last().getStringExtra("open_route"))
            assertNull(overlay.visibleView)
        }
    }

    @Test fun curtainDefersUntilHidden_thenStartsSixSeconds() {
        curtain = true
        overlay.show(Notice.Slow)
        assertNull(overlay.visibleView)
        scheduler.advanceBy(3_000)
        curtain = false
        overlay.onCurtainHidden(HideReason.Ready)
        assertEquals(Notice.Slow.text, text())
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun curtainKeepsOnlyLatestNotice() {
        curtain = true
        overlay.show(Notice.Slow)
        overlay.show(Notice.BrowserFailed)
        assertNull(overlay.visibleView)
        curtain = false
        overlay.onCurtainHidden(HideReason.Failed)
        assertEquals(Notice.BrowserFailed.text, text())
    }

    @Test fun missingHiddenCallback_checksAfterFiveSeconds() {
        curtain = true
        overlay.show(Notice.Slow)
        curtain = false
        scheduler.advanceBy(4_999)
        assertNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertEquals(Notice.Slow.text, text())
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun curtainStillShowingAfterFiveSeconds_discardsPending() {
        curtain = true
        overlay.show(Notice.Slow)
        scheduler.advanceBy(5_000)
        curtain = false
        overlay.onCurtainHidden(HideReason.Safety)
        assertNull(overlay.visibleView)
    }

    @Test fun newCurtainLeavesVisibleNoticeUntilExpiry() {
        overlay.show(Notice.Slow)
        curtain = true
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun pendingNotice_doesNotReplaceVisibleNoticeUntilCurtainHidden() {
        overlay.show(Notice.Slow)
        scheduler.advanceBy(2_000)
        curtain = true
        overlay.show(Notice.NoBrowser)
        assertEquals(Notice.Slow.text, text())
        scheduler.advanceBy(3_999)
        assertEquals(Notice.Slow.text, text())
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
        curtain = false
        overlay.onCurtainHidden(HideReason.Ready)
        assertEquals(Notice.NoBrowser.text, text())
        scheduler.advanceBy(5_999)
        assertNotNull(overlay.visibleView)
        scheduler.advanceBy(1)
        assertNull(overlay.visibleView)
    }

    @Test fun noOverlayPermission_usesTextToast() {
        val fallback = NoticeOverlay(app, { false }, {}, scheduler, canOverlay = { false })
        fallback.show(Notice.NoBrowser)
        assertNull(fallback.visibleView)
        assertEquals(Notice.NoBrowser.text, ShadowToast.getTextOfLatestToast())
    }

    @Test fun widthFitsBetweenDockAndEdge_onWideAndNarrowScreens() {
        val density = 238f / 160f
        for (screen in listOf(1920, 1280)) {
            val width = noticeWidthPx(screen, density)
            assertEquals(minOf(screen * .5f, 720 * density, screen - 2 * (104 * density + 48)).toInt(), width)
            val left = (screen - width) / 2f
            assertTrue(left >= 48 + 88 * density)
            assertTrue(left + width <= screen - (48 + 88 * density))
        }
    }

    @Test fun actionIntents_haveSettingsRouteAndNewTask() {
        for ((action, route) in listOf(NoticeAction.EditSets to "sets", NoticeAction.ChooseBrowser to "link")) {
            val intent = noticeActionIntent(app, action)!!
            assertEquals(SettingsActivity::class.java.name, intent.component!!.className)
            assertEquals(route, intent.getStringExtra("open_route"))
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        }
        assertNull(noticeActionIntent(app, NoticeAction.Home))
    }
}
