package com.pendulum.algo.dsp

import com.pendulum.algo.model.FsEstimate
import com.pendulum.algo.model.Gap
import com.pendulum.algo.model.GapKind
import com.pendulum.algo.model.IntegrityConfig
import com.pendulum.algo.model.IntegrityReport
import com.pendulum.algo.model.SampleBlock
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Timeline
import com.pendulum.algo.model.TriAxial
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimation de la frequence d'echantillonnage reelle et de la derive d'horloge (§3.4).
 *
 * Regle numero un : **ne jamais faire confiance a `nominalRateHz`**. Le champ existe dans
 * l'en-tete pour la tracabilite, pas pour le calcul. `fs` se recalcule depuis
 * `(tFirstNs, tLastNs, N)` de chaque bloc — apres revalidation par l'etape −1, puisque ces
 * champs ne sont pas couverts par le CRC.
 *
 * Enjeu chiffre : un `fs` faux de 5,2 % (50 -> 52,6 Hz) ne deplace le coude du passe-haut que de
 * 0,500 a 0,526 Hz, sans effet audible — mais il fausse **toutes les durees de 5,2 %**. Un CLM
 * mesure a 10,0 s en dure 9,5 s reellement, un IMI de 90 s en vaut 85,5 s : les evenements aux
 * bornes de classe basculent en masse.
 */
object Rate {

    /**
     * @param outlierTol tolerance de rejet autour de la mediane (§6.1, `fsOutlierTol = 0,05`).
     *   Un `fs` de bloc aberrant est un **symptome de timestamps corrompus**, pas de derive : la
     *   derive d'un quartz se mesure en ppm, pas en pourcents.
     */
    fun estimate(blocks: List<SampleBlock>, nominalHz: Double, outlierTol: Double = 0.05): FsEstimate {
        val fsList = ArrayList<Double>(blocks.size)
        val wList = ArrayList<Double>(blocks.size)
        for (b in blocks) {
            val n = b.x.size
            if (n < 2) continue
            val span = (b.tLastNs - b.tFirstNs).toDouble()
            if (span <= 0.0) continue
            fsList.add((n - 1) * 1e9 / span)
            wList.add(n.toDouble())
        }
        if (fsList.isEmpty()) {
            return FsEstimate(nominalHz, nominalHz, 0, 0.0, false)
        }
        val fsArr = fsList.toDoubleArray()
        val wArr = wList.toDoubleArray()
        // Premiere mediane ponderee par N : un bloc de 512 echantillons contraint `fs` bien
        // mieux qu'un bloc de 30, et la ponderation evite qu'une rafale de blocs courts en fin
        // de session ne tire l'estimation.
        val med0 = Numeric.weightedMedian(fsArr, wArr)
        var rejected = 0
        val keptFs = ArrayList<Double>(fsArr.size)
        val keptW = ArrayList<Double>(fsArr.size)
        for (i in fsArr.indices) {
            if (med0 > 0.0 && abs(fsArr[i] - med0) / med0 > outlierTol) {
                rejected++
            } else {
                keptFs.add(fsArr[i]); keptW.add(wArr[i])
            }
        }
        val fsSession = if (keptFs.isEmpty()) med0
        else Numeric.weightedMedian(keptFs.toDoubleArray(), keptW.toDoubleArray())
        return FsEstimate(
            fsSessionHz = fsSession,
            fsNominalHz = nominalHz,
            blocksRejected = rejected,
            clockDriftPpm = 0.0,
            clockDriftSuspect = false,
        )
    }

    /**
     * Derive entre l'echelle `SensorEvent.timestamp` et l'horloge murale, en **ppm** (§3.4).
     *
     * Certains OEM excluent le temps de suspend de `SensorEvent.timestamp` : les deux echelles
     * divergent alors lentement. La consequence n'est pas cosmetique — la fusion avec
     * l'hypnogramme Health Connect se decale, ce qui deplace des CLM d'un stade a l'autre et
     * fausse le partage PLMS / PLMW.
     *
     * @param wallMs `startWallMs` de chaque chunk.
     * @param eventNs `firstEventTimestampNs` du meme chunk.
     * @return `(pente - 1) x 1e6`. `NaN` si moins de deux ancres exploitables.
     */
    fun clockDrift(wallMs: LongArray, eventNs: LongArray): Double {
        require(wallMs.size == eventNs.size) { "tailles differentes" }
        if (wallMs.size < 2) return Double.NaN
        val x = DoubleArray(wallMs.size) { (eventNs[it] - eventNs[0]).toDouble() }
        val y = DoubleArray(wallMs.size) { (wallMs[it] - wallMs[0]).toDouble() * 1e6 } // ms -> ns
        val slope = Numeric.slope(x, y)
        return if (slope.isNaN()) Double.NaN else (slope - 1.0) * 1e6
    }

