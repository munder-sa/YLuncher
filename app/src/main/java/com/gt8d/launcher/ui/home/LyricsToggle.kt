package com.gt8d.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.ui.theme.Palette

val LyricsIcon: ImageVector = ImageVector.Builder("Lyrics", 40.dp, 40.dp, 40f, 40f).apply {
    addPath(
        pathData = addPathNodes("M9 6h22a5 5 0 0 1 5 5v15a5 5 0 0 1-5 5H17l-8 5v-5a5 5 0 0 1-5-5V11a5 5 0 0 1 5-5Z M12 14h16 M12 22h12"),
        fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2.5f * 40f / 28f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
}.build()

@Composable
fun LyricsToggle(on: Boolean, available: Boolean, description: String, onToggle: () -> Unit,
                 modifier: Modifier = Modifier, boxAlignment: Alignment = Alignment.Center) {
    Box(modifier.size(72.dp).testTag("player_lyrics_toggle")
        .semantics { contentDescription = description }
        .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() }),
        contentAlignment = boxAlignment) {
        Box(Modifier.size(48.dp).alpha(if (available) 1f else 0.38f)
            .background(if (on || !available) Color.White else Palette.Control, RoundedCornerShape(12.dp))
            .testTag("player_lyrics_toggle_box"), contentAlignment = Alignment.Center) {
            Icon(LyricsIcon, null, Modifier.size(28.dp),
                tint = if (on || !available) Palette.Background0 else Color.White)
        }
    }
}
