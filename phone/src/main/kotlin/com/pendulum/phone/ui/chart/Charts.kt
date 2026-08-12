package com.pendulum.phone.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pendulum.phone.R
import com.pendulum.phone.ui.theme.LocalChartTokens
import com.pendulum.phone.ui.theme.PendulumType
import kotlin.math.roundToInt

/**
 * The composable wrappers of the three charts.
 *
 * They are **thin by construction**: they resolve the tokens, handle the gestures and the
 * accessibility, then delegate to the drawing function. No computation logic, no scale decision.
 * That is what lets the PDF export call the same functions without pulling in Compose UI.
 *
 * Each chart exposes a **Values** button that opens the same content as a table. This is at once
 * the accessible alternative — the only path really usable without sight, since a
 * `contentDescription` on a canvas does not render data — and the "I want the exact figure" mode,
 * which is a legitimate and frequent request here.
 */

@Composable
fun NightChart(
    spec: NightChartSpec,
    transform: XTransform,
    cursorMs: Long?,
    onCursor: (Long?) -> Unit,
    onEvent: (Long) -> Unit,
    onValues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val measurer = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }

    // Resolved here, in composition, like the lane names: the drawing has no `Context`. It is
    // formatted even when the peak will not be annotated — that is one resource lookup against a
    // threshold condition duplicated between the drawing and its caller.
    val peakLabel = spec.peak?.let {
        stringResource(R.string.chart_night_peak, it.ratio.roundToInt(), it.time)
    }

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = spec.accessibleDescription }
                .pointerInput(Unit) {
                    // Horizontal zoom only: the Y axis never zooms. A zoomable Y would allow
                    // reframing on a detail and losing the scale of the threshold, which is
                    // precisely what this chart exists to check.
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        transform.applyPinch(centroid.x, pan.x, zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { transform.reset() },
                        onTap = { onEvent(transform.timeAt(it.x)) },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onCursor(transform.timeAt(it.x)) },
                        onDrag = { c, _ -> onCursor(transform.timeAt(c.position.x)) },
                        onDragEnd = { onCursor(null) },
                    )
                },
        ) {
            drawNightChart(spec, tokens, transform, measurer, scratch, peakLabel, cursorMs)
        }
        TextButton(onClick = onValues, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.chart_values), style = PendulumType.label)
        }
    }
}

@Composable
fun Hypnogram(
    spec: HypnogramSpec,
    transform: XTransform,
    cursorMs: Long?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val measurer = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }

    // Same rule as for the metrology band: the six words of the margin are resolved in composition
    // and passed to the drawing, which has neither composition nor `Context`.
    val labels = HypnogramLabels(
        still = stringResource(R.string.chart_lane_immobile),
        wake = stringResource(R.string.chart_stage_awake),
        rem = stringResource(R.string.chart_stage_rem),
        n1 = stringResource(R.string.chart_stage_n1),
        n2 = stringResource(R.string.chart_stage_n2),
        n3 = stringResource(R.string.chart_stage_n3),
    )

    // No `pointerInput` here: a single owner of the gesture, and that is the night chart.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = spec.statistics },
    ) {
        drawHypnogram(spec, tokens, transform, measurer, scratch, labels, cursorMs)
    }
}

/**
 * The device state band, under the hypnogram, **same axis and same cursor**.
 *
 * No `pointerInput` here either: the single owner of the gesture remains the night chart. Its
 * height is greater than the hypnogram's because it carries six lanes and the gutter — and that
 * gutter is part of the drawing, not of the layout: it must stay attached to the band whatever
 * spacing the parent component applies.
 */
@Composable
fun MetrologyBand(
    spec: MetrologySpec,
    transform: XTransform,
    cursorMs: Long?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val measurer = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }

    // The lane names are resolved **here**, in composition, and passed to the drawing: an extension
    // of `DrawScope` has neither composition nor `Context`, and passing a `Context` down into a
    // `Canvas` to read five words there would mean paying for the lookup on every frame.
    val labels = BandLabels(
        worn = stringResource(R.string.chart_lane_worn),
        noSensor = stringResource(R.string.chart_no_offbody_sensor),
        charging = stringResource(R.string.chart_lane_charger),
        clipping = stringResource(R.string.chart_lane_clip),
        freezes = stringResource(R.string.chart_lane_freeze),
        battery = stringResource(R.string.chart_lane_battery),
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = spec.accessibleDescription },
    ) {
        drawMetrologyBand(spec, tokens, transform, measurer, scratch, labels, cursorMs)
    }
}

@Composable
fun TrendChart(
    spec: TrendChartSpec,
    onNight: (String) -> Unit,
    onValues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalChartTokens.current
    val measurer = rememberTextMeasurer()
    val scratch = remember { ChartScratch() }
    val localDensity = LocalDensity.current

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = spec.accessibleDescription }
                .pointerInput(spec) {
                    // No zoom: the period is chosen with the selector, not with a pinch. A free
                    // pinch would allow framing only on the nights that suit.
                    detectTapGestures { p ->
                        findNearestPoint(
                            spec = spec,
                            x = p.x,
                            y = p.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            // The density, because the margins of the plot are in dp: without it,
                            // the search would target the full width of the canvas and not the area
                            // where the points are really drawn.
                            density = localDensity.density,
                            acceptanceRadius = with(localDensity) { 24.dp.toPx() },
                        )?.let(onNight)
                    }
                },
        ) {
            drawTrendChart(spec, tokens, measurer, scratch)
        }
        spec.reference?.let {
            // The provenance of the threshold is in the caption, outside the plot area: inside the
            // chart, it would become one more annotation to decode.
            Text(it.caption, style = PendulumType.caption, modifier = Modifier.padding(start = 4.dp))
        }
        TextButton(onClick = onValues, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.chart_values), style = PendulumType.label)
        }
    }
}
