package com.pendulum.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pendulum.phone.data.WatchPairing
import com.pendulum.phone.data.PairingState
import com.pendulum.phone.data.WatchState
import com.pendulum.phone.data.TrendState
import com.pendulum.phone.data.EveningContextSealer
import com.pendulum.phone.data.EveningEntry
import com.pendulum.phone.data.PendulumPreferences
import com.pendulum.phone.data.PendulumRepository
import com.pendulum.phone.data.WatchCommands
import com.pendulum.phone.DataEraser
import com.pendulum.phone.export.NightExporter
import com.pendulum.phone.export.ReportExporter
import com.pendulum.phone.health.SleepReader
import com.pendulum.phone.health.SleepSources
import com.pendulum.phone.ui.onboarding.OnboardingResume
import com.pendulum.phone.ui.chart.MedianBand
import com.pendulum.phone.ui.chart.PointState
import com.pendulum.phone.ui.chart.ReferenceLine
import com.pendulum.phone.ui.chart.NightPoint
import com.pendulum.phone.ui.chart.TrendChartSpec
import com.pendulum.phone.ui.home.HomeUi
import com.pendulum.phone.ui.home.HomeMachine
import com.pendulum.phone.ui.model.Aggregate
import com.pendulum.phone.ui.model.Feedback
import com.pendulum.phone.ui.model.NightState
import com.pendulum.phone.ui.model.WakingMachine
import com.pendulum.phone.ui.model.Mapping
import com.pendulum.phone.ui.model.NightUi
import com.pendulum.phone.ui.model.Situations
import com.pendulum.phone.ui.nights.NightDetailUi
import com.pendulum.phone.ui.model.TrendUiState
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.QuestionnaireResponseEntity
import com.pendulum.phone.ui.export.ExportUi
import com.pendulum.phone.ui.quiz.QuizOutcome
import com.pendulum.phone.ui.settings.P1ReportUi
import com.pendulum.phone.ui.settings.SettingsUi
import com.pendulum.phone.R
import com.pendulum.phone.ui.text.FileNames
import com.pendulum.phone.ui.text.UiText
import com.pendulum.phone.ui.text.text
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The ViewModels. They are deliberately thin, and that is the point.
 *
 * Everything that decides something lives elsewhere and is tested without Android: the thresholds
 * and the estimators in `ui/model/Aggregate.kt`, the exclusion criteria in the SQL view
 * `comparable_night`, the formatting in `ui/model/Mapping.kt`. What is left here is the wiring —
 * which flow feeds which screen — plus the building of the chart `Spec`s, which has to happen
 * upstream of a `@Composable` so that the PDF export can reuse exactly the same objects.
 *
 * `AndroidViewModel` rather than a factory: the repository needs nothing but the application
 * context, and the project has no dependency injection at all. Introducing one to wire three
 * screens would pass off an architectural change as a defect fix.
 */

/**
 * The Trend screen.
 *
 * The switch between [TrendUiState.Refusal] and [TrendUiState.Ready] is not an `if` on a boolean:
 * it depends on the **nullity** of the aggregates, which `Mapping.aggregate` refuses to produce
 * below three nights. There is therefore no path by which an aggregated figure could appear
 * earlier, even by taking the wrong branch.
 */
class TrendViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)
    private val reader = SleepReader(app)

    /**
     * What Health Connect answers, re-read on demand.
     *
     * This is not a Room flow: availability and the list of sources are suspending calls to a
     * system provider, with no change notification. They are re-read when the screen opens and
     * when it comes back to the foreground — that is, at the two moments when a permission has
     * just been granted elsewhere.
     */
    private val _healthAvailability = MutableStateFlow<SleepReader.Availability?>(null)
    private val _recentSources = MutableStateFlow<Int?>(null)

    init {
        rereadHealth()
    }

    fun rereadHealth() {
        viewModelScope.launch {
            val availability = reader.availability()
            _healthAvailability.value = availability
            _recentSources.value = if (availability == SleepReader.Availability.READY) {
                reader.recentSources(System.currentTimeMillis())?.size
            } else {
                null
            }
        }
    }

    val state: StateFlow<TrendUiState> = combine(
        repo.observeTrend(),
        _healthAvailability,
        _recentSources,
    ) { trend, availability, sources ->
        trend.toUiState(availability, sources)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendUiState.Loading)

    private fun TrendState.toUiState(
        availability: SleepReader.Availability?,
        recentSources: Int?,
    ): TrendUiState {
        val r = rhythm
        val c = count
        val periodicity = Mapping.periodicityAggregate(aggregatableNights)

        // The waking status strip: a pure function, fed by the facts the repository has read. It
        // was wired onto `WakingState.None`, so the strip was never rendered and the five states
        // of `06-interface.md` §2.3 existed only in previews.
        val waking = WakingMachine.of(wakingFacts, System.currentTimeMillis()) { ms ->
            Mapping.readableTime(ms, wakingFacts?.zoneId ?: java.time.ZoneId.systemDefault().id)
        }
        val situation = Situations.sleep(availability, recentSources, lastNightOrigins)

        // No aggregate exists, so no chart is built. Not even an empty chart with its axes — an
        // empty axis invites the eye to imagine the curve that is missing, which is exactly the
        // opposite of what the refusal means.
        //
        // Two causes lead here and `Refusal` tells them apart by its two counts: not enough
        // eligible nights, or enough nights but too few accepted rhythm fits. The second is the
        // more frequent and it is not a breakdown — see `RefusalReason`.
        if (r == null || c == null) {
            return TrendUiState.Refusal(
                eligibleNights = eligibleNights,
                fittedRhythmNights = nightsWithFittedRhythm,
                requiredNights = Aggregate.MIN_NIGHTS_AGGREGATE,
                recordedNights = nights,
                waking = waking,
                sleepSituation = situation,
                wakingSession = wakingFacts?.sessionHex,
            )
        }

        return TrendUiState.Ready(
            rhythm = r,
            count = c,
            // The position sentence applies to the **hourly count** and to it alone: the
            // fundamental rhythm has no published threshold transposable to an ankle measurement.
            position = Aggregate.position(c.ciLow, c.ciHigh, c.nights),
            // Both quality medians go through `Mapping.aggregate`, like the rhythm and the count:
            // it is the only place the three-night minimum is applied and the only one that returns
            // the interval and the `n`. `TrendState.medianPeriodicity` / `medianMissRate` are
            // hand-rolled without either, and the qualifier was gated on `r.nights` — the accepted
            // rhythm fits — and not on the nights whose index is valid.
            qualifiedPeriodicity = Aggregate.qualifyPeriodicity(
                periodicity?.median,
                periodicity?.nights ?: 0,
            ),
            missRate = Mapping.missRateAggregate(aggregatableNights),
            chart = trendChart(r),
            recordedNights = recordedNights,
            eligibleNights = eligibleNights,
            excludedNights = excludedNights,
            rule = text(R.string.settings_rule_aasm),
            mask = text(R.string.settings_health_connect),
            // A dash, until the `comparable_night` view carries `plmw`. This line used to be the
            // arithmetic mean of `plmiSpt` — PLMS per hour of sleep period, a different quantity,
            // one that grows with *sleep* movements — shown under "Movements while awake": 7/h on a
            // night whose measured PLMW was 3/h. The view exposes `plmiSpt` and not `plmw`, so the
            // right figure cannot be read from here; the wrong one is not shown in its place.
            plmw = null,
            waking = waking,
            wakingSession = wakingFacts?.sessionHex,
            customProfile = customProfile,
            mixedHashes = mixedHashes,
            questionnaireState = text(R.string.quiz_not_filled),
            exportPossible = eligibleNights >= Aggregate.MIN_NIGHTS_AGGREGATE,
            sleepSituation = situation,
        )
    }

    /**
     * The action of the waking status strip, for the four states that carry one.
     *
     * The four labels say different things — "Transfer now", "Try again now", "Resume the
     * transfer", "Run the analysis again" — and they **all ask for the same thing**: that the night
     * go back into the chain of the end-of-night button. The sweep asks the watch to push whatever
     * it still holds; the chain reconciles the disk, re-reads Health Connect, then scores. A state
     * missing chunks receives them, a state waiting for the hypnogram asks for it again, a failed
     * analysis starts again from the raw data that was kept.
     *
     * The sweep's success is not waited for: it is the same reasoning as on the home screen — the
     * watch may already have pushed everything, and making the analysis conditional on it being
     * reachable would make a complete night unusable because the strap was left in the bathroom.
     */
    fun retryWaking(sessionHex: String) {
        viewModelScope.launch {
            WatchCommands.requestSweep(getApplication())
            WorkScheduler.enqueueEndOfNight(getApplication(), sessionHex)
        }
    }

    /**
     * The trend chart.
     *
     * **The X axis is calendar-based and not ordinal**: a night is placed at its real date, so a
     * week without a measurement leaves a visible gap. That is information, not a defect — and it
     * is half of the reason why the points are not joined up. Joining two points six days apart
     * would assert a continuous trajectory that the measurement does not support.
     *
     * Excluded nights are **plotted all the same**, as hollow circles, at their value, and left out
     * of every computation. Hiding them would give a cleaner picture and a false reading.
     */
    private fun TrendState.trendChart(r: Aggregate.Result): TrendChartSpec {
        // A night without an accepted fit has no point: it has no value at all. It is the same
        // rule as in the list and in the detail, carried by the nullity of `rhythmSec`.
        val points = nights
            .mapNotNull { n ->
                val value = n.rhythmSec ?: return@mapNotNull null
                NightPoint(
                    sessionHex = n.sessionHex,
                    dateMs = n.startWallMs,
                    value = value.toFloat(),
                    state = when (n.state) {
                        NightState.ELIGIBLE -> PointState.ELIGIBLE
                        NightState.PROVISIONAL -> PointState.ACCEL_MASKED
                        NightState.EXCLUDED -> PointState.EXCLUDED
                    },
                )
            }
            .sortedBy { it.dateMs }

        val first = points.firstOrNull()?.dateMs ?: 0L
        val last = points.lastOrNull()?.dateMs ?: first

        return TrendChartSpec(
            quantity = Aggregate.Quantity.RHYTHM_SECONDS,
            points = points,
            bands = listOf(
                MedianBand(
                    startMs = first,
                    endMs = last,
                    median = r.median.toFloat(),
                    ciLow = r.ciLow.toFloat(),
                    ciHigh = r.ciHigh.toFloat(),
                    label = null,
                ),
            ),
            // No reference line on the rhythm: the published threshold on periodicity is on
            // another scale with other evidence behind it, and transposing it would manufacture a
            // clinical boundary. The 15/h threshold belongs to the hourly count.
            reference = null as ReferenceLine?,
            firstDayMs = first,
            lastDayMs = last,
            // The X axis is calendar-based, so it needs a calendar: the ticks are local dates, not
            // multiples of 86 400 000 ms. Without a time zone, a night begun at 23:14 gets labelled
            // on the following day.
            zoneId = zoneId,
            pivotMs = null,
            // A summary, not a block label. A `contentDescription` such as "trend chart over 9
            // nights" teaches a screen reader that a chart exists and nothing of what it contains.
            // The value table remains the main path — no summary replaces data — but it must not
            // be the only way of knowing there is something there to read.
            accessibleDescription = descriptionOf(points, r),
        )
    }

    private fun TrendState.descriptionOf(
        points: List<NightPoint>,
        r: Aggregate.Result,
    ): String {
        val res = getApplication<Application>().resources
        if (points.isEmpty()) return res.getString(R.string.chart_trend_description_empty)
        val byHex = nights.associateBy { it.sessionHex }
        fun date(p: NightPoint) = byHex[p.sessionHex]?.readableDate.orEmpty()
        fun value(v: Float) = Math.round(v).toString()
        return res.getString(
            R.string.chart_trend_description,
            points.size,
            date(points.first()),
            date(points.last()),
            Math.round(r.median).toString(),
            value(points.minOf { it.value }),
            value(points.maxOf { it.value }),
            Aggregate.Quantity.RHYTHM_SECONDS.unit?.let { res.getString(it) }.orEmpty(),
        )
    }
}

