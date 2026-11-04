package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.gt8d.launcher.diag.DiagLog
import com.gt8d.launcher.media.lyrics.AppleConnectResult
import com.gt8d.launcher.media.lyrics.AppleLyricsReason
import com.gt8d.launcher.media.lyrics.AppleLyricsResult
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AppleMusicStatus
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1000dp")
class AppleMusicLyricsScreenTest {
    @get:Rule(order = 0) val register = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(app, ComponentActivity::class.java)
            shadowOf(app.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                name = component.className; packageName = component.packageName
            })
            shadowOf(app.packageManager).addIntentFilterForActivity(component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }
    @get:Rule(order = 1) val rule = createComposeRule()
    private val fixture = Fixture()
    private lateinit var activity: ComponentActivity
    private var visible by mutableStateOf(true)
    private val actions = SettingsActions({}, {}, { false }, {}, { false })

    private fun host(
        initialRoute: SettingsRoute? = SettingsRoute.AppleMusicLyrics,
        session: AppleMusicSession? = fixture.session,
    ) {
        rule.setContent {
            LauncherTheme {
                activity = LocalContext.current as ComponentActivity
                if (visible) SettingsHost(actions, { visible = false }, {},
                    initialRoute = initialRoute, appleMusic = session)
            }
        }
    }

    private fun open() {
        rule.onNodeWithTag("settings_item_apple_music_lyrics").performScrollTo().performClick()
    }

    private fun input(value: String) {
        rule.onNodeWithTag("apple_music_token").performScrollTo().performTextReplacement(value)
    }

    private fun click(tag: String) {
        val node = rule.onNodeWithTag(tag)
        if (tag != "settings_back") node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun submit(value: String = TOKEN) {
        input(value)
        click("apple_music_connect")
    }

    private fun emptyInput() {
        rule.onNodeWithTag("apple_music_token").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    private fun status(expected: String, state: AppleMusicStatus) {
        rule.onNodeWithTag("apple_music_status").assertTextEquals(expected)
        assertEquals(state, fixture.session.state.value.status)
    }

    private fun noLyrics() { assertEquals(0, fixture.fetchCalls) }

    @Test fun rootOpensAppleMusicSettingsAndReturns() {
        host(initialRoute = null)
        val sections = listOf("画面", "音楽", "システム").map {
            rule.onNodeWithTag("settings_section_$it").fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(sections[0] < sections[1])
        assertTrue(sections[1] < sections[2])
        rule.onNodeWithTag("settings_item_apple_music_lyrics").assert(hasText("Apple Music の歌詞"))
        open()
        rule.onNodeWithText("Apple Music の歌詞").assertIsDisplayed()
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        rule.onNodeWithTag("settings_item_sets").assertDoesNotExist()
        click("settings_back")
        rule.onNodeWithText("設定").assertIsDisplayed()
        rule.onNodeWithTag("settings_item_apple_music_lyrics").assertExists()
        assertEquals(0, fixture.connectCalls.size)
        noLyrics()
    }

    @Test fun masksInputAndClearsItBeforeConnecting() {
        val ready = CompletableDeferred<AppleConnectResult>()
        fixture.answer = { ready.await() }
        host()
        rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
        rule.onNodeWithTag("apple_music_disconnect").assertIsNotEnabled()
        rule.onNodeWithTag("apple_music_token")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        input(" \u3000 ")
        rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
        assertEquals(0, fixture.connectCalls.size)
        input(TOKEN)
        rule.onNodeWithTag("apple_music_connect").assertIsEnabled()
        click("apple_music_connect")
        emptyInput()
        status("接続を確認中", AppleMusicStatus.CHECKING)
        rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
        rule.onNodeWithTag("apple_music_token").assertIsNotEnabled()
        assertEquals(listOf(TOKEN), fixture.connectCalls)
        noLyrics()
        rule.runOnIdle { ready.complete(AppleConnectResult.Ready("jp")) }
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        emptyInput()
        rule.onNodeWithTag("apple_music_token").assertIsEnabled()
        assertEquals(1, fixture.connectCalls.size)
        noLyrics()
    }

    @Test fun disconnectClearsConnectionAndInput() {
        host()
        input(" ")
        rule.onNodeWithTag("apple_music_disconnect").assertIsEnabled()
        click("apple_music_disconnect")
        emptyInput()
        rule.onNodeWithTag("apple_music_disconnect").assertIsNotEnabled()
        assertEquals(0, fixture.connectCalls.size)

        submit()
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        input(SECOND_TOKEN)
        click("apple_music_disconnect")
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        assertNull(fixture.session.state.value.storefront)
        emptyInput()
        rule.onNodeWithTag("apple_music_disconnect").assertIsNotEnabled()

        val pending = CompletableDeferred<AppleConnectResult>()
        lateinit var operation: Job
        fixture.answer = {
            operation = currentCoroutineContext().job
            pending.await()
        }
        submit(SECOND_TOKEN)
        status("接続を確認中", AppleMusicStatus.CHECKING)
        click("apple_music_disconnect")
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        assertTrue(operation.isCancelled)
        emptyInput()
        rule.runOnIdle { pending.complete(AppleConnectResult.Ready("us")) }
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        fixture.answer = { AppleConnectResult.Ready("gb") }
        submit()
        status("接続済み（地域: gb）", AppleMusicStatus.CONNECTED)
        emptyInput()
        assertEquals(listOf(TOKEN, SECOND_TOKEN, TOKEN), fixture.connectCalls)
        noLyrics()
    }

    @Test fun leavingCancelsPendingCheckAndDropsInput() {
        host()
        input(TOKEN)
        click("settings_back")
        open()
        emptyInput()
        assertEquals(0, fixture.connectCalls.size)
        input(SECOND_TOKEN)
        rule.runOnIdle { visible = false }
        rule.onNodeWithTag("apple_music_token").assertDoesNotExist()
        rule.runOnIdle { visible = true }
        emptyInput()
        assertEquals(0, fixture.connectCalls.size)

        val cooperative = CompletableDeferred<AppleConnectResult>()
        lateinit var operation: Job
        fixture.answer = {
            operation = currentCoroutineContext().job
            cooperative.await()
        }
        submit()
        status("接続を確認中", AppleMusicStatus.CHECKING)
        rule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("settings_item_sets").assertExists()
        assertTrue(operation.isCancelled)
        assertEquals(AppleMusicStatus.UNCONFIGURED, fixture.session.state.value.status)
        open()
        emptyInput()
        rule.runOnIdle { cooperative.complete(AppleConnectResult.Ready("us")) }
        status("未接続", AppleMusicStatus.UNCONFIGURED)

        // A dependency that finishes after cancellation must not restore credentials.
        for (dispose in listOf(false, true)) {
            val late = CompletableDeferred<AppleConnectResult>()
            fixture.answer = {
                operation = currentCoroutineContext().job
                withContext(NonCancellable) { late.await() }
            }
            try {
                submit()
                status("接続を確認中", AppleMusicStatus.CHECKING)
                if (dispose) rule.runOnIdle { visible = false } else click("settings_back")
                rule.onNodeWithTag("apple_music_token").assertDoesNotExist()
                assertTrue(operation.isCancelled)
                rule.runOnIdle { late.complete(AppleConnectResult.Ready("us")) }
                rule.waitForIdle()
                assertEquals(AppleMusicStatus.UNCONFIGURED, fixture.session.state.value.status)
                assertNull(fixture.session.state.value.storefront)
                if (dispose) rule.runOnIdle { visible = true } else open()
                emptyInput()
                status("未接続", AppleMusicStatus.UNCONFIGURED)
            } finally {
                late.complete(AppleConnectResult.Ready("us"))
            }
        }
        assertEquals(3, fixture.connectCalls.size)
        noLyrics()
    }

    @Test fun completedConnectionSurvivesNavigation() {
        host()
        submit()
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        val connected = fixture.session.state.value
        input(SECOND_TOKEN)
        click("settings_back")
        rule.onNodeWithTag("settings_item_apple_music_lyrics").assertExists()
        assertEquals(connected, fixture.session.state.value)
        open()
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        emptyInput()
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        assertEquals(connected, fixture.session.state.value)
        rule.runOnIdle { visible = true }
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        emptyInput()
        assertEquals(listOf(TOKEN), fixture.connectCalls)
        noLyrics()
    }

    @Test fun recreationDoesNotRestoreInput() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            LauncherTheme {
                SettingsHost(actions, {}, {}, initialRoute = SettingsRoute.AppleMusicLyrics,
                    appleMusic = fixture.session)
            }
        }
        input(TOKEN)
        assertTrue(rule.onNodeWithTag("apple_music_token").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text.isNotEmpty())
        restoration.emulateSavedInstanceStateRestore()
        emptyInput()
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
        assertEquals(0, fixture.connectCalls.size)
        noLyrics()
    }

    @Test fun showsFailuresWithoutSecretsAndAllowsRetry() {
        DiagLog.clearForTest()
        ShadowLog.clear()
        host()
        val failures = mapOf(
            AppleLyricsReason.AUTH_REQUIRED to "接続を確認できませんでした。トークンを確認して再入力してください。",
            AppleLyricsReason.NETWORK to "通信できませんでした。接続を確認してやり直してください。",
            AppleLyricsReason.TIMEOUT to "確認に時間がかかりました。もう一度お試しください。",
            AppleLyricsReason.WAIT to "しばらく待ってから接続を確認してください。",
            AppleLyricsReason.INVALID to "入力内容または応答を確認できませんでした。",
        )
        var checks = 0
        for (reason in AppleLyricsReason.entries) {
            fixture.answer = { AppleConnectResult.Unavailable(reason, retryAfterSec = 30) }
            input(TOKEN)
            assertNoSecrets()
            click("apple_music_connect")
            ++checks
            status("接続の再確認が必要", AppleMusicStatus.NEEDS_ATTENTION)
            rule.onNodeWithText(failures[reason]
                ?: "接続を確認できませんでした。時間をおいてやり直してください。")
                .performScrollTo().assertIsDisplayed()
            emptyInput()
            rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
            assertNoSecrets()
            fixture.answer = { AppleConnectResult.Ready("jp") }
            submit(SECOND_TOKEN)
            ++checks
            status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
            assertNoSecrets()
        }
        for ((error, reason) in listOf(
            IOException("$EXCEPTION_TEXT $TOKEN") to AppleLyricsReason.NETWORK,
            IllegalStateException("$EXCEPTION_TEXT $SECOND_TOKEN") to AppleLyricsReason.INVALID,
        )) {
            fixture.answer = { throw error }
            submit()
            ++checks
            status("接続の再確認が必要", AppleMusicStatus.NEEDS_ATTENTION)
            rule.onNodeWithText(failures.getValue(reason)).performScrollTo().assertIsDisplayed()
            emptyInput()
            assertNoSecrets()
            fixture.answer = { AppleConnectResult.Ready("jp") }
            submit(SECOND_TOKEN)
            ++checks
            status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        }
        assertEquals(checks, fixture.connectCalls.size)
        noLyrics()
        assertNoSecrets()
    }

    @Test @Config(qualifiers = "w516dp-h1000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun narrowPaneKeepsControlsAccessible() {
        host(initialRoute = null)
        open()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (description in listOf(
            "Apple Music Web のトークンを入力して接続を確認します。",
            "アプリの終了・再起動後は再入力が必要です。",
            "取得できない曲は、ほかの歌詞サービスで表示します。",
        )) {
            val node = rule.onNodeWithText(description).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$description size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}",
                layout.hasVisualOverflow)
        }
        for (tag in listOf("apple_music_token", "apple_music_connect", "apple_music_disconnect")) {
            val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.positionInRoot.x >= root.left)
            assertTrue(node.positionInRoot.x + node.size.width <= root.right)
        }
        val connect = rule.onNodeWithTag("apple_music_connect").fetchSemanticsNode()
        val disconnect = rule.onNodeWithTag("apple_music_disconnect").fetchSemanticsNode()
        assertTrue(connect.positionInRoot.y + connect.size.height <= disconnect.positionInRoot.y)
        submit()
        status("接続済み（地域: jp）", AppleMusicStatus.CONNECTED)
        click("apple_music_disconnect")
        status("未接続", AppleMusicStatus.UNCONFIGURED)
        emptyInput()
        assertEquals(listOf(TOKEN), fixture.connectCalls)
        noLyrics()
    }

    @Test fun missingSessionShowsUnavailableWithoutRequests() {
        host(initialRoute = null, session = null)
        open()
        rule.onNodeWithText("Apple Music の歌詞").assertIsDisplayed()
        rule.onNodeWithText("接続設定を利用できません").assertIsDisplayed()
        rule.onNodeWithTag("apple_music_token").assertDoesNotExist()
        rule.onNodeWithTag("apple_music_connect").assertDoesNotExist()
        click("settings_back")
        rule.onNodeWithTag("settings_item_apple_music_lyrics").assertExists()
        assertEquals(0, fixture.connectCalls.size)
        noLyrics()
    }

    private fun assertNoSecrets() {
        val displayed = rule.onAllNodes(SemanticsMatcher("all nodes") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node ->
                val config = node.config
                // EditableText is the standard password editor's value, not a diagnostic message.
                val text = if (config.contains(SemanticsProperties.Text))
                    config[SemanticsProperties.Text].map { it.text } else emptyList()
                val descriptions = if (config.contains(SemanticsProperties.ContentDescription))
                    config[SemanticsProperties.ContentDescription] else emptyList()
                text + descriptions
            }
        val outputs = displayed + DiagLog.dump() + ShadowLog.getLogs().map {
            it.msg + (it.throwable?.stackTraceToString() ?: "")
        }
        for (output in outputs) {
            for (secret in listOf(TOKEN, SECOND_TOKEN, EXCEPTION_TEXT)) {
                assertFalse("Secret leaked into UI or diagnostics", output.contains(secret))
            }
        }
    }

    private class Fixture {
        val connectCalls = mutableListOf<String>()
        var fetchCalls = 0
        var answer: suspend (String) -> AppleConnectResult = { AppleConnectResult.Ready("jp") }
        val session = AppleMusicSession(
            connectRequest = { token -> connectCalls += token; answer(token) },
            fetchRequest = { _, _, _ ->
                ++fetchCalls
                AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.NOT_FOUND)
            },
        )
    }

    private companion object {
        const val TOKEN = "fake-web-token-177b-secret"
        const val SECOND_TOKEN = "fake-retry-token-177b-secret"
        const val EXCEPTION_TEXT = "private-dependency-error-177b"
    }
}
