package com.gt8d.launcher.link

import android.content.Intent
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserPaneTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private var closes = 0
    private val shown = mutableStateOf(true)

    private fun host() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume()
        controller.get().setContent {
            LauncherTheme {
                if (shown.value) BrowserPane("https://example.com", onClose = { closes++ })
            }
        }
        controller.visible()
        rule.waitForIdle()
    }

    @After fun cleanup() {
        if (::controller.isInitialized) {
            rule.runOnIdle { shown.value = false }
            rule.waitForIdle()
            controller.pause().stop().destroy()
        }
    }

    @Test fun close_callsOnClose() {
        host()
        rule.onNodeWithTag("browser_close").performClick()
        assertEquals(1, closes)
    }

    @Test fun back_withoutHistory_callsOnClose() {
        host()
        rule.onNodeWithTag("browser_back").performClick()
        assertEquals(1, closes)
    }

    @Test fun systemBack_withoutHistory_callsOnClose() {
        host()
        rule.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closes)
    }

    @Test fun chrome_opensChromeAndCloses() {
        host()
        rule.onNodeWithTag("browser_chrome").performClick()
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.android.chrome", intent.`package`)
        assertEquals("https://example.com", intent.dataString)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(1, closes)
    }

    @Test fun dispose_destroysWebView() {
        host()
        val webView = findWebView(controller.get().window.decorView)
        assertNotNull(webView)
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        assertTrue(shadowOf(webView!!).wasDestroyCalled())
    }

    private fun findWebView(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