/**
 * The home screen.
 *
 * ### The clock is a field, not a buried call
 *
 * It serves two things and nothing else: the night key that says which context is "tonight's", and
 * the local hour that **breaks the tie** for the state machine when the database leaves two equally
 * plausible readings. Both uses are explicit, and the decision itself lives in [HomeMachine], pure
 * and tested on its bounds.
 *
 * The local hour is re-read on every emission rather than frozen: a session that closes at 6 am
 * must change the screen, and an application left open all night must not go on offering to prepare
 * a night that has already happened.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)
    private val reader = SleepReader(app)

    private val _healthAvailability = MutableStateFlow<SleepReader.Availability?>(null)

    /**
     * Re-read every time the screen resumes, and not just once: the permission is granted in Health
     * Connect, hence **outside the application**, and the home screen is the one you come back to
     * on the way out.
     */
    fun rereadHealth() {
        viewModelScope.launch { _healthAvailability.value = reader.availability() }
    }

    init { rereadHealth() }

    val state: StateFlow<HomeUi?> = combine(
        repo.observeHome(clock()),
        _healthAvailability,
    ) { source, health ->
        HomeMachine.of(source, localHour())
            .copy(sleepSituation = Situations.sleepPermission(health))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The "end of night" button, in order, and the order matters.
     *
     * The sweep first: it asks the watch to push whatever it still holds, and it is a message — so
     * it fails openly when the watch is out of range, instead of being recorded in a replicated
     * state that would say nothing about whether it was read. The workers next, which reconcile
     * what has arrived, read the hypnogram, then score.
     *
     * The sweep's success is **not waited for** before queueing the chain: the watch may already
     * have pushed everything during the night, in which case there is nothing to fetch and
     * everything to analyse. Making the analysis conditional on the watch being reachable would
     * make a complete night unusable because the strap was left in the bathroom.
     */
    fun endOfNight(sessionHex: String) {
        viewModelScope.launch {
            WatchCommands.requestSweep(getApplication())
            WorkScheduler.enqueueEndOfNight(getApplication(), sessionHex)
        }
    }

    /**
     * Asks the watch to start.
     *
     * The outcome is **published**, not assumed. "The watch is recording" and "the watch received
     * nothing" are two states that a silent button makes identical, and whoever goes to bed
     * believing the first loses their night — they will only notice on waking, when there is
     * nothing left to salvage.
     *
     * The order is only sent if the context is sealed on the phone side. That is an interface
     * courtesy and not the guard rail: the guard rail is in `RecordingService`, which re-checks the
     * preflight before starting and refuses whatever the origin of the request.
     */
    fun startOnWatch() {
        viewModelScope.launch {
            _startFeedback.value = if (WatchCommands.requestStart(getApplication())) {
                Feedback(text(R.string.tonight_start_requested), failed = false)
            } else {
                Feedback(text(R.string.tonight_start_unreachable), failed = true)
            }
        }
    }

    private val _startFeedback = MutableStateFlow<Feedback?>(null)

    /**
     * The feedback from the last start request, or `null` when there is nothing to say.
     *
     * It was published and **no composable collected it**: the two outcomes of the command
     * therefore produced exactly the same screen. This is the application's only gesture whose
     * failure is not found out until the morning, when there is nothing left to salvage.
     *
     * The screen consumes it — see [startFeedbackConsumed] — because it is the result of a gesture
     * and not a state: a sentence that stayed under the button until the next day would end up
     * describing a request that has nothing to do with the current night any more.
     */
    val startFeedback: StateFlow<Feedback?> = _startFeedback

    fun startFeedbackConsumed() {
        _startFeedback.value = null
    }

    /**
     * Guard rail 2: the reveal, logged and timestamped.
     *
     * A single call, no confirmation to ask beforehand. `NightDao.markRevealed` carries
     * `WHERE revealedAtMs IS NULL`, so replaying the gesture does not rewrite the date — the trace
     * says when the figure was first seen, not when the screen was reopened.
     */
    fun reveal(sessionHex: String) {
        viewModelScope.launch { repo.reveal(sessionHex, clock()) }
    }

    private fun clock(): Long = System.currentTimeMillis()

    private fun localHour(): Int =
        java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.systemDefault()).hour
}

