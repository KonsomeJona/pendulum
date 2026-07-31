package com.pendulum.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pendulum.phone.ui.model.ApercuDonnees
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.onboarding.DisclaimerPage
import com.pendulum.phone.ui.text.Textes
import com.pendulum.phone.ui.theme.PendulumTheme
import com.pendulum.phone.ui.trend.TrendScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Les garde-fous, verifies **dans l'application assemblee**.
 *
 * Pourquoi sur appareil et pas en JVM : les tests unitaires de `ui` verifient des fonctions
 * pures — bornes de `position()`, absence de verbe d'evolution dans les ressources, invariants
 * de dessin. Ils ne repondent pas a la seule question qui compte ici, *ce que l'utilisateur voit
 * reellement a l'ecran*. Une regle peut etre correcte dans `Aggregat` et contournee par la
 * composition, par un etat oublie, ou par un ecran qui affiche quand meme.
 *
 * Ces tests sont donc la derniere ligne : ils echouent si le refus se met a afficher un chiffre,
 * si l'avertissement perd une de ses affirmations, ou si la hierarchie des grandeurs s'inverse.
 */
@RunWith(AndroidJUnit4::class)
class GardeFousTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ecranTendance(etat: TendanceUiState) {
        compose.setContent {
            PendulumTheme {
                TrendScreen(
                    etat = etat,
                    onNuit = {},
                    onComparer = {},
                    onQuestionnaire = {},
                    onExport = {},
                    onSceller = {},
                    onActionReveil = {},
                )
            }
        }
    }

    /**
     * Sous trois nuits eligibles, l'ecran refuse — et le refus doit etre **total**. Pas de
     * mediane, pas de categorie, pas de graphe, pas meme un graphe vide avec ses axes : un axe
     * vide invite l'oeil a imaginer la courbe qui manque.
     *
     * C'est le garde-fou le plus important du produit. Chez des patients confirmes, le seuil de
     * 15/h n'est franchi que sur environ un tiers des nuits individuelles ; conclure sur une ou
     * deux nuits n'est pas une imprudence, c'est une erreur de mesure.
     */
    @Test
    fun sousTroisNuits_aucunChiffreAgregeNEstAffiche() {
        ecranTendance(ApercuDonnees.tendanceRefus)

        compose.onNodeWithText(Textes.Tendance.REFUS_TITRE, substring = true).assertIsDisplayed()

        // Aucune mention propre a l'ecran complet ne doit apparaitre.
        for (interdit in listOf("high periodicity", "low periodicity", "Hourly count")) {
            compose.onAllNodesWithText(interdit, substring = true).assertCountEquals(0)
        }
    }

    /**
     * L'avertissement doit porter ses affirmations, et notamment celle que rien ne nous oblige a
     * ecrire mais que l'honnetete impose : ce n'est pas une application de sante officielle. Un
     * utilisateur a 7 h du matin ne lit pas une politique de confidentialite, il lit l'ecran.
     */
    @Test
    fun avertissement_diaQueCeNEstPasUneAppDeSanteOfficielle() {
        compose.setContent { PendulumTheme { DisclaimerPage(onContinuer = {}) } }

        for (affirmation in listOf(
            "not an official health application",
            "not a medical device",
            "makes no diagnosis",
            "does not measure your breathing",
        )) {
            compose.onNodeWithText(affirmation, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * La hierarchie des grandeurs, qui est une decision et non une preference de mise en page :
     * le rythme fondamental en secondes est la grandeur **suivie** — sa variabilite nuit a nuit
     * est douze fois moindre et il n'exige aucun denominateur — tandis que le compte horaire est
     * present mais au second rang, parce que c'est ce qu'un somnologue sait lire.
     *
     * Le test verifie surtout la phrase disant qu'aucun seuil publie ne s'applique au rythme :
     * sans elle, le chiffre de tete se lirait comme un resultat clinique.
     */
    @Test
    fun ecranComplet_leRythmeEstEnTeteEtLeCompteEnSecondRang() {
        ecranTendance(ApercuDonnees.tendancePrete)

        compose.onNodeWithText(Textes.Tendance.RYTHME_SANS_SEUIL, substring = true)
            .performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Hourly count", substring = true)
            .performScrollTo().assertIsDisplayed()
    }
}
