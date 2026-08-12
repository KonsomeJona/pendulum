package com.pendulum.wear.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Opening the phone application from the watch.
 *
 * ### The problem this solves
 *
 * The most frequent preflight blocker is "evening context not sealed", and it is cleared **on the
 * other device**. Until now the watch said "fill in the evening form on the phone" and stopped
 * there: up to the user to put the watch down, find their phone, unlock it, find the application
 * again. At bedtime, screen at ankle height.
 *
 * ### Why this path and not the obvious one
 *
 * The obvious one would be: the watch sends a message, the phone receives it in its
 * `WearableListenerService` and calls `startActivity`. **That path has been broken since
 * Android 10** — the service runs in the background, the launch is blocked, and the only witness is
 * a `Background activity launch blocked!` line in the system logs. Android 14 and then 15 tightened
 * the rules further. It is a silent failure, therefore the worst kind.
 *
 * [RemoteActivityHelper] does get through, because the launch is carried out on the phone side by
 * Google Play Services and not by our process: it benefits from the exemption granted to system
 * components. Its constraint is to accept only an `ACTION_VIEW` with a browsable URI, hence the
 * deep link declared on `MainActivity` on the phone side.
 */
object RemoteCommands {

    /**
     * Opens the evening form on the phone.
     *
     * @return `false` if no device could be reached — phone switched off, out of range, or
     *   companion application absent. The caller must say so: announcing a success when nothing
     *   opened sends someone looking for a screen that never appeared.
     */
    suspend fun openPhone(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // A blocking `get` rather than `await`: the latter would come from
            // `kotlinx-coroutines-guava`, one more dependency just to convert a
            // `ListenableFuture` when we are already on `Dispatchers.IO` and an explicit
            // timeout is better than an unbounded wait.
            RemoteActivityHelper(ctx).startRemoteActivity(
                Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(EVENING_LINK)),
            ).get(TIMEOUT_S, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            // Fallback: a message, which the phone turns into a notification. A notification is
            // the only guaranteed path to wake an application from the background, because the
            // user's tap is an explicit exemption from the block.
            Log.w(TAG, "remote opening refused, falling back to a notification", e)
            sendToAllNodes(ctx, WirePaths.OPEN_PHONE)
        }
    }

    /**
     * Sends a message to every connected node.
     *
     * To every one, and not to the first: there may be several paired phones, and guessing which
     * one is the right one from a node identifier is not something that can be done correctly. The
     * others ignore a message they do not know how to handle.
     */
    private fun sendToAllNodes(ctx: Context, path: String): Boolean = try {
        val nodes = Tasks.await(
            Wearable.getNodeClient(ctx).connectedNodes,
            TIMEOUT_S,
            TimeUnit.SECONDS,
        )
        nodes.forEach { node ->
            Tasks.await(
                Wearable.getMessageClient(ctx).sendMessage(node.id, path, ByteArray(0)),
                TIMEOUT_S,
                TimeUnit.SECONDS,
            )
        }
        nodes.isNotEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "no node reachable for $path", e)
        false
    }

    /**
     * Deep link to the evening form, on the phone side.
     *
     * A custom scheme and not a verified `https` one: an App Link would require hosting an
     * `assetlinks.json`, which is feasible — the documentation site exists — but adds a dependency
     * on a domain for an application that does not even declare the Internet permission. The price
     * of the custom scheme is that a manufacturer may show an "open with" box on first use.
     */
    const val EVENING_LINK = "pendulum://tonight"

    private const val TAG = "PendulumRemote"
    private const val TIMEOUT_S = 15L
}
