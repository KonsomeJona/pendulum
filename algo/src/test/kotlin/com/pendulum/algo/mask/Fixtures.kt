package com.pendulum.algo.mask

import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fabriques synthetiques du paquet `mask`.
 *
 * `fs` volontairement a 10 Hz et non a 50 : le masque travaille par epoques de 5 s, aucun de ses
 * criteres n'a de contenu au-dessus de 1 Hz, et une nuit de 8 h a 10 Hz tient dans 288 000
 * echantillons. Cela verifie au passage que rien n'est code en dur a 50 Hz.
 *
 * Aucun aleatoire, aucune horloge : les tests doivent etre reproductibles au bit.
 */
internal const val FS = 10.0

/** Plancher de bruit constant, et enveloppe au repos. Rapport 1 : le repos est sous tout seuil. */
internal const val FLOOR_G = 0.004f

internal fun samples(sec: Double): Int = Math.round(sec * FS).toInt()

/**
 * Nuit synthetique : gravite d'orientation controlee, enveloppe au repos, plancher constant.
 * L'orientation est parametree par un seul angle de tangage `theta` autour de `x`, ce qui suffit :
 * le critere teste est un angle entre deux directions, pas une orientation absolue.
 */
internal class Night(val durSec: Double) {
    val n: Int = samples(durSec)
    private val theta = DoubleArray(n)
    private val envV = FloatArray(n) { FLOOR_G }
    private val floorV = FloatArray(n) { FLOOR_G }

    /** Bouffee d'amplitude constante. Renvoie l'intervalle, pret a servir d'`ignoreIntervals`. */
    fun burst(startSec: Double, durSec: Double, ampG: Float): Segment {
        val from = samples(startSec).coerceIn(0, n)
        val to = samples(startSec + durSec).coerceIn(0, n)
        for (i in from until to) envV[i] = ampG
        return Segment(from, to)
    }

    /** Serie periodique de bouffees identiques : le cas qui casse la regle des 5 min. */
    fun periodic(startSec: Double, imiSec: Double, durSec: Double, ampG: Float, count: Int): List<Segment> =
        (0 until count).map { burst(startSec + it * imiSec, durSec, ampG) }

    /**
     * Reorientation **persistante** de `deg` degres, en rampe lineaire sur `rampSec` : c'est la
     * signature d'un changement de posture ou d'un mouvement corporel grossier, par opposition a
     * une secousse qui revient a sa position de depart.
     */
    fun tilt(atSec: Double, deg: Double, rampSec: Double = 2.0) {
        val from = samples(atSec).coerceIn(0, n)
        val to = samples(atSec + rampSec).coerceIn(from, n)
        val rad = deg * PI / 180.0
        for (i in from until to) theta[i] += rad * (i - from + 1).toDouble() / (to - from)
        for (i in to until n) theta[i] += rad
    }

    fun gravity(): TriAxial {
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val gz = FloatArray(n)
        for (i in 0 until n) {
            gx[i] = 0f
            gy[i] = sin(theta[i]).toFloat()
            gz[i] = cos(theta[i]).toFloat()
        }
        return TriAxial(FS, 0L, gx, gy, gz)
    }

    fun env(): Signal1D = Signal1D(FS, 0L, envV.copyOf())

    fun floor(): Signal1D = Signal1D(FS, 0L, floorV.copyOf())

    fun segments(): List<Segment> = listOf(Segment(0, n))
}

/**
 * Rotation constante du boitier, par la formule de Rodrigues. Applique la **meme** rotation a tous
 * les echantillons : c'est exactement ce que produit un bracelet remis a l'envers d'une nuit sur
 * l'autre, et le masque ne doit pas s'en apercevoir.
 */
internal fun rotate(g: TriAxial, ax: Double, ay: Double, az: Double, angleDeg: Double): TriAxial {
    val norm = sqrt(ax * ax + ay * ay + az * az)
    val kx = ax / norm
    val ky = ay / norm
    val kz = az / norm
    val c = cos(angleDeg * PI / 180.0)
    val s = sin(angleDeg * PI / 180.0)
    val x = FloatArray(g.n)
    val y = FloatArray(g.n)
    val z = FloatArray(g.n)
    for (i in 0 until g.n) {
        val vx = g.x[i].toDouble()
        val vy = g.y[i].toDouble()
        val vz = g.z[i].toDouble()
        val cx = ky * vz - kz * vy
        val cy = kz * vx - kx * vz
        val cz = kx * vy - ky * vx
        val dot = kx * vx + ky * vy + kz * vz
        x[i] = (vx * c + cx * s + kx * dot * (1 - c)).toFloat()
        y[i] = (vy * c + cy * s + ky * dot * (1 - c)).toFloat()
        z[i] = (vz * c + cz * s + kz * dot * (1 - c)).toFloat()
    }
    return TriAxial(g.fsHz, g.t0Ns, x, y, z)
}
