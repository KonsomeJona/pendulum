package com.pendulum.phone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Nine styles, not ten.
 *
 * ### Tabular figures are not an affectation
 *
 * `fontFeatureSettings = "tnum"` is mandatory everywhere a value can change: a sample counter
 * ticking up, a duration running, interval bounds. Without a fixed advance width, the digits do not
 * have the same width and the counter **jitters** at every refresh. On a screen whose job is to
 * make numbers legible, that is disqualifying.
 *
 * ### [metricXL] has exactly one use site in the whole application
 *
 * This is principle P7 made verifiable: the figure of a single night is never a headline. A large
 * number gets read, a caveat written beside it does not — so only what is allowed to be read on its
 * own is set large, that is, the aggregate over the period, never one night. The single site is
 * `MetricHeadline`, and a grep on `PendulumType.metricXL` must confirm it.
 */
@Immutable
object PendulumType {

    /**
     * Roboto Flex is supplied by the system on Pixel and Wear; elsewhere the fallback is Roboto. We
     * do not ship the font as an asset: it weighs, and the visual difference on weights 300/400/500
     * is marginal against the cost.
     */
    private val family = FontFamily.Default

    private val tightLineHeight = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    private fun style(
        size: Int,
        weight: FontWeight,
        lineHeight: Int,
        letterSpacing: Double = 0.0,
        tabular: Boolean = false,
    ) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
        fontFeatureSettings = if (tabular) "tnum" else null,
        lineHeightStyle = tightLineHeight,
    )

    /** Median over the period. **One use only in the application** (P7). */
    val metricXL = style(44, FontWeight.Light, 48, -0.5, tabular = true)

    /** Section values: the hourly count in second place, the totals of the night detail. */
    val metricL = style(26, FontWeight.Normal, 32, tabular = true)

    val titleL = style(22, FontWeight.Medium, 28)
    val titleM = style(17, FontWeight.Medium, 24)
    val body = style(15, FontWeight.Normal, 22)

    /** First line of a verdict: "Inconclusive variation" and its siblings. */
    val bodyEmph = style(15, FontWeight.Medium, 22)

    val label = style(13, FontWeight.Medium, 18, 0.3)
    val caption = style(12, FontWeight.Normal, 16)

    /** Technical log and raw values. The user is a technician, the log is of use to them. */
    val mono = style(13, FontWeight.Normal, 18).copy(fontFamily = FontFamily.Monospace)

    /** Tabular variant of [body], for rows of values aligned in columns. */
    val bodyNum = body.copy(fontFeatureSettings = "tnum")

    /**
     * Projection onto the Material 3 slots, so that `Button`, `ListItem`, `TopAppBar` and the like
     * inherit the same styles without our having to dress them one by one.
     */
    val material = Typography(
        displayLarge = metricXL,
        displayMedium = metricL,
        displaySmall = titleL,
        headlineLarge = titleL,
        headlineMedium = titleL,
        headlineSmall = titleM,
        titleLarge = titleL,
        titleMedium = titleM,
        titleSmall = label,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = label,
        labelMedium = label,
        labelSmall = caption,
    )
}

/**
 * Spacing scale: `4 · 8 · 12 · 16 · 24 · 32 · 48` dp, and nothing else.
 *
 * A closed scale is a review tool: a 13 dp margin shows up in code review because it cannot be
 * written with these names. It is also what makes a screenshot and a PDF page look alike without
 * anyone having to tune them by hand.
 */
@Immutable
object Spacing {
    val xs = 4
    val s = 8
    val sm = 12
    val m = 16
    val l = 24
    val xl = 32
    val xxl = 48

    /** Screen margin. */
    val screen = m

    /** Space between cards. */
    val betweenCards = sm

    /** Inner padding of a card. */
    val card = m

    /**
     * Maximum width of an explanatory paragraph: ~60 characters.
     * The explanation blocks of this application are long — that is their job — so they must stay
     * legible, and a 90-character line is not.
     */
    val paragraphMax = 340
}

val LocalSpacing = staticCompositionLocalOf { Spacing }
