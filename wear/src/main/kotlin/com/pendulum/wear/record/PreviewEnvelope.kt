package com.pendulum.wear.record

import com.pendulum.format.wire.PreviewEnvelopeCodec
import kotlin.math.sqrt

/**
 * Enveloppe RMS decimee a 1 Hz sur les quinze dernieres minutes, quantifiee u8 logarithmique :
 * 900 octets qui voyagent dans une salve deja payee, pour environ +0,3 % de son volume et
 * **zero reveil radio supplementaire**.
 *
 * **Cet apercu ne sert jamais au calcul.** Tout chiffre publie est recalcule sur les chunks
 * bruts, cote telephone. Ici : preuve de vie et forme du signal, rien d'autre.
 *
 * Le passe-haut est une simple soustraction de moyenne glissante exponentielle : la gravite est
 * quasi continue, le mouvement ne l'est pas. On ne reutilise pas `:algo`, dont les fonctions
 * travaillent sur des signaux materialises alors qu'ici tout est en flux, echantillon par
 * echantillon, sur un budget de calcul qui doit rester invisible dans la consommation.
 */
class PreviewEnvelope(
    rateHz: Int,
    /** Appele a chaque seconde close. Le detecteur de reveil s'y branche : il n'y a aucune
     *  raison de recalculer une seconde fois une RMS qui vient d'etre produite. */
    private val onSecond: (rms: Double, tsNs: Long) -> Unit = { _, _ -> },
) {

    /** Constante de temps du retrait de gravite, en secondes. */
    private val tauSec = 0.5

    private var alpha = 1.0 / (tauSec * rateHz + 1.0)
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var primed = false

    private var bucketStartNs = 0L
    private var sumSq = 0.0
    private var n = 0

    /** Tampon circulaire des 900 dernieres secondes. */
    private val ring = DoubleArray(PreviewEnvelopeCodec.LENGTH)
    private var head = 0
    private var filled = 0

    fun onRateChanged(rateHz: Int) {
        alpha = 1.0 / (tauSec * rateHz + 1.0)
    }

    fun onSample(x: Float, y: Float, z: Float, tsNs: Long) {
        if (!primed) {
            gx = x.toDouble(); gy = y.toDouble(); gz = z.toDouble()
            primed = true
            bucketStartNs = tsNs
        }
        gx += alpha * (x - gx)
        gy += alpha * (y - gy)
        gz += alpha * (z - gz)
        val dx = x - gx
        val dy = y - gy
        val dz = z - gz
        sumSq += dx * dx + dy * dy + dz * dz
        n++

        if (tsNs - bucketStartNs >= 1_000_000_000L) {
            val rms = if (n > 0) sqrt(sumSq / n) else 0.0
            push(rms)
            onSecond(rms, tsNs)
            sumSq = 0.0
            n = 0
            bucketStartNs = tsNs
        }
    }

    private fun push(rms: Double) {
        ring[head] = rms
        head = (head + 1) % ring.size
        if (filled < ring.size) filled++
    }

    /**
     * Fenetre de 900 octets, l'octet le plus recent en queue. Une nuit qui vient de commencer
     * est completee a zero **en tete**, ce qui evite au telephone d'avoir a savoir depuis
     * combien de temps elle dure.
     */
    fun snapshot(): ByteArray {
        val values = DoubleArray(filled)
        val start = (head - filled + ring.size) % ring.size
        for (i in 0 until filled) values[i] = ring[(start + i) % ring.size]
        return PreviewEnvelopeCodec.encode(values)
    }
}
