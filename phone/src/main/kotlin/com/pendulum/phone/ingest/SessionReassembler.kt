package com.pendulum.phone.ingest

import com.pendulum.algo.model.SampleBlock
import com.pendulum.format.ChunkReader
import com.pendulum.format.ChunkScanResult
import java.io.File

/**
 * Le reassemblage : des fichiers de chunks a la liste de blocs que `:algo` sait lire.
 *
 * ### Pourquoi la troncature est une sortie de premier plan, et non un detail
 *
 * Une nuit tronquee reste analysable et **doit** l'etre — la montre morte a 3 h a quand meme
 * enregistre trois heures. Mais son index est biaise a la hausse d'une facon qu'on ne sait pas
 * corriger : les series coupees par le bord perdent leurs mouvements, le denominateur perd des
 * minutes, et les deux biais ne vont pas dans le meme sens. Une nuit tronquee est donc analysee,
 * affichee, et **exclue de la tendance** ([com.pendulum.phone.db.ComparableNight], porte de
 * publication `TRUNCATED_NO_TREND`).
 *
 * D'ou une regle : `sessionClosedCleanly` doit etre calcule ici, pas devine par `:algo`.
 * `TimelineBuilder.build` le prend en parametre et vaut `true` par defaut precisement parce que
 * le module ne peut pas savoir — il ne voit que des blocs. Le telephone, lui, a vu les fichiers.
 */
object SessionReassembler {

    /**
     * @param blocks tous les blocs decodes de la nuit, en ordre chronologique.
     * @param closedCleanly faux des qu'un des signaux de troncature est present. C'est ce qui
     *   part dans `TimelineBuilder.build(..., sessionClosedCleanly = ...)`.
     * @param missingIndices index de chunks jamais recus, dans `[0, maxIdx]`. Un trou au milieu
     *   n'est pas la meme chose qu'une fin manquante : le premier peut encore arriver (la montre
     *   reemet sur accuse), la seconde non.
     * @param declaredChunks nombre total de chunks annonce par la montre a la fermeture, `null`
     *   si la session n'a jamais ete fermee proprement.
     */
    data class Night(
        val sessionHex: String,
        val blocks: List<SampleBlock>,
        val nominalRateHz: Int,
        val closedCleanly: Boolean,
        val missingIndices: List<Int>,
        val incompleteChunks: List<Int>,
        val declaredChunks: Int?,
        val receivedChunks: Int,
        val corruptBlocks: Int,
        val resyncSkippedBytes: Long,
        val truncatedTail: Boolean,
        val desynchronised: Boolean,
        /**
         * L'ancrage horloge murale <-> ligne de temps du capteur, pris sur le **premier** chunk
         * decode. `null` si aucun bloc n'a pu etre lu — auquel cas l'hypnogramme Health Connect
         * ne peut pas etre place sur la nuit, et il vaut mieux ne pas le placer du tout que le
         * placer au hasard.
         */
        val anchor: TimeAnchor?,
    ) {
        val sampleCount: Long get() = blocks.sumOf { it.x.size.toLong() }
    }

