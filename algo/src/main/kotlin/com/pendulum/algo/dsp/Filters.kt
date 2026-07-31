package com.pendulum.algo.dsp

import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.TriAxial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Biquad en **forme directe II transposee**, `Double` en interne, `Float` en interface.
 *
 * Deux points non negociables, tous deux issus du piege n° 3 de la v1 :
 *
 *  - **L'objet est `stateful` et le filtrage est en flux.** Filtrer bloc par bloc en repartant
 *    d'un etat nul a chaque bloc injecte un transitoire d'etablissement toutes les ~10 s (taille
 *    d'un bloc de 512 echantillons a 50 Hz). Ce transitoire est **periodique** : il produit dans
 *    l'enveloppe une modulation reguliere que l'etape 6 lit comme une serie PLM parfaite. C'est
 *    le faux positif le plus insidieux de toute la chaine, parce qu'il ressemble exactement au
 *    signal recherche. Un segment se filtre en **un seul passage**, du premier au dernier
 *    echantillon, quelle que soit la fragmentation des blocs en amont.
 *  - **DFII transposee et non DFI** : elle minimise l'excursion des variables d'etat, ce qui
 *    compte ici parce que le passe-bas gravite (fc/fs = 0,15/50 = 0,003) a ses poles tres proches
 *    du cercle unite.
 *
 * Recurrence : `y = b0*x + s1 ; s1' = b1*x - a1*y + s2 ; s2' = b2*x - a2*y`.
 */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    private var s1 = 0.0
    private var s2 = 0.0

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }

    /**
     * Initialise l'etat au **regime stationnaire d'une entree constante** [dcValue].
     *
     * Sans cela, le passe-bas gravite demarre a 0 alors que son entree vaut ~1 g : il produit un
     * transitoire de la taille de la gravite, soit 1 000 mg, quand un CLM en fait 30 a 200.
     *
     * Regime permanent : `y = x*(b0+b1+b2)/(1+a1+a2)`, d'ou `s1 = (b1+b2)*x - (a1+a2)*y` et
     * `s2 = b2*x - a2*y`.
     */
    fun resetToDc(dcValue: Float) {
        val x = dcValue.toDouble()
        val den = 1.0 + a1 + a2
        val y = if (abs(den) < 1e-12) 0.0 else x * (b0 + b1 + b2) / den
        s1 = (b1 + b2) * x - (a1 + a2) * y
        s2 = b2 * x - a2 * y
    }

    /** Gain a frequence nulle. Vaut 1 pour un passe-bas normalise, 0 pour un passe-haut. */
    fun dcGain(): Double {
        val den = 1.0 + a1 + a2
        return if (abs(den) < 1e-12) 0.0 else (b0 + b1 + b2) / den
    }

    fun step(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + s1
        s1 = b1 * xd - a1 * y + s2
        s2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
        for (i in src.indices) dst[i] = step(src[i])
        return dst
    }

    /** Etat opaque pour le mode incrementiel : `[s1, s2]`. */
    fun snapshot(): DoubleArray = doubleArrayOf(s1, s2)

    fun restore(state: DoubleArray) {
        require(state.size == 2) { "etat biquad de taille ${state.size}" }
        s1 = state[0]
        s2 = state[1]
    }

    /**
     * Module du pole le plus lent. `1 - r` mesure la vitesse d'oubli du filtre ; c'est ce qui
     * fixe le temps d'etablissement, donc la valeur de `settleSec`.
     */
    internal fun poleRadius(): Double {
        val disc = a1 * a1 - 4.0 * a2
        return if (disc >= 0.0) {
            max(abs((-a1 + sqrt(disc)) / 2.0), abs((-a1 - sqrt(disc)) / 2.0))
        } else {
            sqrt(abs(a2)) // poles complexes conjugues : module = sqrt(a2)
        }
    }
}

/** Cascade de sections biquad. Un filtre d'ordre N > 2 n'existe que sous cette forme. */
class BiquadCascade(internal val stages: List<Biquad>, val fsHz: Double) {

    fun reset() = stages.forEach { it.reset() }

    /**
     * Initialise toutes les sections au regime stationnaire de [dcValue]. La valeur DC propagee
     * a la section suivante est celle que la section courante produit en regime permanent : pour
     * une cascade passe-haut, elle tombe a 0 des la premiere section, ce qui est exactement le
     * comportement voulu (le canal mouvement demarre « degravite »).
     */
    fun resetToDc(dcValue: Float) {
        var dc = dcValue
        for (s in stages) {
            s.resetToDc(dc)
            dc = (dc * s.dcGain()).toFloat()
        }
    }

