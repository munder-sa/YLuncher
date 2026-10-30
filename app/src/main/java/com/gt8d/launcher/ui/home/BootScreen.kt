package com.gt8d.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import com.gt8d.launcher.ui.layout.OneLineText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.curtain.CurtainTarget
import com.gt8d.launcher.curtain.SlotPreview
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette

@Composable
fun BootScreen(
    target: CurtainTarget,
    secondsLeft: Int,
    opening: Boolean,
    onOpenNow: () -> Unit,
    onStayHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (leftWeight, rightWeight) = parseRatioWeights(target.ratio)
    val message = if (opening) {
        "前回のセットを開いています…"
    } else {
        "$secondsLeft 秒後に前回のセットを開きます"
    }

    BoxWithConstraints(modifier.widthIn(max = 640.dp)) {
        val stacked = maxWidth < 600.dp
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.Panel)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                fontSize = Dims.BodyText,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("boot_message"),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = target.setName,
                fontSize = Dims.BodyText,
                fontWeight = FontWeight.Bold,
                color = Palette.Accent,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BootSlotBlock(
                    slot = target.left,
                    modifier = Modifier
                        .weight(leftWeight)
                        .fillMaxHeight(),
                )
                BootSlotBlock(
                    slot = target.right,
                    modifier = Modifier
                        .weight(rightWeight)
                        .fillMaxHeight(),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            val buttons: @Composable (Modifier) -> Unit = { buttonModifier ->
                if (!opening) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = buttonModifier
                            .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.Accent)
                            .clickable(onClick = onOpenNow)
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .testTag("boot_open_now"),
                    ) {
                        OneLineText(
                            text = "今すぐ開く",
                            fontSize = Dims.BodyText,
                            fontWeight = FontWeight.Bold,
                            color = Palette.Background0,
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = buttonModifier
                        .sizeIn(minWidth = Dims.MinTouch, minHeight = Dims.MinTouch)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Background1)
                        .clickable(onClick = onStayHome)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .testTag("boot_stay_home"),
                ) {
                    OneLineText(
                        text = "ホームのまま使う",
                        fontSize = Dims.BodyText,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    buttons(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun parseRatioWeights(ratio: SplitRatio): Pair<Float, Float> {
    val parts = ratio.label.split(":")
    val left = parts.getOrNull(0)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    val right = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    return left to right
}

@Composable
private fun BootSlotBlock(
    slot: SlotPreview,
    modifier: Modifier = Modifier,
) {
    val label = when (slot) {
        is SlotPreview.App -> slot.label
        SlotPreview.Panel -> "パネル"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Background1)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = Dims.BodyText,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
