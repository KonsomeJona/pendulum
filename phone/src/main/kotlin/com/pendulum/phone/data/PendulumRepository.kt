package com.pendulum.phone.data

import android.content.Context
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.ComparableNight
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.ParamProfileEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.ui.home.HomeSession
import com.pendulum.phone.ui.home.HomeSource
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.ComputationPath
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.WakingMachine
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.Metrology
import com.pendulum.phone.ui.model.Checks
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.P1Gate
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.ui.nights.NightDetailUi
import com.pendulum.phone.ui.settings.P1ReportUi
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.work.AnalysisParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The bridge that was missing between the database and the screens.
 *
 * The whole chain already existed — Data Layer ingestion, analysis workers, Room, Health Connect,
 * export — and **no screen read it**. `PendulumNavHost` was hard-wired onto `PreviewData`, the
 * preview data set, which gave a fresh installation a complete trend over seven nights, a
 * hypnogram and a "Samsung Health" source. The database, meanwhile, stayed empty.
 *
 * ### What this file does not do
 *
 * It computes nothing new. The medians, the bootstrap intervals with a deterministic seed, the
 * MDC95 and the five position sentences are in [Aggregate], tested on its bounds; the exclusion
 * criteria are in the SQL view `comparable_night`, evaluated before any display code; the
 * formatting is in [Mapping], pure. This repository only **chooses what to read and in what
 * order**, which is exactly what a JVM test cannot check and which therefore has nothing else to
 * do here.
 *
 * ### Why no dependency injection
 *
 * The project has none, and introducing one in order to wire up five screens would amount to
 * passing off an architecture change as a defect fix. The database is already a process singleton
 * (`PendulumDatabase.get`), and the ViewModels build their repository from the application context.
 */
class PendulumRepository(context: Context) {

    private val app = context.applicationContext
    private val db = PendulumDatabase.get(app)
    private val prefs = PendulumPreferences(app)

    // -------------------------------------------------------------------------------------
    // Reading the nights
    // -------------------------------------------------------------------------------------

    /**
     * All the nights of the **active** parameter profile, annotated, in chronological order.
     *
     * The `paramsHash` is not an optional argument that could be forgotten: it comes from the
     * active profile in the database, and `TrendDao` exposes no overload without it. This is guard
     * rail 3 ("the trend refuses to mix two hashes") applied at the level of the type — mixing two
     * hashes would plot on one chart figures produced by two different algorithms, and the jump
     * between the two would read as a clinical change.
     */
    fun observeNights(): Flow<List<NightUi>> =
        observeReading().map { it.nights }.flowOn(Dispatchers.IO)

    /**
     * What three screens all three read: the sessions, the active hash, and the nights already
     * translated.
     *
     * All three assembled the same `combine` and the same `map` — twelve identical lines, copied
     * two and a half times. It was not only volume: the fallback
     * `?: AnalysisParams.DEFAULT.paramsHash` and the reverse-chronological sort appeared there
     * three times, so one screen could lose one of them without anything saying so.
     *
     * The preferred sleep source is no longer part of this read. It was, and it went into the
     * label of every night: see [toNightUi].
     */
    private data class Reading(
        val sessions: List<NightSessionEntity>,
        val profile: ParamProfileEntity?,
        val hash: String,
        val nights: List<NightUi>,
    )

    private fun observeReading(): Flow<Reading> =
        combine(
            db.nightDao().observeAll(),
            db.paramDao().observeActive(),
        ) { sessions, profile -> sessions to profile }
            .map { (sessions, profile) ->
                val hash = profile?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
                Reading(sessions, profile, hash, nightsOf(hash, sessions))
            }

    /**
     * The nights of the active hash, translated and sorted from the most recent to the oldest.
     *
     * Through `displayNights`, with its fallback: this read asked for the [DEFAULT_MASK] row and
     * nothing else, and `NightAnalyzer` writes that row only when Health Connect returned a
     * hypnogram. A night scored without one — every night of a user with no sleep application,
     * and every night in the hours before its hypnogram arrives — had its accelerometer rows, its
     * `analyzedAtMs`, and no row here: absent from the list, "0 nights recorded" on the home card
     * the morning after a night the user had just watched being analysed. The fallback row carries
     * its own flag and reads as provisional; the trend, which goes through `trendPoints`, still
     * never sees it.
     */
    private suspend fun nightsOf(
        hash: String,
        sessions: List<NightSessionEntity>,
    ): List<NightUi> {
        val byHex = sessions.associateBy { it.sessionHex }
        return db.trendDao().displayNights(hash, DEFAULT_RULE, DEFAULT_MASK, FALLBACK_MASK)
            .map { n -> toNightUi(n, byHex[n.sessionHex]) }
            .sortedByDescending { it.startWallMs }
    }

