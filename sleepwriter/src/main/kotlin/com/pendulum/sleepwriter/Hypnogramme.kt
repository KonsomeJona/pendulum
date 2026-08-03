package com.pendulum.sleepwriter

/**
 * Fabrication d'un hypnogramme plausible, sans aleatoire et sans dependance Android.
 *
 * ### Pourquoi ce n'est pas une suite de stades tiree au hasard
 *
 * L'hypnogramme est le denominateur de la mesure, et il ne sert pas qu'a fournir une duree : la
 * chaine de Pendulum le **superpose** au signal accelerometrique, exclut l'eveil, et controle la
 * plausibilite biologique de ce qu'elle voit. Une suite de stades tiree au hasard passerait la
 * conversion de `Hypnogram.toWindows` sans rien exercer de tout cela, et le banc dirait « ca
 * marche » sans avoir teste ce qui compte.
 *
 * Les regles suivies ici sont celles qu'un dormeur respecte :
 *  - **on ne passe jamais de l'eveil au sommeil profond directement.** L'entree dans le profond
 *    se fait par le sommeil leger, toujours ;
 *  - **le paradoxal ne suit pas immediatement le profond** : la remontee passe par le leger ;
 *  - **cycles d'environ 90 minutes**, avec un micro-eveil de fin de cycle, qui est la forme sous
 *    laquelle les eveils apparaissent reellement dans une nuit normale ;
 *  - **profond concentre en premiere moitie de nuit** et decroissant, **paradoxal croissant** en
 *    seconde. C'est la seule asymetrie que l'oeil reconnait immediatement sur un hypnogramme, et
 *    c'est aussi celle qui fait qu'un decalage temporel de la superposition se voit.
 *
 * ### Les stades que Health Connect sait nommer, qui ne sont pas ceux de l'AASM
 *
 * L'AASM distingue N1, N2 et N3. Health Connect ne connait que `LIGHT` et `DEEP` : N1 et N2
 * tombent tous deux dans `LIGHT`, N3 dans `DEEP`. Ce n'est pas une perte pour Pendulum — l'index
 * ne pondere pas par stade, les stades servent au controle de plausibilite (`SleepReader` KDoc) —
 * mais c'est une chose a savoir avant de chercher un N2 qui n'existera jamais dans la donnee
 * relue.
 *
 * De meme, `AWAKE_IN_BED` (7) et `AWAKE` (1) sont deux stades distincts : le premier pour les
 * eveils intra-nuit, le second pour l'eveil final. Les deux comptent comme de l'eveil pour
 * `Hypnogram.stageOf`, mais les ecrire indifferemment produirait un hypnogramme qui n'aurait
 * jamais pu etre ecrit par un vrai appareil.
 *
 * ### Contiguite
 *
 * Les stades produits couvrent `[debutMs, finMs]` **sans trou et sans chevauchement**. C'est
 * deliberement le cas facile : la politique de trous de `Hypnogram.fillHole` change le
 * denominateur, donc l'index, et merite un scenario a elle plutot que d'etre melangee au scenario
 * nominal.
 */
object Hypnogramme {

    // Constantes de `SleepSessionRecord`, recopiees pour que ce fichier reste pur JVM et donc
    // testable sans emulateur — meme choix que `phone/health/Hypnogram.kt`, qui les recopie aussi.
    const val EVEIL = 1
    const val SOMMEIL = 2
    const val LEGER = 4
    const val PROFOND = 5
    const val PARADOXAL = 6
    const val EVEIL_AU_LIT = 7

    /** Un stade, en millisecondes d'horloge murale UTC. */
    data class Stade(val debutMs: Long, val finMs: Long, val type: Int)

    /** Latence d'endormissement : le temps passe au lit, eveille, avant le premier stade. */
    const val LATENCE_MIN = 8L

    /** Eveil final, avant la fin de la session. */
    const val EVEIL_FINAL_MIN = 3L

    /** Micro-eveil de fin de cycle. */
    private const val MICRO_EVEIL_MIN = 2L

    private const val CYCLE_MIN = 90L

