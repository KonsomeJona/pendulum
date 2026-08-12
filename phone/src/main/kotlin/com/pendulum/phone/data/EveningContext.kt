package com.pendulum.phone.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.NightContextEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.work.ContextPublicationWorker
import com.pendulum.phone.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The sealing of the evening context — the one real gate of the product.
 *
 * ### What did not exist
 *
 * `Preflight` refuses the start as long as `/pendulum/context/<evening>` has not been published,
 * and **no phone code ever published that item**. The sealing button was an `onSceller = {}`, the
 * entry form did not exist, and the two strings that describe it (`tonight_seal_title` and
 * `tonight_seal_confirmation`) were referenced by no composable. Consequence: the watch displayed
 * "fill in the evening form on the phone" and refused to start, indefinitely, without any path
 * existing to answer it.
 *
 * ### Why the sealing is irreversible
 *
 * The failure mode aimed at is not fraud, it is the good-faith touch-up. Seeing a high figure on
 * waking, remembering that "in fact the dose was later", and correcting it, is enough to
 * manufacture the very correlation being looked for. The defence is not discipline: it is that the
 * database refuses the modification (`ContextDao` without `@Update`, plus two SQLite triggers) and
 * that the form says so beforehand, not afterwards.
 */
class EveningContextSealer(context: Context) {

    private val app = context.applicationContext
    private val db = PendulumDatabase.get(app)

    /** The context of the current evening, or `null` as long as it is not sealed. */
    fun observeCurrentEvening(nowMs: Long): Flow<NightContextEntity?> =
        db.contextDao().observe(WirePaths.nightKey(nowMs))

    suspend fun isSealed(nowMs: Long): Boolean =
        db.contextDao().find(WirePaths.nightKey(nowMs)) != null

    /**
     * Seals the context, then publishes it to the watch. **In that order, and it matters.**
     *
     * The database first: it is the database that carries the proof that the context precedes the
     * measurement, and it refuses the duplicate (`OnConflictStrategy.ABORT`) — sealing the same
     * evening twice raises rather than overwriting in silence.
     *
     * The reverse would have an unpleasant and silent consequence: a watch unblocked for an evening
     * whose context is not in the database, hence a night that records and that will come out
     * excluded for `NO_CONTEXT` in the morning.
     *
     * ### What the Data Layer catches up, and what it does not
     *
     * It catches up what **entered the store**: an item put down while the watch is off or out of
     * range leaves on its own as soon as the watch comes back, and that is its whole reason for
     * being. It catches up nothing at all when the `putDataItem` itself failed — Google Play
     * Services unavailable, API exception — because the item then never existed anywhere. And since
     * the database is immutable and `Preflight` makes the absence of that item a hard block, that
     * one second of unavailability lost the whole night, with no recourse.
     *
     * It is [ContextPublicationWorker] that catches up that case, and it alone: its WorkManager
     * queue survives a phone restart, and a `putDataItem` with an identical payload is
     * deduplicated, so putting the item down again costs nothing and risks nothing.
     *
     * @return `true` if the item was indeed published **straight away**. `false` means the database
     *   has the context, that the replay is enqueued, and that the watch does not know about it yet
     *   — the caller must say so, not keep quiet about it: the sealing succeeded, the unblocking
     *   did not.
     */
    suspend fun seal(entry: EveningEntry, nowMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val nightKey = WirePaths.nightKey(nowMs)

            SealingOrder.execute(
                writeToDatabase = {
                    db.contextDao().seal(
                        NightContextEntity(
                            nightKey = nightKey,
                            sealedAtMs = nowMs,
                            leg = entry.leg,
                            strapId = entry.strap,
                            aloneInBed = entry.aloneInBed,
                            medicationJson = entry.medicationJson,
                            caffeineAfter16h = entry.caffeineAfter16h,
                            alcoholUnits = entry.alcoholUnits,
                            unusualExercise = entry.unusualExercise,
                            notes = entry.notes?.takeIf { it.isNotBlank() },
                        )
                    )
                },
                publish = { ContextPublication.put(app, nightKey, nowMs) },
                enqueueReplay = {
                    WorkScheduler.enqueueContextPublication(app, nightKey, nowMs)
                },
            )
        }
}

