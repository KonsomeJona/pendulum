package com.pendulum.format.temps

/**
 * La loi d'echelle du temps mural, et rien d'autre.
 *
 * Une nuit dure huit heures ; un test doit durer quelques minutes. Les deux extremites du produit
 * portent des delais reels — rotation de chunk a 5 min, chien de garde a 15 min, echelle de
 * reprise Health Connect jusqu'a T+32 h — qui rendent une nuit complete inobservable sur un banc.
 *
 * ### La fausse bonne idee, ecartee explicitement
 *
 * **Ne pas toucher a l'horloge systeme de l'emulateur.** `adb shell date` fait deriver
 * simultanement la validation des certificats de Play Services, les fenetres de WorkManager et
 * les replis exponentiels du Data Layer. Les symptomes sont des echecs plausibles et sans
 * rapport avec la cause — on y perd des jours. Elle est nommee ici pour que personne ne la
 * retente en croyant l'avoir inventee.
 *
 * ### Un diviseur entier, et pas un facteur flottant
 *
 * `nominal / d` est exact et monotone ; `nominal * f` avec `f = 1.0 / 600` ne l'est pas, et deux
 * durees nominalement ordonnees peuvent ressortir egales apres arrondi. Comme l'ordre de ces
 * durees **est** le comportement teste — la salve part avant l'expiration du chien de garde, le
 * rang T+1 h tombe avant le rang T+2 h — un arrondi qui les egalise ne casse pas une valeur, il
 * casse un scenario, et il le casse par intermittence.
 *
 * `diviseur = 1` est le temps reel. C'est la valeur de la variante release, et la seule qu'elle
 * sache produire : voir les jumeaux `EchelleTemps` de `:wear` et `:phone`.
 *
 * ### Ce qui ne passe jamais par ici
 *
 *  - **Les volumes.** `WireProtocol.CHUNK_ROTATION_BYTES` est le garde-fou dur de la rotation :
 *    c'est lui qui verifie que les tampons memoire ne debordent pas et que la charge utile tient
 *    sous les 100 Ko d'un `DataItem`. Comprimer la duree **sans** comprimer le volume est l'effet
 *    recherche : on veut voir passer les memes octets, plus vite.
 *  - **Le temps capteur.** Les fenetres de `GapMonitor`, l'epoque de 30 s de `WakeDetector` et le
 *    seau d'une seconde de `PreviewEnvelope` sont exprimes en nanosecondes de `SensorEvent`. La
 *    source synthetique du banc conserve la periode nominale en temps capteur tout en defilant
 *    vite en temps mural : comprimer ces fenetres-la ferait mesurer autre chose que ce qu'elles
 *    mesurent. La convention de nommage porte la regle — un `...Ms` est du temps mural et se met
 *    a l'echelle, un `...Ns` ou `...Us` est du temps capteur ou materiel et n'y touche pas.
 *  - **Les delais d'attente d'appels distants.** `Tasks.await(..., 60 s)` attend une couche
 *    reelle dont la latence, elle, ne se comprime pas. Les diviser produirait des expirations
 *    franches sur un banc par ailleurs sain — un banc qui echoue pour la mauvaise raison est
 *    pire qu'un banc absent.
 */
object Temps {

    /** Temps reel. La seule valeur que la variante release sache produire. */
    const val DIVISEUR_REEL = 1L

    /**
     * Met une duree **murale** a l'echelle.
     *
     * Plancher a 1 ms : une duree nominale non nulle ne doit jamais devenir zero. Un delai nul
     * transformerait une condition de rotation « toutes les cinq minutes » en « a chaque bloc »,
     * c'est-a-dire en un tout autre comportement, et le banc mesurerait ce comportement-la en
     * croyant mesurer l'autre.
     *
     * Une duree nominale nulle ou negative traverse inchangee : elle exprime « tout de suite » ou
     * « deja passe », deux notions qui n'ont pas d'echelle.
     */
    fun ms(nominalMs: Long, diviseur: Long): Long {
        require(diviseur >= 1L) { "diviseur de temps invalide : $diviseur" }
        if (nominalMs <= 0L) return nominalMs
        return (nominalMs / diviseur).coerceAtLeast(1L)
    }

    /** [ms] appliquee terme a terme. L'echelle de reprise Health Connect en est un tableau. */
    fun ms(nominauxMs: LongArray, diviseur: Long): LongArray =
        LongArray(nominauxMs.size) { ms(nominauxMs[it], diviseur) }
}
