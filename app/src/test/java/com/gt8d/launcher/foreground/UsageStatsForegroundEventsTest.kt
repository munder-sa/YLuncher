package com.gt8d.launcher.foreground

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageStatsForegroundEventsTest {
    @Test
    fun queryFailure_isNotReportedAsEmptySuccess() {
        val cause = IllegalStateException("query failed")
        val events = UsageStatsForegroundEvents(ApplicationProvider.getApplicationContext()) { throw cause }
        val error = assertThrows(ForegroundEventsUnavailableException::class.java) {
            events.resumedSince(0)
        }
        assertSame(cause, error.cause)
    }

    @Test
    fun successfulEmptyQuery_remainsEmptySuccess() {
        val events = UsageStatsForegroundEvents(ApplicationProvider.getApplicationContext()) { emptyList() }
        assertEquals(emptyList<ResumeEvent>(), events.resumedSince(0))
    }

    @Test
    fun missingUsageStatsService_isUnavailable() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.USAGE_STATS_SERVICE) null else super.getSystemService(name)
        }
        assertThrows(ForegroundEventsUnavailableException::class.java) {
            UsageStatsForegroundEvents(context).resumedSince(0)
        }
    }
}
