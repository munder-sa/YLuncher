package com.gt8d.launcher.ui.panel

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.AppGraph
import com.gt8d.launcher.apps.SplitRedirects
import com.gt8d.launcher.config.Side
import com.gt8d.launcher.pip.PipDetector
import com.gt8d.launcher.split.PanelPlacement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelActivityPipTest {
    @Test fun pipTarget_inSplit_launchesFullscreen_thenReopensAfterDelay() {
        checkLaunch(pip = true, component = false)
    }

    @Test fun pipComponent_inSplit_launchesFullscreen_thenReopensAfterDelay() {
        checkLaunch(pip = true, component = true)
    }

    @Test fun nonPip_inSplit_keepsAdjacent_withoutReopen() {
        checkLaunch(pip = false, component = false)
        checkLaunch(pip = false, component = true)
    }

    @Test fun pip_fullOrUnknownPlacement_doesNotReopen() {
        checkLaunch(pip = true, component = false, placement = PanelPlacement.Full)
        checkLaunch(pip = true, component = false, placement = null)
    }

    private fun checkLaunch(pip: Boolean, component: Boolean, placement: PanelPlacement? = PanelPlacement.Left) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppGraph.init(app)
        val previous = AppGraph.panelPlacement.value
        val controller = Robolectric.buildActivity(PanelActivity::class.java)
        val activity = controller.get()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val reopened = mutableListOf<Pair<String, Side>>()
        try {
            shadowOf(activity).setInMultiWindowMode(true)
            var pinned = pip
            activity.pipDetector = PipDetector { it == "example.pip" && pinned }
            activity.redirects = SplitRedirects { null }
            activity.launchApp = { pkg, adjacent -> calls.add(pkg to adjacent); true }
            activity.launchComponent = { cn, adjacent -> calls.add(cn.packageName to adjacent); true }
            activity.pipLaunchApp = { pkg -> calls.add(pkg to false); pinned = false; true }
            activity.pipLaunchComponent = { cn -> calls.add(cn.packageName to false); pinned = false; true }
            activity.reopen = { pkg, side -> reopened.add(pkg to side); true }
            activity.pauseMedia = { false }
            AppGraph.reportPanelPlacement(placement)
            assertTrue(if (component) activity.onLaunchComponent(ComponentName("example.pip", "example.pip.Main"))
                else activity.onLaunchApp("example.pip"))
            assertEquals(listOf("example.pip" to !pip), calls)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1199))
            assertTrue(reopened.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            val side = when (placement) {
                PanelPlacement.Left -> Side.Left
                PanelPlacement.Right -> Side.Right
                else -> null
            }
            assertEquals(if (pip && side != null) listOf("example.pip" to side) else emptyList(), reopened)
        } finally {
            AppGraph.reportPanelPlacement(previous)
        }
    }
}