    /**
     * The nights that have the right to enter an aggregate: comparable **and** publishable.
     *
     * The two conditions are distinct and stay so. A night can be perfectly comparable — same leg,
     * same strap, stable calibration — and have no right at all to carry a figure, because its mask
     * did not converge or because its analysable sleep is insufficient.
     */
    private suspend fun aggregatableNights(hash: String): List<ComparableNight> =
        db.trendDao().trendPoints(hash, DEFAULT_RULE, DEFAULT_MASK)

    /**
     * What is needed to build the Trend screen, in a single coherent read.
     *
     * Returning a record rather than the final `TrendUiState` leaves the ViewModel the choice of
     * the refusal/ready branch, which is an interface decision, and keeps this file ignorant of
     * what is displayed.
     */
    fun observeTrend(): Flow<TrendState> =
        observeReading()
            .map { (sessions, profile, hash, nights) ->
                val aggregatable = aggregatableNights(hash)
                val fitted = aggregatable.filter { Mapping.rhythmSec(it) != null }

                // `observeAll` sorts by `startWallMs DESC`: the first row is the night the state
                // band speaks about. We do not filter on a night key — a night from the day before
                // yesterday still in transfer is exactly the one whose progress needs to be told.
                val recent = sessions.firstOrNull()
                val lastSnapshot = recent?.let { db.hcSnapshotDao().latest(it.sessionHex) }

                TrendState(
                    nights = nights,
                    aggregatableNights = aggregatable,
                    nightsWithFittedRhythm = fitted.size,
                    // The median of the rhythm covers only the nights whose fit was **accepted**.
                    // Without this filter, the refused nights entered the bootstrap with a finite
                    // but unidentified `fundamentalSec` — or with `NaN`, which makes the whole
                    // median `NaN` as soon as it falls on the right side of the sort.
                    rhythm = Mapping.aggregate(
                        Aggregate.Quantity.RHYTHM_SECONDS,
                        fitted,
                    ) { it.fundamentalSec },
                    count = Mapping.aggregate(
                        Aggregate.Quantity.HOURLY_COUNT,
                        aggregatable,
                    ) { it.plmi },
                    customProfile = profile
                        ?.takeIf { it.paramsHash != AnalysisParams.DEFAULT.paramsHash }
                        ?.label,
                    // Two hashes present in the recorded nights: the trend would plot only one, and
                    // silence about the other would be misleading.
                    mixedHashes = sessions
                        .mapNotNull { it.paramsHash }
                        .distinct()
                        .size > 1,
                    // The raw facts of the last night. The **decision** about what to display
                    // belongs to `WakingMachine`, pure and tested on its bounds; this file only
                    // reads, and it stays ignorant of the five states.
                    wakingFacts = recent?.let { s ->
                        WakingMachine.Facts(
                            sessionHex = s.sessionHex,
                            readableDate = Mapping.readableDate(s.startWallMs, s.zoneId),
                            zoneId = s.zoneId,
                            sessionState = s.state,
                            chunksReceived = db.chunkDao().count(s.sessionHex),
                            totalChunks = s.totalChunks,
                            bytesReceived = db.chunkDao().totalBytes(s.sessionHex),
                            analysedAtMs = s.analyzedAtMs,
                            nightEndMs = s.endWallMs,
                            // The only fact that says the figure rests on an independent
                            // denominator: a Health Connect sleep window exists for the current
                            // hash. A successful `hc_snapshot` is not enough — the rescore may not
                            // have taken place yet.
                            sleepMaskApplied = db.derivedDao()
                                .windowsOf(s.sessionHex, hash)
                                .any { it.source == DEFAULT_MASK },
                            hypnogramReceived = lastSnapshot?.selectedRecordId != null,
                            hcAttempts = db.hcSnapshotDao().attemptCount(s.sessionHex),
                            lastAttemptMs = lastSnapshot?.fetchedAtMs,
                            integrityRejected = s.integrityRejectedFraction,
                        )
                    },
                    // `originCount` counts the distinct applications that published a session
                    // overlapping this night. Two or more, and the denominator depends on which one
                    // is read: that is exactly E-HC-03.
                    lastNightOrigins = lastSnapshot?.originCount ?: 0,
                    // The time zone of the most recent night — `observeAll` already sorts by
                    // `startWallMs DESC`. The X axis of the trend is calendar-based: it needs a
                    // calendar, and the only defensible one is the calendar in which the nights
                    // were lived. A campaign straddling two time zones — a trip — will read in the
                    // later of the two; that is an accepted approximation, the only alternative
                    // being an axis whose scale changes in the middle.
                    zoneId = recent?.zoneId ?: java.time.ZoneId.systemDefault().id,
                )
            }
            .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------------------
    // The home screen
    // -------------------------------------------------------------------------------------

