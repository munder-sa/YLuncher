package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.media.lyrics.LyricWord
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
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
    private fun show(state: LyricsState = LyricsState.Synced(lines)) {
        rule.mainClock.autoAdvance = false
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            CompositionLocalProvider(LocalLyricsPaneObserver provides observer) {
                LyricsPane(state, now, Modifier.size(329.dp, 240.dp)) { clock }
            }
        } }
        controller.visible()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    private fun center(text: String): Float {
        val rect = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
        return (rect.top.value + rect.bottom.value) / 2
    }
    private fun areaCenter(): Float {
        val rect = rule.onNodeWithTag("player_lyrics").getUnclippedBoundsInRoot()
        return (rect.top.value + rect.bottom.value) / 2
    }
    @Test fun highlightsCurrentLine() {
        show()
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
    }
    @Test fun beforeFirstLineHasNoCurrent() {
        now = now.copy(positionMs = 500)
        show()
        rule.onNodeWithTag("player_lyrics_current").assertDoesNotExist()
        rule.onNodeWithText("一").assertIsDisplayed()
        assertEquals(areaCenter(), center("一"), 0.5f)
    }
    @Test fun nextLineAnimates() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show()
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("一")
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
        assertTrue("line=${center("二")}, area=${areaCenter()}", center("二") > areaCenter() + 0.5f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
    @Test fun jumpSnapsWithoutAnimation() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show()
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
        show()
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
    }
    @Test fun lyricsAreaNotClickable() {
        show()
        rule.onNodeWithTag("player_lyrics").assert(hasClickAction().not())
    }
    @Test fun lineLevelHighlightWhenNoWords() {
        show()
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二").assertIsDisplayed()
        rule.onNodeWithTag("player_lyrics_fill", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun wordFillWhenWordsPresent() {
        show(LyricsState.Synced(listOf(LyricLine(1000, "架空の朝", listOf(LyricWord(1000, 0))))))
        rule.onNodeWithTag("player_lyrics_fill", useUnmergedTree = true).assertExists()
    }
    @Test fun interludeDotsOnEmptyLine() {
        show(LyricsState.Synced(listOf(LyricLine(1000, "♪"), LyricLine(4000, "架空の朝"))))
        rule.onNodeWithTag("player_lyrics_interlude").assertIsDisplayed()
    }
    @Test fun introDotsBeforeFirstLine() {
        now = now.copy(positionMs = 1000)
        show(LyricsState.Synced(listOf(LyricLine(10000, "架空の朝"))))
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
        assertEquals(0.45f, lyricLineAlpha(1), 0f)
        assertEquals(0.35f, lyricLineAlpha(2), 0f)
        assertEquals(0.25f, lyricLineAlpha(3), 0f)
        assertEquals(0.25f, lyricLineAlpha(10), 0f)
    }
    @Test fun linesAreLeftAligned() {
        show()
        val left = rule.onNodeWithTag("player_lyrics").getBoundsInRoot().left.value
        lines.forEach {
            assertEquals(left, rule.onNodeWithText(it.text).getBoundsInRoot().left.value, 1f)
        }
    }
    @Test fun currentLineCenteredWithUniformSize() {
        show()
        val current = rule.onNodeWithTag("player_lyrics_current").getBoundsInRoot()
        assertEquals(areaCenter(), (current.top.value + current.bottom.value) / 2, 1f)
        listOf("一", "三").forEach {
            val other = rule.onNodeWithText(it).getBoundsInRoot()
            assertEquals(current.bottom.value - current.top.value, other.bottom.value - other.top.value, 0f)
        }
    }

    private fun alpha(index: Int): Float = rule.onNodeWithTag("player_lyrics_line_$index")
        .fetchSemanticsNode().config[LyricDrawProbeKey].alpha

    @Test fun currentLineFadesIn() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show()
        assertEquals(0.45f, alpha(1), 0f)
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(250)
        assertTrue(alpha(1) > 0.45f && alpha(1) < 1f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1f, alpha(1), 0f)
    }

    @Test fun previousLineFadesOut() {
        now = now.copy(playing = true, positionMs = 0, positionUpdatedAt = 0)
        show()
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
        show()
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
        show()
        rule.runOnIdle { clock = 2100 }
        rule.mainClock.advanceTimeBy(200)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val before = observer.measures
        val placementsBefore = observer.placements
        repeat(32) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        assertEquals(before, observer.measures)
        assertEquals(placementsBefore, observer.placements)
        assertEquals(areaCenter(), center("二"), 0.5f)
    }
}
