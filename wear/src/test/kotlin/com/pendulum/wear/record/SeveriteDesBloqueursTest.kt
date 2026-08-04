package com.pendulum.wear.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La couleur d'un bloqueur encode **ce qu'il est**, pas ce qu'il empeche.
 *
 * Ce test existe parce que le defaut qu'il fige ne se voyait pas en lisant le code : il fallait
 * mettre les deux ecrans cote a cote. Pour le meme fait — le contexte du soir pas encore scelle —
 * le telephone affichait de l'ambre et la montre du rouge. Aucun test ne pouvait echouer, aucune
 * previsualisation ne le montrait, et les deux ecrans etaient defendables separement.
 *
 * L'exhaustivite du `when` de [estUnePanne] fait le reste du travail : ajouter un [IssueId] sans
 * lui donner de severite ne compile pas. Ce qui est ecrit ici, c'est l'intention, pour qu'un
 * changement de severite soit un geste delibere et non un effet de bord.
 */
@DisplayName("La severite d'un bloqueur decrit sa nature, pas ce qu'il empeche")
class SeveriteDesBloqueursTest {

    @Test
    fun `une etape que l'utilisateur peut lever n'est pas une panne`() {
        // Remplir un formulaire qu'on n'a pas encore rempli n'est pas une panne : c'est le
        // deroulement normal du produit, et c'est meme la porte d'entree.
        assertThat(IssueId.CONTEXT_NOT_SEALED.estUnePanne).isFalse()
        // La permission de notification se donne en deux gestes, depuis l'ecran lui-meme.
        assertThat(IssueId.NOTIFICATIONS_DENIED.estUnePanne).isFalse()
    }

    @Test
    fun `ce que l'utilisateur ne peut pas reparer depuis cet ecran reste rouge`() {
        assertThat(IssueId.NO_ACCELEROMETER.estUnePanne).isTrue()
        assertThat(IssueId.STORAGE_FULL.estUnePanne).isTrue()
        assertThat(IssueId.FGS_REFUSED.estUnePanne).isTrue()
        // Une compilation de banc branchee sur le vrai capteur mesurerait faux sans le dire.
        // C'est le pire cas silencieux du projet ; il ne peut pas etre ambre.
        assertThat(IssueId.BENCH_SCALE_MISMATCH.estUnePanne).isTrue()
    }

    @Test
    fun `aucun avertissement n'est une panne, sinon la distinction ne veut plus rien dire`() {
        listOf(
            IssueId.LOW_BATTERY,
            IssueId.PHONE_UNREACHABLE,
            IssueId.NO_WAKEUP_SENSOR,
            IssueId.PENDING_SYNC,
        ).forEach { assertThat(it.estUnePanne).describedAs(it.name).isFalse() }
    }
}
