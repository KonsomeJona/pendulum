package com.pendulum.phone.banc

import com.pendulum.algo.model.Stage
import com.pendulum.algo.synth.GroundTruth
import com.pendulum.algo.synth.NightSpec
import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * La telemetrie d'une nuit ensemencee : un point par minute, comme en produit la montre.
 *
 * ### Pourquoi elle est fabriquee alors que les chunks ne le sont pas
 *
 * Elle ne se deduit d'aucune autre table. Sans elle, le detail d'une nuit n'a **rien** a mettre
 * dans sa bande d'etat de l'appareil — ni jauge de batterie, ni etat du port — et l'ecran se juge
 * alors sur une moitie de lui-meme. Les chunks, eux, sont deliberement absents : voir la KDoc de
 * [Ensemencement], la raison n'est pas la meme.
 *
 * Consequence a connaitre : les bandes temporelles (hors poignet, charge, gel d'ecriture) se
 * placent sur la base de temps **capteur**, dont l'origine est le `tFirstNs` du premier chunk.
 * Aucune nuit ensemencee n'ayant de chunk, ces bandes restent vides — la jauge et l'etat du port,
 * qui ne dependent que des points, s'affichent normalement.
 *
 * ### Les valeurs
 *
 * Elles ne sont ni rondes ni constantes, pour la meme raison que les nuits sont synthetisees
 * plutot qu'ecrites a la main : une batterie qui descend d'un pas exact et une gigue toujours
 * egale ne font apparaitre aucun defaut d'echelle. La pente de batterie suit la duree reelle de
 * la nuit, la periode hors poignet est celle que le generateur a effectivement injectee.
 */
internal object Telemetrie {

    /** Un point par minute : c'est la cadence de la montre (cinq points par chunk complet). */
    private const val PERIODE_MS = 60_000L

    /** Capacite nominale d'une batterie de montre, en uAh. Sert a rendre `batteryChargeUah`. */
    private const val CAPACITE_UAH = 300_000

    fun points(
        sessionHex: String,
        spec: NightSpec,
        truth: GroundTruth,
        dureeEnregistreeMin: Double,
    ): List<TelemetryPointEntity> {
        val combien = dureeEnregistreeMin.roundToInt().coerceAtLeast(1)
        val r = Random(sessionHex.hashCode().toLong())

        // La periode hors poignet telle que le generateur l'a posee, relue dans le masque plutot
        // que redevinee : c'est la seule facon que le drapeau `offBody` de la telemetrie raconte
        // la meme nuit que les fenetres de sommeil.
        val horsPoignet = truth.mask.windows.filter { it.stage == Stage.OUT_OF_BED }

        // 45 % de batterie sur une nuit de 8 h, mise a l'echelle de la duree reelle. C'est l'ordre
        // de grandeur d'un enregistrement accelerometrique continu a 50 Hz.
        val consommationPct = 45.0 * (dureeEnregistreeMin / 480.0)

        return (0 until combien).map { i ->
            val msRel = i * PERIODE_MS
            val avancement = i.toDouble() / combien
            val pct = (100.0 - consommationPct * avancement).roundToInt().coerceIn(1, 100)
            val dansHorsPoignet = horsPoignet.any { msRel >= it.startMsRel && msRel < it.endMsRel }

            TelemetryPointEntity(
                sessionHex = sessionHex,
                // L'horloge monotone porte l'unicite de la ligne (`UNIQUE(sessionHex,
                // elapsedRealtimeNs)`) : elle ne repasse jamais deux fois par la meme valeur.
                elapsedRealtimeNs = msRel * 1_000_000L,
                sensorTsNs = spec.startNs + msRel * 1_000_000L,
                batteryChargeUah = (CAPACITE_UAH * pct / 100.0).roundToInt(),
                // Intervalle maximal entre deux echantillons sur la minute ecoulee : la periode
                // nominale, plus la dispersion d'un vidage de FIFO.
                maxIntervalUs = 20_000L + r.nextInt(9_000).toLong(),
                fsyncTotalUs = 12_000L + r.nextInt(30_000).toLong(),
                fsyncMaxUs = 3_000L + r.nextInt(9_000).toLong(),
                // Une montre au poignet est plus chaude que l'air ; elle refroidit un peu quand
                // elle en sort.
                temperatureDeciC = if (dansHorsPoignet) 240 + r.nextInt(30) else 305 + r.nextInt(25),
                measuredRateCentiHz = 4_990 + r.nextInt(25),
                jitterStdUs = 210 + r.nextInt(180),
                clippedSamples = 0,
                fsyncCount = 10 + r.nextInt(4),
                batteryPct = pct,
                offBody = if (dansHorsPoignet) {
                    TelemetryPoint.OFF_BODY_RETIRE
                } else {
                    TelemetryPoint.OFF_BODY_PORTE
                },
                // Jamais en charge : une montre sur son socle n'enregistre pas la nuit, et un
                // point sous charge sortirait de toute regression de pente de batterie — donc en
                // fabriquer un rendrait la pente ininterpretable pour rien.
                charging = false,
            )
        }
    }
}
