package com.pendulum.algo.dsp

import com.pendulum.algo.model.DualEnvelope
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.Signal1D
import com.pendulum.algo.model.TriAxial
import kotlin.math.sqrt

/**
 * Etape 2 — magnitude et enveloppe a deux echelles.
 *
 * **Ce que fait la magnitude L2, et ce qu'elle ne fait pas.** `m = sqrt(ax^2 + ay^2 + az^2)` est
 * invariante par **rotation constante** du bracelet : le boitier peut etre tourne d'une nuit a
 * l'autre autour de la cheville sans changer d'un iota l'amplitude mesuree. C'est ce qui rend le
 * detecteur independant de l'orientation de pose, sans aucune calibration d'axe. Elle n'est en
 * revanche invariante ni par changement de **gain** (serrage du bracelet : c'est le volet B de
 * §3.3), ni par rotation *variable* pendant l'evenement.
 *
 * **Point statistique a connaitre et a ne surtout pas « corriger »** (§2, etape 2) : sur du bruit
 * gaussien isotrope, `m` suit une loi de Maxwell de moyenne `1,596 sigma` et de coefficient de
 * variation 42 %. **La magnitude L2 n'a pas une moyenne nulle, elle a un piedestal.** Ce n'est
 * pas un probleme tant que le plancher est estime **sur la meme grandeur** — ce que fait
 * l'etape 3 — car le rapport `k_on` devient alors autocoherent. Il faut simplement savoir que
 * « 8 fois le plancher » signifie 8 fois une moyenne de Maxwell, et non 8 fois un ecart-type par
 * axe (ce qui vaudrait 12,8 sigma).
 */
object Envelope {

    /** `m(t) = ||a_lin(t)||`. `NaN` propage : un trou reste un trou. */
    fun magnitudeL2(t: TriAxial): Signal1D {
        val n = t.n
        val v = FloatArray(n)
        for (i in 0 until n) {
            val x = t.x[i]; val y = t.y[i]; val z = t.z[i]
            v[i] = if (x.isNaN() || y.isNaN() || z.isNaN()) Float.NaN
            else sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)).toFloat()
        }
        return Signal1D(t.fsHz, t.t0Ns, v)
    }

    /**
     * RMS glissant centre, calcule **segment par segment**.
     *
     * Les fenetres sont tronquees sur les `W/2` premieres et dernieres secondes de chaque segment
     * et normalisees par le nombre d'echantillons valides (§2, etape 2, comportement aux bords).
     * Ces zones tombent de toute facon dans le `warmup` deja exclu : la troncature sert a ne pas
     * fabriquer de discontinuite, pas a rendre les bords exploitables.
     *
     * Ne jamais laisser une fenetre franchir une frontiere de segment : de part et d'autre, le
     * couplage mecanique et l'etat des filtres n'ont plus rien de commun.
     */
    fun rms(s: Signal1D, winSec: Double, segments: List<Segment>): Signal1D {
        val out = FloatArray(s.n) { Float.NaN }
        val win = Numeric.samples(winSec, s.fsHz)
        for (seg in segments) {
            if (seg.length <= 0) continue
            Numeric.movingRms(s.v, seg.fromIdx, seg.toIdx, win, out)
        }
        return Signal1D(s.fsHz, s.t0Ns, out)
    }

    /**
     * Enveloppe a deux echelles.
     *
     * **Pourquoi deux, et pourquoi la grossiere porte la decision.** La v1 detectait sur une
     * fenetre de 0,15 s. C'etait un bug, pas un reglage (§0-b) : a 0,15 s la fenetre ne moyenne
     * meme pas une demi-periode du contenu spectral d'un CLM (dont le pic est vers 2 Hz, soit
     * 0,25 s de demi-periode). L'enveloppe garde donc l'ondulation a `2f` du signal redresse, et
     * cette ondulation traverse le seuil plusieurs fois pendant un unique mouvement : **un CLM
     * est fragmente en trois ou quatre evenements courts**, chacun trop bref pour survivre au
     * critere de duree minimale de 0,5 s. On perd le mouvement ET on fabrique du bruit de
     * comptage. A 0,50 s (>= une periode complete a 2 Hz) l'ondulation est annulee.
     *
     * La fine (0,15 s) est conservee **uniquement** pour le recalage des fronts d'un evenement
     * deja detecte (etape 5.3) et pour le critere de morphologie WASM 3.2.1-d : la ou l'on veut
     * de la resolution temporelle et non de la stabilite de decision.
     */
    fun dual(
        m: Signal1D,
        segments: List<Segment>,
        coarseSec: Double = 0.50,
        fineSec: Double = 0.15,
    ): DualEnvelope = DualEnvelope(
        coarse = rms(m, coarseSec, segments),
        fine = rms(m, fineSec, segments),
        coarseWinSec = coarseSec,
        fineWinSec = fineSec,
    )
}
