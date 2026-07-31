package com.pendulum.algo.mask

import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DiaryWindow
import com.pendulum.algo.model.MaskAgreement
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paramètres de la fusion (`docs/ALGO-v2.md` §3.6.4, tableau §6.6).
 *
 * @param maxLagMs demi-plage de recherche du décalage, ±10 min par défaut (plage 300–900 s).
 * @param lagStepMs pas de la recherche. Vaut la durée d'une époque : chercher plus fin que la
 *   grille sur laquelle on compare n'ajoute aucune information.
 * @param minCoverage recouvrement minimal exigé pour qu'un décalage candidat soit seulement
 *   *éligible*. Sans lui, le décalage maximal gagne toujours : en ne laissant qu'une poignée
 *   d'époques en vis-à-vis, il est trivial d'atteindre 100 % d'accord sur presque rien.
 */
data class FusionConfig(
    val maxLagMs: Long = 600_000,
    val lagStepMs: Long = 5_000,
    val minCoverage: Double = 0.50,
)

/**
 * Correction apprise du TST accélérométrique vers le TST de référence (§3.6.4, point 3).
 * `TST_HC ≈ alpha · TST_accel + beta`.
 */
data class TstCorrection(val alpha: Double, val beta: Double, val nNights: Int, val valid: Boolean)

/** Époque non renseignée par un masque. À ne jamais confondre avec « éveillé » : voir `binarize`. */
private const val UNDEFINED: Byte = -1

/**
 * Fusion du masque accélérométrique avec un hypnogramme externe et/ou un journal manuel
 * (`docs/ALGO-v2.md` §3.6.4 et §3.6.5).
 *
 * **La raison d'être de ce fichier est l'indépendance du dénominateur, pas la précision.**
 * `aPLM-i = numérateur(mouvement) / dénominateur(sommeil déduit de l'absence de mouvement)` : les
 * deux termes sortent du même signal et sont anti-corrélés par construction, donc la métrique
 * s'auto-amplifie. Health Connect casse la boucle parce qu'il est *indépendant* — autre poignet,
 * autre capteur, autre algorithme, autre appareil — et non parce qu'il apporte les stades. Le
 * journal manuel la casse encore plus complètement : deux champs saisis à la main ne dépendent
 * d'aucun signal.
 *
 * **Règle non négociable, appliquée ici et pas dans l'interface** (`SPEC-v2.md` §2.3) : un masque
 * [MaskSource.ACCEL_IMMOBILITY] est [DenominatorIndependence.CIRCULAR] et ne peut jamais porter le
 * résultat principal ni alimenter la tendance. Aucune fonction de ce fichier ne « promeut » son
 * indépendance : ni le recalage, ni la correction apprise ne transforment un dénominateur
 * circulaire en dénominateur indépendant — ils le rendent seulement moins biaisé, ce qui est une
 * tout autre propriété.
 *
 * Fonctions pures : aucune horloge murale, aucun aléa, aucune I/O.
 */
object MaskFusion {

    /** Grille de comparaison, en ms. Identique à `ImmobilityConfig.epochSec` : §3.6.4, point 1. */
    const val ALIGN_EPOCH_MS: Long = 5_000

    /** Au-delà, drapeau `HC_LAG_SUSPECT` (§3.6.4, point 1). */
    const val LAG_SUSPECT_MS: Long = 300_000

    fun lagSuspect(agreement: MaskAgreement): Boolean = abs(agreement.bestLagMs) > LAG_SUSPECT_MS

