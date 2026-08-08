package com.pendulum.phone.temps

import com.pendulum.format.temps.Temps
import java.util.concurrent.TimeUnit

/**
 * Toutes les durees **murales** de `:phone`, et le seul endroit ou elles aient le droit de vivre.
 *
 * Jumeau de `com.pendulum.wear.temps.Durees`, meme role et meme contrat : `DureesTest` compare
 * deux catalogues construits a 1 et a 600 par reflexion, `RecensementDureesTest` interdit qu'une
 * duree murale reapparaisse en dur ailleurs dans `src/main/`.
 *
 * ### Ce qui n'est pas ici, et pourquoi
 *
 *  - `MachineReveil.DEBIT_ESTIME_OCTETS_PAR_S` et `TAILLE_CHUNK_ESTIMEE_OCTETS` — l'estimation
 *    d'attente au reveil est un **debit reel** du Data Layer multiplie par un volume reel. Ni
 *    l'un ni l'autre ne se comprime, donc le temps qu'ils annoncent non plus. Le banc verra une
 *    estimation juste devant un transfert veritablement rapide, ce qui est le comportement
 *    correct et non un artefact.
 *  - Les constantes d'unite des graphes (`heureMs` de `DessinNuit`, le jour de `TrendScreen`) —
 *    des facteurs de conversion pour un axe, jamais des delais.
 *  - Les cinq secondes de `SharingStarted.WhileSubscribed` — la duree de survie d'un flux apres
 *    la disparition du dernier abonne. Elle regle un cout de recomposition, pas un comportement
 *    observable ; la comprimer ferait relancer des collectes pendant les rotations d'ecran sans
 *    rien apprendre a personne.
 *
 * @param diviseur 1 en temps reel. Voir les jumeaux `EchelleTemps`.
 */
class Durees(diviseur: Long) {

    /**
     * L'echelle de reprise de la lecture Health Connect, depuis la **fin** de la nuit.
     *
     * T+30 min, 1 h, 2 h, 4 h, 8 h, 16 h, 32 h. Voir `FetchSchedule` pour ce que ces valeurs
     * valent : une estimation a recaler sur la latence reellement observee, pas une mesure.
     */
    val offsetsLectureMs: LongArray = Temps.ms(
        longArrayOf(
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
            TimeUnit.HOURS.toMillis(8),
            TimeUnit.HOURS.toMillis(16),
            TimeUnit.HOURS.toMillis(32),
        ),
        diviseur,
    )

    /** Au-dela, on arrete d'attendre l'hypnogramme et la nuit reste sur son masque accelerometrique. */
    val abandonLectureMs: Long = Temps.ms(TimeUnit.HOURS.toMillis(36), diviseur)

    /** Anti-rafale des lectures opportunistes : un cable qui fait faux contact emet en boucle. */
    val minEntreOpportunistesMs: Long = Temps.ms(TimeUnit.MINUTES.toMillis(10), diviseur)

    /**
     * Periode du chien de garde WorkManager cote telephone.
     *
     * Meme reserve que sur la montre : `PeriodicWorkRequest` ramene toute periode sous quinze
     * minutes a quinze minutes. La valeur comprimee exprime l'intention ; le banc doit declencher
     * ce travail lui-meme s'il veut l'exercer.
     */
    val periodeWatchdogMs: Long = Temps.ms(TimeUnit.MINUTES.toMillis(30), diviseur)

    /**
     * Premier palier du repli exponentiel de la republication du contexte du soir.
     *
     * Meme reserve que sur [periodeWatchdogMs], et pour la meme raison : WorkManager ramene tout
     * repli sous dix secondes a dix secondes, donc a l'echelle 600 cette valeur ne se comprime
     * plus. Elle exprime l'intention — reessayer vite, parce que la panne visee est une
     * indisponibilite passagere des services Google Play et que la montre attend, ce soir.
     *
     * Trente secondes et non cinq minutes : l'utilisateur vient de sceller son contexte et
     * s'appreterait a se coucher. Un premier rejeu a cinq minutes rendrait le rattrapage
     * inutilisable dans le seul cas ou il compte.
     */
    val delaiRepublicationContexteMs: Long = Temps.ms(TimeUnit.SECONDS.toMillis(30), diviseur)

    /** Silence au-dela duquel une session ouverte passe `STALE` : la montre reviendra peut-etre. */
    val silenceAvantStaleMs: Long = Temps.ms(TimeUnit.MINUTES.toMillis(45), diviseur)

    /** Age au-dela duquel une nuit est declaree `TRUNCATED` et analysee telle quelle. */
    val ageMaxNuitMs: Long = Temps.ms(TimeUnit.HOURS.toMillis(14), diviseur)

    /**
     * Combien de temps le compte rendu du bouton « demarrer sur la montre » reste sous le bouton.
     *
     * Six secondes et non deux : la phrase d'echec est longue — elle nomme le geste de secours,
     * appuyer sur START sur la montre — et elle se lit au coucher, lumiere basse, une main. Deux
     * secondes la feraient disparaitre avant la fin de la ligne.
     *
     * Elle est **ici** et non dans l'ecran, contrairement aux cinq secondes de
     * `SharingStarted.WhileSubscribed` : celles-la reglent un cout de recomposition que personne
     * n'observe, celle-ci est une duree d'affichage que le banc doit traverser a sa propre vitesse.
     * Un banc a l'echelle 600 qui attendrait six secondes reelles sous chaque demarrage passerait
     * l'essentiel de sa nuit simulee a regarder une phrase.
     */
    val retourDemarrageMs: Long = Temps.ms(TimeUnit.SECONDS.toMillis(6), diviseur)

    companion object {

        /** Le catalogue de la variante compilee. Un seul point de lecture, un seul diviseur. */
        val ACTIVES = Durees(EchelleTemps.DIVISEUR)
    }
}
