package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.dsp.ThresholdParams
import com.pendulum.algo.dsp.Thresholds
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.max

/**
 * Seuils de detection vus par le detecteur (`docs/fr/ALGO-v2.md` §2 etape 4, parametres §6.3).
 *
 * Jumelle de `com.pendulum.algo.dsp.ThresholdParams`, qui porte les memes quatre valeurs : `dsp` calcule
 * les courbes, `detect` decide. Le detecteur convertit sa configuration en [ThresholdParams] plutot
 * que l'inverse, pour que `dsp` ne depende jamais de `detect`.
 *
 * **Sur `kOn = 8,0`.** Ce KDoc a longtemps decrit 8,0 comme un budget anti-artefact herite de la
 * premiere specification « faute de mieux ». Le balayage de `ThresholdPolicySweepTest` — `k_on` de 4
 * a 12, pas de 1,0, 20 graines — contredit les deux moities de cette phrase, et il faut les deux :
 *
 *  - **`k_on` n'est pas libre.** Les trois criteres de T5 ne tiennent qu'a 8,0 : la sensibilite au
 *    point « 8x » vaut 1,000 pour `k_on <= 7` et 0,000 pour `k_on >= 9`. La raison doit etre lue en
 *    entier, parce qu'elle n'est pas a l'honneur de T5 : l'abscisse de T5 est le plancher **effectif**
 *    `Theta_on / k_on`, et sur sa nuit calme et non calibree c'est `Theta_abs` qui commande le seuil.
 *    « 8x le plancher effectif » vaut donc `8 x Theta_abs / k_on`, qui n'egale le seuil que si
 *    `k_on = 8`. **T5 ne valide pas 8,0, il l'inscrit dans son propre enonce.** C'est une raison de ne
 *    pas deplacer `k_on` sans reecrire T5 ; ce n'est pas une preuve que 8,0 soit la bonne valeur.
 *  - **`k_on` n'est pas le parametre dominant sur une nuit calibree.** `Theta_on` y reste fige a
 *    53,7 mg de `k_on = 4` a `k_on = 12`, parce que le troisieme terme — `f_cal x gainCal` — l'emporte
 *    sur toute la plage. Le rappel de T6 (0,265 a 0,276) et le sous-comptage de T22 (0,697) ne bougent
 *    pas du balayage. Ce qui gouverne le sous-comptage est `calFraction`, pas `k_on`.
 *
 * Ce que `k_on` change reellement sur une nuit calibree passe par [ClmConfig.grossBodyFactor], dont
 * l'amplitude de reference est `Theta_on / k_on` : la precision au-dessus du seuil monte de 0,768 a
 * 0,956 sur le balayage tandis que la sensibilite reste plate a ~0,90. `k_on` y agit donc comme un
 * reglage de rejet des mouvements corporels grossiers, ce qui est l'inverse de la lecture habituelle.
 *
 * Reste vrai de la phrase d'origine : 8,0 n'est pas dicte par le bruit thermique, 4,8 y suffirait.
 * Le tableau complet est dans `docs/07-validation.md` §4.1.
 *
 * @param kOn multiplicateur du plancher a l'attaque (plage 5-12). Fixe a 8,0 : voir ci-dessus.
 * @param kOff multiplicateur du plancher au relachement (plage 2,0-4,0). Hysteresis = kOn/kOff.
 * @param absFloorG plancher absolu, en g (plage 0,010-0,050).
 * @param calFraction fraction du gain de calibration (plage 0,08-0,20).
 */
data class ThresholdConfig(
    val kOn: Double = 8.0,
    val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f,
    val calFraction: Double = 0.12,
) {
    fun toParams(): ThresholdParams = ThresholdParams(kOn, kOff, absFloorG, calFraction)
}