    /**
     * What the home screen must know, in a single coherent read.
     *
     * It returns a [HomeSource] — the persisted state — and not the final screen: the decision
     * ("prepare the night" rather than "end of night") lives in [HomeMachine], pure and tested on
     * its bounds, and this file stays ignorant of what is displayed.
     *
     * ### The clock is a parameter
     *
     * The night key rolls over at noon, so knowing whether the context of "the current evening" is
     * sealed depends on what time it is. Passing it as an argument is what makes the rule readable:
     * a clock called at the bottom of a function would make the attachment of a night untestable,
     * and the noon rollover is exactly the kind of rule that breaks in silence.
     *
     * Accepted limitation: the key is frozen when the flow is built, hence when the screen opens.
     * An application left open across noon would keep the previous day's key until it is recreated.
     * That is one second of lag on a daily event, against a `flatMapLatest` on a clock that would
     * recompose the screen for no reason.
     */
    fun observeHome(nowMs: Long): Flow<HomeSource> {
        val nightKey = WirePaths.nightKey(nowMs)
        return combine(
            db.nightDao().observeAll(),
            db.contextDao().observe(nightKey),
            db.paramDao().observeActive(),
            prefs.strapReference,
            prefs.preferredSleepSource,
        ) { sessions, nightContext, profile, reference, preferredSource ->
            val hash = profile?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
            val nights = nightsOf(hash, sessions)

            // `observeAll` already sorts by `startWallMs DESC`: the first row is the most recent
            // session, whatever evening it attaches to. We deliberately do not filter on the
            // current night key — a night from the day before yesterday left open is exactly what
            // the end-of-night button must be able to close.
            val recent = sessions.firstOrNull()

            HomeSource(
                recentSession = recent?.let {
                    HomeSession(
                        sessionHex = it.sessionHex,
                        state = it.state,
                        analysed = it.analyzedAtMs != null,
                        readableStart = Mapping.readableTime(it.startWallMs, it.zoneId),
                        readableDate = Mapping.readableDate(it.startWallMs, it.zoneId),
                    )
                },
                contextSealed = nightContext != null,
                sealedLeg = Mapping.legLabel(nightContext?.leg),
                // The strap of the sealed context takes precedence over the preference: it is the
                // one that was confirmed this evening, not the one from a previous evening.
                strapReference = nightContext?.strapId?.takeIf { it.isNotBlank() } ?: reference,
                sleepSource = preferredSource?.let {
                    Mapping.sourceLabel(DEFAULT_MASK, it)
                },
                recordedNights = nights.size,
                eligibleNights = nights.count { it.state == NightState.ELIGIBLE },
                // `comparable_night` only exists for nights that have already been scored: the
                // first row is therefore the last night that has a figure to reveal.
                lastAnalysedNight = nights.firstOrNull(),
            )
        }.flowOn(Dispatchers.IO)
    }

