package com.pendulum.algo.synth

import com.pendulum.algo.dsp.Filters
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Acceleration de la pesanteur, en m/s^2. Valeur normale, la meme que §5.1. */
internal const val G_MS2: Double = 9.80665

/**
 * Profil angulaire a **jerk minimal d'ordre 5**, le geste balistique standard en biomecanique
 * (`ALGO-v2.md` §5.1).
 *
 * ```
 * s(u)   = 10u^3 - 15u^4 + 6u^5
 * s'(u)  = 30u^2 - 60u^3 + 30u^4
 * s''(u) = 60u - 180u^2 + 120u^3          impulsion BIPOLAIRE
 * ```
 *
 * Les extrema de `s''` sont en `u = (3 +/- sqrt(3))/6`, de valeur exacte `+/- 10/sqrt(3)`, soit
 * `+/- 5,773502691896258`. C'est cette constante qui fixe le tableau de calibration de §5.1
 * (30 / 184 / 985 mg) et qui cale le modele sur la litterature.
 */
internal object MinJerk {
    const val PEAK_ACCEL_COEFF: Double = 5.773502691896258 // 10 / sqrt(3)

    fun s(u: Double): Double = u * u * u * (10.0 + u * (-15.0 + 6.0 * u))

    fun sDot(u: Double): Double = u * u * (30.0 + u * (-60.0 + 30.0 * u))

    fun sDDot(u: Double): Double = u * (60.0 + u * (-180.0 + 120.0 * u))
}

/**
 * Cinematique d'un mouvement complet : flexion (`tRise`), maintien (`tHold`), retour (`tFall`).
 *
 * La signature accelerometrique est **quadripolaire** : une paire bipolaire a la flexion, une paire
 * bipolaire au retour, separees par la phase de maintien (§5.1).
 *
 * ### La phase de maintien n'est pas un plateau immobile
 *
 * Le modele d'origine tenait `theta = theta_max` pendant `tHold`. Avec la duree publiee de 4,2 s et
 * un `tRise` de 0,15 a 0,50 s, cela donnait **~3,6 s d'angle strictement constant**, donc
 * `theta' = theta'' = 0` et un terme gravitaire reduit a un palier continu : apres le passe-haut a
 * 0,5 Hz, **plus rien**. Le detecteur voyait deux bouffees separees par ~2,3 s de silence et emettait
 * — correctement, en appliquant la regle d'offset AASM a 0,50 s — deux mouvements pour un.
 *
 * **La source de la duree contredit cette forme.** Sforza et al. 2005 (§2.4) mesure ses 4,2 s avec le
 * PAM-RL : seuil d'entree 200 mg, seuil de decroissance 100 mg, et surtout **drop-out time de 1 s** —
 * un « kick » ne se termine qu'apres une seconde entiere sous 100 mg. Un evenement contenant 2,3 s de
 * silence accelerometrique aurait donc ete decoupe en deux par le PAM-RL lui-meme, et la duree
 * moyenne publiee aurait ete de l'ordre de la moitie. Le meme raisonnement vaut si l'on lit les 4,2 s
 * comme une duree de bouffee EMG (criteres de Coleman, 0,5 a 10 s) : une bouffee EMG de 4,2 s est
 * 4,2 s de **contraction active**, pas un maintien passif.
 *
 * Le corpus de regles le dit d'ailleurs lui-meme : la regle d'offset a 0,50 s n'existe que parce
 * qu'un mouvement de jambe est un **train d'activations** separees de moins de 0,5 s. Un modele qui
 * dessine un silence de 2,3 s au milieu d'un mouvement contredit la regle que le detecteur applique.
 *
 * **Le modele retenu** : pendant le maintien, la flexion est entretenue par une activite musculaire
 * continue et non lisse (composante clonique / tremulante classique du PLMS). L'angle oscille autour
 * de `theta_max` en `holdCycles` creux successifs, chaque demi-creux etant un profil a jerk minimal
 * comme la flexion elle-meme. Deux consequences voulues :
 *
 *  - **Aucune constante nouvelle n'est ajustee sur le test.** La periode d'un creux est calee sur
 *    `2 x tRise`, l'echelle balistique propre du mouvement : le pic spectral d'un demi-creux vaut
 *    `0,8 / tRise`, exactement la bande 1,6-5,3 Hz de §5.1, et la frequence de repetition
 *    `1/(2.tRise)` tombe dans 1,0-3,3 Hz, la bande clonique publiee.
 *  - **La profondeur relative vient de Sforza aussi** : `holdDepthRad = 0,50 x theta_max` donne un
 *    pic d'acceleration de maintien egal a **0,50 x** le pic balistique, c'est-a-dire le rapport
 *    seuil de decroissance / seuil d'entree du PAM-RL (100 mg / 200 mg). C'est le minimum que doit
 *    soutenir un evenement pour que ce dispositif l'ait compte comme un seul kick de 4,2 s.
 *
 * Les raccords sont **C2** : `s'(0) = s'(1) = 0` et `s''(0) = s''(1) = 0`, donc ni saut de vitesse ni
 * saut d'acceleration entre flexion, creux successifs et retour. Le tableau de calibration de §5.1
 * (30 / 184 / 985 mg) ne depend que de `theta_max`, `tRise` et `r` : il est inchange.
 *
 * `holdCycles = 0` conserve le plateau immobile d'origine ; c'est le defaut, garde pour les
 * distracteurs (mouvements corporels grossiers, rituel de calibration) dont la phase de maintien est
 * bien un maintien passif.
 */
