package com.pendulum.algo.dsp

import com.pendulum.algo.model.FloorMode
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import kotlin.math.max
import kotlin.math.min

/**
 * Parametres du plancher de bruit. Valeurs par defaut = tableau §6.2.
 *
 * [hopSec] ne figure pas dans la specification : c'est un parametre d'implementation, documente
 * dans [NoiseFloor.estimate].
 */
data class NoiseFloorConfig(
    val winSec: Double = 120.0,
    val pass1Percentile: Int = 25,
    val excludeFactor: Double = 4.0,
    val minValidFraction: Double = 0.25,
    val mode: FloorMode = FloorMode.BILATERAL,
    /** Utilise seulement si `mode == CAUSAL_LAGGED`. */
    val causalLagSec: Double = 5.0,
    /** IMPLEMENTATION — pas de la grille d'evaluation. Voir [NoiseFloor.estimate]. */
    val hopSec: Double = 1.0,
)

/**
 * Etape 3 — plancher de bruit adaptatif, estimateur **en trois passes, segmente**.
 *
 * ```
 * Passe 1 : floor0(t) = p25 de env sur la fenetre de W = 120 s
 * Passe 2 : masque M = { i : env[i] > k_excl x floor0[i] }        (k_excl = 4)
 * Passe 3 : floor(t)  = mediane de env sur la meme fenetre, restreinte a { i hors de M }
 *           si trop peu d'echantillons survivent -> valeur valide la plus proche, FLOOR_EXTRAPOLATED
 * Puis    : floor(t) <- max(floor(t), Theta_abs / k_on)
 * ```
 *
 * **Pourquoi l'exclusion iterative plutot qu'un simple percentile bas** (§1.3, contre-analyse
 * chiffree). Une serie de PLMS n'auto-contamine presque pas une mediane : duree moyenne d'un PLM
 * a la cheville 4,2 s, IMI moyen 31,3 s, soit un rapport cyclique de 13 a 18 %, et une mediane ne
 * decroche qu'au-dela de 50 % de contamination. A 18 %, la mediane monte de 16,6 % et un p10 de
 * 11,1 % : le percentile bas seul ne gagne que 5,5 points, ce n'est pas un ordre de grandeur.
 * **Ce qui casse reellement l'estimateur, ce sont les mouvements corporels grossiers** : un
 * retournement de 20 s dans une fenetre de 25 s, c'est 80 % de contamination et un decrochage
 * complet. D'ou les deux vraies protections : `W = 120 s` (le meme retournement n'y pese plus que
 * 17 %) et le masquage explicite des echantillons hauts avant la passe finale.
 *
 * **Pourquoi la fenetre ne franchit jamais une frontiere.** Le plancher n'est pas du bruit
 * thermique, c'est un plancher **mecanique** : il change par sauts a chaque changement de posture,
 * parce que le couplage bracelet-cheville-literie change. Une fenetre a cheval sur un saut
 * moyenne deux regimes et donne un seuil faux des deux cotes. C'est exactement le defaut que la
 * fenetre causale decalee ne sait pas traiter (§1.3, raison n° 2) et la raison pour laquelle le
 * mode definitif reste bilateral.
 */
object NoiseFloor {

