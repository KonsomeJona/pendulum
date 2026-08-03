package com.pendulum.wear.record

import com.pendulum.format.wire.StopReason
import com.pendulum.wear.temps.Durees

/**
 * Conditions d'arret automatique. **Pur** : l'etat est passe en entree, rien n'est lu du systeme
 * ici, donc les six branches et leurs anti-rebond sont testables en JVM.
 *
 * La premiere condition qui se presente gagne, et sa `stopReason` part dans le sidecar et dans
 * `/pendulum/session`. Chacune ferme la nuit **proprement** : le fichier courant est clos, la salve
 * finale est urgente. C'est la difference entre « la montre est morte a 3 h » et « la montre a
 * ferme a 3 h et tout envoye ».
 *
 * **L'off-body n'arrete jamais rien** et n'apparait donc pas ici. La detection off-body repose
 * sur le PPG et le capacitif au poignet ; a la cheville son comportement n'est pas documente et
 * lit tres probablement « non porte » en permanence. S'y fier couperait chaque nuit a sa
 * premiere minute. Elle est journalisee (sidecar, `FLAG_OFF_BODY`) et jamais actionnee. Le vrai
 * detecteur de « montre retiree » est [wakeRatio], qui mesure la locomotion — c'est-a-dire
 * l'evenement qui interesse reellement (« la personne s'est levee »), pas un proxy non valide.
 */
class StopConditions(
    private val startWallMs: Long,
    /** Minutes depuis minuit local. Defaut 10:00. */
    private val stopAtLocalMinutes: Int,
    /**
     * Les trois durees, en parametres plutot que lues dans le catalogue au fond de [evaluate].
     *
     * Cette classe est pure et ses tests le sont aussi : ils affirment des bornes a la
     * milliseconde pres (« 59 999 ms de charge continuent, 60 000 arretent »). Les faire dependre
     * du diviseur de la variante compilee ferait echouer un test de logique metier parce qu'un
     * banc a ete configure autrement — c'est-a-dire pour une raison qui n'a rien a voir avec ce
     * qu'il verifie.
     */
    private val antiRebondChargeMs: Long = CHARGING_DEBOUNCE_MS,
    private val dureeMaxMs: Long = MAX_DURATION_MS,
    private val delaiMinAvantHeureButoirMs: Long = DELAI_MIN_AVANT_HEURE_BUTOIR_MS,
) {

    companion object {
        /** Un chargeur magnetique produit de faux contacts brefs : 60 s de charge soutenue. */
        val CHARGING_DEBOUNCE_MS = Durees.ACTIVES.antiRebondChargeMs

        val MAX_DURATION_MS = Durees.ACTIVES.dureeMaxSessionMs

        /** Voir [evaluate] : l'heure butoir ne vaut que passe ce delai depuis le debut. */
        val DELAI_MIN_AVANT_HEURE_BUTOIR_MS = Durees.ACTIVES.delaiMinAvantHeureButoirMs

        /** Fermeture propre *avant* que le systeme ne tue quoi que ce soit. */
        const val LOW_BATTERY_PCT = 5

        const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }

    private var chargingSinceMs = 0L

    /**
     * @param localMinutes minutes depuis minuit, en heure locale.
     * @param wakeRatio fraction des epoques de 30 s au-dessus du seuil de locomotion sur les
     *   dix dernieres minutes, dans `0..1`.
     */
    fun evaluate(
        nowMs: Long,
        isCharging: Boolean,
        batteryPct: Int,
        freeBytes: Long,
        localMinutes: Int,
        wakeRatio: Double,
    ): StopReason? {
        if (isCharging) {
            if (chargingSinceMs == 0L) chargingSinceMs = nowMs
            if (nowMs - chargingSinceMs >= antiRebondChargeMs) return StopReason.CHARGING
        } else {
            chargingSinceMs = 0L
        }

        if (batteryPct in 0..LOW_BATTERY_PCT) return StopReason.LOW_BATTERY
        if (nowMs - startWallMs >= dureeMaxMs) return StopReason.MAX_DURATION
        // L'heure butoir ne vaut que si la nuit a commence avant elle : un enregistrement
        // demarre a 11 h ne doit pas s'arreter a la milliseconde suivante.
        if (localMinutes >= stopAtLocalMinutes && nowMs - startWallMs > delaiMinAvantHeureButoirMs) {
            return StopReason.TIME_LIMIT
        }
        if (wakeRatio > 0.80) return StopReason.WAKE_DETECTED
        if (freeBytes in 0 until MIN_FREE_BYTES) return StopReason.DISK_FULL
        return null
    }
}

/**
 * Detection de reveil : plus de 80 % des epoques de 30 s au-dessus du seuil de locomotion sur
 * dix minutes glissantes. **Pur**, alimente par l'enveloppe RMS deja calculee pour l'apercu —
 * aucun calcul supplementaire dans la boucle capteur.
 */
class WakeDetector {

    /** RMS au-dela duquel une epoque compte comme locomotion, en m/s^2. */
    private val locomotionThreshold = 1.5

    private val epochs = ArrayDeque<Boolean>()
    private var epochStartNs = 0L
    private var above = 0
    private var total = 0

    /** Fraction d'epoques actives sur la fenetre, dans `0..1`. */
    var ratio: Double = 0.0
        private set

    fun onSecond(rms: Double, tsNs: Long) {
        if (epochStartNs == 0L) epochStartNs = tsNs
        total++
        if (rms > locomotionThreshold) above++
        if (tsNs - epochStartNs >= 30_000_000_000L) {
            epochs.addLast(above > total / 2)
            if (epochs.size > 20) epochs.removeFirst() // 20 epoques de 30 s = 10 min
            ratio = if (epochs.isEmpty()) 0.0 else epochs.count { it }.toDouble() / epochs.size
            epochStartNs = tsNs
            above = 0
            total = 0
        }
    }
}
