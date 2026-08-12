package com.pendulum.wear.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import java.util.Locale
import android.util.Log
import androidx.core.content.ContextCompat
import com.pendulum.wear.transfer.DataLayerTransfer
import java.io.File

/**
 * Pre-night verification. This is the only moment when the user looks at the watch, therefore the
 * only moment when a message stands a chance of being read: every blocker is phrased as an action,
 * and the blockers / warnings split is a decision, not a shade of colour.
 *
 * **An unreachable phone is never blocking.** The whole transfer architecture exists so that this
 * case carries no consequence: reporting it as an error would be lying to the user and would talk
 * them out of recording their night.
 */
object Preflight {

    /** Below this we refuse to start: a night weighs ~9 MB, but the margin protects the
     *  unacknowledged leftovers that we will never delete by force. */
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    /** Ceiling for the chunk directory: ~22 nights. */
    const val CHUNK_DIR_CAP_BYTES = 200L * 1024 * 1024

    const val PREFS = "pendulum"

    /** Set by the service when `startForeground` was refused: the only way to surface, on the
     *  bedtime screen, a failure that otherwise lives only in logcat. */
    const val PREF_FGS_REFUSED = "fgs_refused"

    /** Set by the service when [scaleMismatch] refused a start. Same mechanism as
     *  [PREF_FGS_REFUSED], and for the same reason: the refusal is decided at the moment the source
     *  is known, that is to say too late for the screen that triggered it. */
    const val PREF_SCALE_MISMATCH = "echelle_desaccordee"

    /**
     * **The bench time divisor only applies to synthetic replay.** True when a compressed build is
     * about to record the real sensor, which compresses nothing and throws everything out of tune.
     *
     * The compression is of **wall-clock** time: it divides the durations in `Durations`. The
     * replay in `SyntheticSource` compresses **sensor** time on the other side, by the same factor,
     * and it is that equality which `ScaleConsistencyTest` protects. With the real accelerometer
     * there is no replay at all: sensor time advances at 1x while wall-clock time advances at 250x,
     * and the factor has nothing left to equalise.
     *
     * What this produces, measured and not merely feared (`BENCH-LOG.md` §11.5.3): the FIFO burst
     * latency is 30 s of sensor time, hardware-bound and not compressible, while the guard delay of
     * the cut-off time falls from 1 h to 14.4 s. Recording therefore stops **before** the sensor
     * has delivered its first byte — 14.636 s, zero chunks, zero messages. A bench that goes out of
     * tune in silence is worse than no bench at all: it returns figures.
     *
     * The choice is to **refuse to start** rather than to neutralise the divisor at run time.
     * Neutralising would amount to running a build that is not the one you think you launched, and
     * `Durations.ACTIVE` is a catalogue built once and for all from a compiled value: there is no
     * honest place to correct it. Refusing says what to do.
     */
    fun scaleMismatch(divisor: Long, syntheticSource: Boolean): Boolean =
        divisor != 1L && !syntheticSource

    /**
     * @param nowMs the clock, as a parameter rather than read deep inside the function. It is what
     *   determines the night key, hence *which* evening context is looked for: the defect that cost
     *   this product the most — a context sealed under one key and read under another — replays
     *   here to the millisecond, and without this parameter it is not reproducible.
     */
    fun check(ctx: Context, nowMs: Long = System.currentTimeMillis()): PreflightResult {
        val blockers = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        val sm = ctx.getSystemService(SensorManager::class.java)
        val wakeUp = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val sensor = wakeUp ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) blockers += Issue(IssueId.NO_ACCELEROMETER)