    /**
     * @param boundaries frontieres a ne jamais franchir : **frontieres de segment ET de
     *   changement de posture** (§3.1, effet de bord (a)). Index de grille, ordre quelconque,
     *   doublons tolerés.
     * @param floorMinG plancher du plancher, `Theta_abs / k_on` (0,020 / 8,0 = 2,5 mg avec les
     *   valeurs par defaut de §6.3). Il est passe en argument plutot que loge dans
     *   [NoiseFloorConfig] pour ne pas dupliquer ici les parametres de seuil, qui appartiennent a
     *   l'etape 4 ; l'appelant doit le derivér de sa propre configuration de seuils.
     * @return le plancher par echantillon, et le drapeau `FLOOR_EXTRAPOLATED` par echantillon.
     *   Hors segment, le plancher vaut `NaN` et le drapeau est `true`.
     *
     * **IMPLEMENTATION — grille d'evaluation a pas `hopSec`.** Recalculer deux percentiles sur
     * 6 000 echantillons a chacun des 1,44 x 10^6 points de la nuit couterait ~10^10 operations.
     * Le plancher est donc evalue tous les `hopSec` (1 s par defaut) puis **interpole
     * lineairement**. C'est licite parce que la fenetre fait 120 s : entre deux points distants
     * de 1 s, 99,2 % du contenu de la fenetre est commun, et la variation du plancher entre les
     * deux est necessairement infime. Le cout est ramene a ~6 x 10^8 operations. La sortie reste
     * bit-identique d'une execution a l'autre pour un `hopSec` donne ; changer `hopSec` change le
     * resultat de facon marginale mais reelle, il fait donc partie du hash des parametres.
     */
    fun estimate(
        env: Signal1D,
        segments: List<Segment>,
        boundaries: IntArray,
        cfg: NoiseFloorConfig = NoiseFloorConfig(),
        floorMinG: Float = 0.0025f,
    ): Pair<Signal1D, BooleanArray> {
        val n = env.n
        val fs = env.fsHz
        val floor = FloatArray(n) { Float.NaN }
        val extrapolated = BooleanArray(n) { true }

        val win = Numeric.samples(cfg.winSec, fs)
        val hop = Numeric.samples(cfg.hopSec, fs)
        val lag = Numeric.samples(cfg.causalLagSec, fs)
        val minValid = (cfg.minValidFraction * win).toInt().coerceAtLeast(1)
        val scratch = FloatArray(win + 1)

        for (interval in splitAtBoundaries(segments, boundaries, n)) {
            estimateInterval(env, interval, win, hop, lag, minValid, cfg, floor, extrapolated, scratch)
        }

        // Coherence avec le plancher absolu (§1.3, derniere ligne) : un plancher mesure sous
        // `Theta_abs / k_on` ne peut de toute facon pas produire un seuil sous `Theta_abs`.
        // Le borner ici evite qu'une nuit anormalement calme ne fasse exploser le rapport
        // signal/plancher et ne rende toutes les statistiques de qualite illisibles.
        for (i in 0 until n) {
            val f = floor[i]
            if (!f.isNaN() && f < floorMinG) floor[i] = floorMinG
        }
        return Signal1D(fs, env.t0Ns, floor) to extrapolated
    }

    /**
     * Decoupe les segments aux frontieres fournies. Une frontiere interieure a un segment le
     * coupe en deux intervalles homogenes ; les frontieres hors segment sont ignorees.
     */
    internal fun splitAtBoundaries(segments: List<Segment>, boundaries: IntArray, n: Int): List<Segment> {
        val sorted = boundaries.filter { it in 0..n }.distinct().sorted()
        val out = ArrayList<Segment>()
        for (seg in segments) {
            var start = seg.fromIdx
            for (b in sorted) {
                if (b > start && b < seg.toIdx) {
                    out.add(Segment(start, b))
                    start = b
                }
            }
            if (seg.toIdx > start) out.add(Segment(start, seg.toIdx))
        }
        return out
    }

