package com.pendulum.phone.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.sp
import com.pendulum.phone.ui.theme.ChartTokens

/**
 * The device state band — third band, same axis, **foreign grammar**.
 *
 * | Lane | Height | Content | Shape |
 * |---|---|---|---|
 * | `worn` | 12 dp | off the wrist | hatched blocks |
 * | `charger` | 8 dp | on charge | solid blocks |
 * | `timing` | 30 dp | timestamping class | hard staircase, three named levels |
 * | `clip` | 10 dp | sensor clipping | *rug plot* |
 * | `freeze` | 10 dp | write freezes | *rug plot* |
 * | `battery` | 12 dp | charge left | completion gauge |
 *
 * ### What the shape forbids reading
 *
 * Above, physiology: continuous, analogue, fine strokes. Here, none of that. No curve, no stroke
 * joining two instants, no graduated Y axis. These are blocks, hard steps, ticks of the same height
 * and a gauge — the *housekeeping* convention of scientific telemetry, isolated precisely so as not
 * to be read as signal.
 *
 * The X axis, on the other hand, is **the same**, and that is a necessity: without it a measurement
 * artefact is read as a physiological event — a flat signal taken for calm when the watch was off
 * the wrist. The band therefore re-ticks the hour axis beneath it, which is also what allows it to
 * exist on its own when the envelope has not been rebuilt.
 *
 * ### The battery gauge does not span the width of the plot, and that is deliberate
 *
 * A bar that spanned the whole area would have an abscissa facing every instant, and its edge would
 * read as "the battery fell at 03:12". The gauge is therefore **deliberately shorter** than the
 * plot, anchored on the left, and closed by a hard edge: none of its positions corresponds to an
 * hour. The question put is "did the watch last the night", not "what was the charge at 03:12", and
 * the exact figure is written next to it.
 *
 * ### Red and green
 *
 * Here they encode a **technical** state — the battery lasted, or it did not — which is their only
 * authorised use (`PendulumColors`). And they are never alone: the gauge that is met is a solid
 * fill, the gauge that is not met is hatched, and the figure is written in both cases.
 *
 * ### No gestures
 *
 * Like the hypnogram, this function handles no interaction: it receives `cursorMs`. The single
 * owner of the gesture is the night chart — two independent zoom handlers drift, and a band out by
 * a pixel makes a movement be attributed to the wrong minute.
 */
/**
 * The six words of the left margin, **already resolved**.
 *
 * They come down as a parameter rather than being read here: a `DrawScope` has neither composition
 * nor `Context`, and resolving them on every drawing frame would mean paying for a resource lookup
 * for six words that do not change. `MetrologyBand` reads them once, in composition.
 */
data class BandLabels(
    val worn: String,
    val noSensor: String,
    val charging: String,
    val clipping: String,
    val freezes: String,
    val battery: String,
)

