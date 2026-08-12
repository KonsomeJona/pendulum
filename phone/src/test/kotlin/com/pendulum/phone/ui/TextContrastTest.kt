package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import com.pendulum.phone.ui.theme.PendulumColors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The contrast of the **text**, and the background it actually lands on.
 *
 * ### The defect this file exists to prevent
 *
 * The onboarding disclaimer screen — the one that carries "this is not a medical device", made
 * unskippable by a blocking scroll and four acknowledgements — was displayed in very light grey on
 * very light grey. **Measured contrast: 1.06:1.** It stayed unreadable through `@Preview`s, a
 * computed contrast test and a review.
 *
 * The cause was not a badly chosen colour: both palettes are correct and have stayed so. It is that
 * **nothing was painting the background**. Compose paints no background of its own, and the
 * `:phone` module declared no `android:theme`; Android then applies
 * `Theme.DeviceDefault.Light.DarkActionBar` — a light `windowBackground` under a dark action bar,
 * which incidentally explains why the title bar looked right: it was not ours. Everywhere the
 * composition painted a background by accident — the `Scaffold` of `PendulumNavHost`, whose
 * `containerColor` is `colorScheme.background` — the defect was invisible. The onboarding is a
 * plain `Column`: the light window background stayed visible there under text written in the dark
 * palette.
 *
 * ### Why these two tests, and not a colour equality test
 *
 * A test checking that "`MaterialTheme.colorScheme.background` equals
 * `LocalPendulumColors.current.background`" would have gone green: both already descend from a
 * single source, `PendulumColors.toMaterialScheme()`. That is not where the discrepancy was. The
 * discrepancy was between the Compose palette and **the Android window**, which is a third source
 * nobody was looking at.
 *
 * The window background is therefore read where it lives — `res/values/themes.xml` — and poured
 * into the list of backgrounds against which each text role is measured. On the tree from before
 * the fix, the file does not exist: both tests fall, the first by saying that nothing is declared,
 * the second by refusing to measure against a background it does not know.
 *
 * ### The threshold
 *
 * WCAG 2.1 criterion 1.4.3: **4.5:1** for body text. No exemption here — the only one that exists
 * in the standard covers large text (18 pt, or 14 pt bold), and all three text roles of the product
 * are used for body copy somewhere.
 */
class TextContrastTest {

    /**
     * The window background declared by the module, read from the resource.
     *
     * `null` when no theme is declared — that is, the state that produced the defect, and it is
     * that state which the two tests below refuse.
     */
    private fun declaredWindowBackground(): Color? {
        val themes = moduleFile("src/main/res/values/themes.xml") ?: return null
        val hex = Regex("""android:windowBackground"\s*>\s*(#[0-9A-Fa-f]{6,8})\s*<""")
            .find(themes.readText())
            ?.groupValues
            ?.get(1)
            ?: return null
        return Color(hex.removePrefix("#").padStart(8, 'F').toLong(16).toULong().toInt())
    }

    private fun themeDeclaredInManifest(): Boolean =
        moduleFile("src/main/AndroidManifest.xml")
            ?.readText()
            ?.contains("android:theme=\"@style/Theme.Pendulum\"")
            ?: false

    /**
     * **The test that would have caught the defect.**
     *
     * It does not talk about colours but about sources: the window must have a background, that
     * background must be declared, and it must be exactly the one of the default palette. Three
     * conditions, and the tree from before the fix missed all three.
     */
    @Test
    fun `the window background is declared, and it is the dark palette's`() {
        assertThat(themeDeclaredInManifest())
            .describedAs(
                "AndroidManifest.xml must carry android:theme=\"@style/Theme.Pendulum\". Without " +
                    "android:theme, Android applies Theme.DeviceDefault.Light.DarkActionBar, " +
                    "whose windowBackground is LIGHT: it shows through everywhere the " +
                    "composition paints none, and text from the dark palette becomes " +
                    "unreadable on it.",
            )
            .isTrue()

        val background = declaredWindowBackground()
        assertThat(background)
            .describedAs(
                "res/values/themes.xml must declare android:windowBackground for " +
                    "Theme.Pendulum: it is the only colour visible before Compose runs.",
            )
            .isNotNull()

        assertThat(background)
            .describedAs(
                "the window background and PendulumColors.Dark.background are the same colour " +
                    "seen from two places; letting them diverge is reintroducing the defect in " +
                    "the form of a flash at start-up.",
            )
            .isEqualTo(PendulumColors.Dark.background)
    }

