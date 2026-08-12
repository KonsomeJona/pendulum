package com.pendulum.phone.health

import android.content.Context
import android.os.Build
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Reading sleep from Health Connect.
 *
 * ### Why an external source, and not the ankle watch
 *
 * The PLMI is a fraction: movements / hours of sleep. The ankle watch produces the numerator very
 * well and **cannot** honestly produce the denominator — actigraphy infers "asleep/awake" from a
 * movement statistic, yet the event we are counting *is* a movement. Every PLMS burst would push
 * the algorithm into declaring "wake": the numerator goes up while the denominator goes down, and
 * the error on the ratio is doubled in the same direction. That is a systematic bias, not noise:
 * it does not average out over several nights.
 *
 * The good news is solid: what we need (the sleep/wake boundary, hence the TST) is what consumer
 * watches do **best** (sensitivity ~0.95); what they do badly (the stages, kappa 0.34-0.47) is
 * what we need **least** — the PLMI does not weight by stage. The stages serve the biological
 * plausibility check, not the figure.
 *
 * ### The bias to know about and to name
 *
 * Specificity ~0.52: the watch declares "asleep" for close to one wake epoch in two, and therefore
 * **overestimates** the TST. Since the TST is the denominator, the index comes out structurally
 * **underestimated**. This bias runs against the upward one from breathing-related movements. It
 * must above all not be claimed that they cancel out: they are named separately.
 *
 * ### The two permissions
 *
 * `READ_SLEEP` **and** `READ_HEALTH_DATA_IN_BACKGROUND`. The second is not a comfort extra:
 * `SleepFetchWorker` runs by construction with the application closed, and without it the read
 * fails outside the foreground. This is a gap in SPEC v1, fixed here.
 */
