package com.pendulum.algo.synth

import com.pendulum.algo.dsp.Filters
import com.pendulum.algo.dsp.Numeric
import kotlin.math.abs
import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Le **modele de mouvement** de `docs/03-algorithm.md` §7.5 (`fr/ALGO-v2.md` §5.1), teste pour
 * lui-meme et non a travers la chaine complete.
 *
 * Deux choses y sont verifiees, et elles sont de nature differente :
 *
 *  1. le tableau de calibration 30 / 184 / 985 mg sort bien du modele — c'est l'argument de validite
 *     le plus fort dont dispose le generateur, et il n'etait assert nulle part ;
 *  2. la phase de maintien n'est pas accelerometriquement muette. C'etait la cause racine de la
 *     fragmentation de T6 : un maintien immobile de ~3,6 s produisait ~2,3 s de silence, et le
 *     detecteur decoupait chaque mouvement en morceaux en appliquant litteralement la regle d'offset
 *     AASM a 0,50 s.
 */
class MovementModelTest {

    private val fs = 50.0

    /** Duree publiee (Sforza 2005) et plage publiee de `T_rise` (§5.1). */
    private val totalSec = 4.2
    private val tRisesSec = doubleArrayOf(0.15, 0.30, 0.50)

    /**
     * **§5.1 — le tableau de calibration.** Ces quatre valeurs sont citees dans toute la
     * documentation comme la preuve que le modele « se cale de lui-meme sur les chiffres publies ».
     * Elles chiffrent le seul terme **tangentiel** `r . theta''`, pas la crete de la norme du rendu :
     * c'est [MovementKinematics.peakTangentialG] qui les calcule, et c'est la raison pour laquelle
     * cette fonction existe.
     */
    @Test
    @DisplayName("§5.1 — le tableau 30 / 184 / 985 mg sort du modele sans aucun reglage")
    fun peakTangentialMatchesTheCalibrationTable() {
        fun peak(deg: Double, tRiseSec: Double, radiusM: Double): Double =
            MovementKinematics(Math.toRadians(deg), tRiseSec, 0.0, tRiseSec).peakTangentialG(radiusM)

        assertThat(peak(4.0, 0.45, 0.15)).`as`("petit CLM").isCloseTo(0.030, within(1e-3))
        assertThat(peak(10.0, 0.35, 0.22)).`as`("CLM moyen").isCloseTo(0.184, within(1e-3))
        assertThat(peak(20.0, 0.25, 0.30)).`as`("gros CLM").isCloseTo(0.985, within(2e-3))
        assertThat(peak(15.0, 0.30, 0.02)).`as`("cheville seule").isCloseTo(0.034, within(1e-3))
    }

    /**
     * **Le maintien soutient au moins 0,40 fois la crete balistique.**
     *
     * La constante du modele est `holdActivityRatio = 0,50`, lue sur le PAM-RL de Sforza : seuil de
     * decroissance 100 mg contre seuil d'entree 200 mg. L'assertion porte sur l'**acceleration
     * angulaire**, la grandeur ou cette constante est definie, sans la fenetre RMS de 0,5 s qui
     * melangerait la forme du maintien et celle de la flexion.
     *
     * Le realise n'est pas exactement 0,50 : le nombre de creux est un entier, donc la periode d'un
     * creux vaut `T_hold / round(T_hold / 2.T_rise)` et non exactement `2 . T_rise`. L'ecart maximal
     * sur la plage publiee de `T_rise` est de 12 % (0,439 a `T_rise = 0,50 s`), d'ou la borne a 0,40.
     */
    @Test
    @DisplayName("§5.1 — le maintien soutient >= 0,40 fois la crete balistique (PAM-RL 100/200 mg)")
    fun theActiveHoldSustainsTheSforzaRatio() {
        for (tRise in tRisesSec) {
            val kin = kinematics(tRise, holdCycles = cyclesFor(tRise), holdRatio = 0.50)
            val ballistic = peakAbs(kin, 0.0, tRise)
            val hold = peakAbs(kin, tRise + 0.05, tRise + kin.tHoldSec - 0.05)
            assertThat(hold / ballistic)
                .`as`("crete de maintien / crete balistique, T_rise = %.2f s", tRise)
                .isGreaterThanOrEqualTo(0.40)
        }
    }

    /**
     * **Aucun silence interieur ne dure une demi-seconde.**
     *
     * Le seuil de duree est la regle elle-meme : `offHoldSec = 0,50 s`, la duree de relachement
     * continu qui, selon l'AASM/WASM, termine un mouvement. Un modele qui produit un silence plus
     * long que cela affirme quelque chose que la regle qu'il sert a tester declare impossible.
     *
     * Le niveau de reference est le rapport d'hysteresis du detecteur, `k_off / k_on = 2,5 / 8,0 =
     * 0,3125` : en dessous, un evenement dont la crete atteint tout juste `Theta_on` serait relache.
     */
    @Test
    @DisplayName("§5.1 — un mouvement de 4,2 s ne contient aucun silence d'une demi-seconde")
    fun theActiveHoldNeverGoesQuietForHalfASecond() {
        for (tRise in tRisesSec) {
            assertThat(longestQuietRunSec(cyclesFor(tRise), tRise))
                .`as`("plus long silence interieur, T_rise = %.2f s", tRise)
                .isLessThan(0.50)
        }
    }