/**
 * Parametres de la detection des LM et de leur classification en CLM (§2 etape 5, §6.3).
 *
 * `offHoldSec`, `minDurSec` et `maxDurSec` sont marques **fixe** dans le tableau §6.3 (regle
 * AASM/WASM litterale) : ils sont lisibles mais **non constructibles**, pour qu'aucun appelant ne
 * puisse les faire varier. La variation des regles cliniques est le test T14 — qui change de jeu de
 * regles dans [SeriesConfig] — pas T12 qui balaie les parametres de traitement.
 *
 * @param morphologyWinSec fenetre du critere de morphologie WASM 3.2.1-d (plage 0,3-0,8).
 * @param grossBodyFactor seuil de mouvement corporel grossier, en x plancher effectif (plage 25-60).
 * @param refractorySec periode refractaire de part et d'autre d'un GBM (plage 1-4).
 * @param minExcursionDeg sous cette excursion de `tilt`, marquage `TRANSMITTED_SUSPECT` (plage 0,5-4).
 */
data class ClmConfig(
    val thresholds: ThresholdConfig = ThresholdConfig(),
    val morphologyWinSec: Double = 0.50,
    val grossBodyFactor: Double = 40.0,
    val refractorySec: Double = 2.0,
    val minExcursionDeg: Double = 1.5,
) {
    /** Duree de maintien sous le seuil de sortie qui date l'offset. Fixe, clinique. */
    val offHoldSec: Double get() = OFF_HOLD_SEC

    /** Duree minimale d'un LM. Fixe (AASM VII / WASM 3.3.1). */
    val minDurSec: Double get() = MIN_DUR_SEC

    /** Duree maximale d'un CLM. Fixe. Au-dela : LM long, qui **casse** la serie. */
    val maxDurSec: Double get() = MAX_DUR_SEC

    companion object {
        const val OFF_HOLD_SEC: Double = 0.50
        const val MIN_DUR_SEC: Double = 0.50
        const val MAX_DUR_SEC: Double = 10.0

        /** §3.2 : borne de duree du marqueur `TRANSMITTED_SUSPECT`. Constante d'ingenierie. */
        const val TRANSMITTED_MAX_DUR_SEC: Double = 1.50
    }
}

/**
 * Machine d'etats de detection des LM, puis classification en CLM. `docs/fr/ALGO-v2.md` §2 etape 5.
 *
 * Deroulement, litteral :
 *  1. onset provisoire au franchissement de `Theta_on` sur l'enveloppe **grossiere** ;
 *  2. offset provisoire au **debut** d'une periode d'au moins `offHoldSec` sous `Theta_off` — c'est
 *     la lettre de la regle AASM/WASM (« the START of a period lasting at least 0.5 s during which
 *     the EMG does not exceed... »), et c'est aussi ce qui **fusionne** deux bouffees separees de
 *     moins de 0,5 s sans etape de fusion dediee. §1.5-iv : la regle de « fusion des CLM espaces
 *     < 0,5 s » de la v1 est une confusion avec la regle de combinaison **bilaterale**, qui ne
 *     s'applique pas a une jambe unique ; correctement implemente, l'offset le fait tout seul ;
 *  3. recalage des deux fronts sur l'enveloppe **fine** ;
 *  4. critere de morphologie WASM 3.2.1-d : une fenetre de `morphologyWinSec` dont la **mediane**
 *     de l'enveloppe fine depasse `Theta_off`. C'est le meilleur filtre anti-matelas disponible dans
 *     le corpus des regles (§3.2) : une vibration transmise est une sonnerie de 0,05-0,4 s, crete
 *     elevee et mediane faible, et c'est le seul filtre de ce catalogue qui soit une regle clinique
 *     publiee plutot qu'une invention ;
 *  5. classification par la duree, puis GBM avec periode refractaire, posture, zone aveugle.
 *
 * Ordre de priorite du motif de rejet unique porte par [Clm.reject] :
 * `MORPHOLOGY` > `TRUNCATED` > `TOO_SHORT` > `BLIND_ZONE` > `POSTURAL` > `GROSS_BODY`.
 * La morphologie passe avant la duree parce que la specification l'evalue a l'etape 5.4, avant la
 * classification 5.5 ; consequence a connaitre : avec `morphologyWinSec == minDurSec` (defaut),
 * `TOO_SHORT` n'est atteignable qu'en abaissant `morphologyWinSec`. Les **drapeaux**, eux, sont
 * cumulatifs : un evenement peut porter a la fois `POSTURAL` et `GROSS_BODY`.
 *
 * Tous les evenements de la machine d'etats sont renvoyes, y compris les rejetes : le rapport de
 * qualite en a besoin, et [SeriesBuilder] a besoin des `LM_LONG` et des `TRUNCATED` pour casser les
 * series au bon endroit. Les consommateurs filtrent avec [Clm.isClm].
 */
