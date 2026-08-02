package com.pendulum.wear.record

/**
 * Surveillance de l'integrite temporelle de l'acquisition, et decision d'auto-degradation.
 * **Pur** : que des `Long` de nanosecondes en entree, donc entierement testable en JVM.
 *
 * > **Un trou se mesure sur les ecarts de `SensorEvent.timestamp`. Jamais sur l'heure d'arrivee.**
 *
 * C'est la seule regle de ce fichier qui ne se negocie pas. En mode batche les echantillons
 * arrivent par salves : trente secondes de silence puis 1 500 evenements livres d'un coup, ce
 * n'est pas un trou, c'est le fonctionnement nominal. Une regle fondee sur l'heure de livraison
 * — l'`elapsedRealtimeNanos` entre deux `onSensorChanged` — se declenche donc **en permanence**
 * dans le cas nominal. Et comme l'escalade repond aux trous en prenant un `PARTIAL_WAKE_LOCK`,
 * une telle regle ne produit pas seulement un faux positif dans un journal : elle transforme
 * silencieusement chaque nuit en nuit sous wake lock, depense 65 % de la batterie, et invalide
 * la mesure d'autonomie que la premiere phase de test existe precisement pour obtenir. La panne
 * ressemble alors a « le batching ne marche pas sur cet appareil » alors que ce qui ne marche
 * pas, c'est le moniteur.
 *
 * Deux signaux admissibles, tous deux calcules sur les timestamps capteur :
 *  - **intra-lot** : ecart entre echantillons consecutifs superieur a 3 fois la periode nominale ;
 *  - **fenetre** : deficit du nombre d'echantillons sur 60 s de temps capteur glissantes
 *    (`recus < 0,95 x attendus`), ce qui rattrape un trou tombant entre deux lots sans jamais
 *    regarder l'heure a laquelle les lots sont arrives.
 */
class GapMonitor(rateHz: Int) {

    private var periodNs: Long = 1_000_000_000L / rateHz

    /** Un trou compte pour l'escalade au-dela de 3 s : en deca, le signal reste exploitable. */
    private val bigGapNs = 3_000_000_000L

    private val escalationWindowNs = 600_000_000_000L // 10 min de temps capteur
    private val measureWindowNs = 60_000_000_000L

    private var lastTsNs = 0L
    private var windowStartNs = 0L
    private var windowCount = 0

    /**
     * Echantillons manquants **deja imputes** par le signal intra-lot dans la fenetre courante.
     *
     * Sans ce compteur, un meme trou physique etait compte deux fois : une fois a son arrivee par
     * l'intra-lot, une seconde fois a la cloture de la fenetre, parce que les echantillons qu'il a
     * emportes ressortent comme deficit. Consequence mesuree : `gapCount` et `gapTotalMs`
     * doublaient sur ces trous, et surtout **deux** trous physiques dans une meme fenetre
     * suffisaient a faire monter d'un palier la ou la regle en annonce trois.
     *
     * Ce n'etait pas une imprecision cosmetique. Chaque palier prend un `PARTIAL_WAKE_LOCK` :
     * escalader une fois et demie trop vite, c'est passer la nuit sous wake lock, depenser 65 %
     * de batterie, et invalider la mesure d'autonomie que la phase P1 existe pour obtenir — le
     * cout exact que la KDoc de ce fichier dit vouloir eviter.
     *
     * La fenetre reste ce que sa documentation annonce : **un rattrapage de ce que l'intra-lot ne
     * peut pas voir** — un trou tombant entre deux lots — et non un amplificateur de ce qu'il a
     * deja vu.
     */
    private var manquantsDejaComptes = 0
    private val bigGapTimestamps = ArrayDeque<Long>()

    /** Nombre de trous detectes, tous signaux confondus. */
    var gapCount: Int = 0
        private set

    /** Duree cumulee manquante, en millisecondes. */
    var gapTotalMs: Long = 0
        private set

    /** Palier de degradation atteint, de 0 a 3. Ne redescend jamais. */
    var step: Int = 0
        private set

    /** `fs` reellement delivre sur la derniere fenetre de 60 s. 0 tant qu'aucune n'est close. */
    var measuredRateHz: Double = 0.0
        private set

    /**
     * Vrai quand `fs` mesure s'ecarte de plus de 5 % du nominal. La cadence demandee n'est pas
     * la cadence delivree — 50 Hz sort couramment a 50,3 ou 52,6 Hz — et une autre application
     * qui demarre une seance d'exercice en pleine nuit peut la changer. Un `fs` faux decale tous
     * les filtres et toutes les durees de mouvement de la chaine d'analyse.
     */
    var rateDeviates: Boolean = false
        private set

    /** Palier a appliquer, consomme par le service. `null` tant qu'il n'y a rien de nouveau. */
    var pendingStep: Int? = null
        private set

