package com.pendulum.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Formes : rayons fermes, et surtout **pas de gelule**.
 *
 * Un bouton entierement arrondi lit « application grand public ». Ce produit est un instrument de
 * mesure qu'on montre a un medecin ; le registre visuel fait partie du message. Rayon 10 dp pour
 * les boutons et les champs, 14 dp pour les cartes, 20 dp en haut des feuilles modales.
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

/** Ce que l'utilisateur peut choisir dans Reglages › Apparence. */
enum class ThemeMode { System, Dark, Light }

/**
 * Le theme.
 *
 * **Sombre par defaut, et `isSystemInDarkTheme()` volontairement ignore** tant que l'utilisateur
 * n'a pas choisi [ThemeMode.System]. Le motif n'est pas esthetique : cette application se
 * consulte a 7 h du matin ou en pleine nuit, souvent dans le noir, d'une seule main. Un ecran
 * clair a ce moment-la eblouit, et un utilisateur eblouit lit mal un intervalle de confiance.
 *
 * [render] permet a l'export de reutiliser exactement les memes fonctions de dessin avec la
 * palette claire et des epaisseurs en points : `RenderTarget.Print` force le clair quel que soit
 * [mode], parce qu'un PDF sombre est illisible imprime.
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
            // Pas de dynamicColorScheme() : voir la KDoc de PendulumColors.
            colorScheme = pendulum.toMaterialScheme(),
            typography = PendulumType.material,
            shapes = PendulumShapes.material,
            content = content,
        )
    }
}
