package com.gt8d.launcher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.media.lyrics.AppleLyricsReason
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AppleMusicStatus
import com.gt8d.launcher.media.lyrics.AppleMusicRetentionState
import com.gt8d.launcher.media.lyrics.AppleMusicRetentionStatus
import com.gt8d.launcher.media.lyrics.AppleMusicRetentionProblem
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AppleMusicLyricsScreen(
    session: AppleMusicSession,
    modifier: Modifier = Modifier,
    onWebLogin: (() -> Unit)? = null,
    webData: AppleMusicWebData? = null,
) {
    val connection by session.state.collectAsState()
    val retention by session.retention.collectAsState()
    val webState = webData?.state?.collectAsState()?.value
    var token by remember(session) { mutableStateOf("") }
    var connectJob by remember(session) { mutableStateOf<Job?>(null) }
    var confirmDisconnect by remember(session) { mutableStateOf(false) }
    var clearWeb by remember(session) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) { session.restoreIfNeeded() }
    DisposableEffect(session) {
        // Cancelling a completed job leaves its validated connection intact.
        onDispose { token = ""; connectJob?.cancel(); connectJob = null }
    }
    val checking = connection.status == AppleMusicStatus.CHECKING
    val clearingWeb = webState == AppleMusicWebDataState.CLEARING
    val busy = checking || clearingWeb || retention.status in listOf(
        AppleMusicRetentionStatus.READING, AppleMusicRetentionStatus.SAVING, AppleMusicRetentionStatus.CLEARING)
    LaunchedEffect(clearingWeb) {
        if (clearingWeb) { token = ""; connectJob?.cancel(); connectJob = null }
    }
    val disconnect = {
        token = ""
        connectJob?.cancel()
        connectJob = null
        // Both operations own application-scope work and outlive this composable.
        session.disconnect()
        if (clearWeb) webData?.clear()
        Unit
    }

    Column(modifier.fillMaxWidth()) {
        SettingsSection {
            Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apple Musicにログインして歌詞を接続します。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Color.White)
                Text("接続情報をこの端末に保存し、再起動後に復元します。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Palette.Muted)
                Text("取得できない曲は、ほかの歌詞サービスで表示します。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Palette.Muted)
                Text(
                    when (connection.status) {
                        AppleMusicStatus.UNCONFIGURED -> when {
                            retention.status == AppleMusicRetentionStatus.CLEARING -> "接続情報を削除中"
                            retention.problem == AppleMusicRetentionProblem.CLEAR -> "接続情報の削除を確認できません"
                            else -> "未接続"
                        }
                        AppleMusicStatus.CHECKING -> "接続を確認中"
                        AppleMusicStatus.CONNECTED -> "接続済み（地域: ${connection.storefront}）"
                        AppleMusicStatus.NEEDS_ATTENTION -> "接続の再確認が必要"
                    },
                    Modifier.fillMaxWidth().testTag("apple_music_status"), fontSize = Dims.BodyText, color = Color.White,
                )
                if (connection.status == AppleMusicStatus.NEEDS_ATTENTION) {
                    Text(connectionFailureText(connection.reason), Modifier.fillMaxWidth(),
                        fontSize = Dims.BodyText, color = Palette.Muted)
                }
                retentionText(retention)?.let {
                    Text(it, Modifier.fillMaxWidth().testTag("apple_music_retention"),
                        fontSize = Dims.BodyText, color = Palette.Muted)
                }
                webDataText(webState)?.let {
                    Text(it, Modifier.fillMaxWidth().testTag("apple_music_web_data_status"),
                        fontSize = Dims.BodyText, color = Palette.Muted)
                }
                SettingsButton("Apple Musicにログイン", "apple_music_web_login", Modifier.fillMaxWidth(),
                    kind = SettingsButtonKind.Primary, enabled = onWebLogin != null && !busy) {
                    if (webData?.canStart != false) {
                        token = ""
                        connectJob?.cancel()
                        connectJob = null
                        onWebLogin?.invoke()
                    }
                }
                if (connection.status == AppleMusicStatus.NEEDS_ATTENTION &&
                    retention.status == AppleMusicRetentionStatus.SAVED) {
                    SettingsButton("保存済みの接続を確認", "apple_music_restore", Modifier.fillMaxWidth(),
                        enabled = !busy) { session.restoreIfNeeded() }
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth().testTag("apple_music_token"),
                    label = { Text("Web用トークン") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(fontSize = Dims.BodyText),
                )
                SettingsButton("接続確認", "apple_music_connect", Modifier.fillMaxWidth(),
                    enabled = token.isNotBlank() && !busy) {
                    if (webData?.canStart != false) {
                        val input = token
                        token = ""
                        connectJob = scope.launch { session.connect(input) }
                    }
                }
                SettingsButton("解除", "apple_music_disconnect", Modifier.fillMaxWidth(),
                    enabled = !clearingWeb && retention.status != AppleMusicRetentionStatus.CLEARING &&
                        (webData != null || connection.status != AppleMusicStatus.UNCONFIGURED ||
                            token.isNotEmpty() || retention.status != AppleMusicRetentionStatus.NONE)) {
                    if (webData == null) disconnect() else {
                        clearWeb = true
                        confirmDisconnect = true
                    }
                }
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Apple Musicの接続を解除") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("保存した接続情報を削除します。", fontSize = Dims.BodyText)
                    Row(Modifier.fillMaxWidth().testTag("apple_music_clear_web")
                        .toggleable(clearWeb, role = Role.Checkbox, onValueChange = { clearWeb = it }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearWeb, onCheckedChange = null)
                        Text("この端末のAppleログイン情報も削除", Modifier.weight(1f), fontSize = Dims.BodyText)
                    }
                }
            },
            confirmButton = {
                SettingsButton("解除する", "apple_music_disconnect_confirm", kind = SettingsButtonKind.Danger,
                    enabled = !clearingWeb) {
                    confirmDisconnect = false
                    disconnect()
                }
            },
            dismissButton = {
                SettingsButton("キャンセル", "apple_music_disconnect_cancel") { confirmDisconnect = false }
            },
        )
    }
}