        // Without a notification the system cannot display the foreground service, and it ends up
        // stopping it: the permission is not cosmetic.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            blockers += Issue(IssueId.NOTIFICATIONS_DENIED)
        }

        val store = SessionStore(ctx)
        val pending = pendingChunkCount(store.chunksRoot)
        val free = ctx.filesDir.usableSpace
        val occupied = dirSize(store.chunksRoot)
        if (free < MIN_FREE_BYTES || occupied > CHUNK_DIR_CAP_BYTES * 95 / 100) {
            // Refusing to begin a night is better than silently overwriting one.
            blockers += Issue(IssueId.STORAGE_FULL, listOf(formatBytes(free), pending.toString()))
        }

        val nightKey = DataLayerTransfer.nightKey(nowMs)
        val sealed = try {
            DataLayerTransfer.isEveningContextSealed(ctx, nightKey)
        } catch (e: Exception) {
            Log.w(TAG, "could not read the context lock", e)
            false
        }
        if (!sealed) blockers += Issue(IssueId.CONTEXT_NOT_SEALED)

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FGS_REFUSED, false)) {
            blockers += Issue(IssueId.FGS_REFUSED)
        }
        if (prefs.getBoolean(PREF_SCALE_MISMATCH, false)) {
            blockers += Issue(
                IssueId.BENCH_SCALE_MISMATCH,
                listOf(com.pendulum.wear.time.TimeScaling.DIVISOR.toString()),
            )
        }

        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (battery in 0..39) warnings += Issue(IssueId.LOW_BATTERY, listOf(battery.toString()))
        if (sensor != null && wakeUp == null) warnings += Issue(IssueId.NO_WAKEUP_SENSOR)
        if (pending > 0) warnings += Issue(IssueId.PENDING_SYNC, listOf(pending.toString()))
        if (!phoneReachable(ctx)) warnings += Issue(IssueId.PHONE_UNREACHABLE)
        // A bench override has to be visible. The time divisor has had its own from the start;
        // this one deserves the same treatment, otherwise a bench build records on the
        // charger with nothing saying so. A warning and not a blocker: it is exactly what
        // the build was asked to do.
        if (com.pendulum.wear.time.Bench.IGNORE_CHARGER) {
            warnings += Issue(IssueId.BENCH_CHARGER_IGNORED)
        }

        return PreflightResult(blockers, warnings, battery, free, pending)
    }

    private fun phoneReachable(ctx: Context): Boolean = try {
        val nodes = com.google.android.gms.tasks.Tasks.await(
            com.google.android.gms.wearable.Wearable.getNodeClient(ctx).connectedNodes,
            10,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        phoneReachable(nodes)
    } catch (e: Exception) {
        false
    }

    /**
     * **A non-empty node list, and nothing more.** Extracted from its caller so that it can be
     * tested without GMS: it is a predicate over a list, and it is the only part that can get it
     * wrong.
     *
     * ### What is known about this predicate, and what is not
     *
     * On the emulator it lies: with the phone emulator killed, `connectedNodes` still returns the
     * paired watch and the `PHONE_UNREACHABLE` warning never appears (§7.1). That is the known
     * defect, and it has **not yet been reproduced on real hardware** — for want of being able to
     * produce a genuinely unreachable phone without losing the `adb` link used to measure it.
     *
     * ### The two proposed fixes, and why neither is applied
     *
     * `getCapability(…, FILTER_REACHABLE)`: returns a node in exactly the same cases. Measured in
     * §11.3.
     *
     * `nodes.any { it.isNearby }`: **a measured false red** (§12.2). With Bluetooth off and both
     * devices on the same WiFi, `isNearby` turns `false` on both sides — and the Data Layer keeps
     * carrying: an item published by the phone reaches the watch in under 45 s, a deletion in under
     * 60 s. The link switches to WiFi, which "proximity" no longer describes. What §11.3 read as a
     * dead transport was a 45 s waiting window that was too short. Applying this fix would display
     * "phone unreachable" while synchronisation is happening.
     *
     * `MessageClient.sendMessage`, the only Data Layer API that fails when the node is out of
     * range: measured at `ok=true` in 8 to 13 ms in **every** radio state that could be produced,
     * including those where `isNearby` was `false`. Eleven milliseconds is not a round trip: it is
     * a local acceptance. Since no genuinely unreachable state could be produced, nothing says it
     * would fail, and one predicate is not swapped for another on a hunch.
     *
     * The warning is never blocking in any case: it says "recording carries on, sync will happen
     * later", which stays true in both directions of error.
     */
    fun phoneReachable(nodes: List<com.google.android.gms.wearable.Node>): Boolean =
        nodes.isNotEmpty()

    fun pendingChunkCount(chunksRoot: File): Int =
        chunksRoot.listFiles()?.sumOf { dir ->
            dir.listFiles { f: File -> f.name.endsWith(".pendulum") }?.size ?: 0
        } ?: 0

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * A free volume, **value and unit held together**.
     *
     * A non-breaking space (U+00A0) and not an ordinary space: on the round screen of the watch,
     * the line "Free space 12.2 GB" broke between the number and its unit, leaving "GB" alone on
     * the next line. The bench had noted it, and the fix was twice believed done — on a screenshot
     * where the value happened to fit. Nothing in the code prevented it: it is the character itself
     * that must forbid the break, not the width of that day's number.
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f\u00A0GB".format(Locale.UK, bytes / 1024.0 / 1024 / 1024)
        else -> "%d\u00A0MB".format(Locale.UK, bytes / 1024 / 1024)
    }

    private const val TAG = "PendulumPreflight"
}

