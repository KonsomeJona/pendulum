package com.pendulum.phone.ui.chart

import kotlin.math.max
import kotlin.math.min

/**
 * La pyramide min/max de l'enveloppe RMS.
 *
 * ### Le probleme
 *
 * Huit heures a 50 Hz font 1,44 million d'echantillons pour environ 1 100 colonnes de pixels.
 * Il faut donc reduire d'un facteur ~1 300. La reduction evidente — la moyenne — est **interdite**
 * ici (P5) : un mouvement de 40 ms noye dans une moyenne de 1 300 echantillons disparait purement
 * et simplement de l'ecran. Or c'est exactement ce que l'utilisateur regarde. Une courbe lissee
 * qui a perdu ses pics n'est pas une simplification, c'est une fausse observation.
 *
 * ### La reponse
 *
 * Une pyramide min/max construite **une seule fois**, hors thread principal, au chargement de la
 * nuit. Le niveau `k` agrege les paires du niveau `k-1` par `min` et `max` : un extremum ne peut
 * jamais etre efface, il ne fait que remonter. Au dessin, on choisit le niveau donnant environ
 * une paire par colonne et on trace un segment vertical `min → max` par colonne.
 *
 * `EnvelopePyramidTest` assure la propriete qui compte : un pic isole d'un seul echantillon
 * survit a un facteur de decimation de 4096.
 *
 * ### Cout
 *
 * `2n` operations a la construction, et environ deux fois la base en memoire — ~11 Mo pour une
 * nuit de 8 h en `Float`. C'est accepte tel quel : si le profilage montre une pression memoire,
 * la parade est de quantifier en `ShortArray` (log₂ × 512), pas d'optimiser avant d'avoir mesure.
 */
class EnvelopePyramid(private val base: FloatArray) {

    /**
     * `levels[k]` contient les paires `[min, max]` pour un facteur de decimation `2^(k+1)`.
     * Le niveau `-1` conceptuel est [base] lui-meme.
     */
    val levels: List<FloatArray> = buildList {
        var precedent: FloatArray? = null
        var tailleSource = base.size
        while (tailleSource > 2) {
            val paires = (tailleSource + 1) / 2
            val niveau = FloatArray(paires * 2)
            if (precedent == null) {
                // Premier niveau : agrege les echantillons bruts deux par deux.
                for (i in 0 until paires) {
                    val a = base[i * 2]
                    val b = if (i * 2 + 1 < base.size) base[i * 2 + 1] else a
                    niveau[i * 2] = min(a, b)
                    niveau[i * 2 + 1] = max(a, b)
                }
            } else {
                val src = precedent
                for (i in 0 until paires) {
                    val i0 = i * 2
                    val i1 = if (i * 2 + 1 < tailleSource) i * 2 + 1 else i0
                    niveau[i * 2] = min(src[i0 * 2], src[i1 * 2])
                    niveau[i * 2 + 1] = max(src[i0 * 2 + 1], src[i1 * 2 + 1])
                }
            }
            add(niveau)
            precedent = niveau
            tailleSource = paires
        }
    }

    val taille: Int get() = base.size

    /** Facteur de decimation du niveau `k`. */
    private fun facteur(k: Int): Int = 1 shl (k + 1)

    /**
     * Le niveau dont une paire couvre au plus `echantillonsParColonne` echantillons.
     * Renvoie `-1` pour dessiner directement depuis la base (zoom fort).
     */
    fun niveauPour(echantillonsParColonne: Float): Int {
        if (echantillonsParColonne <= 2f) return -1
        var k = 0
        while (k + 1 < levels.size && facteur(k + 1) <= echantillonsParColonne) k++
        return k
    }

    /**
     * Remplit `sortie` avec `2 × colonnes` valeurs `[min, max]` sur l'intervalle
     * d'echantillons `[de, a[`.
     *
     * `sortie` est **pre-alloue par l'appelant** et reutilise d'une frame a l'autre : cette
     * fonction n'alloue rien. C'est la condition pour qu'un pincement reste fluide — une
     * allocation de 4 × 1 100 flottants par frame declenche des GC visibles a l'oeil.
     */
    fun remplirMinMax(de: Int, a: Int, colonnes: Int, sortie: FloatArray) {
        require(sortie.size >= colonnes * 2) { "sortie trop courte" }
        val debut = de.coerceIn(0, base.size)
        val fin = a.coerceIn(debut + 1, base.size)
        val parColonne = (fin - debut).toFloat() / colonnes
        val k = niveauPour(parColonne)

        if (k < 0) {
            for (c in 0 until colonnes) {
                val i0 = debut + (c * parColonne).toInt()
                val i1 = (debut + ((c + 1) * parColonne).toInt()).coerceAtMost(fin)
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                var i = i0
                while (i < i1.coerceAtLeast(i0 + 1) && i < base.size) {
                    val v = base[i]
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                    i++
                }
                sortie[c * 2] = mn
                sortie[c * 2 + 1] = mx
            }
            return
        }

        val niveau = levels[k]
        val f = facteur(k)
        val paires = niveau.size / 2
        for (c in 0 until colonnes) {
            val s0 = debut + (c * parColonne).toInt()
            val s1 = (debut + ((c + 1) * parColonne).toInt()).coerceAtMost(fin)
            val p0 = (s0 / f).coerceIn(0, paires - 1)
            val p1 = (s1 / f).coerceIn(p0, paires - 1)
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (p in p0..p1) {
                val vmin = niveau[p * 2]
                val vmax = niveau[p * 2 + 1]
                if (vmin < mn) mn = vmin
                if (vmax > mx) mx = vmax
            }
            sortie[c * 2] = mn
            sortie[c * 2 + 1] = mx
        }
    }
}
