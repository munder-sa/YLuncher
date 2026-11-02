package com.gt8d.launcher.panelhost

import android.app.Activity
import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelHostActivityTest {
    private val panel = ComponentName("com.gt8d.launcher", "com.gt8d.launcher.ui.panel.PanelActivity")

    @Test fun onCreate_startsPanelForResult() {
        val controller = Robolectric.buildActivity(PanelHostActivity::class.java).create()
        val started = shadowOf(controller.get()).nextStartedActivityForResult
        assertEquals(panel, started.intent.component)
        assertEquals(1, started.requestCode)
        assertEquals(0, started.intent.flags)
        controller.destroy()
    }

    @Test fun panelFinishedOk_hostFinishesOnNextResume() {
        val controller = Robolectric.buildActivity(PanelHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivityForResult
        controller.pause()
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, null)
        controller.resume()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }

    @Test fun panelKilled_hostRelaunchesPanelOnNextResume() {
        val controller = Robolectric.buildActivity(PanelHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivityForResult
        controller.pause()
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        controller.resume()
        assertFalse(activity.isFinishing)
        val relaunched = shadowOf(activity).nextStartedActivityForResult
        assertEquals(panel, relaunched.intent.component)
        assertEquals(started.requestCode, relaunched.requestCode)
        controller.pause().stop().destroy()
    }

    @Test fun relaunch_limitedToThree() {
        val controller = Robolectric.buildActivity(PanelHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        repeat(4) { index ->
            val started = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(started)
            assertEquals(panel, started.intent.component)
            controller.pause()
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
            controller.resume()
            assertEquals(index == 3, activity.isFinishing)
        }
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }

    @Test fun relaunch_countResetsAfterQuietWindow() {
        val controller = Robolectric.buildActivity(PanelHostActivity::class.java).create().start().resume()
        val activity = controller.get()
        repeat(6) {
            val started = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(started)
            controller.pause()
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(PanelHostActivity.RELAUNCH_WINDOW_MS + 1))
            controller.resume()
            assertFalse(activity.isFinishing)
        }
        controller.pause().stop().destroy()
    }
}
