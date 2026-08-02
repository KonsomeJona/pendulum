package com.pendulum.phone.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Les trois etats de l'appairage, sur des entrees simulees.
 *
 * La classification est la seule partie decidable sans appareil, et c'est aussi la seule qui peut
 * etre fausse sans que rien ne le montre : les trois etats s'affichent tous comme un ecran
 * plausible. Confondre « aucune montre appairee » et « application absente » envoie l'utilisateur
 * installer Pendulum sur une montre qui n'est appairee a rien — l'installation reussit, elle ne
 * se voit nulle part, et l'assistant continue de dire non.
 */
class AppairageMontreTest {

    @Test
    fun `aucun noeud connecte, aucune montre n'est appairee`() {
        val etat = AppairageMontre.classer(
            noeudsConnectes = emptySet(),
            noeudsCapables = emptySet(),
        )
        assertThat(etat).isEqualTo(EtatAppairage.AUCUNE_MONTRE)
    }

    @Test
    fun `montre appairee sans la capacite, l'application est absente ou hors de portee`() {
        val etat = AppairageMontre.classer(
            noeudsConnectes = setOf("noeud-a"),
            noeudsCapables = emptySet(),
        )
        assertThat(etat).isEqualTo(EtatAppairage.APP_ABSENTE_OU_HORS_PORTEE)
    }

    @Test
    fun `capacite trouvee, l'etape est validee`() {
        val etat = AppairageMontre.classer(
            noeudsConnectes = setOf("noeud-a"),
            noeudsCapables = setOf("noeud-a"),
        )
        assertThat(etat).isEqualTo(EtatAppairage.PRETE)
    }

    @Test
    fun `plusieurs montres appairees, une seule porte l'application`() {
        // Le cas ou l'ancienne montre reste appairee. Une seule suffit a valider l'etape : on ne
        // demande pas d'installer Pendulum sur toutes les montres de la maison.
        val etat = AppairageMontre.classer(
            noeudsConnectes = setOf("ancienne", "cheville"),
            noeudsCapables = setOf("cheville"),
        )
        assertThat(etat).isEqualTo(EtatAppairage.PRETE)
    }

    @Test
    fun `un noeud capable l'emporte sur une liste de noeuds vide`() {
        // Les deux lectures ne sont pas atomiques. Un noeud qui annonce la capacite *et* est
        // declare joignable est une preuve plus forte qu'une liste de noeuds lue une
        // milliseconde plus tot et deja perimee. Classer cela « aucune montre » renverrait vers
        // l'application compagnon quelqu'un dont la montre repond.
        val etat = AppairageMontre.classer(
            noeudsConnectes = emptySet(),
            noeudsCapables = setOf("cheville"),
        )
        assertThat(etat).isEqualTo(EtatAppairage.PRETE)
    }

    @Test
    fun `les deux capacites ne portent pas le meme nom`() {
        // Symetrie du Data Layer : chacun annonce la sienne et cherche celle d'en face. Les
        // confondre donnerait un telephone qui se detecte lui-meme et valide l'etape sans montre.
        assertThat(AppairageMontre.CAPACITE_TELEPHONE)
            .isNotEqualTo(AppairageMontre.CAPACITE_MONTRE)
    }
}
