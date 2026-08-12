package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import com.pendulum.phone.ui.theme.ChartTokens
import com.pendulum.phone.ui.theme.PendulumColors
import com.pendulum.phone.ui.theme.RenderTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The contrast of the chart elements, computed rather than assumed.
 *
 * ### The rule applied
 *
 * WCAG 2.1 criterion 1.4.11 (*Non-text Contrast*) requires **3:1** against the adjacent background
 * for "graphical parts required to understand the content". The lines of a chart are explicitly
 * among them. The document says 4.4:1 for the four opaque hues (`06-interface.md` §5.4) — which is
 * true — but says nothing about the **alphas** with which three of those hues are actually drawn.
 * That is where the criterion gets lost, and an eye does not catch it up: a band at 14 % opacity
 * looks "discreet", not "below the threshold".
 *
 * ### What is required, and what is not
 *
 * An element is held to 3:1 when it is **the sole carrier** of a piece of information: the
 * detector's threshold line, the series bar, the interval bounds, the night points, the median
 * line, the reference line.
 *
 * The **fill** of the interval band is exempt from it, and it is the only exemption in this file:
 * the information "here is the interval" is carried by its two bounds, drawn dashed, which are
 * themselves held to 3:1 below. Bringing the fill up to 3:1 would require an opacity of about 0.50
 * (measured by [alphaForThreeToOne]), which would drown the night points drawn on top of it — that
 * is, it would trade a compliant element for an unreadable one. The axis ticks are exempt for the
 * same reason as they are in the standard: they are decorative, the value being carried by the axis
 * text.
 */
class ChartContrastTest {

    /** WCAG 1.4.11 threshold for a graphical object. */
    private val minimum = Colorimetry.GRAPHIC_MINIMUM

    // The colorimetry itself lives in `Colorimetry`: `TextContrastTest` needs the very same
    // computation, and it is that sharing which closes the gap the onboarding disclaimer went
    // through — the charts were measured, the text was not.
    private fun ratio(a: Color, b: Color) = Colorimetry.ratio(a, b)
    private fun compose(over: Color, under: Color, alpha: Float) =
        Colorimetry.compose(over, under, alpha)
    private fun alphaForThreeToOne(over: Color, background: Color) =
        Colorimetry.alphaForThreeToOne(over, background)

    // -------------------------------------------------------------------------------------
    // Measurement
    // -------------------------------------------------------------------------------------

    private data class Measurement(val name: String, val ratio: Double, val required: Boolean)

    private fun measure(colors: PendulumColors, target: RenderTarget): List<Measurement> {
        val t = ChartTokens.of(colors, target)
        val background = t.plotBackground
        fun row(name: String, c: Color, alpha: Float = 1f, required: Boolean = true) =
            Measurement(name, ratio(compose(c, background, alpha), background), required)

        return listOf(
            // --- opaque hues
            row("night point / median / envelope", t.primaryData),
            row("accel mask ring", t.attention),
            row("REM / second rule", t.secondSignal),
            row("noise floor / excluded night", t.structural),
            row("axis text", t.axisText),
            // --- metrology band: the only two hues of the product that encode a state, and they
            //     encode only a **technical** state. They are opaque everywhere they are used —
            //     full gauge, hatching, rug plot ticks — hence no alpha to measure, but they are
            //     held to the same 3:1 as the rest: the gauge is the only place in the application
            //     where a user reads "the watch held out through the night".
            row("battery gauge, threshold met (technicalOk)", t.technicalOk),
            row("battery gauge, threshold not met (technicalFail)", t.technicalFail),
            // --- composed hues: this is where the criterion is decided
            row("onset threshold (thresholdAlpha)", t.primaryData, t.thresholdAlpha),
            row("series bar (seriesBarAlpha)", t.primaryData, t.seriesBarAlpha),
            row("interval bounds", t.primaryData, ChartTokens.CI_BOUNDS_ALPHA),
            row("accel mask lane (hypnogram)", t.structural, t.maskLaneAlpha),
            // --- exempt, measured all the same: an exemption with no figure is an excuse
            row("CI band fill (ciBandAlpha)", t.primaryData, t.ciBandAlpha, required = false),
            row("Y tick", t.structural, ChartTokens.TICK_ALPHA, required = false),
            // The baselines of the two rug plots, exempt for the same reason as the ticks: they
            // locate the ticks, they do not inform. It is the ticks — opaque, measured above —
            // that carry.
            row("rug plot baseline", t.technicalFail, ChartTokens.TICK_ALPHA, required = false),
        )
    }