/**
 * The evening form and its sealing.
 *
 * The clock is a parameter and not a `System.currentTimeMillis()` called deep inside a function:
 * the night key rolls over at midday, so the whole attachment logic depends on what time it is, and
 * a hidden clock makes that rule untestable.
 */
class EveningViewModel(app: Application) : AndroidViewModel(app) {

    private val sealer = EveningContextSealer(app)
    private val prefs = PendulumPreferences(app)

    /** True as soon as the current evening's context is sealed — hence that the watch may start. */
    val contextSealed: StateFlow<Boolean> = sealer
        .observeCurrentEvening(clock())
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val strapReference: StateFlow<String> = prefs.strapReference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The result of the sealing, consumed once by the screen then reset to `null`.
     *
     * [SealingResult.PublicationFailed] is not an error in the usual sense: the context **is**
     * sealed, which is the essential part and the irreversible one. Only the watch does not know it
     * yet, and it is `ContextPublicationWorker` that will catch it up — not the Data Layer. The
     * latter only catches up what has **entered the store**; a put that failed never entered it.
     * The screen must say so all the same — announcing a complete success would send somebody
     * hunting for ten minutes for why START stays blocked, when the replay is not instantaneous.
     */
    private val _result = MutableStateFlow<SealingResult?>(null)
    val result: StateFlow<SealingResult?> = _result

    fun seal(entry: EveningEntry) {
        viewModelScope.launch {
            val now = clock()
            _result.value = try {
                if (sealer.seal(entry, now)) {
                    // The strap reference is kept for the following evenings: it has to be
                    // identical from one night to the next, so retyping it would be an opportunity
                    // to diverge rather than a check.
                    prefs.setStrapReference(entry.strap)
                    SealingResult.Sealed
                } else {
                    prefs.setStrapReference(entry.strap)
                    SealingResult.PublicationFailed
                }
            } catch (e: Exception) {
                // `OnConflictStrategy.ABORT`: sealing the same evening twice throws rather than
                // silently overwriting. That is the intended behaviour, and the screen must say
                // which of the two things happened.
                SealingResult.AlreadySealed
            }
        }
    }

    fun resultConsumed() {
        _result.value = null
    }

    private fun clock(): Long = System.currentTimeMillis()
}

enum class SealingResult { Sealed, PublicationFailed, AlreadySealed }

/**
 * A night's detail.
 *
 * It **finally reads the route argument**. The `night/{hex}` of the navigation graph was ignored:
 * whichever night was tapped, the screen displayed the same demonstration data set — 412 movements,
 * seven quality checks all green, an "algo 1.4.0" rule.
 */
class NightDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _detail = MutableStateFlow<NightDetailUi?>(null)
    val detail: StateFlow<NightDetailUi?> = _detail

    fun load(sessionHex: String) {
        viewModelScope.launch { _detail.value = repo.nightDetail(sessionHex) }
    }

    /**
     * Guard rail 2: the reveal, then the re-read.
     *
     * The re-read is not a precaution but the only way of showing the figure: the detail is a
     * snapshot loaded once, not a flow, and `revealedAtMs` is part of what it carries.
     * `markRevealed` never rewrites a date already set, so replaying the gesture is a no-op — the
     * trace says when the figure was first seen, not how many times the screen was reopened.
     */
    fun reveal(sessionHex: String) {
        viewModelScope.launch {
            repo.reveal(sessionHex, System.currentTimeMillis())
            _detail.value = repo.nightDetail(sessionHex)
        }
    }

    /**
     * Guard rail 3: a parameter is not set night by night.
     *
     * The button is called "Apply to every night" and there is no other: `RescoreAllWorker`
     * recomputes **every** night from the raw data, under the current hash. There is deliberately
     * no "only recompute the recent ones" variant — a three-point trend two of whose points were
     * computed differently is not a partial trend, it is a false chart.
     *
     * The screen does not refresh straight after, and that is correct: the rescore is background
     * work that can take a while, and displaying a new figure before it has been computed would
     * teach you to read figures before they are true. The night is re-read when you come back to it.
     */
    fun applyToAll() {
        WorkScheduler.enqueueRescoreAll(getApplication())
    }

    /**
     * A night's report, written into the `Uri` the user has just designated.
     *
     * The stream comes from SAF and from nowhere else: the application never writes into a shared
     * directory of its own initiative, and does not declare `INTERNET`. See the KDoc of
     * [com.pendulum.phone.export.NightExporter].
     */
    fun exportReport(sessionHex: String, uri: android.net.Uri, name: String) {
        viewModelScope.launch {
            write(uri, name) { ReportExporter.exportNight(getApplication(), sessionHex, it) }
        }
    }

    /** A night's raw bundle, same SAF path. See [exportReport]. */
    fun exportBundle(sessionHex: String, uri: android.net.Uri, name: String) {
        viewModelScope.launch {
            write(uri, name) { NightExporter.exportBundle(getApplication(), sessionHex, it) }
        }
    }

    /**
     * What the last of the two exports gave. Consumed by the screen, not erased.
     *
     * Both exports were perfectly mute: no success, no failure, no trace. The raw bundle is
     * particularly badly placed to be so — it is a night's only portable copy, and a user who
     * believes they have got it out before erasing their data loses the raw signal.
     */
    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback: StateFlow<Feedback?> = _feedback

    /**
     * The write, and the two ways in which it used to fail silently.
     *
     * The `runCatching` **threw** its exception, and the `?.` swallowed a null stream: a `Uri` that
     * the provider refuses to open gave exactly the same result as a successful write, that is,
     * nothing. The null stream is therefore turned into an exception, and `isSuccess` decides.
     */
    private suspend fun write(
        uri: android.net.Uri,
        name: String,
        block: suspend (java.io.OutputStream) -> Unit,
    ) {
        val written = withContext(Dispatchers.IO) {
            runCatching {
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: error("the provider opened no stream for $uri")
                stream.use { block(it) }
            }.isSuccess
        }
        _feedback.value = if (written) {
            Feedback(text(R.string.export_written, name), failed = false)
        } else {
            Feedback(text(R.string.export_failed), failed = true)
        }
    }
}

/** The night list. Nothing to decide: the SQL view has already annotated, [Mapping] has already
 *  translated. */
class NightsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    val nights: StateFlow<List<NightUi>> = repo.observeNights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * The first-launch onboarding.
 *
 * ### What it repairs
 *
 * `OnboardingPager` existed, complete, and **had no caller at all**. The consequence was not
 * cosmetic: the application's only `rememberLauncherForActivityResult` lived in that unreachable
 * screen, so no user path ever granted the Health Connect permissions. Every night was then scored
 * by the accelerometric mask alone — the numerator/denominator circularity that the whole project
 * exists to avoid — without any screen saying so.
 *
 * ### The pairing state is a flow, not a read
 *
 * `WatchPairing.observe` subscribes to `CapabilityClient`: step 3 ticks itself when the app appears
 * on the watch, while the user is still installing it. `WhileSubscribed` guarantees that the Data
 * Layer subscription stops as soon as the screen leaves — a forgotten Wearable listener outlives
 * the composable, not the ViewModel.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PendulumPreferences(app)
    private val reader = SleepReader(app)

    /** `null` until the first DataStore read has completed: nothing is composed until then. */
    val step: StateFlow<Int?> = prefs.onboardingStep
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val watch: StateFlow<WatchState> = WatchPairing.observe(app)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WatchState(PairingState.NO_WATCH),
        )

    val preferredSource: StateFlow<String?> = prefs.preferredSleepSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val strapReference: StateFlow<String> = prefs.strapReference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** `null` until Health Connect has been queried: the screen then displays nothing. */
    private val _health = MutableStateFlow<HealthState?>(null)
    val health: StateFlow<HealthState?> = _health

    /** Last result of an attempt to open the store on the watch, consumed once. */
    private val _installation = MutableStateFlow<Boolean?>(null)
    val installation: StateFlow<Boolean?> = _installation

    /**
     * The step is written on **leaving** the page, and never on entering it: a step begun and then
     * abandoned is not a step crossed.
     */
    fun crossStep(page: Int) {
        viewModelScope.launch {
            prefs.setOnboardingStep(OnboardingResume.stepAfter(page, step.value ?: 0))
        }
    }

    /**
     * Re-reads Health Connect: its availability, then the sources of the last seven days.
     *
     * Called when step 4 opens **and** on return from the permission request. Without the second
     * call, the screen would stay on `PERMISSIONS_MISSING` just after the user has granted them,
     * which reads as a refusal.
     */
    fun rereadHealth() {
        viewModelScope.launch {
            val availability = reader.availability()
            _health.value = HealthState(
                availability = availability,
                sources = if (availability == SleepReader.Availability.READY) {
                    reader.recentSources(System.currentTimeMillis())
                } else {
                    null
                },
            )
        }
    }

    /**
     * The choice of source, written into the preferences.
     *
     * It is the setting that `SleepFetchWorker` reads and that nobody wrote. It is not a
     * convenience: when two applications publish overlapping sessions, the denominator depends on
     * which one is read, and a denominator that changes from one night to the next manufactures a
     * trend that does not exist.
     */
    fun chooseSource(pkg: String) {
        viewModelScope.launch { prefs.setPreferredSleepSource(pkg) }
    }

    /** The strap reference, entered at step 5 and until now thrown away. */
    fun setStrapReference(reference: String) {
        viewModelScope.launch { prefs.setStrapReference(reference) }
    }

    fun installOnWatch() {
        viewModelScope.launch {
            _installation.value = WatchPairing.openStoreOnWatch(getApplication())
        }
    }

    fun installationConsumed() {
        _installation.value = null
    }
}

