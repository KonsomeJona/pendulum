package com.pendulum.algo.detect

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.PlmSeries
import com.pendulum.algo.model.SeriesRule
import com.pendulum.algo.model.ShortImiPolicy
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.Stage

/**
 * Un jeu de regles cliniques, transcrit **litteralement** depuis le tableau §6.5 de `ALGO-v2.md`.
 *
 * Ces valeurs ne sont pas des reglages : la variation legitime est le test T14, qui compare les deux
 * jeux, pas T12 qui balaie les parametres de traitement. Utiliser [aasmV3] et [wasm2016].
 *
 * Correction majeure de la v2 sur la v1 (§1.5-ii) : la v1 croyait que les deux jeux ne different que
 * par la borne basse de l'IMI (5 s contre 10 s). **C'est le point le moins important des deux.**
 * Ferri et al. (Sleep Med 2015, 107 SJSR + 63 temoins) ont isole les deux effets : monter la borne
 * basse seule (« Alt1 ») ne change presque rien, « only the Alt2 algorithm » — borne basse **plus**
 * rupture de serie sur IMI court — « provided significantly different results ». La difference
 * structurante est donc [shortImiPolicy], secondee par [breakOnLongLm] et [requirePortionInSleep].
 */
data class SeriesConfig(
    val rule: SeriesRule,
    val imiMinSec: Double,
    val imiMaxSec: Double = 90.0,
    val minClmPerSeries: Int = 4,
    val shortImiPolicy: ShortImiPolicy,
    val breakOnLongLm: Boolean,
    val requirePortionInSleep: Boolean,
) {
    companion object {
        /**
         * AASM v3. IMI onset-a-onset dans [5, 90] s ; >= 4 CLM ; au moins une partie de chaque CLM
         * doit tomber dans une epoque de sommeil (nouveaute v3). L'AASM est **muette** sur l'IMI
         * court : la convention WASM 2006 (« le mouvement posterieur est ignore et la periode est
         * calculee jusqu'au candidat suivant ») est retenue par defaut et etiquetee comme une
         * interpretation, pas comme une regle publiee.
         */
        fun aasmV3() = SeriesConfig(
            rule = SeriesRule.AASM_V3,
            imiMinSec = 5.0,
            imiMaxSec = 90.0,
            minClmPerSeries = 4,
            shortImiPolicy = ShortImiPolicy.SKIP_LATER,
            breakOnLongLm = false,
            requirePortionInSleep = true,
        )

        /**
         * WASM 2016. IMI dans [10, 90] s ; >= 4 CLM (= 3 IMI) ; un IMI hors bornes **casse** la serie
         * (3.3.6) ; un LM > 10 s casse la serie (3.2.1 : « LM now have no maximum length. A LM > 10 s
         * now ends a PLM sequence. ») ; une serie peut **traverser** une transition veille/sommeil
         * (2.4.4), d'ou `requirePortionInSleep = false`.
         */
        fun wasm2016() = SeriesConfig(
            rule = SeriesRule.WASM_2016,
            imiMinSec = 10.0,
            imiMaxSec = 90.0,
            minClmPerSeries = 4,
            shortImiPolicy = ShortImiPolicy.BREAK_SERIES,
            breakOnLongLm = true,
            requirePortionInSleep = false,
        )
    }
}

/**
 * Series construites, plus le compteur des series abandonnees aux bords de la nuit.
 *
 * @param truncatedSeriesDropped series tronquees par un bord d'enregistrement qui n'atteignaient pas
 *   `minClmPerSeries` et sont donc abandonnees. **A rapporter** : c'est un biais a la baisse mesurable
 *   qui augmente sur une nuit interrompue, et il ne compense pas le biais a la hausse d'une nuit
 *   tronquee (§3.7.2 point 5) — il ne faut pas pretendre le contraire.
 */
data class SeriesBuildResult(val series: List<PlmSeries>, val truncatedSeriesDropped: Int)

/**
 * Construction des series PLM. `docs/ALGO-v2.md` §2 etape 6, regles §6.5.
 *
 * **Ce que cette classe ne fait pas, et c'est deliberе** : un mouvement manque ne coupe pas une serie.
 * Dans le regime typique (IMI ~21 s), rater un CLM double l'intervalle a ~42 s, ce qui reste dans la
 * fenetre [5, 90] s : la serie survit et seul le compte baisse. La rupture n'arrive que si l'intervalle
 * **fusionne** depasse `imiMaxSec`. Aucune heuristique de « protection » n'est ajoutee au-dessus de la
 * regle — ce serait inventer une regle clinique.
 */
object SeriesBuilder {

    private val SLEEP_STAGES = setOf(Stage.SLEEP, Stage.LIGHT, Stage.DEEP, Stage.REM)

