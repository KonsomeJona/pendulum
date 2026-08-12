package com.pendulum.phone.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Pendulum's colour roles, hard-coded, in both themes.
 *
 * ### Why a hand-made palette rather than Material You
 *
 * `dynamicColorScheme()` derives the colours from the wallpaper. That would be pretty and it would
 * be wrong: a screenshot shown to a doctor, or a printed PDF, cannot depend on the device's
 * wallpaper. Two phones must produce **the same chart**. The palette is therefore fixed and the
 * chart tokens descend from it (`ChartTokens`).
 *
 * ### The semantic rule, which is the only truly non-negotiable one here
 *
 * [error] and [success] **never** describe a health result. A short rhythm is not red, a long
 * rhythm is not green, a high hourly count is not red. Those two colours serve only **technical**
 * states: transfer, permission, pairing, file integrity, storage. Colouring a result turns it into
 * a verdict — and this product measures, it does not judge.
 *
 * The practical corollary: everything that is a "situation" and not a "failure" (a night without a
 * hypnogram, a short night, a watch not worn) is shown in [attention], never in [error].
 *
 * ### And colour is never alone (P6)
 *
 * No information on these screens is carried by hue alone. The three night states are a filled
 * disc / a circled disc / an empty circle; the three lines of the night chart are solid / dashed /
 * dotted; the sleep stages are first of all a vertical position; REM and the gaps are hatched.
 * Acceptance test: put every screenshot into greyscale, nothing must disappear.
 */
@Immutable
data class PendulumColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    /** Background of the inactive bands of the charts: "here, nothing is counted". */
    val surfaceMuted: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Degraded quality, provisional result, disagreement between masks. Never a failure. */
    val attention: Color,
    /** REM, second counting rule drawn on top. */
    val secondSignal: Color,
    /** A **technical** failure only. Never a health result. */
    val error: Color,
    /** A successful transfer **only**. Never a health result. */
    val success: Color,
    val isDark: Boolean,
) {
    companion object {
        /**
         * Dark by default, and forced on first launch: `isSystemInDarkTheme()` is deliberately
         * ignored until the user has chosen. The reason is the context of use, not fashion — this
         * application is consulted at 7 am, in the dark, with one hand, and a light screen at that
         * moment is aggressive.
         */
        val Dark = PendulumColors(
            background = Color(0xFF0E1116),
            surface = Color(0xFF161A21),
            surfaceElevated = Color(0xFF1E232C),
            surfaceMuted = Color(0xFF12161C),
            outline = Color(0xFF2A313C),
            textPrimary = Color(0xFFE6EAF0),
            textSecondary = Color(0xFFA3ADBB),
            // #7C8695 until now, and below the threshold: 4.28:1 on [surfaceElevated], which is the
            // background of the "values" sheet — where every column header sits in this role.
            // Measured by `TextContrastTest`, never by an eye: a grey at 4.28:1 reads as
            // "discreet", not as "non-compliant".
            textTertiary = Color(0xFF818B9B),
            accent = Color(0xFF6FB2FF),
            onAccent = Color(0xFF0B1017),
            attention = Color(0xFFE0A33E),
            secondSignal = Color(0xFF8E7BEF),
            error = Color(0xFFE5736A),
            success = Color(0xFF5FBF8C),
            isDark = true,
        )

        /**
         * Light — and this is also the export palette. A dark PDF is illegible in print, and the
         * export must look like the screen without being the screen: same shapes, same typography,
         * same drawing functions, different tokens.
         */
        val Light = PendulumColors(
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFEEF1F5),
            outline = Color(0xFFD8DDE4),
            textPrimary = Color(0xFF12161C),
            textSecondary = Color(0xFF4C5663),
            // #666F7D until now: 4.48:1 on [surfaceMuted]. The same rule as in the dark palette,
            // and for the same reason — [ThemeMode.Light] is a declared screen theme, not merely
            // the export palette.
            textTertiary = Color(0xFF646D7A),
            accent = Color(0xFF1E63C8),
            onAccent = Color(0xFFFFFFFF),
            attention = Color(0xFF8A5D0B),
            secondSignal = Color(0xFF4F3FB8),
            error = Color(0xFFB3352C),
            success = Color(0xFF1F7A4D),
            isDark = false,
        )
    }

    /**
     * Material 3 serves as a token carrier and a component library, not as a colour system: we give
     * it our values.
     *
     * Two points worth noting in this projection:
     *
     * - `surfaceVariant`/`surfaceContainer*` are all brought back to [surface] or
     *   [surfaceElevated]. The stacked tonal elevation of M3 is illegible in dark and does not
     *   survive printing; here a card is a surface **plus a 1 dp border**, which gives the same
     *   image on screen and on paper.
     * - `error` is indeed wired to our [error], but no component carrying a health result uses M3's
     *   `error` role. The constraint is upstream, in the screens.
     */
    fun toMaterialScheme(): ColorScheme {
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = surfaceElevated,
            onPrimaryContainer = textPrimary,
            secondary = secondSignal,
            onSecondary = onAccent,
            tertiary = attention,
            onTertiary = onAccent,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceMuted,
            onSurfaceVariant = textSecondary,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceElevated,
            surfaceContainerHighest = surfaceElevated,
            surfaceContainerLow = surface,
            surfaceContainerLowest = background,
            surfaceTint = Color.Transparent, // no tonal elevation: see above
            outline = outline,
            outlineVariant = outline,
            error = error,
            onError = onAccent,
            errorContainer = surface,
            onErrorContainer = error,
            scrim = Color(0xCC000000),
        )
    }
}

val LocalPendulumColors = staticCompositionLocalOf { PendulumColors.Dark }
