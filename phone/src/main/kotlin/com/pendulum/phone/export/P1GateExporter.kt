package com.pendulum.phone.export

import android.content.Context
import android.net.Uri
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.model.BatterySlope
import com.pendulum.phone.ui.model.Checks
import com.pendulum.phone.ui.model.P1Gate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

/**
 * The export of the P1 gate report, in CSV.
 *
 * ### Why this file exists
 *
 * It is the one that will decide whether the project carries on with Wear OS or switches to a
 * dedicated recorder of the Axivity AX3 kind (`01-overview.md` §5). A hardware decision taken on
 * a memory — "it seemed to hold" — is exactly the kind of decision one regrets six months later
 * with no way to replay it. It has to rest on a file, and that file has to be readable without
 * the application that produced it: it therefore carries its thresholds, its conclusion and its
 * limits at the top.
 *
 * ### The same SAF path as the rest
 *
 * Writing goes into an `OutputStream` supplied by the caller, in practice that of a `Uri`
 * obtained through `ACTION_CREATE_DOCUMENT`. The application **never** writes into a shared
 * directory on its own initiative, and it does not declare the `INTERNET` permission: the file
 * can only go where the user has pointed it.
 *
 * ### Every night, not the last fourteen
 *
 * The screen shows fourteen because a run and its failures have to be seen together; the file
 * truncates none of them. An export that cut off the older nights would be an export that picks
 * its evidence.
 */
object P1GateExporter {

    /** The column names, in order. In English, like everything that leaves the application. */
    private val HEADER = listOf(
        "night_key",
        "session_hex",
        "zone_id",
        "start_wall_ms",
        "end_wall_ms",
        "recorded_hours",
        "sample_count",
        "expected_samples",
        "coverage",
        "coverage_min",
        "coverage_state",
        "battery_pct_end",
        "battery_min_pct",
        // The slope, its three supporting figures and its result. An extrapolated percentage
        // without the number of points carrying the line cannot be read back: thirty points and
        // four hundred points give the same column if it is not written down.
        "battery_points_used",
        "battery_points_charging",
        "battery_uah_per_h",
        "battery_pct_at_8h",
        "battery_state",
        "fs_measured_hz",
        "fs_nominal_hz",
        "fs_tolerance",
        "fs_state",
        "gap_count",
        "gap_total_s",
        "truncated",
        "session_state",
        "stop_reason",
        "verdict",
    )

