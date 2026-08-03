package com.pendulum.wear.temps

import com.pendulum.format.temps.Temps
import com.pendulum.format.wire.WireProtocol
import java.util.concurrent.TimeUnit

/**
 * Toutes les durees **murales** de `:wear`, et le seul endroit ou elles aient le droit de vivre.
 *
 * Le rassemblement n'est pas cosmetique : il rend l'echelle **verifiable**. `DureesTest` construit
 * deux catalogues, l'un a 1 et l'autre a 600, et compare toutes les proprietes par reflexion. Une
 * duree ajoutee ici sans passer par [Temps.ms] fait tomber ce test ; une duree ecrite en dur dans
 * un fichier de `src/main/` fait tomber le recensement de `RecensementDureesTest`. Les deux
 * ensemble ferment la porte que la seule discipline de revue laisse toujours entrouverte.
 *
 * ### Ce qui n'est pas ici, et pourquoi
 *
 *  - `WireProtocol.CHUNK_ROTATION_BYTES` — un volume. Voir [Temps] : c'est precisement ce qu'on
 *    ne comprime pas.
 *  - Les fenetres de `GapMonitor`, l'epoque de `WakeDetector`, le seau de `PreviewEnvelope`,
 *    `SensorPipeline.FLUSH_GAP_NS` et les latences de `SensorStrategy` — du temps **capteur** ou
 *    materiel, en `Ns`/`Us`. La source synthetique du banc conserve la periode nominale en temps
 *    capteur ; ces fenetres mesurent donc la meme chose qu'en vrai, et les comprimer leur ferait
 *    mesurer autre chose.
 *  - Les delais d'attente de `Tasks.await` — l'attente d'une couche reelle, non comprimable.
 *  - `stopAtLocalMinutes` — une **heure locale**, pas une duree. Dix heures du matin restent dix
 *    heures du matin quelle que soit la vitesse a laquelle on y arrive.
 *
 * @param diviseur 1 en temps reel. Voir les jumeaux `EchelleTemps`.
 */
class Durees(diviseur: Long) {

    /**
     * Fermeture du chunk courant par la duree. L'autre condition de rotation — le plafond
     * d'octets — n'est **pas** mise a l'echelle, et c'est la consequence a garder en tete :
     * comprimee, cette borne-ci ne se declenche presque plus, et c'est le volume qui ferme les
     * chunks. Le banc exerce donc la rotation par octets, pas la rotation par duree. Voir la
     * KDoc de `ChunkStore.writeBlock`.
     */
    val rotationChunkMs: Long = Temps.ms(WireProtocol.CHUNK_ROTATION_MS, diviseur)

    /** Le tick du service : `fsync`, conditions d'arret, publication de l'etat. */
    val tickServiceMs: Long = Temps.ms(TimeUnit.SECONDS.toMillis(10), diviseur)

    /**
     * Au-dela, une session est perimee quoi qu'en dise son marqueur : ni le watchdog, ni
     * `BOOT_COMPLETED`, ni un redemarrage `START_STICKY` ne la reprennent. Quatorze heures
     * bornent le cas du marqueur oublie sur le disque apres un kill sans fermeture.
     */
    val ageMaxSessionMs: Long = Temps.ms(TimeUnit.HOURS.toMillis(14), diviseur)

    /**
     * Periode du chien de garde WorkManager.
     *
     * **Comprimer cette valeur ne comprime pas le comportement.** `PeriodicWorkRequest` impose un
     * plancher de quinze minutes (`MIN_PERIODIC_INTERVAL_MILLIS`) et ramene silencieusement toute
     * demande plus courte a ce plancher. La valeur mise a l'echelle exprime l'intention et vaut
     * pour la lecture ; le banc, lui, ne verra jamais le chien de garde se declencher plus vite,
     * et doit donc le declencher lui-meme. C'est l'une des choses que ce banc ne prouve pas.
     */
    val periodeWatchdogMs: Long = Temps.ms(TimeUnit.MINUTES.toMillis(15), diviseur)

    /**
     * Delai avant de redemander un service de premier plan apres un `onTimeout`. Il laisse au
     * systeme le temps d'achever l'arret ; un travail unique n'a pas de plancher, donc celui-ci
     * se comprime reellement.
     */
    val relanceApresTimeoutMs: Long = Temps.ms(TimeUnit.SECONDS.toMillis(30), diviseur)

    /** Anti-rebond du chargeur : un contact magnetique produit de faux contacts brefs. */
    val antiRebondChargeMs: Long = Temps.ms(TimeUnit.MINUTES.toMillis(1), diviseur)

    /** Duree maximale d'un enregistrement, condition d'arret `MAX_DURATION`. */
    val dureeMaxSessionMs: Long = Temps.ms(TimeUnit.HOURS.toMillis(10), diviseur)

    /**
     * Duree minimale avant que l'heure butoir locale puisse arreter la nuit : un enregistrement
     * demarre a 11 h ne doit pas s'arreter a la milliseconde suivante.
     */
    val delaiMinAvantHeureButoirMs: Long = Temps.ms(TimeUnit.HOURS.toMillis(1), diviseur)

    companion object {

        /** Le catalogue de la variante compilee. Un seul point de lecture, un seul diviseur. */
        val ACTIVES = Durees(EchelleTemps.DIVISEUR)
    }
}
