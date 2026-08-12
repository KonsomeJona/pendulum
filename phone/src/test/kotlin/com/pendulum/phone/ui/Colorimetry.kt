package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG colorimetry, written once.
 *
 * It was private to `ChartContrastTest`, which measured the contrast of the **charts** and of them
 * alone. The contrast of the **text** was measured nowhere, and it is through that gap that the
 * onboarding disclaimer screen went: text from the dark palette on the light background the system
 * gives an application with no `android:theme`, that is 1.06:1.
 *
 * Thirty lines, no dependency, and above all no "by eye" approximation — which is exactly the
 * point: a very light grey background under very light grey text is described as "discreet" for as
 * long as nobody computes.
 */
internal object Colorimetry {

    /** WCAG 2.1, criterion 1.4.3 (*Contrast (Minimum)*): body text. */
    const val TEXT_MINIMUM = 4.5

    /** WCAG 2.1, criterion 1.4.11 (*Non-text Contrast*): graphical object. */
    const val GRAPHIC_MINIMUM = 3.0

    private fun linearChannel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(c: Color): Double =
        0.2126 * linearChannel(c.red) +
            0.7152 * linearChannel(c.green) +
            0.0722 * linearChannel(c.blue)

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * `SrcOver` composition in sRGB space, exactly what `drawRect(..., alpha = a)` does: the blend
     * happens on the encoded values, not on the linearised ones.
     */
    fun compose(over: Color, under: Color, alpha: Float): Color = Color(
        red = over.red * alpha + under.red * (1f - alpha),
        green = over.green * alpha + under.green * (1f - alpha),
        blue = over.blue * alpha + under.blue * (1f - alpha),
    )

    /** The lowest opacity that would bring [over] on [background] up to [GRAPHIC_MINIMUM]. */
    fun alphaForThreeToOne(over: Color, background: Color): Float {
        var a = 0.01f
        while (a < 1f) {
            if (ratio(compose(over, background, a), background) >= GRAPHIC_MINIMUM) return a
            a += 0.01f
        }
        return 1f
    }
}
