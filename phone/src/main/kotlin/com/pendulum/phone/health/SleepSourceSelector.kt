package com.pendulum.phone.health

/**
 * Deduplication des sessions de sommeil de Health Connect.
 *
 * ### Le fait contre-intuitif dont tout decoule
 *
 * `readRecords()` **ne dedoublonne rien** : il renvoie tous les enregistrements de toutes les
 * sources. Si Samsung Health et Sleep as Android ont tous deux ecrit la nuit, on obtient deux
 * sessions qui se chevauchent. Concatener naivement leurs stades donne un hypnogramme incoherent
 * et un TST a peu pres double — donc un aPLM-i divise par deux, **sans le moindre
 * avertissement**. Rien dans l'API ne signale le probleme.
 *
 * `aggregate()` dedoublonne, lui, selon la priorite d'application reglee par l'utilisateur. Mais
 * il ne sait rendre que `SLEEP_DURATION_TOTAL` : **aucune agregation ne renvoie les stades.** Il
 * sert donc de controle croise (§6, temps 1) et pas de source.
 *
 * ### La regle d'or
 *
 * **Une source et une seule pour une nuit donnee. On ne fusionne jamais les stades de deux
 * sources.** Deux hypnogrammes qui se contredisent ne se moyennent pas : ils se choisissent.
 * L'ordre de selection est celui de `SOURCES-SOMMEIL.md` §6 :
 *
 *  1. la source preferee reglee par l'utilisateur, si elle couvre >= 50 % de la fenetre ;
 *  2. sinon celle qui a le plus de **types de stade distincts** — un vrai hypnogramme bat une
 *     duree deguisee en hypnogramme ;
 *  3. a egalite, la plus longue couverture par les stades.
 *
 * La priorite systeme de Health Connect n'est **pas** un filet : Google precise que les
 * applications lectrices restent libres de tout lire et de fusionner a leur facon. C'est donc le
 * travail de Pendulum, pas celui de la plateforme.
 */
object SleepSourceSelector {

    /** Un stade, tel que Health Connect le rend. Les trous entre stades sont autorises. */
    data class StageSpan(val startMs: Long, val endMs: Long, val stageType: Int)

    /**
     * Une session candidate.
     *
     * @param packageName `metadata.dataOrigin.packageName`. **Consigne systematiquement** :
     *   sans lui, une nuit anormale est indebogable — on ne sait meme pas quelle application a
     *   ecrit l'hypnogramme utilise.
     * @param lastModifiedMs `metadata.lastModifiedTime`. C'est le champ qui trahit une
     *   reecriture apres coup : certains fournisseurs mettent a jour une session deja publiee,
     *   et une nuit lue a T+1 h peut differer de la meme nuit a T+8 h.
     */
    data class Candidate(
        val recordId: String,
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
        val lastModifiedMs: Long,
        val stages: List<StageSpan>,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

        /** Types de stade distincts, `STAGE_TYPE_UNKNOWN` (0) exclu : il ne renseigne rien. */
        val distinctStageTypes: Int
            get() = stages.asSequence().map { it.stageType }.filter { it != STAGE_TYPE_UNKNOWN }
                .distinct().count()

        /** Duree couverte par des stades. Peut etre tres inferieure a la duree de la session. */
        val stageCoverageMs: Long
            get() = stages.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
    }

    const val STAGE_TYPE_UNKNOWN = 0

    /** Seuil de recouvrement au-dela duquel la source preferee l'emporte d'office. */
    const val PREFERRED_MIN_OVERLAP = 0.50

    data class Selection(
        val chosen: Candidate?,
        val rejected: List<Candidate>,
        val reason: String,
    )