internal class MovementKinematics(
    val thetaMaxRad: Double,
    val tRiseSec: Double,
    val tHoldSec: Double,
    val tFallSec: Double,
    val holdCycles: Int = 0,
    val holdDepthRad: Double = 0.0,
) {
    val totalSec: Double get() = tRiseSec + tHoldSec + tFallSec

    /** Duree d'un creux de maintien, en secondes. `0` quand le maintien est un plateau immobile. */
    private val holdCycleSec: Double =
        if (holdCycles > 0 && tHoldSec > 0.0) tHoldSec / holdCycles else 0.0

    /**
     * Parametre `v` du demi-creux courant, a l'instant `tIn` compte depuis le debut du maintien.
     * `v` parcourt [0,1] a l'aller comme au retour ; `dv/dt` vaut `+2/tc` puis `-2/tc`. C'est cette
     * symetrie qui rend les raccords exacts : `theta''` s'ecrit `-profondeur . s''(v) . (dv/dt)^2`
     * dans les deux demi-creux, et s'annule aux deux bouts puisque `s''(0) = s''(1) = 0`.
     */
    private fun holdV(tIn: Double): Double {
        val u = (tIn % holdCycleSec) / holdCycleSec
        return if (u < 0.5) 2.0 * u else 2.0 - 2.0 * u
    }

    /** Angle, en radians, a l'instant `t` compte depuis l'onset. */
    fun theta(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.s(t / tRiseSec)
        t < tRiseSec + tHoldSec ->
            if (holdCycleSec <= 0.0) thetaMaxRad
            else thetaMaxRad - holdDepthRad * MinJerk.s(holdV(t - tRiseSec))
        t < totalSec -> thetaMaxRad * (1.0 - MinJerk.s((t - tRiseSec - tHoldSec) / tFallSec))
        else -> 0.0
    }

    /** Vitesse angulaire, en rad/s. */
    fun thetaDot(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.sDot(t / tRiseSec) / tRiseSec
        t < tRiseSec + tHoldSec -> {
            if (holdCycleSec <= 0.0) {
                0.0
            } else {
                val tIn = t - tRiseSec
                val sign = if ((tIn % holdCycleSec) / holdCycleSec < 0.5) 1.0 else -1.0
                -holdDepthRad * MinJerk.sDot(holdV(tIn)) * sign * 2.0 / holdCycleSec
            }
        }
        t < totalSec -> -thetaMaxRad * MinJerk.sDot((t - tRiseSec - tHoldSec) / tFallSec) / tFallSec
        else -> 0.0
    }

    /** Acceleration angulaire, en rad/s^2. */
    fun thetaDDot(t: Double): Double = when {
        t < 0.0 -> 0.0
        t < tRiseSec -> thetaMaxRad * MinJerk.sDDot(t / tRiseSec) / (tRiseSec * tRiseSec)
        t < tRiseSec + tHoldSec -> {
            if (holdCycleSec <= 0.0) {
                0.0
            } else {
                val a = 2.0 / holdCycleSec
                -holdDepthRad * MinJerk.sDDot(holdV(t - tRiseSec)) * a * a
            }
        }
        t < totalSec -> -thetaMaxRad * MinJerk.sDDot((t - tRiseSec - tHoldSec) / tFallSec) /
            (tFallSec * tFallSec)
        else -> 0.0
    }

    /** Crete theorique du terme tangentiel, en g. C'est la colonne « crete tangentielle » de §5.1. */
    fun peakTangentialG(radiusM: Double): Double =
        MinJerk.PEAK_ACCEL_COEFF * radiusM * thetaMaxRad / (tRiseSec * tRiseSec) / G_MS2
}