    private fun estimateInterval(
        env: Signal1D,
        iv: Segment,
        win: Int,
        hop: Int,
        lag: Int,
        minValid: Int,
        cfg: NoiseFloorConfig,
        floor: FloatArray,
        extrapolated: BooleanArray,
        scratch: FloatArray,
    ) {
        val len = iv.length
        if (len <= 0) return

        // Points d'evaluation : tous les `hop`, plus le dernier echantillon pour que
        // l'interpolation couvre l'intervalle entier sans extrapoler.
        val evalCount = ((len - 1) / hop) + 1
        val evalIdx = IntArray(evalCount + 1)
        for (j in 0 until evalCount) evalIdx[j] = iv.fromIdx + j * hop
        evalIdx[evalCount] = iv.toIdx - 1
        val nEval = if (evalIdx[evalCount] > evalIdx[evalCount - 1]) evalCount + 1 else evalCount

        val f0 = FloatArray(nEval)
        val f3 = FloatArray(nEval) { Float.NaN }
        val ext = BooleanArray(nEval)

        // --- Passe 1 : p25 brut -----------------------------------------------------------
        for (j in 0 until nEval) {
            val e = evalIdx[j]
            val lo: Int
            val hi: Int
            if (cfg.mode == FloorMode.BILATERAL) {
                lo = max(iv.fromIdx, e - win / 2)
                hi = min(iv.toIdx, e + win - win / 2)
            } else {
                // CAUSAL_LAGGED : fenetre [t - lag - W, t - lag]. Le decalage de 5 s empeche
                // l'evenement en cours de contaminer son propre plancher ; le biais de retard de
                // 35 s qui en resulte est acceptable en mode provisoire, jamais en definitif.
                hi = min(iv.toIdx, max(iv.fromIdx, e - lag))
                lo = max(iv.fromIdx, hi - win)
            }
            f0[j] = if (hi > lo) {
                Numeric.percentile(env.v, lo, hi, cfg.pass1Percentile.toDouble(), scratch)
            } else Float.NaN
        }
        // `f0` peut contenir des NaN (fenetre entierement dans un trou) : on les comble avant de
        // s'en servir comme reference d'exclusion, sinon le masque de la passe 2 laisserait
        // passer n'importe quoi a cet endroit.
        fillNearest(f0)

        // --- Passe 1bis : floor0 par echantillon (support du masque de la passe 2) ---------
        val floor0Sample = FloatArray(len)
        interpolate(evalIdx, f0, nEval, iv, floor0Sample)

        // --- Passes 2 et 3 : mediane sur les echantillons non masques ----------------------
        for (j in 0 until nEval) {
            val e = evalIdx[j]
            val lo: Int
            val hi: Int
            if (cfg.mode == FloorMode.BILATERAL) {
                lo = max(iv.fromIdx, e - win / 2)
                hi = min(iv.toIdx, e + win - win / 2)
            } else {
                hi = min(iv.toIdx, max(iv.fromIdx, e - lag))
                lo = max(iv.fromIdx, hi - win)
            }
            var m = 0
            for (i in lo until hi) {
                val v = env.v[i]
                if (v.isNaN()) continue
                val ref = floor0Sample[i - iv.fromIdx]
                // Masquage : tout ce qui depasse `k_excl x floor0` est presume evenement, pas
                // bruit de fond. `k_excl = 4` est volontairement SOUS `k_on = 8` : on veut aussi
                // ecarter les CLM sous-seuil, qui sont du signal meme s'ils ne seront pas
                // comptes, sans quoi ils remonteraient le plancher et s'auto-elimineraient.
                if (!ref.isNaN() && v > cfg.excludeFactor * ref) continue
                scratch[m++] = v
            }
            if (m >= minValid) {
                f3[j] = Numeric.percentileOfCompact(scratch, m, 50.0)
                ext[j] = false
            } else {
                // Moins de `minValidFraction x W` echantillons survivants : la fenetre est
                // dominee par de l'evenement ou par du trou. On ne publie pas une mediane sur
                // 3 echantillons — on reprend la valeur valide la plus proche et on le dit.
                // Ce cas est SYSTEMATIQUE sur les dernieres secondes d'une nuit tronquee (§3.7.2).
                f3[j] = Float.NaN
                ext[j] = true
            }
        }
        // Repli : valeur valide la plus proche ; a defaut de toute valeur valide dans
        // l'intervalle, la passe 1 (§1.3 le prevoit explicitement ainsi).
        val anyValid = (0 until nEval).any { !f3[it].isNaN() }
        if (anyValid) {
            fillNearest(f3)
        } else {
            for (j in 0 until nEval) f3[j] = f0[j]
        }

        // --- Restitution par echantillon ---------------------------------------------------
        val out = FloatArray(len)
        interpolate(evalIdx, f3, nEval, iv, out)
        for (i in 0 until len) floor[iv.fromIdx + i] = out[i]

        // Un echantillon est extrapole des lors que **l'un des deux** points d'evaluation qui
        // l'encadrent l'est : un plancher interpole depuis une valeur extrapolee reste extrapole.
        var j = 0
        for (i in iv.fromIdx until iv.toIdx) {
            while (j + 1 < nEval && evalIdx[j + 1] < i) j++
            val right = if (j + 1 < nEval) ext[j + 1] else ext[j]
            extrapolated[i] = ext[j] || right
        }
    }

    /** Comble les `NaN` d'un tableau par la valeur valide la plus proche (arriere puis avant). */
    private fun fillNearest(a: FloatArray) {
        val n = a.size
        var last = Float.NaN
        for (i in 0 until n) {
            if (!a[i].isNaN()) last = a[i] else if (!last.isNaN()) a[i] = last
        }
        last = Float.NaN
        for (i in n - 1 downTo 0) {
            if (!a[i].isNaN()) last = a[i] else if (!last.isNaN()) a[i] = last
        }
    }

    /** Interpolation lineaire des valeurs aux points d'evaluation vers tous les echantillons. */
    private fun interpolate(evalIdx: IntArray, values: FloatArray, nEval: Int, iv: Segment, out: FloatArray) {
        if (nEval == 1) {
            java.util.Arrays.fill(out, values[0])
            return
        }
        var j = 0
        for (i in iv.fromIdx until iv.toIdx) {
            while (j + 1 < nEval && evalIdx[j + 1] < i) j++
            val a = evalIdx[j]
            val b = if (j + 1 < nEval) evalIdx[j + 1] else a
            out[i - iv.fromIdx] = if (b == a) values[j] else {
                val u = (i - a).toDouble() / (b - a).toDouble()
                (values[j] + u * (values[j + 1] - values[j])).toFloat()
            }
        }
    }
}
