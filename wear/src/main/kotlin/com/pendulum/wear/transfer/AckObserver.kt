package com.pendulum.wear.transfer

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.EraseOrder
import com.pendulum.format.wire.WirePaths
import com.pendulum.wear.record.RecordingService
import com.pendulum.wear.record.SessionStore

/**
 * Reception of the acknowledgements from the phone. Started by GMS on delivery, even if the
 * application has never been opened — which is why the acknowledgement is a `DataItem` and not a
 * message: a message sent while the watch is out of range would be lost, and the watch would keep
 * its files forever.
 *
 * Reading the same acknowledgement twice gives the same result as reading it once: files already
 * erased stay erased, items already deleted too. Idempotence comes for free, without a counter,
 * because the acknowledgement is a **state**, not an event.
 */
class AckObserver : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val data = event.dataItem.data ?: continue
            try {
                when {
                    path == WirePaths.ERASE -> onErase(EraseOrder.decode(data))
                    path.startsWith(WirePaths.ACK_PREFIX) -> {
                        val ack = Ack.decode(data)
                        val dir = SessionStore(this).sessionDir(ack.sessionHex)
                        val deleted = DataLayerTransfer.applyAck(this, ack, dir)
                        Log.i(TAG, "ack ${ack.sessionHex}: $deleted files freed")
                        // Room has just been freed in the store: the rest of the backlog can
                        // leave right away rather than at the next chunk rotation.
                        SyncWorker.enqueue(this)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "unreadable item on $path", e)
            }
        }
    }

    /**
     * The phone has erased everything it received before the order's instant.
     *
     * An item and not a message, like the acknowledgement and for the same reason: it has to
     * reach a watch that was out of range when the user typed the confirmation. What it does is
     * in `DataLayerTransfer.disown`; what is finished here is the one thing that object cannot
     * do — stopping the recording service when the session being recorded is among the disowned.
     *
     * `ACTION_STOP`, the same intent the watch's own button sends, and not a kill: the service
     * releases its wake lock and its sensor in `finalizeSession` and nowhere else. The night
     * closes normally on the watch's side; its directory carries the tombstone by then, so the
     * close announces nothing and the final burst pushes nothing. Sent through
     * `startForegroundService` because that is how `MainActivity` does it and the service is
     * already in the foreground, which is what makes the call legal from a GMS callback; the
     * `catch` covers the refusal all the same, and the tombstone stands either way — a service
     * that could not be stopped records into a directory whose every burst is discarded.
     */
    private fun onErase(order: EraseOrder) {
        val disowned = DataLayerTransfer.disown(this, order)
        Log.i(TAG, "erasure before ${order.erasedBeforeMs}: ${disowned.sessions.size} sessions disowned")
        if (!disowned.stopRecording) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
        } catch (e: Exception) {
            Log.w(TAG, "the disowned recording could not be stopped from here", e)
        }
    }

    /**
     * The two requests the phone can address to the watch.
     *
     * ### `/pendulum/sweep-request` — full catch-up
     *
     * The sweep over `ChannelClient` is not implemented: `SweepFraming` does not exist yet in
     * `:format`, and the catch-up by `DataItem` already covers the real case (the ceiling of
     * 24 items empties at the pace of the acknowledgements). The request is therefore honoured by
     * an ordinary burst — same result, a few minutes more, no specific code to maintain.
     *
     * ### `/pendulum/start-request` — start recording
     *
     * A deliberate departure from `docs/06-interface.md` §2.2, which reserves starting for a
     * physical gesture on the watch. The guard rail itself stays whole: [RecordingService]
     * re-checks `Preflight.check` before `startSession(resume = false)`, so a request without a
     * sealed context is refused here whatever its origin.
     */
    override fun onMessageReceived(event: MessageEvent) {
        when {
            event.path.startsWith(WirePaths.SWEEP_REQUEST) -> SyncWorker.enqueue(this)
            event.path.startsWith(WirePaths.START_REQUEST) -> startOrNotify()
        }
    }

    /**
     * Start from the background, or ask the user to do it.
     *
     * This service is started by Google Play Services, therefore **from the background**, and
     * Android 12 forbids starting a foreground service from there outside of exemptions. The
     * attempt is made anyway because it goes through in the cases where an exemption applies; when
     * it is refused, the fallback is not a makeshift to be hidden: a notification on the watch
     * gives exactly the "one tap" gesture that was sought, and it is the only path the system
     * guarantees.
     *
     * `ForegroundServiceStartNotAllowedException` only exists from Android 12 onwards; the `catch`
     * therefore covers `Exception` rather than its exact type, which would not be resolvable at
     * compile time against a lower `minSdk`. Here `minSdk` is 33, but catching broadly costs one
     * line and also covers a refusal for another reason — a service already dead, a process being
     * torn down — that nothing would tell apart at run time.
     */
    private fun startOrNotify() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START),
            )
            Log.i(TAG, "start requested by the phone")
        } catch (e: Exception) {
            Log.w(TAG, "start from the background refused, falling back to a notification", e)
            WatchNotifications.readyToStart(this)
        }
    }

    private companion object {
        const val TAG = "PendulumAck"
    }
}
