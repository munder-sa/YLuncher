package com.gt8d.launcher.ui.theme

import kotlin.math.abs

object LyricsVisualStyle {
    const val CurrentAlpha = 1f
    const val NextAlpha = 0.62f
    const val WordBaseAlpha = 0.62f
    const val PreviousAlpha = 0.45f
    const val Distance2Alpha = 0.35f
    const val FarAlpha = 0.25f
    const val InactiveScale = 0.97f
    const val ScrollMs = 800
    const val FadeMs = 500
    const val ScaleMs = 500
    const val StaggerStepMs = 50
    const val StaggerMaxMs = 100
    const val SampleMaxSide = 32
}

fun lineTargetAlpha(index: Int, current: Int, hasWords: Boolean): Float {
    if (current < 0) return when (index) {
        0 -> LyricsVisualStyle.NextAlpha
        1 -> LyricsVisualStyle.PreviousAlpha
        2 -> LyricsVisualStyle.Distance2Alpha
        else -> LyricsVisualStyle.FarAlpha
    }
    return when {
        index == current -> if (hasWords) LyricsVisualStyle.WordBaseAlpha else LyricsVisualStyle.CurrentAlpha
        index.toLong() == current.toLong()+1 -> LyricsVisualStyle.NextAlpha
        index.toLong() == current.toLong()-1 -> LyricsVisualStyle.PreviousAlpha
        abs(index.toLong()-current.toLong()) == 2L -> LyricsVisualStyle.Distance2Alpha
        else -> LyricsVisualStyle.FarAlpha
    }
}
