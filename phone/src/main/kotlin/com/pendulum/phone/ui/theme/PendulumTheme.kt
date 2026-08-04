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
 *
 * ### Le theme peint son fond, et ce n'est pas une commodite
 *
 * Compose ne peint aucun fond de lui-meme : un `MaterialTheme` ne fait que **porter** des jetons,
 * et la fenetre Android reste visible partout ou la composition ne dessine rien. Le fond etait
 * donc peint par accident, par le `containerColor` du `Scaffold` de `PendulumNavHost` — et
 * l'assistant du premier lancement, qui est une simple `Column` sans `Scaffold`, laissait voir le
 * fond **clair** que le systeme donne a une application sans `android:theme`. Le texte, lui,
 * restait dans la palette sombre : 1,06:1 mesure, sur l'ecran qui porte « ceci n'est pas un
 * dispositif medical ».
 *
 * Le `Surface` ci-dessous rend le fond solidaire de la palette, pour tout l'arbre, sans qu'un
 * ecran ait a y penser. La seconde moitie de la reparation est dans `res/values/themes.xml` :
 * elle couvre l'image dessinee **avant** que Compose ne s'execute.
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
        ) {
            // `Surface` et non `Box(background)` : il pose aussi `contentColor`, donc un composant
            // Material qui ne precise pas sa couleur de texte prend `onBackground` — c'est-a-dire
            // `textPrimary` — au lieu du noir par defaut.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = pendulum.background,
                contentColor = pendulum.textPrimary,
                content = content,
            )
        }
    }
}
