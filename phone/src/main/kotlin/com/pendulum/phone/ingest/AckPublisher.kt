package com.pendulum.phone.ingest

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import com.pendulum.phone.db.PendulumDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The acknowledgement, recomputed **entirely from the database** and put into the Data Layer.
 *
 * ### Why it has more than one caller
 *
 * Until 4 September 2026 the only caller was the receiving service, at the end of a burst of chunk
 * events. That made the acknowledgement an *event* in practice, whatever the protocol says about
 * it being a state: a chunk row that came into the database by any other road — `IngestWorker`
 * recovering a file the service had written just before the process died, a chunk that landed
 * before its session and was reconciled when the header arrived — was never acknowledged, because
 * nothing re-put the item outside a chunk event of that session. Its file stayed on the watch,
 * counted as pending every evening, and its item held one of the 24 in-flight slots for good.
 *
 * The acknowledgement is now put from every place a row can appear. That is safe by construction:
 * it is recomputed from the `complete` rows every single time — one query over a few dozen rows —
 * so no caller can publish anything the database does not hold, and an identical put is
 * deduplicated by the Data Layer. What differs between two puts of the same state is `phoneMs`,
 * so the watch may see the same acknowledgement twice; `applyAck` is idempotent, and the second
 * pass is exactly the re-solicitation a stuck file needs.
 *
 * `setUrgent()`: without it, the system may delay the synchronisation by 30 minutes. The watch
 * keeps its files until the acknowledgement; delaying it means saturating its disk and its ceiling
 * of items in flight for nothing.
 */
object AckPublisher {

    /**
     * Sixty seconds, the same bound the watch puts on every store call: a dead Google Play
     * Services must not hold a GMS callback, or a worker, for ever.
     */
    private const val TIMEOUT_S = 60L

    /**
     * @param needResend received indices whose CRC-32 was wrong, from the burst that just ended.
     *   Empty from the recovery paths: a file on the disk has already passed the verifier.
     * @return `true` if an item was put; `false` when there was nothing to acknowledge and nothing
     *   to ask for again — an empty acknowledgement tells the watch nothing, and a put wakes the
     *   link.
     */
    suspend fun publish(
        context: Context,
        db: PendulumDatabase,
        sessionHex: String,
        needResend: List<Int> = emptyList(),
    ): Boolean {
        val complete = db.chunkDao().completeIndices(sessionHex)
        if (complete.isEmpty() && needResend.isEmpty()) return false
        val ack = AckBuilder.build(sessionHex, complete, needResend, System.currentTimeMillis())
        val request = PutDataRequest.create(WirePaths.ack(sessionHex))
            .setData(ack.encode())
            .setUrgent()
        withContext(Dispatchers.IO) {
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), TIMEOUT_S, TimeUnit.SECONDS)
        }
        return true
    }
}
