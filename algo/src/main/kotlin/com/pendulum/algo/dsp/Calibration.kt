package com.pendulum.algo.dsp

import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.SensorCalibration
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * §3.3 — normalisation inter-nuits. Deux volets **independants et cumulatifs**, qu'il ne faut
 * jamais confondre :
 *
 *  - **Volet A, autocalibration statique du capteur** ([autocalibrate], [apply]). Corrige le
 *    decalage et le gain du MEMS, qui derivent avec la temperature et le vieillissement. Ne fait
 *    **rien** contre le serrage du bracelet.
 *  - **Volet B, rituel de calibration mecanique** ([fromRitual]). C'est celui qui compte.
 *    `gainCal` est la mesure **directe** du gain de la chaine mecanique cheville -> bracelet ->
 *    boitier -> MEMS de cette nuit-la, pour un geste physiologique de reference. C'est exactement
 *    la variable que le serrage fait bouger, et elle est mesuree au lieu d'etre supposee.
 *
 * La v1 se contentait de « garder le meme bracelet et le meme serrage » : ce n'est pas une
 * solution, c'est un voeu. Et c'est le probleme le plus serieux du projet apres le masque de
 * sommeil, parce qu'il attaque directement la comparabilite — toute la valeur d'un depistage
 * sur 5 a 7 nuits.
 */
object Calibration {

    /** Tolerance inter-nuits de §3.3 : au-dela, la nuit est marquee `CALIB_OUTLIER`. */
    const val DEFAULT_OUTLIER_TOLERANCE = 0.35

    // ------------------------------------------------------------------
    // Volet A — autocalibration statique
    // ------------------------------------------------------------------

    /**
     * Autocalibration a la maniere de GGIR / van Hees, entierement derivee de la nuit elle-meme,
     * sans intervention de l'utilisateur.
     *
     * 1. fenetres statiques : `sd(x), sd(y), sd(z) < 13 mg` sur [staticWinSec] ;
     * 2. **couverture de la sphere** : `max(g_i) - min(g_i) >= 0,30 g` sur chacun des trois axes.
     *    Sans cette condition, le probleme est mal pose : une nuit passee dans une seule
     *    orientation ne contraint pas le gain des deux autres axes, et les moindres carres
     *    renverraient un resultat confiant et faux. Echec -> `valid = false`, l'appelant doit
     *    conserver la calibration de la nuit precedente (drapeau `CALIB_INSUFFICIENT_COVERAGE`) ;
     * 3. 5 iterations de moindres carres sur `(offset o, gain diagonal S)` minimisant
     *    `somme_f ( ||S x (a_f - o)|| - 1 )^2` ;
     * 4. rejet si `||o|| > 0,10 g` ou `max|S - 1| > 0,05` : au-dela, ce n'est plus une derive de
     *    MEMS, c'est un capteur suspect et le corriger masquerait la panne.
     */
    fun autocalibrate(
        raw: TriAxial,
        segments: List<Segment>,
        staticWinSec: Double = 10.0,
        sdThresholdG: Float = 0.013f,
        minSphereSpanG: Float = 0.30f,
    ): SensorCalibration {
        val invalid = SensorCalibration(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), Float.NaN, false)
        val win = Numeric.samples(staticWinSec, raw.fsHz)
        val px = ArrayList<Double>(); val py = ArrayList<Double>(); val pz = ArrayList<Double>()

        for (seg in segments) {
            var w = seg.fromIdx
            while (w + win <= seg.toIdx) {
                val hi = w + win
                val m = meanIfStill(raw, w, hi, sdThresholdG)
                if (m != null) { px.add(m[0]); py.add(m[1]); pz.add(m[2]) }
                w = hi
            }
        }
        val nPts = px.size
        if (nPts < 10) return invalid

        val spanX = span(px); val spanY = span(py); val spanZ = span(pz)
        if (spanX < minSphereSpanG || spanY < minSphereSpanG || spanZ < minSphereSpanG) return invalid

        val ax = px.toDoubleArray(); val ay = py.toDoubleArray(); val az = pz.toDoubleArray()
        val offset = doubleArrayOf(0.0, 0.0, 0.0)
        val scale = doubleArrayOf(1.0, 1.0, 1.0)

        // Gauss-Newton « a la GGIR » : a chaque iteration on projette les points calibres sur la
        // sphere unite, puis on refait une regression lineaire par axe du point projete sur le
        // point BRUT. Regresser sur le brut et non sur le calibre evite d'accumuler les
        // corrections, donc de diverger si une iteration part de travers.
        repeat(5) {
            val tx = DoubleArray(nPts); val ty = DoubleArray(nPts); val tz = DoubleArray(nPts)
            for (i in 0 until nPts) {
                val cx = scale[0] * (ax[i] - offset[0])
                val cy = scale[1] * (ay[i] - offset[1])
                val cz = scale[2] * (az[i] - offset[2])
                val nrm = sqrt(cx * cx + cy * cy + cz * cz)
                if (nrm < 1e-6) { tx[i] = cx; ty[i] = cy; tz[i] = cz } else {
                    tx[i] = cx / nrm; ty[i] = cy / nrm; tz[i] = cz / nrm
                }
            }
            fitAxis(ax, tx, scale, offset, 0)
            fitAxis(ay, ty, scale, offset, 1)
            fitAxis(az, tz, scale, offset, 2)
        }

