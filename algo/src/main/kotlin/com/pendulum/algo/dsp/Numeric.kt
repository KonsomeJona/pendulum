package com.pendulum.algo.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Primitives numeriques partagees du module `algo`.
 *
 * Trois regles valables pour TOUT ce fichier, et sur lesquelles le reste de la chaine s'appuie :
 *
 *  1. **Politique NaN — un `NaN` est un echantillon ABSENT, jamais une valeur.** Tous les
 *     reducteurs (mediane, percentile, MAD, moyennes/RMS glissants) l'ignorent et normalisent
 *     par le nombre d'echantillons reellement valides. Un reducteur qui ne voit aucune valeur
 *     valide renvoie `NaN` — l'appelant doit tester, jamais propager en aveugle. C'est ce qui
 *     permet a l'etape 0 de marquer les trous par `NaN` sans avoir a fabriquer des zeros
 *     (un zero serait une valeur, et un zero dans une enveloppe tirerait le plancher vers le bas).
 *
 *  2. **Aucune allocation dans les boucles chaudes.** Les fonctions qui tournent sur 1,44 x 10^6
 *     echantillons prennent un `dst` et un `scratch` fournis par l'appelant. Les surcharges
 *     confortables qui allouent existent, mais ne doivent pas etre appelees dans une boucle.
 *
 *  3. **Determinisme strict.** Aucun pivot aleatoire dans le quickselect (mediane de trois),
 *     aucun parcours dependant de l'ordre d'une table de hachage, aucune horloge. Meme entree
 *     -> meme sortie au bit pres, sur toute JVM (les accumulations se font en `Double`, dont
 *     l'arithmetique IEEE-754 est reproductible).
 */
object Numeric {

    // ------------------------------------------------------------------
    // Selection / percentiles
    // ------------------------------------------------------------------

    /**
     * Copie dans [dst] les valeurs non-`NaN` de `src[from until to]`. Renvoie le nombre copie.
     * C'est l'etape prealable a tout percentile : on ne trie jamais le signal en place, et on
     * ne veut pas payer un test `isNaN` a chaque comparaison du quickselect.
     */
    fun compactValid(src: FloatArray, from: Int, to: Int, dst: FloatArray): Int {
        var m = 0
        for (i in from until to) {
            val v = src[i]
            if (!v.isNaN()) dst[m++] = v
        }
        return m
    }

    /**
     * Quickselect en place : apres l'appel, `a[k]` porte la k-ieme plus petite valeur de
     * `a[0 until size]`, tout ce qui est a gauche lui est <= et tout ce qui est a droite >=.
     *
     * Pivot par mediane de trois (premier, milieu, dernier) : deterministe, et suffisant contre
     * le cas pathologique qui nous menace reellement ici — une fenetre de plancher deja triee
     * ou quasi constante (nuit tres calme), ou un pivot naif degenererait en O(n^2).
     */
    fun selectInPlace(a: FloatArray, size: Int, k: Int): Float {
        require(size > 0 && k in 0 until size) { "k=$k hors de [0,$size)" }
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            // Mediane de trois, triee sur place : a[lo] <= a[mid] <= a[hi].
            if (a[mid] < a[lo]) swap(a, mid, lo)
            if (a[hi] < a[lo]) swap(a, hi, lo)
            if (a[hi] < a[mid]) swap(a, hi, mid)
            val pivot = a[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    swap(a, i, j)
                    i++
                    j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[lo]
    }

    private fun swap(a: FloatArray, i: Int, j: Int) {
        val t = a[i]; a[i] = a[j]; a[j] = t
    }

    /**
     * Percentile d'un tampon **deja compacte** (aucun `NaN`), avec interpolation lineaire entre
     * les deux statistiques d'ordre encadrantes (convention « linear » de numpy / R type 7).
     *
     * L'interpolation n'est pas cosmetique : le plancher de bruit est un p25 puis une mediane sur
     * une fenetre de 6 000 echantillons, et un percentile a rang entier ferait sauter la sortie
     * par paliers a chaque entree/sortie d'echantillon dans la fenetre glissante. Le seuil, qui
     * en derive par multiplication par 8, heriterait de ces sauts.
     *
     * @param p percentile dans [0, 100].
     */
    fun percentileOfCompact(buf: FloatArray, size: Int, p: Double): Float {
        if (size <= 0) return Float.NaN
        if (size == 1) return buf[0]
        val h = (size - 1) * (p.coerceIn(0.0, 100.0) / 100.0)
        val lo = h.toInt()
        val frac = h - lo
        val vLo = selectInPlace(buf, size, lo)
        if (frac == 0.0 || lo + 1 >= size) return vLo
        // Apres selectInPlace(lo), tout ce qui est a droite de `lo` lui est >= : la statistique
        // d'ordre lo+1 est donc simplement le minimum de la partie droite. Pas de second tri.
        var vHi = buf[lo + 1]
        for (i in lo + 2 until size) if (buf[i] < vHi) vHi = buf[i]
        return (vLo + frac * (vHi - vLo)).toFloat()
    }

    /** Percentile de `v[from until to]`, `NaN` ignores. [scratch] doit contenir `to - from` cases. */
    fun percentile(v: FloatArray, from: Int, to: Int, p: Double, scratch: FloatArray): Float {
        val m = compactValid(v, from, to, scratch)
        return percentileOfCompact(scratch, m, p)
    }

    /** Surcharge confortable — **alloue**, ne pas utiliser dans une boucle chaude. */
    fun percentile(v: FloatArray, p: Double): Float =
        percentile(v, 0, v.size, p, FloatArray(v.size))

    /** Mediane de `v[from until to]`, `NaN` ignores. */
    fun median(v: FloatArray, from: Int, to: Int, scratch: FloatArray): Float =
        percentile(v, from, to, 50.0, scratch)

    /** Surcharge confortable — **alloue**. */
    fun median(v: FloatArray): Float = percentile(v, 50.0)

    /** Mediane d'un `DoubleArray` (utilisee hors boucle chaude : estimation de `fs`, calibration). */
    fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val f = FloatArray(v.size)
        // On passe par des Float : toutes les grandeurs concernees (fs, gains) tiennent
        // largement dans 24 bits de mantisse, et cela evite de dupliquer le quickselect.
        var m = 0
        for (d in v) if (!d.isNaN()) f[m++] = d.toFloat()
        return percentileOfCompact(f, m, 50.0).toDouble()
    }

    /**
     * Mediane ponderee : plus petite valeur dont le cumul des poids atteint la moitie du total.
     * Sert a `fs_session` (etape 0), ou chaque bloc pese son nombre d'echantillons — un bloc de
     * 512 echantillons contraint `fs` bien mieux qu'un bloc de 30.
     *
     * Convention : borne inferieure (« lower weighted median »), pas d'interpolation. Un `fs`
     * interpole entre deux blocs n'aurait aucun sens physique.
     */
    fun weightedMedian(values: DoubleArray, weights: DoubleArray): Double {
        require(values.size == weights.size) { "tailles differentes" }
        if (values.isEmpty()) return Double.NaN
        val idx = values.indices.sortedBy { values[it] }
        var total = 0.0
        for (w in weights) total += w
        if (total <= 0.0) return Double.NaN
        var cum = 0.0
        for (i in idx) {
            cum += weights[i]
            if (cum >= total / 2.0) return values[i]
        }
        return values[idx.last()]
    }

    /**
     * MAD normalisee : `1,4826 x mediane(|v - mediane(v)|)`. Le facteur ramene la MAD sur
     * l'ecart-type d'une gaussienne, ce qui la rend comparable a un sigma sans heriter de sa
     * sensibilite aux valeurs extremes.
     *
     * @param scratch au moins `to - from` cases ; reutilise pour les deux passes.
     */
    fun mad(v: FloatArray, from: Int, to: Int, scratch: FloatArray): Float {
        val m = compactValid(v, from, to, scratch)
        if (m == 0) return Float.NaN
        val med = percentileOfCompact(scratch, m, 50.0)
        // percentileOfCompact a permute `scratch`, mais on n'a besoin que des valeurs, pas de
        // l'ordre : on ecrase chaque case par son ecart absolu a la mediane.
        for (i in 0 until m) scratch[i] = abs(scratch[i] - med)
        return 1.4826f * percentileOfCompact(scratch, m, 50.0)
    }

    /** Surcharge confortable — **alloue**. */
    fun mad(v: FloatArray): Float = mad(v, 0, v.size, FloatArray(v.size))

    // ------------------------------------------------------------------
    // Fenetres glissantes
    // ------------------------------------------------------------------

    /**
     * Decoupe d'une fenetre centree de [win] echantillons autour de `i`.
     * `win` pair : la case supplementaire va a droite. Fixe une fois pour toutes ici pour que
     * tous les modules (enveloppe, plancher, masque) partagent exactement la meme convention —
     * un decalage d'un demi-echantillon entre l'enveloppe et le plancher deplacerait les fronts.
     */
    fun halfLeft(win: Int): Int = (win - 1) / 2

    fun halfRight(win: Int): Int = win - 1 - halfLeft(win)

    /**
     * Moyenne glissante centree sur `[from, to)`, fenetre de [win] echantillons **tronquee aux
     * bornes** et normalisee par le nombre d'echantillons valides (pas par [win]).
     *
     * Somme courante en `Double` : O(n) quel que soit [win]. La derive d'arrondi d'une somme
     * courante sur 1,44 x 10^6 additions reste ~1e-10 en relatif sur des valeurs de l'ordre du g,
     * trois ordres de grandeur sous la resolution du capteur (0,49 mg) — et elle est
     * **deterministe**, ce qui est la seule propriete qui compte pour la non-regression.
     */
    fun movingMean(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray) {
        require(win >= 1) { "win doit etre >= 1" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        var sum = 0.0
        var count = 0
        var lo = from
        var hi = from - 1 // dernier index inclus deja ajoute
        for (i in from until to) {
            val wantLo = max(from, i - hl)
            val wantHi = min(to - 1, i + hr)
            while (hi < wantHi) {
                hi++
                val v = src[hi]
                if (!v.isNaN()) { sum += v; count++ }
            }
            while (lo < wantLo) {
                val v = src[lo]
                if (!v.isNaN()) { sum -= v; count-- }
                lo++
            }
            dst[i] = if (count > 0) (sum / count).toFloat() else Float.NaN
        }
    }

    /**
     * RMS glissant centre : `sqrt(moyenne_glissante(src^2))`. C'est l'enveloppe de l'etape 2.
     *
     * On somme les carres en `Double` et non les valeurs : sur une magnitude L2 deja positive,
     * le RMS et la moyenne different, et c'est bien le RMS que la specification demande
     * (energie, pas amplitude moyenne).
     */
    fun movingRms(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray) {
        require(win >= 1) { "win doit etre >= 1" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        var sum = 0.0
        var count = 0
        var lo = from
        var hi = from - 1
        for (i in from until to) {
            val wantLo = max(from, i - hl)
            val wantHi = min(to - 1, i + hr)
            while (hi < wantHi) {
                hi++
                val v = src[hi]
                if (!v.isNaN()) { sum += v.toDouble() * v.toDouble(); count++ }
            }
            while (lo < wantLo) {
                val v = src[lo]
                if (!v.isNaN()) { sum -= v.toDouble() * v.toDouble(); count-- }
                lo++
            }
            // La somme courante peut devenir tres legerement negative par annulation
            // catastrophique quand tous les carres retires valent leur propre somme.
            dst[i] = if (count > 0) sqrt(max(0.0, sum / count)).toFloat() else Float.NaN
        }
    }

    /**
     * Mediane glissante centree. **O(n x win)** : reservee aux petites fenetres (typiquement le
     * critere de morphologie WASM 3.2.1-d, `win = 25` a 50 Hz). Ne pas l'utiliser pour le plancher
     * de bruit, dont la fenetre fait 6 000 echantillons — c'est exactement pour cela que
     * [NoiseFloor] evalue sur une grille a pas, et non a chaque echantillon.
     *
     * @param scratch au moins [win] cases.
     */
    fun movingMedian(src: FloatArray, from: Int, to: Int, win: Int, dst: FloatArray, scratch: FloatArray) {
        require(win >= 1 && scratch.size >= win) { "scratch trop petit" }
        val hl = halfLeft(win)
        val hr = halfRight(win)
        for (i in from until to) {
            val lo = max(from, i - hl)
            val hi = min(to - 1, i + hr)
            val m = compactValid(src, lo, hi + 1, scratch)
            dst[i] = percentileOfCompact(scratch, m, 50.0)
        }
    }

    /**
     * Percentile par fenetre **non chevauchante** de [winSamples] echantillons.
     * Une valeur par fenetre, la derniere pouvant etre tronquee. `p = 95` par defaut : c'est la
     * forme dont le masque d'immobilite et le rapport de qualite ont besoin (« quel est le niveau
     * haut de cette epoque ? »), a distinguer de la crete, trop sensible a un unique artefact.
     */
    fun percentileByWindow(
        src: FloatArray,
        from: Int,
        to: Int,
        winSamples: Int,
        p: Double = 95.0,
        scratch: FloatArray = FloatArray(winSamples),
    ): FloatArray {
        require(winSamples >= 1) { "winSamples doit etre >= 1" }
        val n = max(0, to - from)
        val nWin = ceil(n.toDouble() / winSamples).toInt()
        val out = FloatArray(nWin)
        for (w in 0 until nWin) {
            val lo = from + w * winSamples
            val hi = min(to, lo + winSamples)
            val m = compactValid(src, lo, hi, scratch)
            out[w] = percentileOfCompact(scratch, m, p)
        }
        return out
    }

    /** Raccourci lisible du cas dominant. */
    fun p95ByWindow(src: FloatArray, winSamples: Int): FloatArray =
        percentileByWindow(src, 0, src.size, winSamples, 95.0)

    // ------------------------------------------------------------------
    // Divers
    // ------------------------------------------------------------------

    /** Nombre d'echantillons correspondant a [sec] a [fsHz], au minimum 1. */
    fun samples(sec: Double, fsHz: Double): Int = max(1, Math.round(sec * fsHz).toInt())

    /**
     * Pente des moindres carres de `y` sur `x`, sans ordonnee a l'origine imposee.
     * Utilisee pour la derive d'horloge (§3.4) et pour l'autocalibration (§3.3).
     * Renvoie `NaN` si la variance de `x` est nulle (points confondus).
     */
    fun slope(x: DoubleArray, y: DoubleArray): Double {
        require(x.size == y.size)
        val n = x.size
        if (n < 2) return Double.NaN
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += x[i]; my += y[i] }
        mx /= n; my /= n
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            sxy += dx * (y[i] - my)
            sxx += dx * dx
        }
        return if (sxx <= 0.0) Double.NaN else sxy / sxx
    }
}
