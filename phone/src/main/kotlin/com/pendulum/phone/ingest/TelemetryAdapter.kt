package com.pendulum.phone.ingest

import com.pendulum.format.TelemetryPoint
import com.pendulum.phone.db.TelemetryPointEntity

/**
 * The step from the `TLM!` block to the database row.
 *
 * ### Why this is a projection and not an interpretation
 *
 * No unit is converted, no field is renamed, no sentinel is translated into `null`. The table is
 * the image of the block, and that is what makes it possible to read a database row next to the
 * KDoc of [TelemetryPoint] with no correspondence table.
 *
 * The opposite temptation — turning `batteryPct = 255` into `null`, tenths of a degree into
 * degrees, microseconds into milliseconds — costs two conventions instead of one: that of the
 * format and that of the database. The day they diverge, the divergence bears on quantities that
 * decide the detection threshold, and nothing flags it. The sentinels are therefore interpreted
 * **at the point of reading**, once and only once, by `com.pendulum.phone.ui.model.Metrology`.
 *
 * ### What the adapter does not do
 *
 * It filters nothing. A point whose battery is unreadable, whose off-body sensor is absent or
 * whose rate is zero is written as it is: it is a fact of the night, and a night from which only
 * the usable points were kept would be a night whose moments of missing measurement had been
 * erased — exactly the ones that explain the rest.
 */
object TelemetryAdapter {

    fun toEntity(sessionHex: String, p: TelemetryPoint): TelemetryPointEntity = TelemetryPointEntity(
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

    fun toEntities(sessionHex: String, points: List<TelemetryPoint>): List<TelemetryPointEntity> =
        points.map { toEntity(sessionHex, it) }
}