internal fun connectionFailureText(reason: AppleLyricsReason?): String = when (reason) {
    AppleLyricsReason.AUTH_REQUIRED -> "接続を確認できませんでした。Apple Musicにもう一度ログインしてください。"
    AppleLyricsReason.NETWORK -> "通信できませんでした。接続を確認してやり直してください。"
    AppleLyricsReason.TIMEOUT -> "確認に時間がかかりました。もう一度お試しください。"
    AppleLyricsReason.WAIT -> "しばらく待ってから接続を確認してください。"
    AppleLyricsReason.INVALID -> "入力内容または応答を確認できませんでした。"
    else -> "接続を確認できませんでした。時間をおいてやり直してください。"
}

internal fun retentionText(state: AppleMusicRetentionState): String? = when (state.status) {
    AppleMusicRetentionStatus.NONE -> null
    AppleMusicRetentionStatus.READING -> "保存した接続情報を読み込み中"
    AppleMusicRetentionStatus.SAVING -> "接続情報を保存中"
    AppleMusicRetentionStatus.SAVED -> "この端末に保存済み"
    AppleMusicRetentionStatus.SESSION_ONLY -> "この起動中のみ接続できます"
    AppleMusicRetentionStatus.CLEARING -> "接続情報を削除中"
    AppleMusicRetentionStatus.ERROR -> when (state.problem) {
        AppleMusicRetentionProblem.READ -> "保存した接続情報を読み込めませんでした。もう一度ログインしてください。"
        AppleMusicRetentionProblem.CLEAR -> "接続情報の削除に失敗しました。もう一度解除してください。"
        else -> "接続情報を保存できませんでした。この起動中のみ接続できます"
    }
}

private fun webDataText(state: AppleMusicWebDataState?): String? = when (state) {
    AppleMusicWebDataState.CLEARING -> "Appleログイン情報を削除中"
    AppleMusicWebDataState.CLEARED -> "Appleログイン情報を削除しました"
    AppleMusicWebDataState.FAILED -> "Appleログイン情報の削除に失敗しました。もう一度解除してください。"
    else -> null
}