/**
 * What step 4 knows about Health Connect.
 *
 * @param sources `null` when the question makes no sense — Health Connect absent, too old, or
 *   permissions not granted. An empty list, on the other hand, is an answer: nothing writes sleep
 *   on this phone, and the screen must then help rather than stay silent.
 */
data class HealthState(
    val availability: SleepReader.Availability,
    val sources: List<SleepSources.Observed>?,
)

/**
 * The P1 gate report.
 *
 * A snapshot, loaded once: the report answers a question that does not move while it is being read
 * — how many nights in a row stayed within the three criteria. A flow would make the conclusion
 * recompose while the rows that justify it are being read.
 *
 * `null` until the read has completed, and the screen then displays nothing: the same rule as on
 * the home screen and in a night's detail. A report that opens on "0 nights out of 3" and then
 * fills in teaches you to read a verdict before it is true.
 */
class P1ReportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _report = MutableStateFlow<P1ReportUi?>(null)
    val report: StateFlow<P1ReportUi?> = _report

    init {
        viewModelScope.launch { _report.value = repo.p1Report() }
    }
}

/**
 * The settings.
 *
 * Several rows still show a dash rather than a value, and that is deliberately visible: the paired
 * watch and the Health Connect state arrive with the onboarding work, the space used with that of
 * the P1 gate. A dash says "not wired up yet"; a hard-coded "Pixel Watch 3" said "wired up", which
 * was false.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PendulumPreferences(app)

    /**
     * The space used, re-read on demand and not observed.
     *
     * This is not a flow: it is a sum of file sizes plus that of the database file, hence a disk
     * read. Re-reading it on every emission of a preference would mean one disk access per theme
     * tap. It is re-read when the screen opens and after an erasure — the only two moments at which
     * it changes in a way the user can observe.
     */
    private val _spaceUsed = MutableStateFlow(NOT_SET)

    /** Feedback from the last bundle re-import. Null as long as there has been none. */
    private val _import = MutableStateFlow<UiText?>(null)

    init {
        rereadSpace()
    }

    fun rereadSpace() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { DataEraser.bytesOnDisk(getApplication()) }
            _spaceUsed.value = Mapping.readableBytes(bytes)
        }
    }

    val settings: StateFlow<SettingsUi> = combine(
        prefs.preferredSleepSource,
        prefs.strapReference,
        prefs.theme,
        _spaceUsed,
        _import,
    ) { source, reference, theme, space, imported ->
        SettingsUi(
            rule = text(R.string.settings_rule_aasm),
            preferredSource = source?.let { text(Mapping.appName(it)) }
                ?: text(R.string.settings_source_unknown),
            profile = text(DEFAULT_PROFILE),
            wearingReference = text(reference.ifBlank { NOT_SET }),
            // The value used to say the word of the label — "Automatic stop: Automatic stop".
            // It now says **when** the watch stops, which is what `StopConditions` decides:
            // charging, waking, or maximum duration.
            autoStop = text(R.string.settings_auto_stop_value),
            watch = text(NOT_SET),
            healthConnect = text(NOT_SET),
            spaceUsed = text(space),
            appVersion = text(com.pendulum.phone.BuildConfig.VERSION_NAME),
            algoVersion = text(NOT_SET),
            theme = themeLabel(theme),
            lastImport = imported,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY_SETTINGS)

    /**
     * Reading a night bundle back in — the other half of [NightExporter], and the only one that
     * makes the outward trip verifiable.
     *
     * An export whose product nobody knows how to read back is not an export, it is a deferred
     * loss: `BundleRoundTripTest` proves that a database rebuilt from a bundle gives an identical
     * result, and that proof is only worth something if a user path exists that takes it. It is
     * also what makes it possible to carry a campaign from one phone to another without going
     * through a server, which the absence of the `INTERNET` permission forbids in any case.
     *
     * The failure is announced and is not an exception that propagates: a file picked at random in
     * the chooser is the ordinary case, not an incident.
     */
    fun importNight(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        NightExporter.importBundle(getApplication(), it)
                    }
                }.getOrNull()
            }
            _import.value = if (result == null) {
                text(R.string.settings_import_refused)
            } else {
                // The imported night is named by its date: "import successful" does not let you
                // check that the right file was taken. The date comes from the session written by
                // the import, not from `comparable_night` — that view has no row yet, the night
                // not having been analysed.
                val session = withContext(Dispatchers.IO) {
                    PendulumDatabase.get(getApplication()).nightDao().find(result)
                }
                // The bundle carries the raw data, never the results: that is a choice of
                // `NightExporter`, so that an exported figure is not compared with a figure
                // recomputed by a later version. An imported night must therefore be **analysed**,
                // failing which it enters the database and appears nowhere.
                WorkScheduler.enqueueNightChain(getApplication(), result)
                text(
                    R.string.settings_imported,
                    session?.let { Mapping.readableDate(it.startWallMs, it.zoneId) } ?: result,
                )
            }
            rereadSpace()
        }
    }

    /**
     * Advances the theme by one: system, then light, then dark, then round again.
     *
     * The row that shows the theme was an inert value for as long as this setter had no caller —
     * it displayed a stored choice it could not change, so the only reachable theme was the
     * default. The order starts at `System` because that is the one a reader looking for a light
     * screen wants first, and dark stays the default for the reason `PendulumTheme` gives: this
     * application is read at 7 a.m. or in the middle of the night, and a light screen dazzles.
     */
    fun cycleTheme() {
        viewModelScope.launch {
            val current = prefs.theme.first()
            prefs.setTheme(
                when (current) {
                    PendulumPreferences.THEME_DARK -> PendulumPreferences.THEME_SYSTEM
                    PendulumPreferences.THEME_SYSTEM -> PendulumPreferences.THEME_LIGHT
                    else -> PendulumPreferences.THEME_DARK
                }
            )
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    private companion object {
        const val DEFAULT_PROFILE = "default"
        const val NOT_SET = "—"

        val EMPTY_SETTINGS = SettingsUi(
            rule = text(R.string.settings_rule_aasm),
            preferredSource = text(NOT_SET),
            profile = text(DEFAULT_PROFILE),
            wearingReference = text(NOT_SET),
            autoStop = text(NOT_SET),
            watch = text(NOT_SET),
            healthConnect = text(NOT_SET),
            spaceUsed = text(NOT_SET),
            appVersion = text(NOT_SET),
            algoVersion = text(NOT_SET),
            theme = text(R.string.settings_theme_dark),
        )

        /**
         * The persisted token, rendered in the language of the interface.
         *
         * `PendulumPreferences` writes `SOMBRE`, `CLAIR`, `SYSTEME` — storage tokens, in French
         * because that is the project's working language. The screen displayed that token as it
         * was: "Theme  SOMBRE" in the middle of an English interface, while the three English
         * labels existed in the text file and had no caller at all. A storage token is not
         * interface text, and it does not become one just because it can be read.
         */
        fun themeLabel(token: String): UiText = when (token) {
            PendulumPreferences.THEME_SYSTEM -> text(R.string.settings_theme_system)
            PendulumPreferences.THEME_LIGHT -> text(R.string.settings_theme_light)
            else -> text(R.string.settings_theme_dark)
        }
    }
}

/**
 * The export screen, and the document that is the project's reason to exist.
 *
 * ### What it repairs
 *
 * `ReportExporter` was written, complete, and **had no caller at all**: the export screen's five
 * lambdas were empty in `MainActivity`. The only purpose `README.md` considers defensible — "to
 * produce a document to put in front of a doctor" — was reachable by no gesture.
 *
 * ### The guard rail holds in the same place as on the screen
 *
 * The button stays visible and disabled below [Aggregate.MIN_NIGHTS_AGGREGATE] eligible nights,
 * with its reason written on it: it is `ExportScreen` that decides, from the single field
 * `eligibleNights`, and not this ViewModel. There are therefore not two places where the rule can
 * diverge, and no path where the button would be enabled and the write would fail.
 */
class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PendulumRepository(app)

    private val _questionnaire = MutableStateFlow(true)

    /** **Yes** by default: hiding failed nights from a doctor is misleading. */
    private val _excluded = MutableStateFlow(true)

    /**
     * What the last write gave. Displayed by the screen, not erased.
     *
     * It carried the file name alone, and it was set **after** a `runCatching` whose exception was
     * thrown: "Written: pendulum-report-2026-03-15.md" could therefore be written when nothing had
     * been. On the document meant for the doctor, that is the product's most expensive lie — you
     * take it to the consultation without reopening it.
     */
    private val _feedback = MutableStateFlow<Feedback?>(null)

    val state: StateFlow<ExportUi?> = combine(
        repo.observeTrend(),
        _questionnaire,
        _excluded,
        _feedback,
    ) { trend, questionnaire, excluded, feedback ->
        val nights = trend.nights.sortedBy { it.startWallMs }
        ExportUi(
            includeQuestionnaire = questionnaire,
            includeExcluded = excluded,
            eligibleNights = trend.eligibleNights,
            period = when {
                nights.isEmpty() -> "—"
                nights.size == 1 -> nights.first().readableDate
                else -> "${nights.first().readableDate} – ${nights.last().readableDate}"
            },
            customProfile = trend.customProfile,
            writeFeedback = feedback,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuestionnaire(included: Boolean) {
        _questionnaire.value = included
    }

    fun setExcluded(included: Boolean) {
        _excluded.value = included
    }

    /** The name proposed in the SAF picker. The day of generation, not that of a night. */
    fun fileName(): String =
        FileNames.campaignReport(
            Mapping.isoDay(System.currentTimeMillis(), java.time.ZoneId.systemDefault().id),
        )

    /**
     * The write, into the `Uri` the user has just designated, and nowhere else.
     *
     * The trend state is re-read at the moment of writing rather than captured at display time:
     * between the screen opening and the location being chosen, a rescore may have finished, and a
     * document carrying the earlier figures without saying so would be a false document.
     *
     * ### The two ways of failing silently, and what replaces them
     *
     * The `runCatching` **threw** its exception, and the `?.` swallowed a null stream — a SAF
     * provider refusing to open the `Uri` therefore gave exactly the same result as a successful
     * write. The file name was set afterwards, unconditionally. A report meant for a doctor could
     * thus go unwritten without anything saying so.
     *
     * Both cases are now a failure: the null stream is turned into an exception, and the
     * `runCatching`'s `isSuccess` decides what the screen displays.
     */
    fun save(uri: android.net.Uri, name: String) {
        viewModelScope.launch {
            val trend = repo.observeTrend().first()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("the provider opened no stream for $uri")
                    stream.use {
                        ReportExporter.exportCampaign(
                            context = getApplication(),
                            state = trend,
                            includeQuestionnaire = _questionnaire.value,
                            includeExcluded = _excluded.value,
                            out = it,
                        )
                    }
                }.isSuccess
            }
            _feedback.value = if (written) {
                Feedback(text(R.string.export_written, name), failed = false)
            } else {
                Feedback(text(R.string.export_failed), failed = true)
            }
        }
    }
}

