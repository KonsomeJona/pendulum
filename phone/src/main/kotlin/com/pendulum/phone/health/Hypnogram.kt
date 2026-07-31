package com.pendulum.phone.health

import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.phone.ingest.TimeAnchor
import com.pendulum.phone.work.AnalysisParams

/**
 * Traduction d'un hypnogramme Health Connect vers ce que `:algo` sait lire.
 *
 * Deux operations, et chacune est un piege documente :
 *  - le **changement de repere temporel** (horloge murale UTC -> millisecondes relatives a la
 *    ligne de temps du capteur), qui passe par [TimeAnchor] et jamais par une soustraction
 *    d'horloges murales ;
 *  - le traitement des **trous**, que l'API autorise explicitement entre deux stades.
 */
object Hypnogram {

    // Constantes de SleepSessionRecord, recopiees pour que ce fichier reste lisible sans l'API.
    const val STAGE_UNKNOWN = 0
    const val STAGE_AWAKE = 1
    const val STAGE_SLEEPING = 2
    const val STAGE_OUT_OF_BED = 3
    const val STAGE_LIGHT = 4
    const val STAGE_DEEP = 5
    const val STAGE_REM = 6
    const val STAGE_AWAKE_IN_BED = 7

    /** `debutMs:finMs:type;...`, horloge murale UTC. Format de `hc_snapshot.selectedStagesCsv`. */
    fun encodeCsv(stages: List<SleepSourceSelector.StageSpan>): String =
        stages.joinToString(";") { "${it.startMs}:${it.endMs}:${it.stageType}" }

    fun decodeCsv(csv: String): List<SleepSourceSelector.StageSpan> {
        if (csv.isBlank()) return emptyList()
        return csv.split(';').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 3) return@mapNotNull null
            val a = f[0].toLongOrNull() ?: return@mapNotNull null
            val b = f[1].toLongOrNull() ?: return@mapNotNull null
            val t = f[2].toIntOrNull() ?: return@mapNotNull null
            SleepSourceSelector.StageSpan(a, b, t)
        }
    }

    /**
     * Conversion en fenetres exploitables par `:algo`.
     *
     * ### Les trous
     *
     * L'API autorise les trous entre deux stades. Le choix de ce qu'on en fait **change le
     * denominateur, donc l'index, donc potentiellement une decision** : compter un trou comme du
     * sommeil gonfle le TST et deflate l'index ; le compter comme de l'eveil fait l'inverse.
     * C'est pourquoi ce n'est pas une valeur en dur mais un parametre trace dans le hash
     * ([AnalysisParams.hypnogramHolePolicy]), avec `EXCLUDE` par defaut — un trou n'est pas une
     * information, et le remplir revient a en inventer une.
     *
     * ### Le cas « duree seule »
     *
     * Une source peut ecrire une session sans aucun stade (piege n°1 : techniquement conforme,
     * inutile pour le controle de plausibilite). On produit alors une unique fenetre
     * [Stage.SLEEP] couvrant la session : c'est exactement l'information disponible, ni plus —
     * fabriquer une repartition leger/profond/REM sortie de nulle part serait pire que rien.
     */
    fun toWindows(
        stages: List<SleepSourceSelector.StageSpan>,
        sessionStartMs: Long,
        sessionEndMs: Long,
        anchor: TimeAnchor,
        holePolicy: AnalysisParams.HolePolicy,
    ): List<SleepWindow> {
        if (stages.isEmpty()) {
            return listOf(
                SleepWindow(anchor.toMsRel(sessionStartMs), anchor.toMsRel(sessionEndMs), Stage.SLEEP)
            )
        }

        val sorted = stages.sortedBy { it.startMs }
        val out = ArrayList<SleepWindow>(sorted.size * 2)
        var cursor = sessionStartMs

        for (s in sorted) {
            if (s.startMs > cursor) fillHole(out, cursor, s.startMs, anchor, holePolicy)
            out += SleepWindow(anchor.toMsRel(s.startMs), anchor.toMsRel(s.endMs), stageOf(s.stageType))
            cursor = maxOf(cursor, s.endMs)
        }
        if (cursor < sessionEndMs) fillHole(out, cursor, sessionEndMs, anchor, holePolicy)
        return out
    }

    private fun fillHole(
        out: MutableList<SleepWindow>,
        fromMs: Long,
        toMs: Long,
        anchor: TimeAnchor,
        policy: AnalysisParams.HolePolicy,
    ) {
        val stage = when (policy) {
            // EXCLUDE : on emet quand meme une fenetre, marquee UNKNOWN. Ne rien emettre du tout
            // laisserait `:algo` interpoler entre les deux stades voisins, ce qui reviendrait a
            // choisir en silence exactement ce qu'on refuse de choisir.
            AnalysisParams.HolePolicy.EXCLUDE -> Stage.UNKNOWN
            AnalysisParams.HolePolicy.AS_SLEEP -> Stage.SLEEP
            AnalysisParams.HolePolicy.AS_WAKE -> Stage.WAKE
        }
        out += SleepWindow(anchor.toMsRel(fromMs), anchor.toMsRel(toMs), stage)
    }

    fun stageOf(hcStage: Int): Stage = when (hcStage) {
        STAGE_AWAKE -> Stage.WAKE
        STAGE_SLEEPING -> Stage.SLEEP
        STAGE_OUT_OF_BED -> Stage.OUT_OF_BED
        STAGE_LIGHT -> Stage.LIGHT
        STAGE_DEEP -> Stage.DEEP
        STAGE_REM -> Stage.REM
        STAGE_AWAKE_IN_BED -> Stage.AWAKE_IN_BED
        else -> Stage.UNKNOWN
    }
}
