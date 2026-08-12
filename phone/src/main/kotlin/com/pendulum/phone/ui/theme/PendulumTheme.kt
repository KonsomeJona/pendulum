package com.pendulum.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Shapes: restrained radii, and above all **no pill**.
 *
 * A fully rounded button reads as "consumer application". This product is a measuring instrument
 * that one shows to a doctor; the visual register is part of the message. Radius 10 dp for buttons
 * and fields, 14 dp for cards, 20 dp at the top of modal sheets.
 */
object PendulumShapes {
    val card = RoundedCornerShape(14.dp)
    val field = RoundedCornerShape(10.dp)
    val button = RoundedCornerShape(10.dp)
    val chip = RoundedCornerShape(10.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    val material = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = field,
        medium = card,
        large = card,
        extraLarge = sheet,
    )
}

/** What the user can choose in Settings > Appearance. */
enum class ThemeMode { System, Dark, Light }

/**
 * The theme.
 *
 * **Dark by default, and `isSystemInDarkTheme()` deliberately ignored** until the user has chosen
 * [ThemeMode.System]. The reason is not aesthetic: this application is consulted at 7 am or in the
 * middle of the night, often in the dark, with one hand. A light screen at that moment dazzles, and
 * a dazzled user reads a confidence interval badly.
 *
 * [render] lets the export reuse exactly the same drawing functions with the light palette and
 * stroke widths in points: `RenderTarget.Print` forces light whatever [mode] says, because a dark
 * PDF is illegible in print.
 *
 * ### The theme paints its own background, and that is not a convenience
 *
 * Compose paints no background of its own: a `MaterialTheme` only **carries** tokens, and the
 * Android window stays visible everywhere the composition draws nothing. The background was
 * therefore painted by accident, by the `containerColor` of the `Scaffold` in `PendulumNavHost` —
 * and the first-launch onboarding, which is a plain `Column` without a `Scaffold`, let the **light**
 * background that the system gives an application without an `android:theme` show through. The
 * text, meanwhile, stayed in the dark palette: 1.06:1 measured, on the very screen that carries
 * "this is not a medical device".
 *
 * The `Surface` below makes the background follow the palette, for the whole tree, without a screen
 * having to think about it. The second half of the repair is in `res/values/themes.xml`: it covers
 * the image drawn **before** Compose runs.
 */
@Composable
fun PendulumTheme(
    mode: ThemeMode = ThemeMode.Dark,
    render: RenderTarget = RenderTarget.Screen,
    content: @Composable () -> Unit,
) {
    val dark = when {
        render == RenderTarget.Print -> false
        mode == ThemeMode.Dark -> true
        mode == ThemeMode.Light -> false
        else -> isSystemInDarkTheme()
    }
    val pendulum = if (dark) PendulumColors.Dark else PendulumColors.Light
    val density = LocalDensity.current.density

    CompositionLocalProvider(
        LocalPendulumColors provides pendulum,
        LocalChartTokens provides ChartTokens.of(pendulum, render, density),
        LocalSpacing provides Spacing,
    ) {
        MaterialTheme(
            // No dynamicColorScheme(): see the KDoc of PendulumColors.
            colorScheme = pendulum.toMaterialScheme(),
            typography = PendulumType.material,
            shapes = PendulumShapes.material,
        ) {
            // `Surface` and not `Box(background)`: it also sets `contentColor`, so a Material
            // component that does not state its text colour takes `onBackground` — that is,
            // `textPrimary` — instead of the default black.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = pendulum.background,
                contentColor = pendulum.textPrimary,
                content = content,
            )
        }
    }
}