    fun step(x: Float): Float {
        var v = x
        for (s in stages) v = s.step(v)
        return v
    }

    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray {
        for (i in src.indices) dst[i] = step(src[i])
        return dst
    }

    fun snapshot(): Array<DoubleArray> = Array(stages.size) { stages[it].snapshot() }

    fun restore(state: Array<DoubleArray>) {
        require(state.size == stages.size) { "cascade de taille differente" }
        for (i in stages.indices) stages[i].restore(state[i])
    }

    val stageCount: Int get() = stages.size

    /**
     * Temps d'etablissement a 1 % (5 constantes de temps) du pole le plus lent.
     *
     * Sert a **verifier** `settleSec` / `warmupSec`, pas a les remplacer : la specification fige
     * ces deux durees (§6.1) pour que le denominateur du PLMI ne bouge pas quand on ajuste un
     * coude de filtre. Une cascade dont `settlingTimeSec` depasse `warmupSec` est une erreur de
     * reglage, pas une raison d'allonger le warmup silencieusement.
     */
    val settlingTimeSec: Double
        get() {
            var slowest = 0.0
            for (s in stages) {
                val r = s.poleRadius()
                if (r > 0.0 && r < 1.0) slowest = max(slowest, -1.0 / ln(r))
            }
            return 5.0 * slowest / fsHz
        }
}

/**
 * Conception de filtres de Butterworth par transformation bilineaire avec **prewarping** du coude.
 *
 * Les coefficients sont TOUJOURS calcules a partir du `fs` reel passe en argument, jamais du
 * `nominalRateHz` de l'entete (§3.4, premier point). En pratique l'etape 0 ramene le signal sur
 * une grille a 50,000 Hz exactement, donc `fs` reel vaut `targetFsHz` — mais la dependance reste
 * explicite dans la signature : le jour ou l'on choisirait de ne pas reechantillonner, le
 * compilateur obligerait a fournir la bonne valeur.
 */
object Filters {

    /**
     * Facteurs quadratiques normalises du polynome de Butterworth d'ordre [order] :
     * `s^2 + alpha_k*s + 1`, avec `alpha_k = 2*cos((2k+1)*pi/(2N))`.
     * Ordre 2 -> {sqrt(2)} ; ordre 4 -> {1,8478 ; 0,7654}.
     */
    private fun alphas(order: Int): DoubleArray {
        require(order >= 2 && order % 2 == 0) { "ordre pair >= 2 attendu, recu $order" }
        val n = order / 2
        return DoubleArray(n) { k -> 2.0 * cos(PI * (2 * k + 1) / (2.0 * order)) }
    }

    private fun prewarp(fsHz: Double, fcHz: Double): Double {
        require(fsHz > 0.0) { "fsHz doit etre > 0" }
        require(fcHz > 0.0 && fcHz < fsHz / 2.0) { "fc=$fcHz hors bande utile pour fs=$fsHz" }
        return tan(PI * fcHz / fsHz)
    }

    fun butterLowpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade {
        val k = prewarp(fsHz, fcHz)
        val k2 = k * k
        val stages = alphas(order).map { a ->
            val norm = 1.0 / (1.0 + a * k + k2)
            Biquad(
                b0 = k2 * norm, b1 = 2.0 * k2 * norm, b2 = k2 * norm,
                a1 = 2.0 * (k2 - 1.0) * norm, a2 = (1.0 - a * k + k2) * norm,
            )
        }
        return BiquadCascade(stages, fsHz)
    }

    fun butterHighpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade {
        val k = prewarp(fsHz, fcHz)
        val k2 = k * k
        val stages = alphas(order).map { a ->
            val norm = 1.0 / (1.0 + a * k + k2)
            Biquad(
                b0 = norm, b1 = -2.0 * norm, b2 = norm,
                a1 = 2.0 * (k2 - 1.0) * norm, a2 = (1.0 - a * k + k2) * norm,
            )
        }
        return BiquadCascade(stages, fsHz)
    }

