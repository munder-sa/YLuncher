package com.gt8d.launcher.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.media.lyrics.AppleLyricsReason
import com.gt8d.launcher.media.lyrics.AppleMusicSession
import com.gt8d.launcher.media.lyrics.AppleMusicStatus
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AppleMusicLyricsScreen(session: AppleMusicSession, modifier: Modifier = Modifier) {
    val connection by session.state.collectAsState()
    var token by remember(session) { mutableStateOf("") }
    var connectJob by remember(session) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(session) {
        // Cancelling a completed job leaves its validated connection intact.
        onDispose { connectJob?.cancel() }
    }
    val checking = connection.status == AppleMusicStatus.CHECKING

    Column(modifier.fillMaxWidth()) {
        SettingsSection {
            Column(Modifier.fillMaxWidth().background(Palette.Panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Apple Music Web のトークンを入力して接続を確認します。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Color.White)
                Text("アプリの終了・再起動後は再入力が必要です。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Palette.Muted)
                Text("取得できない曲は、ほかの歌詞サービスで表示します。",
                    Modifier.fillMaxWidth(), fontSize = Dims.BodyText, color = Palette.Muted)
                Text(
                    when (connection.status) {
                        AppleMusicStatus.UNCONFIGURED -> "未接続"
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
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth().testTag("apple_music_token"),
                    label = { Text("Web用トークン") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !checking,
                    textStyle = TextStyle(fontSize = Dims.BodyText),
                )
                SettingsButton("接続確認", "apple_music_connect", Modifier.fillMaxWidth(),
                    kind = SettingsButtonKind.Primary, enabled = token.isNotBlank() && !checking) {
                    val input = token
                    token = ""
                    connectJob = scope.launch { session.connect(input) }
                }
                SettingsButton("解除", "apple_music_disconnect", Modifier.fillMaxWidth(),
                    enabled = connection.status != AppleMusicStatus.UNCONFIGURED || token.isNotEmpty()) {
                    token = ""
                    connectJob?.cancel()
                    connectJob = null
                    session.disconnect()
                }
            }
        }
    }
}

private fun connectionFailureText(reason: AppleLyricsReason?): String = when (reason) {
    AppleLyricsReason.AUTH_REQUIRED -> "接続を確認できませんでした。トークンを確認して再入力してください。"
    AppleLyricsReason.NETWORK -> "通信できませんでした。接続を確認してやり直してください。"
    AppleLyricsReason.TIMEOUT -> "確認に時間がかかりました。もう一度お試しください。"
    AppleLyricsReason.WAIT -> "しばらく待ってから接続を確認してください。"
    AppleLyricsReason.INVALID -> "入力内容または応答を確認できませんでした。"
    else -> "接続を確認できませんでした。時間をおいてやり直してください。"
}
