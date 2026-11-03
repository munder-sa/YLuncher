package com.gt8d.launcher.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.R
import com.gt8d.launcher.media.NowPlaying
import com.gt8d.launcher.media.lyricsQuery
import com.gt8d.launcher.media.lyrics.LyricsState
import com.gt8d.launcher.ui.layout.OneLineText
import com.gt8d.launcher.ui.layout.MarqueeText
import com.gt8d.launcher.ui.panel.AppInfo
import com.gt8d.launcher.ui.panel.MediaAction
import com.gt8d.launcher.ui.panel.SeekBar
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.theme.Dims

@Composable
fun HomeMiniPlayer(
    now: NowPlaying?,
    idleApp: AppInfo?,
    position: () -> Long,
    appInfo: (String) -> AppInfo,
    onAction: (MediaAction) -> Unit,
    onSeek: (Long) -> Unit,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier,
    lyrics: LyricsState = LyricsState.Off,
    lyricsEnabled: Boolean = false,
    onToggleLyrics: () -> Unit = {},
) {
    val background = rememberArtworkBackground(now)
    Surface(modifier.testTag("home_player"), shape = RoundedCornerShape(24.dp), color = Palette.Panel) {
        BoxWithConstraints(Modifier.fillMaxSize().artworkBackground(background.value).padding(start = 40.dp, top = 40.dp, end = 40.dp, bottom = 32.dp)) {
            if (now == null) {
                if (idleApp != null) {
                    Column(Modifier.fillMaxWidth().align(Alignment.TopStart).testTag("player_idle_app")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppImage(idleApp, Modifier.size(72.dp).testTag("player_idle_icon"))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                OneLineText(idleApp.label, Modifier.testTag("player_idle_name"))
                                OneLineText(if (idleApp.installed) "再生情報はありません" else "アプリが見つかりません",
                                    Modifier.testTag("player_idle_status"), color = Palette.Muted)
                            }
                        }
                        if (idleApp.installed) {
                            Spacer(Modifier.height(24.dp))
                            OutlinedButton(onClick = { onLaunch(idleApp.pkg) },
                                modifier = Modifier.sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                                    .testTag("player_idle_open"),
                                shape = RoundedCornerShape(36.dp), border = BorderStroke(2.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                OneLineText("${idleApp.label}を開く", Modifier.testTag("player_idle_open_text"))
                            }
                        }
                    }
                }
            } else {
                val app = appInfo(now.pkg)
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
                val controlsHeight = 96.dp
                val showToggle = now.lyricsQuery != null
                val appRowHeight = if (showToggle) 48.dp else 44.dp
                fun layout(spacing: PlayerSpacing): PlayerLayout {
                    val textHeight = titleHeight + artistHeight +
                        (if (now.artist != null) spacing.titleToArtist else 0.dp)
                    val titleRowHeight = maxOf(textHeight, if (now.hasFavorite) 72.dp else 0.dp)
                    val required = titleRowHeight + seekHeight + controlsHeight
                    fun gaps(details: Boolean, art: Boolean) = playerGaps(
                        spacing, details, art, now.artist != null, seekHeight > 0.dp)
                    fun fixed(details: Boolean, art: Boolean) = required +
                        (if (details) appRowHeight + (if (seekHeight > 0.dp) timeHeight else 0.dp) else 0.dp) +
                        gaps(details, art).total
                    val details = maxHeight >= fixed(true, false)
                    val art = minOf(maxWidth, (maxHeight - fixed(details, true)).coerceAtLeast(0.dp))
                    return PlayerLayout(details, art, gaps(details, art > 0.dp), fixed(details, false),
                        (maxHeight - fixed(details, true)).coerceAtLeast(0.dp))
                }
                val normal = layout(PlayerSpacing.Normal)
                val layout = if (normal.artSize >= 120.dp && normal.fixedHeight <= maxHeight) normal else {
                    val compact = layout(PlayerSpacing.Compact)
                    if (compact.fixedHeight <= maxHeight) compact else layout(PlayerSpacing.Minimal)
                }
                val showDetails = layout.showDetails
                val artSize = layout.artSize
                val gaps = layout.gaps
                val showLyrics = showToggle && lyricsEnabled && layout.lyricsHeight >= 120.dp &&
                    (lyrics is LyricsState.Synced || lyrics is LyricsState.Plain || lyrics is LyricsState.Loading)
                val available = !(lyricsEnabled && !showLyrics)
                val reason = if (!available) when (lyrics) {
                    LyricsState.Missing -> "歌詞なし"
                    LyricsState.Offline -> "オフライン"
                    LyricsState.Failed -> "取得できません"
                    else -> null
                } else null
                val description = when {
                    !lyricsEnabled -> "歌詞を表示"
                    available -> "ジャケットを表示"
                    else -> "${reason ?: "表示する場所がありません"}。押すと歌詞表示をオフ"
                }
                val restartKey = now.pkg to now.title to now.artist
                Column(Modifier.fillMaxSize()) {
                    if (showDetails) {
                        Row(Modifier.fillMaxWidth().height(appRowHeight).testTag("player_app_row"), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppImage(app, Modifier.size(36.dp))
                            OneLineText(app.label, Modifier.weight(1f), color = Palette.Muted)
                            if (showToggle) {
                                reason?.let { Text(it, color = Palette.Muted, fontSize = 20.sp,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                LyricsToggle(lyricsEnabled, available, description, onToggleLyrics,
                                    Modifier.layout { measurable, constraints ->
                                        val size = 72.dp.roundToPx()
                                        val box = 48.dp.roundToPx()
                                        val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(size, size))
                                        layout(box, box) { child.placeRelative(-12.dp.roundToPx(), -12.dp.roundToPx()) }
                                    })
                            }
                        }
                    }
                    if (showLyrics) {
                        Spacer(Modifier.height(gaps.beforeArt))
                        LyricsPane(lyrics, now, Modifier.fillMaxWidth().height(layout.lyricsHeight))
                    } else if (artSize > 0.dp) {
                        Spacer(Modifier.height(gaps.beforeArt))
                        val artwork = now.artwork
                        val artModifier = Modifier.size(artSize).align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = app.installed, onClickLabel = "${app.label}を開く") { onLaunch(now.pkg) }
                            .testTag("player_art")
                        if (artwork == null) Image(painterResource(R.drawable.artwork_placeholder), null, artModifier)
                        else Image(artwork.asImageBitmap(), null, artModifier)
                    }
                    Spacer(Modifier.height(gaps.beforeTitle))
                    Row(Modifier.fillMaxWidth().testTag("player_title_row"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).testTag("player_text")) {
                            MarqueeText(now.title, Modifier.fillMaxWidth(), tag = "player_title",
                                fontSize = 28.sp, fontWeight = FontWeight.SemiBold, restartKey = restartKey)
                            now.artist?.let {
                                Spacer(Modifier.height(gaps.beforeArtist))
                                MarqueeText(it, Modifier.fillMaxWidth(), tag = "player_artist", color = Palette.Muted, restartKey = restartKey)
                            }
                        }
                        if (showToggle && !showDetails) {
                            Spacer(Modifier.width(8.dp))
                            LyricsToggle(lyricsEnabled, available, description, onToggleLyrics,
                                Modifier.layout { measurable, _ ->
                                    val size = 72.dp.roundToPx()
                                    val child = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(size, size))
                                    layout(48.dp.roundToPx(), 0) {
                                        child.placeRelative(-24.dp.roundToPx(), -size / 2)
                                    }
                                },
                                boxAlignment = Alignment.CenterEnd)
                        }
                        if (now.hasFavorite) {
                            Spacer(Modifier.width(8.dp))
                            PlayerControl(MediaAction.Favorite, onAction)
                        }
                    }
                    Spacer(Modifier.height(gaps.beforeSeek))
                    SeekBar(now.durationMs, position, onSeek, tag = "player_seek", showTimes = showDetails, timesTag = "player_times")
                    Spacer(Modifier.height(gaps.beforeControls))
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerControl(MediaAction.Prev, onAction)
                        PlayerControl(if (now.playing) MediaAction.Pause else MediaAction.Play, onAction)
                        PlayerControl(MediaAction.Next, onAction)
                    }
                }
            }
        }
    }
}

