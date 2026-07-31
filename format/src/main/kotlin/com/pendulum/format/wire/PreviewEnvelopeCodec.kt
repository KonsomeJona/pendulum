package com.pendulum.format.wire

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Quantification de l'enveloppe d'apercu : RMS decime a 1 Hz, un octet par seconde.
 *
 * **Pourquoi logarithmique.** Le signal utile s'etale sur quatre decades — du plancher de bruit
 * du MEMS (~1e-3 m/s^2) a un a-coup de cheville (~40 m/s^2). Une quantification lineaire sur
 * 8 bits donnerait un pas de 0,16 m/s^2, c'est-a-dire deux niveaux pour tout ce qui se passe
 * pendant le sommeil : l'apercu serait plat toute la nuit puis sature au lever. En
 * logarithmique, l'erreur est **relative** et constante (~2 % de demi-pas), ce qui est
 * exactement la propriete qu'on veut pour un trace destine a l'oeil.
 *
 * **Cet apercu ne sert jamais au calcul.** Il est la pour voir que l'enregistrement est vivant ;
 * tout chiffre est calcule sur les chunks bruts, jamais sur ces octets.
 */
object PreviewEnvelopeCodec {

    /** 900 s = 15 min glissantes a 1 Hz, soit 900 octets — bien en deca du plafond d'un `DataItem`. */
    const val LENGTH = 900

    /** Plancher : en dessous, on ne distingue plus le signal du bruit propre du capteur. */
    const val MIN_MS2 = 1e-3

    /** Plafond : ~4 g en RMS sur une seconde, largement au-dela de ce qu'une cheville produit. */
    const val MAX_MS2 = 40.0

    /** Reserve pour « sous le plancher » : le niveau 0 n'est pas une valeur, c'est un etat. */
    private const val LEVELS = 255.0

    private val LOG_SPAN = ln(MAX_MS2 / MIN_MS2)

    /** Quantifie un RMS en m/s^2 vers `0..255`. 0 signifie « sous [MIN_MS2] », pas « zero exact ». */
    fun quantize(rms: Double): Int {
        if (!rms.isFinite() || rms <= MIN_MS2) return 0
        val level = 1 + (LEVELS - 1) * ln(rms / MIN_MS2) / LOG_SPAN
        return level.roundToInt().coerceIn(1, 255)
    }

    /** Inverse de [quantize]. Le niveau 0 rend [MIN_MS2], borne superieure de ce qu'il represente. */
    fun dequantize(level: Int): Double {
        require(level in 0..255) { "niveau hors bornes : $level" }
        if (level == 0) return MIN_MS2
        return MIN_MS2 * exp(LOG_SPAN * (level - 1) / (LEVELS - 1))
    }

    /**
     * Encode une fenetre de [LENGTH] valeurs RMS. Une fenetre plus courte (debut de nuit) est
     * completee a zero **en tete** : l'octet le plus recent est toujours le dernier, ce qui
     * evite au telephone d'avoir a savoir depuis combien de temps la nuit a commence.
     */
    fun encode(rms: DoubleArray): ByteArray {
        require(rms.size <= LENGTH) { "fenetre trop longue : ${rms.size} > $LENGTH" }
        val out = ByteArray(LENGTH)
        val offset = LENGTH - rms.size
        for (i in rms.indices) out[offset + i] = quantize(rms[i]).toByte()
        return out
    }

    /** Decode une fenetre complete en m/s^2. */
    fun decode(envU8: ByteArray): DoubleArray {
        require(envU8.size == LENGTH) { "fenetre de $LENGTH octets attendue, recu ${envU8.size}" }
        return DoubleArray(LENGTH) { dequantize(envU8[it].toInt() and 0xFF) }
    }
}
