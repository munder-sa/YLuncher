package com.gt8d.launcher.ui.home

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.panel.MediaAction
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
class HomeMiniPlayerTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val song = NowPlaying("music", "長い曲名".repeat(30), "アーティスト", null, 120000L, true, true)
    private val idle = AppInfo("idle", "音楽アプリ", null, true)
    private fun host(now: NowPlaying? = song, height: Int = 800, idleApp: AppInfo = idle,
                     onAction: (MediaAction) -> Unit = {}, onSeek: (Long) -> Unit = {}, onLaunch: (String) -> Unit = {}) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            HomeMiniPlayer(now, idleApp, 30000L, { AppInfo(it, "音楽", null, true) },
                onAction, onSeek, onLaunch, Modifier.size(360.dp, height.dp))
        } }
        controller.visible()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    @Test fun paused_showsPlayerWithPlayIcon() {
        host(song.copy(playing = false))
        rule.onNodeWithTag("player_title").assertIsDisplayed()
        rule.onNodeWithTag("player_play").assert(hasContentDescription("再生"))
        rule.onNodeWithTag("player_idle_app").assertDoesNotExist()
    }
    @Test fun seek_sendsOnceOnRelease() {
        val seeks = mutableListOf<Long>()
        host(onSeek = { seeks.add(it) })
        val seek = rule.onNodeWithTag("player_seek")
        seek.performTouchInput { down(center); moveTo(Offset(width - 1f, centerY)) }
        rule.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
        seek.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf(120000L), seeks) }
        seek.performTouchInput { down(center); moveTo(Offset(1f, centerY)) }
        rule.runOnIdle { assertEquals(listOf(120000L), seeks) }
        seek.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf(120000L, 0L), seeks) }
    }
    @Test fun unknownDuration_hidesSeek() {
        host(song.copy(durationMs = null))
        rule.onNodeWithTag("player_seek").assertDoesNotExist()
        rule.onNodeWithTag("player_times").assertDoesNotExist()
    }
    @Test fun idle_uninstalled_showsWarning_notClickable() {
        host(null, idleApp = idle.copy(installed = false))
        rule.onNodeWithText("アプリが見つかりません").assertIsDisplayed()
        rule.onNodeWithTag("player_idle_app").assertHasNoClickAction()
        rule.onNodeWithText("タップで開く").assertDoesNotExist()
    }
    @Test fun height400_keepsTitleAndPlay() {
        host(height = 400)
        rule.onNodeWithTag("player_title").assertIsDisplayed()
        rule.onNodeWithTag("player_play").assertIsDisplayed().assertWidthIsAtLeast(96.dp).assertHeightIsAtLeast(96.dp)
        listOf("player_fav", "player_prev", "player_next").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed().assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(72.dp)
        }
    }
    @Test fun playing_showsTitleArtistAndControls() {
        host()
        listOf("player_title", "player_artist", "player_seek", "player_prev", "player_play", "player_next", "player_fav").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed()
        }
    }
    @Test fun play_pause_next_prev_fav_callActions() {
        val actions = mutableListOf<MediaAction>()
        host(onAction = { actions.add(it) })
        listOf("player_play", "player_next", "player_prev", "player_fav").forEach { rule.onNodeWithTag(it).performClick() }
        assertEquals(listOf(MediaAction.Pause, MediaAction.Next, MediaAction.Prev, MediaAction.Favorite), actions)
        controller.pause().stop().destroy()
        host(song.copy(playing = false), onAction = { actions.add(it) })
        rule.onNodeWithTag("player_play").performClick()
        assertEquals(MediaAction.Play, actions.last())
    }
    @Test fun noFavorite_hidesFav() {
        host(song.copy(hasFavorite = false))
        rule.onNodeWithTag("player_fav").assertDoesNotExist()
    }
    @Test fun idle_showsIdleApp_andOpens() {
        var pkg: String? = null
        host(null, onLaunch = { pkg = it })
        rule.onNodeWithText("音楽アプリ").assertIsDisplayed()
        rule.onNodeWithText("タップで開く").assertIsDisplayed()
        rule.onNodeWithTag("player_idle_app").performClick()
        assertEquals("idle", pkg)
    }
    @Test fun shortHeight_keepsTitleAndControlsVisible() {
        host(height = 480)
        listOf("player_title", "player_play", "player_prev", "player_next", "player_fav").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed()
        }
    }
    @Test fun longTitle_isOneLine() {
        host()
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("player_title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
    }
}
