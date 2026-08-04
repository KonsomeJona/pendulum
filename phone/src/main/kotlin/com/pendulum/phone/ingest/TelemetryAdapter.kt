package com.pendulum.phone.ingest

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity

/**
 * Le passage du bloc `TLM!` a la ligne de base.
 *
 * ### Pourquoi c'est une projection et pas une interpretation
 *
 * Aucune unite n'est convertie, aucun champ n'est renomme, aucune sentinelle n'est traduite en
 * `null`. La table est l'image du bloc, et c'est ce qui permet de relire une ligne de base a cote
 * de la KDoc de [TelemetryPoint] sans table de correspondance.
 *
 * La tentation inverse — convertir `batteryPct = 255` en `null`, les dixiemes de degre en degres,
 * les microsecondes en millisecondes — coute deux conventions au lieu d'une : celle du format et
 * celle de la base. Le jour ou elles divergent, la divergence porte sur des grandeurs qui decident
 * du seuil de detection, et rien ne la signale. Les sentinelles sont donc interpretees **au point
 * de lecture**, une seule fois, par `com.pendulum.phone.ui.model.Metrologie`.
 *
 * ### Ce que l'adaptateur ne fait pas
 *
 * Il ne filtre rien. Un point dont la batterie est illisible, dont le capteur off-body est absent
 * ou dont la cadence est nulle est ecrit tel quel : c'est un fait de la nuit, et une nuit dont on
 * ne garderait que les points exploitables serait une nuit dont on aurait efface les moments ou
 * la mesure a manque — exactement ceux qui expliquent le reste.
 */
object TelemetryAdapter {

    fun versEntite(sessionHex: String, p: TelemetryPoint): TelemetryPointEntity = TelemetryPointEntity(
        sessionHex = sessionHex,
        elapsedRealtimeNs = p.elapsedRealtimeNs,
        sensorTsNs = p.sensorTsNs,
        batteryChargeUah = p.batteryChargeUah,
        maxIntervalUs = p.maxIntervalUs,
        fsyncTotalUs = p.fsyncTotalUs,
        fsyncMaxUs = p.fsyncMaxUs,
        temperatureDeciC = p.temperatureDeciC,
        measuredRateCentiHz = p.measuredRateCentiHz,
        jitterStdUs = p.jitterStdUs,
        clippedSamples = p.clippedSamples,
        fsyncCount = p.fsyncCount,
        batteryPct = p.batteryPct,
        offBody = p.offBody,
        charging = p.charging,
    )

    fun versEntites(sessionHex: String, points: List<TelemetryPoint>): List<TelemetryPointEntity> =
        points.map { versEntite(sessionHex, it) }
}