    /**
     * Seuil de §3.4 : une pente s'ecartant de 1 de plus de 1e-4 (soit plus de 2,9 s sur 8 h)
     * doit lever `CLOCK_DRIFT`.
     */
    fun driftSuspect(ppm: Double): Boolean = !ppm.isNaN() && abs(ppm) > 100.0
}

/**
 * Parametres de l'etape 0. Valeurs par defaut = tableau §6.1, a l'unite pres.
 *
 * Les trois derniers champs (detection off-body) ne figurent dans aucun tableau de la
 * specification : §5.2 decrit seulement le scenario de test T8 (« montre sur la table 10 min,
 * gravite constante + bruit seul, exclusion du numerateur **et** du denominateur »). Les valeurs
 * retenues transcrivent ce scenario et sont signalees comme **interpretation**.
 */
data class TimelineConfig(
    val targetFsHz: Double = 50.0,
    val gapMicroSec: Double = 0.10,
    val gapSegmentSec: Double = 2.0,
    val settleSec: Double = 2.0,
    val warmupSec: Double = 5.0,
    val fsOutlierTol: Double = 0.05,
    val integrity: IntegrityConfig = IntegrityConfig(),
    /** INTERPRETATION — fenetre d'analyse de l'immobilite absolue pour l'off-body. */
    val offBodyWinSec: Double = 60.0,
    /** INTERPRETATION — sous cet ecart-type sur les trois axes, plus rien ne bouge du tout. */
    val offBodySdG: Float = 0.005f,
    /** INTERPRETATION — duree minimale d'une plage off-body, calquee sur le test T8. */
    val offBodyMinSec: Double = 600.0,
)

/**
 * Etape 0 — reconstruction de la ligne de temps.
 *
 * **C'est la fonction la plus delicate du prealable.** Tout ce qui suit (durees de CLM, IMI,
 * denominateur du PLMI) se lit en index de la grille produite ici : un decalage d'un demi-trou
 * fausse la nuit entiere sans jamais lever d'exception.
 *
 * Choix structurant (§3.4, deuxieme point) : on **reechantillonne sur une grille fixe** a
 * `targetFsHz` plutot que d'adapter les coefficients des filtres a un `fs` variable. Adapter les
 * filtres obligerait a recalculer les biquads en cours de session, ce qui produit un transitoire
 * a chaque recalcul — on remplacerait un biais de 5 % par des artefacts localises, c'est-a-dire
 * par des faux positifs. L'interpolation lineaire coute **au plus 1,8 %** d'attenuation point a
 * point a 3 Hz et rend tout le reste exact.
 *
 * Ce commentaire a longtemps annonce « 0,2 % pour un rapport de reechantillonnage inferieur a
 * 1,06 ». La specification a formellement retire cette phrase le 2026-07-31 (`ALGO-v2.md` §2
 * etape 0, encadre de correction) : ce n'est pas le rapport de reechantillonnage qui gouverne
 * l'attenuation mais la **periode d'echantillonnage source**, et meme moyennee sur une phase
 * uniforme — la seule lecture qui aurait pu justifier 0,2 % — elle reste autour de 1,1 %. Le
 * chiffre etait donc faux d'un ordre de grandeur, et il a survecu ici a sa propre retractation.
 */
object TimelineBuilder {

