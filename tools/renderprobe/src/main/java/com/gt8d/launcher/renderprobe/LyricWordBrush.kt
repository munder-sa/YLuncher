package com.gt8d.launcher.renderprobe

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density

/** One RuntimeShader per row owner. No global shader or reference-renderer dependency. */
internal class LyricWordBrush {
    val shader = RuntimeShader(SOURCE)
    var updates: Int = 0
        private set

    init {
        shader.setFloatUniform("mode", 0f)
        shader.setFloatUniform("halfWidth", 1f)
        shader.setFloatUniform("boundary", 0f, 0f, 0f)
    }

    // Each capture recomposes Text with a fresh wrapper around the SAME RuntimeShader.
    // This is a correctness experiment, not a claim about draw-only invalidation/performance.
    fun textBrush() = ShaderBrush(shader)

    fun update(fill: Float, layout: TextLayoutResult, density: Density) {
        val text = layout.layoutInput.text
        when {
            fill <= 0f -> shader.setFloatUniform("mode", 0f)
            fill >= text.length -> shader.setFloatUniform("mode", 1f)
            else -> {
                // Preserve KaraokeLine's UTF-16 offsets, including RTL and surrogate interiors.
                val offset = fill.toInt().coerceAtMost(text.lastIndex)
                val line = layout.getLineForOffset(offset)
                val startX = layout.getHorizontalPosition(offset, true)
                val nextX = if (offset + 1 < layout.getLineEnd(line)) {
                    layout.getHorizontalPosition(offset + 1, true)
                } else layout.getLineRight(line)
                val x = startX + (nextX - startX) * (fill - offset)
                shader.setFloatUniform("boundary", x, layout.getLineTop(line), layout.getLineBottom(line))
                shader.setFloatUniform("halfWidth", 12f * density.density)
                shader.setFloatUniform("mode", 2f)
            }
        }
        updates++
    }

    private companion object {
        const val SOURCE = """
            uniform float mode;
            uniform float3 boundary;
            uniform float halfWidth;

            half4 main(float2 position) {
                if (mode == 0.0) return half4(0.0);
                if (mode == 1.0) return half4(1.0);
                if (position.y < boundary.y) return half4(1.0);
                if (position.y >= boundary.z) return half4(0.0);
                float alpha = clamp((boundary.x + halfWidth - position.x) / (2.0 * halfWidth), 0.0, 1.0);
                return half4(alpha, alpha, alpha, alpha);
            }
        """
    }
}
