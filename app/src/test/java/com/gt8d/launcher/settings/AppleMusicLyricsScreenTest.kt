package com.gt8d.launcher.settings

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Looper
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
import com.gt8d.launcher.media.lyrics.AppleCredentialStore
import com.gt8d.launcher.media.lyrics.AppleCredentialReadResult
import com.gt8d.launcher.media.lyrics.AppleCredentialSource
import com.gt8d.launcher.media.lyrics.StoredAppleCredential
import com.gt8d.launcher.media.lyrics.AppleMusicRetentionStatus
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.theme.LauncherTheme
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.After
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
    private val persistentFixtures = mutableListOf<PersistentFixture>()

    @After fun releasePersistence() {
        persistentFixtures.forEach { it.store.releaseAll(); it.scope.cancel() }
    }

    private fun host(
        initialRoute: SettingsRoute? = SettingsRoute.AppleMusicLyrics,
        session: AppleMusicSession? = fixture.session,
        onLogin: (() -> Unit)? = null,
        webData: AppleMusicWebData? = null,
    ) {
        rule.setContent {
            LauncherTheme {
                activity = LocalContext.current as ComponentActivity
                if (visible) SettingsHost(actions, { visible = false }, {},
                    initialRoute = initialRoute, appleMusic = session,
                    onAppleMusicWebLogin = onLogin, appleMusicWebData = webData)
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
            AppleLyricsReason.AUTH_REQUIRED to "接続を確認できませんでした。Apple Musicにもう一度ログインしてください。",
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
            "Apple Musicにログインして歌詞を接続します。",
            "接続情報をこの端末に保存し、再起動後に復元します。",
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

    @Test fun loginCallbackIsOptionalAndDiscardsManualInput() {
        var calls = 0
        var callback by mutableStateOf<(() -> Unit)?>(null)
        rule.setContent {
            LauncherTheme {
                SettingsHost(actions, {}, {}, initialRoute = SettingsRoute.AppleMusicLyrics,
                    appleMusic = fixture.session, onAppleMusicWebLogin = callback)
            }
        }
        rule.onNodeWithTag("apple_music_web_login").assertIsNotEnabled()
        input(TOKEN)
        rule.runOnIdle { callback = { calls++ } }
        click("apple_music_web_login")
        assertEquals(1, calls)
        emptyInput()
        assertEquals(0, fixture.connectCalls.size)
        assertNoSecrets()
    }

    @Test fun savedRestoreRequiresValidationAndRetryKeepsSessionCooldown() {
        val f = persistent()
        f.store.value = saved()
        val validation = CompletableDeferred<AppleConnectResult>()
        f.answer = { validation.await() }
        host(session = f.session, webData = f.webData, onLogin = {})
        awaitPersistence { f.calls == 1 }
        rule.onNodeWithTag("apple_music_retention").assertTextEquals("この端末に保存済み")
        rule.onNodeWithTag("apple_music_status").assertTextEquals("接続を確認中")
        rule.runOnIdle { validation.complete(AppleConnectResult.Unavailable(AppleLyricsReason.NETWORK, 120L)) }
        rule.onNodeWithTag("apple_music_status").assertTextEquals("接続の再確認が必要")
        rule.onNodeWithText("通信できませんでした。接続を確認してやり直してください。").assertExists()
        click("apple_music_restore")
        assertEquals(1, f.calls)
        rule.runOnIdle { f.now += 60_000 }
        click("apple_music_restore")
        assertEquals(1, f.calls)
        rule.runOnIdle { f.now += 60_000; f.answer = { AppleConnectResult.Ready("jp") } }
        click("apple_music_restore")
        awaitPersistence { f.session.state.value.status == AppleMusicStatus.CONNECTED }
        rule.onNodeWithTag("apple_music_status").assertTextEquals("接続済み（地域: jp）")
        assertEquals(2, f.calls)
        assertEquals(1, f.store.reads)
        assertNotNull(f.store.value)
        assertNoSecrets()
    }

    @Test fun persistentManualConnectShowsSavingThenSavedAndWriteFailureIsSessionOnly() {
        val f = persistent()
        host(session = f.session, webData = f.webData, onLogin = {})
        awaitPersistence { f.store.reads == 1 && f.session.retention.value.status == AppleMusicRetentionStatus.NONE }
        f.store.writeGate = CountDownLatch(1)
        try {
            submit()
            awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.SAVING }
            rule.onNodeWithTag("apple_music_status").assertTextEquals("接続済み（地域: jp）")
            rule.onNodeWithTag("apple_music_retention").assertTextEquals("接続情報を保存中")
            rule.onNodeWithTag("apple_music_web_login").assertIsNotEnabled()
            emptyInput()
        } finally { f.store.writeGate?.countDown() }
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.SAVED }
        rule.onNodeWithTag("apple_music_retention").assertTextEquals("この端末に保存済み")
        assertEquals(AppleCredentialSource.MANUAL, f.store.value?.source)
        f.store.failWrite = true
        submit(SECOND_TOKEN)
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.SESSION_ONLY }
        rule.onNodeWithTag("apple_music_status").assertTextEquals("接続済み（地域: jp）")
        rule.onNodeWithTag("apple_music_retention").assertTextEquals("この起動中のみ接続できます")
        assertNull(f.store.value)
        emptyInput()
        assertNoSecrets()
    }

    @Test fun readAndClearFailuresHaveSeparateFixedMessages() {
        val f = persistent()
        f.store.failRead = true
        f.store.readGate = CountDownLatch(1)
        try {
            host(session = f.session, webData = f.webData)
            rule.onNodeWithTag("apple_music_retention").assertTextEquals("保存した接続情報を読み込み中")
        } finally { f.store.readGate?.countDown() }
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.ERROR }
        rule.onNodeWithTag("apple_music_retention").assertTextEquals(
            "保存した接続情報を読み込めませんでした。もう一度ログインしてください。")
        f.store.failClear = true
        click("apple_music_disconnect")
        rule.onNodeWithTag("apple_music_clear_web").performClick()
        rule.onNodeWithTag("apple_music_disconnect_confirm").performClick()
        awaitPersistence { f.session.retention.value.problem == com.gt8d.launcher.media.lyrics.AppleMusicRetentionProblem.CLEAR }
        rule.onNodeWithTag("apple_music_retention").assertTextEquals("接続情報の削除に失敗しました。もう一度解除してください。")
        rule.onNodeWithTag("apple_music_status").assertTextEquals("接続情報の削除を確認できません")
        assertEquals(0, f.backend.calls)
        assertNoSecrets()
    }

    @Test fun disconnectConfirmationDefaultsOnCancelDoesNothingAndOptOutOnlyClearsCredentials() {
        val f = persistent()
        f.store.value = saved()
        host(session = f.session, webData = f.webData, onLogin = {})
        awaitPersistence { f.session.state.value.status == AppleMusicStatus.CONNECTED }
        input(SECOND_TOKEN)
        val maskedInputBeforeDialog = rule.onNodeWithTag("apple_music_token").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertEquals(SECOND_TOKEN.length, maskedInputBeforeDialog.length)
        assertNotEquals(AnnotatedString(SECOND_TOKEN), maskedInputBeforeDialog)
        click("apple_music_disconnect")
        rule.onNodeWithTag("apple_music_clear_web").assertIsOn()
        rule.onNodeWithTag("apple_music_disconnect_cancel").performClick()
        assertEquals(0, f.store.clears)
        assertEquals(0, f.backend.calls)
        assertEquals(AppleMusicStatus.CONNECTED, f.session.state.value.status)
        rule.onNodeWithTag("apple_music_token").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, maskedInputBeforeDialog))
        click("apple_music_disconnect")
        rule.onNodeWithTag("apple_music_clear_web").assertIsOn().performClick().assertIsOff()
        rule.onNodeWithTag("apple_music_disconnect_confirm").performClick()
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.NONE }
        assertEquals(1, f.store.clears)
        assertEquals(0, f.backend.checks)
        assertEquals(0, f.backend.calls)
        assertNull(f.store.value)
        emptyInput()
        click("apple_music_disconnect")
        rule.onNodeWithTag("apple_music_clear_web").assertIsOn()
        rule.onNodeWithTag("apple_music_disconnect_cancel").performClick()
        assertNoSecrets()
    }

    @Test fun confirmedDeletionContinuesAfterLeavingAndKeepsBothPendingStates() {
        val f = persistent()
        f.store.value = saved()
        host(session = f.session, webData = f.webData, onLogin = {})
        awaitPersistence { f.session.state.value.status == AppleMusicStatus.CONNECTED }
        f.store.clearGate = CountDownLatch(1)
        try {
            click("apple_music_disconnect")
            rule.onNodeWithTag("apple_music_clear_web").assertIsOn()
            rule.onNodeWithTag("apple_music_disconnect_confirm").performClick()
            rule.onNodeWithTag("apple_music_status").assertTextEquals("接続情報を削除中")
            rule.onNodeWithTag("apple_music_web_data_status").assertTextEquals("Appleログイン情報を削除中")
            rule.onNodeWithTag("apple_music_web_login").assertIsNotEnabled()
            rule.onNodeWithTag("apple_music_connect").assertIsNotEnabled()
            assertEquals(1, f.backend.calls)
            rule.runOnIdle { visible = false }
        } finally { f.store.clearGate?.countDown() }
        rule.runOnIdle { f.backend.complete() }
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.NONE }
        assertEquals(AppleMusicWebDataState.CLEARED, f.webData.state.value)
        assertNull(f.store.value)
        rule.runOnIdle { visible = true }
        emptyInput()
        rule.onNodeWithTag("apple_music_status").assertTextEquals("未接続")
        rule.onNodeWithTag("apple_music_web_data_status").assertTextEquals("Appleログイン情報を削除しました")
        assertNoSecrets()
    }

    @Test @Config(qualifiers = "w516dp-h1000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun newLoginAndDisconnectControlsFitNarrowPaneAndWebFailureIsSeparate() {
        val f = persistent()
        f.store.value = saved()
        f.answer = { AppleConnectResult.Unavailable(AppleLyricsReason.NETWORK) }
        f.backend.supported = false
        host(session = f.session, webData = f.webData, onLogin = {})
        awaitPersistence { f.session.state.value.status == AppleMusicStatus.NEEDS_ATTENTION }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (tag in listOf("apple_music_web_login", "apple_music_restore", "apple_music_disconnect")) {
            val node = rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.left >= root.left)
            assertTrue(node.boundsInRoot.right <= root.right)
        }
        click("apple_music_disconnect")
        rule.onNodeWithTag("apple_music_clear_web").assertIsOn().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("この端末のAppleログイン情報も削除", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Label layout: size=${layouts.single().size}, paragraph=${layouts.single().multiParagraph.width}x${layouts.single().multiParagraph.height}",
            layouts.single().hasVisualOverflow)
        rule.onNodeWithTag("apple_music_disconnect_confirm").assertIsDisplayed().performClick()
        awaitPersistence { f.session.retention.value.status == AppleMusicRetentionStatus.NONE }
        rule.onNodeWithTag("apple_music_web_data_status").assertTextEquals(
            "Appleログイン情報の削除に失敗しました。もう一度解除してください。")
        rule.onNodeWithTag("apple_music_status").assertTextEquals("未接続")
        assertNull(f.store.value)
        assertNoSecrets()
    }

    private fun persistent() = PersistentFixture().also { persistentFixtures += it }
    private fun awaitPersistence(condition: () -> Boolean) {
        rule.waitUntil(10_000) {
            // IO completion posts to Android's main looper; advancing Compose's clock alone
            // does not drain that queue in Robolectric's paused-looper mode.
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
    private fun saved() = StoredAppleCredential(TOKEN, "jp", AppleCredentialSource.WEB, 1_000L)

    private class PersistentFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = GatedStore()
        val backend = WebBackend()
        val webData = AppleMusicWebData(scope, backend)
        var now = 1_000L
        var calls = 0
        var answer: suspend () -> AppleConnectResult = { AppleConnectResult.Ready("jp") }
        val session = AppleMusicSession({ calls++; answer() },
            { _, _, _ -> error("Settings must not request lyrics") }, store, scope, clock = { now })
    }

    private class GatedStore : AppleCredentialStore {
        @Volatile var value: StoredAppleCredential? = null
        @Volatile var reads = 0
        @Volatile var clears = 0
        @Volatile var failRead = false
        @Volatile var failWrite = false
        @Volatile var failClear = false
        @Volatile var readGate: CountDownLatch? = null
        @Volatile var writeGate: CountDownLatch? = null
        @Volatile var clearGate: CountDownLatch? = null
        override fun read(): AppleCredentialReadResult {
            reads++
            readGate?.let { check(it.await(20, TimeUnit.SECONDS)) }
            if (failRead) throw IOException("$EXCEPTION_TEXT $TOKEN")
            return value?.let { AppleCredentialReadResult.Ready(it) } ?: AppleCredentialReadResult.Missing
        }
        override fun write(credential: StoredAppleCredential): Boolean {
            writeGate?.let { check(it.await(20, TimeUnit.SECONDS)) }
            if (failWrite) return false
            value = credential
            return true
        }
        override fun clear(): Boolean {
            clears++
            clearGate?.let { check(it.await(20, TimeUnit.SECONDS)) }
            if (failClear) return false
            value = null
            return true
        }
        fun releaseAll() { readGate?.countDown(); writeGate?.countDown(); clearGate?.countDown() }
    }

    private class WebBackend : AppleMusicWebData.Backend {
        var checks = 0
        var calls = 0
        var supported = true
        var callback: (() -> Unit)? = null
        override fun supported(): Boolean { checks++; return supported }
        override fun delete(onComplete: () -> Unit) { calls++; callback = onComplete }
        fun complete() = checkNotNull(callback).invoke()
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
