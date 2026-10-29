package com.gt8d.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.safety.SafetyGuard
import java.io.File
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LauncherApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LauncherAppTest {
    companion object {
        private var previousHandler: Thread.UncaughtExceptionHandler? = null
        private val delegated = mutableListOf<Pair<Thread, Throwable>>()
        private val originalHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            delegated.add(thread to throwable)
        }

        @BeforeClass
        @JvmStatic
        fun installPreviousHandler() {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        }

        @AfterClass
        @JvmStatic
        fun restorePreviousHandler() {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<LauncherApp>()
    private fun state() = Json.parseToJsonElement(File(context.filesDir, "safety.json").readText()).jsonObject

    @Before
    fun prepare() {
        delegated.clear()
    }

    @After
    fun cleanup() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
        SafetyGuard.clear(context)
        File(context.filesDir, "safety.json").delete()
    }

    @Test
    fun onCreate_recordsStart() {
        val start = SystemClock.elapsedRealtime()
        assertEquals(start, state().getValue("startElapsed").jsonPrimitive.long)
        assertEquals(0, state().getValue("crashes").jsonArray.size)
        assertFalse(state().getValue("fallback").jsonPrimitive.boolean)
    }

    @Test
    fun onCreate_marksStableAt10000Not9999() {
        repeat(3) { SafetyGuard.recordCrash(context) }
        val crashes = state().getValue("crashes")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9999))
        assertEquals(crashes, state().getValue("crashes"))
        assertTrue(state().getValue("fallback").jsonPrimitive.boolean)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(0, state().getValue("crashes").jsonArray.size)
        assertTrue(state().getValue("fallback").jsonPrimitive.boolean)
    }

    @Test
    fun uncaughtHandler_recordsCrashAndDelegatesSameArguments() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(42))
        val thread = Thread.currentThread()
        val throwable = IllegalStateException("test crash")
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        assertNotSame(originalHandler, handler)
        handler.uncaughtException(thread, throwable)
        assertEquals(listOf(42L), state().getValue("crashes").jsonArray.map { it.jsonPrimitive.long })
        assertFalse(state().getValue("fallback").jsonPrimitive.boolean)
        assertEquals(1, delegated.size)
        assertSame(thread, delegated.single().first)
        assertSame(throwable, delegated.single().second)
    }
}
