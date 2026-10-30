package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
class HomeClockHeaderTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private fun host(width: Int) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            Box(Modifier.width(width.dp).testTag("header_area")) {
                HomeClockHeader(160.dp, now = { LocalDateTime.of(2026, 10, 31, 12, 34) })
            }
        } }
        controller.visible()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    private fun checkDate() {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("home_date").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        assertFalse("${results.single().layoutInput} paragraph=${results.single().multiParagraph.width}x${results.single().multiParagraph.height} size=${results.single().size}", results.single().hasVisualOverflow)
        val date = rule.onNodeWithTag("home_date").fetchSemanticsNode().boundsInRoot
        val area = rule.onNodeWithTag("header_area").fetchSemanticsNode().boundsInRoot
        assertTrue(date.left >= area.left && date.right <= area.right)
    }
    @Test fun clockAndDate_sameRow_whenWide() {
        host(700); checkDate()
        val clock = rule.onNodeWithTag("clock").fetchSemanticsNode().boundsInRoot
        val date = rule.onNodeWithTag("home_date").fetchSemanticsNode().boundsInRoot
        assertTrue(date.left >= clock.right)
        assertTrue(date.top < clock.bottom)
    }
    @Test fun date_movesBelow_whenNarrow() {
        host(330); checkDate()
        assertTrue(rule.onNodeWithTag("home_date").fetchSemanticsNode().boundsInRoot.top >=
            rule.onNodeWithTag("clock").fetchSemanticsNode().boundsInRoot.bottom)
    }
    @Test fun dateFormat_japanese() {
        host(700)
        rule.onNodeWithText("10月31日(土)").assertIsDisplayed()
    }
}
