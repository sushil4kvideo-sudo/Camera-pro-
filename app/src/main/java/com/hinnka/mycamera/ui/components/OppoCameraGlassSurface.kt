package com.hinnka.mycamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Compose port of OppoCam's LIQUID_GLASS FrostedGlassBox material.
 *
 * The camera backdrop is blurred by LutRenderer from the existing live GL preview texture,
 * so this surface only renders the physical glass substrate and specular layers. It deliberately
 * does not capture a second view, read a bitmap, or create another camera stream.
 */
@Composable
fun OppoCameraGlassSurface(
    modifier: Modifier = Modifier,
    transparency: Float = 0.55f,
    blurStrength: Float = 22f,
    onBoundsChanged: (Rect?) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val userTransparency = transparency.coerceIn(0f, 1f)
    val opacityMultiplier = 1f - userTransparency * 0.90f
    val finalSubstrateAlpha = (0.85f * opacityMultiplier).coerceIn(0.06f, 0.98f)
    val boundedBlur = blurStrength.coerceIn(0f, 50f)
    val blurDiffusionAlpha = (
        (boundedBlur / 50f) * 0.38f * (0.35f + 0.65f * opacityMultiplier)
    ).coerceIn(0f, 0.42f)
    val tintAlpha = (finalSubstrateAlpha * 0.92f).coerceIn(0.08f, 0.95f)
    val sheenAlpha = 0.24f * (0.4f + 0.6f * opacityMultiplier)

    val shape = RoundedCornerShape(30.dp)
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0x99FFD54F),
                Color(0x66FFFFFF),
                Color(0x18FFFFFF),
            )
        )
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(
                    alpha = (0.35f * opacityMultiplier).coerceIn(0.10f, 0.40f)
                ),
                spotColor = Color.Black.copy(
                    alpha = (0.65f * opacityMultiplier).coerceIn(0.20f, 0.70f)
                ),
            )
            .clip(shape)
            .border(width = 1.dp, brush = borderBrush, shape = shape)
    ) {
        // Layer 2: Tinted glass substrate (the real blurred camera frame sits directly below).
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F121C).copy(alpha = tintAlpha),
                            Color(0xFF07090F).copy(alpha = (tintAlpha + 0.08f).coerceAtMost(0.98f)),
                        )
                    )
                )
        )

        // Layer 3: Frosted-glass light diffusion.
        if (blurDiffusionAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = blurDiffusionAlpha * 0.55f),
                                Color.White.copy(alpha = blurDiffusionAlpha * 0.15f),
                                Color.White.copy(alpha = blurDiffusionAlpha * 0.35f),
                            )
                        )
                    )
            )
        }

        // Layer 4: OppoCam Liquid Glass gold/white specular sheen.
        if (sheenAlpha > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0x33FFD54F).copy(alpha = 0.20f * opacityMultiplier),
                                Color.White.copy(alpha = sheenAlpha),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f * opacityMultiplier),
                            )
                        )
                    )
            )
        }

        // Layer 5: Keep all controls sharp and readable above the optical material.
        content()

        // Layer 6: Thin top specular highlight rim.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.45f * (0.5f + 0.5f * opacityMultiplier)),
                            Color.White.copy(alpha = 0.15f * (0.5f + 0.5f * opacityMultiplier)),
                            Color.Transparent,
                        )
                    )
                )
        )
    }
}