    /**
     * Every text role, against every background it can meet — **the window background included**,
     * which is exactly what was missing.
     *
     * The palette backgrounds and the window background are measured in the same table, without
     * distinction, because the user makes no difference: they read text on what the device shows,
     * whatever the layer that painted it.
     */
    @Test
    // `point` spelled out: a Kotlin backticked name cannot contain `.`, and a decimal comma
    // reads as a thousands separator in English. The figure itself is in the KDoc above.
    fun `every text role holds 4 point 5 to 1 on every background it can meet`() {
        val window = declaredWindowBackground()
        assertThat(window)
            .describedAs("window background not found: see the previous test")
            .isNotNull()

        data class Measurement(val name: String, val ratio: Double)

        val measurements = buildList {
            listOf(
                "dark" to PendulumColors.Dark,
                "light (export)" to PendulumColors.Light,
            ).forEach { (paletteName, p) ->
                val backgrounds = buildList {
                    add("background" to p.background)
                    add("surface" to p.surface)
                    add("surfaceElevated" to p.surfaceElevated)
                    add("surfaceMuted" to p.surfaceMuted)
                    // The window background belongs to the default palette alone: that is the one
                    // the theme forces, and the only one the window can show.
                    if (p.isDark) add("windowBackground (res/values/themes.xml)" to window!!)
                }
                val textRoles = listOf(
                    "textPrimary" to p.textPrimary,
                    "textSecondary" to p.textSecondary,
                    "textTertiary" to p.textTertiary,
                    // `attention` carries text: "degraded quality", "short night". So do the two
                    // other semantic hues, in the technical state band.
                    "attention" to p.attention,
                    "error" to p.error,
                    "success" to p.success,
                )
                backgrounds.forEach { (backgroundName, background) ->
                    textRoles.forEach { (roleName, roleColor) ->
                        add(
                            Measurement(
                                "$paletteName palette: $roleName on $backgroundName",
                                Colorimetry.ratio(roleColor, background),
                            ),
                        )
                    }
                }
            }
        }

        print(
            buildString {
                append("\ntext contrast (WCAG 1.4.3, threshold ${Colorimetry.TEXT_MINIMUM}:1)\n")
                measurements.forEach {
                    append(
                        "  %-58s %5.2f:1  %s%n".format(
                            it.name,
                            it.ratio,
                            if (it.ratio >= Colorimetry.TEXT_MINIMUM) "ok" else "FAIL",
                        ),
                    )
                }
            },
        )

        val failures = measurements.filter { it.ratio < Colorimetry.TEXT_MINIMUM }
        assertThat(failures)
            .withFailMessage("Text below %s:1 (WCAG 1.4.3): %s", Colorimetry.TEXT_MINIMUM, failures)
            .isEmpty()
    }

    /**
     * The Material projection renders the palette as it is, on the four roles that decide a
     * background and the colour of the text laid on it.
     *
     * This is what makes the sentence "`MaterialTheme.colorScheme.background` and
     * `LocalPendulumColors.current.background` are the same colour" true. It already was;
     * it was guaranteed by nothing.
     */
    @Test
    fun `the Material projection renders the palette exactly on the background and text roles`() {
        listOf(PendulumColors.Dark, PendulumColors.Light).forEach { p ->
            val m = p.toMaterialScheme()
            assertThat(m.background).describedAs("background").isEqualTo(p.background)
            assertThat(m.onBackground).describedAs("onBackground").isEqualTo(p.textPrimary)
            assertThat(m.surface).describedAs("surface").isEqualTo(p.surface)
            assertThat(m.onSurface).describedAs("onSurface").isEqualTo(p.textPrimary)
        }
    }

    /** Walks up from the working directory until a file of the `:phone` module is found. */
    private fun moduleFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "phone/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}
