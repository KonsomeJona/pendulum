package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import com.pendulum.phone.ui.theme.ChartTokens

/**
 * The hypnogram: a discrete staircase, three lanes, strict alignment with the night chart.
 *
 * | Lane | Height | Content |
 * |---|---|---|
 * | Accelerometer mask | 12 dp | moving / still |
 * | Health Connect hypnogram | 72 dp | Wake, REM, N1, N2, N3, from top to bottom |
 * | Disagreement | 6 dp | segments where the two masks diverge, amber hatching |
 *
 * ### What the layout says without writing it
 *
 * The vertical order is that of sleep laboratories: wake at the top, deep sleep at the bottom. It
 * is the **position** that carries the stage, not the colour — a colour-blind reader reads this
 * hypnogram as well as anyone, and a greyscale screenshot loses nothing.
 *
 * REM is the only stage to receive a distinct visual treatment, because it is the only one whose
 * interpretation changes the reading: few periodic movements are expected there. It is marked by a
 * **diagonal hatching**, not by a hue alone.
 *
 * ### No gestures
 *
 * This function handles no interaction: it receives `cursorMs`. The single owner of the gesture is
 * the night chart. Two independent zoom handlers drift, and a hypnogram out by a pixel makes a
 * movement be read in the wrong stage.
 */
/**
 * The six words of the left margin, **already resolved**.
 *
 * Same reason as [BandLabels]: a `DrawScope` has neither composition nor `Context`, and the five
 * stage names therefore cannot live in the constructor of [StageUi] — a string written there would
 * never be reached by a `values-fr/`. `Hypnogram` reads them once, in composition.
 */
data class HypnogramLabels(
    val still: String,
    val wake: String,
    val rem: String,
    val n1: String,
    val n2: String,
    val n3: String,
) {
    fun of(s: StageUi): String = when (s) {
        StageUi.WAKE -> wake
        StageUi.REM -> rem
        StageUi.N1 -> n1
        StageUi.N2 -> n2
        StageUi.N3 -> n3
    }
}

fun DrawScope.drawHypnogram(
    spec: HypnogramSpec,
    t: ChartTokens,
    x: XTransform,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    labels: HypnogramLabels,
    cursorMs: Long? = null,
) {
    val m = defaultMargins
    val area = plotArea(m.copy(bottom = dpPx(4f)))
    x.widthPx = area.width

    val maskHeight = dpPx(12f)
    val disagreementHeight = dpPx(6f)
    val gap = dpPx(4f)
    val stagesHeight = (area.height - maskHeight - disagreementHeight - 2 * gap).coerceAtLeast(dpPx(24f))

    val maskY = area.top
    val stagesY = maskY + maskHeight + gap
    val disagreementY = stagesY + stagesHeight + gap

    fun xOf(ms: Long) = area.left + x.xOf(ms)

    // --- lane 1: accelerometer mask. Contiguous intervals are already merged in the Spec:
    //     without that, an 8 h night would produce a thousand 30 s rectangles.
    for (iv in spec.accelStillness) {
        val x0 = xOf(iv.startMs).coerceIn(area.left, area.right)
        val x1 = xOf(iv.endMs).coerceIn(area.left, area.right)
        if (x1 <= x0) continue
        drawRect(t.structural, Offset(x0, maskY), Size(x1 - x0, maskHeight), alpha = t.maskLaneAlpha)
    }
    axisText(measurer, scratch, t, labels.still, dpPx(2f), maskY - dpPx(1f))

    // --- lane 2: the hypnogram, or its absence
    if (spec.stages == null) {
        // No empty lane with its ticks: an empty axis invites the eye to look for a curve, and
        // makes absent data look like a rendering failure.
        drawRect(t.mutedBand, Offset(area.left, stagesY), Size(area.width, stagesHeight))
        axisText(
            measurer, scratch, t, spec.unavailableText,
            area.left + area.width / 2f, stagesY + stagesHeight / 2f - dpPx(6f),
            color = t.annotationText, centred = true,
        )
    } else {
        drawStaircase(spec.stages, t, area, stagesY, stagesHeight, measurer, scratch, labels, ::xOf)

        // --- lane 3: disagreement between the two masks. It only exists if both exist.
        for (iv in spec.disagreement) {
            val x0 = xOf(iv.startMs).coerceIn(area.left, area.right)
            val x1 = xOf(iv.endMs).coerceIn(area.left, area.right)
            if (x1 <= x0) continue
            hatch(Rect(x0, disagreementY, x1, disagreementY + disagreementHeight), t.attention, t.strokeThin, dpPx(3f))
        }
    }

    cursorMs?.let {
        val cx = xOf(it)
        if (cx in area.left..area.right) {
            drawLine(t.annotationText, Offset(cx, area.top), Offset(cx, area.bottom), t.strokeThin)
        }
    }
}

/**
 * The staircase proper.
 *
 * The `Path` is built in coordinates **normalised 0..1 on X**, which allows it to be memoised once
 * per data set and transformed at draw time: zooming never rebuilds the path. Here it is rebuilt on
 * every call because the function must stay a pure function of `DrawScope` — the memoisation
 * belongs to the wrapping composable, which keeps the `Path` in a `remember(spec)`.
 */
private fun DrawScope.drawStaircase(
    stages: List<StageSegment>,
    t: ChartTokens,
    area: Rect,
    top: Float,
    height: Float,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    labels: HypnogramLabels,
    xOf: (Long) -> Float,
) {
    val levelHeight = height / StageUi.LEVELS
    fun yOf(s: StageUi) = top + s.rank * levelHeight + levelHeight / 2f

    // Level labels: the vertical position is the primary carrier of the information.
    for (s in StageUi.entries) {
        axisText(measurer, scratch, t, labels.of(s), dpPx(2f), yOf(s) - dpPx(6f))
    }

    val path = Path()
    var first = true
    var previousY = 0f
    for (seg in stages) {
        val x0 = xOf(seg.startMs)
        val x1 = xOf(seg.endMs)
        val y = yOf(seg.stage)
        if (first) {
            path.moveTo(x0, y)
            first = false
        } else {
            // Vertical step at the stage change, then a horizontal tread.
            path.lineTo(x0, previousY)
            path.lineTo(x0, y)
        }
        path.lineTo(x1, y)
        previousY = y

        // REM: diagonal hatching inside its tread. A PathEffect on the stroke does not fill an
        // area — one has to clip and loop.
        if (seg.stage == StageUi.REM && x1 > x0) {
            val r = Rect(
                x0.coerceIn(area.left, area.right),
                y - levelHeight / 2f,
                x1.coerceIn(area.left, area.right),
                y + levelHeight / 2f,
            )
            if (r.width > 0f) hatch(r, t.secondSignal, t.strokeThin, dpPx(6f))
        }
    }
    drawPath(path, t.primaryData, style = Stroke(width = t.strokeBold))
}
