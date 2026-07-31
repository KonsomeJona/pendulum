package com.pendulum.phone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Ou le dessin part : un ecran (sombre par defaut) ou une page de PDF (claire, en points). */
enum class RenderTarget { Screen, Print }

/**
 * Tout ce dont les trois fonctions de dessin ont besoin, et rien d'autre.
 *
 * ### Pourquoi ce sont des parametres et pas des constantes
 *
 * Les memes fonctions `DrawScope.drawXxx()` dessinent l'ecran et la page PDF (`UX.md` §8.3). Si
 * une couleur ou une epaisseur etait codee en dur dans la fonction de dessin, l'export
 * divergerait de l'ecran le jour ou l'un des deux changerait — et personne ne le verrait avant
 * qu'un medecin ait le papier en main. En passant un jeu de tokens, il n'existe **qu'un seul**
 * code de rendu et le theme est une donnee.
 *
 * ### Les quatre teintes, et pas cinq
 *
 * Bleu (donnee), ambre (qualite/attention), violet (second signal, REM), gris (structure). Aucune
 * paire rouge-vert : toutes les distinctions restent lisibles en deuteranopie et protanopie, et
 * de toute facon aucune n'est portee par la seule couleur (P6).
 *
 * Les epaisseurs sont en unites de dessin abstraites : le `DrawScope` d'un Canvas Compose les
 * recoit deja converties en pixels par l'appelant, celui d'une page PDF en points. La regle
 * minimale est 1,5 dp a l'ecran / 0,6 pt a l'export — en dessous, un trait disparait a
 * l'impression.
 */
@Immutable
data class ChartTokens(
    val target: RenderTarget,

    // --- fonds
    val plotBackground: Color,
    /** Bandes hors sommeil, voie « hypnogramme indisponible ». */
    val mutedBand: Color,

    // --- roles de donnee
    /** Enveloppe, points de nuit, ligne de mediane. */
    val primaryData: Color,
    /** Drapeaux, desaccord des masques, anneau « masque accelero ». */
    val attention: Color,
    /** REM, seconde regle superposee. */
    val secondSignal: Color,
    /** Plancher de bruit, axes, nuits ecartees. */
    val structural: Color,

    // --- texte
    val axisText: Color,
    val annotationText: Color,

    /**
     * Taille du texte d'axe, **en sp et non en pixels**, contrairement a tous les autres
     * scalaires de cette classe.
     *
     * La distinction n'est pas cosmetique : les traits et les motifs de pointilles sont
     * consommes par des primitives de `DrawScope`, qui travaillent en pixels, d'ou leur
     * multiplication par la densite ici. Le texte, lui, part dans un `TextStyle` sous la forme
     * `axisTextSize.sp`, et c'est le systeme de typographie qui applique la densite. La
     * multiplier ici aussi l'appliquait **deux fois** : sur un ecran a densite 2,75, un 11 sp
     * demande devenait 83 px, et toutes les graduations se chevauchaient au point de rendre
     * les trois graphes illisibles. Defaut invisible en previsualisation, trouve sur capture reelle.
     */
    val axisTextSize: Float,

    // --- traits
    val strokeThin: Float,
    val strokeNormal: Float,
    val strokeBold: Float,
    val dashThreshold: FloatArray,
    val dashNoiseFloor: FloatArray,
    val dashReference: FloatArray,

    // --- remplissages translucides
    val ciBandAlpha: Float,
    val seriesBarAlpha: Float,
    val thresholdAlpha: Float,
) {
    companion object {
        /**
         * `density` convertit des dp en pixels a l'ecran ; a l'export, l'appelant passe le
         * facteur points/dp de la page. Une seule echelle, un seul chemin.
         */
        fun of(colors: PendulumColors, target: RenderTarget, density: Float = 1f): ChartTokens {
            val screen = target == RenderTarget.Screen
            // 1,5 dp a l'ecran, 0,6 pt a l'export : les minimas de UX.md §4.
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
                axisText = colors.textTertiary,
                annotationText = colors.textSecondary,
                // Pas de `* density` : la valeur est en sp, voir la KDoc du champ.
                axisTextSize = if (screen) 11f else 8f,
                strokeThin = thin,
                strokeNormal = normal,
                strokeBold = bold,
                dashThreshold = floatArrayOf(6f * density, 4f * density),
                dashNoiseFloor = floatArrayOf(2f * density, 3f * density),
                dashReference = floatArrayOf(6f * density, 4f * density),
                ciBandAlpha = 0.14f,
                seriesBarAlpha = 0.35f,
                thresholdAlpha = 0.55f,
            )
        }
    }

    // `FloatArray` dans une data class : equals/hashCode generes comparent les references. Ces
    // tokens ne servent jamais de cle, mais on redefinit pour ne pas laisser un piege dormir.
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