fun DrawScope.drawMetrologyBand(
    spec: MetrologySpec,
    t: ChartTokens,
    x: XTransform,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    labels: BandLabels,
    cursorMs: Long? = null,
) {
    val gutter = dpPx(ChartTokens.GUTTER_DP)
    val m = defaultMargins
    val area = plotArea(m.copy(top = m.top + gutter))
    x.widthPx = area.width

    // --- the gutter: a hard rule, full width, opaque. It belongs to neither of the two halves and
    //     that is what makes it readable as a boundary rather than as a lane.
    drawLine(
        t.structural,
        Offset(0f, gutter / 2f),
        Offset(size.width, gutter / 2f),
        t.strokeBold,
    )

    fun xOf(ms: Long) = area.left + x.xOf(ms)
    fun clampX(v: Float) = v.coerceIn(area.left, area.right)

    // --- no telemetry: a muted band and a sentence, never empty lanes. An empty axis with its
    //     labels makes one look for a fault where there is a night recorded before telemetry
    //     existed.
    //
    //     `timestamping` is the second test and not a duplicate of the first: it is the only lane
    //     that has one tier per placed point, so it is empty exactly when no point could be anchored
    //     on the samples' time base. Without this test, a night whose telemetry arrived but none of
    //     whose chunks are in the database would draw six empty lanes.
    if (spec.points == 0 || spec.timestamping.isEmpty()) {
        drawRect(t.mutedBand, Offset(area.left, area.top), Size(area.width, area.height))
        axisText(
            measurer, scratch, t, spec.unavailableText,
            area.left + area.width / 2f, area.top + area.height / 2f - dpPx(6f),
            color = t.annotationText, centred = true,
        )
        return
    }

    val gap = dpPx(5f)
    val wornHeight = dpPx(12f)
    val chargingHeight = dpPx(8f)
    val rugHeight = dpPx(10f)
    val gaugeHeight = dpPx(12f)
    // The staircase takes what is left, with a floor: on a squashed canvas a cramped lane is better
    // than a lane of negative height, which would draw its steps upside down.
    val timestampingHeight = (area.height - wornHeight - chargingHeight - 2 * rugHeight - gaugeHeight - 5 * gap)
        .coerceAtLeast(dpPx(18f))

    var y = area.top
    val wornY = y; y += wornHeight + gap
    val chargingY = y; y += chargingHeight + gap
    val timestampingY = y; y += timestampingHeight + gap
    val clippingY = y; y += rugHeight + gap
    val freezesY = y; y += rugHeight + gap
    val gaugeY = y

    // --- lane 1: wearing. Three states, and "no sensor" is not "worn".
    laneLabel(measurer, scratch, t, labels.worn, wornY, wornHeight)
    when (spec.wearState) {
        WearState.NO_SENSOR -> {
            drawRect(t.mutedBand, Offset(area.left, wornY), Size(area.width, wornHeight))
            axisText(
                measurer, scratch, t, labels.noSensor,
                area.left + dpPx(4f), wornY + wornHeight / 2f - dpPx(5f),
                color = t.annotationText, textSize = (t.axisTextSize * 0.85f).sp,
            )
        }
        else -> for (iv in spec.offWrist) {
            val x0 = clampX(xOf(iv.startMs))
            val x1 = clampX(xOf(iv.endMs))
            if (x1 <= x0) continue
            // Hatching and not a solid fill: the off-body detector very probably reads "not worn"
            // permanently at the ankle, so this lane is an indication to cross-check against
            // temperature, not a verdict. Hatching says "flagged", a solid fill would have said
            // "established".
            hatch(Rect(x0, wornY, x1, wornY + wornHeight), t.attention, t.strokeThin, dpPx(4f))
        }
    }

    // --- lane 2: charging. Solid fill: this one is a binary fact read on the device, and it is what
    //     takes a point out of the battery slope regression.
    laneLabel(measurer, scratch, t, labels.charging, chargingY, chargingHeight)
    for (iv in spec.charging) {
        val x0 = clampX(xOf(iv.startMs))
        val x1 = clampX(xOf(iv.endMs))
        if (x1 <= x0) continue
        drawRect(t.secondSignal, Offset(x0, chargingY), Size(x1 - x0, chargingHeight))
    }

    // --- lane 3: timestamping, a hard staircase over three named levels
    drawTimestampStaircase(spec, t, area, timestampingY, timestampingHeight, measurer, scratch, ::xOf)

    // --- lanes 4 and 5: the two rug plots. Ticks of the same height: height would be a scale, and
    //     there is none here. The volume is read in the "why this number" panel.
    laneLabel(measurer, scratch, t, labels.clipping, clippingY, rugHeight)
    drawRug(spec.clipping, t.technicalFail, t.strokeBold, area, clippingY, rugHeight, ::xOf)

    laneLabel(measurer, scratch, t, labels.freezes, freezesY, rugHeight)
    drawRug(spec.freezes, t.attention, t.strokeNormal, area, freezesY, rugHeight, ::xOf)

    // --- lane 6: the gauge. Deliberately shorter than the plot, see the KDoc.
    laneLabel(measurer, scratch, t, labels.battery, gaugeY, gaugeHeight)
    spec.battery?.let { drawGauge(it, t, area, gaugeY, gaugeHeight, measurer, scratch) }

    // --- the hour axis, re-ticked under the band: it is what makes the alignment checkable.
    drawHourTicks(
        startMs = spec.startMs,
        endMs = spec.endMs,
        area = Rect(area.left, area.top, area.right, area.bottom),
        textY = size.height - m.bottom + dpPx(4f),
        t = t,
        measurer = measurer,
        scratch = scratch,
        xOf = ::xOf,
    )

    // --- the cursor, shared with the two bands above: it is the only thing that crosses the
    //     gutter, and that is precisely its role — reading the same instant in all three bands.
    cursorMs?.let {
        val cx = xOf(it)
        if (cx in area.left..area.right) {
            drawLine(t.annotationText, Offset(cx, area.top), Offset(cx, area.bottom), t.strokeThin)
        }
    }
}

/** The lane name, in the left margin, vertically centred. A nominal scale. */
private fun DrawScope.laneLabel(
    measurer: TextMeasurer,
    scratch: ChartScratch,
    t: ChartTokens,
    label: String,
    top: Float,
    height: Float,
) = axisText(
    measurer, scratch, t, label, dpPx(2f), top + height / 2f - dpPx(5f),
    textSize = (t.axisTextSize * 0.85f).sp,
)

/**
 * The timestamping staircase: horizontal tread, vertical step, no interpolation.
 *
 * There is **no** continuous `Path` as in the hypnogram: each tier is an independent segment, and
 * the level change is a vertical stroke laid between two contiguous tiers. The difference shows to
 * the eye on a gap — the hypnogram joins its stages across it, this one joins nothing, because a
 * minute without a telemetry point is a minute for which there is no class to announce.
 */
