package com.pendulum.phone.ui.chart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * The horizontal transform, shared between the night chart and the hypnogram.
 *
 * ### Why this is an object hoisted into the parent and not a state internal to each chart
 *
 * The two charts must stay aligned to the pixel: a movement marked at 02:14 in the upper chart must
 * fall exactly above stage N2 in the hypnogram below. Two independent zoom states drift as soon as
 * a gesture is lost, and the resulting misalignment is a visual lie — it makes a movement be read
 * in the wrong stage.
 *
 * A single owner of the interaction, therefore: the night chart handles the gestures, the hypnogram
 * only reads.
 *
 * ### Why `mutableFloatStateOf`
 *
 * A pinch changes [scale] sixty times a second. If these values lived in the screen's state, the
 * whole hierarchy would recompose. Being `@Stable` with float states read only inside the `Canvas`,
 * only the drawing phase is invalidated: no recomposition, no remeasure.
 */
@Stable
class XTransform(
    val t0Ms: Long,
    val t1Ms: Long,
    val zoomMax: Float = 480f,
) {
    /** 1 = the whole night. 480 = one minute visible out of eight hours. */
    var scale by mutableFloatStateOf(1f)
        private set

    /** Start of the visible window, as a fraction 0..1 of the night. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Width of the plot area, in pixels. Written by the `Canvas` on every draw. */
    var widthPx by mutableFloatStateOf(1f)

    private val spanMs: Float get() = (t1Ms - t0Ms).toFloat().coerceAtLeast(1f)

    fun xOf(timeMs: Long): Float {
        val u = (timeMs - t0Ms) / spanMs
        return (u - offset) * scale * widthPx
    }

    fun timeAt(x: Float): Long {
        val u = offset + (x / widthPx) / scale
        return t0Ms + (u * spanMs).toLong()
    }

    /** Visible fraction of the night, in 0..1. Used to choose the level of the pyramid. */
    val visibleWindow: ClosedFloatingPointRange<Float> get() = offset..(offset + 1f / scale)

    fun applyPinch(centroidX: Float, panX: Float, zoom: Float) {
        val underFinger = offset + (centroidX / widthPx) / scale
        scale = (scale * zoom).coerceIn(1f, zoomMax)
        // We keep the point under the finger still, then apply the pan.
        offset = underFinger - (centroidX / widthPx) / scale - (panX / widthPx) / scale
        clamp()
    }

    fun reset() {
        scale = 1f
        offset = 0f
    }

    private fun clamp() {
        val visibleWidth = 1f / scale
        offset = offset.coerceIn(0f, (1f - visibleWidth).coerceAtLeast(0f))
    }
}
