package com.pendulum.wear.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.pendulum.format.ChunkFormat
import com.pendulum.format.TelemetryPoint
import com.pendulum.format.wire.LivePreview
import com.pendulum.format.wire.SessionHeader
import com.pendulum.format.wire.SessionState
import com.pendulum.format.wire.StopReason
import com.pendulum.wear.R
import com.pendulum.wear.Watchdog
import com.pendulum.wear.time.Durations
import com.pendulum.wear.transfer.DataLayerTransfer
import com.pendulum.wear.transfer.SyncWorker
import com.pendulum.wear.ui.MainActivity
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Foreground service of type `health`: the capture, and everything that must survive eight hours
 * with the screen off.
 *
 * **Why `health` and not `dataSync`.** On Android 15 the six-hours-per-twenty-four limit applies
 * to `dataSync` and `mediaProcessing`: an eight-hour night crosses it at the sixth hour,
 * `onTimeout()` is called, and a missing `stopSelf()` produces a fatal `RemoteServiceException`.
 * `health` has **no documented limit**. And `dataSync` cannot be started from `BOOT_COMPLETED` by
 * an application targeting Android 15 or later, whereas `health` is not on that list — that is
 * what makes the resume after reboot legal. The service is qualified by
 * `HIGH_SAMPLING_RATE_SENSORS`, a normal permission, and not by `BODY_SENSORS`, which is
 * while-in-use and would break precisely that resume.
 *
 * The service never gives up on its own: `START_STICKY`, and `onTaskRemoved` does nothing.
 * Swiping the task away must not stop a night.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.pendulum.wear.action.START"
        const val ACTION_RESUME = "com.pendulum.wear.action.RESUME"
        const val ACTION_STOP = "com.pendulum.wear.action.STOP"

        private const val TAG = "PendulumRecord"
        private const val CHANNEL_ID = "pendulum_recording"
        private const val NOTIF_ID = 1

        const val RATE_HZ = 50

        /** One tick every ten seconds of **awake** time. See [tick]. */
        private val TICK_MS = Durations.ACTIVE.serviceTickMs

        /** Seen from the watchdog: a `bindService` to answer a binary question would cost more
         *  in complexity than the `@Volatile` it replaces. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var recordThread: HandlerThread
    private lateinit var handler: Handler
    private val syncExecutor = Executors.newSingleThreadExecutor()

    private lateinit var sessionStore: SessionStore
    private var marker: SessionMarker? = null
    private var store: ChunkStore? = null
    private var pipeline: SensorPipeline? = null
    private var gaps: GapMonitor? = null
    private var envelope: PreviewEnvelope? = null
    private var wakeDetector = WakeDetector()
    private var stopConditions: StopConditions? = null
    private var mode: AcquisitionMode? = null
    private var source: SensorSource? = null
    private var offBodySensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var startElapsedMs = 0L
    private var ticks = 0
    private var closedChunks = 0
    private var lastClosedIdx = -1

    /** Written by the transfer thread, read by the recording thread. */
    @Volatile
    private var backlogged = false

    /** Written by the off-body sensor, read by the pipeline. */
    @Volatile
    private var offBody = false
    private var offBodySeconds = 0L
    private val batterySeries = ArrayList<Int>(600)
    private var stopping = false

    // --- lifecycle ---

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        createChannel()
        recordThread = HandlerThread("pendulum-rec", android.os.Process.THREAD_PRIORITY_FOREGROUND)
        recordThread.start()
        handler = Handler(recordThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First useful instruction, before any I/O: five seconds of delay and the system throws
        // ForegroundServiceDidNotStartInTimeException.
        if (!promoteToForeground()) return START_NOT_STICKY

        // A restart through START_STICKY delivers a null intent: this is a resume, not a start,
        // and above all not a new session.
        val action = intent?.action ?: ACTION_RESUME
        handler.post {
            when (action) {
                ACTION_START -> startSession(resume = false, intent = intent)
                ACTION_RESUME -> startSession(resume = true, intent = intent)
                ACTION_STOP -> finalizeSession(StopReason.USER)
            }
        }
        return START_STICKY
    }

    /** Deliberately does nothing: the service survives the task being swiped away. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        recordThread.quitSafely()
        syncExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * `health` is not subject to the foreground service timeout. It is implemented anyway: if a
     * future version were to subject it to one, the default behaviour would be a crash six hours
     * into the night, every night. Cost if useless: zero. Cost if needed and absent: half of
     * every night.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) = onTimeoutCommon()

    @RequiresApi(34)
    override fun onTimeout(startId: Int) = onTimeoutCommon()

    private fun onTimeoutCommon() {
        Log.w(TAG, "onTimeout: clean close then restart of a new session")
        handler.post {
            finalizeSession(StopReason.MAX_DURATION)
            // A fresh session, with a new identifier, thirty seconds later. The restart goes
            // through WorkManager rather than an exact alarm: that avoids declaring
            // SCHEDULE_EXACT_ALARM for a path which, in principle, never runs.
            Watchdog.restartAfterTimeout(this)
        }
    }

    // --- start ---

    /**
     * @param intent the one from `onStartCommand`, null after a restart through `START_STICKY`.
     *   It is only read by [SourceFactory], which only does anything with it in the debug variant.
     */
    private fun startSession(resume: Boolean, intent: Intent?) {
        if (isRunning) return

        val existing = sessionStore.readMarker()
        if (resume && existing == null) {
            Log.i(TAG, "resume requested with no marker: nothing to resume")
            stopSelfClean()
            return
        }
        // The same guard rails as BootReceiver, because this path is also that of a restart
        // through START_STICKY: never restart a night whose time has passed.
        if (resume && existing != null) {
            if (existing.isStale(System.currentTimeMillis())) {
                Log.i(TAG, "resume refused: the night is over, finalising")
                SyncWorker.enqueue(this, existing.sessionHex)
                stopSelfClean()
                return
            }
        }
        if (!resume) {
            val pre = Preflight.check(this)
            if (!pre.canStart) {
                Log.w(TAG, "preflight blocking: ${pre.blockers.map { it.id }}")
                RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
                stopSelfClean()
                return
            }
        }

        val sm = getSystemService(SensorManager::class.java)
        val src = SourceFactory.create(this, intent)
        val acc = src.describe()
        if (acc == null) {
            Log.e(TAG, "no accelerometer")
            stopSelfClean()
            return
        }
        source = src

        // The scale guard rail, placed here and not earlier: it is `SourceFactory.create` that
        // transcribes the intent extras into the preference, so the source is only known from
        // this line onwards. The refusal is recorded the way the foreground service refusal is —
        // a preference that the next preflight turns into a readable blocker — because a
        // recording that stops by itself after fourteen seconds tells nobody anything.
        if (Preflight.scaleMismatch(
                divisor = com.pendulum.wear.time.TimeScaling.DIVISOR,
                syntheticSource = SourceFactory.syntheticSourceEnabled(this),
            )
        ) {
            Log.e(
                TAG,
                "start refused: bench build at scale " +
                    "${com.pendulum.wear.time.TimeScaling.DIVISOR} on the real sensor. " +
                    "The divisor only compresses wall-clock time; the FIFO burst latency does " +
                    "not compress, and the cut-off time would cut the night before the first " +
                    "sample. Rebuild without -Ppendulum.temps.diviseur, or start with " +
                    "--ez pendulum.synth true.",
            )
            prefs().edit().putBoolean(Preflight.PREF_SCALE_MISMATCH, true).commit()
            RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
            src.stop()
            source = null
            stopSelfClean()
            return
        }
        prefs().edit().putBoolean(Preflight.PREF_SCALE_MISMATCH, false).apply()

        // The strategy is decided here, at run time, on `fifoReservedEventCount`: the share
        // *guaranteed* to this application. `fifoMaxEventCount` is shared between every client of
        // the sensor, and budgeting on it amounts to betting that nobody else is listening.
        val m = if (resume && existing != null) {
            SensorStrategy.decide(acc.wakeUp, acc.fifoReserved, existing.nominalRateHz)
        } else {
            SensorStrategy.decide(acc.wakeUp, acc.fifoReserved, RATE_HZ)
        }
        mode = m
        Log.i(
            TAG,
            "sensor=${acc.name} wakeUp=${acc.wakeUp} " +
                "reserved=${acc.fifoReserved} max=${acc.fifoMax} mode=${m.label}",
        )

        val now = System.currentTimeMillis()
        val stopAtMinutes = stopAtLocalMinutes()
        val current = if (resume && existing != null) {
            existing.copy(modeFlags = m.modeFlags, nominalRateHz = m.rateHz)
        } else {
            SessionMarker(
                sessionHex = SessionStore.newSessionHex(),
                startWallMs = now,
                plannedStopWallMs = plannedStop(now, stopAtMinutes),
                lastChunkIndex = -1,
                modeFlags = m.modeFlags,
                nominalRateHz = m.rateHz,
                zoneId = SessionStore.currentZoneId(),
                stopAtLocalMinutes = stopAtMinutes,
            )
        }
        marker = current
        sessionStore.begin(current)

        val cs = ChunkStore(
            sessionDir = sessionStore.sessionDir(current.sessionHex),
            sessionUuid = SessionStore.uuidBytes(current.sessionHex),
            sensorResolution = acc.resolution,
            sensorMaxRange = acc.maxRange,
            fifoReserved = acc.fifoReserved,
            startIndex = current.lastChunkIndex + 1,
            rateHz = m.rateHz,
            modeFlags = m.modeFlags,
        )
        val gm = GapMonitor(m.rateHz)
        val env = PreviewEnvelope(m.rateHz) { rms, tsNs -> wakeDetector.onSecond(rms, tsNs) }
        val pl = SensorPipeline(
            store = cs,
            gaps = gm,
            envelope = env,
            rateHz = m.rateHz,
            onChunkClosed = ::onChunkClosed,
            offBody = { offBody },
        )
        store = cs
        gaps = gm
        envelope = env
        pipeline = pl
        stopConditions = StopConditions(current.startWallMs, stopAtMinutes)
        wakeDetector = WakeDetector()
        batterySeries.clear()
        closedChunks = 0
        lastClosedIdx = current.lastChunkIndex
        ticks = 0
        startElapsedMs = SystemClock.elapsedRealtime()
        stopping = false

        if (resume) {
            // The first block after a resume carries the trace of the interruption: without it,
            // the gap would be invisible on replay and the analysis would date events wrongly.
            pl.markNextBlock(ChunkFormat.FLAG_GAP_BEFORE)
        }

        publishSessionOpen(current, m)

        src.start(m, handler, sink)
        offBodySensor = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)?.also {
            // Logged, never acted on: at the ankle, off-body very probably reads "not worn"
            // permanently, and trusting it would cut every night in its first minute.
            sm.registerListener(offBodyListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        if (m.needsWakeLock) acquireWakeLock()

        isRunning = true
        Watchdog.start(this)
        handler.postDelayed(::tick, TICK_MS)
        publishUiState()
    }

    private fun stopSelfClean() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- loop ---

    /** Samples, wherever they come from, go here and nowhere else. */
    private val sink = SampleSink { x, y, z, tsNs, arrivalNs, nowMs ->
        pipeline?.onEvent(x, y, z, tsNs, arrivalNs, nowMs)
    }

    /** Off-body stays wired straight to `SensorManager`: it does not go through [SensorPipeline],
     *  it is logged and never acted on, so there is nothing about it to simulate. */
    private val offBodyListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 0.0 = not worn. We merely remember it.
            if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                offBody = event.values[0] == 0f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Single tick, every ten seconds of uptime. A `Handler` does not advance while the SoC is
     * suspended: this tick therefore causes **no wake-up**, it runs in the wake of the wake-ups
     * that draining the FIFO causes anyway. That is also why there is only one timer and not
     * three — the 30 s and 60 s periods are counted multiples of it.
     */
    private fun tick() {
        if (!isRunning || stopping) return
        ticks++
        try {
            store?.sync()
        } catch (e: Exception) {
            Log.e(TAG, "fsync failed", e)
        }

        gaps?.consumePendingStep()?.let(::applyDegradation)

        if (ticks % 3 == 0) publishUiState()
        if (ticks % 6 == 0) minuteTick()

        handler.postDelayed(::tick, TICK_MS)
    }

    private fun minuteTick() {
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        if (pct >= 0) batterySeries += pct
        if (offBody) offBodySeconds += 60
        writeTelemetry(bm, pct, charging)

        val reason = stopConditions?.evaluate(
            nowMs = System.currentTimeMillis(),
            isCharging = charging,
            batteryPct = pct,
            freeBytes = filesDir.usableSpace,
            localMinutes = localMinutes(),
            wakeRatio = wakeDetector.ratio,
        )
        if (reason != null) {
            Log.i(TAG, "automatic stop: $reason")
            finalizeSession(reason)
            return
        }
        // The notification is only rewritten once a minute: it is the only way to check at a
        // glance that the night is running, but every rewrite is work.
        updateNotification()
    }

    /**
     * The telemetry point for the minute, written **into the current chunk**.
     *
     * ### Why here, and not on a clock of its own
     *
     * The standby measurement of 3 August 2026 (`docs/workings/BENCH-LOG.md` §12.4) gives ten log
     * lines, zero gaps, zero escalation, no wake lock and the same PID over thirty-two minutes of
     * deep Doze. That is the result not to spoil. Telemetry therefore adds **no periodic
     * `Handler`, no alarm and no wake lock**: it is carried by [minuteTick], that is, by the
     * service's only tick, whose KDoc explains that it lives on the uptime clock — which does not
     * advance while the SoC is suspended. The point therefore falls in the wake of a wake-up that
     * draining the FIFO causes anyway, and never in place of a stretch of sleep.
     *
     * The choice of the minute rather than another period follows the same logic: it is the
     * branch that **already reads the battery**, and its period is also that of [GapMonitor]'s
     * measurement window — each point therefore carries a freshly closed window rather than a
     * half-filled one. And 60 s divides the 300 s of the chunk rotation, which guarantees that a
     * complete chunk carries five points: without that division, a lost chunk would take with it
     * a telemetry gap that no other chunk would fill. See
     * [WireProtocol.TELEMETRY_PERIOD_MS][com.pendulum.format.wire.WireProtocol.TELEMETRY_PERIOD_MS].
     *
     * The counters are only consumed if the point can actually go out: otherwise we would lose
     * them while they describe time that has already passed.
     */
    private fun writeTelemetry(bm: BatteryManager?, pct: Int, charging: Boolean) {
        val cs = store ?: return
        if (!cs.chunkOpen) return
        val gm = gaps
        val writes = cs.consumeFlashWrites()
        val clipped = cs.consumeClippedSamples()
        cs.writeTelemetry(
            TelemetryPoint(
                elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                sensorTsNs = gm?.lastTimestampNs ?: 0L,
                // Already returns Integer.MIN_VALUE when the device cannot count coulombs, which
                // is exactly the format's sentinel.
                batteryChargeUah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    ?: TelemetryPoint.CHARGE_UNKNOWN,
                maxIntervalUs = TelemetryPoint.clampU32(gm?.maxIntervalUs ?: 0L),
                fsyncTotalUs = TelemetryPoint.clampU32(writes.totalUs),
                fsyncMaxUs = TelemetryPoint.clampU32(writes.maxUs),
                temperatureDeciC = temperatureDeciC(),
                measuredRateCentiHz = TelemetryPoint.clampU16(
                    Math.round((gm?.measuredRateHz ?: 0.0) * 100),
                ),
                jitterStdUs = TelemetryPoint.clampU16(Math.round(gm?.jitterStdUs ?: 0.0)),
                clippedSamples = TelemetryPoint.clampU16(clipped),
                fsyncCount = TelemetryPoint.clampU16(writes.count.toLong()),
                batteryPct = if (pct in 0..100) pct else TelemetryPoint.BATTERY_UNKNOWN,
                offBody = when {
                    offBodySensor == null -> TelemetryPoint.OFF_BODY_ABSENT
                    offBody -> TelemetryPoint.OFF_BODY_REMOVED
                    else -> TelemetryPoint.OFF_BODY_WORN
                },
                charging = charging,
            ),
        )
    }

    /**
     * Battery temperature, in tenths of a degree Celsius.
     *
     * **A synchronous read of an already published state, not a receiver that would live on.**
     * `registerReceiver(null, ...)` on a sticky broadcast returns the current intent and registers
     * nothing: no wake-up, no alarm, nothing running between two calls. It is the same order of
     * cost as the battery `getIntProperty` done right next to it.
     *
     * It is also the only source available to an ordinary application: `BatteryManager` exposes
     * no temperature property, `TYPE_AMBIENT_TEMPERATURE` is absent from very nearly every watch,
     * and `HardwarePropertiesManager` is reserved for the system. What is measured is therefore
     * the **battery** temperature, which follows that of the case with a few minutes of delay —
     * fine enough to see a watch leave a wrist, too coarse for anything else, and that is exactly
     * the use made of it.
     */
    private fun temperatureDeciC(): Int = try {
        val state = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val deci = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (deci == Int.MIN_VALUE) TelemetryPoint.TEMPERATURE_UNKNOWN else deci.coerceIn(-32768, 32767)
    } catch (e: Exception) {
        // A missing temperature has never been worth losing the night that goes with it.
        Log.w(TAG, "temperature unreadable", e)
        TelemetryPoint.TEMPERATURE_UNKNOWN
    }

    /**
     * Self-degradation. **Monotonic**: a step never comes back down within the same session,
     * otherwise the system oscillates between two modes all night. Every step forces a chunk
     * rotation, because `modeFlags` and `nominalRateHz` live in the file header and the format is
     * append-only: they will never be rewritten.
     */
    private fun applyDegradation(step: Int) {
        val old = mode ?: return
        val src = source ?: return
        val next = old.degradedTo(step)
        Log.w(TAG, "degradation step $step: ${old.label} -> ${next.label}")
        mode = next

        src.stop()
        pipeline?.flushBlock(SystemClock.elapsedRealtime())
        store?.rotate(next.rateHz, next.modeFlags)?.let(::onChunkClosed)
        if (next.rateHz != old.rateHz) pipeline?.onRateChanged(next.rateHz, SystemClock.elapsedRealtime())
        if (next.needsWakeLock) acquireWakeLock()
        src.start(next, handler, sink)

        marker = marker?.copy(modeFlags = next.modeFlags, nominalRateHz = next.rateHz)
        sessionStore.updateMode(next.modeFlags, next.rateHz)
    }

    private fun onChunkClosed(idx: Int) {
        closedChunks++
        lastClosedIdx = idx
        sessionStore.updateLastChunkIndex(idx)
        marker = marker?.copy(lastChunkIndex = idx)
        writeSidecar(null, null)
        // One burst every three closed chunks, that is, a quarter of an hour. Going to one burst
        // per chunk would only multiply the cost by 1.7 — the fixed wake-up dominates the
        // transmission time — but as long as the battery budget is not measured, we keep the
        // cautious version.
        if (closedChunks % DataLayerTransfer.PUSH_EVERY_N_CHUNKS == 0) push(force = false)
    }

    /** The push lives on its own thread: it blocks for several seconds and has no reason to
     *  delay writing the next block. */
    private fun push(force: Boolean) {
        val m = marker ?: return
        val env = envelope?.snapshot() ?: return
        val samples = store?.totalSamples ?: 0
        val bytes = store?.totalBytes ?: 0
        val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
        val battery = batterySeries.lastOrNull() ?: 0
        val gapCount = gaps?.gapCount ?: 0
        val gapMs = gaps?.gapTotalMs ?: 0
        val idx = lastClosedIdx
        val flags = m.modeFlags
        syncExecutor.execute {
            try {
                val dir = sessionStore.sessionDir(m.sessionHex)
                backlogged = DataLayerTransfer.pushChunks(this, m.sessionHex, dir, urgentLast = true)
                DataLayerTransfer.putLive(
                    this,
                    LivePreview(
                        sessionHex = m.sessionHex,
                        lastUpdateMs = System.currentTimeMillis(),
                        elapsedMs = elapsed,
                        samplesWritten = samples,
                        bytesWritten = bytes,
                        batteryPct = battery.coerceIn(0, 100),
                        gapCount = gapCount,
                        gapTotalMs = gapMs,
                        modeFlags = flags,
                        lastClosedChunkIdx = idx,
                        syncBacklogged = backlogged,
                        envU8 = env,
                    ),
                )
            } catch (e: Exception) {
                // The Data Layer buffers and resynchronises on its own: a failure here is not a
                // loss, only a delay. The disk remains the source of truth.
                Log.w(TAG, "burst failed, postponed", e)
                if (force) SyncWorker.enqueue(this)
            }
        }
    }

    // --- closing ---

    private fun finalizeSession(reason: StopReason) {
        if (stopping) return
        stopping = true
        val m = marker
        Log.i(TAG, "session close, reason $reason")
        RecordingState.update { it.copy(phase = RecordPhase.FINALIZING) }
        updateNotification(finalizing = true)

        source?.stop()
        offBodySensor?.let { getSystemService(SensorManager::class.java).unregisterListener(offBodyListener) }
        handler.removeCallbacksAndMessages(null)

        pipeline?.flushBlock(SystemClock.elapsedRealtime())
        store?.close()?.let { idx ->
            closedChunks++
            lastClosedIdx = idx
        }
        writeSidecar(System.currentTimeMillis(), reason)

        if (m != null) {
            val total = lastClosedIdx + 1
            // We wait for the last burst: it is what turns "the watch died at 3 a.m." into "the
            // watch closed at 3 a.m. and sent everything".
            val task = syncExecutor.submit {
                try {
                    DataLayerTransfer.closeSession(this, m, System.currentTimeMillis(), total, reason)
                    val dir = sessionStore.sessionDir(m.sessionHex)
                    DataLayerTransfer.pushChunks(this, m.sessionHex, dir, urgentLast = true)
                } catch (e: Exception) {
                    Log.w(TAG, "final burst failed", e)
                }
            }
            try {
                task.get(90, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "final burst did not finish within the timeout", e)
            }
        }

        releaseWakeLock()
        sessionStore.clearActive()
        Watchdog.stop(this)
        SyncWorker.enqueue(this)

        RecordingState.set(
            RecordUiState(
                phase = RecordPhase.IDLE,
                lastStopReason = reason,
                batteryPct = batterySeries.lastOrNull() ?: -1,
                chunksPending = Preflight.pendingChunkCount(sessionStore.chunksRoot),
            ),
        )
        isRunning = false
        stopSelfClean()
    }

    private fun writeSidecar(endWallMs: Long?, reason: StopReason?) {
        val m = marker ?: return
        sessionStore.writeSidecar(
            m.sessionHex,
            Sidecar(
                startWallMs = m.startWallMs,
                endWallMs = endWallMs,
                zoneId = m.zoneId,
                nominalRateHz = m.nominalRateHz,
                measuredRateHz = gaps?.measuredRateHz ?: 0.0,
                modeFlags = m.modeFlags,
                degradationStep = gaps?.step ?: 0,
                gapCount = gaps?.gapCount ?: 0,
                gapTotalMs = gaps?.gapTotalMs ?: 0,
                offBodySeconds = offBodySeconds,
                samples = store?.totalSamples ?: 0,
                bytes = store?.totalBytes ?: 0,
                totalChunks = lastClosedIdx + 1,
                stopReason = reason,
                batterySeries = batterySeries.toList(),
            ),
        )
    }

    // --- utilities ---

    private fun publishSessionOpen(m: SessionMarker, mode: AcquisitionMode) {
        syncExecutor.execute {
            try {
                DataLayerTransfer.putSession(
                    this,
                    SessionHeader(
                        sessionHex = m.sessionHex,
                        startWallMs = m.startWallMs,
                        tzOffsetMin = TimeZone.getDefault().getOffset(m.startWallMs) / 60_000,
                        zoneId = m.zoneId,
                        nominalRateHz = mode.rateHz,
                        modeFlags = mode.modeFlags,
                        plannedStopWallMs = m.plannedStopWallMs,
                        state = SessionState.OPEN,
                    ),
                )
            } catch (e: Exception) {
                // Without this item, the phone will not know a night exists until it receives its
                // first chunk. That is a display delay, not a loss of data.
                Log.w(TAG, "session announcement failed", e)
            }
        }
    }

    private fun publishUiState() {
        val m = mode
        RecordingState.set(
            RecordUiState(
                phase = if (stopping) RecordPhase.FINALIZING else RecordPhase.RECORDING,
                elapsedMs = SystemClock.elapsedRealtime() - startElapsedMs,
                samples = store?.totalSamples ?: 0,
                bytesWritten = store?.totalBytes ?: 0,
                batteryPct = batterySeries.lastOrNull() ?: -1,
                gapCount = gaps?.gapCount ?: 0,
                gapTotalMs = gaps?.gapTotalMs ?: 0,
                modeLabel = m?.label ?: "",
                chunksPending = Preflight.pendingChunkCount(sessionStore.chunksRoot),
                chunksTotal = lastClosedIdx + 1,
                syncBacklogged = backlogged,
            ),
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        // Deliberately without an expiry timeout: outside an application store there is no
        // constraint, and a wake lock that expires at 4 a.m. is a silent bug.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pendulum:rec").also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(finalizing: Boolean = false): Notification {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
        val text = if (finalizing) {
            getString(R.string.notif_text_finalizing)
        } else {
            getString(
                R.string.notif_text,
                formatElapsed(elapsed),
                "%.1f".format((store?.totalBytes ?: 0) / 1_048_576.0),
                batterySeries.lastOrNull() ?: 0,
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pendulum)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            // No setSilent: the channel is created with IMPORTANCE_LOW, so already without sound
            // or vibration. Calling it here would be redundant, and the overload does not exist
            // on the platform's Notification.Builder (only on NotificationCompat).
            .setLocalOnly(true)
            .build()
    }

    private fun updateNotification(finalizing: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(finalizing))
    }

    private fun promoteToForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            // The `health` type does not exist before API 34; on a Wear OS 4 the service starts
            // with no declared type, which is the historical behaviour.
            startForeground(NOTIF_ID, buildNotification())
        }
        prefs().edit().putBoolean(Preflight.PREF_FGS_REFUSED, false).apply()
        true
    } catch (e: Exception) {
        // SecurityException if the qualification by HIGH_SAMPLING_RATE_SENSORS is not honoured by
        // the device, ForegroundServiceStartNotAllowedException if the start comes from the
        // background. In both cases the fact must reach the bedtime screen, not just logcat: that
        // is the only moment when someone can do something about it.
        Log.e(TAG, "foreground service start refused", e)
        prefs().edit().putBoolean(Preflight.PREF_FGS_REFUSED, true).apply()
        RecordingState.update { it.copy(phase = RecordPhase.IDLE) }
        stopSelf()
        false
    }

    private fun prefs() = getSharedPreferences(Preflight.PREFS, Context.MODE_PRIVATE)

    private fun stopAtLocalMinutes(): Int = prefs().getInt("stop_at_minutes", 10 * 60)

    private fun localMinutes(): Int = Calendar.getInstance().let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    }

    /** The nearer of "ten hours of night" and the local cut-off time. */
    private fun plannedStop(nowMs: Long, stopAtMinutes: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.HOUR_OF_DAY, stopAtMinutes / 60)
        cal.set(Calendar.MINUTE, stopAtMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        if (nowMinutes >= stopAtMinutes) cal.add(Calendar.DAY_OF_YEAR, 1)
        return minOf(nowMs + StopConditions.MAX_DURATION_MS, cal.timeInMillis)
    }

    private fun formatElapsed(ms: Long): String {
        val minutes = ms / 60_000
        return getString(R.string.elapsed_hm, minutes / 60, minutes % 60)
    }
}
