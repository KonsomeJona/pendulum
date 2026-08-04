package com.pendulum.phone.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Les roles de couleur de Pendulum, en dur, dans les deux themes.
 *
 * ### Pourquoi une palette a la main plutot que Material You
 *
 * `dynamicColorScheme()` derive les couleurs du fond d'ecran. Ce serait joli et ce serait faux :
 * une capture d'ecran montree a un medecin, ou un PDF imprime, ne peut pas dependre du fond
 * d'ecran de l'appareil. Deux telephones doivent produire **le meme graphe**. La palette est donc
 * figee et les tokens de graphe en descendent (`ChartTokens`).
 *
 * ### La regle semantique, qui est la seule vraiment non negociable ici
 *
 * [error] et [success] ne decrivent **jamais** un resultat de sante. Un rythme court n'est pas
 * rouge, un rythme long n'est pas vert, un compte horaire eleve n'est pas rouge. Ces deux
 * couleurs ne servent qu'aux etats **techniques** : transfert, permission, appairage, integrite
 * de fichier, stockage. Colorer un resultat, c'est le transformer en verdict — et ce produit
 * mesure, il ne juge pas.
 *
 * Le corollaire pratique : tout ce qui est « situation » et non « panne » (nuit sans
 * hypnogramme, nuit courte, montre non portee) s'affiche en [attention], jamais en [error].
 *
 * ### Et la couleur n'est jamais seule (P6)
 *
 * Aucune information de cet ecran n'est portee par la seule teinte. Les trois etats de nuit sont
 * disque plein / disque cercle / cercle creux ; les trois lignes du graphe de nuit sont pleine /
 * tiretee / pointillee ; les stades de sommeil sont d'abord une position verticale ; REM et les
 * trous sont haches. Test de recette : passer chaque capture en niveaux de gris, rien ne doit
 * disparaitre.
 */
@Immutable
data class PendulumColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    /** Fond des bandes inactives des graphes : « ici, rien n'est compte ». */
    val surfaceMuted: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** Qualite degradee, resultat provisoire, desaccord de masques. Jamais une panne. */
    val attention: Color,
    /** REM, seconde regle de comptage en superposition. */
    val secondSignal: Color,
    /** Echec **technique** uniquement. Jamais un resultat de sante. */
    val error: Color,
    /** Transfert reussi **uniquement**. Jamais un resultat de sante. */
    val success: Color,
    val isDark: Boolean,
) {
    companion object {
        /**
         * Sombre par defaut, et force au premier lancement : `isSystemInDarkTheme()` est
         * volontairement ignore tant que l'utilisateur n'a pas choisi. Le motif est le contexte
         * d'usage, pas la mode — cette application se consulte a 7 h du matin, dans le noir, une
         * seule main, et un ecran clair a ce moment-la est agressif.
         */
        val Dark = PendulumColors(
            background = Color(0xFF0E1116),
            surface = Color(0xFF161A21),
            surfaceElevated = Color(0xFF1E232C),
            surfaceMuted = Color(0xFF12161C),
            outline = Color(0xFF2A313C),
            textPrimary = Color(0xFFE6EAF0),
            textSecondary = Color(0xFFA3ADBB),
            // #7C8695 jusqu'ici, et sous le seuil : 4,28:1 sur [surfaceElevated], qui est le fond
            // de la feuille « valeurs » — ou toutes les en-tetes de colonne sont dans ce role.
            // Mesure par `ContrasteTexteTest`, jamais par un oeil : un gris a 4,28:1 se lit
            // « discret », pas « non conforme ».
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
         * Clair — et c'est aussi la palette de l'export. Un PDF sombre est illisible imprime, et
         * l'export doit ressembler a l'ecran sans etre l'ecran : memes formes, meme typographie,
         * memes fonctions de dessin, tokens differents.
         */
        val Light = PendulumColors(
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            surfaceElevated = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFEEF1F5),
            outline = Color(0xFFD8DDE4),
            textPrimary = Color(0xFF12161C),
            textSecondary = Color(0xFF4C5663),
            // #666F7D jusqu'ici : 4,48:1 sur [surfaceMuted]. La meme regle que dans la palette
            // sombre, et pour la meme raison — [ThemeMode.Light] est un theme d'ecran declare,
            // pas seulement la palette de l'export.
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
     * Material 3 sert de porteur de tokens et de bibliotheque de composants, pas de systeme de
     * couleur : on lui donne nos valeurs.
     *
     * Deux points a noter dans cette projection :
     *
     * - `surfaceVariant`/`surfaceContainer*` sont tous ramenes a [surface] ou [surfaceElevated].
     *   L'elevation tonale empilee de M3 est illisible en sombre et ne survit pas a
     *   l'impression ; ici une carte est une surface **plus une bordure 1 dp**, ce qui donne la
     *   meme image a l'ecran et sur le papier.
     * - `error` est bien branche sur notre [error], mais aucun composant portant un resultat de
     *   sante n'utilise le role `error` de M3. La contrainte est en amont, dans les ecrans.
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
            surfaceTint = Color.Transparent, // pas d'elevation tonale : voir plus haut
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
