package com.gt8d.launcher.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gt8d.launcher.ui.layout.FitClock
import com.gt8d.launcher.ui.layout.rememberClockLayoutCache
import com.gt8d.launcher.ui.lifecycle.rememberMinuteTime
import com.gt8d.launcher.ui.theme.Palette
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeClockHeader(
    maxClockHeight: Dp,
    modifier: Modifier = Modifier,
    now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    val dateTime by rememberMinuteTime(now)
    val density = LocalDensity.current
    val dateStyle = LocalTextStyle.current.copy(fontSize = 32.sp, lineHeight = 40.sp, color = Palette.Muted)
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN))
    val date = dateTime.format(DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val heightPx = with(density) { maxClockHeight.toPx() }
        val clockStyle = TextStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        val clockCache = rememberClockLayoutCache("header", clockStyle, constraints, maxClockHeight, 40f, 150f)
        // Parent and child keep separate caches because their constraints differ.
        val (clockWidthPx, clockSp) = remember(clockCache, time) {
            var width = 0
            var selectedSp = 40f
            for (size in 150 downTo 40 step 2) {
                val style = clockStyle.copy(fontSize = size.sp)
                val result = clockCache.template(style, Constraints(maxWidth = constraints.maxWidth))
                width = result.size.width
                selectedSp = size.toFloat()
                val actual = clockCache.measure(time, style, Constraints.fixedWidth(width))
                if (!result.hasVisualOverflow && !actual.hasVisualOverflow && actual.size.height <= heightPx) break
            }
            width to selectedSp
        }
        val dateCache = rememberClockLayoutCache("date", dateStyle, constraints, maxClockHeight, 32f, 32f)
        val dateWidth = remember(dateCache, date) {
            dateCache.measure(date, dateStyle).size.width
        }
        val clockWidth = with(density) { clockWidthPx.toDp() }
        val dateModifier = Modifier.width(with(density) { dateWidth.toDp() }).testTag("home_date")
        val fits = clockWidthPx + with(density) { 24.dp.toPx() } + dateWidth <= constraints.maxWidth
        if (fits) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FitClock(time, maxClockHeight, Modifier.width(clockWidth).alignByBaseline(), maxSp = clockSp)
                Text(date, dateModifier.alignByBaseline(), style = dateStyle,
                    softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
            }
        } else {
            Column {
                FitClock(time, maxClockHeight, Modifier.width(clockWidth), maxSp = clockSp)
                Text(date, dateModifier, style = dateStyle,
                    softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}