class SleepReader(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    /**
     * State of the read. Five outcomes, and they are not repaired the same way: that is why this
     * is not a boolean.
     */
    enum class Availability {
        /** Health Connect present, permissions granted, background read available. */
        READY,

        /** Health Connect absent or too old on this device. */
        SDK_UNAVAILABLE,

        /** Present but an update is required before use. */
        UPDATE_REQUIRED,

        /** Permissions not granted: this is a user gesture, not an error. */
        PERMISSIONS_MISSING,

        /**
         * Permissions granted but the background read is **not** available on this version. A case
         * to handle separately: scheduling `SleepFetchWorker` anyway would produce silent failures
         * all night long. See `hasBackgroundReadFeature`.
         */
        BACKGROUND_READ_UNAVAILABLE,
    }

    suspend fun availability(): Availability {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_UNAVAILABLE -> return Availability.SDK_UNAVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                return Availability.UPDATE_REQUIRED
        }
        val c = client ?: return Availability.SDK_UNAVAILABLE
        // **The order matters.** The availability of the *feature* is checked BEFORE the
        // permissions. On a provider that cannot read in the background,
        // `READ_HEALTH_DATA_IN_BACKGROUND` does not exist: it can therefore never appear in
        // `getGrantedPermissions()`. Testing it second returned `PERMISSIONS_MISSING` for ever and
        // sent the user into a permission request that cannot succeed, instead of saying that the
        // device cannot do it — the `BACKGROUND_READ_UNAVAILABLE` branch was dead.
        if (!hasBackgroundReadFeature(c)) return Availability.BACKGROUND_READ_UNAVAILABLE
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.containsAll(REQUIRED_PERMISSIONS)) return Availability.PERMISSIONS_MISSING
        return Availability.READY
    }

    /**
     * Check of the "background read" feature, **before** scheduling the worker. A granted
     * permission does not say that the platform can honour it: the two things are distinct in the
     * API, and checking only the first gives a worker that runs and reads nothing.
     */
    private fun hasBackgroundReadFeature(c: HealthConnectClient): Boolean = runCatching {
        c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /**
     * The result of a read, as it goes into `hc_snapshot`.
     *
     * @param aggregateTstMin TST returned by `aggregate(SLEEP_DURATION_TOTAL)`, **deduplicated by
     *   the system**. It does not serve as the result: it serves as a cross-check. If our own TST
     *   departs from it by more than ~10 %, it is our deduplication that is wrong — and without
     *   this check, nothing would say so.
     */
    data class Reading(
        val selection: SleepSourceSelector.Selection,
        val allCandidates: List<SleepSourceSelector.Candidate>,
        val aggregateTstMin: Double?,
        val verdict: String,
        val readAtMs: Long,
    )

    /**
     * Reads the sessions that overlap `[windowStartMs, windowEndMs]`, with a margin.
     *
     * The margin exists because a sleep session often starts before the ankle watch starts and
     * ends after it stops. Filtering strictly on the recording window would cut the hypnogram at
     * the edges and remove real sleep from the denominator.
     */
    suspend fun read(
        windowStartMs: Long,
        windowEndMs: Long,
        preferredPackage: String?,
        marginMinutes: Long = 180,
    ): Reading? {
        val c = client ?: return null
        val from = Instant.ofEpochMilli(windowStartMs).minusSeconds(marginMinutes * 60)
        val to = Instant.ofEpochMilli(windowEndMs).plusSeconds(marginMinutes * 60)

        val candidates = readCandidates(from, to)

        // Cross-check. `aggregate` deduplicates (Activity and Sleep only) according to the priority
        // set by the user, but never returns the stages: it therefore cannot replace `readRecords`,
        // only verify it.
        val aggregateTstMin = runCatching {
            c.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(60_000.0)
        }.getOrNull()

        val selection = SleepSourceSelector.select(
            candidates, windowStartMs, windowEndMs, preferredPackage,
        )
        val verdict = selection.chosen
            ?.let { SleepSourceSelector.verdictOf(it, windowStartMs, windowEndMs) }
            ?: "NO_SESSION"

        return Reading(
            selection = selection,
            allCandidates = candidates,
            aggregateTstMin = aggregateTstMin,
            verdict = verdict,
            readAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * The sources that have written a sleep session over the last [days] days, with the number of
     * nights each of them covers.
     *
     * This is what step 4 of onboarding displays, in place of the two names that were hard-coded
     * there. `null` — and not an empty list — when Health Connect is not usable: the two cases are
     * displayed differently, since "no application writes sleep" and "Health Connect is not
     * installed" are repaired in two different places.
     */
    suspend fun recentSources(
        nowMs: Long,
        days: Int = SleepSources.OBSERVED_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SleepSources.Observed>? {
        client ?: return null
        val to = Instant.ofEpochMilli(nowMs)
        val from = to.minus(days.toLong(), ChronoUnit.DAYS)
        return runCatching { SleepSources.summarise(readCandidates(from, to), zone) }.getOrNull()
    }

    /**
     * Paginated read of the sessions of a window, translated into candidates.
     *
     * Pagination is mandatory: `ReadRecordsRequest` has a **default page size of 1000** and the
     * response says only one thing when it is full — it returns a non-null `pageToken`. Reading
     * only the first page truncates silently, and the failure mode is exactly the one we are
     * trying to avoid: a missing sleep source loses the denominator without any error surfacing.
     * The case is rare over one night, less so over seven days, and "rare and silent" is worse than
     * "frequent and noisy".
     */
    private suspend fun readCandidates(
        from: Instant,
        to: Instant,
    ): List<SleepSourceSelector.Candidate> {
        val c = client ?: return emptyList()
        val records = buildList {
            var pageToken: String? = null
            do {
                val response = c.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = pageToken,
                    )
                )
                addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
        }

        return records.map { r ->
            SleepSourceSelector.Candidate(
                recordId = r.metadata.id,
                packageName = r.metadata.dataOrigin.packageName,
                startMs = r.startTime.toEpochMilli(),
                endMs = r.endTime.toEpochMilli(),
                lastModifiedMs = r.metadata.lastModifiedTime.toEpochMilli(),
                stages = r.stages.map {
                    SleepSourceSelector.StageSpan(
                        startMs = it.startTime.toEpochMilli(),
                        endMs = it.endTime.toEpochMilli(),
                        stageType = it.stage,
                    )
                },
            )
        }
    }

    companion object {

        /**
         * `READ_HEALTH_DATA_HISTORY` is requested because `RescoreAllWorker` re-reads the
         * hypnogram of **every** night from the raw data: beyond 30 days, reading data written by
         * another application is refused without it.
         *
         * A trap to know about: the 30-day window is reset by an uninstall/reinstall. Aggressive
         * debugging can therefore lose access to one's own earlier nights — that is also why
         * `hc_snapshot` keeps what Health Connect returned, instead of counting on re-reading it
         * later.
         */
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

        val OPTIONAL_PERMISSIONS: Set<String> = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        /** Contract to launch from the interface in order to request the permissions. */
        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()

        /**
         * Below this delay, the dialog was not shown.
         *
         * Health Connect follows the runtime permission rule: after two refusals, the system stops
         * displaying the dialog and the contract returns **immediately**, showing nothing and
         * saying nothing. There is no API to tell this case apart from an ordinary refusal —
         * `shouldShowRequestPermissionRationale` is worthless here, Health Connect being only a
         * broker for these permissions.
         *
         * Elapsed time is therefore the only signal available. The threshold is deliberately low:
         * a round trip to a real dialog, even refused with an immediate gesture, asks the system to
         * open and close an activity. Erring in this direction sends the user towards a fresh
         * request, which is benign; erring in the other would hide from them the only way out they
         * have left.
         */
        const val SUPPRESSED_DIALOG_MS = 400L

        /**
         * The Health Connect screen where the permission is granted by hand, when the dialog no
         * longer appears.
         *
         * Two paths depending on the version: since Android 14 Health Connect is part of the
         * platform and can open the page **of this application**, which avoids making the user
         * hunt for Pendulum in a list. Before that, only the general screen exists.
         *
         * Returns `null` when nothing resolves the intent — the caller must then stay silent
         * rather than offer a button that does nothing.
         */
        fun manualPermissionsIntent(context: Context): Intent? {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            } else {
                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            }
            return intent.takeIf {
                it.resolveActivity(context.packageManager) != null
            }
        }

        /**
         * `android.health.connect.HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS`, spelled
         * out: the constant lives in a platform class that only exists from Android 14 onwards, and
         * referencing it there would force raising `compileSdk` for one string.
         */
        private const val ACTION_MANAGE_HEALTH_PERMISSIONS =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
    }
}
