package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * La colorimetrie WCAG, ecrite une fois.
 *
 * Elle etait privee dans `ContrasteGraphesTest`, qui mesurait le contraste des **graphes** et
 * seulement d'eux. Le contraste du **texte** n'etait mesure nulle part, et c'est par ce trou
 * qu'est passe l'ecran d'avertissement du premier lancement : un texte de la palette sombre sur
 * le fond clair que le systeme donne a une application sans `android:theme`, soit 1,06:1.
 *
 * Trente lignes, aucune dependance, et surtout aucune approximation « a l'oeil » — c'est
 * exactement le point : un fond gris tres clair sous un texte gris tres clair se decrit comme
 * « discret » tant qu'on ne calcule pas.
 */
internal object Colorimetrie {

    /** WCAG 2.1, critere 1.4.3 (*Contrast (Minimum)*) : texte courant. */
    const val TEXTE_MINIMUM = 4.5

    /** WCAG 2.1, critere 1.4.11 (*Non-text Contrast*) : objet graphique. */
    const val GRAPHIQUE_MINIMUM = 3.0

    private fun canalLineaire(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(c: Color): Double =
        0.2126 * canalLineaire(c.red) +
            0.7152 * canalLineaire(c.green) +
            0.0722 * canalLineaire(c.blue)

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Composition `SrcOver` en espace sRGB, exactement ce que fait `drawRect(..., alpha = a)` :
     * la fusion se fait sur les valeurs encodees, pas sur les valeurs linearisees.
     */
    fun compose(dessus: Color, dessous: Color, alpha: Float): Color = Color(
        red = dessus.red * alpha + dessous.red * (1f - alpha),
        green = dessus.green * alpha + dessous.green * (1f - alpha),
        blue = dessus.blue * alpha + dessous.blue * (1f - alpha),
    )

    /** L'opacite minimale qui ferait passer [dessus] sur [fond] a [GRAPHIQUE_MINIMUM]. */
    fun alphaPourTrois(dessus: Color, fond: Color): Float {
        var a = 0.01f
        while (a < 1f) {
            if (ratio(compose(dessus, fond, a), fond) >= GRAPHIQUE_MINIMUM) return a
            a += 0.01f
        }
        return 1f
    }
}