    /**
     * Passe-bande = passe-haut d'ordre [order] **suivi** du passe-bas d'ordre [order], et non une
     * transformation passe-bande d'ordre 2N.
     *
     * Ce n'est pas une approximation : la specification decrit le canal mouvement comme
     * « ButterBP(0,5 - 8,0 Hz, ordre 2 **par section**) » (§2, etape 1) et regle les deux coudes
     * independamment (§6.1). Avec un rapport fHigh/fLow = 16 les deux coudes n'interagissent pas ;
     * en revanche la forme cascadee laisse regler l'ordre du passe-haut seul (`hpOrder`, 2 ou 4),
     * qui est exactement le compromis de §1.2 : +19 dB de rejection a 0,25 Hz contre une sonnerie
     * de posture allongee de 2 s a 4 s.
     */
    fun butterBandpass(fsHz: Double, fLowHz: Double, fHighHz: Double, order: Int = 2): BiquadCascade {
        require(fLowHz < fHighHz) { "fLow doit etre < fHigh" }
        val hp = butterHighpass(fsHz, fLowHz, order)
        val lp = butterLowpass(fsHz, fHighHz, order)
        return BiquadCascade(hp.stages + lp.stages, fsHz)
    }

    /**
     * Module de la reponse en frequence de la cascade, en lineaire. Uniquement destine aux tests
     * et au diagnostic : ne pas l'appeler dans la chaine.
     */
    fun magnitudeAt(c: BiquadCascade, fHz: Double): Double {
        val w = 2.0 * PI * fHz / c.fsHz
        var re = 1.0
        var im = 0.0
        for (s in c.stages) {
            // H(e^jw) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
            val c1 = cos(-w); val s1 = kotlin.math.sin(-w)
            val c2 = cos(-2 * w); val s2 = kotlin.math.sin(-2 * w)
            val nr = s.b0 + s.b1 * c1 + s.b2 * c2
            val ni = s.b1 * s1 + s.b2 * s2
            val dr = 1.0 + s.a1 * c1 + s.a2 * c2
            val di = s.a1 * s1 + s.a2 * s2
            val den = dr * dr + di * di
            val hr = (nr * dr + ni * di) / den
            val hi = (ni * dr - nr * di) / den
            val newRe = re * hr - im * hi
            val newIm = re * hi + im * hr
            re = newRe; im = newIm
        }
        return sqrt(re * re + im * im)
    }
}

/** Sortie de l'etape 1 : les deux chemins paralleles. */
data class GravitySplit(val gravity: TriAxial, val linear: TriAxial)

/**
 * Etape 1 — separation gravite / mouvement par **deux chemins paralleles**, jamais par
 * soustraction. `a - LP(a)` serait mathematiquement un passe-haut valide, mais l'interet n'est
 * pas mathematique : `g_chapeau` devient un signal de premiere classe, consomme par le detecteur
 * de posture (§3.1), la caracteristique `tilt` de chaque evenement (etape 5), le masque
 * d'immobilite (§3.6) et l'autocalibration (§3.3).
 */
object Gravity {

