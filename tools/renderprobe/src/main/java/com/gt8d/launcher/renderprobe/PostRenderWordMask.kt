package com.gt8d.launcher.renderprobe

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density

/** One shader/effect owner per row. The input is the normally rendered overlay Text. */
internal class PostRenderWordMask {
    val shader = RuntimeShader(SOURCE)
    var updates: Int = 0
        private set
    var effectCreations: Int = 0
        private set
    var effect: RenderEffect? = null
        private set

    init {
        shader.setFloatUniform("mode", 0f)
        shader.setFloatUniform("halfWidth", 1f)
        shader.setFloatUniform("boundary", 0f, 0f, 0f)
    }

    // Updating the shader deliberately does NOT replace the retained effect. The probe
    // can draw that exact effect after an update, then compare an explicitly fresh one.
    fun update(fill: Float, layout: TextLayoutResult, density: Density) {
        val text = layout.layoutInput.text
        when {
            fill <= 0f -> shader.setFloatUniform("mode", 0f)
            fill >= text.length -> shader.setFloatUniform("mode", 1f)
            else -> {
                // Match the reference's UTF-16 offsets, line edges and RTL positions.
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

    // One Android RenderEffect and one Compose wrapper per call, including full/zero
    // masks. No assumption that an already-created effect observes changed uniforms.
    fun createEffect(): RenderEffect {
        val created = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
        effectCreations++
        effect = created
        return created
    }

    private companion object {
        const val SOURCE = """
            uniform shader content;
            uniform float mode;
            uniform float3 boundary;
            uniform float halfWidth;

            half4 main(float2 position) {
                half4 color = content.eval(position);
                if (mode == 0.0) return color * 0.0;
                if (mode == 1.0) return color;
                if (position.y < boundary.y) return color;
                if (position.y >= boundary.z) return color * 0.0;
                float alpha = clamp((boundary.x + halfWidth - position.x) / (2.0 * halfWidth), 0.0, 1.0);
                return color * alpha;
            }
        """
    }
}
