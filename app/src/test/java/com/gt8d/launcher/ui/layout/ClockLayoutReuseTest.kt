package com.gt8d.launcher.ui.layout

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.ui.home.HomeClockHeader
import com.gt8d.launcher.ui.theme.LauncherTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
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
class ClockLayoutReuseTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val calls = mutableListOf<Pair<String, String>>()
    private val observer = ClockLayoutObserver { owner, text -> calls += owner to text }
    private var width by mutableStateOf(700)
    private var height by mutableStateOf(160)
    private var fontScale by mutableStateOf(1f)
    private var densityScale by mutableStateOf(1f)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var revision by mutableStateOf(0)
    private var text by mutableStateOf("11:11")
    private var minSp by mutableStateOf(40f)
    private var maxSp by mutableStateOf(150f)
    private var dateStyle by mutableStateOf(TextStyle.Default)
    private var time = LocalDateTime.of(2026, 10, 31, 11, 11)
    private val clock = { time }

    private fun host(content: @Composable () -> Unit) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            LauncherTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalClockLayoutObserver provides observer,
                    LocalDensity provides Density(density.density * densityScale, fontScale),
                    LocalLayoutDirection provides direction,
                    LocalTextStyle provides dateStyle,
                ) {
                    // Keep the strict Rect.contains checks away from its excluded right/bottom edges.
                    Box(Modifier.width(width.dp).height(500.dp).testTag("area").padding(1.dp)) { content() }
                }
            }
        }
        controller.visible()
        rule.waitForIdle()
    }

    @Composable private fun Fit() {
        val description = "revision $revision"
        FitClock(text, height.dp, Modifier.semantics { contentDescription = description },
            minSp = minSp, maxSp = maxSp)
    }

    @Composable private fun Header() {
        val description = "revision $revision"
        HomeClockHeader(height.dp,
            Modifier.semantics { contentDescription = description }, now = clock)
    }

    private fun update(change: () -> Unit) {
        rule.runOnIdle(change)
        rule.waitForIdle()
    }

    private fun count(owner: String) = calls.count { it.first == owner }
    private fun templates(owner: String) = calls.count { it == owner to "00:00" }
    private fun clockFits() {
        val node = rule.onNodeWithTag("clock")
        val layout = node.textLayout()
        assertFalse(layout.hasVisualOverflow)
        assertEquals(1, layout.lineCount)
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("clock=$bounds area=$area", area.contains(bounds.topLeft) && area.contains(bounds.bottomRight))
        assertTrue(layout.size.height <= with(rule.density) { (height.dp).toPx() } * densityScale)
    }

    private fun tick(newTime: LocalDateTime) {
        update {
            time = newTime
            controller.get().sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
        }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Test fun fit_unrelatedRecompositionDoesNotMeasure_andNewTimeReusesTemplatesButChecksActualGlyphs() {
        host { Fit() }
        clockFits()
        val initial = calls.size
        val samples = templates("fit")
        assertTrue(samples > 0)
        update { revision++ }
        rule.onNodeWithContentDescription("revision 1").assertExists()
        assertEquals(initial, calls.size)
        update { text = "23:59" }
        rule.onNodeWithTag("clock").assertTextEquals("23:59")
        assertTrue(calls.size > initial)
        assertEquals(samples, templates("fit"))
        assertTrue(calls.any { it == "fit" to "23:59" })
        clockFits()
        val changed = calls.size
        update { revision++ }
        assertEquals(changed, calls.size)
    }

    @Test fun fit_widthHeightFontScaleDensityDirectionAndLimitsInvalidateSelection() {
        host { Fit() }
        fun changes(change: () -> Unit) {
            val before = count("fit")
            update(change)
            assertTrue(count("fit") > before)
            clockFits()
        }
        val originalSize = rule.onNodeWithTag("clock").textLayout().layoutInput.style.fontSize
        changes { width = 280 }
        assertTrue(rule.onNodeWithTag("clock").textLayout().layoutInput.style.fontSize.value < originalSize.value)
        val wideHeight = rule.onNodeWithTag("clock").textLayout().size.height
        changes { height = 70 }
        assertTrue(rule.onNodeWithTag("clock").textLayout().size.height < wideHeight)
        changes { fontScale = 1.3f }
        changes { densityScale = 1.1f }
        changes { direction = LayoutDirection.Rtl }
        changes { maxSp = 90f }
        changes { minSp = 38f }
    }

    @Test fun fit_actualWiderGlyphsAreMeasuredAndRemainInsideConstraints() {
        width = 350
        host { Fit() }
        val first = rule.onNodeWithTag("clock").textLayout()
        update { text = "WW:WW" }
        val wider = rule.onNodeWithTag("clock").textLayout()
        assertTrue(calls.any { it == "fit" to "WW:WW" })
        assertTrue(wider.layoutInput.style.fontSize.value < first.layoutInput.style.fontSize.value)
        rule.onNodeWithTag("clock").assertTextEquals("WW:WW")
        clockFits()
    }

    @Test fun header_reusesBothSearches_andDateUntilTheActualStringsChange() {
        host { Header() }
        val initial = calls.size
        assertTrue(count("header") > 0)
        assertTrue(count("fit") > 0)
        assertEquals(1, count("date"))
        val parentSamples = templates("header")
        val childSamples = templates("fit")
        update { revision++ }
        rule.onNodeWithContentDescription("revision 1").assertExists()
        assertEquals(initial, calls.size)
        tick(time.plusMinutes(1))
        rule.onNodeWithTag("clock").assertTextEquals("11:12")
        assertTrue(calls.size > initial)
        assertEquals(parentSamples, templates("header"))
        assertEquals(childSamples, templates("fit"))
        assertEquals(1, count("date"))
        tick(LocalDateTime.of(2026, 11, 1, 0, 0))
        rule.onNodeWithTag("clock").assertTextEquals("00:00")
        rule.onNodeWithTag("home_date").assertTextEquals("11月1日(日)")
        assertEquals(2, count("date"))
        clockFits()
    }

    @Test fun header_geometryAndThemeChangesRemeasure_andDateMovesBelowAtNarrowWidth() {
        host { Header() }
        val parent = count("header")
        val child = count("fit")
        update { width = 330 }
        assertTrue(count("header") > parent)
        assertTrue(count("fit") > child)
        val dateBounds = rule.onNodeWithTag("home_date").fetchSemanticsNode().boundsInRoot
        val clockBounds = rule.onNodeWithTag("clock").fetchSemanticsNode().boundsInRoot
        assertTrue(dateBounds.top >= clockBounds.bottom)
        val beforeHeight = count("header")
        update { height = 100 }
        assertTrue(count("header") > beforeHeight)
        val beforeScale = count("header")
        update { fontScale = 1.3f }
        assertTrue(count("header") > beforeScale)
        update { width = 700 }
        val beforeStyle = count("date")
        val dateWidth = rule.onNodeWithTag("home_date").textLayout().size.width
        update { dateStyle = TextStyle(letterSpacing = 3.sp, fontFamily = FontFamily.Serif) }
        assertEquals(beforeStyle + 1, count("date"))
        assertNotEquals(dateWidth, rule.onNodeWithTag("home_date").textLayout().size.width)
        assertFalse(rule.onNodeWithTag("home_date").textLayout().hasVisualOverflow)
        rule.onNodeWithTag("home_date").assertTextEquals("10月31日(土)")
        clockFits()
        val after = calls.size
        update { revision++ }
        assertEquals(after, calls.size)
    }

    private class DeferredFont(val loaded: CompletableDeferred<Typeface>) : AndroidFont(
        FontLoadingStrategy.Async,
        object : TypefaceLoader {
            override fun loadBlocking(context: Context, font: AndroidFont): Typeface? =
                error("This font must load asynchronously")
            override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
                (font as DeferredFont).loaded.await()
        },
    ) {
        override val weight = FontWeight.Normal
        override val style = FontStyle.Normal
    }

    @Test fun resolvedFontCompletionAndResolverReplacement_invalidateAffectedMeasurements() {
        val loaded = CompletableDeferred<Typeface>()
        dateStyle = TextStyle(fontFamily = FontFamily(DeferredFont(loaded)))
        var resolverVersion by mutableStateOf(0)
        host {
            val context = LocalContext.current
            val resolver = remember(resolverVersion) { createFontFamilyResolver(context) }
            CompositionLocalProvider(LocalFontFamilyResolver provides resolver) { Header() }
        }
        val beforeCompletion = count("date")
        val oldDateWidth = rule.onNodeWithTag("home_date").textLayout().size.width
        update { loaded.complete(Typeface.MONOSPACE) }
        assertTrue("date font completion", count("date") > beforeCompletion)
        assertNotEquals(oldDateWidth, rule.onNodeWithTag("home_date").textLayout().size.width)
        clockFits()
        val completed = calls.size
        update { revision++ }
        assertEquals(completed, calls.size)
        val beforeResolver = listOf("header", "fit", "date").associateWith { count(it) }
        update { resolverVersion++ }
        beforeResolver.forEach { (owner, before) -> assertTrue("$owner resolver replacement", count(owner) > before) }
        clockFits()
    }

    @Test fun templateCache_keeps128Candidates_andMeasuresUncachedOverflowCorrectly() {
        lateinit var cache: ClockLayoutCache
        host { cache = rememberClockLayoutCache("bounded", TextStyle.Default, Constraints(), 500.dp, 1f, 300f) }
        val styles = (1..130).map { TextStyle(fontSize = it.sp) }
        lateinit var first: List<ClockMeasurement>
        update { first = styles.map { cache.template(it) } }
        assertEquals(130, count("bounded"))
        update { assertEquals(first, styles.map { cache.template(it) }) }
        assertEquals(132, count("bounded"))
        update { assertEquals(first.take(128), styles.take(128).map { cache.template(it) }) }
        assertEquals(132, count("bounded"))
    }
}
