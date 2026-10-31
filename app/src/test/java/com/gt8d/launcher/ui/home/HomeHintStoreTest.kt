package com.gt8d.launcher.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeHintStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("home_hint", Context.MODE_PRIVATE)

    @Before fun setUp() { preferences.edit().clear().commit() }

    @Test fun initially_shouldShow() {
        assertTrue(HomeHintStore(context).shouldShow())
    }

    @Test fun recordShown_fiveTimes_hidesHintAndPersistsCount() {
        val store = HomeHintStore(context)
        repeat(5) {
            assertTrue(store.shouldShow())
            store.recordShown()
            assertEquals(it + 1, preferences.getInt("shown_count", 0))
        }
        assertFalse(HomeHintStore(context).shouldShow())
    }

    @Test fun recordShown_afterFive_staysAtFive() {
        val store = HomeHintStore(context)
        repeat(10) { store.recordShown() }
        assertFalse(store.shouldShow())
        assertEquals(5, preferences.getInt("shown_count", 0))
    }
}