    /**
     * @param sessionClosedCleanly INTERPRETATION — [SampleBlock] ne porte aucun marqueur de fin
     *   de session (le format est append-only, il n'y a pas de patch d'en-tete a la fermeture).
     *   L'appelant — c'est-a-dire l'adaptateur de `:phone`, qui a vu le fichier — est le seul a
     *   savoir si la nuit s'est terminee proprement. Par defaut on suppose que oui, pour qu'une
     *   nuit synthetique ne soit pas marquee tronquee a tort ; `:phone` doit passer `false` des
     *   que le dernier chunk est incomplet ou que la session a ete tuee.
     */
    fun build(
        blocks: List<SampleBlock>,
        nominalHz: Double,
        cfg: TimelineConfig = TimelineConfig(),
        sessionClosedCleanly: Boolean = true,
    ): Timeline {
        val (accepted, integrity) = Integrity.check(blocks, nominalHz, cfg.integrity)
        val fsEst = Rate.estimate(accepted, nominalHz, cfg.fsOutlierTol)

        if (accepted.isEmpty()) return emptyTimeline(cfg, fsEst, integrity)

        val fsTarget = cfg.targetFsHz
        // Cadence de reference pour mesurer un trou : le `fs` de session mesure, jamais le
        // nominal. Si l'un des deux est absurde on retombe sur le nominal, faute de mieux.
        val fsSrc = if (fsEst.fsSessionHz.isFinite() && fsEst.fsSessionHz > 1.0) fsEst.fsSessionHz else nominalHz
        val stepSrcNs = 1e9 / fsSrc

        val t0Ns = sampleTimeNs(accepted[0], 0)
        val lastBlock = accepted.last()
        val tEndNs = sampleTimeNs(lastBlock, lastBlock.x.size - 1)
        val n = (((tEndNs - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1).coerceAtLeast(1)

        val gx = FloatArray(n); val gy = FloatArray(n); val gz = FloatArray(n)

        val microGapNs = cfg.gapMicroSec * 1e9
        val segmentGapNs = cfg.gapSegmentSec * 1e9

        val gaps = ArrayList<Gap>()

        // --- Remplissage de la grille par curseur a deux pointeurs -----------------------
        // On ne materialise jamais la suite des echantillons sources : `(b, i)` suffit, et la
        // monotonie garantie par l'etape −1 fait que le curseur n'avance que vers l'avant.
        var cb = 0
        var ci = 0
        var nanRunStart = -1
        var nanRunPairB = -1
        var nanRunPairI = -1
        var nanRunExcessNs = 0.0

        fun closeNanRun(endExclusive: Int) {
            if (nanRunStart < 0) return
            val excessSec = nanRunExcessNs / 1e9
            val kind = if (nanRunExcessNs > segmentGapNs) GapKind.SEGMENT_BREAK else GapKind.BLIND
            gaps.add(Gap(nanRunStart, endExclusive, kind, excessSec))
            nanRunStart = -1
        }

        for (k in 0 until n) {
            val tk = t0Ns + Math.round(k * 1e9 / fsTarget)
            // Avancer tant que l'echantillon SUIVANT est encore <= tk.
            while (true) {
                val nb: Int
                val ni: Int
                if (ci + 1 < accepted[cb].x.size) { nb = cb; ni = ci + 1 }
                else if (cb + 1 < accepted.size) { nb = cb + 1; ni = 0 }
                else break
                if (sampleTimeNs(accepted[nb], ni) <= tk) { cb = nb; ci = ni } else break
            }
            val hasNext: Boolean
            val nb: Int
            val ni: Int
            if (ci + 1 < accepted[cb].x.size) { hasNext = true; nb = cb; ni = ci + 1 }
            else if (cb + 1 < accepted.size) { hasNext = true; nb = cb + 1; ni = 0 }
            else { hasNext = false; nb = -1; ni = -1 }

            val tCur = sampleTimeNs(accepted[cb], ci)
            if (!hasNext) {
                // Dernier echantillon de la session : la grille s'arrete dessus par construction.
                if (nanRunStart >= 0) closeNanRun(k)
                gx[k] = accepted[cb].x[ci]; gy[k] = accepted[cb].y[ci]; gz[k] = accepted[cb].z[ci]
                continue
            }
            val tNext = sampleTimeNs(accepted[nb], ni)
            val excessNs = (tNext - tCur).toDouble() - stepSrcNs

            if (excessNs <= microGapNs) {
                // Trou micro (< 0,10 s, soit 5 echantillons) : interpolation lineaire silencieuse.
                // Plus court que la plus rapide caracteristique d'un CLM (T_rise >= 0,15 s), donc
                // aucun artefact detectable — c'est le seul cas ou l'on fabrique de la donnee.
                if (nanRunStart >= 0) closeNanRun(k)
                val span = (tNext - tCur).toDouble()
                val u = if (span > 0.0) ((tk - tCur).toDouble() / span).coerceIn(0.0, 1.0) else 0.0
                val a = accepted[cb]; val bnx = accepted[nb]
                gx[k] = lerp(a.x[ci], bnx.x[ni], u)
                gy[k] = lerp(a.y[ci], bnx.y[ni], u)
                gz[k] = lerp(a.z[ci], bnx.z[ni], u)
            } else if ((tk - tCur).toDouble() * 2.0 <= stepSrcNs) {
                // Point de grille situe a moins d'un demi-echantillon du dernier echantillon
                // valide AVANT le trou : on recopie cet echantillon au lieu de le perdre. Sans
                // ce cas, le trou mangerait un echantillon reel a son bord gauche et la
                // frontiere de segment tomberait un cran trop tot.
                if (nanRunStart >= 0) closeNanRun(k)
                gx[k] = accepted[cb].x[ci]; gy[k] = accepted[cb].y[ci]; gz[k] = accepted[cb].z[ci]
            } else {
                // Trou reel : NaN. Le canal mouvement les traitera comme des zeros, le canal
                // gravite maintiendra la derniere valeur (etape 1) ; le denominateur les retire.
                if (nanRunStart >= 0 && (nanRunPairB != cb || nanRunPairI != ci)) closeNanRun(k)
                if (nanRunStart < 0) {
                    nanRunStart = k
                    nanRunPairB = cb; nanRunPairI = ci
                    nanRunExcessNs = excessNs
                }
                gx[k] = Float.NaN; gy[k] = Float.NaN; gz[k] = Float.NaN
            }
        }
        closeNanRun(n)

        // Les trous MICRO sont rapportes pour le diagnostic mais n'excluent rien : leurs bornes
        // en index sont calculees par la formule directe, a +/-1 echantillon pres. Les trous
        // BLIND et SEGMENT_BREAK, eux, viennent des plages de NaN reellement ecrites, donc sont
        // exacts — c'est sur eux seuls que reposent segments, zones aveugles et denominateur.
        collectMicroGaps(accepted, t0Ns, fsTarget, stepSrcNs, microGapNs, n, gaps)
        gaps.sortBy { it.fromIdx }

        val signal = TriAxial(fsTarget, t0Ns, gx, gy, gz)

        // --- Segments -------------------------------------------------------------------
        val segments = ArrayList<Segment>()
        var segStart = 0
        for (g in gaps) {
            if (g.kind != GapKind.SEGMENT_BREAK) continue
            if (g.fromIdx > segStart) segments.add(Segment(segStart, g.fromIdx))
            segStart = g.toIdx
        }
        if (segStart < n) segments.add(Segment(segStart, n))

        // --- Zones aveugles --------------------------------------------------------------
        // Deux origines reunies dans la meme liste, parce qu'elles ont exactement le meme effet
        // aval (aucun CLM ne peut y debuter ni s'y achever, et le temps est retire du
        // denominateur) :
        //  a) les trous BLIND, elargis de `settleSec` de chaque cote — le filtre sonne autant
        //     apres un trou qu'apres un demarrage ;
        //  b) les `warmupSec` premieres secondes de CHAQUE segment. §2 etape 1 les exclut de
        //     l'analyse et du denominateur ; les y ranger evite de dupliquer cette regle dans le
        //     detecteur, le masque et le calcul d'indices — trois endroits ou l'oublier serait
        //     silencieux.
        val settleSamples = Numeric.samples(cfg.settleSec, fsTarget)
        val warmupSamples = Numeric.samples(cfg.warmupSec, fsTarget)
        val blind = ArrayList<Segment>()
        for (seg in segments) {
            val head = min(seg.toIdx, seg.fromIdx + warmupSamples)
            if (head > seg.fromIdx) blind.add(Segment(seg.fromIdx, head))
        }
        for (g in gaps) {
            if (g.kind != GapKind.BLIND) continue
            val seg = segments.firstOrNull { g.fromIdx >= it.fromIdx && g.fromIdx < it.toIdx } ?: continue
            val lo = max(seg.fromIdx, g.fromIdx - settleSamples)
            val hi = min(seg.toIdx, g.toIdx + settleSamples)
            if (hi > lo) blind.add(Segment(lo, hi))
        }
        val blindZones = mergeSegments(blind)

        // --- Off-body ---------------------------------------------------------------------
        val offBody = detectOffBody(signal, accepted, t0Ns, segments, cfg)

        // --- Temps analysable --------------------------------------------------------------
        val analysable = BooleanArray(n)
        for (seg in segments) for (i in seg.fromIdx until seg.toIdx) analysable[i] = true
        for (z in blindZones) for (i in z.fromIdx until z.toIdx) analysable[i] = false
        for (z in offBody) for (i in z.fromIdx until z.toIdx) analysable[i] = false
        for (i in 0 until n) if (gx[i].isNaN()) analysable[i] = false
        var analysableCount = 0
        for (i in 0 until n) if (analysable[i]) analysableCount++

        // Une nuit dont les derniers blocs ont ete rejetes se termine sur un trou : elle est
        // tronquee au sens de §3.7.2, meme si le fichier a ete ferme proprement.
        val endsOnGap = gaps.lastOrNull()?.let { it.toIdx >= n } ?: false
        val truncated = !sessionClosedCleanly || endsOnGap

        return Timeline(
            signal = signal,
            gaps = gaps,
            segments = segments,
            blindZones = blindZones,
            fs = fsEst,
            offBody = offBody,
            integrity = integrity,
            analysableSec = analysableCount / fsTarget,
            truncated = truncated,
        )
    }

    // ------------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------------

    /**
     * Instant de l'echantillon `i` du bloc, par interpolation exacte de l'en-tete :
     * `t = tFirst + round(i x (tLast - tFirst) / (N - 1))`.
     *
     * C'est la formule de §2 etape 0, et **pas** `tFirst + i/fs` : le FIFO materiel echantillonne
     * uniformement entre les deux bornes du bloc, mais la cadence reelle d'un bloc peut differer
     * de la cadence de session de quelques ppm. Utiliser `1/fs` accumulerait cette difference
     * jusqu'a la fin du bloc.
     */
    private fun sampleTimeNs(b: SampleBlock, i: Int): Long {
        val n = b.x.size
        if (n <= 1) return b.tFirstNs
        return b.tFirstNs + Math.round(i.toDouble() * (b.tLastNs - b.tFirstNs).toDouble() / (n - 1))
    }

    private fun lerp(a: Float, b: Float, u: Double): Float =
        (a.toDouble() + u * (b.toDouble() - a.toDouble())).toFloat()

    private fun collectMicroGaps(
        accepted: List<SampleBlock>,
        t0Ns: Long,
        fsTarget: Double,
        stepSrcNs: Double,
        microGapNs: Double,
        n: Int,
        out: MutableList<Gap>,
    ) {
        var prevT = Long.MIN_VALUE
        for (b in accepted) {
            val cnt = b.x.size
            if (cnt < 1) continue
            val tFirst = sampleTimeNs(b, 0)
            if (prevT != Long.MIN_VALUE) {
                val excess = (tFirst - prevT).toDouble() - stepSrcNs
                // Strictement positif : un enchainement parfait n'est pas un trou.
                if (excess > stepSrcNs * 0.5 && excess <= microGapNs) {
                    val from = ((prevT - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1
                    val to = ((tFirst - t0Ns).toDouble() * fsTarget / 1e9).toInt() + 1
                    if (to > from) {
                        out.add(Gap(from.coerceIn(0, n), to.coerceIn(0, n), GapKind.MICRO, excess / 1e9))
                    }
                }
            }
            prevT = sampleTimeNs(b, cnt - 1)
        }
    }

    /** Fusionne et trie une liste d'intervalles, en absorbant les chevauchements. */
    internal fun mergeSegments(src: List<Segment>): List<Segment> {
        if (src.isEmpty()) return emptyList()
        val sorted = src.sortedWith(compareBy({ it.fromIdx }, { it.toIdx }))
        val out = ArrayList<Segment>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val s = sorted[i]
            cur = if (s.fromIdx <= cur.toIdx) Segment(cur.fromIdx, max(cur.toIdx, s.toIdx)) else {
                out.add(cur); s
            }
        }
        out.add(cur)
        return out
    }

    /**
     * Off-body — INTERPRETATION.
     *
     * La specification ne definit l'off-body que par son scenario de test (T8 : « montre sur la
     * table 10 min, gravite constante + bruit seul »). Deux sources sont donc combinees :
     *
     *  1. le drapeau `FLAG_OFF_BODY` du bloc, quand la montre l'a pose elle-meme ;
     *  2. une detection d'**immobilite absolue** : sur des fenetres de `offBodyWinSec`,
     *     l'ecart-type des trois axes est sous `offBodySdG`, et cela dure au moins
     *     `offBodyMinSec`.
     *
     * Le seuil est volontairement bien plus bas que celui du masque d'immobilite (§3.6) : une
     * cheville endormie n'est jamais totalement immobile — respiration, micro-ajustements,
     * tonus — alors qu'une montre posee sur une table ne produit que le bruit du MEMS. Confondre
     * les deux couterait cher dans les deux sens : compter du temps table comme du sommeil
     * gonfle le denominateur et deflate le PLMI ; exclure du vrai sommeil calme le fait monter.
     */
    private fun detectOffBody(
        signal: TriAxial,
        accepted: List<SampleBlock>,
        t0Ns: Long,
        segments: List<Segment>,
        cfg: TimelineConfig,
    ): List<Segment> {
        val n = signal.n
        val fs = signal.fsHz
        val win = Numeric.samples(cfg.offBodyWinSec, fs)
        val minSamples = Numeric.samples(cfg.offBodyMinSec, fs)
        val candidates = ArrayList<Segment>()

        for (seg in segments) {
            var runStart = -1
            var w = seg.fromIdx
            while (w < seg.toIdx) {
                val hi = min(seg.toIdx, w + win)
                val still = isStill(signal, w, hi, cfg.offBodySdG)
                if (still) {
                    if (runStart < 0) runStart = w
                } else {
                    if (runStart >= 0 && w - runStart >= minSamples) candidates.add(Segment(runStart, w))
                    runStart = -1
                }
                w = hi
            }
            if (runStart >= 0 && seg.toIdx - runStart >= minSamples) candidates.add(Segment(runStart, seg.toIdx))
        }

        // Drapeau materiel : on fait confiance a la montre pour dire « non porte », jamais pour
        // dire « porte » (l'absence de drapeau ne prouve rien, le capteur off-body est optionnel).
        for (b in accepted) {
            if ((b.flags and BlockFlags.OFF_BODY) == 0) continue
            val from = (((b.tFirstNs - t0Ns).toDouble() * fs / 1e9).toInt()).coerceIn(0, n)
            val to = (((b.tLastNs - t0Ns).toDouble() * fs / 1e9).toInt() + 1).coerceIn(0, n)
            if (to > from) candidates.add(Segment(from, to))
        }
        return mergeSegments(candidates)
    }

    private fun isStill(s: TriAxial, from: Int, to: Int, sdLimit: Float): Boolean {
        if (to - from < 2) return false
        val axes = arrayOf(s.x, s.y, s.z)
        for (a in axes) {
            var sum = 0.0; var sum2 = 0.0; var cnt = 0
            for (i in from until to) {
                val v = a[i]
                if (v.isNaN()) continue
                sum += v; sum2 += v.toDouble() * v; cnt++
            }
            if (cnt < 2) return false
            val mean = sum / cnt
            val varr = max(0.0, sum2 / cnt - mean * mean)
            if (sqrt(varr) >= sdLimit) return false
        }
        return true
    }

    private fun emptyTimeline(cfg: TimelineConfig, fs: FsEstimate, integrity: IntegrityReport) = Timeline(
        signal = TriAxial(cfg.targetFsHz, 0L, FloatArray(0), FloatArray(0), FloatArray(0)),
        gaps = emptyList(),
        segments = emptyList(),
        blindZones = emptyList(),
        fs = fs,
        offBody = emptyList(),
        integrity = integrity,
        analysableSec = 0.0,
        truncated = true,
    )
}
