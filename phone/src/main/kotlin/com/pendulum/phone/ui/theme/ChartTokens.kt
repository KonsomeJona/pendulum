package com.pendulum.phone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Where the drawing goes: a screen (dark by default) or a PDF page (light, in points). */
enum class RenderTarget { Screen, Print }

/**
 * Everything the three drawing functions need, and nothing else.
 *
 * ### Why these are parameters and not constants
 *
 * The same `DrawScope.drawXxx()` functions draw the screen and the PDF page (`UX.md` §8.3). If a
 * colour or a stroke width were hard-coded inside the drawing function, the export would diverge
 * from the screen the day one of the two changed — and nobody would see it before a doctor had the
 * paper in hand. By passing a set of tokens, there is **only one** rendering code and the theme is
 * data.
 *
 * ### The four hues, and not five
 *
 * Blue (data), amber (quality/attention), violet (second signal, REM), grey (structure). No
 * red-green pair: every distinction stays legible under deuteranopia and protanopia, and in any
 * case none is carried by colour alone (P6).
 *
 * The stroke widths are in abstract drawing units: the `DrawScope` of a Compose Canvas receives
 * them already converted into pixels by the caller, that of a PDF page into points. The minimum
 * rule is 1.5 dp on screen / 0.6 pt on export — below that, a line disappears in print.
 */