    /**
     * @param files les fichiers de chunks, **tries par index**. [ChunkStore.listChunkFiles] le
     *   garantit par le zero-padding des noms.
     * @param sessionStateClosed la montre a-t-elle annonce `CLOSED` ? Une session restee `OPEN`
     *   ou passee `STALE`/`TRUNCATED` par le chien de garde est tronquee par definition, meme si
     *   tous les fichiers presents sont impeccables.
     */
    fun reassemble(
        sessionHex: String,
        files: List<File>,
        sessionStateClosed: Boolean,
        declaredChunks: Int?,
    ): Night {
        val blocks = ArrayList<SampleBlock>(files.size * 30)
        val scans = ArrayList<Pair<Int, ChunkScanResult>>(files.size)
        val incomplete = ArrayList<Int>()
        var nominalRateHz = 0
        var anchor: TimeAnchor? = null

        for (f in files) {
            val idx = indexOf(f) ?: continue
            val scan = f.inputStream().buffered().use { input ->
                ChunkReader.forEachBlock(input) { decoded ->
                    // `adoptInPlace` : le bloc sort du lecteur et n'est plus lu ailleurs, donc
                    // convertir ses tableaux en place economise ~19 Mo de pointe memoire sur
                    // une nuit sans rien risquer. Voir le contrat de BlockAdapter.
                    blocks += BlockAdapter.adoptInPlace(decoded)
                }
            }
            if (nominalRateHz == 0) nominalRateHz = scan.header.nominalRateHz
            // L'ancrage est pris sur le premier bloc **effectivement decode**, pas sur l'en-tete
            // seul : si les tout premiers blocs ont ete rejetes, la ligne de temps ne commence
            // pas a `firstEventTimestampNs`, et un hypnogramme place dessus serait decale.
            if (anchor == null && blocks.isNotEmpty()) {
                anchor = TimeAnchor(
                    startWallMs = scan.header.startWallMs,
                    firstEventTimestampNs = scan.header.firstEventTimestampNs,
                    timelineT0Ns = blocks.first().tFirstNs,
                )
            }
            if (!scan.complete) incomplete += idx
            scans += idx to scan
        }

        val received = scans.map { it.first }.sorted()
        val missing = missingIndices(received, declaredChunks)

        // Cinq signaux, un seul verdict. Aucun n'est redondant :
        //  - la montre n'a pas annonce la fin           -> la nuit peut encore continuer, ou pas ;
        //  - il manque des index                        -> un trou au milieu, du temps perdu ;
        //  - un chunk n'a pas son marqueur de fin       -> il etait en cours d'ecriture ;
        //  - moins de chunks recus que declares         -> la fin du transfert n'est pas arrivee ;
        //  - une desynchronisation ailleurs qu'en queue -> corruption, pas simple troncature.
        val closedCleanly = sessionStateClosed &&
            missing.isEmpty() &&
            incomplete.isEmpty() &&
            (declaredChunks == null || received.size >= declaredChunks)

        return Night(
            sessionHex = sessionHex,
            blocks = blocks,
            nominalRateHz = if (nominalRateHz > 0) nominalRateHz else DEFAULT_RATE_HZ,
            closedCleanly = closedCleanly,
            missingIndices = missing,
            incompleteChunks = incomplete,
            declaredChunks = declaredChunks,
            receivedChunks = received.size,
            corruptBlocks = scans.sumOf { it.second.corruptBlocks },
            resyncSkippedBytes = scans.sumOf { it.second.resyncSkippedBytes },
            truncatedTail = scans.any { it.second.truncatedTail },
            desynchronised = scans.any { it.second.desynchronised },
            anchor = anchor,
        )
    }

    /**
     * Les index manquants dans `[0, borne]`.
     *
     * La borne est le **max des index declares et recus** : si la montre a annonce 96 chunks et
     * qu'on n'en a que 40, il en manque 56, pas zero. Ne regarder que les recus donnerait
     * « aucun trou » a une nuit dont les deux tiers ne sont jamais arrives — le pire des faux
     * negatifs, parce qu'il est silencieux.
     */
    fun missingIndices(received: List<Int>, declaredChunks: Int?): List<Int> {
        val maxReceived = received.maxOrNull() ?: -1
        val upper = maxOf(maxReceived, (declaredChunks ?: 0) - 1)
        if (upper < 0) return emptyList()
        val present = received.toHashSet()
        return (0..upper).filterNot { it in present }
    }

    /** `00042.pendulum` -> 42. `null` si le nom ne suit pas la convention. */
    fun indexOf(file: File): Int? = file.name.removeSuffix(".pendulum").toIntOrNull()

    /** Cadence de repli quand aucun en-tete n'a pu etre lu. `:algo` recalcule `fs` de toute facon. */
    const val DEFAULT_RATE_HZ = 50
}
