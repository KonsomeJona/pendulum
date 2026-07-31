package com.pendulum.algo.detect

import com.pendulum.algo.dsp.Gravity
import com.pendulum.algo.dsp.Numeric
import com.pendulum.algo.model.PostureChange
import com.pendulum.algo.model.Segment
import com.pendulum.algo.model.TriAxial

/**
 * Parametres du detecteur de posture (`docs/ALGO-v2.md` §6.4). Aucun n'est marque « fixe ».
 *
 * @param tauSec demi-fenetre de comparaison de `g` (plage 1,0-3,0).
 * @param postureDeg seuil de rotation persistante, en degres (plage 12-30).
 * @param stableDeg cone de stabilite post-transition (plage 6-15).
 * @param stableSec duree de maintien dans le cone (plage 5-20).
 * @param guardSec fenetre d'exclusion des CLM autour de la transition (plage 1,5-4,0).
 */
data class PostureConfig(
    val tauSec: Double = 2.0,
    val postureDeg: Double = 20.0,
    val stableDeg: Double = 10.0,
    val stableSec: Double = 10.0,
    val guardSec: Double = 2.5,
)

/**
 * Detection des changements de posture. Transcription de `docs/ALGO-v2.md` §3.1, parametres §6.4.
 *
 * Pourquoi ce detecteur existe : un retournement change la projection de la gravite d'un axe de
 * jusqu'a **1 g** en 0,5-3 s. Passe dans le passe-haut a 0,5 Hz, cet echelon produit un transitoire
 * de constante de temps 0,32 s, sensible sur ~2 s. Un CLM typique fait 30-200 mg : l'artefact de
 * posture est donc **5 a 30 fois plus gros qu'un vrai CLM** et dure exactement la bonne duree pour
 * etre compte. C'est, de loin, la premiere source de faux positifs, et il est indiscernable d'un
 * mouvement de jambe sur le seul canal enveloppe. La seule information qui les separe est portee
 * par la gravite : une posture **reoriente durablement** le segment, un CLM revient a sa position.
 *
 * Le detecteur n'opere donc jamais sur l'enveloppe, uniquement sur `g`.
 */
object PostureDetector {

    /**
     * @param gravity `g` estime (etape 1), sur la grille uniforme. Aucun `NaN` attendu : le canal
     *   gravite maintient la derniere valeur dans les trous (etape 0).
     * @param segments intervalles continus analysables. Une fenetre ne franchit jamais une frontiere.
     * @return un [PostureChange] par transition, dans l'ordre chronologique.
     */
    fun detect(
        gravity: TriAxial,
        segments: List<Segment>,
        cfg: PostureConfig = PostureConfig(),
    ): List<PostureChange> {
        val fs = gravity.fsHz
        require(fs > 0.0) { "fsHz doit etre > 0" }
        val tau = samplesOf(cfg.tauSec, fs).coerceAtLeast(1)
        val stableLen = samplesOf(cfg.stableSec, fs).coerceAtLeast(1)
        val out = ArrayList<PostureChange>()

        for (seg in segments) {
            val first = seg.fromIdx + tau
            val last = seg.toIdx - tau // exclu
            var i = first
            while (i < last) {
                val d = Gravity.angleDeg(gravity, i - tau, i + tau)
                if (d.isNaN() || d <= cfg.postureDeg) {
                    i++
                    continue
                }
                // Plage maximale ou la rotation depasse le seuil : c'est la transition entiere,
                // depuis son amorce jusqu'a son terme. On ne coupe pas au premier index stable,
                // sans quoi l'angle net ne mesurerait que la fin de la rotation.
                var end = i
                var peakIdx = i
                var peakDeg = d
                while (end + 1 < last) {
                    val dn = Gravity.angleDeg(gravity, end + 1 - tau, end + 1 + tau)
                    if (dn.isNaN() || dn <= cfg.postureDeg) break
                    end++
                    if (dn > peakDeg) {
                        peakDeg = dn
                        peakIdx = end
                    }
                }
                // Condition de stabilisation : il faut qu'au moins un instant de la transition relie
                // deux orientations tenues dans un cone de `stableDeg` pendant `stableSec`.
                if (isSettled(gravity, i, end, tau, seg, stableLen, cfg.stableDeg)) {
                    val fromIdx = i - tau
                    val toIdx = (end + tau).coerceAtMost(seg.toIdx - 1)
                    val net = Gravity.angleDeg(gravity, fromIdx, toIdx)
                    out += PostureChange(
                        atIdx = peakIdx,
                        atMsRel = Math.round(peakIdx * 1000.0 / fs),
                        deltaDeg = if (net.isNaN()) peakDeg else net,
                        settleMs = Math.round((toIdx - fromIdx) * 1000.0 / fs).toInt(),
                    )
                }
                i = end + 1
            }
        }
        return out
    }

