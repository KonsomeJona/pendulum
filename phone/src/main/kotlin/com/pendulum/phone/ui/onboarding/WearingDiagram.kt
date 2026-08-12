package com.pendulum.phone.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pendulum.phone.R
import com.pendulum.phone.ui.theme.LocalPendulumColors

/**
 * Where to wear the watch, as a drawing.
 *
 * ### Why a drawing and not three more sentences
 *
 * The conditions to be kept identical from one night to the next are **spatial**: in front of the
 * tibia, above the bone, not on the bone. A sentence describes a location, a drawing shows it —
 * and this is the one of the four instructions that a user applies wrongly without ever knowing
 * it, because nothing on the screen will tell them that their watch is ten centimetres too low.
 *
 * ### Why a `DrawScope` and not an image
 *
 * This is the rule of the `ui/chart` package (`UX.md` §8.3), and it holds here for the same
 * reason: the theme of this application is dark on screen and **light in print**
 * (`RenderTarget.Print`). A frozen image would carry its own colours and would come out black on
 * black in one case or the other; a drawing takes the theme's colours at the moment it is painted.
 *
 * The same drawing exists as SVG in `docs/images/where-to-wear.svg` for the documentation. The two
 * resemble each other deliberately, but this one is the reference: it is the one the user sees.
 *
 * ### What the drawing says, and what it does not say
 *
 * It carries **no text**. The labels live in `strings.xml`, so they get translated, which a drawn
 * text would not. The composable does carry a description for screen readers: a diagram without a
 * text alternative says nothing to whoever does not see it, and that would be the second time this
 * product forgot that question.
 */
@Composable
fun WearingDiagram(modifier: Modifier = Modifier) {
    val c = LocalPendulumColors.current
    val description = stringResource(R.string.wearing_diagram_description)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(RATIO)
            .semantics { contentDescription = description },
    ) {
        drawWearingDiagram(
            limb = c.textSecondary,
            bone = c.outline,
            watch = c.textPrimary,
            refusal = c.error,
            rotation = c.accent,
        )
    }
}

/** Width over height. The leg occupies the top, the three orientations the bottom. */
private const val RATIO = 600f / 540f

/**
 * The drawing, in the coordinates of a 600 x 540 reference canvas, scaled to the real size.
 * Working in fixed coordinates then scaling avoids having to reason in fractions at every stroke,
 * and keeps the drawing identical whatever the available width.
 */
fun DrawScope.drawWearingDiagram(
    limb: Color,
    bone: Color,
    watch: Color,
    refusal: Color,
    rotation: Color,
) {
    val k = size.width / 600f
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val limbStroke = Stroke(width = 2.2f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val watchStroke = Stroke(width = 2.4f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val thinStroke = Stroke(width = 1.6f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val dashes = Stroke(
        width = 1.6f * k,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * k, 4f * k)),
    )

    // --- The leg, seen from the front --------------------------------------------------------
    val leg = Path().apply {
        moveTo(232f * k, 40f * k); quadraticBezierTo(224f * k, 140f * k, 228f * k, 206f * k)
        // left malleolus
        quadraticBezierTo(220f * k, 226f * k, 224f * k, 242f * k)
        quadraticBezierTo(230f * k, 252f * k, 240f * k, 248f * k)
        // instep and toes
        quadraticBezierTo(236f * k, 272f * k, 234f * k, 290f * k)
        quadraticBezierTo(232f * k, 310f * k, 252f * k, 314f * k)
        lineTo(292f * k, 314f * k)
        quadraticBezierTo(312f * k, 310f * k, 310f * k, 290f * k)
        quadraticBezierTo(308f * k, 272f * k, 304f * k, 248f * k)
        // right malleolus, going back up
        quadraticBezierTo(314f * k, 252f * k, 320f * k, 242f * k)
        quadraticBezierTo(324f * k, 226f * k, 316f * k, 206f * k)
        quadraticBezierTo(320f * k, 140f * k, 312f * k, 40f * k)
    }
    drawPath(leg, limb, style = limbStroke)
    // the line of the toes
    val toes = Path().apply {
        moveTo(236f * k, 294f * k); quadraticBezierTo(272f * k, 302f * k, 308f * k, 294f * k)
    }
    drawPath(toes, limb, style = limbStroke)

    // The tibia, dotted: it is the bone one follows, and it gives the "front".
    drawLine(bone, p(272f, 48f), p(272f, 236f), strokeWidth = 1.5f * k, pathEffect = dashes.pathEffect)

    // --- The watch in its place: in front, above the bumps ---------------------------------
    watchCase(p(245f, 146f), 54f * k, 36f * k, watch, watchStroke, k)
    drawCircle(watch, radius = 5f * k, center = p(272f, 164f), style = thinStroke)
    val strapEnds = Path().apply {
        moveTo(245f * k, 157f * k); quadraticBezierTo(230f * k, 164f * k, 245f * k, 171f * k)
        moveTo(299f * k, 157f * k); quadraticBezierTo(314f * k, 164f * k, 299f * k, 171f * k)
    }
    drawPath(strapEnds, watch, style = watchStroke)

    // --- What must not be done: the watch resting on the bumps -----------------------------
    for (x in listOf(200f, 298f)) {
        watchCase(p(x, 210f), 46f * k, 30f * k, refusal, dashes, k)
        val cross = Path().apply {
            moveTo((x + 9f) * k, 218f * k); lineTo((x + 37f) * k, 232f * k)
            moveTo((x + 37f) * k, 218f * k); lineTo((x + 9f) * k, 232f * k)
        }
        drawPath(cross, refusal, style = watchStroke)
    }

    // --- The same watch, rotated on itself: the reference point moves, the place does not ---
    val buttonPositions = listOf(
        Triple(120f, 183f, 460f),  // button on the right
        Triple(270f, 300f, 436f),  // button at the top
        Triple(420f, 417f, 460f),  // button on the left
    )
    buttonPositions.forEachIndexed { i, (xCase, xButton, yButton) ->
        val alpha = if (i == 0) 1f else 0.55f
        watchCase(p(xCase, 440f), 60f * k, 40f * k, watch.copy(alpha = alpha), watchStroke, k)
        drawCircle(watch.copy(alpha = alpha), radius = 4.5f * k, center = p(xButton, yButton), style = thinStroke)
    }

    // The rotation arrow, its head included: `DrawScope` has no end marker.
    val arc = Path().apply {
        moveTo(196f * k, 418f * k)
        cubicTo(250f * k, 372f * k, 350f * k, 372f * k, 404f * k, 418f * k)
    }
    drawPath(arc, rotation, style = thinStroke)
    val arrowHead = Path().apply {
        moveTo(404f * k, 418f * k); lineTo(392f * k, 404f * k)
        moveTo(404f * k, 418f * k); lineTo(390f * k, 414f * k)
    }
    drawPath(arrowHead, rotation, style = thinStroke)
}

/** A watch case: a rectangle with rounded corners, in outline. */
private fun DrawScope.watchCase(
    corner: Offset,
    width: Float,
    height: Float,
    color: Color,
    stroke: Stroke,
    k: Float,
) {
    drawRoundRect(
        color = color,
        topLeft = corner,
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * k, 8f * k),
        style = stroke,
    )
}
