package com.gt8d.launcher.ui.layout

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.gt8d.launcher.ui.theme.Dims

val OneLineKey = SemanticsPropertyKey<Boolean>("OneLine")

@Composable
fun OneLineText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = Dims.BodyText,
    color: Color = Color.White,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        modifier = modifier.semantics { this[OneLineKey] = true },
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