    /**
     * @return vrai si un trou precede cet echantillon, auquel cas le bloc qui commence ici
     *   doit porter `FLAG_GAP_BEFORE`.
     */
    fun onSample(tsNs: Long): Boolean {
        if (lastTsNs == 0L) {
            lastTsNs = tsNs
            windowStartNs = tsNs
            windowCount = 1
            return false
        }

        // Horodatage qui recule. Les couches capteur d'Android le font parfois en mode batche, et
        // le laisser passer corrompt tout ce qui suit : `spanNs` devient negatif a la cloture,
        // donc `expected` aussi, donc la comparaison de deficit ne se declenche plus jamais — et
        // `windowStartNs` repart dans le passe, ce dont la fenetre suivante ne se remet pas.
        // L'echantillon est ignore plutot que corrige : on ne sait pas ou il devrait aller.
        if (tsNs <= lastTsNs) return false

        val dt = tsNs - lastTsNs
        lastTsNs = tsNs
        windowCount++

        var gapHere = false
        if (dt > 3 * periodNs) {
            gapHere = true
            gapCount++
            gapTotalMs += (dt - periodNs) / 1_000_000
            manquantsDejaComptes += ((dt - periodNs) / periodNs).toInt()
            if (dt >= bigGapNs) recordBigGap(tsNs)
        }

        if (tsNs - windowStartNs >= measureWindowNs) {
            closeWindow(tsNs)
        }
        return gapHere
    }

    /** Apres une re-inscription du capteur a une autre cadence. */
    fun onRateChanged(rateHz: Int) {
        periodNs = 1_000_000_000L / rateHz
        // La continuite temporelle est rompue par la re-inscription : on repart proprement
        // plutot que de compter un trou qu'on a provoque nous-memes.
        lastTsNs = 0L
        windowStartNs = 0L
        windowCount = 0
        manquantsDejaComptes = 0
    }

    fun consumePendingStep(): Int? = pendingStep.also { pendingStep = null }

    private fun closeWindow(tsNs: Long) {
        val spanNs = tsNs - windowStartNs
        measuredRateHz = windowCount * 1e9 / spanNs
        val nominal = 1e9 / periodNs
        rateDeviates = Math.abs(measuredRateHz - nominal) / nominal > 0.05

        val expected = (spanNs / periodNs).toInt()

        // Le deficit **inexplique** : ce que la fenetre constate, moins ce que l'intra-lot a deja
        // impute. `coerceAtLeast(0)` n'est pas une precaution de style — sans lui, un capteur qui
        // delivre un peu plus vite que sa cadence nominale (51 Hz demandes a 50) rend
        // `windowCount > expected`, donc un `missing` negatif, donc un `gapTotalMs` qui
        // **diminue**. Le compteur de temps perdu se mettait a en regagner.
        val manquantsNonVus = (expected - windowCount - manquantsDejaComptes).coerceAtLeast(0)
        if (manquantsNonVus > 0.05 * expected) {
            val missingNs = manquantsNonVus * periodNs
            gapCount++
            gapTotalMs += missingNs / 1_000_000
            // Un deficit de fenetre superieur a 3 s vaut un gros trou : il est simplement
            // reparti autrement dans le temps, pas moins reel.
            if (missingNs >= bigGapNs) recordBigGap(tsNs)
        }

        windowStartNs = tsNs
        windowCount = 0
        manquantsDejaComptes = 0
    }

    /** Trois gros trous dans une fenetre glissante de 10 min font monter d'un palier. */
    private fun recordBigGap(tsNs: Long) {
        bigGapTimestamps.addLast(tsNs)
        while (bigGapTimestamps.isNotEmpty() && tsNs - bigGapTimestamps.first() > escalationWindowNs) {
            bigGapTimestamps.removeFirst()
        }
        if (bigGapTimestamps.size >= 3 && step < 3) {
            step++
            pendingStep = step
            // Le compteur repart a zero : le palier suivant se merite sur les dix minutes qui
            // suivent, sinon les memes trous declencheraient les trois paliers d'affilee.
            //
            // Objection connue et ecartee : apres l'escalade il faut trois trous **nouveaux**, ce
            // qui freine la montee quand le materiel lache franchement — quatre gros trous en
            // cinq minutes ne produisent qu'un palier. C'est assume. Chaque palier prend un wake
            // lock supplementaire, et le cout d'escalader trop vite est une nuit entiere de
            // batterie plus une mesure d'autonomie invalidee ; le cout d'escalader trop lentement
            // est quelques trous de plus dans un signal que l'analyse sait deja marquer. Les deux
            // ne se valent pas. Si une campagne reelle montre l'inverse, c'est ici qu'il faudra
            // revenir — avec la mesure, pas avec l'intuition.
            bigGapTimestamps.clear()
        }
    }
}