    /**
     * @param events **tous** les evenements produits par [ClmDetector], rejetes compris : les
     *   `LM_LONG` et les `TRUNCATED` sont necessaires pour casser les series au bon endroit.
     * @param mask masque de sommeil ; sert a `requirePortionInSleep`, a `duringSleepFraction`, et a
     *   borner la nuit pour la detection des series tronquees.
     * @param fsHz frequence de la grille, explicite : les IMI sont calcules sur les **index**, jamais
     *   sur les champs en millisecondes, qui sont arrondis.
     * @return les series d'au moins `minClmPerSeries` CLM. `PlmSeries.clmIndices` indexe la liste
     *   `events` **telle que fournie**, de sorte que `events[i]` est toujours valide.
     */
    fun build(events: List<Clm>, mask: SleepMask, fsHz: Double, cfg: SeriesConfig): List<PlmSeries> =
        buildDetailed(events, mask, fsHz, cfg).series

    fun buildDetailed(
        events: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: SeriesConfig,
    ): SeriesBuildResult {
        require(fsHz > 0.0) { "fsHz doit etre > 0" }
        val order = events.indices.sortedBy { events[it].onsetIdx }
        val imiMaxMs = (cfg.imiMaxSec * 1000.0).toLong()
        val nightStartMs = mask.windows.minOfOrNull { it.startMsRel } ?: 0L
        val nightEndMs = mask.windows.maxOfOrNull { it.endMsRel }
            ?: events.maxOfOrNull { it.onsetMsRel + it.durationMs } ?: 0L

        val out = ArrayList<PlmSeries>()
        var dropped = 0
        val open = ArrayList<Int>()
        val imis = ArrayList<Float>()
        var openTruncStart = false
        var afterHardBreak = false
        var sawAnyClm = false

        fun close(truncatedAtEnd: Boolean) {
            if (open.isEmpty()) return
            if (open.size >= cfg.minClmPerSeries) {
                val inSleep = open.count { overlapsSleep(events[it], mask) }
                out += PlmSeries(
                    rule = cfg.rule,
                    clmIndices = open.toIntArray(),
                    imiSec = FloatArray(imis.size) { imis[it] },
                    truncatedAtStart = openTruncStart,
                    truncatedAtEnd = truncatedAtEnd,
                    duringSleepFraction = inSleep.toFloat() / open.size,
                )
            } else if (openTruncStart || truncatedAtEnd) {
                // Tronquee et incomplete : abandonnee, mais comptee (§2 etape 6, comportement aux bords).
                dropped++
            }
            open.clear()
            imis.clear()
            openTruncStart = false
        }

        fun openWith(idx: Int, hardBreak: Boolean) {
            open += idx
            openTruncStart = when {
                hardBreak -> true
                !sawAnyClm -> events[idx].onsetMsRel - nightStartMs < imiMaxMs
                else -> false
            }
            sawAnyClm = true
        }

        for (idx in order) {
            val e = events[idx]

            // Ruptures dures. Un LM > 10 s casse la serie en WASM (3.3.6) ; un evenement tronque par
            // un bord de segment la casse toujours — sa duree est inconnue, c'est le comportement
            // conservateur, et c'est deja la regle WASM 3.3.3 pour une reprise d'enregistrement.
            val truncatedEvent = (e.flags and ClmFlags.TRUNCATED) != 0
            val longLm = (e.flags and ClmFlags.LM_LONG) != 0
            if (truncatedEvent || (cfg.breakOnLongLm && longLm)) {
                close(truncatedAtEnd = truncatedEvent)
                afterHardBreak = truncatedEvent
                continue
            }
            if (!e.isClm) continue
            if (cfg.requirePortionInSleep && !overlapsSleep(e, mask)) continue

            if (open.isEmpty()) {
                openWith(idx, afterHardBreak)
                afterHardBreak = false
                continue
            }
            sawAnyClm = true
            val imiSec = (e.onsetIdx - events[open.last()].onsetIdx) / fsHz
            when {
                imiSec > cfg.imiMaxSec -> {
                    close(truncatedAtEnd = false)
                    openWith(idx, false)
                }

                imiSec >= cfg.imiMinSec -> {
                    open += idx
                    imis += imiSec.toFloat()
                }

                // IMI court : **le** parametre qui separe les deux jeux de regles.
                cfg.shortImiPolicy == ShortImiPolicy.BREAK_SERIES -> {
                    close(truncatedAtEnd = false)
                    openWith(idx, false)
                }

                // SKIP_LATER : le mouvement posterieur est ignore, la reference ne bouge pas, la
                // periode est mesuree jusqu'au candidat suivant.
                else -> Unit
            }
        }
        val lastOnsetMs = open.lastOrNull()?.let { events[it].onsetMsRel } ?: 0L
        close(truncatedAtEnd = nightEndMs - lastOnsetMs < imiMaxMs)
        return SeriesBuildResult(out, dropped)
    }

    /** Au moins une partie du CLM tombe dans une epoque de sommeil (regle AASM v3). */
    private fun overlapsSleep(e: Clm, mask: SleepMask): Boolean {
        val from = e.onsetMsRel
        val to = e.onsetMsRel + e.durationMs
        return mask.windows.any {
            it.stage in SLEEP_STAGES && from < it.endMsRel && to > it.startMsRel
        }
    }
}