private data class PlayerGaps(
    val beforeArt: Dp, val beforeTitle: Dp, val beforeArtist: Dp,
    val beforeSeek: Dp, val beforeControls: Dp,
) {
    val total get() = beforeArt + beforeTitle + beforeSeek + beforeControls
}

private data class PlayerLayout(val showDetails: Boolean, val artSize: Dp, val gaps: PlayerGaps,
                              val fixedHeight: Dp, val lyricsHeight: Dp)

private enum class PlayerSpacing(val appToArt: Dp, val artToTitle: Dp, val titleToArtist: Dp,
                               val artistToSeek: Dp, val beforeControls: Dp) {
    Normal(16.dp, 24.dp, 4.dp, 16.dp, 16.dp),
    Compact(8.dp, 8.dp, 2.dp, 8.dp, 8.dp),
    Minimal(0.dp, 0.dp, 0.dp, 4.dp, 0.dp),
}

private fun playerGaps(spacing: PlayerSpacing, app: Boolean, art: Boolean, artist: Boolean, seek: Boolean): PlayerGaps =
    PlayerGaps(
        if (app && art) spacing.appToArt else 0.dp,
        if (art) spacing.artToTitle else 0.dp,
        if (artist) spacing.titleToArtist else 0.dp,
        if (artist && seek) spacing.artistToSeek else 0.dp,
        if (seek) spacing.beforeControls else 0.dp,
    )

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
