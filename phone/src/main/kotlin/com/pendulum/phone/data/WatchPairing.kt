package com.pendulum.phone.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The three states of the pairing, and the reason why this is not a boolean.
 *
 * Each one is repaired in a different place, and the classic mistake on this screen is to conflate
 * the first two: offering to install the application on a watch that is paired to nothing produces
 * an installation nobody will ever see, and leaves the user convinced they did what was asked of
 * them.
 */
enum class PairingState {
    /**
     * No connected node: **no watch is paired to this phone**. The repair lies in the
     * manufacturer's companion application, not in an application store.
     */
    NO_WATCH,

    /**
     * A watch is paired, but nothing answers the Pendulum capability. Two causes indistinguishable
     * from the outside — application absent, or watch out of Bluetooth range — and a single useful
     * action in both cases: offer the installation, and wait.
     */
    APP_MISSING_OR_OUT_OF_RANGE,

    /** A reachable watch announces the capability. This is the only state that validates the step. */
    READY,
}

/** What the pairing screen knows about the watch. The name is `null` as long as no node exists. */
data class WatchState(val state: PairingState, val name: String? = null)

/**
 * Detection of the watch from the phone.
 *
 * ### Why the capability and not the node list
 *
 * `NodeClient.connectedNodes` answers "is a watch paired", and that is all. It says nothing about
 * the application running on it: a Galaxy Watch that is paired but has no Pendulum appears there
 * exactly like a ready watch. Only `CapabilityClient` answers the question that matters, and only
 * if both sides declare their capability in `res/values/wear.xml`.
 *
 * ### The two halves of the detection
 *
 * It only makes sense if both sides declare their capability: `phone/res/values/wear.xml`
 * announces [PHONE_CAPABILITY], `wear/res/values/wear.xml` announces [WATCH_CAPABILITY], and each
 * one looks for the other's. The names are deliberately distinct — a shared name would make each
 * device capable of detecting itself.
 *
 * Both files carry a `tools:keep` on the array. This is not decorative: no Kotlin reference points
 * to it — it is Google Play Services that reads it, by its name, at installation — so the resource
 * shrinker deletes it, in release only, in silence. The detection then stops working without any
 * message being produced.
 */
object WatchPairing {

    /** What the phone announces. Must match `res/values/wear.xml`. */
    const val PHONE_CAPABILITY = "pendulum_phone_app"

    /** What the phone looks for. Must match the `wear.xml` of the `:wear` module. */
    const val WATCH_CAPABILITY = "pendulum_watch_app"

    /**
     * Store page opened **on the watch**. `market://` and not `https://play.google.com/...`: the
     * URI is resolved by the watch's Play Store, which is the only one able to install anything
     * there.
     */
    private const val WATCH_STORE_LINK = "market://details?id=com.pendulum"

    /**
     * The classification, isolated from any Android call so as to be testable.
     *
     * @param connectedNodes identifiers returned by `NodeClient.connectedNodes`.
     * @param capableNodes identifiers returned by `CapabilityClient.getCapability(...,
     *   FILTER_REACHABLE)`.
     *
     * A capable node wins even if the list of connected nodes is empty: the two reads are not
     * atomic, and a node that announces the capability *and* is declared reachable is stronger
     * evidence than an empty list read one millisecond earlier.
     */
    fun classify(connectedNodes: Set<String>, capableNodes: Set<String>): PairingState = when {
        capableNodes.isNotEmpty() -> PairingState.READY
        connectedNodes.isEmpty() -> PairingState.NO_WATCH
        else -> PairingState.APP_MISSING_OR_OUT_OF_RANGE
    }

