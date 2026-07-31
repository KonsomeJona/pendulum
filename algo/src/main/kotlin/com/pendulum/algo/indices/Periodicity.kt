package com.pendulum.algo.indices

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.PiResult
import com.pendulum.algo.model.SleepMask

/**
 * Periodicity Index de Ferri.
 *
 * # La convention retenue, et pourquoi il faut en choisir une
 *
 * Deux formes contradictoires du PI circulent — **dans les publications de Ferri lui-même**. Elles
 * diffèrent sur deux points indépendants, ce qui fait quatre variantes possibles :
 *
 *  - la borne basse de la fenêtre de périodicité est-elle stricte (`10 < IMI`) ou inclusive
 *    (`10 ≤ IMI`) ? De même pour la borne haute ;
 *  - le numérateur compte-t-il des **intervalles** qualifiants, ou des **mouvements** appartenant à
 *    une séquence périodique (`PLMS_alt / LMS_total`, forme légèrement supérieure) ?
 *
 * Les deux formes donnent des valeurs différentes sur la même nuit. Mélangées, elles produisent une
 * tendance dans laquelle une partie de la variation observée n'est qu'un changement de définition.
 *
 * **Convention de ce module, unique et jamais mélangée** ([PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE]) :
 *
 * ```
 * IMI_k = onset_{k+1} − onset_k                        k = 1..N−1   (N = CLM pendant le sommeil)
 * qualifiant(k)  ⟺  imiLowExclusiveSec < IMI_k ≤ imiHighInclusiveSec     (10 s exclu, 90 s inclus)
 * Découper la suite des IMI en séquences maximales d'intervalles qualifiants CONSÉCUTIFS.
 * PI = ( Σ longueur(R) pour toute séquence R de longueur ≥ minRunLength ) / (N − 1)
 * ```
 *
 * — **borne basse stricte, borne haute inclusive** ;
 * — **numérateur en intervalles**, pas en mouvements ;
 * — longueur minimale de séquence = **3 intervalles**, soit 4 mouvements (Ferri 2006), ce qui aligne
 *   le PI sur la règle de série clinique (≥ 4 CLM) ;
 * — fenêtre **10–90 s**, pas 10–50 s.
 *
 * La convention est reproduite dans [PiDetail.convention] pour qu'un résultat archivé reste
 * interprétable même si ce fichier change un jour. Un changement de convention **doit** bumper le
 * `paramsHash` et déclencher un rescore de toutes les nuits (garde-fou nº 3 de `SPEC-v2.md` §3).
 *
 * # Le garde-fou de taux
 *
 * Sous [PeriodicityConfig.minLmRatePerHour] mouvements par heure, le PI est **ininterprétable**
 * (Drakatos 2021) : son dénominateur `N − 1` devient si petit qu'une poignée d'intervalles décide
 * de tout. C'est exactement ce qui explique l'instabilité publiée du groupe témoin (0,092 ± 0,152
 * chez Ferri 2022 contre 0,220 ± 0,229 chez Mogavero 2024). Sous ce taux, [PiResult.valid] est faux
 * et la valeur ne doit pas être affichée.
 *
 * # Ce que le PI ne sauve pas
 *
 * Il ne discrimine **pas** les mouvements liés à la respiration : les RRLM sont périodiques eux
 * aussi, et le cycle apnéique (25–45 s) recouvre le mode PLMS (22–26 s). Le PI n'est pas un
 * garde-fou anti-RRLM et ne doit jamais être présenté comme tel (§3.5).
 *
 * Valeurs de référence : seuil diagnostique ≈ **0,50** ; SJSR 0,601 ± 0,189 ; témoins 0,092 ± 0,152.
 */

/** Une seule valeur aujourd'hui : le type existe pour rendre la convention explicite à l'archivage. */
enum class PiConvention {
    /** Borne basse stricte, borne haute inclusive, numérateur en intervalles. */
    FERRI_INTERVALS_LOW_EXCLUSIVE,
}

data class PeriodicityConfig(
    /** 10 s **exclu** : un IMI de exactement 10,0 s ne qualifie pas. */
    val imiLowExclusiveSec: Double = 10.0,
    /** 90 s **inclus** : un IMI de exactement 90,0 s qualifie. */
    val imiHighInclusiveSec: Double = 90.0,
    /** En **intervalles** (3 intervalles = 4 mouvements). */
    val minRunLength: Int = 3,
    /** Sous ce taux de LM par heure de sommeil analysable, le PI est ininterprétable. */
    val minLmRatePerHour: Double = 10.0,
)

/**
 * Détail du calcul, pour l'export et les tests. [PiResult] reste la sortie contractuelle ;
 * ce type ajoute ce qui permet de vérifier *comment* le chiffre a été obtenu.
 */
