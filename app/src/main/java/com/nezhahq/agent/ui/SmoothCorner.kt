package com.nezhahq.agent.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Squircle corner geometry, shared by the Compose [SmoothCornerShape] and the platform-View glass
 * bar.
 *
 * The two callers append to different `Path` types — `androidx.compose.ui.graphics.Path` and
 * `android.graphics.Path` — which is why this emits points through a callback instead of being a
 * `Path` extension. Both previously carried their own copy of the same curve, so a change to the
 * exponent silently applied to only one of them.
 */
internal const val SMOOTH_CORNER_EXPONENT: Double = 2.35

/** Enough segments that the curve reads as smooth at the radii this app uses. */
private const val SMOOTH_CORNER_STEPS = 32

/**
 * Walks one corner from [startDegrees] to [endDegrees], reporting each point to [lineTo].
 *
 * A larger [exponent] rounds the corner further towards a circle; the default is the squircle the
 * design system is built around.
 */
internal fun traceSmoothCorner(
    centerX: Float,
    centerY: Float,
    radius: Float,
    startDegrees: Double,
    endDegrees: Double,
    exponent: Double = SMOOTH_CORNER_EXPONENT,
    lineTo: (x: Float, y: Float) -> Unit
) {
    val power = 2.0 / exponent
    for (step in 1..SMOOTH_CORNER_STEPS) {
        val degrees = startDegrees + (endDegrees - startDegrees) * step / SMOOTH_CORNER_STEPS
        val radians = degrees * PI / 180.0
        lineTo(
            (centerX + radius * cos(radians).signedPow(power)).toFloat(),
            (centerY + radius * sin(radians).signedPow(power)).toFloat()
        )
    }
}

private fun Double.signedPow(power: Double): Double =
    if (this < 0.0) -abs(this).pow(power) else abs(this).pow(power)
