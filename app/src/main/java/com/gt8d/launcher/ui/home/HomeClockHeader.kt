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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gt8d.launcher.ui.layout.FitClock
import com.gt8d.launcher.ui.lifecycle.VisibleUiEffect
import com.gt8d.launcher.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeClockHeader(
    maxClockHeight: Dp,
    modifier: Modifier = Modifier,
    now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    var dateTime by remember { mutableStateOf(now().truncatedTo(ChronoUnit.MINUTES)) }
    VisibleUiEffect(now) {
        while (true) {
            dateTime = now().truncatedTo(ChronoUnit.MINUTES)
            delay(1_000L)
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dateStyle = LocalTextStyle.current.copy(fontSize = 32.sp, lineHeight = 40.sp, color = Palette.Muted)
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN))
    val date = dateTime.format(DateTimeFormatter.ofPattern("M月d日(E)", Locale.JAPAN))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val heightPx = with(density) { maxClockHeight.toPx() }
        // Use FitClock's width/height selection before reserving any space for the date.
        var clockWidthPx = 0
        var clockSp = 40f
        for (size in 150 downTo 40 step 2) {
            val style = TextStyle(fontSize = size.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
            val result = measurer.measure(
                "00:00", style,
                softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = constraints.maxWidth),
            )
            clockWidthPx = result.size.width
            clockSp = size.toFloat()
            val actual = measurer.measure(time, style, softWrap = false, maxLines = 1,
                constraints = Constraints.fixedWidth(clockWidthPx))
            if (!result.hasVisualOverflow && !actual.hasVisualOverflow && actual.size.height <= heightPx) break
        }
        val dateWidth = measurer.measure(date, dateStyle, softWrap = false, maxLines = 1).size.width
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