/**
 * The screening questionnaire.
 *
 * ### A single question, and an answer that is kept
 *
 * `questionnaire_response` is append-only by use: a sitting is added, it is not corrected. "Review
 * my answers" therefore changes nothing — it asks the question again, and the next answer is added
 * with its date. That is what makes it possible to say when an answer was given, and the report for
 * the doctor lists them all.
 *
 * ### Why the answer "no" does not erase the measurement
 *
 * The outcome is a sentence, never a score, and it conditions no other screen: the questionnaire is
 * about what is felt while awake, the watch measures what happens during sleep. Making one depend on
 * the other would amount to letting a screening close a measurement.
 */
class QuizViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = PendulumDatabase.get(app).questionnaireDao()

    private val _outcome = MutableStateFlow<QuizOutcome?>(null)
    val outcome: StateFlow<QuizOutcome?> = _outcome

    init {
        viewModelScope.launch {
            _outcome.value = withContext(Dispatchers.IO) {
                dao.all().firstOrNull()?.let { outcomeOf(it.answersJson) }
            }
        }
    }

    fun answer(urgeToMove: Boolean) {
        viewModelScope.launch {
            val json = """{"urge_to_move":$urgeToMove}"""
            withContext(Dispatchers.IO) {
                dao.append(
                    QuestionnaireResponseEntity(
                        kind = KIND,
                        answeredAtMs = System.currentTimeMillis(),
                        answersJson = json,
                    )
                )
            }
            _outcome.value = outcomeOf(json)
        }
    }

    /** Ask the question again. The previous sitting stays in the database with its date. */
    fun review() {
        _outcome.value = null
    }

    private fun outcomeOf(json: String): QuizOutcome = when {
        json.contains("\"urge_to_move\":true") -> QuizOutcome.CONSISTENT
        json.contains("\"urge_to_move\":false") -> QuizOutcome.NOT_CONSISTENT
        else -> QuizOutcome.INCOMPLETE
    }

    private companion object {
        /** The name of the sitting. A single question; the detailed questionnaire will sit beside it. */
        const val KIND = "screening-single"
    }
}

