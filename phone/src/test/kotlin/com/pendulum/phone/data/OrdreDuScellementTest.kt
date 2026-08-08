package com.pendulum.phone.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * L'enchainement du scellement, et le defaut de fiabilite qu'il repare.
 *
 * ### Le defaut
 *
 * `sceller()` ecrivait le contexte en base — irreversiblement, `OnConflictStrategy.ABORT` plus
 * deux declencheurs SQLite d'immuabilite — puis tentait un `putDataItem`. **Rien ne rejouait ce
 * put.** Une indisponibilite passagere des services Google Play suffisait donc a produire un etat
 * sans issue : contexte scelle et non modifiable cote telephone, item jamais entre dans le
 * magasin, et une montre qui refuse d'enregistrer cette nuit-la pour toujours — `Preflight` fait
 * de `CONTEXT_NOT_SEALED` un blocage dur. Aucun recours utilisateur : rescelller leve.
 *
 * Le seul geste qui change cela est celui-ci — un put echoue enfile un rejeu — et il tenait dans
 * une ligne absente, au fond d'une coroutine qui ouvrait SQLite et appelait les services Google
 * Play. C'est pour cela qu'il est desormais dans [OrdreDuScellement], ou il s'exerce.
 */
class OrdreDuScellementTest {

    @Test
    fun `un put echoue enfile le rejeu`() {
        var rejeuxEnfiles = 0

        val publie = OrdreDuScellement.executer(
            ecrireEnBase = {},
            publier = { false },
            enfilerRejeu = { rejeuxEnfiles++ },
        )

        assertThat(publie).`as`("l'appelant doit savoir que la montre n'a rien recu").isFalse()
        assertThat(rejeuxEnfiles).`as`("rejeux enfiles apres un put echoue").isEqualTo(1)
    }

    @Test
    fun `un put reussi n'enfile rien`() {
        var rejeuxEnfiles = 0

        val publie = OrdreDuScellement.executer(
            ecrireEnBase = {},
            publier = { true },
            enfilerRejeu = { rejeuxEnfiles++ },
        )

        assertThat(publie).isTrue()
        // Enfiler quand meme ne serait pas dangereux — un item identique est dedoublonne — mais ce
        // serait un travail de fond planifie chaque soir pour ne rien faire, et surtout un signal
        // faux dans l'inspecteur WorkManager le jour ou l'on cherchera pourquoi une nuit a manque.
        assertThat(rejeuxEnfiles).`as`("rejeux enfiles apres un put reussi").isZero()
    }

    @Test
    fun `la base est ecrite avant toute tentative de publication`() {
        // L'inverse debloquerait la montre pour une soiree dont le contexte n'est pas en base :
        // une nuit qui s'enregistre et qui sortira ecartee pour `NO_CONTEXT` au matin.
        val gestes = mutableListOf<String>()

        OrdreDuScellement.executer(
            ecrireEnBase = { gestes += "base" },
            publier = { gestes += "put"; false },
            enfilerRejeu = { gestes += "rejeu" },
        )

        assertThat(gestes).containsExactly("base", "put", "rejeu")
    }

    @Test
    fun `une base qui refuse le doublon ne publie ni n'enfile rien`() {
        // `ecrireEnBase` leve : c'est `OnConflictStrategy.ABORT` sur une soiree deja scellee.
        // Publier a cet instant annoncerait a la montre un contexte que la saisie courante n'a pas
        // ecrit, et enfiler un rejeu le repeterait toute la soiree.
        val gestes = mutableListOf<String>()

        val leve = runCatching {
            OrdreDuScellement.executer(
                ecrireEnBase = { throw IllegalStateException("deja scelle") },
                publier = { gestes += "put"; true },
                enfilerRejeu = { gestes += "rejeu" },
            )
        }

        assertThat(leve.isFailure).isTrue()
        assertThat(gestes).isEmpty()
    }
}
