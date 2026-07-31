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
 * Neuf styles, pas dix.
 *
 * ### Les chiffres tabulaires ne sont pas une coquetterie
 *
 * `fontFeatureSettings = "tnum"` est obligatoire partout ou une valeur peut changer : compteur
 * d'echantillons qui s'incremente, duree qui defile, bornes d'intervalle. Sans chasse fixe, les
 * chiffres n'ont pas la meme largeur et le compteur **tressaute** a chaque rafraichissement. Sur
 * un ecran dont le metier est de rendre des nombres lisibles, c'est disqualifiant.
 *
 * ### [metricXL] a exactement un site d'usage dans toute l'application
 *
 * C'est le principe P7 rendu verifiable : le chiffre d'une seule nuit n'est jamais un titre. Un
 * grand nombre est lu, une precaution ecrite a cote ne l'est pas — donc on ne met en grand que
 * ce qui a le droit d'etre lu seul, c'est-a-dire l'agregat de la periode, jamais une nuit.
 * Le site unique est `MetricHeadline`, et un grep sur `PendulumType.metricXL` doit le confirmer.
 */
@Immutable
object PendulumType {

    /**
     * Roboto Flex est fourni par le systeme sur Pixel et Wear ; ailleurs le repli est Roboto.
     * On ne fournit pas la police en asset : elle pese, et la difference visuelle sur des
     * graisses 300/400/500 est marginale devant le cout.
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

    /** Mediane de la periode. **Un seul usage dans l'application** (P7). */
    val metricXL = style(44, FontWeight.Light, 48, -0.5, tabular = true)

    /** Valeurs de section : compte horaire au second rang, totaux du detail de nuit. */
    val metricL = style(26, FontWeight.Normal, 32, tabular = true)

    val titleL = style(22, FontWeight.Medium, 28)
    val titleM = style(17, FontWeight.Medium, 24)
    val body = style(15, FontWeight.Normal, 22)

    /** Premiere ligne d'un verdict : « Variation non concluante » et ses freres. */
    val bodyEmph = style(15, FontWeight.Medium, 22)

    val label = style(13, FontWeight.Medium, 18, 0.3)
    val caption = style(12, FontWeight.Normal, 16)

    /** Journal technique et valeurs brutes. L'utilisateur est technicien, le journal lui sert. */
    val mono = style(13, FontWeight.Normal, 18).copy(fontFamily = FontFamily.Monospace)

    /** Variante tabulaire de [body], pour les lignes de valeurs alignees en colonnes. */
    val bodyNum = body.copy(fontFeatureSettings = "tnum")

    /**
     * Projection sur les emplacements Material 3, pour que `Button`, `ListItem`, `TopAppBar` et
     * consorts heritent des memes styles sans qu'on ait a les habiller un par un.
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
 * Echelle d'espacement : `4 · 8 · 12 · 16 · 24 · 32 · 48` dp, et rien d'autre.
 *
 * Une echelle fermee est un outil de revue : une marge de 13 dp se voit dans la revue de code
 * parce qu'elle ne peut pas s'ecrire avec ces noms. C'est aussi ce qui fait qu'une capture
 * d'ecran et une page de PDF se ressemblent sans qu'on ait a les accorder a la main.
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

    /** Marge d'ecran. */
    val screen = m

    /** Espace inter-cartes. */
    val betweenCards = sm

    /** Padding interne d'une carte. */
    val card = m

    /**
     * Largeur maximale d'un paragraphe explicatif : ~60 caracteres.
     * Les blocs d'explication de cette application sont longs — c'est leur metier — donc ils
     * doivent rester lisibles, et une ligne de 90 caracteres ne l'est pas.
     */
    val paragraphMax = 340
}

val LocalSpacing = staticCompositionLocalOf { Spacing }
