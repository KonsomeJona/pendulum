package com.pendulum.phone.ui

import com.pendulum.phone.ui.onboarding.RepriseAssistant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La reprise de l'assistant.
 *
 * Le defaut que ces tests empechent de revenir n'est pas un plantage : c'est un assistant qui se
 * saute lui-meme. Le compteur persiste compte les etapes **franchies**, et l'ecrire a l'entree
 * d'une etape suffirait a considerer l'avertissement comme lu par quelqu'un qui l'a seulement vu
 * apparaitre. Comme la valeur est un simple entier, rien dans le type ne distingue les deux
 * lectures — seul un test le fait.
 */
class RepriseAssistantTest {

    @Test
    fun `installation neuve, l'assistant est a faire et commence au debut`() {
        assertThat(RepriseAssistant.assistantAFaire(0)).isTrue()
        assertThat(RepriseAssistant.pageDeDepart(0)).isEqualTo(0)
    }

    @Test
    fun `quitter apres l'etape 3 y ramene, et pas au debut`() {
        // Trois etapes franchies : les pages 0, 1 et 2 sont derriere, on rouvre sur la page 3 —
        // la quatrieme, celle de la source de sommeil. Refaire trois ecrans d'avertissement pour
        // arriver a celui qu'on cherchait est la facon la plus sure de faire desinstaller une
        // application.
        assertThat(RepriseAssistant.assistantAFaire(3)).isTrue()
        assertThat(RepriseAssistant.pageDeDepart(3)).isEqualTo(3)
    }

    @Test
    fun `assistant termine, il n'est plus a faire`() {
        assertThat(RepriseAssistant.assistantAFaire(RepriseAssistant.PAGES)).isFalse()
    }

    @Test
    fun `sortir d'une page franchit cette page et une seule`() {
        assertThat(RepriseAssistant.etapeApres(page = 0, etapesFranchies = 0)).isEqualTo(1)
        assertThat(RepriseAssistant.etapeApres(page = 3, etapesFranchies = 3)).isEqualTo(4)
    }

    @Test
    fun `sortir de la derniere page termine l'assistant`() {
        val derniere = RepriseAssistant.PAGES - 1
        val apres = RepriseAssistant.etapeApres(derniere, derniere)
        assertThat(apres).isEqualTo(RepriseAssistant.PAGES)
        assertThat(RepriseAssistant.assistantAFaire(apres)).isFalse()
    }

    @Test
    fun `le compteur ne redescend jamais`() {
        // Sortir d'une page anterieure a ce qui est deja acquis ne doit rien effacer : le
        // contraire redemanderait des permissions deja accordees.
        assertThat(RepriseAssistant.etapeApres(page = 0, etapesFranchies = 4)).isEqualTo(4)
        assertThat(RepriseAssistant.etapeApres(page = 1, etapesFranchies = 3)).isEqualTo(3)
    }

    @Test
    fun `un compteur trop grand ouvre la derniere page et non une page inexistante`() {
        // Cas d'un assistant ramene a moins d'etapes dans une version ulterieure, sur un
        // telephone ou l'ancien compteur est deja ecrit.
        assertThat(RepriseAssistant.pageDeDepart(99)).isEqualTo(RepriseAssistant.PAGES - 1)
        assertThat(RepriseAssistant.etapeApres(99, 0)).isEqualTo(RepriseAssistant.PAGES)
    }
}