/**
 * Signal tri-axial d'un mouvement, echantillonne, **hors gravite statique**.
 *
 * Convention de repere, capteur a la cheville :
 *  - `x` = axe long du tibia, oriente vers le pied ; c'est aussi le bras de levier `r` ;
 *  - `y` = anterieur ; c'est la direction tangentielle, `y = z x x` ;
 *  - `z` = medio-lateral ; c'est l'axe de flexion du genou et de la cheville.
 *
 * Trois contributions, toutes de §5.1 :
 *  1. `a_tang = r.theta''` selon `+y` ;
 *  2. `a_cent = r.theta'^2` selon `-x` (vers le centre de rotation, proximal) ;
 *  3. la **rotation du vecteur gravite** vu dans le repere capteur. Pour les grands angles c'est
 *     elle qui domine : a 20 degres, `sin(20) = 0,342 g` etale sur ~0,25 s. Les deux doivent etre
 *     modelisees — et c'est exactement ce qui rend `tiltExcursionDeg` informatif cote detecteur.
 *
 * Le facteur `coupling` (serrage du bracelet, §3.3) attenue **tout** ce que le membre transmet au
 * boitier : le bras de levier effectif comme l'angle reellement subi par le capteur. C'est
 * physiquement coherent — un bracelet lache laisse le boitier suivre partiellement le membre — et
 * c'est ce qui fait que `gainCal` mesure bien la variable qui detruit la comparabilite inter-nuits.
 */
internal class MovementRender(val n: Int) {
    val dx = DoubleArray(n)
    val dy = DoubleArray(n)
    val dz = DoubleArray(n)

    /** Crete de la norme du signal de mouvement. */
    var peakG: Double = 0.0
        private set

    fun computePeak() {
        var p = 0.0
        for (i in 0 until n) {
            val m = sqrt(dx[i] * dx[i] + dy[i] * dy[i] + dz[i] * dz[i])
            if (m > p) p = m
        }
        peakG = p
    }
}

/**
 * Rend un mouvement sur `n` echantillons a `fs`, en partant de l'orientation `g0` (vecteur unitaire
 * gravite dans le repere capteur a l'onset).
 */
internal fun renderMovement(
    kin: MovementKinematics,
    radiusM: Double,
    coupling: Double,
    g0x: Double,
    g0y: Double,
    g0z: Double,
    fs: Double,
    n: Int,
): MovementRender {
    val out = MovementRender(n)
    val rEff = radiusM * coupling
    for (i in 0 until n) {
        val t = i / fs
        val th = kin.theta(t) * coupling
        val thd = kin.thetaDot(t) * coupling
        val thdd = kin.thetaDDot(t) * coupling

        // 1 + 2 : termes inertiels, dans le plan sagittal.
        val aTan = rEff * thdd / G_MS2
        val aCen = rEff * thd * thd / G_MS2

        // 3 : rotation de g autour de z de -theta (tourner le capteur de +theta fait tourner la
        // gravite apparente de -theta). On soustrait g0 : seul le CHANGEMENT est du mouvement.
        val c = cos(th)
        val s = sin(th)
        val gxr = g0x * c + g0y * s
        val gyr = -g0x * s + g0y * c

        out.dx[i] = -aCen + (gxr - g0x)
        out.dy[i] = aTan + (gyr - g0y)
        out.dz[i] = 0.0
    }
    out.computePeak()
    return out
}

/**
 * Crete de `env_c` — l'enveloppe RMS **centree** de largeur `win` de la norme du rendu, **apres le
 * passe-bande du canal mouvement de l'etape 1**.
 *
 * Le passe-bande n'est pas un raffinement : c'est ce qui rend l'echelle
 * [AmplitudeScale.COARSE_ENVELOPE] conforme a sa definition — « exactement la grandeur que le
 * detecteur compare a `Theta_on` ». Le detecteur ne voit jamais le rendu brut : il voit
 * `RMS_0,5s(||ButterBP(0,5-8 Hz)(a)||)`. Mesurer l'amplitude sur le rendu non filtre surestime les
 * mouvements dont l'energie vit sous 0,5 Hz — au premier rang desquels le terme de **rotation de la
 * gravite**, qui est un palier quasi continu pendant la phase de maintien. Sur le rituel de
 * calibration (25 degres tenus 0,4 s) l'ecart atteint un facteur 3 : `gainCal` etait surestime
 * d'autant, et le troisieme terme du seuil avec lui.
 *
 * L'etape 1 filtre chaque axe separement **puis** prend la norme L2 (§2, etapes 1 et 2) : c'est
 * l'ordre reproduit ici. Le filtrage est lineaire, donc le passage du rendu seul est exact : la
 * contribution du mouvement a `a_lin` est bien `ButterBP(rendu)`, quel que soit le fond sur lequel
 * il est ajoute.
 *
 * Le rendu est prolonge par des zeros a droite sur `tailSec` pour que la queue de la reponse du
 * filtre soit comptee — un mouvement isole commence et finit au repos, mais le filtre, lui, sonne
 * encore. La convention de fenetre est celle de `com.pendulum.algo.dsp.Numeric.movingRms`
 * (`halfLeft = (win-1)/2`), au demi-echantillon pres, sans quoi les fronts seraient decales.
 */