enum class IssueId {
    // blockers
    CONTEXT_NOT_SEALED,
    NOTIFICATIONS_DENIED,
    NO_ACCELEROMETER,
    STORAGE_FULL,
    FGS_REFUSED,

    /** Bench build — compressed wall-clock time — on the real sensor. See
     *  [Preflight.scaleMismatch]. Does not exist in release: the divisor is 1 there. */
    BENCH_SCALE_MISMATCH,

    /** Bench build: stopping on the charger is disabled. See `Bench.IGNORE_CHARGER`. */
    BENCH_CHARGER_IGNORED,

    // warnings
    LOW_BATTERY,
    PHONE_UNREACHABLE,
    NO_WAKEUP_SENSOR,
    PENDING_SYNC,
}

/**
 * What is **broken**, as opposed to what is **not yet done**.
 *
 * Blocking the start and being a failure are two different things, and the colour encodes the
 * second, not the first. The rule is the one of `PendulumColors` on the phone side: red is reserved
 * for what is broken; a situation the user can clear themselves is amber.
 *
 * The defect repaired here only showed up with the two screens side by side: for the **same** fact
 * — the evening context not yet sealed — the phone displayed amber and the watch red. Two devices,
 * two verdicts, one single state. The phone was right: filling in a form you have not filled in yet
 * is not a failure.
 *
 * `NO_ACCELEROMETER`, `STORAGE_FULL` and `FGS_REFUSED` stay red: there is nothing the user can do
 * about them from this screen. `BENCH_SCALE_MISMATCH` too — a bench build wired to the real sensor
 * would produce false measurements, and that is the worst silent case in the project.
 */
val IssueId.isFailure: Boolean
    get() = when (this) {
        IssueId.NO_ACCELEROMETER,
        IssueId.STORAGE_FULL,
        IssueId.FGS_REFUSED,
        IssueId.BENCH_SCALE_MISMATCH -> true

        IssueId.CONTEXT_NOT_SEALED,
        IssueId.NOTIFICATIONS_DENIED,
        // Not a failure: the override is exactly what the build was asked to do. It deserves to be
        // seen, not to be reported as broken — and unlike `BENCH_SCALE_MISMATCH`, it falsifies no
        // measurement, it only lets a recording run that is not a night.
        IssueId.BENCH_CHARGER_IGNORED -> false

        // Warnings are amber by construction; the question does not arise for them.
        IssueId.LOW_BATTERY,
        IssueId.PHONE_UNREACHABLE,
        IssueId.NO_WAKEUP_SENSOR,
        IssueId.PENDING_SYNC -> false
    }

/** An issue and its already-formatted arguments. The labels live in `strings.xml`. */
data class Issue(val id: IssueId, val args: List<String> = emptyList())

data class PreflightResult(
    val blockers: List<Issue>,
    val warnings: List<Issue>,
    val batteryPct: Int,
    val freeBytes: Long,
    val pendingChunks: Int,
) {
    val canStart: Boolean get() = blockers.isEmpty()
}
