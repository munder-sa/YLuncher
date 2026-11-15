package com.gt8d.launcher.carback

import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarScreenSwitchTest {
    @Test
    fun buildIntent_hasExpectedActionPackageAndExtra() {
        val intent = CarScreenSwitch.buildIntent()

        assertEquals("com.suding.main.ACTION_EXECUTE_AT_CMD", intent.action)
        assertEquals("com.carsyso.main", intent.`package`)
        assertEquals(setOf("extra_at_cmd"), intent.extras!!.keySet())
        assertEquals("AT#CB", intent.getStringExtra("extra_at_cmd"))
    }

    @Test
    fun send_sendsOneExpectedBroadcast() {
        val app = RuntimeEnvironment.getApplication()

        assertTrue(CarScreenSwitch.send(app))

        val broadcasts = Shadows.shadowOf(app).broadcastIntents
        assertEquals(1, broadcasts.size)
        val intent = broadcasts.single()
        assertEquals("com.suding.main.ACTION_EXECUTE_AT_CMD", intent.action)
        assertEquals("com.carsyso.main", intent.`package`)
        assertEquals(setOf("extra_at_cmd"), intent.extras!!.keySet())
        assertEquals("AT#CB", intent.getStringExtra("extra_at_cmd"))
    }

    @Test
    fun send_exception_returnsFalse() {
        var attempts = 0
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun sendBroadcast(intent: Intent) {
                attempts++
                throw SecurityException("broadcast denied")
            }
        }

        assertFalse(CarScreenSwitch.send(context))
        assertEquals(1, attempts)
    }
}
