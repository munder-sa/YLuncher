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
import androidx.compose.ui.test.assertIsDisplayed
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
import com.gt8d.launcher.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
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
        rule.onNodeWithTag("idle_app").performClick()
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
