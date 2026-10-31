package com.gt8d.launcher.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.R
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.layout.MarqueeText

val MediaControlBackground = SemanticsPropertyKey<Color>("MediaControlBackground")

@Composable
fun MediaCard(
    now: NowPlaying?,
    idleApp: AppInfo?,
    positionMs: Long,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
    onLaunch: (String) -> Unit,
) {
    Surface(
        color = Palette.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("media_card"),
    ) {
        if (now == null) {
            IdleMediaContent(idleApp = idleApp, onLaunch = onLaunch)
        } else {
            ActiveMediaContent(
                now = now,
                positionMs = positionMs,
                onAction = onAction,
                onSeek = onSeek,
                onLaunch = onLaunch,
            )
        }
    }
}

@Composable
private fun IdleMediaContent(
    idleApp: AppInfo?,
    onLaunch: (String) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val showName = maxWidth >= 300.dp
        if (idleApp != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                    .semantics { contentDescription = idleApp.label }
                    .clickable {
                        if (idleApp.installed) {
                            onLaunch(idleApp.pkg)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("idle_app"),
            ) {
                if (idleApp.icon != null) {
                    Image(
                        bitmap = idleApp.icon,
                        contentDescription = idleApp.label,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    val initial = idleApp.label.firstOrNull()?.toString() ?: "?"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = idleApp.label }
                            .clip(CircleShape)
                            .background(Palette.Background1),
                    ) {
                        Text(
                            text = initial,
                            fontSize = Dims.BodyText,
                            color = Color.White,
                        )
                    }
                }
                if (showName || !idleApp.installed) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OneLineText(
                        text = if (idleApp.installed) idleApp.label else "アプリが見つかりません",
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveMediaContent(
    now: NowPlaying,
    positionMs: Long,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
    onLaunch: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val bitmap = now.artwork
            val artModifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "再生中のアプリを開く") { onLaunch(now.pkg) }
                .testTag("artwork")
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = now.title,
                    modifier = artModifier,
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.artwork_placeholder),
                    contentDescription = now.title,
                    modifier = artModifier,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).testTag("media_metadata")) {
                val restartKey = now.pkg to now.title to now.artist
                MarqueeText(
                    text = now.title,
                    fontSize = Dims.BodyText,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    tag = "title",
                    restartKey = restartKey,
                )
                if (now.artist != null) {
                    MarqueeText(
                        text = now.artist,
                        fontSize = Dims.BodyText,
                        color = Color.LightGray,
                        tag = "artist",
                        restartKey = restartKey,
                    )
                }
            }
            if (now.hasFavorite) {
                Spacer(Modifier.width(8.dp))
                MediaControl(MediaAction.Favorite, onAction)
            }
        }

        SeekBar(now.durationMs, positionMs, onSeek)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = ((maxWidth - 216.dp) / 2).coerceIn(0.dp, 8.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaControl(MediaAction.Prev, onAction)
                MediaControl(if (now.playing) MediaAction.Pause else MediaAction.Play, onAction)
                MediaControl(MediaAction.Next, onAction)
            }
        }
    }
}

@Composable
private fun MediaControl(action: MediaAction, onAction: (MediaAction) -> Unit) {
    val (icon, description, tag) = when (action) {
        MediaAction.Prev -> Triple(Icons.Default.SkipPrevious, "前へ", "btn_prev")
        MediaAction.Play -> Triple(Icons.Default.PlayArrow, "再生", "btn_playpause")
        MediaAction.Pause -> Triple(Icons.Default.Pause, "一時停止", "btn_playpause")
        MediaAction.Next -> Triple(Icons.Default.SkipNext, "次へ", "btn_next")
        MediaAction.Favorite -> Triple(Icons.Default.StarBorder, "お気に入り", "btn_favorite")
    }
    val isPlayPause = action == MediaAction.Play || action == MediaAction.Pause
    val background = if (isPlayPause) Palette.PanelAccent else Color.Transparent
    IconButton(onClick = { onAction(action) }, modifier = Modifier.size(72.dp)
        .background(background, CircleShape)
        .semantics { this[MediaControlBackground] = background }.testTag(tag)) {
        Icon(icon, description, tint = if (isPlayPause) Palette.Background0 else Palette.PanelAccent,
            modifier = Modifier.size(36.dp))
    }
}