    /**
     * Temps 1 et 2 de §3.6.4 : **recalage temporel** puis **accord**.
     *
     * Pourquoi le recalage n'est pas une coquetterie : les deux appareils ont deux horloges, et la
     * latence d'endormissement est réellement différente au poignet et à la cheville. Deux minutes
     * de décalage suffisent à faire basculer des mouvements de part et d'autre de l'endormissement,
     * donc à les imputer au mauvais stade — et l'AASM v3 exige justement qu'une partie de chaque
     * mouvement tombe dans une époque de sommeil. Un décalage non corrigé ne dégrade pas le
     * résultat « un peu » : il supprime ou fabrique des événements.
     *
     * @return κ de Cohen, ΔTST = `TST_HC − TST_accel` (positif = HC score plus de sommeil),
     *   recouvrement de Jaccard des périodes de sommeil en %, et le décalage retenu. Toutes les
     *   grandeurs sont évaluées **après** recalage. `NaN` si l'un des masques est vide : une
     *   absence de mesure n'est pas un désaccord de zéro.
     */
    fun align(accel: SleepMask, hc: List<SleepWindow>, cfg: FusionConfig = FusionConfig()): MaskAgreement {
        val nothing = MaskAgreement(Double.NaN, Double.NaN, Double.NaN, 0L)
        if (accel.windows.isEmpty() || hc.isEmpty()) return nothing

        val endMs = max(lastEnd(accel.windows), lastEnd(hc))
        val nEpochs = (endMs / ALIGN_EPOCH_MS).toInt() + 1
        val a = binarize(accel.windows, nEpochs)
        val h = binarize(hc, nEpochs)

        val definedA = a.count { it >= 0 }
        val definedH = h.count { it >= 0 }
        if (definedA == 0 || definedH == 0) return nothing
        val reference = min(definedA, definedH)

        val step = max(1L, cfg.lagStepMs / ALIGN_EPOCH_MS)
        val maxShift = (cfg.maxLagMs / ALIGN_EPOCH_MS)
        var bestShift = 0L
        var bestScore = -1.0
        var bestFound = false
        // Balayage ordonné par |λ| croissant, amélioration **stricte** : à accord égal, le plus
        // petit décalage l'emporte. Un recalage est une correction d'horloge, pas un degré de
        // liberté d'ajustement ; à égalité, ne rien corriger est la conclusion honnête.
        var d = 0L
        while (d <= maxShift) {
            for (sign in intArrayOf(1, -1)) {
                if (d == 0L && sign == -1) continue
                val shift = sign * d
                val score = agreementAt(a, h, shift, reference, cfg.minCoverage) ?: continue
                if (!bestFound || score > bestScore) {
                    bestFound = true
                    bestScore = score
                    bestShift = shift
                }
            }
            d += step
        }
        if (!bestFound) return nothing

        return MaskAgreement(
            kappa = kappaAt(a, h, bestShift),
            tstDeltaMin = sleepMinutes(hc) - accel.tstMin,
            overlapPct = jaccardAt(a, h, bestShift),
            bestLagMs = bestShift * ALIGN_EPOCH_MS,
        )
    }

    /**
     * Produit le masque qui portera le résultat, par ordre de préférence strict : Health Connect,
     * puis journal manuel, puis — faute de mieux — le masque accélérométrique inchangé.
     *
     * L'ordre n'est pas un ordre de qualité de mesure, c'est un ordre d'**indépendance**. Un
     * hypnogramme de montre est moins précis qu'une PSG et probablement moins précis, sur certaines
     * nuits, que notre propre masque ; il reste préférable parce qu'il ne partage pas sa source
     * d'erreur avec le numérateur. C'est la couche 3 de §3.6.3.
     *
     * @param hc fenêtres Health Connect, déjà exprimées dans le même repère `msRel` que le masque
     *   accélérométrique. `null` ou vide = HC n'a pas répondu.
     * @param diary journal manuel. Utilisé comme **dénominateur** quand HC manque, et comme simple
     *   bornage « au lit » quand HC répond.
     */
    fun fuse(
        accel: SleepMask,
        hc: List<SleepWindow>?,
        diary: DiaryWindow?,
        cfg: FusionConfig = FusionConfig(),
    ): SleepMask {
        val coverage = analysableCoverageOf(accel)
        if (hc != null && hc.isNotEmpty()) {
            val lag = align(accel, hc, cfg).bestLagMs
            val shifted = hc.map { SleepWindow(it.startMsRel + lag, it.endMsRel + lag, it.stage) }
            val windows = if (diary == null) shifted else clipTo(shifted, diary)
            return maskOf(
                windows = windows,
                source = MaskSource.FUSED,
                independence = DenominatorIndependence.INDEPENDENT_HC,
                coverage = coverage,
                lagAppliedMs = lag,
                // Le dénominateur est celui de HC : aucune correction apprise ne s'y applique,
                // même si le masque accélérométrique en portait une.
                corrected = false,
            )
        }
        if (diary != null) return fromDiary(diary, coverage, MaskSource.FUSED)
        // Rien d'indépendant à offrir : on renvoie le masque accélérométrique **tel quel**, avec sa
        // source et sa circularité. Le renommer `FUSED` laisserait croire qu'une fusion a eu lieu.
        return accel
    }