/**
 * The sequence of the sealing, isolated from the database, from the Data Layer and from
 * WorkManager.
 *
 * Three lines that carry two decisions, and neither of the two was verifiable as long as they
 * lived at the bottom of a coroutine that opened SQLite and called Google Play Services:
 *
 *  1. **The database before the Data Layer.** Unblocking the watch for an evening whose context is
 *     not in the database gives a night that records and that will come out excluded for
 *     `NO_CONTEXT`.
 *  2. **A failed put enqueues the replay.** That is the only thing separating a passing
 *     unavailability of Google Play Services from a definitively lost night: the database is
 *     immutable, so there is no second chance by hand.
 *
 * `inline` so that [writeToDatabase] can stay a suspending lambda called from `seal()` without this
 * function having to be one — the test then exercises it without a coroutine.
 */
object SealingOrder {

    /** @return `true` if the item left on the first try, `false` if the replay was enqueued. */
    inline fun execute(
        writeToDatabase: () -> Unit,
        publish: () -> Boolean,
        enqueueReplay: () -> Unit,
    ): Boolean {
        writeToDatabase()
        if (publish()) return true
        enqueueReplay()
        return false
    }
}

/**
 * The putting down of the item `Preflight` waits for — the only place on the phone that writes it.
 *
 * A single place, because two write paths would give two payloads, and two different payloads do
 * not deduplicate: the replay would put down an item distinct from that of the online attempt, and
 * the watch would see the same context change under it twice. Here, [ContextPublicationWorker] and
 * [EveningContextSealer] call the same function with the same `sealedAtMs`, so the Data Layer
 * recognises the item and does nothing — which is exactly the wanted behaviour when the first
 * attempt had in fact succeeded.
 *
 * The payload is **deliberately minimal**: the evening and the instant of the sealing, nothing of
 * the content of the form. The watch needs to know only one thing — that the context exists — and
 * the content is health data that has no reason to cross the Data Layer nor to replicate itself
 * onto a second device.
 *
 * Manual framing rather than a `DataMap`, as everywhere else in this protocol: a dictionary fails
 * in silence when a key changes name.
 */
object ContextPublication {

    private const val TAG = "PendulumContext"

    /**
     * The timeout of the remote call, and it does not go through `Durations`.
     *
     * `TimeScale` states it plainly: since remote-call waits do not compress, dividing them would
     * produce outright expiries on an otherwise healthy bench.
     */
    private const val TIMEOUT_S = 20L

    /** @return `true` if the item entered the local replicated store. */
    fun put(context: Context, nightKey: String, sealedAtMs: Long): Boolean = try {
        val request = PutDataRequest.create(WirePaths.context(nightKey))
            .setData(sealedAtMs.toString().toByteArray())
            // The watch may well be waiting for this item, screen on, at the foot of the bed.
            .setUrgent()
        Tasks.await(
            Wearable.getDataClient(context.applicationContext).putDataItem(request),
            TIMEOUT_S,
            TimeUnit.SECONDS,
        )
        true
    } catch (e: Exception) {
        // No exception rethrown: the context **is** sealed, which is the essential part and the
        // irreversible one. What failed is the entry into the store, and it is the republication
        // worker that takes it up again — the Data Layer, for its part, can only catch up what has
        // already entered it.
        Log.w(TAG, "context sealed in the database but not published to the watch", e)
        false
    }
}

/**
 * What the evening form asks for.
 *
 * The fields are exactly those of `NightContextEntity`, and that is no coincidence: a form that
 * collects more than what the database seals collects data that nothing protects, and a form that
 * collects less leaves empty columns of which nobody will be able to say whether they are wrong or
 * absent.
 */
data class EveningEntry(
    /** `LEFT` or `RIGHT`. Comparability criterion: a one-sided sensor sees a doubled interval
     *  when the movements alternate, so changing leg changes the measurement. */
    val leg: String,
    /** Strap and tightness notch. The play of the strap makes the amplitude vary by a factor of 2 to 3. */
    val strap: String,
    /** A bed partner transmits their own movements through the mattress. */
    val aloneInBed: Boolean,
    val medicationJson: String,
    val caffeineAfter16h: Boolean,
    val alcoholUnits: Double,
    val unusualExercise: Boolean,
    val notes: String? = null,
) {
    companion object {
        const val LEG_LEFT = "LEFT"
        const val LEG_RIGHT = "RIGHT"
    }
}
