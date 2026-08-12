package com.pendulum.sleepwriter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the tool can do. One action per launch, never two. */
enum class Mode(val key: String) {
    /** Writes a session according to the scenario. */
    WRITE("write"),

    /** Reads back and logs, without writing anything. This is the bench's oracle. */
    VERIFY("verify"),

    /** Reports the state of the permissions, without writing or reading anything. */
    PERMISSIONS("permissions"),

    /**
     * Erases the sessions written **by this application** inside the window.
     *
     * Health Connect only allows deleting one's own records, which makes this mode harmless for
     * another source's data. It exists because an emulator reloaded from a snapshot keeps the
     * nights of the previous scenario: without it, the `none` scenario (`E-HC-01`) means nothing at
     * all the second time it is run.
     */
    PURGE("purge");

    companion object {
        fun from(key: String?): Mode? = entries.firstOrNull { it.key == key }
    }
}

/** The four situations Pendulum must be able to take on the denominator side. */
enum class Scenario(val key: String) {
    /** Full night with stages. See [Hypnogram]. */
    FULL_NIGHT("full-night"),

    /**
     * Duration only, without any stage.
     *
     * This is not an exotic degraded case: many consumer devices write nothing else.
     * `Hypnogram.toWindows` then produces a single `SLEEP` window, `SleepSourceSelector` returns
     * the `STAGES_MISSING` verdict, and Pendulum must **say so** rather than break down by stage
     * information it does not have.
     */
    DURATION_ONLY("duration-only"),

    /**
     * Nothing at all, for `E-HC-01`.
     *
     * Scenario by omission: there is nothing to write. It exists so that the bench script stays
     * symmetric — one scenario per situation, even when the situation is an absence — and so that
     * the log trace attests that this emptiness was intended rather than suffered.
     */
    NONE("none"),

    /**
     * Two overlapping sources, for `E-HC-03`.
     *
     * Variant A writes the full night with its stages, variant B a shorter and shifted
     * duration-only session. `SleepSourceSelector` must retain A through the "more distinct stage
     * types" criterion and not through an accident in the order the API returns things. The
     * durations differ on purpose: two identical sources would not prove which one was chosen.
     */
    CONFLICT("conflict");

    companion object {
        fun from(key: String?): Scenario? = entries.firstOrNull { it.key == key }
    }
}

/**
 * @param delayMs wait **in real time** before writing. This is how the bench simulates the
 *   hypnogram arriving after waking — a **normal** case and not a failure: the wrist watch does not
 *   transfer its night on waking but when its battery policy decides to. The bench compresses six
 *   hours into a few seconds; it does not shift the recorded window, which stays that of the night.
 * @param marginMin read margin for [Mode.VERIFY], on either side of the window. Same default value
 *   as `SleepReader.read`: a sleep session starts before the ankle watch starts and ends after it
 *   stops.
 */
data class Request(
    val mode: Mode,
    val scenario: Scenario,
    val startMs: Long,
    val endMs: Long,
    val delayMs: Long = 0L,
    val marginMin: Long = 180L,
)

/**
 * Writing to and reading back from Health Connect.
 *
 * ### What this file assumes about Health Connect, for lack of having been able to run it
 *
 * Three behaviours are deduced from the documentation and not measured; they are written down here
 * so that whoever runs the bench first knows where to look if something breaks:
 *  1. **a session whose end is in the future is refused.** The write then raises an exception
 *     rather than writing a shifted record. The case is detected and reported by the `warn` field,
 *     but the write is attempted all the same: hiding Health Connect's real error behind a
 *     home-made refusal would make diagnosis harder, not easier;
 *  2. **stages must fit inside the session, must not overlap, and must be ordered.** [Hypnogram]
 *     guarantees all three by construction;
 *  3. **`clientRecordId` performs an update and not a duplicate.** That is what makes a command
 *     replayable: rerunning exactly the same write replaces the record instead of creating a second
 *     session overlapping itself — which would manufacture a false `E-HC-03` with a single
 *     application.
 */
