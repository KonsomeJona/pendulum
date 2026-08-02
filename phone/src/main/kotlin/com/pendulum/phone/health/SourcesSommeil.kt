package com.pendulum.phone.health

import com.pendulum.format.wire.WirePaths
import java.time.ZoneId

/**
 * Ce que les sept derniers jours de Health Connect disent des sources disponibles.
 *
 * L'assistant montrait ici deux noms ecrits en dur, « Samsung Health » et « Sleep as Android »,
 * avec leur couverture. Sur un telephone qui n'a ni l'un ni l'autre, l'ecran affirmait donc
 * l'existence de sources inexistantes, et l'utilisateur en choisissait une — le reglage etait
 * ecrit nulle part, et `SleepFetchWorker` retombait sur l'heuristique de [SleepSourceSelector]
 * sans que rien ne le dise.
 *
 * ### Pourquoi compter des nuits et pas des sessions
 *
 * Une application qui republie trois fois la meme nuit n'est pas une source qui couvre trois
 * nuits. Le denombrement passe donc par la **cle de nuit** — la meme que celle du contexte du
 * soir, avec sa bascule a midi — et non par le nombre d'enregistrements rendus. Compter les
 * sessions ferait passer une source bavarde pour une source reguliere, ce qui est exactement
 * l'inverse de ce qu'on cherche.
 */
object SourcesSommeil {

    /** Fenetre d'observation de l'assistant. Sept jours : `06-interface.md` §2.1, etape 4. */
    const val JOURS_OBSERVES = 7

    /**
     * Une source telle que l'assistant la presente.
     *
     * @param stades vrai si au moins une nuit porte deux types de stade distincts. Une source qui
     *   ne rend qu'une duree reste utilisable — le denominateur est tout ce dont l'index a besoin —
     *   mais elle ne permet pas de ventiler les mouvements par stade, et l'ecran doit le dire.
     */
    data class Observee(
        val paquet: String,
        val nuits: Int,
        val stades: Boolean,
    )

    /**
     * Regroupe des sessions par paquet emetteur.
     *
     * L'ordre est total et deterministe — nuits couvertes, puis presence de stades, puis nom de
     * paquet — pour que deux ouvertures successives de l'assistant proposent la meme liste dans le
     * meme ordre. Une liste qui se reordonne entre deux affichages fait choisir la mauvaise ligne.
     */
    fun resumer(
        candidats: List<SleepSourceSelector.Candidate>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Observee> = candidats
        .groupBy { it.packageName }
        .map { (paquet, sessions) ->
            Observee(
                paquet = paquet,
                nuits = sessions.map { WirePaths.nightKey(it.startMs, zone) }.distinct().size,
                stades = sessions.any { it.distinctStageTypes >= 2 },
            )
        }
        .sortedWith(
            compareByDescending<Observee> { it.nuits }
                .thenByDescending { it.stades }
                .thenBy { it.paquet },
        )
}