object ClmDetector {

    fun detect(
        env: DualEnvelope,
        floor: Signal1D,
        floorExtrapolated: BooleanArray,
        gravity: TriAxial,
        segments: List<Segment>,
        blindZones: List<Segment>,
        postures: List<PostureChange>,
        calibration: NightCalibration,
        cfg: ClmConfig = ClmConfig(),
        postureCfg: PostureConfig = PostureConfig(),
    ): List<Clm> {
        val n = env.coarse.n
        require(env.coarse.fsHz > 0.0) { "fsHz doit etre > 0" }
        require(env.fine.n == n && floor.n == n && floorExtrapolated.size == n) {
            "enveloppes, plancher et drapeaux doivent partager la grille"
        }
        require(gravity.n == n) { "la gravite doit partager la grille des enveloppes" }
        return Detection(
            env, floor, floorExtrapolated, gravity, blindZones, postures, calibration, cfg, postureCfg,
        ).detectAll(segments)
    }
}

/** Etat de travail d'un appel a [ClmDetector.detect]. Rien n'en sort, rien n'y entre : pur. */
private class Detection(
    env: DualEnvelope,
    floor: Signal1D,
    private val floorExtrapolated: BooleanArray,
    private val gravity: TriAxial,
    private val blindZones: List<Segment>,
    private val postures: List<PostureChange>,
    calibration: NightCalibration,
    private val cfg: ClmConfig,
    private val postureCfg: PostureConfig,
) {
    private val coarse = env.coarse.v
    private val fine = env.fine.v
    private val fl = floor.v
    private val fs = env.coarse.fsHz
    private val params = cfg.thresholds.toParams()
    private val gain = calibration.gainCalG

    private val offHold = samplesOf(cfg.offHoldSec, fs).coerceAtLeast(1)
    private val morphoWin = samplesOf(cfg.morphologyWinSec, fs).coerceAtLeast(1)
    private val minDur = samplesOf(cfg.minDurSec, fs)
    private val maxDur = samplesOf(cfg.maxDurSec, fs)
    private val tau = samplesOf(postureCfg.tauSec, fs)
    private val guard = samplesOf(postureCfg.guardSec, fs)
    private val refractory = samplesOf(cfg.refractorySec, fs)
    private val transmittedMaxDur = samplesOf(ClmConfig.TRANSMITTED_MAX_DUR_SEC, fs)

    /** Tampon de travail des medianes glissantes : evite une allocation par fenetre. */
    private var scratch = FloatArray(morphoWin.coerceAtLeast(64))

    private fun on(i: Int): Float = Thresholds.onAt(fl[i], gain, params)
    private fun off(i: Int): Float = Thresholds.offAt(fl[i], gain, params)

    /**
     * Plancher **effectif** : `Theta_on / kOn`. C'est lui, et non `floor` brut, qui sert de reference
     * d'amplitude au critere GBM — sinon un plancher nul rendrait le critere degenere (tout
     * evenement satisferait `crete >= 40 x 0`).
     */
    private fun effectiveFloor(i: Int): Float = (on(i) / params.kOn).toFloat()

    private fun scratchOf(len: Int): FloatArray {
        if (scratch.size < len) scratch = FloatArray(len)
        return scratch
    }

    fun detectAll(segments: List<Segment>): List<Clm> {
        val raw = ArrayList<Clm>()
        for (seg in segments) {
            var i = seg.fromIdx
            while (i < seg.toIdx) {
                if (!(coarse[i] >= on(i))) {
                    i++
                    continue
                }
                val onsetProv = i

                // --- 2. offset provisoire : DEBUT de la premiere periode de `offHoldSec` sous Theta_off.
                var quietStart = -1
                var offsetProv = -1
                var resume = seg.toIdx
                var j = onsetProv
                while (j < seg.toIdx) {
                    if (coarse[j] < off(j)) {
                        if (quietStart < 0) quietStart = j
                        if (j - quietStart + 1 >= offHold) {
                            offsetProv = quietStart
                            resume = j + 1
                            break
                        }
                    } else {
                        quietStart = -1
                    }
                    j++
                }
                val truncatedEnd = offsetProv < 0
                if (truncatedEnd) offsetProv = seg.toIdx

                // --- 3. recalage des fronts sur l'enveloppe fine.
                var k = onsetProv
                while (k >= seg.fromIdx && !(fine[k] < off(k))) k--
                val truncatedStart = k < seg.fromIdx
                val onsetIdx = if (truncatedStart) seg.fromIdx else minOf(onsetProv, k + 1)

                val offsetIdx: Int
                if (truncatedEnd) {
                    offsetIdx = seg.toIdx
                } else {
                    var m = offsetProv - 1
                    while (m > onsetIdx && !(fine[m] >= off(m))) m--
                    offsetIdx = (m + 1).coerceIn(onsetIdx + 1, seg.toIdx)
                }

                raw += classify(onsetIdx, offsetIdx, truncatedStart || truncatedEnd, seg)
                i = max(resume, onsetIdx + 1)
            }
        }
        return applyRefractory(raw)
    }

    /**
     * §2 etape 5.6 : periode refractaire de part et d'autre d'un mouvement corporel grossier. Elle
     * ne s'applique qu'aux evenements par ailleurs acceptes — un evenement deja rejete garde son
     * motif d'origine, plus informatif pour le rapport de qualite.
     */
    private fun applyRefractory(raw: List<Clm>): List<Clm> {
        val zones = raw.asSequence()
            .filter { (it.flags and ClmFlags.GROSS_BODY) != 0 }
            .map { Segment(it.onsetIdx - refractory, it.offsetIdx + refractory) }
            .toList()
        if (zones.isEmpty()) return raw
        return raw.map { e ->
            if (e.reject == null && (e.flags and ClmFlags.GROSS_BODY) == 0 &&
                zones.any { overlaps(e.onsetIdx, e.offsetIdx, it) }
            ) {
                e.copy(reject = ClmRejectReason.GROSS_BODY)
            } else {
                e
            }
        }
    }

    private fun classify(onsetIdx: Int, offsetIdx: Int, truncated: Boolean, seg: Segment): Clm {
        val durSamples = offsetIdx - onsetIdx
        var flags = 0

        // Amplitudes. La crete est prise sur l'enveloppe de decision (grossiere) ; la mediane sur
        // l'enveloppe fine, qui est la grandeur du critere de morphologie.
        var peak = 0f
        var peakIdx = onsetIdx
        for (t in onsetIdx until offsetIdx) {
            val v = coarse[t]
            if (v.isFinite() && v > peak) {
                peak = v
                peakIdx = t
            }
        }
        val medianAmp = Numeric.median(fine, onsetIdx, offsetIdx, scratchOf(durSamples))

        // Caracteristiques extraites de g : elles portent l'information que le passe-haut jette
        // (§1.2). tiltChange = changement net et persistant ; tiltExcursion = excursion transitoire.
        val refIdx = (onsetIdx - tau).coerceAtLeast(seg.fromIdx)
        val postIdx = (offsetIdx - 1 + tau).coerceIn(seg.fromIdx, seg.toIdx - 1)
        val tiltChange = Gravity.angleDeg(gravity, refIdx, postIdx).let { if (it.isNaN()) 0f else it }
        var excursion = 0f
        for (t in onsetIdx until offsetIdx) {
            val a = Gravity.angleDeg(gravity, refIdx, t)
            if (!a.isNaN() && a > excursion) excursion = a
        }

        for (t in onsetIdx until offsetIdx) {
            if (floorExtrapolated[t]) {
                flags = flags or ClmFlags.FLOOR_EXTRAPOLATED
                break
            }
        }
        flags = flags or Thresholds.dominanceAt(fl[onsetIdx], gain, params)

        val morphoOk = morphologyOk(onsetIdx, offsetIdx)
        val inBlind = blindZones.any { overlaps(onsetIdx, offsetIdx, it) }
        if (inBlind) flags = flags or ClmFlags.IN_BLIND_ZONE
        if (truncated) flags = flags or ClmFlags.TRUNCATED
        if (durSamples > maxDur) flags = flags or ClmFlags.LM_LONG

        val postural = postures.any { onsetIdx >= it.atIdx - guard && onsetIdx <= it.atIdx + guard }
        if (postural) flags = flags or ClmFlags.POSTURAL

        // §3.2, defense nº 2 : rapporte, jamais exclu. Une dorsiflexion isolee produit aussi une
        // excursion quasi nulle au capteur (mecanisme de Terrill) ; exclure ces evenements
        // aggraverait le biais a la baisse deja present — deux erreurs dans le meme sens.
        if (excursion < cfg.minExcursionDeg && durSamples < transmittedMaxDur) {
            flags = flags or ClmFlags.TRANSMITTED_SUSPECT
        }

        val gbm = peak >= cfg.grossBodyFactor * effectiveFloor(peakIdx) ||
            tiltChange > postureCfg.postureDeg ||
            durSamples > maxDur
        if (gbm) flags = flags or ClmFlags.GROSS_BODY

        val reject = when {
            !morphoOk -> ClmRejectReason.MORPHOLOGY
            truncated -> ClmRejectReason.TRUNCATED
            durSamples < minDur -> ClmRejectReason.TOO_SHORT
            inBlind -> ClmRejectReason.BLIND_ZONE
            postural -> ClmRejectReason.POSTURAL
            gbm -> ClmRejectReason.GROSS_BODY
            else -> null
        }

        return Clm(
            onsetIdx = onsetIdx,
            offsetIdx = offsetIdx,
            onsetMsRel = Math.round(onsetIdx * 1000.0 / fs),
            durationMs = Math.round(durSamples * 1000.0 / fs).toInt(),
            peakAmpG = peak,
            medianAmpG = medianAmp,
            noiseFloorG = effectiveFloor(onsetIdx),
            thresholdOnG = on(onsetIdx),
            thresholdOffG = off(onsetIdx),
            tiltChangeDeg = tiltChange,
            tiltExcursionDeg = excursion,
            flags = flags,
            reject = reject,
        )
    }

    /** Critere WASM 3.2.1-d : au moins une fenetre de `morphoWin` dont la mediane depasse Theta_off. */
    private fun morphologyOk(from: Int, to: Int): Boolean {
        if (to - from < morphoWin) return false
        val buf = scratchOf(morphoWin)
        var s = from
        while (s + morphoWin <= to) {
            if (Numeric.median(fine, s, s + morphoWin, buf) >= off(s + morphoWin / 2)) return true
            s++
        }
        return false
    }

    private fun overlaps(from: Int, to: Int, seg: Segment): Boolean =
        from < seg.toIdx && to > seg.fromIdx
}