        var sse = 0.0
        for (i in 0 until nPts) {
            val cx = scale[0] * (ax[i] - offset[0])
            val cy = scale[1] * (ay[i] - offset[1])
            val cz = scale[2] * (az[i] - offset[2])
            val e = sqrt(cx * cx + cy * cy + cz * cz) - 1.0
            sse += e * e
        }
        val residual = sqrt(sse / nPts).toFloat()

        val offNorm = sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2])
        val maxScaleDev = maxOf(abs(scale[0] - 1.0), abs(scale[1] - 1.0), abs(scale[2] - 1.0))
        val valid = offNorm <= 0.10 && maxScaleDev <= 0.05 && residual.isFinite()

        return SensorCalibration(
            offsetG = floatArrayOf(offset[0].toFloat(), offset[1].toFloat(), offset[2].toFloat()),
            scale = floatArrayOf(scale[0].toFloat(), scale[1].toFloat(), scale[2].toFloat()),
            residualG = residual,
            valid = valid,
        )
    }

    /**
     * `a <- S x (a_brut - o)`, applique **avant toute autre etape**. Une calibration invalide est
     * une identite : on ne corrige jamais avec des coefficients qu'on vient de juger douteux.
     */
    fun apply(raw: TriAxial, c: SensorCalibration): TriAxial {
        if (!c.valid) return raw
        val n = raw.n
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        for (i in 0 until n) {
            x[i] = c.scale[0] * (raw.x[i] - c.offsetG[0])
            y[i] = c.scale[1] * (raw.y[i] - c.offsetG[1])
            z[i] = c.scale[2] * (raw.z[i] - c.offsetG[2])
        }
        return TriAxial(raw.fsHz, raw.t0Ns, x, y, z)
    }

    // ------------------------------------------------------------------
    // Volet B — rituel de calibration mecanique
    // ------------------------------------------------------------------

    /**
     * Extraction du rituel guide de 70 s (§3.3, volet B) :
     * phase 1, 30 s d'immobilite -> `floorCal` ; phase 2, 10 dorsiflexions « confortables »
     * guidees par un metronome a 3 s d'intervalle -> `gainCal` ; phase 3, 10 s de retour au calme.
     *
     * ```
     * floorCal = p50( env_c ) sur la phase 1
     * gainCal  = mediane des 10 amplitudes crete de env_c dans [t_i, t_i + 2 s]
     * snrCal   = gainCal / floorCal
     * ```
     *
     * Les instants `t_i` sont donnes par le metronome : **la verite terrain est connue**, on ne
     * detecte rien, on mesure a un instant impose. C'est ce qui rend `gainCal` reproductible
     * d'une nuit a l'autre, la ou une detection introduirait sa propre variabilite.
     *
     * @param kickWindowSec fenetre de mesure apres chaque top du metronome (2 s dans §3.3).
     * @param baselineGainG mediane des `gainCal` des nuits precedentes de la meme campagne.
     *   Si fournie, un ecart de plus de [outlierTolerance] leve `outlierVsBaseline` : la valeur
     *   reste affichee mais doit etre exclue de la tendance et de l'ICC. Sans ce garde-fou, on
     *   compare des pommes et des poires en croyant mesurer une variabilite biologique.
     */
    fun fromRitual(
        env: Signal1D,
        stillFromMs: Long,
        stillToMs: Long,
        kickOnsetsMs: LongArray,
        kickWindowSec: Double = 2.0,
        baselineGainG: Float? = null,
        outlierTolerance: Double = DEFAULT_OUTLIER_TOLERANCE,
        sensor: SensorCalibration? = null,
    ): NightCalibration {
        val fs = env.fsHz
        val n = env.n
        fun idx(ms: Long): Int = Math.round(ms * fs / 1000.0).toInt().coerceIn(0, n)

        val sFrom = idx(stillFromMs)
        val sTo = idx(stillToMs)
        val floorCal = if (sTo > sFrom) {
            Numeric.percentile(env.v, sFrom, sTo, 50.0, FloatArray(sTo - sFrom))
        } else Float.NaN

        val kickWin = Numeric.samples(kickWindowSec, fs)
        val peaks = ArrayList<Float>(kickOnsetsMs.size)
        for (t in kickOnsetsMs) {
            val from = idx(t)
            val to = min(n, from + kickWin)
            var peak = Float.NaN
            for (i in from until to) {
                val v = env.v[i]
                if (v.isNaN()) continue
                if (peak.isNaN() || v > peak) peak = v
            }
            if (!peak.isNaN()) peaks.add(peak)
        }
        if (peaks.isEmpty()) {
            return NightCalibration(sensor, Float.NaN, floorCal, Float.NaN, GainSource.NONE, false)
        }
        val gainCal = Numeric.median(peaks.toFloatArray())
        val snr = if (floorCal.isNaN() || floorCal <= 0f) Float.NaN else gainCal / floorCal
        return NightCalibration(
            sensor = sensor,
            gainCalG = gainCal,
            floorCalG = floorCal,
            snrCal = snr,
            gainSource = GainSource.RITUAL,
            outlierVsBaseline = isOutlier(gainCal, baselineGainG, outlierTolerance),
        )
    }

    /**
     * Repli quand le rituel n'a pas ete fait (l'utilisateur oublie, l'ecran ne s'affiche pas).
     *
     * Etalon interne : la **mediane des amplitudes crete des mouvements corporels grossiers** de
     * la nuit. Les retournements sont un evenement physiologiquement stereotype, frequent
     * (20 a 60 par nuit) et d'amplitude relativement stable (Sicbaldi : 377 +/- 63 mg pendant le
     * sommeil, soit un CV de 17 % a travers les sujets). C'est moins bon que le rituel, et
     * nettement mieux que rien.
     *
     * `gainSource` doit accompagner **chaque** PLMI publie : un PLMI cale sur un etalon
     * `GROSS_BODY` et un PLMI cale sur un `RITUAL` ne sont pas sur la meme echelle.
     */
    fun fromGrossBodyMovements(
        clms: List<Clm>,
        baselineGainG: Float? = null,
        outlierTolerance: Double = DEFAULT_OUTLIER_TOLERANCE,
        sensor: SensorCalibration? = null,
    ): NightCalibration {
        val peaks = ArrayList<Float>()
        val floors = ArrayList<Float>()
        for (c in clms) {
            if ((c.flags and ClmFlags.GROSS_BODY) == 0) continue
            if (!c.peakAmpG.isNaN()) peaks.add(c.peakAmpG)
            if (!c.noiseFloorG.isNaN()) floors.add(c.noiseFloorG)
        }
        if (peaks.isEmpty()) {
            return NightCalibration(sensor, Float.NaN, Float.NaN, Float.NaN, GainSource.NONE, false)
        }
        val gain = Numeric.median(peaks.toFloatArray())
        val floor = if (floors.isEmpty()) Float.NaN else Numeric.median(floors.toFloatArray())
        val snr = if (floor.isNaN() || floor <= 0f) Float.NaN else gain / floor
        return NightCalibration(
            sensor = sensor,
            gainCalG = gain,
            floorCalG = floor,
            snrCal = snr,
            gainSource = GainSource.GROSS_BODY,
            outlierVsBaseline = isOutlier(gain, baselineGainG, outlierTolerance),
        )
    }

    /**
     * Controle qualite inter-nuits (§3.3). `true` = nuit non comparable a sa campagne :
     * « serrage du bracelet probablement different — resserrer et refaire ».
     */
    fun isOutlier(gainCalG: Float, baselineGainG: Float?, tolerance: Double = DEFAULT_OUTLIER_TOLERANCE): Boolean {
        val b = baselineGainG ?: return false
        if (b.isNaN() || b <= 0f || gainCalG.isNaN()) return false
        return abs(gainCalG - b) / b > tolerance
    }

    // ------------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------------

    /** Moyenne des trois axes sur `[from, to)` si la fenetre est statique, `null` sinon. */
    private fun meanIfStill(t: TriAxial, from: Int, to: Int, sdLimit: Float): DoubleArray? {
        val axes = arrayOf(t.x, t.y, t.z)
        val means = DoubleArray(3)
        for (a in 0..2) {
            val arr = axes[a]
            var sum = 0.0; var sum2 = 0.0; var cnt = 0
            for (i in from until to) {
                val v = arr[i]
                if (v.isNaN()) continue
                sum += v; sum2 += v.toDouble() * v; cnt++
            }
            // Une fenetre a trous n'est pas une fenetre statique : on exige qu'elle soit pleine.
            if (cnt < to - from) return null
            val mean = sum / cnt
            if (sqrt(max(0.0, sum2 / cnt - mean * mean)) >= sdLimit) return null
            means[a] = mean
        }
        return means
    }

    private fun span(v: List<Double>): Double {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (d in v) { if (d < lo) lo = d; if (d > hi) hi = d }
        return hi - lo
    }

    /**
     * Regression `cible = s x brut + c` pour un axe, puis conversion en `(gain, offset)` du
     * modele `s x (a - o)`, soit `o = -c/s`. Une pente non finie ou nulle laisse l'axe inchange
     * plutot que d'introduire une correction absurde.
     */
    private fun fitAxis(rawAxis: DoubleArray, target: DoubleArray, scale: DoubleArray, offset: DoubleArray, k: Int) {
        val s = Numeric.slope(rawAxis, target)
        if (s.isNaN() || abs(s) < 1e-6) return
        var mr = 0.0; var mt = 0.0
        for (i in rawAxis.indices) { mr += rawAxis[i]; mt += target[i] }
        mr /= rawAxis.size; mt /= target.size
        val c = mt - s * mr
        scale[k] = s
        offset[k] = -c / s
    }
}