    /**
     * @param raw grille uniforme de l'etape 0 (`NaN` dans les trous).
     * @param segments segments continus ; les filtres sont reinitialises a l'etat stationnaire a
     *   chaque frontiere de segment, et **jamais** entre deux blocs a l'interieur d'un segment.
     * @param settleSec duree de la moyenne d'amorcage (§6.1, `settleSec = 2,0 s`).
     *
     * Traitement des `NaN` a l'interieur d'un segment (trous BLIND de 0,10 a 2,0 s) :
     *  - **canal mouvement : on injecte 0**. Un trou n'est pas un mouvement ; injecter la derniere
     *    valeur y creerait un palier que le passe-haut lirait comme un echelon.
     *  - **canal gravite : on maintient la derniere valeur**. La gravite est persistante ;
     *    injecter 0 ferait plonger `g_chapeau` vers l'origine et fabriquerait un faux changement
     *    de posture de 90 degres a chaque micro-trou.
     *
     * La sortie reste definie (non-`NaN`) dans le trou : c'est volontaire. L'exclusion se fait par
     * les **zones aveugles** de la ligne de temps, pas en propageant des `NaN` qui detruiraient
     * l'etat des filtres pour tout le reste du segment.
     */
    fun split(
        raw: TriAxial,
        segments: List<Segment>,
        fcGravityHz: Double = 0.15,
        fcHpHz: Double = 0.50,
        fcLpHz: Double = 8.0,
        hpOrder: Int = 2,
        settleSec: Double = 2.0,
    ): GravitySplit {
        val fs = raw.fsHz
        val n = raw.n
        val gx = FloatArray(n) { Float.NaN }
        val gy = FloatArray(n) { Float.NaN }
        val gz = FloatArray(n) { Float.NaN }
        val lx = FloatArray(n) { Float.NaN }
        val ly = FloatArray(n) { Float.NaN }
        val lz = FloatArray(n) { Float.NaN }

        val axesIn = arrayOf(raw.x, raw.y, raw.z)
        val axesG = arrayOf(gx, gy, gz)
        val axesL = arrayOf(lx, ly, lz)
        val settle = Numeric.samples(settleSec, fs)

        for (seg in segments) {
            if (seg.length <= 0) continue
            for (a in 0..2) {
                val src = axesIn[a]
                // Moyenne des `settleSec` premieres secondes du segment, NaN exclus : c'est la
                // valeur DC dont partent les deux filtres.
                var sum = 0.0
                var cnt = 0
                val settleEnd = minOf(seg.toIdx, seg.fromIdx + settle)
                for (i in seg.fromIdx until settleEnd) {
                    val v = src[i]
                    if (!v.isNaN()) { sum += v; cnt++ }
                }
                val dc = if (cnt > 0) (sum / cnt).toFloat() else 0f

                val lp = Filters.butterLowpass(fs, fcGravityHz, 2)
                lp.resetToDc(dc)
                val bp = Filters.butterBandpass(fs, fcHpHz, fcLpHz, hpOrder)
                bp.resetToDc(dc)

                val gOut = axesG[a]
                val lOut = axesL[a]
                var hold = dc
                for (k in seg.fromIdx until seg.toIdx) {
                    val v = src[k]
                    val vg: Float
                    val vl: Float
                    if (v.isNaN()) {
                        vg = hold
                        vl = 0f
                    } else {
                        hold = v
                        vg = v
                        vl = v
                    }
                    gOut[k] = lp.step(vg)
                    lOut[k] = bp.step(vl)
                }
            }
        }
        return GravitySplit(
            gravity = TriAxial(fs, raw.t0Ns, gx, gy, gz),
            linear = TriAxial(fs, raw.t0Ns, lx, ly, lz),
        )
    }

    /** `g_chapeau / ||g_chapeau||`. `NaN` la ou la norme est nulle ou indefinie. */
    fun unitVectors(gravity: TriAxial): TriAxial {
        val n = gravity.n
        val ux = FloatArray(n); val uy = FloatArray(n); val uz = FloatArray(n)
        for (i in 0 until n) {
            val x = gravity.x[i]; val y = gravity.y[i]; val z = gravity.z[i]
            val norm = sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z))
            if (norm > 1e-9 && !norm.isNaN()) {
                ux[i] = (x / norm).toFloat(); uy[i] = (y / norm).toFloat(); uz[i] = (z / norm).toFloat()
            } else {
                ux[i] = Float.NaN; uy[i] = Float.NaN; uz[i] = Float.NaN
            }
        }
        return TriAxial(gravity.fsHz, gravity.t0Ns, ux, uy, uz)
    }

    /**
     * Angle entre deux vecteurs, en degres.
     *
     * Calcul par `atan2(||a x b||, a.b)` et non par `acos(a.b/(|a||b|))` : pour deux vecteurs
     * presque colineaires — le cas dominant, puisqu'on compare `g_chapeau` a lui-meme decale de
     * 2 s — l'argument de l'`acos` vaut `1 - epsilon` et l'annulation catastrophique fait perdre
     * la moitie des chiffres significatifs. Or `minExcursionDeg` vaut 1,5 degre : c'est
     * exactement dans cette zone que la precision doit tenir.
     */
    fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val axd = ax.toDouble(); val ayd = ay.toDouble(); val azd = az.toDouble()
        val bxd = bx.toDouble(); val byd = by.toDouble(); val bzd = bz.toDouble()
        val cx = ayd * bzd - azd * byd
        val cy = azd * bxd - axd * bzd
        val cz = axd * byd - ayd * bxd
        val cross = sqrt(cx * cx + cy * cy + cz * cz)
        val dot = axd * bxd + ayd * byd + azd * bzd
        if (cross.isNaN() || dot.isNaN()) return Float.NaN
        if (cross == 0.0 && dot == 0.0) return Float.NaN
        return Math.toDegrees(atan2(cross, dot)).toFloat()
    }

    /** Variante de confort : angle entre les echantillons `i` et `j` d'un meme champ de gravite. */
    fun angleDeg(g: TriAxial, i: Int, j: Int): Float =
        angleDeg(g.x[i], g.y[i], g.z[i], g.x[j], g.y[j], g.z[j])
}
