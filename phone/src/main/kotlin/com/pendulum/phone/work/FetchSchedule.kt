package com.pendulum.phone.work

import java.util.concurrent.TimeUnit

/**
 * La replanification de la lecture Health Connect. Fonction pure, testable sans appareil.
 *
 * ### Le piege que ce fichier existe pour desamorcer
 *
 * **La session de sommeil n'apparait pas au reveil.** Le transfert montre -> telephone est regi
 * par « la politique batterie de la montre » (mots du constructeur), sans delai garanti. Une
 * fois la donnee sur le telephone, l'ecriture vers Health Connect est immediate — le goulot est
 * donc entierement en amont, et aucun code du telephone ne peut l'accelerer.
 *
 * La consequence est qu'une lecture unique au reveil echoue la plupart du temps, et echoue
 * **silencieusement** : Health Connect ne renvoie pas d'erreur, il renvoie une liste vide. Sans
 * echelle de reprise, l'application conclurait « pas de donnees de sommeil cette nuit » alors
 * que la nuit arrive deux heures plus tard.
 *
 * ### Pourquoi la nuit n'attend pas cette lecture
 *
 * `AnalyzeWorker` tourne **immediatement** avec le masque accelerometrique : la nuit est
 * analysable des le transfert termine, et l'utilisateur voit un resultat au reveil.
 * `SleepFetchWorker` et `RescoreWorker` ajoutent ensuite le second bras, non circulaire, quand
 * l'hypnogramme arrive. L'echelle ci-dessous n'est donc jamais bloquante : au pire, la nuit
 * garde son seul masque accelerometrique, qui a le droit d'exister mais pas de porter le
 * resultat principal.
 *
 * ### Les valeurs
 *
 * T+30 min, 1 h, 2 h, 4 h, 8 h — puis 16 h et 32 h pour ne pas laisser 28 heures sans tentative
 * avant le mur — et abandon a T+36 h.
 *
 * **Ces chiffres sont une estimation, pas une mesure.** La procedure de verification
 * (`SOURCES-SOMMEIL.md` §5, etape 1) demande de noter l'heure exacte a laquelle la nuit apparait,
 * trois matins de suite, et de recaler cette echelle sur la valeur observee. Tant que ce n'est
 * pas fait, ces sept valeurs sont un pari raisonnable et rien de plus.
 */
object FetchSchedule {

    /** Delais depuis la **fin** de la nuit, en millisecondes. */
    val OFFSETS_MS: LongArray = longArrayOf(
        TimeUnit.MINUTES.toMillis(30),
        TimeUnit.HOURS.toMillis(1),
        TimeUnit.HOURS.toMillis(2),
        TimeUnit.HOURS.toMillis(4),
        TimeUnit.HOURS.toMillis(8),
        TimeUnit.HOURS.toMillis(16),
        TimeUnit.HOURS.toMillis(32),
    )

    /**
     * Au-dela, on arrete. Trente-six heures ne sont pas un compromis : c'est le point ou
     * continuer d'essayer coute plus (des reveils, une notification qui reste en suspens, une
     * nuit dont l'etat n'est jamais final) que ce que la reponse rapporterait.
     */
    val GIVE_UP_MS: Long = TimeUnit.HOURS.toMillis(36)

    sealed interface Plan {
        /** @param delayMs attente avant la prochaine tentative. Zero = rattrapage immediat. */
        data class Retry(val delayMs: Long, val attemptIndex: Int) : Plan

        data class GiveUp(val reason: String) : Plan
    }

    /**
     * @param attemptsDone nombre de lectures deja tentees pour cette nuit. Il est lu dans
     *   `hc_snapshot` (une ligne par tentative), **jamais** dans une preference ou un compteur
     *   de worker : WorkManager peut rejouer un worker, et un compteur qui avance a chaque
     *   execution consommerait l'echelle en quelques secondes apres un simple redemarrage.
     * @param sessionEndMs fin de la nuit. C'est l'origine des temps de l'echelle : compter
     *   depuis le debut de la nuit ferait tirer la premiere tentative pendant que la personne
     *   dort encore.
     *
     * `delayMs = 0` quand l'offset est deja passe — cas du telephone eteint toute la matinee.
     * On rattrape alors les tentatives une par une plutot que de sauter directement a la
     * derniere : chacune produit une ligne `hc_snapshot`, et cette trace est ce qui permettra
     * de recaler l'echelle sur la latence reelle.
     */
    fun plan(attemptsDone: Int, sessionEndMs: Long, nowMs: Long): Plan {
        val elapsed = nowMs - sessionEndMs
        if (elapsed >= GIVE_UP_MS) {
            return Plan.GiveUp("T+36 h depasse (${elapsed / 3_600_000} h)")
        }
        if (attemptsDone >= OFFSETS_MS.size) {
            return Plan.GiveUp("echelle epuisee apres ${OFFSETS_MS.size} tentatives")
        }
        val target = sessionEndMs + OFFSETS_MS[attemptsDone]
        return Plan.Retry(delayMs = (target - nowMs).coerceAtLeast(0L), attemptIndex = attemptsDone)
    }

    /**
     * Faut-il rescorer apres cette lecture ?
     *
     * On continue de lire **meme apres un succes**, parce qu'un fournisseur peut *reecrire* une
     * session deja publiee : la nuit lue a T+1 h peut differer de la meme nuit a T+8 h. Mais on
     * ne rescore que si quelque chose a change — sinon chaque tentative relancerait une analyse
     * complete pour aboutir au meme chiffre.
     *
     * La comparaison porte sur l'identifiant de l'enregistrement, sa date de derniere
     * modification et le nombre de stades : les trois champs qui bougent quand une source
     * republie sa nuit.
     */
    fun shouldRescore(
        previousRecordId: String?,
        previousLastModifiedMs: Long?,
        previousStageCount: Int,
        currentRecordId: String?,
        currentLastModifiedMs: Long?,
        currentStageCount: Int,
    ): Boolean {
        if (currentRecordId == null) return false
        if (previousRecordId == null) return true
        return previousRecordId != currentRecordId ||
            previousLastModifiedMs != currentLastModifiedMs ||
            previousStageCount != currentStageCount
    }
}
