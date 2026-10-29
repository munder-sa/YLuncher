package com.gt8d.launcher.ui

import android.app.Activity
import android.content.Intent
import com.gt8d.launcher.link.BrowserActivity
import com.gt8d.launcher.ui.home.HomeActivity
import com.gt8d.launcher.ui.panel.PanelActivity
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoActionBarTest {
    private fun <T : Activity> checkActivity(type: Class<T>, intent: Intent? = null) {
        val controller = Robolectric.buildActivity(type, intent).create().start()
        try {
            assertNull(controller.get().actionBar)
        } finally {
            controller.stop().destroy()
        }
    }

    @Test fun homeActivity_hasNoActionBar() = checkActivity(HomeActivity::class.java)
    @Test fun panelActivity_hasNoActionBar() = checkActivity(PanelActivity::class.java)
    @Test fun browserActivity_hasNoActionBar() = checkActivity(
        BrowserActivity::class.java,
        Intent().putExtra(BrowserActivity.EXTRA_URL, "https://example.com"),
    )
}