@Immutable
data class ChartTokens(
    val target: RenderTarget,

    // --- backgrounds
    val plotBackground: Color,
    /** Bands outside sleep, and the "hypnogram unavailable" lane. */
    val mutedBand: Color,

    // --- data roles
    /** Envelope, night points, median line. */
    val primaryData: Color,
    /** Flags, disagreement between masks, the "accelerometer mask" ring. */
    val attention: Color,
    /** REM, second rule drawn on top. */
    val secondSignal: Color,
    /** Noise floor, axes, excluded nights. */
    val structural: Color,

    /**
     * A **technical** state that was met, and nothing else. The battery gauge of the metrology band
     * when the watch lasted the night.
     *
     * ### The only authorised use of the red-green pair in a chart
     *
     * The project's rule is that [PendulumColors.error] and [PendulumColors.success] **never**
     * describe a health result: a short rhythm is not red, a high hourly count is not red. That
     * rule does not say they are useless — it says what they are for. Transfer, permission,
     * pairing, file integrity, storage: device states. "The battery lasted the night" is one of
     * them, on the same footing as "the file is complete", and that is precisely why the metrology
     * band is kept apart from the rest of the drawing.
     *
     * The four data hues stay free of any red-green pair, and the distinction here is in any case
     * **not carried by colour alone** (P6): the gauge that was met is a solid fill, the gauge that
     * was not is hatched, and the figure is written beside it in both cases. In greyscale, nothing
     * is lost.
     */
    val technicalOk: Color,

    /** A **technical** state not met. See [technicalOk] for what this pair is allowed to encode. */
    val technicalFail: Color,

    // --- text
    val axisText: Color,
    val annotationText: Color,

    /**
     * Size of the axis text, **in sp and not in pixels**, unlike every other scalar of this class.
     *
     * The distinction is not cosmetic: the strokes and the dash patterns are consumed by
     * `DrawScope` primitives, which work in pixels, hence their multiplication by the density here.
     * The text, on the other hand, leaves in a `TextStyle` as `axisTextSize.sp`, and it is the
     * typography system that applies the density. Multiplying it here as well applied it **twice**:
     * on a screen at density 2.75, a requested 11 sp became 83 px, and every tick overlapped to the
     * point of making the three charts illegible. A defect invisible in preview, found on a real
     * screenshot.
     */
    val axisTextSize: Float,

    // --- strokes
    val strokeThin: Float,
    val strokeNormal: Float,
    val strokeBold: Float,
    val dashThreshold: FloatArray,
    val dashNoiseFloor: FloatArray,
    val dashReference: FloatArray,

    // --- translucent fills
    //
    // Three of these four values are under an accessibility constraint and not a matter of taste:
    // WCAG 1.4.11 requires 3:1 for graphical objects, and a translucent fill composited over the
    // chart background loses that contrast very quickly without the eye noticing. The values below
    // are measured by `ChartContrastTest`, on both targets — the light export is the binding case,
    // because a dark blue at 55 % over an almost white background falls to 2.3:1.
    /** Fill of the interval band. **Exempt**: it is its bounds that inform. */
    val ciBandAlpha: Float,
    val seriesBarAlpha: Float,
    val thresholdAlpha: Float,
    /** The hypnogram's "accelerometer mask" lane: it carries the moving/still state. */
    val maskLaneAlpha: Float,
) {
    companion object {

        /**
         * Opacity of the two dashed bounds of the interval band.
         *
         * They are the **real carrier** of the information "here is the interval": it is therefore
         * they, and not the fill, that must hold the 3:1 of WCAG 1.4.11. Measured in
         * `ChartContrastTest` — 3.7:1 on the dark background at this value, against 1.3:1 for the
         * fill. A named constant rather than a literal at the call site, because this is a value
         * under an accessibility constraint and not an aesthetic setting.
         */
        const val CI_BOUNDS_ALPHA = 0.75f

        /**
         * Opacity of the horizontal ticks. Deliberately low and **deliberately exempt** from the
         * 3:1: the value is carried by the axis text, the line is only a guide for the eye. A tick
         * at the same contrast as the data would make a grid that competes with the curve.
         */
        const val TICK_ALPHA = 0.22f

        /**
         * Height of the gutter that separates physiology from metrology, in dp.
         *
         * **Abnormally wide, and that is the whole point.** The lanes of the hypnogram are 4 dp
         * apart; this one is five times as much and carries a hard full-width rule down its middle.
         * A separation of the same thickness as the others would read as one more lane of the same
         * chart, and the eye would then look for a correlation between the battery and the
         * movements — between which there is none.
         */
        const val GUTTER_DP = 20f

        /**
         * `density` converts dp into pixels on screen; on export, the caller passes the page's
         * points-per-dp factor. One scale, one path.
         */
        fun of(colors: PendulumColors, target: RenderTarget, density: Float = 1f): ChartTokens {
            val screen = target == RenderTarget.Screen
            // 1.5 dp on screen, 0.6 pt on export: the minima of UX.md §4.
            val thin = (if (screen) 1.0f else 0.4f) * density
            val normal = (if (screen) 1.5f else 0.6f) * density
            val bold = (if (screen) 2.0f else 0.9f) * density
            return ChartTokens(
                target = target,
                plotBackground = colors.background,
                mutedBand = colors.surfaceMuted,
                primaryData = colors.accent,
                attention = colors.attention,
                secondSignal = colors.secondSignal,
                structural = colors.textTertiary,
                technicalOk = colors.success,
                technicalFail = colors.error,
                axisText = colors.textTertiary,
                annotationText = colors.textSecondary,
                // No `* density`: the value is in sp, see the field's KDoc.
                axisTextSize = if (screen) 11f else 8f,
                strokeThin = thin,
                strokeNormal = normal,
                strokeBold = bold,
                dashThreshold = floatArrayOf(6f * density, 4f * density),
                dashNoiseFloor = floatArrayOf(2f * density, 3f * density),
                dashReference = floatArrayOf(6f * density, 4f * density),
                ciBandAlpha = 0.14f,
                // 0.35 and 0.55 measured 2.05:1 and 3.37:1 on the dark screen, 1.67:1 and 2.34:1 on
                // the light export — that is, three of four values below the 3:1 threshold. 0.75 is
                // the measured floor that holds on both targets (5.3:1 in dark, 3.4:1 in light).
                // The distinction between the onset threshold and the envelope stays carried by the
                // dash, not by the opacity — which is the P6 rule anyway.
                seriesBarAlpha = 0.75f,
                thresholdAlpha = 0.75f,
                maskLaneAlpha = 0.85f,
            )
        }
    }

    // `FloatArray` in a data class: the generated equals/hashCode compare references. These tokens
    // are never used as a key, but we redefine them so as not to leave a trap sleeping.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartTokens) return false
        return target == other.target &&
            plotBackground == other.plotBackground &&
            primaryData == other.primaryData &&
            strokeNormal == other.strokeNormal &&
            axisTextSize == other.axisTextSize
    }

    override fun hashCode(): Int =
        target.hashCode() * 31 + plotBackground.hashCode() * 31 + strokeNormal.hashCode()
}

val LocalChartTokens = staticCompositionLocalOf { ChartTokens.of(PendulumColors.Dark, RenderTarget.Screen) }