    /**
     * Masque Health Connect seul, tel qu'il est stocké dans `NightAnalysis.masks`. Aucun recalage :
     * le recalage n'a de sens que relativement à un autre masque, et c'est [align] qui le porte.
     */
    fun fromHealthConnect(
        hc: List<SleepWindow>,
        analysableCoverage: Double = 1.0,
    ): SleepMask = maskOf(
        windows = hc,
        source = MaskSource.HEALTH_CONNECT,
        independence = DenominatorIndependence.INDEPENDENT_HC,
        coverage = analysableCoverage,
        lagAppliedMs = 0L,
        corrected = false,
    )

    /**
     * Masque du journal manuel — §3.6.5-a, « la solution la moins chère et la meilleure ».
     *
     * Le dénominateur produit est le **temps au lit**, strictement indépendant du signal. Il
     * surestime le TST puisqu'il inclut le WASO : l'indice en ressort **déflaté**, c'est-à-dire
     * biaisé du côté prudent (on sous-diagnostique plutôt que de sur-diagnostiquer), et surtout
     * biaisé d'une quantité qui **ne dépend pas du nombre de mouvements**. C'est exactement ce
     * qu'on cherche : un biais constant se compare d'une nuit à l'autre, une rétroaction non.
     *
     * Le stade est [Stage.SLEEP] indifférencié : un journal ne connaît pas les stades, et prétendre
     * le contraire fabriquerait une répartition N1/N2/N3/REM sortie de nulle part.
     */
    fun fromDiary(
        diary: DiaryWindow,
        analysableCoverage: Double = 1.0,
        source: MaskSource = MaskSource.DIARY,
    ): SleepMask = maskOf(
        windows = listOf(SleepWindow(diary.bedTimeMsRel, diary.riseTimeMsRel, Stage.SLEEP)),
        source = source,
        independence = DenominatorIndependence.INDEPENDENT_DIARY,
        coverage = analysableCoverage,
        lagAppliedMs = 0L,
        corrected = false,
    )