    /**
     * The sleep source is labelled from **the row**, `comparable_night.sourcePackage` — the
     * application whose hypnogram supplied this night's denominator under the active hash — and
     * not from `prefs.preferredSleepSource`, which is what every night was labelled with until
     * now. The preference is what the user *wants* read tonight; it says nothing about what *was*
     * read for a given night (it was not even handed to the read, see `SleepFetchWorker`), and
     * changing it relabelled the whole campaign at once. The home card still shows the preference,
     * as a setting, in [observeHome]; no night does.
     */
    private fun toNightUi(
        n: ComparableNight,
        session: NightSessionEntity?,
    ): NightUi = Mapping.nightUi(
        n = n,
        endWallMs = session?.endWallMs,
        sleepSource = Mapping.sourceLabel(n.maskSource, n.sourcePackage),
        flags = Mapping.flags(
            n = n,
            gapCount = session?.gapCount ?: 0,
            gapTotalMs = session?.gapTotalMs ?: 0L,
            batteryPctLast = session?.batteryPctLast,
        ),
    )

    // -------------------------------------------------------------------------------------
    // The detail of a night
    // -------------------------------------------------------------------------------------

    /**
     * Everything the database knows about a night.
     *
     * What is **not** in it: the envelope of the signal. It is computed during the analysis and
     * never persisted; rebuilding it would require re-reading the raw chunks and redoing the
     * processing chain, which has no place in the opening of a screen. The chart is therefore
     * `null` and the section is not drawn — rather than drawn with a manufactured curve, which was
     * the case until now.
     */
    suspend fun nightDetail(sessionHex: String): NightDetailUi? = withContext(Dispatchers.IO) {
        val session = db.nightDao().find(sessionHex) ?: return@withContext null
        val hash = db.paramDao().active()?.paramsHash ?: AnalysisParams.DEFAULT.paramsHash
        // The same read as the list — `displayNights`, with its fallback — and for the same
        // reason: asked for the Health Connect row alone, this returned `null` for every night
        // scored without a hypnogram, and tapping such a night opened a blank screen.
        val night = db.trendDao()
            .displayNights(hash, DEFAULT_RULE, DEFAULT_MASK, FALLBACK_MASK)
            .firstOrNull { it.sessionHex == sessionHex }
            ?: return@withContext null

        val events = db.derivedDao().eventsOf(sessionHex, hash)
        // The result row of the mask that is being displayed — `night.maskSource`, not
        // [DEFAULT_MASK]: on a night shown through its fallback row there is no Health Connect
        // result, and reading `DEFAULT_MASK` here would put the counts of a row that does not
        // exist (zeros) under the figures of the row that does.
        val result = db.derivedDao().resultsOf(sessionHex, hash)
            .firstOrNull { it.rule == DEFAULT_RULE && it.maskSource == night.maskSource }
        // Resolved **once** and passed to the three places that display it. It was resolved twice
        // by two different paths, one of which hard-coded the label: the detail of a night then
        // showed two source labels for the same night. From the row, not the preference — see
        // [toNightUi].
        val sleepSource = Mapping.sourceLabel(night.maskSource, night.sourcePackage)

        // The telemetry, for its part, is in the database from ingestion onwards: the device state
        // band therefore appears on nights whose envelope has not yet been read back from the raw
        // data.
        val telemetry = db.telemetryDao().ofSession(sessionHex)
        val metrology = if (telemetry.isEmpty()) null else {
            Metrology.spec(
                session = session,
                points = telemetry,
                startMs = axisStartMs(session),
                endMs = axisEndMs(session),
                // The origin of the sensor time base: the `tFirstNs` of the first chunk. It is on
                // that origin that the telemetry aligns, and it is the only one that timestamps
                // the movements.
                t0Ns = db.chunkDao().ofSession(sessionHex).minOfOrNull { it.tFirstNs },
                res = app.resources,
            )
        }

        NightDetailUi(
            night = toNightUi(night, session),
            inBed = Mapping.readableDuration(night.analysableMin),
            chart = null,
            hypnogram = null,
            metrology = metrology,
            movements = events.size,
            plms = result?.plmsCount ?: 0,
            plmw = result?.plmwCount ?: 0,
            // The rejections are counted **next to** the movements kept and not hidden: they are
            // what says why the figure is what it is, and a posture discrepancy that sets aside
            // fourteen movements changes how the night reads.
            postureExcluded = events.count { it.rejectReason == POSTURE_REJECTION },
            durationExcluded = events.count { it.rejectReason in DURATION_REJECTIONS },
            series = events.count { it.inSeriesAasm },
            imiMedianSec = medianIntervalSec(events.map { it.onsetMsRel }),
            checks = Checks.of(session, night, result, sleepSource),
            appliedRule = text(
                R.string.settings_rule_with_version,
                text(R.string.settings_rule_aasm),
                session.algoVersion.orEmpty(),
            ),
            // The miss rate and the respiratory bracketing were computed by `:algo` and persisted
            // in `plm_result` from the start, and displayed nowhere. The block computes nothing
            // new: it lays out the path that leads to the figure.
            why = ComputationPath.of(
                n = night,
                result = result,
                recordedDurationMin = recordedDurationMin(session),
                // `plmsCount` alone. The index at the top of the block is `plmi`, that is
                // `plmsCount / analysableTstMin`: the movements **during sleep**, over the
                // analysable sleep. This row used to add `plmwCount` — the movements during wake,
                // which enter `plmw` and never `plmi` — so the one block whose purpose is to let
                // the reader redo the division showed a numerator that does not give the figure
                // above it: 96 PLMS and 7 PLMW read "103 movements counted" over "5 h 12", which
                // is 19.8/h under a title saying "Why 18.4 /h". The two counts are shown
                // separately, as `plms` and `plmw`, on the same screen.
                movementsKept = result?.plmsCount ?: 0,
                rule = text(R.string.settings_rule_aasm),
                sleepSource = sleepSource,
                // What explains a detection decision: the clipping of the sensor, the jitter and
                // its consequence on timestamping, the write stalls. Three measured quantities
                // that decided the threshold and the instant without being visible anywhere.
                metrology = Metrology.summary(telemetry, session.nominalRateHz),
            ),
            situation = Situations.night(night, session),
        )
    }

