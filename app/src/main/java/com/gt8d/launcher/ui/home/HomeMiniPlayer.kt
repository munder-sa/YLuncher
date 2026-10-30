package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.R
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.panel.MediaAction
import com.gt8d.launcher.ui.panel.SeekBar
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun HomeMiniPlayer(
    now: NowPlaying?,
    idleApp: AppInfo?,
    positionMs: Long,
    appInfo: (String) -> AppInfo,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.testTag("home_player"), shape = RoundedCornerShape(32.dp), color = Palette.Panel) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp)) {
            if (now == null) {
                if (idleApp != null) {
                    Column(Modifier.fillMaxWidth().align(Alignment.Center)
                        .then(if (idleApp.installed) Modifier.clickable { onLaunch(idleApp.pkg) } else Modifier)
                        .testTag("player_idle_app"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppImage(idleApp, Modifier.size(160.dp))
                        OneLineText(idleApp.label)
                        OneLineText(if (idleApp.installed) "タップで開く" else "アプリが見つかりません", color = Palette.Muted)
                    }
                }
            } else {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val textStyle = LocalTextStyle.current
                fun lineHeight(text: String, size: Int, weight: FontWeight? = null) = with(density) {
                    measurer.measure(text, textStyle.copy(fontSize = size.sp, fontWeight = weight),
                        maxLines = 1, softWrap = false).size.height.toDp()
                }
                val titleHeight = lineHeight(now.title, 28, FontWeight.SemiBold)
                val artistHeight = now.artist?.let { lineHeight(it, 20) } ?: 0.dp
                val timeHeight = lineHeight("0:00", 20)
                val seekHeight = if ((now.durationMs ?: 0L) > 0L) 72.dp else 0.dp
                val wrapFavorite = now.hasFavorite && maxWidth < 336.dp
                val controlsHeight = if (wrapFavorite) 176.dp else 96.dp
                val required = titleHeight + artistHeight + seekHeight + controlsHeight
                val showDetails = maxHeight >= required + 44.dp + (if (seekHeight > 0.dp) timeHeight else 0.dp)
                val artSize = minOf(maxWidth, (maxHeight - required -
                    (if (showDetails) 44.dp + (if (seekHeight > 0.dp) timeHeight else 0.dp) else 0.dp)).coerceAtLeast(0.dp))
                Column(Modifier.fillMaxSize()) {
                    if (showDetails) {
                        val app = appInfo(now.pkg)
                        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppImage(app, Modifier.size(36.dp))
                            OneLineText(app.label, Modifier.weight(1f), color = Palette.Muted)
                        }
                    }
                    if (artSize > 0.dp) {
                        val artwork = now.artwork
                        val artModifier = Modifier.size(artSize).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp))
                        if (artwork == null) Image(painterResource(R.drawable.artwork_placeholder), null, artModifier)
                        else Image(artwork.asImageBitmap(), null, artModifier)
                    }
                    OneLineText(now.title, Modifier.fillMaxWidth().testTag("player_title"),
                        fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    now.artist?.let { OneLineText(it, Modifier.fillMaxWidth().testTag("player_artist"), color = Palette.Muted) }
                    SeekBar(now.durationMs, positionMs, onSeek, tag = "player_seek", showTimes = showDetails, timesTag = "player_times")
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (wrapFavorite) PlayerControl(MediaAction.Favorite, onAction)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (now.hasFavorite && !wrapFavorite) PlayerControl(MediaAction.Favorite, onAction)
                            PlayerControl(MediaAction.Prev, onAction)
                            PlayerControl(if (now.playing) MediaAction.Pause else MediaAction.Play, onAction)
                            PlayerControl(MediaAction.Next, onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppImage(app: AppInfo, modifier: Modifier) {
    if (app.icon != null) Image(app.icon, app.label, modifier)
    else Box(modifier.clip(CircleShape).background(Palette.Background1), contentAlignment = Alignment.Center) {
        Text(app.label.firstOrNull()?.toString() ?: "?", color = Color.White, fontSize = 32.sp)
    }
}

@Composable
private fun PlayerControl(action: MediaAction, onAction: (MediaAction) -> Unit) {
    val (icon, description, tag) = when (action) {
        MediaAction.Favorite -> Triple(Icons.Default.StarBorder, "お気に入り", "player_fav")
        MediaAction.Prev -> Triple(Icons.Default.SkipPrevious, "前へ", "player_prev")
        MediaAction.Play -> Triple(Icons.Default.PlayArrow, "再生", "player_play")
        MediaAction.Pause -> Triple(Icons.Default.Pause, "一時停止", "player_play")
        MediaAction.Next -> Triple(Icons.Default.SkipNext, "次へ", "player_next")
    }
    val primary = action == MediaAction.Play || action == MediaAction.Pause
    IconButton(onClick = { onAction(action) }, modifier = Modifier.size(if (primary) 96.dp else 72.dp)
        .then(if (primary) Modifier.background(Color.White, CircleShape) else Modifier).testTag(tag)) {
        Icon(icon, description, tint = if (primary) Palette.Background0 else Color.White, modifier = Modifier.size(40.dp))
    }
}