    /**
     * The red-green pair of the metrology band never encodes a health result, and it never encodes
     * **alone**.
     *
     * This test verifies the half that the colorimetry does not: that the two hues really are those
     * of [PendulumColors.success] and [PendulumColors.error], that is, the two roles the palette
     * reserves for technical states, and that they are not confused with any of the four data hues.
     * The day someone wires `technicalFail` onto `attention` to "soften" the band, or `primaryData`
     * onto `success`, this test will fall — and the semantic rule of the palette has no other
     * executable guardian.
     *
     * That the colour is not the sole carrier is held by the shape (solid fill against hatching)
     * and by the figure written next to it: that is checked by eye on a greyscale screenshot, not
     * by a luminance computation.
     */
    @Test
    fun `the technical state hues are the palette's, and distinct from the data hues`() {
        listOf(
            PendulumColors.Dark to RenderTarget.Screen,
            PendulumColors.Light to RenderTarget.Print,
        ).forEach { (colors, target) ->
            val t = ChartTokens.of(colors, target)
            assertThat(t.technicalOk).isEqualTo(colors.success)
            assertThat(t.technicalFail).isEqualTo(colors.error)
            assertThat(listOf(t.primaryData, t.attention, t.secondSignal, t.structural))
                .describedAs("no data hue may equal a technical state hue")
                .doesNotContain(t.technicalOk, t.technicalFail)
        }
    }

    private fun report(title: String, measurements: List<Measurement>): String = buildString {
        append("\n$title\n")
        measurements.forEach {
            append(
                "  %-42s %5.2f:1  %s%n".format(
                    it.name,
                    it.ratio,
                    if (!it.required) "(exempt)" else if (it.ratio >= minimum) "ok" else "FAIL",
                ),
            )
        }
    }

    @Test
    fun `dark screen - every element that carries information holds 3 to 1`() {
        val measurements = measure(PendulumColors.Dark, RenderTarget.Screen)
        print(report("contrast, dark screen (background ${PendulumColors.Dark.background})", measurements))
        print(
            "  opacity a CI fill would need to reach 3:1: " +
                "${alphaForThreeToOne(PendulumColors.Dark.accent, PendulumColors.Dark.background)}\n",
        )

        val failures = measurements.filter { it.required && it.ratio < minimum }
        assertThat(failures)
            .withFailMessage("Elements below 3:1 (WCAG 1.4.11): %s", failures)
            .isEmpty()
    }

    @Test
    fun `light export - same rule, same threshold`() {
        // The export starts from the light palette: a dark PDF is unreadable printed. The tokens
        // change, the constraint does not — and that is exactly the kind of discrepancy that only
        // shows up once the sheet is in a doctor's hand.
        val measurements = measure(PendulumColors.Light, RenderTarget.Print)
        print(report("contrast, light export (background ${PendulumColors.Light.background})", measurements))

        val failures = measurements.filter { it.required && it.ratio < minimum }
        assertThat(failures)
            .withFailMessage("Elements below 3:1 (WCAG 1.4.11): %s", failures)
            .isEmpty()
    }

    @Test
    // `point` spelled out: a Kotlin backticked name cannot contain `.`, and a decimal comma
    // reads as a thousands separator in English. The figure itself is in the KDoc above.
    fun `the four opaque hues also hold the 4 point 4 to 1 announced by the documentation`() {
        listOf(
            PendulumColors.Dark to RenderTarget.Screen,
            PendulumColors.Light to RenderTarget.Print,
        ).forEach { (colors, target) ->
            val t = ChartTokens.of(colors, target)
            listOf(t.primaryData, t.attention, t.secondSignal, t.structural).forEach { c ->
                assertThat(ratio(c, t.plotBackground))
                    .describedAs("$c on ${t.plotBackground}")
                    .isGreaterThanOrEqualTo(4.4)
            }
        }
    }
}