/**
 * Total erasure.
 *
 * `DataEraser` was written — work cancelled, files before database, `VACUUM` — and **had no caller
 * at all**: the settings' "Erase all data" row called a `{}`. A health application whose erase
 * button does nothing promises exactly what it does not deliver.
 *
 * The space used is re-read before and after, and displayed: it is the only verifiable confirmation
 * that the eight hours of accelerometry per night really have left `filesDir`, where an empty
 * database and an empty screen prove nothing.
 */
class ErasureViewModel(app: Application) : AndroidViewModel(app) {

    private val _spaceUsed = MutableStateFlow("—")
    val spaceUsed: StateFlow<String> = _spaceUsed

    private val _erased = MutableStateFlow(false)
    val erased: StateFlow<Boolean> = _erased

    init {
        reread()
    }

    fun erase() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { DataEraser.eraseEverything(getApplication()) }
            // `DataEraser` cancels **all** the work, and that is intended: a `SleepFetchWorker`
            // already queued would recreate a row a few minutes after the erasure. The watchdog,
            // on the other hand, recreates nothing — it observes that no session is open — and
            // without it the application stays unsupervised until the next start.
            WorkScheduler.ensureWatchdog(getApplication())
            _erased.value = true
            reread()
        }
    }

    private fun reread() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { DataEraser.bytesOnDisk(getApplication()) }
            _spaceUsed.value = Mapping.readableBytes(bytes)
        }
    }
}
