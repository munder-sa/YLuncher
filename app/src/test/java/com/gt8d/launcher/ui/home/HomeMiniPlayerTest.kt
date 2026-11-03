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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.media.lyrics.LyricLine
import androidx.compose.runtime.mutableStateOf
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1000dp")
class HomeMiniPlayerTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private val song = NowPlaying("music", "長い曲名".repeat(30), "アーティスト", null, 120000L, true, true)
    private val idle = AppInfo("idle", "音楽アプリ", null, true)
    private fun host(now: NowPlaying? = song, height: Int = 800, idleApp: AppInfo = idle,
                     installed: Boolean = true, width: Int = 360, scale: Float = 1f,
                     onAction: (MediaAction) -> Unit = {}, onSeek: (Long) -> Unit = {}, onLaunch: (String) -> Unit = {},
                     lyrics: LyricsState = LyricsState.Off, lyricsEnabled: Boolean = false,
                     onToggleLyrics: () -> Unit = {}) {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HomeMiniPlayer(now, idleApp, 30000L, { AppInfo(it, "音楽", null, installed) },
                    onAction, onSeek, onLaunch, Modifier.size(width.dp, height.dp), lyrics, lyricsEnabled, onToggleLyrics)
            }
        } }
        controller.visible()
    }
    @After fun cleanup() { if (::controller.isInitialized) controller.pause().stop().destroy() }
    private val lyricSong = song.copy(title = "架空の曲", metaTitle = "架空の曲", playing = false, hasFavorite = false)
    private val synced = LyricsState.Synced(listOf(LyricLine(0, "架空の一行"), LyricLine(1000, "架空の二行")))
    private fun bounds(tag: String) = rule.onNodeWithTag(tag, true).getUnclippedBoundsInRoot()
    private fun equalDp(expected: androidx.compose.ui.unit.Dp, actual: androidx.compose.ui.unit.Dp) =
        assertEquals(expected.value, actual.value, 0.5f)
    private fun lyricHost(height: Int = 694, favorite: Boolean = false, state: LyricsState = synced,
                          enabled: Boolean = false, toggle: () -> Unit = {}) =
        host(lyricSong.copy(hasFavorite = favorite), height = height, width = 409,
            lyrics = state, lyricsEnabled = enabled, onToggleLyrics = toggle)
    @Test fun toggleInAppRowWhenTall() {
        lyricHost()
        val box = bounds("player_lyrics_toggle_box")
        val touch = bounds("player_lyrics_toggle")
        equalDp(48.dp, box.right - box.left); equalDp(48.dp, box.bottom - box.top)
        equalDp(72.dp, touch.right - touch.left); equalDp(72.dp, touch.bottom - touch.top)
        equalDp(bounds("player_seek").right, box.right)
        equalDp((bounds("player_app_row").top + bounds("player_app_row").bottom) / 2, (box.top + box.bottom) / 2)
    }
    @Test fun toggleInTitleRowWhenShort() {
        lyricHost(height = 300)
        rule.onNodeWithTag("player_app_row").assertDoesNotExist()
        equalDp(bounds("player_seek").right, bounds("player_lyrics_toggle_box").right)
        val row = bounds("player_title_row")
        val box = bounds("player_lyrics_toggle_box")
        equalDp((row.top + row.bottom) / 2, (box.top + box.bottom) / 2)
    }
    @Test fun toggleBesideFavorite() {
        lyricHost(height = 300, favorite = true)
        rule.onNodeWithTag("player_app_row").assertDoesNotExist()
        equalDp(bounds("player_lyrics_toggle_box").right + 8.dp, bounds("player_fav").left)
        equalDp(bounds("player_seek").right, bounds("player_fav").right)
        assertTrue(bounds("player_lyrics_toggle").right <= bounds("player_fav").left)
    }
    @Test fun tapCallsOnToggle() {
        var calls = 0
        lyricHost(toggle = { calls++ })
        rule.onNodeWithTag("player_lyrics_toggle").performClick()
        assertEquals(1, calls)
    }
    @Test fun onShowsLyricsInsteadOfArt() {
        lyricHost(enabled = true)
        rule.onNodeWithTag("player_lyrics").assertIsDisplayed()
        rule.onNodeWithTag("player_art").assertDoesNotExist()
    }
    @Test fun missingFallsBackToArt() {
        listOf(LyricsState.Missing to "歌詞なし", LyricsState.Offline to "オフライン", LyricsState.Failed to "取得できません")
            .forEachIndexed { i, (state, reason) ->
                if (i > 0) controller.pause().stop().destroy()
                lyricHost(state = state, enabled = true)
                rule.onNodeWithTag("player_art").assertIsDisplayed()
                rule.onNodeWithTag("player_lyrics").assertDoesNotExist()
                rule.onNodeWithText(reason).assertIsDisplayed()
            }
    }
    @Test fun unavailableToggleStillTurnsOff() {
        var calls = 0
        lyricHost(state = LyricsState.Missing, enabled = true, toggle = { calls++ })
        rule.onNodeWithTag("player_lyrics_toggle")
            .assertContentDescriptionEquals("歌詞なし。押すと歌詞表示をオフ").performClick()
        assertEquals(1, calls)
    }
    @Test fun loadingShowsSearching() {
        lyricHost(state = LyricsState.Loading, enabled = true)
        rule.onNodeWithText("歌詞を探しています…").assertIsDisplayed()
    }
    @Test fun noMetaTitleHidesToggle() {
        host(lyricSong.copy(metaTitle = null), width = 409)
        rule.onNodeWithTag("player_lyrics_toggle").assertDoesNotExist()
    }
    @Test fun noArtistHidesToggle() {
        host(lyricSong.copy(artist = null), width = 409)
        rule.onNodeWithTag("player_lyrics_toggle").assertDoesNotExist()
    }
    @Test fun idleCardHasNoToggle() {
        host(null, width = 409)
        rule.onNodeWithTag("player_lyrics_toggle").assertDoesNotExist()
    }
    @Test fun lyricsHeightThreshold() {
        val height = mutableStateOf(694)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LauncherTheme {
            HomeMiniPlayer(lyricSong, idle, 0, { idle }, {}, {}, {}, Modifier.size(409.dp, height.value.dp), synced, true)
        } }
        controller.visible()
        var lastHeight = 0.dp
        for (h in 694 downTo 200) {
            rule.runOnIdle { height.value = h }
            if (rule.onAllNodesWithTag("player_lyrics").fetchSemanticsNodes().isEmpty()) {
                equalDp(120.dp, lastHeight)
                rule.onNodeWithTag("player_art").assertExists()
                return
            }
            lastHeight = bounds("player_lyrics").bottom - bounds("player_lyrics").top
            assertTrue(lastHeight >= 120.dp)
        }
        fail("歌詞の高さの境界が見つからない")
    }
    @Test fun spacingTierUnchangedByToggle() {
        lyricHost()
        val top = bounds("player_title_row").top
        controller.pause().stop().destroy()
        lyricHost(enabled = true)
        equalDp(top, bounds("player_title_row").top)
    }
    @Test fun offShowsArtAndToggleOff() {
        lyricHost()
        rule.onNodeWithTag("player_art").assertIsDisplayed()
        rule.onNodeWithTag("player_lyrics_toggle").assertIsOff().assertContentDescriptionEquals("歌詞を表示")
    }
    @Test fun artTap_launchesPlayerApp() {
        val launches = mutableListOf<String>()
        host(onLaunch = { launches.add(it) })
        rule.onNodeWithTag("player_art").performClick()
        assertEquals(listOf("music"), launches)
    }
    @Test fun artTap_uninstalled_doesNotLaunchPlayerApp() {
        val launches = mutableListOf<String>()
        host(installed = false, onLaunch = { launches.add(it) })
        rule.onNodeWithTag("player_art").assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(emptyList<String>(), launches)
    }
    @Test fun padding_titleStarts40dpInsidePlayer() {
        host()
        val player = rule.onNodeWithTag("home_player").fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithTag("player_title").fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertEquals(40f * density, title.left - player.left, 1f * density)
    }
    @Test fun height480_keepsAllElementsInsidePlayer() {
        host(height = 480)
        val player = rule.onNodeWithTag("home_player").fetchSemanticsNode().boundsInRoot
        listOf("player_title", "player_prev", "player_play", "player_next", "player_fav").forEach {
            val child = rule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$it top", child.top >= player.top)
            assertTrue("$it bottom", child.bottom <= player.bottom)
        }
        val absent = mutableListOf<String>()
        listOf("player_art", "player_artist", "player_seek", "player_times").forEach { tag ->
            val nodes = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (nodes.isEmpty()) {
                absent.add(tag)
            } else {
                nodes.forEach { node ->
                    val child = node.boundsInRoot
                    assertTrue("$tag top", child.top >= player.top)
                    assertTrue("$tag bottom", child.bottom <= player.bottom)
                }
            }
        }
        println("height480 absent elements: $absent")
        rule.onNodeWithTag("player_play").assertWidthIsAtLeast(96.dp).assertHeightIsAtLeast(96.dp)
    }
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
        rule.onNodeWithTag("player_idle_open").assertDoesNotExist()
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
        rule.onNodeWithTag("player_text").assertWidthIsEqualTo(280.dp)
    }
    @Test fun favorite_isBesideTitle() {
        assertFavoriteBesideTitle(width = 409)
    }
    @Test fun favorite_isBesideTitle_narrowWidth() {
        assertFavoriteBesideTitle(width = 336)
    }
    private fun assertFavoriteBesideTitle(width: Int) {
        host(width = width, height = 800)
        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val favorite = bounds("player_fav")
        val density = rule.density.density
        assertTrue(favorite.center.y >= bounds("player_title").top)
        assertTrue(favorite.center.y <= bounds("player_artist").bottom)
        assertTrue(favorite.right <= bounds("home_player").right - 40f * density + density)
        assertEquals(8f * density, favorite.left - bounds("player_text").right, density)
        assertTrue(favorite.bottom < bounds("player_play").top)
    }
    @Test fun controls_singleRow() {
        host(width = 336)
        fun centerY(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.y
        assertEquals(centerY("player_play"), centerY("player_prev"), 1f)
        assertEquals(centerY("player_play"), centerY("player_next"), 1f)
    }
    @Test fun idle_showsIdleApp_andOpens() {
        val launches = mutableListOf<String>()
        host(null, onLaunch = { launches.add(it) })
        rule.onNodeWithText("音楽アプリ").assertIsDisplayed()
        rule.onNodeWithText("再生情報はありません").assertIsDisplayed()
        rule.onNodeWithTag("player_idle_app").assertHasNoClickAction()
        rule.onNodeWithTag("player_idle_open").assertHeightIsAtLeast(72.dp).assertWidthIsAtLeast(72.dp).performClick()
        assertEquals(listOf("idle"), launches)
        val icon = rule.onNodeWithTag("player_idle_icon", true).fetchSemanticsNode().boundsInRoot
        val player = rule.onNodeWithTag("home_player").fetchSemanticsNode().boundsInRoot
        assertEquals(40f * rule.density.density, icon.top - player.top, rule.density.density)
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun idle_longNameAtLargeFont_ellipsizesInsideCard() {
        host(null, idleApp = idle.copy(label = "音".repeat(40)), scale = 1.3f)
        val player = rule.onNodeWithTag("home_player").fetchSemanticsNode().boundsInRoot
        val inset = 40f * rule.density.density
        for (tag in listOf("player_idle_name", "player_idle_open_text")) {
            val node = rule.onNodeWithTag(tag, true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= player.left + inset && bounds.right <= player.right - inset)
        }
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
    @Test fun spacing_isBalanced() {
        host(height = 800)
        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val art = bounds("player_art")
        val titleRow = bounds("player_title_row")
        val seek = bounds("player_seek")
        val density = rule.density.density
        assertEquals(24f * density, titleRow.top - art.bottom, 1f * density)
        assertTrue(seek.top - titleRow.bottom >= 16f * density)
        val player = bounds("home_player")
        listOf("player_art", "player_title", "player_artist", "player_seek", "player_times",
            "player_prev", "player_play", "player_next", "player_fav").forEach {
            val child = bounds(it)
            assertTrue("$it top", child.top >= player.top)
            assertTrue("$it bottom", child.bottom <= player.bottom)
        }
    }
}
