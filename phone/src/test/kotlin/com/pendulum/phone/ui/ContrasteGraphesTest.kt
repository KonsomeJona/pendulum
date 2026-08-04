package com.pendulum.phone.ui

import androidx.compose.ui.graphics.Color
import com.pendulum.phone.ui.theme.ChartTokens
import com.pendulum.phone.ui.theme.PendulumColors
import com.pendulum.phone.ui.theme.RenderTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 * Le contraste des elements de graphe, calcule plutot que suppose.
 *
 * ### La regle appliquee
 *
 * WCAG 2.1 critere 1.4.11 (*Non-text Contrast*) exige **3:1** contre l'arriere-plan adjacent pour
 * « les parties graphiques necessaires a la comprehension du contenu ». Les lignes d'un graphe
 * en font explicitement partie. Le document dit 4,4:1 pour les quatre teintes opaques
 * (`06-interface.md` §5.4) — ce qui est vrai — mais ne dit rien des **alphas** avec lesquels
 * trois de ces teintes sont effectivement dessinees. C'est la que le critere se perd, et un oeil
 * ne le rattrape pas : une bande a 14 % d'opacite parait « discrete », pas « sous le seuil ».
 *
 * ### Ce qui est exige, et ce qui ne l'est pas
 *
 * Un element est tenu au 3:1 quand il est **le seul porteur** d'une information : la ligne de
 * seuil du detecteur, la barre de serie, les bornes de l'intervalle, les points de nuit, la ligne
 * de mediane, la ligne de reference.
 *
 * Le **remplissage** de la bande d'intervalle en est exempte, et c'est la seule exemption de ce
 * fichier : l'information « voici l'intervalle » est portee par ses deux bornes, tracees en
 * tirete, qui sont elles tenues au 3:1 ci-dessous. Monter le remplissage a 3:1 demanderait une
 * opacite d'environ 0,50 (mesuree par [alphaPourTrois]), ce qui noierait les points de nuit
 * dessines par-dessus — c'est-a-dire echangerait un element conforme contre un element illisible.
 * Les graduations de l'axe sont exemptees pour la meme raison qu'elles le sont dans la norme :
 * elles sont decoratives, la valeur etant portee par le texte d'axe.
 */
class ContrasteGraphesTest {

    /** Seuil WCAG 1.4.11 pour un objet graphique. */
    private val minimum = 3.0

    // -------------------------------------------------------------------------------------
    // Colorimetrie : luminance relative sRGB et composition alpha, telles que la norme les
    // definit. Trente lignes, aucune dependance, et surtout aucune approximation « a l'oeil ».
    // -------------------------------------------------------------------------------------

