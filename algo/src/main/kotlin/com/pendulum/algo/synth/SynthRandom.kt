package com.pendulum.algo.synth

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Generateur pseudo-aleatoire du paquet `synth`. **Seule** source d'alea autorisee ici.
 *
 * Trois exigences, dans cet ordre :
 *
 *  1. **Determinisme total** (`ALGO-v2.md` §5, test T13). L'algorithme est SplitMix64, ecrit ici en
 *     entier : aucune dependance a `java.util.Random` (dont `nextGaussian` a un etat cache et une
 *     methode polaire a rejet, donc un nombre d'appels variable), aucune horloge, aucun `hashCode`
 *     d'objet. Deux executions avec la meme graine produisent la meme suite, bit pour bit, sur
 *     n'importe quelle JVM.
 *  2. **Sous-flots independants nommes** ([stream]). C'est ce qui rend les tests T8 a T11 possibles :
 *     regenerer une nuit en changeant *un seul* distracteur (trous, gain mecanique, `fs`) ne doit pas
 *     deplacer les mouvements. Si tous les tirages sortaient d'un flot unique, ajouter un trou
 *     consommerait des tirages et decalerait toute la nuit — on comparerait alors deux nuits
 *     differentes en croyant mesurer l'effet du trou.
 *  3. **Loi log-normale de premiere classe** : durees et amplitudes de §5.1 en sont, et le
 *     `sigmaLog` doit rester un parametre (il pilote la pente de la courbe de sensibilite, T5).
 *
 * La loi normale est tiree par Box-Muller *sans* cache : chaque appel consomme exactement deux
 * uniformes. Un cache rendrait la suite dependante de la parite du nombre d'appels precedents,
 * c'est-a-dire fragile a toute modification du code appelant.
 */
class SynthRandom private constructor(private val rootSeed: Long, private var state: Long) {

    constructor(seed: Long) : this(seed, seed)

    /** Sous-flot nomme, derive de la graine **racine** (jamais de l'etat courant). */
    fun stream(label: String): SynthRandom {
        val h = fnv64(label)
        val s = mix64(rootSeed xor h)
        return SynthRandom(s, s)
    }

    fun nextLong(): Long {
        state += GAMMA
        return mix64(state)
    }

    /** Uniforme dans `[0, 1)`, 53 bits de mantisse. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * TWO_POW_MINUS_53

    /** Uniforme dans `(0, 1]` — le complement de [nextDouble], pour les logarithmes. */
    fun nextDoublePositive(): Double = 1.0 - nextDouble()

    fun nextInt(boundExclusive: Int): Int {
        require(boundExclusive > 0) { "borne doit etre > 0" }
        return (nextDouble() * boundExclusive).toInt().coerceAtMost(boundExclusive - 1)
    }

    /** Entier uniforme dans `[lo, hi]`, bornes incluses. */
    fun nextIntRange(lo: Int, hi: Int): Int = if (hi <= lo) lo else lo + nextInt(hi - lo + 1)

    fun uniform(lo: Double, hi: Double): Double = lo + (hi - lo) * nextDouble()

    /** Uniforme sur l'echelle logarithmique : donne autant de poids a `[3, 10[` qu'a `[10, 30[`. */
    fun logUniform(lo: Double, hi: Double): Double {
        require(lo > 0.0 && hi >= lo) { "bornes log-uniformes invalides" }
        return exp(uniform(ln(lo), ln(hi)))
    }

    fun nextBoolean(p: Double): Boolean = nextDouble() < p

    /** Box-Muller, deux uniformes par appel, sans etat cache. */
    fun nextGaussian(): Double {
        val u1 = nextDoublePositive()
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(TWO_PI * u2)
    }

    /**
     * Log-normale de mediane `median` et d'ecart-type logarithmique `sigmaLog`, tronquee a
     * `[lo, hi]`. La troncature est faite par **re-tirage** (jusqu'a 64 essais puis ecretage) et non
     * par ecretage direct : ecreter empilerait une masse de Dirac sur les bornes, ce qui fausserait
     * la queue basse — precisement celle qui porte les mouvements sous le seuil de detection, donc la
     * pente de la courbe de sensibilite T5.
     */
    fun logNormal(median: Double, sigmaLog: Double, lo: Double, hi: Double): Double {
        require(median > 0.0) { "mediane doit etre > 0" }
        val mu = ln(median)
        repeat(64) {
            val v = exp(mu + sigmaLog * nextGaussian())
            if (v in lo..hi) return v
        }
        return exp(mu).coerceIn(lo, hi)
    }

    /**
     * Log-normale calee sur une **moyenne** et un **ecart-type** arithmetiques (c'est ainsi que la
     * litterature publie les durees de CLM : 4,2 +/- 1,4 s, Sforza 2005).
     */
    fun logNormalFromMeanSd(mean: Double, sd: Double, lo: Double, hi: Double): Double {
        require(mean > 0.0 && sd >= 0.0) { "moyenne/ecart-type invalides" }
        val cv2 = (sd / mean) * (sd / mean)
        val sigma = sqrt(ln(1.0 + cv2))
        val median = mean / exp(sigma * sigma / 2.0)
        return logNormal(median, sigma, lo, hi)
    }

    private companion object {
        const val GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val TWO_POW_MINUS_53: Double = 1.0 / (1L shl 53)
        const val TWO_PI: Double = 2.0 * Math.PI

        fun mix64(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }

        /** FNV-1a 64 bits sur les octets UTF-8 du label. Stable entre JVM, contrairement a `hashCode`. */
        fun fnv64(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001B3L
            }
            return h
        }
    }
}

/** Ecart-type logarithmique correspondant a un coefficient de variation en pourcent. */
internal fun sigmaLogOfCvPct(cvPct: Double): Double = sqrt(ln(1.0 + (cvPct / 100.0) * (cvPct / 100.0)))
