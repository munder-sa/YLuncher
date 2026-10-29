package com.gt8d.launcher.split

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidActivityLauncherTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val selected = ComponentName("example.app", "example.app.B")

    private fun start(context: Context = app, adjacent: Boolean = false): Boolean =
        AndroidActivityLauncher::class.java.getMethod(
            "startComponent", ComponentName::class.java, Boolean::class.javaPrimitiveType,
        ).invoke(AndroidActivityLauncher(context), selected, adjacent) as Boolean

    @Test fun startComponent_selectedSecondaryActivity_isPreserved() {
        val pm = Shadows.shadowOf(app.packageManager)
        for (cls in listOf("example.app.A", selected.className)) {
            val cn = ComponentName(selected.packageName, cls)
            pm.addOrUpdateActivity(ActivityInfo().apply { name = cls; packageName = cn.packageName })
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
        assertEquals("example.app.A", app.packageManager.getLaunchIntentForPackage(selected.packageName)?.component?.className)
        assertTrue(start())
        assertEquals(selected, Shadows.shadowOf(app).nextStartedActivity.component)
    }

    @Test fun startComponent_adjacent_hasAllThreeFlags() {
        assertTrue(start(adjacent = true))
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(selected, intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        for (flag in listOf(Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT, Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) {
            assertEquals(flag, intent.flags and flag)
        }
    }
    @Test fun startComponent_fullscreen_hasNoAdjacentOrMultipleTask() {
        assertTrue(start())
        val intent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
    @Test fun startComponent_startFailure_returnsFalseWithoutFallback() {
        var attempts = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                attempts++
                throw IllegalStateException("start failed")
            }
        }
        assertFalse(start(context))
        assertEquals(1, attempts)
    }
}