class SleepWriter(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    /** Runs the request and returns the log line that was emitted. */
    suspend fun execute(r: Request): String = when (r.mode) {
        Mode.PERMISSIONS -> permissions()
        Mode.WRITE -> write(r)
        Mode.VERIFY -> verify(r)
        Mode.PURGE -> purge(r)
    }

    private suspend fun permissions(): String {
        val sdkStatus = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "AVAILABLE"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "UPDATE_REQUIRED"
            else -> "UNAVAILABLE"
        }
        val c = client
        val granted = if (c == null) emptySet() else runCatching {
            c.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        val canWrite = PERMISSION_WRITE in granted
        val canRead = PERMISSION_READ in granted
        return emit(
            Mode.PERMISSIONS,
            if (c == null) BenchLog.Status.HC_UNAVAILABLE
            else if (canWrite && canRead) BenchLog.Status.OK
            else BenchLog.Status.PERMISSION_MISSING,
            listOf(
                "sdk" to sdkStatus,
                "write" to if (canWrite) "GRANTED" else "DENIED",
                "read" to if (canRead) "GRANTED" else "DENIED",
            ),
        )
    }

    private suspend fun write(r: Request): String {
        val windowFields = window(r)

        if (r.scenario == Scenario.NONE) {
            return emit(Mode.WRITE, BenchLog.Status.NOTHING_TO_WRITE, windowFields + listOf("records" to 0))
        }
        val c = client
            ?: return emit(Mode.WRITE, BenchLog.Status.HC_UNAVAILABLE, windowFields)
        if (PERMISSION_WRITE !in runCatching {
                c.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
        ) {
            return emit(Mode.WRITE, BenchLog.Status.PERMISSION_MISSING, windowFields)
        }
        if (r.endMs <= r.startMs) {
            return emit(Mode.WRITE, BenchLog.Status.BAD_PARAM, windowFields)
        }

        // The conflict scenario writes two different things depending on the variant. The code is
        // the same in both APKs; `BuildConfig.SOURCE_LABEL` is what decides, because what
        // distinguishes the two sources is not their behaviour but their package name.
        val isSourceB = BuildConfig.SOURCE_LABEL == "B"
        val start = if (r.scenario == Scenario.CONFLICT && isSourceB) {
            r.startMs + 45 * 60_000L
        } else {
            r.startMs
        }
        val end = if (r.scenario == Scenario.CONFLICT && isSourceB) {
            r.endMs - 30 * 60_000L
        } else {
            r.endMs
        }
        val withStages = when (r.scenario) {
            Scenario.FULL_NIGHT -> true
            Scenario.DURATION_ONLY -> false
            Scenario.CONFLICT -> !isSourceB
            Scenario.NONE -> false
        }

        val stages = if (withStages) Hypnogram.fullNight(start, end) else emptyList()
        val zone = ZoneId.systemDefault()
        val startInstant = Instant.ofEpochMilli(start)
        val endInstant = Instant.ofEpochMilli(end)

        val record = SleepSessionRecord(
            startTime = startInstant,
            startZoneOffset = zone.rules.getOffset(startInstant),
            endTime = endInstant,
            endZoneOffset = zone.rules.getOffset(endInstant),
            title = "Pendulum bench ${BuildConfig.SOURCE_LABEL}",
            notes = r.scenario.key,
            stages = stages.map {
                SleepSessionRecord.Stage(
                    startTime = Instant.ofEpochMilli(it.startMs),
                    endTime = Instant.ofEpochMilli(it.endMs),
                    stage = it.type,
                )
            },
            // `autoRecorded` and `TYPE_WATCH`: this is what a wrist watch would write, and the
            // recording method is a field Health Connect exposes to readers. A `manualEntry` would
            // make the bench look like a hand-typed entry, which no real denominator source is.
            metadata = Metadata.autoRecorded(
                device = Device(
                    type = Device.TYPE_WATCH,
                    manufacturer = "Pendulum",
                    model = "SleepWriter${BuildConfig.SOURCE_LABEL}",
                ),
                clientRecordId = "pendulum-bench-${r.scenario.key}-$start-$end",
                clientRecordVersion = System.currentTimeMillis(),
            ),
        )

        val futureWarn = if (end > System.currentTimeMillis()) "window-in-the-future" else null

        return runCatching { c.insertRecords(listOf(record)) }.fold(
            onSuccess = { response ->
                emit(
                    Mode.WRITE, BenchLog.Status.OK,
                    windowFields + listOf(
                        "records" to 1,
                        "stages" to stages.size,
                        "writtenStartMs" to start,
                        "writtenEndMs" to end,
                        "writtenDurationMin" to (end - start) / 60_000L,
                        "ids" to response.recordIdsList.joinToString(";"),
                        "warn" to futureWarn,
                    ),
                )
            },
            onFailure = { error ->
                emit(
                    Mode.WRITE, BenchLog.Status.ERROR,
                    windowFields + listOf(
                        "records" to 0,
                        "warn" to futureWarn,
                        "err" to "${error.javaClass.simpleName}:${error.message}",
                    ),
                )
            },
        )
    }

    /**
     * Reads back **every** source in the window, not only its own.
     *
     * Reading back only its own records would be enough to say "the write succeeded", but not to
     * say what Pendulum is going to see. The `origins` field therefore carries one source per item,
     * with its record and stage counts: that is exactly the input of `SleepSourceSelector`, and it
     * is what lets a script check that a conflict scenario really produced two origins and not a
     * single one written twice.
     */
    private suspend fun verify(r: Request): String {
        val c = client
            ?: return emit(Mode.VERIFY, BenchLog.Status.HC_UNAVAILABLE, window(r))
        if (PERMISSION_READ !in runCatching {
                c.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
        ) {
            return emit(Mode.VERIFY, BenchLog.Status.PERMISSION_MISSING, window(r))
        }

        val from = Instant.ofEpochMilli(r.startMs).minusSeconds(r.marginMin * 60)
        val to = Instant.ofEpochMilli(r.endMs).plusSeconds(r.marginMin * 60)

        val records = runCatching { read(c, from, to) }.getOrElse { error ->
            return emit(
                Mode.VERIFY, BenchLog.Status.ERROR,
                window(r) + listOf("err" to "${error.javaClass.simpleName}:${error.message}"),
            )
        }

        val byOrigin = records.groupBy { it.metadata.dataOrigin.packageName }
        val origins = byOrigin.entries.sortedBy { it.key }.joinToString(";") { (pkg, list) ->
            "$pkg:${list.size}:${list.sumOf { it.stages.size }}"
        }
        val tstMin = records.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } / 60_000L

        return emit(
            Mode.VERIFY,
            if (records.isEmpty()) BenchLog.Status.NOTHING_TO_WRITE else BenchLog.Status.OK,
            window(r) + listOf(
                "marginMin" to r.marginMin,
                "records" to records.size,
                "stages" to records.sumOf { it.stages.size },
                "sumDurationMin" to tstMin,
                "mine" to (byOrigin[context.packageName]?.size ?: 0),
                "origins" to origins,
            ),
        )
    }

    private suspend fun purge(r: Request): String {
        val c = client
            ?: return emit(Mode.PURGE, BenchLog.Status.HC_UNAVAILABLE, window(r))
        val from = Instant.ofEpochMilli(r.startMs).minusSeconds(r.marginMin * 60)
        val to = Instant.ofEpochMilli(r.endMs).plusSeconds(r.marginMin * 60)
        return runCatching {
            c.deleteRecords(SleepSessionRecord::class, TimeRangeFilter.between(from, to))
        }.fold(
            onSuccess = { emit(Mode.PURGE, BenchLog.Status.OK, window(r)) },
            onFailure = {
                emit(
                    Mode.PURGE, BenchLog.Status.ERROR,
                    window(r) + listOf("err" to "${it.javaClass.simpleName}:${it.message}"),
                )
            },
        )
    }

    /**
     * Paginated read. Same reason as in `SleepReader.readCandidates`: the default page size is 1000
     * and a full response only signals itself through a non-null `pageToken`. Reading the first
     * page only truncates silently — and an oracle that truncates silently is worse than no oracle
     * at all.
     */
    private suspend fun read(
        c: HealthConnectClient,
        from: Instant,
        to: Instant,
    ): List<SleepSessionRecord> = buildList {
        var page: String? = null
        do {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageToken = page,
                )
            )
            addAll(response.records)
            page = response.pageToken
        } while (page != null)
    }

    private fun window(r: Request): List<Pair<String, Any?>> = listOf(
        "scenario" to r.scenario.key,
        "source" to BuildConfig.SOURCE_LABEL,
        "pkg" to context.packageName,
        "startMs" to r.startMs,
        "endMs" to r.endMs,
        "start" to ISO.format(Instant.ofEpochMilli(r.startMs)),
        "end" to ISO.format(Instant.ofEpochMilli(r.endMs)),
        "durationMin" to (r.endMs - r.startMs) / 60_000L,
    )

    private fun emit(mode: Mode, status: String, fields: List<Pair<String, Any?>>): String =
        BenchLog.emit(
            BenchLog.RESULT,
            listOf<Pair<String, Any?>>("mode" to mode.key, "status" to status) + fields,
        )

    companion object {

        val PERMISSION_WRITE: String =
            HealthPermission.getWritePermission(SleepSessionRecord::class)

        val PERMISSION_READ: String =
            HealthPermission.getReadPermission(SleepSessionRecord::class)

        /**
         * Both permissions requested together.
         *
         * In a single request and not two: every trip through Health Connect's consent screen is a
         * UiAutomator sequence to drive, and two sequences are twice as many chances to tap the
         * wrong place without any error saying so.
         */
        val PERMISSIONS: Set<String> = setOf(PERMISSION_WRITE, PERMISSION_READ)

        /**
         * Permission request contract.
         *
         * `pm grant` **does not work** for health permissions: they are managed by a Mainline
         * module with its own consent interface, and the command either fails or has no effect.
         * This contract is the only path, exactly as for reading on the `:phone` side
         * (`SleepReader.permissionRequestContract`).
         */
        fun permissionContract() = PermissionController.createRequestPermissionResultContract()

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
