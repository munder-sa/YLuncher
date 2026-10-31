package com.gt8d.launcher.ui.layout

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class MarqueeTextTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val text = mutableStateOf("短い曲名")

    private fun host() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            LauncherTheme { MarqueeText(text.value, Modifier.width(200.dp), tag = "text") }
        }
        controller.visible()
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    private fun check(overflow: Boolean) {
        rule.onNode(SemanticsMatcher.expectValue(MarqueeOverflow, overflow)).assertExists()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
    }

    @Test fun shortText_doesNotOverflow_andIsOneLine() {
        host()
        check(false)
    }

    @Test fun longText_overflows_andIsOneLine() {
        text.value = "長".repeat(40)
        host()
        check(true)
    }

    @Test fun changingShortToLong_updatesOverflow() {
        host()
        check(false)
        rule.runOnIdle { text.value = "長".repeat(40) }
        check(true)
    }
}