    /**
     * The origin of the axis of the three bands, **already shifted to local wall-clock time**.
     *
     * The drawing functions know no time zone: they format a time by taking the remainder of a
     * division by one day. Passing them a UTC epoch would display Greenwich time on a night lived
     * in Tokyo. The shift is therefore applied here, once only, from the offset that ingestion
     * recorded with the night — and not from the time zone of the phone, which may have changed
     * since.
     */
    private fun axisStartMs(session: NightSessionEntity): Long =
        session.startWallMs + session.tzOffsetStartMin * 60_000L

    /**
     * The end of the axis. The **end** offset is used and not the start one: a daylight-saving
     * night carries both, and that is precisely why ingestion records them separately. A night that
     * is still open has no end; the planned duration is then taken, failing which the axis would be
     * of zero length and the band invisible.
     */
    private fun axisEndMs(session: NightSessionEntity): Long {
        val end = session.endWallMs ?: session.plannedStopWallMs
        return maxOf(end, session.startWallMs + 60_000L) + session.tzOffsetEndMin * 60_000L
    }

    /**
     * Duration of the session, from start to stop, in minutes. Zero as long as the night is open.
     *
     * It serves as **context** for the analysable sleep and never as a denominator: "5 h 12" says
     * nothing, "5 h 12 out of 7 h 41 recorded" says where the rest went.
     */
    private fun recordedDurationMin(session: NightSessionEntity): Double {
        val end = session.endWallMs ?: return 0.0
        return ((end - session.startWallMs) / 60_000.0).coerceAtLeast(0.0)
    }

    /**
     * Median of the intervals between the onsets of consecutive movements.
     *
     * It is a **median and not a mean**: the distribution of the intervals is log-normal and
     * carries harmonics — one missed movement merges two 21 s intervals into one of 42 s — and a
     * mean would follow those harmonics. The median absorbs them.
     */
    private fun medianIntervalSec(onsets: List<Long>): Double {
        if (onsets.size < 2) return 0.0
        val sorted = onsets.sorted()
        val gaps = DoubleArray(sorted.size - 1) { (sorted[it + 1] - sorted[it]) / 1000.0 }
        return Aggregate.median(gaps)
    }

    // -------------------------------------------------------------------------------------
    // The P1 gate
    // -------------------------------------------------------------------------------------

