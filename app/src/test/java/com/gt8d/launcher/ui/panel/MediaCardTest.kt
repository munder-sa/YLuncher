package com.gt8d.launcher.ui.panel

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyrics.LyricLine
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaCardTest {
    private val lyricLines = listOf(LyricLine(1000L, "一"), LyricLine(2000L, "二"))
    private fun lyricNow(position: Long = 2500L) = NowPlaying(
        "music", "架空の曲", "架空の歌手", null, 120000L, false, false,
        positionMs = position, metaTitle = "架空の曲",
    )

    @Test fun syncedShowsCurrentLine() {
        rule.setContent {
            LauncherTheme {
                MediaCard(lyricNow(), null, 0L, {}, {}, {},
                    lyrics = LyricsState.Synced(lyricLines), lyricsEnabled = true)
            }
        }
        rule.onNodeWithTag("media_lyric_line").assertTextEquals("二")
    }

    @Test fun beforeFirstLineShowsNote() {
        var position by mutableStateOf(500L)
        rule.setContent {
            LauncherTheme {
                MediaCard(lyricNow(position), null, 0L, {}, {}, {},
                    lyrics = LyricsState.Synced(lyricLines + LyricLine(3000L, "♪")),
                    lyricsEnabled = true)
            }
        }
        rule.onNodeWithTag("media_lyric_line").assertTextEquals("♪")
        rule.runOnIdle { position = 3500L }
        rule.onNodeWithTag("media_lyric_line").assertTextEquals("♪")
    }

    @Test fun longLineEllipsized() {
        rule.setContent {
            LauncherTheme {
                Box(Modifier.width(320.dp)) {
                    MediaCard(lyricNow(), null, 0L, {}, {}, {},
                        lyrics = LyricsState.Synced(listOf(LyricLine(1000L, "架空の長い本文".repeat(30)))),
                        lyricsEnabled = true)
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("media_lyric_line")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        assertTrue(results.single().hasVisualOverflow)
    }

    @Test fun lineReservedWhileEnabled() {
        var lyrics by mutableStateOf<LyricsState>(LyricsState.Synced(lyricLines))
        rule.setContent {
            LauncherTheme {
                MediaCard(lyricNow(), null, 0L, {}, {}, {}, lyrics = lyrics, lyricsEnabled = true)
            }
        }
        fun height(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithTag("media_lyric_line").assertTextEquals("二")
        val cardHeight = height("media_card")
        val lineHeight = height("media_lyric_line")
        for (state in listOf(LyricsState.Loading, LyricsState.Plain("架空の本文"),
            LyricsState.Missing, LyricsState.Offline, LyricsState.Failed, LyricsState.Off)) {
            rule.runOnIdle { lyrics = state }
            rule.onNodeWithTag("media_lyric_line").assertExists().assertTextEquals("")
            assertEquals(cardHeight, height("media_card"), 0f)
            assertEquals(lineHeight, height("media_lyric_line"), 0f)
        }
    }

    @Test fun noLineWhenOffOrNoQuery() {
        var mode by mutableStateOf(0)
        rule.setContent {
            LauncherTheme {
                if (mode == 0) {
                    MediaCard(lyricNow(), null, 0L, {}, {}, {})
                } else {
                    val now = when (mode) {
                        2 -> lyricNow().copy(artist = null)
                        3 -> lyricNow().copy(metaTitle = null)
                        else -> lyricNow()
                    }
                    MediaCard(now, null, 0L, {}, {}, {},
                        lyrics = LyricsState.Synced(lyricLines), lyricsEnabled = mode != 1)
                }
            }
        }
        fun height() = rule.onNodeWithTag("media_card").fetchSemanticsNode().boundsInRoot.height
        val defaultHeight = height()
        rule.onNodeWithTag("media_lyric_line").assertDoesNotExist()
        for (next in 1..3) {
            rule.runOnIdle { mode = next }
            rule.onNodeWithTag("media_lyric_line").assertDoesNotExist()
            assertEquals(defaultHeight, height(), 0f)
        }
    }

    @Test fun idle_innerWidthBoundary_andMissingApp() {
        var innerWidth by mutableStateOf(299)
        var installed by mutableStateOf(true)
        val launches = mutableListOf<String>()
        rule.setContent {
            LauncherTheme {
                Box(Modifier.width((innerWidth + 16).dp)) {
                    MediaCard(null, AppInfo("idle", "音楽アプリ", null, installed), 0L,
                        {}, {}, { launches.add(it) })
                }
            }
        }
        rule.onNodeWithTag("idle_name").assertDoesNotExist()
        rule.onNodeWithTag("idle_status").assertDoesNotExist()
        rule.onNodeWithTag("idle_open").assertIsDisplayed().assertHeightIsAtLeast(72.dp).performClick()
        assertEquals(listOf("idle"), launches)
        rule.onNodeWithTag("idle_app").assertHasNoClickAction()
        rule.runOnIdle { innerWidth = 300 }
        rule.onNodeWithTag("idle_name").assertIsDisplayed()
        rule.onNodeWithText("再生情報はありません").assertIsDisplayed()
        rule.onNodeWithTag("idle_open").assertIsDisplayed()
        rule.runOnIdle { installed = false }
        rule.onNodeWithTag("idle_open").assertDoesNotExist()
        rule.onNodeWithText("アプリが見つかりません").assertIsDisplayed()
        rule.runOnIdle { innerWidth = 299 }
        rule.onNodeWithText("アプリが見つかりません").assertIsDisplayed()
        rule.onNodeWithTag("idle_open").assertDoesNotExist()
        rule.onNodeWithTag("idle_app").assertHasNoClickAction()
    }
    @Test fun favorite_isRightOfMetadata_andControlsStayInOneRow() {
        var favorite by mutableStateOf(true)
        var playing by mutableStateOf(true)
        val actions = mutableListOf<MediaAction>()
        rule.setContent {
            LauncherTheme {
                MediaCard(NowPlaying("music", "Song", "Artist", null, 120000L, playing, favorite),
                    null, 0L, { actions.add(it) }, {}, {})
            }
        }
        fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val metadata = bounds("media_metadata")
        val star = bounds("btn_favorite")
        assertTrue(star.left >= metadata.right)
        assertTrue(star.center.y >= bounds("title").top && star.center.y <= bounds("artist").bottom)
        val prev = bounds("btn_prev")
        assertEquals(prev.center.y, bounds("btn_playpause").center.y, 1f)
        assertEquals(prev.center.y, bounds("btn_next").center.y, 1f)
        rule.onNodeWithTag("btn_favorite").performClick()
        assertEquals(listOf(MediaAction.Favorite), actions)
        fun assertWhite() = rule.onNodeWithTag("btn_playpause")
            .assert(SemanticsMatcher.expectValue(MediaControlBackground, Color.White))
        assertWhite()
        rule.runOnIdle { playing = false }
        assertWhite()
        rule.runOnIdle { favorite = false }
        rule.onNodeWithTag("btn_favorite").assertDoesNotExist()
        val expanded = bounds("media_metadata")
        assertEquals(star.right, expanded.right, 1f)
        assertEquals(metadata.left, expanded.left, 1f)
        assertTrue(expanded.width > metadata.width)
    }

    @Test fun artTap_launchesPlayerApp() {
        val launches = mutableListOf<String>()
        val now = NowPlaying("music", "Song", "Artist", null, 120000L, true, false)
        rule.setContent {
            LauncherTheme {
                MediaCard(now = now, idleApp = null, positionMs = 0L,
                    onAction = {}, onSeek = {}, onLaunch = { launches.add(it) })
            }
        }
        rule.onNodeWithTag("artwork").performClick()
        assertEquals(listOf(now.pkg), launches)
    }

    @Test fun longTitle_isOneLine() {
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying("music", "長い曲名".repeat(30), "Artist", null, 120000L, true, false),
                    idleApp = null, positionMs = 0L,
                    onAction = {}, onSeek = {}, onLaunch = {},
                )
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
    }

    @Test
    fun mediaCard_pointerSeek_commitsEndOnceOnRelease() = pointerSeek(0L, true, 120000L)

    @Test fun seek_tapAndBelowSlopRestoreDisplay_dragCommitsOnce_andNextTapDoesNotCommit() {
        val seeks = mutableListOf<Long>()
        var slop = 0f
        rule.setContent {
            slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
            LauncherTheme { androidx.compose.foundation.layout.Column {
                SeekBar(120000L, 30000L, { seeks.add(it) })
            } }
        }
        val progress = rule.onNodeWithTag("progress")
        fun assertRestored() {
            rule.onNodeWithText("0:30").assertExists()
            rule.onNodeWithText("-1:30").assertExists()
        }
        progress.performTouchInput { click(center) }
        assertEquals(emptyList<Long>(), seeks)
        assertRestored()
        progress.performTouchInput { down(center); moveTo(center + Offset(slop * 0.5f, 0f)); up() }
        assertEquals(emptyList<Long>(), seeks)
        assertRestored()
        progress.performTouchInput { down(center); moveTo(center + Offset(slop + 1f, 0f)) }
        assertEquals(emptyList<Long>(), seeks)
        rule.onNodeWithText("0:30").assertDoesNotExist()
        progress.performTouchInput { up() }
        assertEquals(1, seeks.size)
        assertTrue(seeks.single() > 30000L)
        assertRestored()
        progress.performTouchInput { click(center) }
        assertEquals(1, seeks.size)
        assertRestored()
    }

    @Test
    fun mediaCard_pointerSeek_commitsStartOnceOnRelease() = pointerSeek(60000L, false, 0L)

    private fun pointerSeek(position: Long, end: Boolean, expected: Long) {
        val seeks = mutableListOf<Long>()
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying("music", "Song", "Artist", null, 120000L, true, false),
                    idleApp = null, positionMs = position,
                    onAction = {}, onSeek = { seeks.add(it) }, onLaunch = {},
                )
            }
        }
        val progress = rule.onNodeWithTag("progress")
        progress.performTouchInput {
            down(center)
            moveTo(Offset(if (end) width - 1f else 1f, centerY))
        }
        rule.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
        progress.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf(expected), seeks) }
    }

    @Test
    fun mediaCard_unknownDuration_hasNoPointerSeekTarget() {
        var duration by mutableStateOf<Long?>(null)
        val seeks = mutableListOf<Long>()
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying("music", "Song", "Artist", null, duration, true, false),
                    idleApp = null, positionMs = 0L,
                    onAction = {}, onSeek = { seeks.add(it) }, onLaunch = {},
                )
            }
        }
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.runOnIdle { duration = 0L }
        rule.onNodeWithTag("progress").assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
    }

    @get:Rule(order = 0)
    val registerActivityRule = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val shadowPm = Shadows.shadowOf(app.packageManager)
            val cn = ComponentName(app.packageName, ComponentActivity::class.java.name)
            shadowPm.addOrUpdateActivity(
                ActivityInfo().apply {
                    name = cn.className
                    packageName = cn.packageName
                },
            )
            shadowPm.addIntentFilterForActivity(
                cn,
                IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                },
            )
        }
    }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun mediaCard_idle_showsClockAndIdleApp() {
        var launchedPkg: String? = null
        val idleApp = AppInfo(
            pkg = "com.apple.android.music",
            label = "Apple Music",
            icon = null,
            installed = true,
        )
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = null,
                    idleApp = idleApp,
                    positionMs = 0L,
                    onAction = {},
                    onSeek = {},
                    onLaunch = { launchedPkg = it },
                )
            }
        }

        rule.onNodeWithText("Apple Music").assertIsDisplayed()
        rule.onNodeWithText("再生情報はありません").assertIsDisplayed()
        rule.onNodeWithTag("idle_app").assertHasNoClickAction()
        rule.onNodeWithTag("idle_open").assertHeightIsAtLeast(72.dp).assertWidthIsAtLeast(72.dp)
            .assert(hasContentDescription("Apple Musicを開く")).performClick()
        assertEquals("com.apple.android.music", launchedPkg)
    }

    @Test
    fun mediaCard_playing_showsTitleArtistControls() {
        var hasFavorite by mutableStateOf(false)
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying(
                        pkg = "com.apple.android.music",
                        title = "Test Song",
                        artist = "Test Artist",
                        artwork = null,
                        durationMs = 180_000L,
                        playing = true,
                        hasFavorite = hasFavorite,
                    ),
                    idleApp = null,
                    positionMs = 30_000L,
                    onAction = {},
                    onSeek = {},
                    onLaunch = {},
                )
            }
        }

        rule.onNodeWithTag("title").assertIsDisplayed()
        rule.onNodeWithText("Test Song").assertIsDisplayed()
        rule.onNodeWithTag("artist").assertIsDisplayed()
        rule.onNodeWithText("Test Artist").assertIsDisplayed()
        rule.onNodeWithTag("btn_prev").assertIsDisplayed()
        rule.onNodeWithTag("btn_playpause").assertIsDisplayed()
        rule.onNodeWithTag("btn_next").assertIsDisplayed()
        rule.onNodeWithTag("btn_favorite").assertDoesNotExist()

        hasFavorite = true
        rule.waitForIdle()
        rule.onNodeWithTag("btn_favorite").assertIsDisplayed()
    }

    @Test
    fun mediaCard_unknownDuration_noProgressBar() {
        var durationMs by mutableStateOf<Long?>(null)
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying(
                        pkg = "com.apple.android.music",
                        title = "Radio Stream",
                        artist = "Station",
                        artwork = null,
                        durationMs = durationMs,
                        playing = true,
                        hasFavorite = false,
                    ),
                    idleApp = null,
                    positionMs = 10_000L,
                    onAction = {},
                    onSeek = {},
                    onLaunch = {},
                )
            }
        }

        rule.onNodeWithTag("progress").assertDoesNotExist()

        durationMs = 120_000L
        rule.waitForIdle()
        rule.onNodeWithTag("progress").assertIsDisplayed()
    }

    @Test
    fun mediaCard_playPause_sendsActions() {
        var playing by mutableStateOf(true)
        val actions = mutableListOf<MediaAction>()
        rule.setContent {
            LauncherTheme {
                MediaCard(
                    now = NowPlaying(
                        pkg = "com.apple.android.music",
                        title = "Song",
                        artist = "Artist",
                        artwork = null,
                        durationMs = 120_000L,
                        playing = playing,
                        hasFavorite = false,
                    ),
                    idleApp = null,
                    positionMs = 0L,
                    onAction = { actions.add(it) },
                    onSeek = {},
                    onLaunch = {},
                )
            }
        }

        rule.onNodeWithTag("btn_playpause").performClick()
        assertEquals(listOf(MediaAction.Pause), actions)

        playing = false
        rule.waitForIdle()
        rule.onNodeWithTag("btn_playpause").performClick()
        assertEquals(listOf(MediaAction.Pause, MediaAction.Play), actions)
    }
}
