package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1000dp")
class LyricsPaneTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val lines = listOf(LyricLine(1000, "一"), LyricLine(2000, "二"), LyricLine(3000, "三"))
    private var clock = 1000L
    private val observer = LyricsPaneObserver()
    private var now by mutableStateOf(NowPlaying("dummy", "架空の曲", "架空の人", null, null, false, false,
        positionMs = 2500, metaTitle = "架空の曲"))
    private fun show(state: LyricsState = LyricsState.Synced(lines), detailSemantics: Boolean = false) {
        rule.mainClock.autoAdvance = false
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            CompositionLocalProvider(LocalLyricsPaneObserver provides observer, LocalDensity provides Density(1f, 1f)) {
                if (detailSemantics) {
                    CompositionLocalProvider(LocalLyricsDetailSemantics provides true) {
                        LyricsPane(state, now, Modifier.size(329.dp, 240.dp)) { clock }
                    }
                } else {
                    LyricsPane(state, now, Modifier.size(329.dp, 240.dp)) { clock }
                }
            }
        } }
        controller.visible()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
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