    /**
     * **Assertion inversee, et c'est le but** — la meme mesure sur le plateau immobile d'origine.
     *
     * Elle a la meme fonction que T11 : montrer que la correction corrige quelque chose. Si ce test
     * venait a passer sous 0,50 s, le maintien actif ne servirait a rien et devrait etre retire
     * plutot que garde par prudence. Elle chiffre aussi le defaut : plus de deux secondes de
     * silence, d'ou le decoupage de chaque mouvement en morceaux d'environ 1,1 s mesure sur la nuit
     * nominale (`07-validation.md` §5.5).
     */
    @Test
    @DisplayName("§5.1 — le plateau immobile d'origine, lui, reste muet plus de 2 s (inversee)")
    fun theStaticPlateauIsSilentForOverTwoSeconds() {
        assertThat(longestQuietRunSec(holdCycles = 0, tRiseSec = 0.30))
            .`as`("plus long silence du plateau immobile")
            .isGreaterThan(2.0)
    }

    // ---------------------------------------------------------------------------------------

    /** Nombre de creux de maintien : un par `2 . T_rise`, comme le fait `NightSynth`. */
    private fun cyclesFor(tRiseSec: Double): Int =
        Math.round((totalSec - 2.0 * tRiseSec) / (2.0 * tRiseSec)).toInt().coerceAtLeast(1)

    private fun kinematics(tRiseSec: Double, holdCycles: Int, holdRatio: Double): MovementKinematics {
        val theta = Math.toRadians(12.0)
        return MovementKinematics(
            theta, tRiseSec, totalSec - 2.0 * tRiseSec, tRiseSec, holdCycles, holdRatio * theta,
        )
    }

    /** Crete de `|theta''|` sur `[fromSec, toSec)`, echantillonnee finement. */
    private fun peakAbs(kin: MovementKinematics, fromSec: Double, toSec: Double): Double {
        var best = 0.0
        var t = fromSec
        while (t < toSec) {
            val v = abs(kin.thetaDDot(t))
            if (v > best) best = v
            t += 1e-3
        }
        return best
    }

    /**
     * Plus longue plage, **a l'interieur du mouvement**, ou l'enveloppe RMS de 0,5 s du signal
     * passe-bande reste sous `k_off / k_on` fois sa crete. C'est la grandeur que voit un detecteur
     * accelerometrique a hysteresis.
     */
    private fun longestQuietRunSec(holdCycles: Int, tRiseSec: Double): Double {
        val kin = kinematics(tRiseSec, holdCycles, holdRatio = 0.50)
        val n = Math.round(totalSec * fs).toInt() + 1
        // Gravite le long de l'axe long du tibia : la reprojection de g contribue, comme dans une
        // vraie nuit. C'est la contribution dominante aux grands angles (§5.1).
        val r = renderMovement(kin, 0.22, 1.0, 1.0, 0.0, 0.0, fs, n)

        val env = FloatArray(n)
        Numeric.movingRms(bandPassedMagnitude(r, n), 0, n, Math.round(0.5 * fs).toInt(), env)

        var peak = 0f
        for (v in env) if (v.isFinite() && v > peak) peak = v
        val level = (2.5f / 8.0f) * peak

        // On mesure le silence **interieur** : les plages basses du debut et de la fin sont les
        // bords du mouvement, pas un creux.
        var first = -1
        var last = -1
        for (i in 0 until n) if (env[i] >= level) { if (first < 0) first = i; last = i }
        if (first < 0) return totalSec

        var longest = 0
        var run = 0
        for (i in first..last) {
            if (env[i] < level) { run++; if (run > longest) longest = run } else run = 0
        }
        return longest / fs
    }

    /** Norme L2 du rendu apres le passe-bande 0,5-8 Hz de l'etape 1, axe par axe. */
    private fun bandPassedMagnitude(r: MovementRender, n: Int): FloatArray {
        val acc = DoubleArray(n)
        for (a in arrayOf(r.dx, r.dy, r.dz)) {
            val bp = Filters.butterBandpass(fs, 0.50, 8.0, 2)
            bp.resetToDc(0f)
            for (i in 0 until n) {
                val v = bp.step(a[i].toFloat()).toDouble()
                acc[i] += v * v
            }
        }
        return FloatArray(n) { sqrt(acc[it]).toFloat() }
    }
}