internal fun coarseEnvelopePeak(
    r: MovementRender,
    win: Int,
    fsHz: Double,
    fcHpHz: Double = 0.50,
    fcLpHz: Double = 8.0,
    hpOrder: Int = 2,
    tailSec: Double = 2.0,
): Double {
    val n = r.n
    if (n == 0) return 0.0
    val tail = Math.round(tailSec * fsHz).toInt().coerceAtLeast(0)
    val m = n + tail
    val mag2 = DoubleArray(m)
    val axes = arrayOf(r.dx, r.dy, r.dz)
    for (a in axes) {
        val bp = Filters.butterBandpass(fsHz, fcHpHz, fcLpHz, hpOrder)
        bp.resetToDc(0f)
        for (i in 0 until m) {
            val v = bp.step(if (i < n) a[i].toFloat() else 0f).toDouble()
            mag2[i] += v * v
        }
    }
    val w = win.coerceAtLeast(1)
    val halfLeft = (w - 1) / 2
    // Les echantillons hors du tampon valent 0 (repos avant le mouvement, silence apres la queue),
    // mais le diviseur reste `w` : c'est ce que fait la RMS glissante de l'etape 2 au milieu d'un
    // segment.
    var best = 0.0
    for (c in 0 until m) {
        val from = c - halfLeft
        var acc = 0.0
        for (k in from until from + w) {
            if (k in 0 until m) acc += mag2[k]
        }
        val v = sqrt(acc / w)
        if (v > best) best = v
    }
    return best
}

/**
 * Cale `thetaMax` pour que le rendu atteigne l'amplitude demandee sur l'echelle demandee.
 *
 * Iteration de point fixe multiplicative : le terme tangentiel est exactement lineaire en
 * `thetaMax`, le terme gravitaire l'est au premier ordre et le terme centripete est quadratique ;
 * trois tours suffisent a converger sous le pour-mille dans toute la plage physiologique. On borne
 * ensuite `thetaMax` a `[0,2 ; 60] degres` : au-dela, ce n'est plus une reponse en triple flexion.
 *
 * **Le calage se fait toujours a couplage 1**, et le couplage reel n'est applique qu'au rendu.
 * L'inverse annulerait le serrage du bracelet en amplifiant l'angle pour retrouver l'amplitude
 * demandee — et le test T11 ne mesurerait plus rien du tout. C'est la raison pour laquelle cette
 * fonction ne prend pas de parametre `coupling`.
 *
 * `holdDepthRatio` est la profondeur des creux de maintien **en fraction de `thetaMax`** : elle
 * suit donc l'angle a chaque tour de l'iteration, ce qui laisse le point fixe multiplicatif valide.
 */
internal fun calibrateThetaMax(
    targetG: Double,
    tRiseSec: Double,
    tHoldSec: Double,
    tFallSec: Double,
    radiusM: Double,
    g0x: Double,
    g0y: Double,
    g0z: Double,
    fs: Double,
    n: Int,
    scale: AmplitudeScale,
    envWin: Int,
    holdCycles: Int = 0,
    holdDepthRatio: Double = 0.0,
): Double {
    var theta = 0.10 // rad, point de depart ~5,7 degres
    val minTheta = Math.toRadians(0.2)
    val maxTheta = Math.toRadians(60.0)
    repeat(4) {
        val kin = MovementKinematics(
            theta, tRiseSec, tHoldSec, tFallSec, holdCycles, holdDepthRatio * theta,
        )
        val r = renderMovement(kin, radiusM, 1.0, g0x, g0y, g0z, fs, n)
        val got = when (scale) {
            AmplitudeScale.PEAK -> r.peakG
            AmplitudeScale.COARSE_ENVELOPE -> coarseEnvelopePeak(r, envWin, fs)
        }
        if (got <= 1e-12) return maxTheta
        theta = (theta * targetG / got).coerceIn(minTheta, maxTheta)
    }
    return theta
}