    /**
     * The report of the P1 gate over the last nights.
     *
     * It reads `night_session` **and nothing else**: the P1 gate bears on the acquisition, not on
     * the analysis. A night in which no movement has been detected yet nevertheless has everything
     * needed to say whether the sensor held — that is even the order of the project, since P1
     * precedes the first line of algorithm.
     *
     * The campaign verdict is computed over the **same** nights as those displayed. Computing it
     * over the whole database and showing only fourteen would give a conclusion that the visible
     * rows would not allow to be checked.
     */
    suspend fun p1Report(limit: Int = P1Gate.REPORT_NIGHTS): P1ReportUi =
        withContext(Dispatchers.IO) {
            // The telemetry is read back night by night: it is what makes the battery criterion
            // decidable. Fourteen nights are at most fourteen times five hundred rows, read once
            // when a screen opens that is already a snapshot.
            val verdicts = db.nightDao().all().take(limit).map {
                P1Gate.of(it, db.telemetryDao().ofSession(it.sessionHex))
            }
            P1ReportUi(nights = verdicts, campaign = P1Gate.campaign(verdicts))
        }

    // -------------------------------------------------------------------------------------
    // Guard rail 2: the result is hidden on waking, and revealing it leaves a trace
    // -------------------------------------------------------------------------------------

    /**
     * Marks a night as revealed. **Irreversible and timestamped**, by the query itself
     * (`WHERE revealedAtMs IS NULL`): a second call does not rewrite the date.
     *
     * The trace is not surveillance, it goes out in the export. Reading one's figure on waking, in
     * the state in which one is least able to judge it, is a legitimate choice; doing it without
     * that showing in the document handed to the doctor is not.
     */
    suspend fun reveal(sessionHex: String, nowMs: Long) {
        db.nightDao().markRevealed(sessionHex, nowMs)
    }

    companion object {
        /**
         * The rule set and the mask read by default.
         *
         * They are not adjustable from the interface yet — the Settings screen displays them, a
         * selector remains to be wired. The values correspond to `SeriesRule.AASM_V3` and to
         * `MaskSource.HEALTH_CONNECT` of `:algo`; the day the selector exists, this is where it
         * will be wired, and nowhere else.
         */
        const val DEFAULT_RULE = "AASM_V3"
        const val DEFAULT_MASK = "HEALTH_CONNECT"

        /**
         * The mask a night is shown through when it has no [DEFAULT_MASK] row —
         * `MaskSource.ACCEL_IMMOBILITY`, the only one `NightAnalyzer` writes for every scored
         * night. It reaches the screens only through `TrendDao.displayNights`, never the trend.
         */
        const val FALLBACK_MASK = "ACCEL_IMMOBILITY"

        /**
         * The rejection reasons of `ClmRejectReason`, on the `:algo` side, as they are persisted.
         *
         * They are counted and displayed **next to** the movements kept, not hidden: they are what
         * says why the figure is what it is. Fourteen movements excluded for posture change how a
         * night reads.
         */
        const val POSTURE_REJECTION = "POSTURAL"
        val DURATION_REJECTIONS = setOf("TOO_SHORT", "LM_LONG", "TRUNCATED")
    }
}

/**
 * A coherent read of the state of the trend. `rhythm` and `count` are `null` below three eligible
 * nights — and it is the type that carries the rule, not a reading convention.
 */
data class TrendState(
    val nights: List<NightUi>,
    val aggregatableNights: List<ComparableNight>,
    /**
     * How many aggregatable nights carry an accepted rhythm fit. It is the denominator of the
     * refusal: below [Aggregate.MIN_NIGHTS_AGGREGATE], the screen must say **which** of the two
     * counts is missing, failing which it announces "not enough nights" to someone who has nine.
     */
    val nightsWithFittedRhythm: Int,
    val rhythm: Aggregate.Result?,
    val count: Aggregate.Result?,
    val customProfile: String?,
    val mixedHashes: Boolean,
    val wakingFacts: WakingMachine.Facts? = null,
    val lastNightOrigins: Int = 0,
    /** Time zone in which the plotted nights were lived. See `TrendChartSpec.zoneId`. */
    val zoneId: String = java.time.ZoneId.systemDefault().id,
) {
    val eligibleNights: Int get() = aggregatableNights.size
    val recordedNights: Int get() = nights.size
    val excludedNights: Int get() = nights.count { it.state == com.pendulum.phone.ui.model.NightState.EXCLUDED }
}