    private fun canalLineaire(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * canalLineaire(c.red) +
            0.7152 * canalLineaire(c.green) +
            0.0722 * canalLineaire(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val haut = maxOf(la, lb)
        val bas = minOf(la, lb)
        return (haut + 0.05) / (bas + 0.05)
    }

    /**
     * Composition `SrcOver` en espace sRGB, exactement ce que fait `drawRect(..., alpha = a)` :
     * la fusion se fait sur les valeurs encodees, pas sur les valeurs linearisees.
     */
    private fun compose(dessus: Color, dessous: Color, alpha: Float): Color = Color(
        red = dessus.red * alpha + dessous.red * (1f - alpha),
        green = dessus.green * alpha + dessous.green * (1f - alpha),
        blue = dessus.blue * alpha + dessous.blue * (1f - alpha),
    )

    /** L'opacite minimale qui ferait passer [dessus] sur [fond] a 3:1. Sert au diagnostic. */
    private fun alphaPourTrois(dessus: Color, fond: Color): Float {
        var a = 0.01f
        while (a < 1f) {
            if (ratio(compose(dessus, fond, a), fond) >= minimum) return a
            a += 0.01f
        }
        return 1f
    }

    // -------------------------------------------------------------------------------------
    // Mesure
    // -------------------------------------------------------------------------------------

    private data class Mesure(val nom: String, val ratio: Double, val exige: Boolean)

    private fun mesurer(couleurs: PendulumColors, cible: RenderTarget): List<Mesure> {
        val t = ChartTokens.of(couleurs, cible)
        val fond = t.plotBackground
        fun m(nom: String, c: Color, alpha: Float = 1f, exige: Boolean = true) =
            Mesure(nom, ratio(compose(c, fond, alpha), fond), exige)

        return listOf(
            // --- teintes opaques
            m("point de nuit / mediane / enveloppe", t.primaryData),
            m("anneau masque accelero", t.attention),
            m("REM / seconde regle", t.secondSignal),
            m("plancher de bruit / nuit ecartee", t.structural),
            m("texte d'axe", t.axisText),
            // --- bande de metrologie : les deux seules teintes du produit qui codent un etat, et
            //     elles ne codent qu'un etat **technique**. Elles sont opaques partout ou elles
            //     servent — jauge pleine, hachure, tics de rug plot — donc sans alpha a mesurer,
            //     mais elles sont tenues au meme 3:1 que le reste : la jauge est le seul endroit de
            //     l'application ou un utilisateur lit « la montre a tenu la nuit ».
            m("jauge de batterie tenue (technicalOk)", t.technicalOk),
            m("jauge de batterie non tenue (technicalFail)", t.technicalFail),
            // --- teintes composees : c'est la que le critere se joue
            m("seuil d'onset (thresholdAlpha)", t.primaryData, t.thresholdAlpha),
            m("barre de serie (seriesBarAlpha)", t.primaryData, t.seriesBarAlpha),
            m("bornes de l'intervalle", t.primaryData, ChartTokens.BORNES_IC_ALPHA),
            m("voie masque accelero (hypnogramme)", t.structural, t.maskLaneAlpha),
            // --- exemptes, mesurees quand meme : une exemption non chiffree est une excuse
            m("remplissage de la bande IC (ciBandAlpha)", t.primaryData, t.ciBandAlpha, exige = false),
            m("graduation Y", t.structural, ChartTokens.GRADUATION_ALPHA, exige = false),
            // Les lignes de base des deux rug plots, exemptees pour la meme raison que les
            // graduations : elles situent les tics, elles n'informent pas. Ce sont les tics —
            // opaques, mesures plus haut — qui portent.
            m("ligne de base des rug plots", t.technicalFail, ChartTokens.GRADUATION_ALPHA, exige = false),
        )
    }

    /**
     * Le couple rouge-vert de la bande de metrologie ne code jamais un resultat de sante, et il ne
     * code jamais **seul**.
     *
     * Ce test verifie la moitie que la colorimetrie ne verifie pas : que les deux teintes sont bien
     * celles de [PendulumColors.success] et [PendulumColors.error], c'est-a-dire les deux roles que
     * la palette reserve aux etats techniques, et qu'elles ne se confondent avec aucune des quatre
     * teintes de donnee. Le jour ou quelqu'un branchera `technicalFail` sur `attention` pour
     * « adoucir » la bande, ou `primaryData` sur `success`, ce test tombera — et la regle
     * semantique de la palette n'a aucun autre gardien executable.
     *
     * Que la couleur ne soit pas seule porteuse est tenu par la forme (aplat plein contre hachure)
     * et par le chiffre ecrit a cote : cela se verifie a l'oeil sur une capture en niveaux de gris,
     * pas par un calcul de luminance.
     */
    @Test
    fun `les teintes d'etat technique sont celles de la palette, et distinctes des teintes de donnee`() {
        listOf(
            PendulumColors.Dark to RenderTarget.Screen,
            PendulumColors.Light to RenderTarget.Print,
        ).forEach { (couleurs, cible) ->
            val t = ChartTokens.of(couleurs, cible)
            assertThat(t.technicalOk).isEqualTo(couleurs.success)
            assertThat(t.technicalFail).isEqualTo(couleurs.error)
            assertThat(listOf(t.primaryData, t.attention, t.secondSignal, t.structural))
                .describedAs("aucune teinte de donnee ne doit valoir une teinte d'etat technique")
                .doesNotContain(t.technicalOk, t.technicalFail)
        }
    }

    private fun rapport(titre: String, mesures: List<Mesure>): String = buildString {
        append("\n$titre\n")
        mesures.forEach {
            append(
                "  %-42s %5.2f:1  %s%n".format(
                    it.nom,
                    it.ratio,
                    if (!it.exige) "(exempte)" else if (it.ratio >= minimum) "ok" else "ECHEC",
                ),
            )
        }
    }

    @Test
    fun `ecran sombre - tout element porteur d'information tient le 3 pour 1`() {
        val mesures = mesurer(PendulumColors.Dark, RenderTarget.Screen)
        print(rapport("contraste, ecran sombre (fond ${PendulumColors.Dark.background})", mesures))
        print(
            "  opacite qu'il faudrait pour un remplissage IC a 3:1 : " +
                "${alphaPourTrois(PendulumColors.Dark.accent, PendulumColors.Dark.background)}\n",
        )

        val echecs = mesures.filter { it.exige && it.ratio < minimum }
        assertThat(echecs)
            .withFailMessage("Elements sous 3:1 (WCAG 1.4.11) : %s", echecs)
            .isEmpty()
    }

    @Test
    fun `export clair - meme regle, meme seuil`() {
        // L'export part sur la palette claire : un PDF sombre est illisible imprime. Les tokens
        // changent, la contrainte non — et c'est exactement le genre d'ecart qui ne se voit qu'une
        // fois la feuille dans la main d'un medecin.
        val mesures = mesurer(PendulumColors.Light, RenderTarget.Print)
        print(rapport("contraste, export clair (fond ${PendulumColors.Light.background})", mesures))

        val echecs = mesures.filter { it.exige && it.ratio < minimum }
        assertThat(echecs)
            .withFailMessage("Elements sous 3:1 (WCAG 1.4.11) : %s", echecs)
            .isEmpty()
    }

    @Test
    fun `les quatre teintes opaques tiennent aussi le 4,4 pour 1 annonce par la documentation`() {
        listOf(
            PendulumColors.Dark to RenderTarget.Screen,
            PendulumColors.Light to RenderTarget.Print,
        ).forEach { (couleurs, cible) ->
            val t = ChartTokens.of(couleurs, cible)
            listOf(t.primaryData, t.attention, t.secondSignal, t.structural).forEach { c ->
                assertThat(ratio(c, t.plotBackground))
                    .describedAs("$c sur ${t.plotBackground}")
                    .isGreaterThanOrEqualTo(4.4)
            }
        }
    }
}
