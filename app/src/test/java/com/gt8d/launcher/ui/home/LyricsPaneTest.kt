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
    private var now by mutableStateOf(NowPlaying("dummy", "架空の曲", "架空の人", null, null, false, false,
        positionMs = 2500, metaTitle = "架空の曲"))
    private fun show(state: LyricsState = LyricsState.Synced(lines)) {
        rule.mainClock.autoAdvance = false
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            LyricsPane(state, now, Modifier.size(329.dp, 240.dp)) { clock }
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
        rule.mainClock.advanceTimeBy(150)
        rule.onNodeWithTag("player_lyrics_current").assertTextEquals("二")
        assertTrue(center("二") > areaCenter() + 0.5f)
        rule.mainClock.advanceTimeBy(300)
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
    @Test fun fillFractionBasics() {
        val sample = listOf(LyricLine(1000, "架空の朝"), LyricLine(3000, "架空の空"))
        assertEquals(0f, fillFraction(sample, 0, 1000, null), 0f)
        assertEquals(0.5f, fillFraction(sample, 0, 2000, null), 0f)
        assertEquals(1f, fillFraction(sample, 0, 3000, null), 0f)
        assertEquals(0f, fillFraction(sample, 0, 500, null), 0f)
        assertEquals(0f, fillFraction(sample, -1, 500, null), 0f)
    }
    @Test fun fillFractionLastLine() {
        val sample = listOf(LyricLine(3000, "架空の空"))
        assertEquals(0.5f, fillFraction(sample, 0, 5500, null), 0f)
        assertEquals(0.5f, fillFraction(sample, 0, 3500, 4000), 0f)
    }
    @Test fun fillFractionNonPositiveInterval() {
        val sample = listOf(LyricLine(1000, "架空の朝"), LyricLine(1000, "架空の空"))
        assertEquals(1f, fillFraction(sample, 0, 1000, null), 0f)
        assertEquals(1f, fillFraction(sample, 1, 1000, 500), 0f)
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
}
