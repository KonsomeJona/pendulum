package com.pendulum.phone.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The orders the phone sends to the watch.
 *
 * ### What was missing
 *
 * `WirePaths.SWEEP_REQUEST` has been **listened to by the watch from the start** — `AckObserver`
 * enqueues `SyncWorker` on it, which republishes everything that has not been acknowledged — and
 * **no phone code ever emitted it**. The catch-up path of the protocol therefore existed in full,
 * wired on both sides except on the side that triggers it. The consequence was not an error but a
 * wait: after a Bluetooth cut, the chunks stayed on the watch until it decided by itself to try
 * again.
 *
 * ### A message, not a `DataItem`
 *
 * A `DataItem` is a replicated state: it persists, and republishing the same payload triggers
 * nothing at all, since nothing has changed. An order is not a state — "sweep now" needs to be
 * heard twice in a row if it is said twice. `MessageClient` has exactly that semantics, and its
 * counterpart is that it fails when the watch is out of range. That is acceptable here: the
 * gesture is explicit, the user is in front of the screen, and the failure is reported.
 */
object WatchCommands {

    /**
     * Asks the watch to push everything it still holds.
     *
     * @return `false` if no node could be reached. The caller must say so: announcing a sweep that
     *   was never requested makes people wait for data that will not come.
     */
    suspend fun requestSweep(context: Context): Boolean =
        sendToAllNodes(context, WirePaths.SWEEP_REQUEST)

    /**
     * Asks the watch to start recording.
     *
     * Same shape of defect as the sweep, and found in the same way — by re-reading the
     * documentation rather than the code: `WirePaths.START_REQUEST` was declared, `AckObserver`
     * handled it, including its notification fallback for when Android refuses a start from the
     * background, and **nothing emitted it**. A protocol wired on the receiving side and inert on
     * the triggering side produces no error: it produces a button that does not exist.
     *
     * ### What this order does not bypass
     *
     * `RecordingService` checks `Preflight.check` again before `startSession(resume = false)`. A
     * request arriving without a sealed evening context is therefore refused **on the watch side**,
     * whatever its origin — the guard rail is not in the button, it is in the service, and that is
     * what makes it possible to open a second start path without weakening it.
     *
     * @return `false` if no node could be reached. It has to be said: that is the difference
     *   between "the watch is recording" and "the watch received nothing", and the user who goes to
     *   bed believing the first one loses their night.
     */
    suspend fun requestStart(context: Context): Boolean =
        sendToAllNodes(context, WirePaths.START_REQUEST)

    /**
     * Sends a message to **all** the connected nodes, and not to the first one.
     *
     * Several watches can be paired, and guessing which one is carrying the recording from a node
     * identifier is not something that can be done correctly. The ones that do not know how to
     * handle the path ignore it — that is the behaviour of the Data Layer, not a tolerance on our
     * part. Symmetrical with `wear/transfer/RemoteCommands.kt`.
     */
    private suspend fun sendToAllNodes(context: Context, path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val nodes = Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes,
                    TIMEOUT_S,
                    TimeUnit.SECONDS,
                )
                nodes.forEach { node ->
                    Tasks.await(
                        Wearable.getMessageClient(context).sendMessage(node.id, path, ByteArray(0)),
                        TIMEOUT_S,
                        TimeUnit.SECONDS,
                    )
                }
                nodes.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "no reachable node for $path", e)
                false
            }
        }

    private const val TAG = "PendulumCommands"
    private const val TIMEOUT_S = 15L
}
