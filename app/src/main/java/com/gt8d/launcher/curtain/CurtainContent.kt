package com.gt8d.launcher.curtain

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt8d.launcher.R
import com.gt8d.launcher.config.SplitRatio
import com.gt8d.launcher.ui.theme.Dims
import com.gt8d.launcher.ui.theme.Palette
import com.gt8d.launcher.ui.home.PanelIcon
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

val CurtainColor = SemanticsPropertyKey<Color>("CurtainColor")
val CurtainTrackColor = SemanticsPropertyKey<Color>("CurtainTrackColor")
val CurtainIconCorner = SemanticsPropertyKey<Int>("CurtainIconCorner")

@Composable
fun CurtainContent(
    target: CurtainTarget,
    showProgress: Boolean,
    iconFor: (String) -> ImageBitmap? = { null },
    onCancel: () -> Unit = {},
) {
    val (leftWeight, rightWeight) = parseRatioWeights(target.ratio)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Palette.Background0.copy(alpha = 0.7f)),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = target.setName,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("curtain_set_name"),
                )
                Text(
                    text = "開いています",
                    fontSize = Dims.BodyText,
                    color = Palette.Muted,
                    modifier = Modifier.testTag("curtain_opening"),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .width(640.dp)
                        .height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SlotPreviewBlock(
                        slot = target.left,
                        mirrored = true,
                        iconFor = iconFor,
                        modifier = Modifier
                            .weight(leftWeight)
                            .fillMaxHeight()
                            .testTag("curtain_left"),
                    )
                    SlotPreviewBlock(
                        slot = target.right,
                        iconFor = iconFor,
                        modifier = Modifier
                            .weight(rightWeight)
                            .fillMaxHeight()
                            .testTag("curtain_right"),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (showProgress) {
                    LinearProgressIndicator(
                        color = Palette.PanelAccent,
                        trackColor = Palette.PanelAccent.copy(alpha = 0.24f),
                        modifier = Modifier
                            .width(640.dp)
                            .height(4.dp)
                            .testTag("curtain_progress")
                            .semantics {
                                this[CurtainColor] = Palette.PanelAccent
                                this[CurtainTrackColor] = Palette.PanelAccent.copy(alpha = 0.24f)
                            },
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.height(Dims.MinTouch),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showProgress && target.cancellable) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.height(Dims.MinTouch)
                                .widthIn(min = Dims.MinTouch)
                                .testTag("curtain_cancel"),
                            shape = RoundedCornerShape(36.dp),
                            border = BorderStroke(2.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Text("ホームへ戻る", fontSize = Dims.BodyText)
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("curtain_dots"),
            ) {
                val safeCount = target.count.coerceAtLeast(0)
                for (i in 0 until safeCount) {
                    val dotColor = if (i == target.position) {
                        Palette.PanelAccent
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                            .testTag("curtain_dot_$i")
                            .semantics { this[CurtainColor] = dotColor },
                    )
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
private fun SlotPreviewBlock(
    slot: SlotPreview,
    iconFor: (String) -> ImageBitmap?,
    mirrored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Panel)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (slot) {
            is SlotPreview.App -> {
                val icon = iconFor(slot.pkg)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = slot.label,
                            modifier = Modifier.size(96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .testTag("curtain_app_icon")
                                .semantics { this[CurtainIconCorner] = 8 },
                        )
                    } else {
                        val initial = slot.label.firstOrNull()?.toString() ?: "?"
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Palette.Background1),
                        ) {
                            Text(
                                text = initial,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = slot.label,
                        fontSize = Dims.BodyText,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SlotPreview.Panel -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PanelIcon(Modifier.size(96.dp), mirrored = mirrored)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "パネル",
                        fontSize = Dims.BodyText,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