private fun DrawScope.drawTimestampStaircase(
    spec: MetrologySpec,
    t: ChartTokens,
    area: Rect,
    top: Float,
    height: Float,
    measurer: TextMeasurer,
    scratch: ChartScratch,
    xOf: (Long) -> Float,
) {
    val levels = TimestampLevel.entries
    val levelHeight = height / levels.size
    fun yOf(n: TimestampLevel) = top + n.ordinal * levelHeight + levelHeight / 2f

    // The three names, on the left. That is the scale: it is nominal, it is not graduated, and no
    // intermediate value exists between two of its classes.
    for (n in levels) {
        axisText(
            measurer, scratch, t, n.label, dpPx(2f), yOf(n) - dpPx(5f),
            textSize = (t.axisTextSize * 0.85f).sp,
        )
    }

    var previous: Pair<Float, Float>? = null
    for (p in spec.timestamping) {
        val x0 = xOf(p.startMs).coerceIn(area.left, area.right)
        val x1 = xOf(p.endMs).coerceIn(area.left, area.right)
        val y = yOf(p.level)
        if (x1 > x0) drawLine(t.structural, Offset(x0, y), Offset(x1, y), t.strokeBold)
        // The vertical step is only laid down if the two tiers touch: jumping a gap would draw a
        // transition that was not observed.
        previous?.let { (endX, previousY) ->
            if (x0 - endX < 1f && y != previousY) {
                drawLine(t.structural, Offset(x0, previousY), Offset(x0, y), t.strokeBold)
            }
        }
        previous = x1 to y
    }
}

/**
 * A *rug plot*: ticks of the same height, laid on a base line.
 *
 * The height encodes nothing — neither the number of clipped samples, nor the duration of the
 * freeze. It would be a scale, and this band carries none: the volume is read as figures in the
 * "why this number" panel, where it is written and not eyeballed.
 *
 * The base line is very discreet and **deliberately exempted** from the 3:1 requirement, for the
 * same reason as the axis ticks: it situates the ticks, it does not inform. It is the ticks that
 * carry.
 */
private fun DrawScope.drawRug(
    instants: List<Long>,
    color: androidx.compose.ui.graphics.Color,
    strokeWidth: Float,
    area: Rect,
    top: Float,
    height: Float,
    xOf: (Long) -> Float,
) {
    val base = top + height
    drawLine(
        color, Offset(area.left, base), Offset(area.right, base), strokeWidth,
        alpha = ChartTokens.TICK_ALPHA,
    )
    for (ms in instants) {
        val px = xOf(ms)
        if (px < area.left || px > area.right) continue
        drawLine(color, Offset(px, base), Offset(px, top + height * 0.15f), strokeWidth)
    }
}

/**
 * The completion gauge. Hollow track, fill on the left, hard edge, figure alongside.
 *
 * Its width is **fixed and shorter than the plot**: that is what prevents reading an abscissa
 * against its edge, hence taking it for a discharge curve. On a narrow screen it is brought back to
 * half the area, which keeps it visibly shorter.
 */
private fun DrawScope.drawGauge(
    gauge: BatteryGauge,
    t: ChartTokens,
    area: Rect,
    top: Float,
    height: Float,
    measurer: TextMeasurer,
    scratch: ChartScratch,
) {
    val width = dpPx(96f).coerceAtMost(area.width / 2f)
    val track = Rect(area.left, top, area.left + width, top + height)
    drawRect(
        t.structural,
        Offset(track.left, track.top),
        Size(track.width, track.height),
        style = Stroke(t.strokeNormal),
    )
    val filled = width * gauge.fraction.coerceIn(0f, 1f)
    if (filled > 0f) {
        val r = Rect(track.left, track.top, track.left + filled, track.bottom)
        if (gauge.thresholdMet) {
            // Solid fill: met.
            drawRect(t.technicalOk, Offset(r.left, r.top), Size(r.width, r.height))
        } else {
            // Hatching: not met. The shape carries the state, the colour only confirms it (P6).
            hatch(r, t.technicalFail, t.strokeNormal, dpPx(3f))
            drawRect(t.technicalFail, Offset(r.left, r.top), Size(r.width, r.height), style = Stroke(t.strokeThin))
        }
        // The hard edge: it closes the gauge and says that it stops there, instead of fading out as
        // the end of a curve would.
        drawLine(t.structural, Offset(r.right, track.top), Offset(r.right, track.bottom), t.strokeBold)
    }
    axisText(
        measurer, scratch, t, gauge.label,
        track.right + dpPx(6f), track.top + height / 2f - dpPx(5f),
        color = t.annotationText, textSize = (t.axisTextSize * 0.85f).sp,
    )
}
