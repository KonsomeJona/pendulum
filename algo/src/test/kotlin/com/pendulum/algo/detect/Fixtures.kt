package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.Clm
import com.pendulum.algo.model.ClmRejectReason
import com.pendulum.algo.model.DenominatorIndependence
import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.GainSource
import com.pendulum.algo.model.MaskSource
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.SleepMask
import com.pendulum.algo.model.SleepWindow
import com.pendulum.algo.model.Stage
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixtures synthetiques minimales du paquet `detect`. Volontairement independantes de
 * `com.pendulum.algo.dsp` et de `com.pendulum.algo.synth` : ces tests doivent isoler la machine d'etats,
 * pas la chaine de filtrage.
 */
internal const val FS = 50.0

internal fun samples(sec: Double): Int = Math.round(sec * FS).toInt()

/** Signal de magnitude de mouvement, en g, tout a zero. */
internal fun quietMagnitude(durSec: Double): FloatArray = FloatArray(samples(durSec))

/**
 * Ajoute une bouffee d'amplitude `ampG` sur `[startSec, startSec + durSec)`, avec des flancs en
 * cosinus sureleve de `rampSec` inclus dans la duree. Un plateau constant `A` donne une enveloppe
 * RMS exactement egale a `A`, ce qui rend les seuils lisibles a la main dans les tests.
 */
internal fun burst(
    m: FloatArray,
    startSec: Double,
    durSec: Double,
    ampG: Float,
    rampSec: Double = 0.10,
) {
    val from = samples(startSec)
    val len = samples(durSec)
    val ramp = samples(rampSec).coerceAtMost(len / 2).coerceAtLeast(1)
    for (i in 0 until len) {
        val idx = from + i
        if (idx !in m.indices) continue
        val shape = when {
            i < ramp -> 0.5 * (1.0 - cos(PI * i / ramp))
            i >= len - ramp -> 0.5 * (1.0 - cos(PI * (len - 1 - i) / ramp))
            else -> 1.0
        }
        m[idx] = (m[idx] + ampG * shape).toFloat()
    }
}

/** Enveloppe RMS **centree** a deux echelles, comme l'etape 2. */
internal fun dualEnvelope(m: FloatArray, coarseSec: Double = 0.50, fineSec: Double = 0.15): DualEnvelope =
    DualEnvelope(
        coarse = Signal1D(FS, 0L, rms(m, coarseSec)),
        fine = Signal1D(FS, 0L, rms(m, fineSec)),
        coarseWinSec = coarseSec,
        fineWinSec = fineSec,
    )

private fun rms(m: FloatArray, winSec: Double): FloatArray {
    val w = samples(winSec).coerceAtLeast(1)
    // MEME convention que `Numeric.halfLeft` : fenetre paire, la case supplementaire va a DROITE.
    // Avec `w / 2` les fixtures etaient decalees d'un echantillon vers la gauche par rapport a
    // l'enveloppe que la chaine de production fabrique, et toute assertion de datation portait ce
    // decalage sans le dire.
    val half = Numeric.halfLeft(w)
    val out = FloatArray(m.size)
    for (i in m.indices) {
        val from = (i - half).coerceAtLeast(0)
        val to = (i - half + w).coerceAtMost(m.size)
        var s = 0.0
        for (k in from until to) s += m[k].toDouble() * m[k]
        out[i] = sqrt(s / (to - from)).toFloat()
    }
    return out
}

internal fun constantFloor(n: Int, valueG: Float = 0.005f): Signal1D =
    Signal1D(FS, 0L, FloatArray(n) { valueG })

internal fun noExtrapolation(n: Int): BooleanArray = BooleanArray(n)

/** Gravite constante : montre immobile, aucun changement d'orientation. */
internal fun flatGravity(n: Int): TriAxial =
    TriAxial(FS, 0L, FloatArray(n), FloatArray(n), FloatArray(n) { 1f })

/**
 * Gravite qui tourne de `deg` autour de l'axe X entre `startSec` et `startSec + durSec`, puis reste
 * a sa nouvelle orientation. Si `andBack` est vrai, elle revient a l'orientation d'origine sur la
 * meme duree : c'est un mouvement ample, pas un changement de posture.
 */
internal fun rotatingGravity(
    n: Int,
    startSec: Double,
    durSec: Double,
    deg: Double,
    andBack: Boolean = false,
): TriAxial {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
    val from = samples(startSec)
    val len = samples(durSec)
    for (i in 0 until n) {
        val u = when {
            i < from -> 0.0
            i < from + len -> (i - from).toDouble() / len
            !andBack -> 1.0
            i < from + 2 * len -> 1.0 - (i - from - len).toDouble() / len
            else -> 0.0
        }
        val a = Math.toRadians(deg * u)
        y[i] = sin(a).toFloat()
        z[i] = cos(a).toFloat()
    }
    return TriAxial(FS, 0L, x, y, z)
}

internal fun noCalibration(): NightCalibration = NightCalibration(
    sensor = null,
    gainCalG = 0f,
    floorCalG = 0f,
    snrCal = 0f,
    gainSource = GainSource.NONE,
    outlierVsBaseline = false,
)

internal fun wholeNight(n: Int): List<Segment> = listOf(Segment(0, n))

/** Masque trivial : toute la fenetre est du sommeil. */
internal fun sleepAllNight(durSec: Double): SleepMask {
    val endMs = Math.round(durSec * 1000.0)
    return SleepMask(
        windows = listOf(SleepWindow(0L, endMs, Stage.SLEEP)),
        source = MaskSource.ACCEL_IMMOBILITY,
        sptMin = durSec / 60.0,
        tstMin = durSec / 60.0,
        wasoMin = 0.0,
        analysableTstMin = durSec / 60.0,
        analysableSptMin = durSec / 60.0,
        corrected = false,
        lagAppliedMs = 0L,
        independence = DenominatorIndependence.CIRCULAR,
        fixedPointConverged = true,
    )
}

/** CLM synthetique pour les tests de [SeriesBuilder]. */
internal fun clmAt(
    onsetSec: Double,
    durationMs: Int = 2000,
    flags: Int = 0,
    reject: ClmRejectReason? = null,
): Clm {
    val onsetIdx = samples(onsetSec)
    return Clm(
        onsetIdx = onsetIdx,
        offsetIdx = onsetIdx + Math.round(durationMs * FS / 1000.0).toInt(),
        onsetMsRel = Math.round(onsetSec * 1000.0),
        durationMs = durationMs,
        peakAmpG = 0.1f,
        medianAmpG = 0.05f,
        noiseFloorG = 0.005f,
        thresholdOnG = 0.04f,
        thresholdOffG = 0.0125f,
        tiltChangeDeg = 1f,
        tiltExcursionDeg = 5f,
        flags = flags,
        reject = reject,
    )
}