    /**
     * Temps 3 de §3.6.4 — **correction apprise** `TST_HC ≈ alpha · TST_accel + beta`.
     *
     * @param pairs `(TST_accel, TST_HC)` des nuits disposant des deux masques, en minutes.
     *
     * Trois régimes, imposés par la taille d'échantillon et non par goût :
     *  - `n < 3` : **aucune correction**. Deux nuits suffisent à tracer une droite parfaite et à
     *    exporter n'importe quelle aberration sur toutes les nuits suivantes. Il ne reste alors que
     *    l'encadrement du temps 4 ;
     *  - `3 ≤ n < 5` : **médiane du ratio**, `beta = 0`. Un seul paramètre, borné, insensible à un
     *    point aberrant ;
     *  - `n ≥ 5` : **Theil-Sen** (médiane des pentes deux à deux, puis médiane des ordonnées à
     *    l'origine). Point de rupture de 29 %, déterministe, et sans la moindre dépendance externe —
     *    là où les moindres carrés se laissent emporter par une seule nuit mal segmentée.
     */
    fun fitCorrection(pairs: List<Pair<Double, Double>>): TstCorrection {
        val usable = pairs.filter { it.first.isFinite() && it.second.isFinite() && it.first > 0.0 }
        val n = usable.size
        if (n < 3) return TstCorrection(Double.NaN, Double.NaN, n, false)

        val alpha: Double
        val beta: Double
        if (n < 5) {
            alpha = medianD(DoubleArray(n) { usable[it].second / usable[it].first })
            beta = 0.0
        } else {
            val slopes = ArrayList<Double>(n * (n - 1) / 2)
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = usable[j].first - usable[i].first
                    if (abs(dx) < 1e-9) continue
                    slopes.add((usable[j].second - usable[i].second) / dx)
                }
            }
            if (slopes.isEmpty()) return TstCorrection(Double.NaN, Double.NaN, n, false)
            alpha = medianD(slopes.toDoubleArray())
            beta = medianD(DoubleArray(n) { usable[it].second - alpha * usable[it].first })
        }
        val valid = alpha.isFinite() && beta.isFinite() && alpha > 0.0
        return TstCorrection(alpha, beta, n, valid)
    }

    /**
     * Applique la correction aux **grandeurs scalaires** du masque, jamais à ses fenêtres.
     *
     * Redistribuer 40 min de sommeil retrouvé sur des fenêtres précises exigerait de savoir *où*
     * elles manquaient — information dont on ne dispose précisément pas. Les fenêtres restent donc
     * le scorage brut (elles servent l'attribution des événements) tandis que le dénominateur porte
     * la correction ; `corrected = true` signale cette dissociation à l'appelant.
     *
     * `independence` reste [DenominatorIndependence.CIRCULAR] : recaler la moyenne d'un estimateur
     * ne le rend pas indépendant de ce qu'il mesure. La rétroaction nuit-à-nuit reste entière.
     */
    fun applyCorrection(accel: SleepMask, c: TstCorrection): SleepMask {
        if (!c.valid || accel.tstMin <= 0.0) return accel
        val corrected = (c.alpha * accel.tstMin + c.beta).coerceIn(0.0, accel.sptMin)
        val ratio = corrected / accel.tstMin
        return accel.copy(
            tstMin = corrected,
            wasoMin = accel.sptMin - corrected,
            analysableTstMin = accel.analysableTstMin * ratio,
            corrected = true,
        )
    }

    // --- Interne ------------------------------------------------------------------------------

    /**
     * Fraction de la nuit réellement analysable, mesurée sur le SPT du masque accélérométrique.
     *
     * Ce taux est une propriété de l'**enregistrement** — trous, segments rompus, hors-corps — et
     * non du scorage : il se transfère donc légitimement à un masque construit sur une autre
     * source, laquelle n'a par définition aucune idée de nos trous à nous. C'est une estimation, et
     * la seule disponible sans faire remonter toute la ligne de temps jusqu'ici.
     */
    private fun analysableCoverageOf(accel: SleepMask): Double =
        if (accel.sptMin > 0.0) (accel.analysableSptMin / accel.sptMin).coerceIn(0.0, 1.0) else 1.0

    private fun maskOf(
        windows: List<SleepWindow>,
        source: MaskSource,
        independence: DenominatorIndependence,
        coverage: Double,
        lagAppliedMs: Long,
        corrected: Boolean,
    ): SleepMask {
        val inBed = windows.filter { it.stage != Stage.OUT_OF_BED }
        val sptMin = if (inBed.isEmpty()) 0.0 else {
            (inBed.maxOf { it.endMsRel } - inBed.minOf { it.startMsRel }) / 60_000.0
        }
        val tstMin = sleepMinutes(windows)
        return SleepMask(
            windows = windows,
            source = source,
            sptMin = sptMin,
            tstMin = tstMin,
            wasoMin = max(0.0, sptMin - tstMin),
            analysableTstMin = tstMin * coverage,
            analysableSptMin = sptMin * coverage,
            corrected = corrected,
            lagAppliedMs = lagAppliedMs,
            independence = independence,
            // Aucun point fixe n'est en jeu : le dénominateur ne sort pas du signal qu'on compte,
            // il n'y a donc rien qui puisse osciller. Rapporter `false` fermerait la porte de
            // publication pour une raison qui n'existe pas.
            fixedPointConverged = true,
        )
    }

    private fun clipTo(windows: List<SleepWindow>, diary: DiaryWindow): List<SleepWindow> =
        windows.mapNotNull {
            val a = max(it.startMsRel, diary.bedTimeMsRel)
            val b = min(it.endMsRel, diary.riseTimeMsRel)
            if (b > a) SleepWindow(a, b, it.stage) else null
        }

    private fun isSleep(s: Stage): Boolean =
        s == Stage.SLEEP || s == Stage.LIGHT || s == Stage.DEEP || s == Stage.REM

    private fun sleepMinutes(windows: List<SleepWindow>): Double {
        var acc = 0L
        for (w in windows) if (isSleep(w.stage)) acc += w.endMsRel - w.startMsRel
        return acc / 60_000.0
    }

    private fun lastEnd(windows: List<SleepWindow>): Long = windows.maxOf { it.endMsRel }

    /**
     * `1` = sommeil, `0` = au lit mais pas endormi, `-1` = **non renseigné**.
     *
     * La distinction entre `0` et `-1` est ce qui rend l'accord interprétable : hors de sa fenêtre,
     * un masque ne dit pas « éveillé », il ne dit rien. Les compter comme des éveils concordants
     * gonflerait mécaniquement κ avec la seule longueur de l'enregistrement.
     */
    private fun binarize(windows: List<SleepWindow>, nEpochs: Int): ByteArray {
        val out = ByteArray(nEpochs) { UNDEFINED }
        for (w in windows) {
            if (w.stage == Stage.OUT_OF_BED) continue
            val v: Byte = if (isSleep(w.stage)) 1 else 0
            val from = (w.startMsRel / ALIGN_EPOCH_MS).toInt().coerceIn(0, nEpochs)
            val to = (w.endMsRel / ALIGN_EPOCH_MS).toInt().coerceIn(0, nEpochs)
            for (k in from until to) out[k] = v
        }
        return out
    }

    /** Accord brut à décalage donné, ou `null` si le recouvrement est insuffisant. */
    private fun agreementAt(
        a: ByteArray,
        h: ByteArray,
        shift: Long,
        reference: Int,
        minCoverage: Double,
    ): Double? {
        var both = 0
        var agree = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            if (j < 0 || j >= h.size) continue
            if (a[k] < 0 || h[j] < 0) continue
            both++
            if (a[k] == h[j]) agree++
        }
        if (both == 0 || both < minCoverage * reference) return null
        return agree.toDouble() / both
    }

    /**
     * κ de Cohen sur les époques où les deux masques sont renseignés.
     *
     * Cas dégénéré traité explicitement : si les deux masques ne voient que du sommeil, `pe = 1` et
     * κ est de la forme 0/0. Un accord parfait sur une seule catégorie n'apporte aucune information
     * au-delà du hasard — la convention retenue est donc `κ = 0` en cas de désaccord et `κ = 1`
     * quand l'accord est parfait, plutôt qu'un `NaN` qui se propagerait dans le rapport.
     */
    private fun kappaAt(a: ByteArray, h: ByteArray, shift: Long): Double {
        var both = 0
        var agree = 0
        var a1 = 0
        var h1 = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            if (j < 0 || j >= h.size) continue
            if (a[k] < 0 || h[j] < 0) continue
            both++
            if (a[k] == h[j]) agree++
            if (a[k].toInt() == 1) a1++
            if (h[j].toInt() == 1) h1++
        }
        if (both == 0) return Double.NaN
        val p0 = agree.toDouble() / both
        val pa = a1.toDouble() / both
        val ph = h1.toDouble() / both
        val pe = pa * ph + (1 - pa) * (1 - ph)
        if (abs(1.0 - pe) < 1e-12) return if (p0 >= 1.0) 1.0 else 0.0
        return (p0 - pe) / (1.0 - pe)
    }

    /** Recouvrement de Jaccard des périodes de sommeil, en %. */
    private fun jaccardAt(a: ByteArray, h: ByteArray, shift: Long): Double {
        var inter = 0
        var union = 0
        for (k in a.indices) {
            val j = k - shift.toInt()
            val sa = if (a[k].toInt() == 1) 1 else 0
            val sh = if (j in h.indices && h[j].toInt() == 1) 1 else 0
            if (sa == 1 && sh == 1) inter++
            if (sa == 1 || sh == 1) union++
        }
        return if (union == 0) Double.NaN else 100.0 * inter / union
    }

    /** Médiane exacte en `Double` : le fit porte sur quelques nuits, la précision y est gratuite. */
    private fun medianD(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}
