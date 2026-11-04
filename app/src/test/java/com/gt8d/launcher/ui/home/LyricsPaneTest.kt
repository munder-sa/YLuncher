package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.media.lyrics.LyricWord
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
import com.gt8d.launcher.ui.theme.LyricsVisualStyle
import com.gt8d.launcher.ui.theme.lineTargetAlpha
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1000dp")
class LyricsPaneTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun cachedWordBoundaryMatchesOriginalPixelsIncludingWrappedLineClear() {
        for (density in listOf(1f, 2f)) for (width in listOf(80, 160)) {
            val cached = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                startX = 0f, endX = 24 * density)
            for (x in listOf(0f, 8.5f, 40f, width.toFloat())) for (line in 0..1) {
                fun render(original: Boolean): ImageBitmap {
                    val image = ImageBitmap(width, 64)
                    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), 64f)) {
                        drawRect(Color.White)
                        val top = line * 32f
                        val bottom = top + 32f
                        if (original) {
                            clipRect(0f, top, size.width, bottom) {
                                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                                    startX = x - 12 * density, endX = x + 12 * density), blendMode = BlendMode.DstIn)
                            }
                            if (bottom < size.height) drawRect(Color.Transparent, Offset(0f, bottom),
                                Size(size.width, size.height - bottom), blendMode = BlendMode.Clear)
                        } else drawLyricWordBoundary(cached, x, top, bottom)
                    }
                    return image
                }
                val expected = render(true).toPixelMap()
                val actual = render(false).toPixelMap()
                for (y in 0 until 64) for (column in 0 until width) {
                    assertEquals("density=$density width=$width x=$x line=$line pixel=$column,$y",
                        expected[column, y].alpha, actual[column, y].alpha, 1f / 255f)
                }
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun edgeMaskKeeps32dpFadeAndHandlesZeroSize() {
        assertNull(lyricEdgeBrush(0f, 32f))
        for (density in listOf(1f, 2f)) {
            val height = (120 * density).toInt()
            val image = ImageBitmap(8, height)
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(8f, height.toFloat())) {
                drawRect(Color.White)
                drawRect(lyricEdgeBrush(size.height, 32 * density)!!, blendMode = BlendMode.DstIn)
            }
            val pixels = image.toPixelMap()
            assertEquals(1f, pixels[4, height / 2].alpha, 0f)
            assertEquals(1f, pixels[4, (32 * density).toInt()].alpha, 0f)
            assertTrue(pixels[4, 0].alpha < 0.04f)
            assertTrue(pixels[4, height - 1].alpha < 0.04f)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fixedBrushesAreNotRebuiltByWordProgressOrLineChanges() {
        now = now.copy(positionMs = 1500, playing = true, positionUpdatedAt = clock)
        show(LyricsState.Synced(listOf(
            LyricLine(1000, "単語", listOf(LyricWord(1000, 0))),
            LyricLine(10000, "次の単語", listOf(LyricWord(10000, 0))))))
        drawPane()
        assertTrue(observer.maskBrushes > 0)
        assertTrue(observer.wordBrushes > 0)
        val counts = observer.maskBrushes to observer.wordBrushes
        val layoutCounts = listOf(observer.compositions, observer.measures, observer.placements)
        repeat(10) {
            rule.runOnIdle { clock += 16 }
            rule.mainClock.advanceTimeByFrame()
            drawPane()
        }
        assertEquals(counts, observer.maskBrushes to observer.wordBrushes)
        assertEquals(layoutCounts, listOf(observer.compositions, observer.measures, observer.placements))
        moveTo(10)
        drawPane()
        assertEquals(counts.first, observer.maskBrushes)
        assertEquals(counts.second + 1, observer.wordBrushes)
        val afterLineChange = observer.maskBrushes to observer.wordBrushes
        rule.mainClock.advanceTimeBy(250)
        drawPane()
        assertEquals(afterLineChange, observer.maskBrushes to observer.wordBrushes)
        rule.runOnIdle { paneWidth = 160.dp; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        drawPane()
        assertTrue(observer.maskBrushes > counts.first)
        assertTrue(observer.wordBrushes > counts.second)
    }
    @Test fun longLyricsConstructAndMeasureOnlyLocalWindow() {
        val sample = List(10000) { LyricLine(it * 1000L, "歌詞 $it") }
        now = now.copy(positionMs = 5000000)
        show(LyricsState.Synced(sample))
        assertTrue(observer.constructedRows.isNotEmpty())
        assertTrue(observer.constructedRows.size < 40)
        assertEquals(observer.constructedRows, observer.measuredRows)
        assertTrue(observer.constructedRows.all { kotlin.math.abs(it - 5000) < 30 })
        val counts = listOf(observer.compositions, observer.measures, observer.placements, observer.frameUpdates)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(counts, listOf(observer.compositions, observer.measures, observer.placements, observer.frameUpdates))
        println("window long: total=${sample.size}, constructed=${observer.constructedRows.size}, measured=${observer.measuredRows.size}, idleObserverDelta=0")
    }

    @Test fun firstWindowDisplaySnapsWithoutAppearanceJobs() {
        now = now.copy(positionMs = 1000)
        show()
        assertEquals(1f, observer.probes.getValue(0).alpha, 0f)
        assertEquals(0, observer.alphaJobs)
        assertEquals(0, observer.emphasisJobs)
    }

    @Test fun windowBeforeFirstToFirstPreservesFadeWithoutMovement() {
        now = now.copy(positionMs = 0)
        show()
        assertEquals(0.62f, observer.probes.getValue(0).alpha, 0f)
        moveTo(1, 200)
        val row = observer.probes.getValue(0)
        assertFalse(row.animated)
        assertTrue(row.alpha > 0.62f && row.alpha < 1f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1f, row.alpha, 0f)
    }

    @Test fun onlyCurrentWordRowHasOverlayAtRest() {
        val sample = List(100) { LyricLine(it * 1000L, "単語 $it", listOf(LyricWord(it * 1000L, 0))) }
        now = now.copy(positionMs = 50000)
        show(LyricsState.Synced(sample))
        assertEquals(setOf(50), observer.overlayRows)
        assertEquals(0, observer.alphaJobs)
        assertEquals(0, observer.emphasisJobs)
        println("word overlays at rest: ${observer.overlayRows.size}, alphaJobs=${observer.alphaJobs}, emphasisJobs=${observer.emphasisJobs}")
    }
    private fun ttmlLines(firstEnd: String = "1.4") = kotlinx.coroutines.runBlocking {
        val parsed = com.gt8d.launcher.media.lyrics.TtmlParser.parse("""
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="1" end="3"><span begin="1" end="$firstEnd">架空</span><span begin="1.5" end="2">の朝</span></p>
            <p begin="4" end="6">架空の空</p>
            </div></body></tt>
        """.trimIndent(), 8000)
        (parsed as com.gt8d.launcher.media.lyrics.TtmlParseResult.WordSynced).lines
    }

    @Test fun ttmlWordsUseExistingFill() {
        val sample = ttmlLines()
        assertEquals(listOf(LyricWord(1000, 0), LyricWord(1500, 2)), sample[0].words)
        assertEquals(1f, wordFillPosition(sample, 0, 1250, 8000), 0f)
        now = now.copy(playing = true, positionMs = 1250, positionUpdatedAt = clock)
        show(LyricsState.Synced(sample), detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_fill", useUnmergedTree = true).assertExists()
        val counts = listOf(observer.compositions, observer.measures, observer.placements)
        val frames = observer.frameUpdates
        repeat(10) {
            rule.runOnIdle { clock += 16 }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.waitForIdle()
        assertTrue(observer.frameUpdates > frames)
        assertEquals(counts, listOf(observer.compositions, observer.measures, observer.placements))
    }

    @Test fun ttmlEndDoesNotChangeFillModel() {
        val early = ttmlLines("1.1")
        val late = ttmlLines("1.4")
        assertEquals(early, late)
        assertEquals(1f, wordFillPosition(early, 0, 1250, 8000), 0f)
        assertEquals(3f, wordFillPosition(early, 0, 2750, 8000), 0f)
    }

    @Test fun ttmlPlainLineHasNoPerFrameLoop() {
        val sample = ttmlLines()
        assertTrue(sample[1].words.isEmpty())
        now = now.copy(playing = true, positionMs = 4500, positionUpdatedAt = clock)
        show(LyricsState.Synced(sample), detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("架空の空")
        rule.onNode(hasTestTag("player_lyrics_fill") and
            hasAnyAncestor(hasTestTag("player_lyrics_line_1")), useUnmergedTree = true).assertDoesNotExist()
        val counts = listOf(observer.compositions, observer.measures, observer.placements, observer.frameUpdates)
        repeat(30) {
            rule.runOnIdle { clock += 16 }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.waitForIdle()
        assertEquals(counts, listOf(observer.compositions, observer.measures, observer.placements, observer.frameUpdates))
    }
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val lines = listOf(LyricLine(1000, "一"), LyricLine(2000, "二"), LyricLine(3000, "三"))
    private var clock = 1000L
    private var paneWidth by mutableStateOf(329.dp)
    private var paneHeight by mutableStateOf(240.dp)
    private var fontScale by mutableFloatStateOf(1f)
    private var paneState by mutableStateOf<LyricsState>(LyricsState.Synced(lines))
    private val observer = LyricsPaneObserver()
    private var now by mutableStateOf(NowPlaying("dummy", "架空の曲", "架空の人", null, null, false, false,
        positionMs = 2500, metaTitle = "架空の曲"))
    private fun show(state: LyricsState = LyricsState.Synced(lines), detailSemantics: Boolean = false) {
        paneState = state
        rule.mainClock.autoAdvance = false
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            CompositionLocalProvider(LocalLyricsPaneObserver provides observer, LocalDensity provides Density(1f, fontScale)) {
                if (detailSemantics) {
                    CompositionLocalProvider(LocalLyricsDetailSemantics provides true) {
                        LyricsPane(paneState, now, Modifier.size(paneWidth, paneHeight)) { clock }
                    }
                } else {
                    LyricsPane(paneState, now, Modifier.size(paneWidth, paneHeight)) { clock }
                }
            }
        } }
        controller.visible()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    private fun moveTo(row: Int, elapsed: Long = 64) {
        rule.runOnIdle { now = now.copy(positionMs = row * 1000L); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(elapsed)
        rule.waitForIdle()
    }

    private fun drawPane() {
        rule.runOnIdle {
            val view = controller.get().window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            bitmap.recycle()
        }
    }

    @Test fun windowSizeDoesNotGrowWithSongLength() {
        now = now.copy(positionMs = 500000)
        show(LyricsState.Synced(List(1000) { LyricLine(it * 1000L, "歌詞") }))
        val shortWindow = observer.constructedRows
        rule.runOnIdle {
            paneState = LyricsState.Synced(List(100000) { LyricLine(it * 1000L, "歌詞") })
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(64)
        assertEquals(shortWindow, observer.constructedRows)
        assertEquals(shortWindow, observer.measuredRows)
        assertTrue(observer.probes.values.all { !it.animated })
        println("window song lengths: 1000=${shortWindow.size}, 100000=${observer.constructedRows.size}")
    }

    @Test fun windowMovementKeepsEnteringRowsAndShrinksOnlyAfterCompletion() {
        now = now.copy(positionMs = 500000)
        show(LyricsState.Synced(List(1000) { LyricLine(it * 1000L, "歌詞") }))
        val initial = observer.constructedRows
        val height = observer.probes.getValue(500).height
        moveTo(501)
        val moving = observer.constructedRows
        assertTrue(moving.containsAll(initial))
        for (i in 480..520) {
            val start = 120f - height / 2f + (i - 500) * (height + 16)
            val end = start - height - 16
            if (minOf(start, end) < 240 && maxOf(start, end) + height > 0) assertTrue(i in moving)
        }
        val counts = listOf(observer.compositions, observer.measures, observer.placements)
        val jobs = observer.alphaJobs to observer.emphasisJobs
        rule.mainClock.advanceTimeBy(350)
        assertEquals(counts, listOf(observer.compositions, observer.measures, observer.placements))
        assertEquals(jobs, observer.alphaJobs to observer.emphasisJobs)
        assertEquals(moving, observer.constructedRows)
        assertEquals(6, observer.alphaJobs)
        assertEquals(2, observer.emphasisJobs)
        rule.mainClock.advanceTimeBy(650)
        assertTrue(observer.constructedRows.size < moving.size)
        val current = observer.probes.getValue(501)
        assertEquals(120f, current.top + current.height / 2f, 0.5f)
        println("window movement: initial=${initial.size}, moving=${moving.size}, settled=${observer.constructedRows.size}, activeObserverDelta=0, alphaJobs=${jobs.first}, emphasisJobs=${jobs.second}")
    }

    @Test fun wordOverlaysSurviveMultipleChangesThenExpireWithoutRestart() {
        val sample = List(1000) { LyricLine(it * 1000L, "単語", listOf(LyricWord(it * 1000L, 0))) }
        now = now.copy(positionMs = 500500)
        show(LyricsState.Synced(sample))
        moveTo(501, 200)
        assertEquals(setOf(500, 501), observer.overlayRows)
        moveTo(502, 64)
        assertEquals(setOf(500, 501, 502), observer.overlayRows)
        println("word overlays during rapid changes: ${observer.overlayRows.size}")
        rule.mainClock.advanceTimeBy(350)
        assertEquals(setOf(501, 502), observer.overlayRows)
        rule.mainClock.advanceTimeBy(250)
        assertEquals(setOf(502), observer.overlayRows)
        moveTo(505)
        assertEquals(setOf(505), observer.overlayRows)
        assertTrue(observer.probes.values.all { !it.animated })
    }

    @Test fun windowJumpsBackwardsAndTrackChangesSnap() {
        now = now.copy(positionMs = 500000)
        show(LyricsState.Synced(List(1000) { LyricLine(it * 1000L, "歌詞") }))
        moveTo(501, 200)
        for (row in listOf(503, 499)) {
            moveTo(row)
            assertTrue(observer.probes.values.all { !it.animated })
            val current = observer.probes.getValue(row)
            assertEquals(120f, current.top + current.height / 2f, 0.5f)
        }
        moveTo(500, 200)
        rule.runOnIdle { now = now.copy(title = "別の曲", metaTitle = "別の曲"); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        assertTrue(observer.probes.values.all { !it.animated })
        val current = observer.probes.getValue(500)
        assertEquals(120f, current.top + current.height / 2f, 0.5f)
        val rows = observer.constructedRows
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(rows, observer.constructedRows)
    }

    @Test fun widthFontAndZeroSizeInvalidateWindowGeometry() {
        now = now.copy(positionMs = 500000)
        show(LyricsState.Synced(List(1000) { LyricLine(it * 1000L, "歌詞の長い行\n折り返し") }))
        moveTo(501, 200)
        rule.runOnIdle { paneWidth = 160.dp; fontScale = 1.5f; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        assertTrue(observer.probes.values.all { !it.animated })
        val current = observer.probes.getValue(501)
        assertEquals(120f, current.top + current.height / 2f, 0.5f)
        rule.runOnIdle { paneWidth = 0.dp; paneHeight = 0.dp; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        assertTrue(observer.constructedRows.size < 40)
        rule.runOnIdle { paneWidth = 329.dp; paneHeight = 240.dp; Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        assertTrue(observer.probes.values.all { !it.animated })
        val restored = observer.probes.getValue(501)
        assertEquals(120f, restored.top + restored.height / 2f, 0.5f)
    }
    private fun center(text: String): Float {
        val rect = rule.onNodeWithText(text).getBoundsInRoot()
        return (rect.top.value + rect.bottom.value) / 2
    }
    private fun areaCenter(): Float {
        val rect = rule.onNodeWithTag("player_lyrics").getUnclippedBoundsInRoot()
        return (rect.top.value + rect.bottom.value) / 2
    }
    @Test fun highlightsCurrentLine() {
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
    }
    @Test fun beforeFirstLineHasNoCurrent() {
        now = now.copy(positionMs = 500)
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertDoesNotExist()
        rule.onNodeWithText("一").assertIsDisplayed()
        assertEquals(areaCenter(), center("一"), 0.5f)
    }
    @Test fun nextLineAnimates() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("一")
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
        assertTrue("line=${center("二")}, area=${areaCenter()}", center("二") > areaCenter() + 0.5f)
        rule.mainClock.advanceTimeBy(700)
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
    @Test fun scrollTakes800msWhileFadeTakes500ms() {
        now = now.copy(positionMs = 1000)
        show(detailSemantics = true)
        rule.runOnIdle {
            now = now.copy(positionMs = 2000)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
        assertEquals(1f, alpha(1), 0f)
        assertTrue(center("二") > areaCenter() + 0.5f)
        rule.mainClock.advanceTimeBy(300)
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
    @Test fun retargetDuringScrollIsContinuous() {
        now = now.copy(positionMs = 1000)
        show(detailSemantics = true)
        val rowDistance = center("三") - center("二")
        rule.runOnIdle {
            now = now.copy(positionMs = 2000)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(300)
        var before = center("三")
        rule.runOnIdle {
            now = now.copy(positionMs = 3000)
            Snapshot.sendApplyNotifications()
        }
        repeat(2) {
            rule.mainClock.advanceTimeByFrame()
            val after = center("三")
            assertTrue("before=$before, after=$after, rowDistance=$rowDistance",
                kotlin.math.abs(after - before) < rowDistance)
            before = after
        }
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("三")
        rule.mainClock.advanceTimeBy(900)
        assertEquals(areaCenter(), center("三"), 0.5f)
    }
    @Test fun lyricTextIs22sp() {
        show(detailSemantics = true)
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("player_lyrics_current")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(22.sp, results.single().layoutInput.style.fontSize)
    }
    @Test fun jumpSnapsWithoutAnimation() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("一")
        rule.runOnIdle { clock = 3500 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("三")
        assertEquals(areaCenter(), center("三"), 0.5f)
    }
    @Test fun plainIsStatic() {
        val text = (1..40).joinToString("\n") { "架空の文 $it" }
        show(LyricsState.Plain(text))
        val before = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
        rule.onNodeWithTag("player_lyrics").performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(400)
        assertEquals(before, rule.onNodeWithText(text).getUnclippedBoundsInRoot())
    }
    @Test fun trackChangeSnapsWithoutAnimation() {
        now = now.copy(positionMs = 1000)
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("一")
        rule.runOnIdle {
            now = now.copy(title = "次の架空曲", metaTitle = "次の架空曲", positionMs = 2500)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
    @Test fun loadingText() {
        show(LyricsState.Loading)
        rule.onNodeWithText("歌詞を探しています…").assertIsDisplayed()
        rule.onNodeWithTag("player_lyrics_line_0").assertDoesNotExist()
        val before = rule.onNodeWithText("歌詞を探しています…").getBoundsInRoot()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(before, rule.onNodeWithText("歌詞を探しています…").getBoundsInRoot())
        assertEquals(0, observer.motionFrames)
    }
    @Test fun lyricsAreaNotClickable() {
        show()
        rule.onNodeWithTag("player_lyrics").assert(hasClickAction().not())
    }
    @Test fun lineLevelHighlightWhenNoWords() {
        show(detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二").assertIsDisplayed()
        rule.onNodeWithTag("player_lyrics_fill", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun wordFillWhenWordsPresent() {
        show(LyricsState.Synced(listOf(LyricLine(1000, "架空の朝", listOf(LyricWord(1000, 0))))), detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_fill", useUnmergedTree = true).assertExists()
    }
    @Test fun interludeDotsOnEmptyLine() {
        show(LyricsState.Synced(listOf(LyricLine(1000, "♪"), LyricLine(4000, "架空の朝"))), detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_interlude").assertIsDisplayed()
    }
    @Test fun introDotsBeforeFirstLine() {
        now = now.copy(positionMs = 1000)
        show(LyricsState.Synced(listOf(LyricLine(10000, "架空の朝"))), detailSemantics = true)
        rule.onNodeWithTag("player_lyrics_interlude").assertIsDisplayed()
        rule.runOnIdle {
            now = now.copy(positionMs = 6000)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("player_lyrics_interlude").assertDoesNotExist()
        rule.onNodeWithText("架空の朝").assertIsDisplayed()
    }
    @Test fun interludeDotsFillProgress() {
        assertEquals(0, interludeLitDots(0, 3000, 0))
        assertEquals(1, interludeLitDots(0, 3000, 1000))
        assertEquals(2, interludeLitDots(0, 3000, 2000))
        assertEquals(3, interludeLitDots(0, 3000, 3000))
        assertEquals(0, interludeLitDots(1000, 3000, 0))
        assertEquals(3, interludeLitDots(0, 3000, 4000))
    }
    @Test fun wordFillInterpolatesCharacters() {
        val sample = listOf(LyricLine(1000, "架空の朝", listOf(LyricWord(1000, 0), LyricWord(2000, 3))),
            LyricLine(4000, "架空の空"))
        assertEquals(0f, wordFillPosition(sample, 0, 500, null), 0f)
        assertEquals(0f, wordFillPosition(sample, -1, 500, null), 0f)
        assertEquals(0f, wordFillPosition(sample, 0, 1000, null), 0f)
        assertEquals(1.5f, wordFillPosition(sample, 0, 1500, null), 0f)
        assertEquals(3f, wordFillPosition(sample, 0, 2000, null), 0f)
        assertEquals(3.5f, wordFillPosition(sample, 0, 3000, null), 0f)
        assertEquals(4f, wordFillPosition(sample, 0, 4000, null), 0f)
    }
    @Test fun wordFillLastLineAndNonPositiveInterval() {
        val sample = listOf(LyricLine(3000, "架空の朝", listOf(LyricWord(3000, 0))))
        assertEquals(2f, wordFillPosition(sample, 0, 5500, null), 0f)
        assertEquals(2f, wordFillPosition(sample, 0, 3500, 4000), 0f)
        assertEquals(4f, wordFillPosition(sample, 0, 3000, 2000), 0f)
        val duplicate = listOf(sample.single(), LyricLine(3000, "架空の空"))
        assertEquals(4f, wordFillPosition(duplicate, 0, 3000, null), 0f)
    }
    @Test fun lineAlphaByDistance() {
        assertEquals(1f, lineTargetAlpha(3, 3, false), 0f)
        assertEquals(0.62f, lineTargetAlpha(4, 3, false), 0f)
        assertEquals(0.45f, lineTargetAlpha(2, 3, false), 0f)
        assertEquals(0.35f, lineTargetAlpha(1, 3, false), 0f)
        assertEquals(0.35f, lineTargetAlpha(5, 3, false), 0f)
        assertEquals(0.25f, lineTargetAlpha(0, 3, false), 0f)
        assertEquals(0.25f, lineTargetAlpha(13, 3, false), 0f)
    }
    @Test fun linesAreLeftAligned() {
        show(detailSemantics = true)
        val left = rule.onNodeWithTag("player_lyrics").getBoundsInRoot().left.value
        lines.forEach {
            assertEquals(left, rule.onNodeWithText(it.text).getBoundsInRoot().left.value, 1f)
        }
    }
    @Test fun currentLineCenteredWithUniformSize() {
        show(detailSemantics = true)
        val current = rule.onNodeWithTag("player_lyrics_current").getBoundsInRoot()
        assertEquals(areaCenter(), (current.top.value + current.bottom.value) / 2, 1f)
        listOf(0, 2).forEach {
            assertEquals(probe(1).height, probe(it).height)
            assertEquals(LyricsVisualStyle.InactiveScale, probe(it).scale, 0f)
        }
        assertEquals(1f, probe(1).scale, 0f)
    }

    private fun alpha(index: Int): Float = rule.onNodeWithTag("player_lyrics_line_$index")
        .fetchSemanticsNode().config[LyricDrawProbeKey].alpha

    @Test fun currentLineFadesIn() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        assertEquals(0.62f, alpha(1), 0f)
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(250)
        assertTrue(alpha(1) > 0.62f && alpha(1) < 1f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1f, alpha(1), 0f)
    }

    @Test fun previousLineFadesOut() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        assertEquals(1f, alpha(0), 0f)
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(250)
        assertTrue(alpha(0) > 0.45f && alpha(0) < 1f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0.45f, alpha(0), 0f)
    }

    @Test fun jumpSetsAlphaImmediately() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        rule.runOnIdle { clock = 3500 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1f, alpha(2), 0f)
        assertEquals(0.35f, alpha(0), 0f)
    }

    @Test fun noPerFrameLoopForLineLevelLyrics() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show()
        val before = observer.compositions
        val framesBefore = observer.frameUpdates
        repeat(30) {
            rule.runOnIdle { clock += 16 }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.waitForIdle()
        assertEquals(before, observer.compositions)
        assertEquals(framesBefore, observer.frameUpdates)
    }

    @Test fun scrollAnimationDoesNotRelayout() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show(detailSemantics = true)
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val before = observer.measures
        val placementsBefore = observer.placements
        repeat(56) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        assertEquals(before, observer.measures)
        assertEquals(placementsBefore, observer.placements)
        assertEquals(areaCenter(), center("二"), 0.5f)
    }

    @Test fun syncedLyricsExposeSingleNode() {
        show()
        rule.onAllNodesWithTag("player_lyrics", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("player_lyrics", useUnmergedTree = true)
            .assertContentDescriptionEquals("歌詞")
        rule.onAllNodesWithText("一", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun semanticsStableAcrossLineChange() {
        now = now.copy(positionMs = 1000)
        show()
        val before = rule.onNodeWithTag("player_lyrics", useUnmergedTree = true)
            .fetchSemanticsNode().config
        rule.runOnIdle {
            now = now.copy(positionMs = 2500)
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        val after = rule.onNodeWithTag("player_lyrics", useUnmergedTree = true)
            .fetchSemanticsNode().config
        assertEquals(before, after)
    }

    private fun probe(index: Int): LyricDrawProbe = rule.onNodeWithTag("player_lyrics_line_$index")
        .fetchSemanticsNode().config[LyricDrawProbeKey]

    @Test fun scaleOriginIsLeftCenter() {
        show(detailSemantics = true)
        for (i in lines.indices) {
            assertEquals(0f, probe(i).origin.pivotFractionX, 0f)
            assertEquals(0.5f, probe(i).origin.pivotFractionY, 0f)
        }
    }

    @Test fun scaleDoesNotChangeMeasuredRows() {
        val sample = listOf(LyricLine(1000, "短い行"), LyricLine(2000, "二行の\n架空の歌詞"), LyricLine(3000, "最後"))
        now = now.copy(positionMs = 1000)
        show(LyricsState.Synced(sample), detailSemantics = true)
        val heights = sample.indices.map { probe(it).height }
        assertTrue(heights[1] > heights[0])
        val gap = probe(1).top - probe(0).top - heights[0]
        rule.runOnIdle { now = now.copy(positionMs = 2000); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(1000)
        sample.indices.forEach { assertEquals(heights[it], probe(it).height) }
        assertEquals(gap, probe(1).top - probe(0).top - heights[0], 0.5f)
        assertEquals(16f, gap, 0.5f)
    }

    @Test fun nextLineAndWordBaseUseReadableAlpha() {
        now = now.copy(positionMs = 1000)
        show(LyricsState.Synced(listOf(LyricLine(1000, "単語", listOf(LyricWord(1000, 0))), LyricLine(2000, "次"))), true)
        assertEquals(0.62f, alpha(0), 0f)
        assertEquals(0.62f, alpha(1), 0f)
    }

    @Test fun fadeCompletesIn500ms() {
        now = now.copy(positionMs = 1000)
        show(detailSemantics = true)
        rule.runOnIdle { now = now.copy(positionMs = 2000); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(250)
        assertTrue(alpha(1) > 0.62f && alpha(1) < 1f)
        rule.mainClock.advanceTimeBy(350)
        assertEquals(1f, alpha(1), 0f)
        assertEquals(0.97f, probe(0).scale, 0f)
        assertEquals(1f, probe(1).scale, 0f)
    }

    @Test fun plainAndLoadingDoNotStaggerOrScale() {
        show(LyricsState.Plain("静止"), true)
        rule.onNodeWithTag("player_lyrics_line_0").assertDoesNotExist()
        val before = rule.onNodeWithText("静止").getUnclippedBoundsInRoot()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(before, rule.onNodeWithText("静止").getUnclippedBoundsInRoot())
        assertEquals(0, observer.motionFrames)
    }

    @Test fun staggerAndScaleDoNotRecomposeOrRelayout() {
        now = now.copy(positionMs = 1000)
        show(detailSemantics = true)
        rule.runOnIdle { now = now.copy(positionMs = 2000); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        val counts = listOf(observer.compositions, observer.measures, observer.placements)
        rule.mainClock.advanceTimeBy(900)
        rule.waitForIdle()
        assertEquals(counts, listOf(observer.compositions, observer.measures, observer.placements))
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
}
