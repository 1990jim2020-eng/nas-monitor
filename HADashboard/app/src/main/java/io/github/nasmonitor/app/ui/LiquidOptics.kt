package io.github.nasmonitor.app.ui

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

internal fun refractedAxis(position: Float, extent: Float, rim: Float): Float {
    if (extent <= 0f || rim <= 0f) return position
    val fraction = (position / extent).coerceIn(0f, 1f)
    val edge = min(position, extent - position).coerceAtLeast(0f)
    val strength = min(rim, extent / 8f)
    return position + sin(fraction * 2f * Math.PI.toFloat()) * strength * exp(-edge / (rim * 2f))
}

internal fun usesDarkSurfaces(mode: String): Boolean = mode in setOf("dark", "purple", "aurora")