    /**
     * Une nuit complete avec stades.
     *
     * Rend une liste vide si la fenetre est trop courte pour porter autre chose que la latence et
     * l'eveil final — ecrire une « nuit » de dix minutes composee de deux eveils serait un
     * hypnogramme faux, et il vaut mieux que l'appelant voie zero stade et le dise.
     */
    fun nuitComplete(debutMs: Long, finMs: Long): List<Stade> {
        val dureeMin = (finMs - debutMs) / 60_000L
        if (dureeMin <= LATENCE_MIN + EVEIL_FINAL_MIN) return emptyList()

        val segments = ArrayList<Pair<Int, Long>>()
        segments += EVEIL_AU_LIT to LATENCE_MIN

        var restant = dureeMin - LATENCE_MIN - EVEIL_FINAL_MIN
        var cycle = 0
        while (restant > 0) {
            for ((type, minutes) in cycle(cycle)) {
                if (restant <= 0) break
                val duree = minOf(minutes, restant)
                segments += type to duree
                restant -= duree
            }
            cycle++
        }
        segments += EVEIL to EVEIL_FINAL_MIN

        // Le dernier segment absorbe l'arrondi a la minute, pour que le dernier stade finisse
        // exactement a `finMs`. Sans cela une session de 8 h 00 min 30 s laisserait trente
        // secondes non couvertes, `verdictOf` verrait un hypnogramme troue, et le banc
        // signalerait un defaut de la chaine la ou il n'y a qu'une division entiere.
        val stades = ArrayList<Stade>(segments.size)
        var curseur = debutMs
        for ((index, segment) in segments.withIndex()) {
            val fin = if (index == segments.lastIndex) finMs else curseur + segment.second * 60_000L
            if (fin > curseur) stades += Stade(curseur, fin, segment.first)
            curseur = fin
        }
        return fusionner(stades)
    }

    /**
     * Un cycle, sous forme de segments consecutifs.
     *
     * Le profond decroit de 30 minutes a zero, le paradoxal croit de 8 a 36 : les deux profils
     * sont ceux d'une nuit reelle, et leur somme laisse au leger la moitie de la nuit, ce qui est
     * la bonne proportion. Au-dela du cinquieme cycle le profil du cinquieme est repete — une
     * nuit de dix heures ne recommence pas a produire du profond.
     */
    private fun cycle(index: Int): List<Pair<Int, Long>> {
        val i = index.coerceAtMost(4)
        val profond = (30L - 9L * i).coerceAtLeast(0L)
        val paradoxal = 8L + 7L * i
        val leger = CYCLE_MIN - profond - paradoxal - MICRO_EVEIL_MIN

        return buildList {
            if (profond > 0) {
                // 60 / 40 : l'endormissement d'un cycle est plus long que la remontee vers le
                // paradoxal. L'ordre leger -> profond -> leger -> paradoxal est le point de ce
                // fichier ; ne pas l'aplatir en une repartition proportionnelle.
                val avant = leger * 6 / 10
                add(LEGER to avant)
                add(PROFOND to profond)
                add(LEGER to (leger - avant))
            } else {
                add(LEGER to leger)
            }
            add(PARADOXAL to paradoxal)
            add(EVEIL_AU_LIT to MICRO_EVEIL_MIN)
        }
    }

    /**
     * Fusionne deux stades identiques consecutifs.
     *
     * Le decoupage en cycles peut produire un `LEGER` de fin de cycle suivi d'un `LEGER` de debut
     * du suivant quand le profond est nul. Deux enregistrements colles de meme type ne sont pas
     * faux, mais aucun appareil reel n'en ecrit, et `distinctStageTypes` comme `stageCoverageMs`
     * de `SleepSourceSelector` se lisent plus facilement sans eux.
     */
    private fun fusionner(stades: List<Stade>): List<Stade> {
        val out = ArrayList<Stade>(stades.size)
        for (s in stades) {
            val dernier = out.lastOrNull()
            if (dernier != null && dernier.type == s.type && dernier.finMs == s.debutMs) {
                out[out.lastIndex] = dernier.copy(finMs = s.finMs)
            } else {
                out += s
            }
        }
        return out
    }
}
