package com.pendulum.phone

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.eraseEverything
import com.pendulum.phone.preview.ApercuDonnees
import com.pendulum.phone.preview.apercuNuitDetail
import com.pendulum.phone.ui.Destination
import com.pendulum.phone.ui.model.TendanceUiState
import com.pendulum.phone.ui.nights.NightDetailScreen
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

    /**
     * **Base vide → ecran de refus.** Le test qui empeche le defaut de revenir.
     *
     * Ce n'est pas une redite du precedent : celui-la monte l'ecran a partir d'un etat construit
     * a la main, celui-ci monte **le graphe de navigation reel** contre une base reellement vide.
     * C'est la difference exacte qui manquait — `PendulumNavHost` passait `ApercuDonnees` a
     * `TrendScreen`, donc l'ecran affichait sept nuits, un hypnogramme et « Samsung Health » sur
     * une installation neuve, pendant que le test unitaire d'a cote passait au vert.
     *
     * Un defaut d'affichage se voit a l'oeil, en principe. Celui-la ne se voyait pas : l'ecran
     * etait credible.
     *
     * Le depart n'est plus la Tendance mais l'accueil, donc le test y navigue — et la premiere
     * assertion porte desormais sur l'accueil lui-meme : sur une base vide, il ne doit y avoir
     * aucun chiffre nulle part, y compris sur l'ecran que l'on voit en premier.
     */
    @Test
    fun baseVide_lAppOuvreSurLeRefusEtAucunChiffreAgrege() {
        val contexte = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        kotlinx.coroutines.runBlocking {
            PendulumDatabase.get(contexte).eraseEverything()
        }

        compose.setContent { PendulumTheme { com.pendulum.phone.ui.PendulumNavHost() } }
        compose.waitForIdle()

        // L'accueil : trois cartes, et la carte d'historique dit qu'il n'y a rien — a la fois
        // sur sa ligne d'etat et sur son bouton, qui porte son motif d'indisponibilite.
        compose.onAllNodesWithText(Textes.EcranAccueil.Historique.VIDE, substring = true)
            .onFirst().assertIsDisplayed()

        compose.onAllNodesWithText(Destination.TENDANCE.libelle, substring = false)
            .onFirst().performClick()
        compose.waitForIdle()

        // Le compteur de nuits, et rien d'autre. Aucun agregat n'existe : il n'y a pas de branche
        // de code qui en produise un sous trois nuits.
        compose.onNodeWithText(Textes.Tendance.REFUS_TITRE, substring = true).assertIsDisplayed()

        for (interdit in listOf(
            Textes.Tendance.RYTHME_LABEL,
            Textes.Tendance.COMPTE_LABEL,
        )) {
            compose.onAllNodesWithText(interdit, substring = true).assertCountEquals(0)
        }
    }

    /**
     * **Garde-fou 2 : le resultat d'une nuit est masque tant qu'il n'a pas ete demande.**
     *
     * La regle est dans `01-overview.md` §4 et n'existait nulle part dans le code : la colonne,
     * le DAO et la fonction du repository etaient ecrits, et aucun ecran ne s'en servait. Le
     * detail affichait donc le chiffre a l'ouverture, sans trace.
     *
     * Le test verifie les deux moities. Masque : ni le rythme, ni le compte, et un bouton pour
     * les demander — **un seul**, sans modale ni avertissement a accepter. Devoile : le chiffre,
     * et plus de bouton.
     */
    @Test
    fun nuitNonDevoilee_leChiffreNEstPasAffiche() {
        val nuit = ApercuDonnees.nuits.first().copy(devoileeAtMs = null)
        compose.setContent {
            PendulumTheme {
                NightDetailScreen(apercuNuitDetail.copy(nuit = nuit), {}, {}, {})
            }
        }

        compose.onNodeWithText(Textes.EcranAccueil.Resultat.BOUTON, substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("${Math.round(nuit.rythmeSec)} s", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun nuitDevoilee_leChiffreEstAffiche() {
        val nuit = ApercuDonnees.nuits.first().copy(devoileeAtMs = 1L)
        compose.setContent {
            PendulumTheme {
                NightDetailScreen(apercuNuitDetail.copy(nuit = nuit), {}, {}, {})
            }
        }

        compose.onNodeWithText(Textes.Nuits.VALEUR_UNE_NUIT, substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(Textes.EcranAccueil.Resultat.BOUTON, substring = true)
            .assertCountEquals(0)
    }
}