    /**
     * A one-off read of both clients.
     *
     * A failure is classified as [PairingState.NO_WATCH] and not as "unknown": when the Wearable
     * services raise, it is almost always because Google Play Services or the Wear OS application
     * are missing, and the action to offer is then exactly that of the first state — go through
     * the companion application. A fourth "we do not know" state would open no further action and
     * would make one more screen harder to understand.
     */
    suspend fun read(context: Context): WatchState = withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS,
            )
            val capable = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE),
                TIMEOUT_S,
                TimeUnit.SECONDS,
            ).nodes

            WatchState(
                state = classify(
                    connectedNodes = nodes.map { it.id }.toSet(),
                    capableNodes = capable.map { it.id }.toSet(),
                ),
                name = capable.firstOrNull()?.displayName ?: nodes.firstOrNull()?.displayName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "pairing could not be read", e)
            WatchState(PairingState.NO_WATCH)
        }
    }

    /**
     * The state, read again at every capability change.
     *
     * This is what makes it possible **not to block during the installation**: the user goes off
     * to install the application on the watch, the screen stays open, and the step ticks itself
     * when the capability appears. Without this listener there would have to be an "I am done"
     * button — hence a button pressed too early, hence a screen that says no to someone who did
     * what was asked of them.
     */
    fun observe(context: Context): Flow<WatchState> = callbackFlow {
        val client = Wearable.getCapabilityClient(context)

        val listener = CapabilityClient.OnCapabilityChangedListener { _: CapabilityInfo ->
            // Everything is read again rather than trusting the event: `CapabilityInfo` carries
            // only the capable nodes, and telling "no watch" from "watch without the application"
            // also requires the list of connected nodes.
            launch { trySend(read(context)) }
        }

        client.addListener(listener, WATCH_CAPABILITY)
        trySend(read(context))

        awaitClose { client.removeListener(listener, WATCH_CAPABILITY) }
    }

    /**
     * Opens the Pendulum Play Store page **on the watch**.
     *
     * `RemoteActivityHelper` and not a local `startActivity`: the launch must happen on the other
     * side, and it is carried out over there by Google Play Services, which is the only path that
     * does not get blocked. Symmetrical with `wear/transfer/RemoteCommands.kt`.
     *
     * @return `false` if nothing could be opened. The caller must say so: announcing an opened page
     *   that is not open sends someone looking for a screen on their watch.
     */
    suspend fun openStoreOnWatch(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS,
            )
            if (nodes.isEmpty()) return@withContext false

            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse(WATCH_STORE_LINK))

            // Every paired watch, and not the first one: guessing which one the user will wear
            // tonight is not something that can be done correctly, and one store page opened too
            // many costs nothing.
            //
            // Blocking `get` rather than `await`: the latter would come from
            // `kotlinx-coroutines-guava`, one more dependency to convert a `ListenableFuture` when
            // we are already on `Dispatchers.IO`.
            var opened = false
            nodes.forEach { node ->
                runCatching {
                    RemoteActivityHelper(context)
                        .startRemoteActivity(intent, node.id)
                        .get(TIMEOUT_S, TimeUnit.SECONDS)
                    opened = true
                }.onFailure { Log.w(TAG, "store not opened on ${node.displayName}", it) }
            }
            opened
        } catch (e: Exception) {
            Log.w(TAG, "store not opened on the watch", e)
            false
        }
    }

    /**
     * Opens the watch's companion application, on **this** phone.
     *
     * This is the repair for the first state, and it is not the Play Store: a watch that is paired
     * to nothing will never receive an installation, however many store pages are opened. Pairing
     * happens in the manufacturer's application, and nowhere else.
     *
     * The two packages cover the bulk of the Wear OS fleet; they are declared in the `<queries>` of
     * the manifest, without which `getLaunchIntentForPackage` returns `null` from Android 11
     * onwards even when the application is installed. On a phone that has neither, `false` is
     * returned and the screen says so, rather than opening some store page at random.
     */
    fun openCompanionApp(context: Context): Boolean {
        for (pkg in COMPANIONS) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }.onFailure { Log.w(TAG, "companion $pkg not launched", it) }
        }
        return false
    }

    private val COMPANIONS = listOf(
        "com.google.android.wearable.app", // Wear OS by Google / Pixel Watch
        "com.samsung.android.app.watchmanager", // Galaxy Wearable
    )

    private const val TAG = "PendulumPairing"
    private const val TIMEOUT_S = 15L
}