    /**
     * @param windowStartMs,windowEndMs la fenetre d'enregistrement de la montre de cheville.
     *   C'est le referentiel : une session de sieste de 14 h qui ne la recoupe pas ne concerne
     *   pas cette nuit, quelle que soit sa qualite.
     * @param preferredPackage la source choisie par l'utilisateur dans les reglages, ou `null`.
     */
    fun select(
        candidates: List<Candidate>,
        windowStartMs: Long,
        windowEndMs: Long,
        preferredPackage: String?,
    ): Selection {
        // Les sessions qui ne recoupent pas la nuit du tout sont ecartees d'emblee : les garder
        // ferait entrer une sieste de l'apres-midi dans le denominateur.
        val overlapping = candidates.filter { overlapMs(it, windowStartMs, windowEndMs) > 0L }
        if (overlapping.isEmpty()) {
            return Selection(null, candidates, "AUCUNE_SESSION_RECOUVRANTE")
        }

        val windowLen = (windowEndMs - windowStartMs).coerceAtLeast(1L)

        preferredPackage?.let { pref ->
            val best = overlapping
                .filter { it.packageName == pref }
                .maxByOrNull { overlapMs(it, windowStartMs, windowEndMs) }
            if (best != null &&
                overlapMs(best, windowStartMs, windowEndMs).toDouble() / windowLen >= PREFERRED_MIN_OVERLAP
            ) {
                return Selection(best, overlapping - best, "SOURCE_PREFEREE")
            }
        }

        // Ordre deterministe et total : nombre de types de stade, puis couverture, puis
        // recouvrement, puis nom de paquet. Les trois derniers criteres ne servent qu'a briser
        // les egalites — sans eux, deux lectures successives des memes donnees pourraient
        // choisir des sources differentes selon l'ordre de retour de l'API, et deux analyses de
        // la meme nuit ne donneraient pas le meme chiffre.
        val chosen = overlapping.sortedWith(
            compareByDescending<Candidate> { it.distinctStageTypes }
                .thenByDescending { it.stageCoverageMs }
                .thenByDescending { overlapMs(it, windowStartMs, windowEndMs) }
                .thenBy { it.packageName }
                .thenBy { it.recordId }
        ).first()

        val reason = if (chosen.distinctStageTypes >= 2) "PLUS_DE_STADES" else "DUREE_SEULE"
        return Selection(chosen, overlapping - chosen, reason)
    }

    /** Recouvrement, en millisecondes, entre une session et la fenetre d'enregistrement. */
    fun overlapMs(c: Candidate, windowStartMs: Long, windowEndMs: Long): Long =
        (minOf(c.endMs, windowEndMs) - maxOf(c.startMs, windowStartMs)).coerceAtLeast(0L)

    fun overlapFraction(c: Candidate, windowStartMs: Long, windowEndMs: Long): Double {
        val len = (windowEndMs - windowStartMs).coerceAtLeast(1L)
        return overlapMs(c, windowStartMs, windowEndMs).toDouble() / len
    }

    /**
     * Le critere de bascule de `SOURCES-SOMMEIL.md` §4, applique litteralement.
     *
     * Une source est jugee suffisante si elle recoupe >= 50 % de la fenetre, contient au moins
     * deux types de stade distincts autres que `UNKNOWN`, et si ses stades couvrent >= 80 % de
     * la session. Un echec sur le seul recouvrement est un probleme de **latence** ; un echec
     * sur les stades est un probleme de **source**. Les deux ne se reparent pas de la meme
     * facon, d'ou deux verdicts distincts et non un booleen.
     */
    fun verdictOf(c: Candidate, windowStartMs: Long, windowEndMs: Long): String {
        val overlapOk = overlapFraction(c, windowStartMs, windowEndMs) >= 0.50
        val stagesOk = c.distinctStageTypes >= 2
        val coverageOk = c.durationMs > 0 &&
            c.stageCoverageMs.toDouble() / c.durationMs >= 0.80
        return when {
            !overlapOk -> "LATENCE"
            !stagesOk -> "STADES_ABSENTS"
            !coverageOk -> "HYPNOGRAMME_TROUE"
            else -> "OK"
        }
    }
}
