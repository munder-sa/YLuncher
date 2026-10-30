package com.gt8d.launcher.ui.layout

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.ParameterizedRobolectricTestRunner

internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    return results.single()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class FitClockTest(private val width: Int, private val height: Int, private val scale: Float) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1} scale={2}")
        fun cases() = listOf(360, 516, 1291).flatMap { w ->
            listOf(400, 726).flatMap { h -> listOf(1f, 1.3f).map { s -> arrayOf<Any>(w, h, s) } }
        }
    }
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun clock_fitsAndKeepsSizeAcrossTimes() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var text by mutableStateOf("00:00")
        try {
            controller.get().setContent {
                LauncherTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Box(Modifier.fillMaxWidth().height((height * 0.3f).dp).testTag("parent")) {
                            FitClock(text, (height * 0.3f).dp)
                        }
                    }
                }
            }
            controller.visible()
            val clock = rule.onNodeWithTag("clock")
            val first = clock.textLayout()
            assertEquals(1, first.lineCount)
            assertFalse("size=${first.size} paragraph=${first.multiParagraph.width}x${first.multiParagraph.height} input=${first.layoutInput}", first.hasVisualOverflow)
            val parent = rule.onNodeWithTag("parent").fetchSemanticsNode().boundsInRoot
            val bounds = clock.fetchSemanticsNode().boundsInRoot
            assertTrue(parent.contains(bounds.topLeft) && parent.contains(bounds.bottomRight))
            rule.runOnIdle {
                text = "23:59"
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            val second = clock.textLayout()
            assertEquals(first.layoutInput.style.fontSize, second.layoutInput.style.fontSize)
            assertEquals(1, second.lineCount)
            assertFalse(second.hasVisualOverflow)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
