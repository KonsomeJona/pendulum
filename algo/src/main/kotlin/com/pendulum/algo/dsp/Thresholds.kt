package com.pendulum.algo.dsp

import com.pendulum.algo.model.ClmFlags
import com.pendulum.algo.model.NightCalibration
import com.pendulum.algo.model.Signal1D

/**
 * Parametres de l'etape 4. Valeurs par defaut = tableau §6.3.
 *
 * Cette classe porte les memes quatre valeurs que `com.pendulum.algo.detect.ThresholdConfig`, dont
 * elle est volontairement distincte : `dsp` calcule les courbes de seuil, `detect` decide. Le
 * detecteur construit son [ThresholdParams] depuis sa propre configuration ; on evite ainsi une
 * dependance de `dsp` vers `detect` — l'inverse du sens de la chaine.
 */
data class ThresholdParams(
    val kOn: Double = 8.0,
    val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f,
    val calFraction: Double = 0.12,
) {
    /**
     * Rapport d'hysteresis, `k_off / k_on` = 0,3125 avec les valeurs par defaut (la
     * specification l'ecrit « 0,31 »).
     *
     * Il est **calcule** et non code en dur, parce que c'est ce qui garantit la propriete
     * annoncee : l'hysteresis vaut 3,2 **quel que soit le terme dominant**. Figer 0,31 tout en
     * laissant `kOn`/`kOff` reglables casserait cette invariance des que l'un des deux bougerait,
     * et le detecteur se mettrait a relacher trop tot ou trop tard selon le regime de la nuit.
     */
    val hysteresisRatio: Double get() = kOff / kOn
}

/**
 * Les deux courbes de seuil, plus la **tracabilite du terme dominant** echantillon par
 * echantillon (`ClmFlags.ABS_FLOOR_LIMITED` / `CAL_FLOOR_LIMITED`, 0 si c'est le plancher
 * mesure qui commande).
 *
 * Cette tracabilite n'est pas un luxe de diagnostic : c'est elle qui dit si le detecteur a
 * fonctionne en regime relatif (sensibilite pilotee par le bruit de la nuit) ou en regime
 * plancher (sensibilite plafonnee). Deux nuits qui ne sont pas dans le meme regime ne sont pas
 * comparables, et c'est la comparabilite qui fait toute la valeur d'un depistage sur 5 a 7 nuits.
 */
class ThresholdCurves(
    val on: Signal1D,
    val off: Signal1D,
    val dominance: IntArray,
) {
    /** Fraction du temps ou le seuil etait plafonne par un terme non adaptatif. */
    fun limitedFraction(): Double {
        if (dominance.isEmpty()) return 0.0
        var c = 0
        for (d in dominance) if (d != 0) c++
        return c.toDouble() / dominance.size
    }
}

/**
 * Etape 4 — seuils.
 *
 * ```
 * Theta_on(t)  = max( k_on  x floor(t),  Theta_abs,         f_cal x gainCal )
 * Theta_off(t) = max( k_off x floor(t),  Theta_abs x r,     f_cal x gainCal x r )   r = k_off/k_on
 * ```
 *
 * **Pourquoi trois termes et pas un.**
 *  - `k_on x floor` : le terme adaptatif. Il suit le bruit reel de la nuit. `k_on = 8` n'est
 *    **pas** dicte par le bruit thermique — un facteur 4,8 suffirait deja a garantir moins de
 *    0,01 faux positif thermique par nuit. Les 8 sont entierement un **budget anti-artefact**.
 *    Consequence pratique : ne jamais regler `k_on` en regardant du bruit, seulement des nuits
 *    reelles (§2 etape 4, test T11).
 *  - `Theta_abs = 20 mg` : le garde-fou absolu. Sur une nuit tres calme, le terme relatif seul
 *    tomberait a 7 mg et le detecteur compterait des micro-vibrations (§1.1).
 *  - `f_cal x gainCal` : le terme de calibration. Un CLM est declare s'il atteint 12 % de
 *    l'amplitude d'une dorsiflexion volontaire confortable **de cette nuit-la** (§3.3, volet B).
 *    C'est le seul des trois qui compense le serrage du bracelet, c'est-a-dire la seule variable
 *    qui detruit la comparabilite inter-nuits.
 */
object Thresholds {

    /** Seuil de declenchement pour un plancher donne. */
    fun onAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Float {
        if (floorG.isNaN()) return Float.NaN
        val rel = (p.kOn * floorG).toFloat()
        val cal = calTerm(gainCalG, p)
        return maxOf(rel, p.absFloorG, cal)
    }

    /** Seuil de relachement. Le rapport d'hysteresis est preserve terme a terme. */
    fun offAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Float {
        if (floorG.isNaN()) return Float.NaN
        val r = p.hysteresisRatio
        val rel = (p.kOff * floorG).toFloat()
        val abs = (p.absFloorG * r).toFloat()
        val cal = (calTerm(gainCalG, p) * r).toFloat()
        return maxOf(rel, abs, cal)
    }

    /**
     * Lequel des trois termes commande le seuil de declenchement.
     * @return 0 (plancher adaptatif), [ClmFlags.ABS_FLOOR_LIMITED] ou [ClmFlags.CAL_FLOOR_LIMITED].
     *   En cas d'egalite exacte, la priorite va au terme le moins adaptatif, qui est le plus
     *   informatif a rapporter : calibration, puis plancher absolu.
     */
    fun dominanceAt(floorG: Float, gainCalG: Float, p: ThresholdParams = ThresholdParams()): Int {
        if (floorG.isNaN()) return 0
        val rel = (p.kOn * floorG).toFloat()
        val cal = calTerm(gainCalG, p)
        val abs = p.absFloorG
        return when {
            cal >= rel && cal >= abs && cal > 0f -> ClmFlags.CAL_FLOOR_LIMITED
            abs >= rel -> ClmFlags.ABS_FLOOR_LIMITED
            else -> 0
        }
    }

    /**
     * Courbes completes sur toute la nuit.
     *
     * @param gainCalG gain de calibration de la nuit ; `0` ou `NaN` desactive le troisieme terme
     *   (cas `GainSource.NONE`, ou aucun mouvement corporel grossier n'a
     *   ete observe). Le seuil se replie alors sur `max(k_on x floor, Theta_abs)`, ce qui reste
     *   correct — mais la nuit n'est plus comparable aux nuits calibrees, et c'est
     *   `NightCalibration.gainSource` qui doit accompagner le resultat publie.
     */
    fun compute(
        floor: Signal1D,
        gainCalG: Float,
        p: ThresholdParams = ThresholdParams(),
    ): ThresholdCurves {
        val n = floor.n
        val on = FloatArray(n)
        val off = FloatArray(n)
        val dom = IntArray(n)
        for (i in 0 until n) {
            val f = floor.v[i]
            on[i] = onAt(f, gainCalG, p)
            off[i] = offAt(f, gainCalG, p)
            dom[i] = dominanceAt(f, gainCalG, p)
        }
        return ThresholdCurves(
            Signal1D(floor.fsHz, floor.t0Ns, on),
            Signal1D(floor.fsHz, floor.t0Ns, off),
            dom,
        )
    }

    fun compute(floor: Signal1D, cal: NightCalibration, p: ThresholdParams = ThresholdParams()): ThresholdCurves =
        compute(floor, cal.gainCalG, p)

    private fun calTerm(gainCalG: Float, p: ThresholdParams): Float =
        if (gainCalG.isNaN() || gainCalG <= 0f) 0f else (p.calFraction * gainCalG).toFloat()
}