    /**
     * Vrai si un instant de la transition relie deux orientations **tenues**.
     *
     * La specification §3.1 n'ecrit que la condition d'arrivee (« g_u reste dans un cone de
     * `stableDeg` pendant `stableSec` »). La condition de depart est ajoutee ici, symetrique, et il
     * faut le signaler : sans elle, la regle se declenche sur tout mouvement ample qui **revient** a
     * sa position. Avec `tau = 2 s`, il existe alors toujours un instant `t` ou `g_u(t - tau)` est
     * pris au sommet du mouvement et `g_u(t + tau)` apres son retour — l'angle depasse le seuil et
     * l'arrivee est parfaitement stable puisqu'elle est l'orientation d'origine. Un simple grand
     * mouvement de jambe fabriquerait donc un faux changement de posture, qui exclurait a tort les
     * CLM voisins par la fenetre de garde et couperait les fenetres d'estimation du plancher. Un
     * changement de posture est une transition **entre deux orientations persistantes** ; c'est ce
     * que dit deja le tableau §6.4 de `stableSec` (« distingue un changement durable d'un mouvement »).
     */
    private fun isSettled(
        g: TriAxial,
        from: Int,
        to: Int,
        tau: Int,
        seg: Segment,
        stableLen: Int,
        stableDeg: Double,
    ): Boolean {
        var k = from
        while (k <= to) {
            val after = k + tau
            val before = k - tau
            if (after < seg.toIdx && before >= seg.fromIdx &&
                holdsCone(g, before, (before - stableLen).coerceAtLeast(seg.fromIdx), before, stableDeg) &&
                holdsCone(g, after, after + 1, (after + stableLen).coerceAtMost(seg.toIdx), stableDeg)
            ) {
                return true
            }
            k++
        }
        return false
    }

    /** `g_u` reste-t-il dans le cone `stableDeg` autour de `g_u(ref)` sur `[from, to)` ? */
    private fun holdsCone(g: TriAxial, ref: Int, from: Int, to: Int, stableDeg: Double): Boolean {
        var j = from
        while (j < to) {
            val a = Gravity.angleDeg(g, ref, j)
            if (a.isNaN() || a > stableDeg) return false
            j++
        }
        return true
    }
}

// --- Helpers geometriques partages par le paquet detect ---

/**
 * Nombre d'echantillons couvrant `sec` a `fs`. Delegue a `com.pendulum.algo.dsp.Numeric.samples` pour
 * qu'une duree en secondes donne **le meme** nombre d'echantillons partout dans la chaine : une
 * fenetre de morphologie de 0,50 s et une fenetre d'enveloppe de 0,50 s doivent compter pareil.
 */
internal fun samplesOf(sec: Double, fs: Double): Int = Numeric.samples(sec, fs)

// L'angle entre deux orientations vient de `Gravity.angleDeg` (`dsp`), qui le calcule par
// `atan2(||a x b||, a.b)` et non par `acos`. Ce detail compte ici : on compare `g` a lui-meme
// decale de 2 s, donc deux vecteurs presque colineaires, ou l'`acos` perdrait la moitie des
// chiffres significatifs — juste dans la zone du degre ou vit `minExcursionDeg` (1,5 deg).