    suspend fun exportTo(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { exportCsv(context, it) }
        }
    }

    suspend fun exportCsv(context: Context, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val sessions = db.nightDao().all()
        // The same telemetry as the screen's, read the same way. The file and the screen must
        // return the same verdict on the same night: making them read two sources would reopen
        // exactly the gap that `Checks` closed on the battery threshold.
        val telemetry = sessions.associate { it.sessionHex to db.telemetryDao().ofSession(it.sessionHex) }
        out.write(csv(sessions, telemetry).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * The complete document, in memory. Pure, therefore testable — and with no size risk: eighty
     * nights are a few kilobytes, where a single night of raw signal is ninety thousand.
     */
    internal fun csv(
        sessions: List<NightSessionEntity>,
        telemetry: Map<String, List<TelemetryPointEntity>> = emptyMap(),
    ): String {
        val verdicts = sessions.map { P1Gate.of(it, telemetry[it.sessionHex].orEmpty()) }
        val campaign = P1Gate.campaign(verdicts)
        return buildString {
            // The preamble is commented with `#`: spreadsheets and `pandas` know to ignore it, and
            // a file that does not carry its thresholds forces one to track down the version of
            // the application that produced it in order to know what "0.987" meant.
            appendLine("# Pendulum — P1 hardware feasibility gate")
            appendLine("# coverage is computed on sensor timestamps, never on arrival time")
            appendLine("# coverage_min=${number(Checks.MIN_COVERAGE)}")
            appendLine("# battery_min_pct=${Checks.MIN_BATTERY_PCT} at ${P1Gate.TARGET_DURATION_H.toInt()} h")
            appendLine("# fs_tolerance=${number(Checks.FS_TOLERANCE)}")
            appendLine("# nights_required_consecutive=${P1Gate.CONSECUTIVE_NIGHTS}")
            appendLine("# nights_examined=${campaign.nightsExamined}")
            appendLine("# nights_inside_p1=${campaign.compliantNights}")
            appendLine("# longest_consecutive_run=${campaign.longestStreak}")
            appendLine("# run_from=${campaign.streakStart ?: ""}")
            appendLine("# run_to=${campaign.streakEnd ?: ""}")
            appendLine("# gate_passed=${campaign.crossed}")
            // The two measurement gaps, in the file and not only on the screen: an empty column
            // whose emptiness is unexplained reads as a fault.
            appendLine("# not transmitted: largest single gap (only the total reaches the phone)")
            appendLine("# battery_pct_at_8h is a least-squares fit on the coulomb counter,")
            appendLine("# charging points removed, refused below ${BatterySlope.MIN_POINTS} points")
            appendLine("#   or ${number(BatterySlope.MIN_DURATION_H, 1)} h of observed discharge")
            appendLine(HEADER.joinToString(","))
            for ((session, verdict) in sessions.zip(verdicts)) {
                appendLine(row(session, verdict, telemetry[session.sessionHex].orEmpty()))
            }
        }
    }

    private fun row(
        s: NightSessionEntity,
        v: P1Gate.NightVerdict,
        telemetry: List<TelemetryPointEntity>,
    ): String {
        val coverage = Checks.coverage(s)
        val hours = P1Gate.recordedHours(s)
        val expected = hours?.let { it * 3_600.0 * s.nominalRateHz }
        // The same slope as the one that produced `battery_state`, not a second one. A file whose
        // supporting columns were not the ones that carried the verdict would be worse than a
        // file with no supporting columns at all.
        val slope = BatterySlope.of(telemetry, P1Gate.TARGET_DURATION_H)
        return listOf(
            v.evening.toString(),
            s.sessionHex,
            s.zoneId,
            s.startWallMs.toString(),
            s.endWallMs?.toString() ?: "",
            hours?.let { number(it, 3) } ?: "",
            s.sampleCount.toString(),
            expected?.let { number(it, 0) } ?: "",
            coverage?.let { number(it, 5) } ?: "",
            number(Checks.MIN_COVERAGE),
            v.coverage.state.name,
            s.batteryPctLast?.toString() ?: "",
            Checks.MIN_BATTERY_PCT.toString(),
            slope?.pointsKept?.toString() ?: "",
            slope?.pointsCharging?.toString() ?: "",
            slope?.let { number(it.slopeUahPerH, 1) } ?: "",
            slope?.let { number(it.pctAt8h, 1) } ?: "",
            v.battery.state.name,
            s.fsMeasuredHz?.let { number(it, 4) } ?: "",
            s.nominalRateHz.toString(),
            number(Checks.FS_TOLERANCE),
            v.frequency.state.name,
            s.gapCount.toString(),
            number(s.gapTotalMs / 1000.0, 1),
            s.truncated.toString(),
            s.state,
            escape(s.stopReason.orEmpty()),
            v.verdict.name,
        ).joinToString(",")
    }

    /**
     * Decimal point, imposed through the locale.
     *
     * On a phone set to French, `"%.3f".format(v)` gives `0,987` — that is, a field containing the
     * column separator. The file stays syntactically valid and shifts every column by one from the
     * first non-integer value onwards, which is the worst kind of corruption: the kind that reads
     * without an error.
     */
    private fun number(v: Double, decimals: Int = 2): String =
        "%.${decimals}f".format(Locale.UK, v)

    /** No field should contain a comma; if one does, it is quoted. */
    private fun escape(v: String): String =
        if (v.contains(',') || v.contains('"')) "\"${v.replace("\"", "\"\"")}\"" else v
}
