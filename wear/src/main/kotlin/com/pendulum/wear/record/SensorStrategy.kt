package com.pendulum.wear.record

import com.pendulum.format.ChunkFormat

/**
 * Decision de strategie d'acquisition. **Pur** : aucune dependance Android, donc les quatre
 * branches sont couvertes par des tests JVM.
 *
 * Le contrat HAL est normatif et decide presque tout :
 *  - capteur **non-wake-up** en suspend : les evenements continuent d'entrer dans le FIFO
 *    materiel, mais « if the FIFO is too small to store all events, the older events are lost » ;
 *  - capteur **wake-up** en suspend : le HAL **doit reveiller le SoC** avant de depasser la
 *    latence de report *ou* de remplir le FIFO.
 *
 * Donc : s'il existe une variante wake-up de `TYPE_ACCELEROMETER`, c'est le seul choix
 * defendable, et la question du wake lock ne se pose qu'en son absence.
 *
 * **On budgete sur `fifoReservedEventCount`, jamais sur `fifoMaxEventCount`.** Le second est la
 * capacite totale du capteur, *partagee* entre tous ses clients (Health Services, l'app du
 * constructeur, le systeme) : dimensionner dessus, c'est parier que personne d'autre n'ecoute,
 * et ce pari est perdu sur une Pixel Watch. Le premier est la seule part garantie a cette
 * application — c'est aussi lui qu'on inscrit dans l'entete du chunk.
 */
object SensorStrategy {

    /** ~10 s de tampon garanti a 50 Hz. **Choix d'ingenierie, pas une valeur sourcee.** */
    const val RESERVED_FOR_WAKEUP_BATCH = 500

    /** ~60 s de tampon garanti a 50 Hz : pari acceptable pour un capteur non-wake-up. */
    const val RESERVED_FOR_NONWAKEUP_BATCH = 3000

    private const val MIN_LATENCY_US = 10_000_000
    private const val MAX_LATENCY_US = 60_000_000

    /**
     * @param isWakeUp `Sensor.isWakeUpSensor` du capteur retenu.
     * @param fifoReserved `Sensor.getFifoReservedEventCount()` — la part garantie, la seule
     *   sur laquelle un budget de latence puisse se calculer.
     * @param rateHz cadence demandee, en Hz.
     */
    fun decide(isWakeUp: Boolean, fifoReserved: Int, rateHz: Int): AcquisitionMode {
        require(rateHz > 0) { "rateHz doit etre positif : $rateHz" }
        return when {
            isWakeUp && fifoReserved >= RESERVED_FOR_WAKEUP_BATCH -> {
                // On ne remplit que la moitie de la part garantie : la marge absorbe une
                // derive de fs et un depart de lot en retard sans jamais toucher le plafond.
                val latencyUs = (0.5 * fifoReserved / rateHz * 1_000_000).toInt()
                    .coerceIn(MIN_LATENCY_US, MAX_LATENCY_US)
                AcquisitionMode(AcquisitionKind.BATCHED_WAKEUP, latencyUs, false, rateHz, true)
            }

            isWakeUp ->
                // Wake-up mais FIFO maigre : le HAL reveillera souvent. On garde le batching
                // pour ce qu'il vaut, sans wake lock — le contrat wake-up suffit a garantir
                // qu'aucun evenement n'est perdu.
                AcquisitionMode(AcquisitionKind.BATCHED_WAKEUP, MIN_LATENCY_US, false, rateHz, true)

            fifoReserved >= RESERVED_FOR_NONWAKEUP_BATCH ->
                AcquisitionMode(AcquisitionKind.BATCHED_WAKELOCK, 20_000_000, true, rateHz, false)

            else ->
                // Non-wake-up et FIFO court : la perte en suspend est documentee, pas hypothetique.
                AcquisitionMode(AcquisitionKind.CONTINUOUS_WAKELOCK, 0, true, rateHz, false)
        }
    }
}

enum class AcquisitionKind { BATCHED_WAKEUP, BATCHED_WAKELOCK, CONTINUOUS_WAKELOCK }

/**
 * Mode d'acquisition effectif. [modeFlags] part tel quel dans l'entete du chunk, ou il est fige :
 * le format est append-only, donc tout changement de mode force une rotation de chunk.
 */
data class AcquisitionMode(
    val kind: AcquisitionKind,
    val maxReportLatencyUs: Int,
    val needsWakeLock: Boolean,
    val rateHz: Int,
    /** Le capteur retenu est la variante wake-up. Conserve a travers les degradations : le
     *  drapeau decrit le capteur, pas le mode, et il ne doit pas disparaitre au palier 2. */
    val wakeUpSensor: Boolean,
    val degraded: Boolean = false,
) {
    val samplingPeriodUs: Int get() = 1_000_000 / rateHz

    val modeFlags: Int
        get() = (if (wakeUpSensor) ChunkFormat.MODE_WAKEUP_SENSOR else 0) or
            (if (maxReportLatencyUs > 0) ChunkFormat.MODE_BATCHED else 0) or
            (if (needsWakeLock) ChunkFormat.MODE_WAKE_LOCK else 0) or
            (if (degraded) ChunkFormat.MODE_DEGRADED else 0)

    /** Libelle court affiche sur la montre : `WAKEUP 30 s`, `CONTINUOUS`, `WAKELOCK 20 s`. */
    val label: String
        get() = when (kind) {
            AcquisitionKind.BATCHED_WAKEUP -> "WAKEUP ${maxReportLatencyUs / 1_000_000} s"
            AcquisitionKind.BATCHED_WAKELOCK -> "WAKELOCK ${maxReportLatencyUs / 1_000_000} s"
            AcquisitionKind.CONTINUOUS_WAKELOCK -> "CONTINUOUS"
        } + if (degraded) " (degraded)" else ""

    /**
     * Application d'un palier d'auto-degradation. **Monotone** : un palier ne redescend jamais
     * dans la meme session, sinon le systeme oscille entre deux modes toute la nuit.
     *
     * 1. prendre le wake lock — 2. latence nulle (continu) — 3. re-enregistrer a 25 Hz.
     */
    fun degradedTo(step: Int): AcquisitionMode = when (step) {
        1 -> copy(needsWakeLock = true, degraded = true)
        2 -> copy(
            kind = AcquisitionKind.CONTINUOUS_WAKELOCK,
            maxReportLatencyUs = 0,
            needsWakeLock = true,
            degraded = true,
        )
        // 25 Hz reste ample : les CLM durent 0,5 a 10 s et l'enveloppe RMS est calculee sur
        // 0,15 s. Nyquist n'est pas le facteur limitant, la resolution de l'onset l'est, et
        // 40 ms suffisent.
        else -> copy(
            kind = AcquisitionKind.CONTINUOUS_WAKELOCK,
            maxReportLatencyUs = 0,
            needsWakeLock = true,
            rateHz = 25,
            degraded = true,
        )
    }
}