data class PiDetail(
    val pi: PiResult,
    val convention: PiConvention,
    val sleepClmCount: Int,
    val totalIntervals: Int,
    val qualifyingIntervals: Int,
    val intervalsInCountedRuns: Int,
    val runCount: Int,
    val longestRunLength: Int,
    /** Dénominateur du seul garde-fou de taux — le PI lui-même n'a **pas** de dénominateur temporel. */
    val denominatorMin: Double,
)

object Periodicity {

    /** Convention en clair, à recopier dans l'export et le rapport médical. */
    const val CONVENTION_DOC: String =
        "PI de Ferri, convention Pendulum : intervalles qualifiants 10 s (exclu) < IMI <= 90 s (inclus), " +
            "sequences maximales d'au moins 3 intervalles consecutifs, numerateur en INTERVALLES " +
            "(jamais en mouvements), denominateur N-1 sur les CLM de sommeil."

    /**
     * Point d'entrée aligné sur `ALGO-v2.md` §4.3.
     *
     * @param clms tous les CLM candidats en ordre chronologique ; seuls les retenus (`isClm`) et
     *   situés dans une époque de sommeil entrent dans le calcul.
     * @param fsHz conservé pour la stabilité de l'API ; les instants viennent de `Clm.onsetMsRel`.
     */
    fun ferriIndex(
        clms: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiResult = detail(clms, mask, fsHz, cfg).pi

    /** Même calcul que [ferriIndex], mais avec le détail des séquences. */
    fun detail(
        clms: List<Clm>,
        mask: SleepMask,
        fsHz: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiDetail {
        require(fsHz > 0.0) { "fsHz doit etre > 0" }
        val lookup = SleepLookup(mask.windows)
        val onsets = ArrayList<Long>(clms.size)
        for (c in clms) {
            if (!c.isClm) continue
            if (!lookup.isSleepAt(c.onsetMsRel)) continue
            onsets.add(c.onsetMsRel)
        }
        val imi = DoubleArray(maxOf(0, onsets.size - 1)) {
            (onsets[it + 1] - onsets[it]) / 1000.0
        }
        return fromIntervals(imi, onsets.size, mask.analysableTstMin, cfg)
    }

    /**
     * Cœur du calcul, exposé pour les tests et pour le mode incrémental.
     *
     * @param imiSec intervalles onset-à-onset **consécutifs**, en secondes, dans l'ordre.
     * @param sleepClmCount `N`, nombre de CLM de sommeil ayant produit ces intervalles.
     * @param analysableSleepMin dénominateur du **seul** garde-fou de taux (le PI n'en a pas).
     */
    fun fromIntervals(
        imiSec: DoubleArray,
        sleepClmCount: Int,
        analysableSleepMin: Double,
        cfg: PeriodicityConfig = PeriodicityConfig(),
    ): PiDetail {
        require(cfg.minRunLength >= 1) { "minRunLength doit etre >= 1" }
        val total = imiSec.size

        var qualifying = 0
        var counted = 0
        var runCount = 0
        var longest = 0
        var run = 0
        // Balayage unique : on ferme la sequence courante des qu'un intervalle ne qualifie plus.
        // `i == total` est un tour de fermeture, pour ne pas dupliquer le code apres la boucle.
        for (i in 0..total) {
            val qualifies = i < total &&
                imiSec[i] > cfg.imiLowExclusiveSec && imiSec[i] <= cfg.imiHighInclusiveSec
            if (qualifies) {
                run++
                qualifying++
            } else if (run > 0) {
                if (run > longest) longest = run
                if (run >= cfg.minRunLength) {
                    counted += run
                    runCount++
                }
                run = 0
            }
        }

        val hours = analysableSleepMin / 60.0
        val rate = if (hours > 0.0) sleepClmCount / hours else 0.0
        val piValue = if (total > 0) counted.toDouble() / total else 0.0
        // Trois conditions independantes, toutes necessaires :
        //  - assez d'intervalles pour qu'une sequence de minRunLength puisse seulement exister ;
        //  - un denominateur temporel connu pour evaluer le taux ;
        //  - un taux de LM au-dessus du plancher d'interpretabilite.
        val valid = total >= cfg.minRunLength && hours > 0.0 && rate >= cfg.minLmRatePerHour

        return PiDetail(
            pi = PiResult(
                periodicityIndex = piValue,
                valid = valid,
                totalIntervals = total,
                lmRatePerHour = rate,
            ),
            convention = PiConvention.FERRI_INTERVALS_LOW_EXCLUSIVE,
            sleepClmCount = sleepClmCount,
            totalIntervals = total,
            qualifyingIntervals = qualifying,
            intervalsInCountedRuns = counted,
            runCount = runCount,
            longestRunLength = longest,
            denominatorMin = analysableSleepMin,
        )
    }
}
