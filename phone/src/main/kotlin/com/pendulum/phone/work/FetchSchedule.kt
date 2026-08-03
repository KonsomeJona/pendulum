package com.pendulum.phone.work

import com.pendulum.phone.temps.Durees

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

    /** Delais depuis la **fin** de la nuit, en millisecondes. Valeurs dans `Durees`. */
    val OFFSETS_MS: LongArray = Durees.ACTIVES.offsetsLectureMs

    /**
     * Au-dela, on arrete. Trente-six heures ne sont pas un compromis : c'est le point ou
     * continuer d'essayer coute plus (des reveils, une notification qui reste en suspens, une
     * nuit dont l'etat n'est jamais final) que ce que la reponse rapporterait.
     */
    val GIVE_UP_MS: Long = Durees.ACTIVES.abandonLectureMs

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
    fun plan(
        attemptsDone: Int,
        sessionEndMs: Long,
        nowMs: Long,
        /**
         * L'echelle et son mur, en parametres plutot que lus au fond de la fonction.
         *
         * Cette fonction est pure et ses tests affirment des valeurs — « la premiere tentative est
         * a T+30 minutes », « on abandonne a T+36 h ». Les laisser suivre le diviseur de la
         * variante compilee ferait echouer ces affirmations parce qu'un banc a ete construit
         * autrement, ce qui n'apprend rien a personne sur la replanification.
         */
        offsetsMs: LongArray = OFFSETS_MS,
        abandonMs: Long = GIVE_UP_MS,
    ): Plan {
        val elapsed = nowMs - sessionEndMs
        if (elapsed >= abandonMs) {
            // Le message se derive de la borne au lieu de la citer : sur le banc la borne est
            // comprimee, et un journal qui annoncerait « T+36 h » apres trois minutes serait la
            // premiere chose a envoyer un lecteur sur une fausse piste.
            return Plan.GiveUp("abandon : $elapsed ms ecoulees, borne $abandonMs ms")
        }
        if (attemptsDone >= offsetsMs.size) {
            return Plan.GiveUp("echelle epuisee apres ${offsetsMs.size} tentatives")
        }
        val target = sessionEndMs + offsetsMs[attemptsDone]
        return Plan.Retry(delayMs = (target - nowMs).coerceAtLeast(0L), attemptIndex = attemptsDone)
    }

    // -------------------------------------------------------------------------------------
    // Le declencheur opportuniste
    // -------------------------------------------------------------------------------------

    /**
     * Deux instants ou la lecture a beaucoup plus de chances d'aboutir que le rang suivant de
     * l'echelle.
     *
     * ### Pourquoi un repli exponentiel seul est le mauvais modele
     *
     * Le repli exponentiel suppose un evenement **aleatoire** dont on ignore la date. La
     * synchronisation Health Connect n'en est pas un : elle est correlee a l'usage. La montre de
     * poignet pousse quand elle est sur le chargeur, et l'application source ecrit quand on
     * l'ouvre — c'est-a-dire souvent quelques secondes avant qu'on ouvre Pendulum pour voir sa
     * nuit. Attendre le rang T+4 h alors que la donnee est arrivee a T+2 h 05 coute deux heures
     * de latence percue pour rien.
     *
     * Deux signaux gratuits, donc : le branchement sur le chargeur
     * (`ACTION_POWER_CONNECTED`, exempte des restrictions de diffusion depuis Android 8) et le
     * retour de l'application au premier plan.
     *
     * ### Ce que cette fonction protege
     *
     * Une lecture opportuniste **ne consomme pas l'echelle** : elle est journalisee avec
     * [INDEX_OPPORTUNISTE] et `HcSnapshotDao.attemptCount` ne compte que les rangs planifies.
     * Sans cela, brancher et debrancher le telephone trois fois epuiserait les sept rangs en une
     * minute et l'application abandonnerait avant midi.
     *
     * Il reste a eviter la rafale : un cable qui fait faux contact peut emettre la diffusion
     * plusieurs fois par minute, et chaque tentative interroge un fournisseur. D'ou le delai
     * minimal entre deux lectures opportunistes.
     */
    fun opportunisteAdmissible(
        sessionEndMs: Long,
        nowMs: Long,
        derniereTentativeMs: Long?,
        /** Voir [plan] : les bornes sont des parametres pour que les tests puissent les nommer. */
        abandonMs: Long = GIVE_UP_MS,
        minEntreMs: Long = MIN_ENTRE_OPPORTUNISTES_MS,
    ): Boolean {
        val elapsed = nowMs - sessionEndMs
        if (elapsed < 0 || elapsed >= abandonMs) return false
        val derniere = derniereTentativeMs ?: return true
        return nowMs - derniere >= minEntreMs
    }

    /** Delai minimal entre deux lectures opportunistes. Anti-rafale, rien de plus. */
    val MIN_ENTRE_OPPORTUNISTES_MS: Long = Durees.ACTIVES.minEntreOpportunistesMs

    /**
     * `attemptIndex` des lignes `hc_snapshot` produites hors echelle.
     *
     * Negatif pour que le compte des tentatives planifiees reste un simple
     * `WHERE attemptIndex >= 0` : une colonne booleenne de plus aurait demande une migration, la
     * convention de signe n'en demande aucune et se lit dans la requete.
     */
    const val INDEX_OPPORTUNISTE = -1

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
