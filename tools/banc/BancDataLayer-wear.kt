package com.pendulum.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.Ack
import com.pendulum.format.wire.WirePaths
import com.pendulum.wear.record.Preflight
import com.pendulum.wear.time.Durations
import com.pendulum.wear.time.TimeScaling
import com.pendulum.wear.transfer.DataLayerTransfer
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The watch-side twin of `BancDataLayer`. It answers a question the screen cannot ask: `Preflight`
 * blends the result of `connectedNodes` and the real state of the link into a single warning. Here
 * the two readings are separated, in the same process, in the same second — that is the only way
 * to show that one of them lies and the other does not.
 *
 * `BANC_*` markers in logcat, tag `BANC`. Deployed by `tools/banc/datalayer.sh`.
 *
 * The test method names, the log markers and the keys of the log lines stay in French: the first
 * are typed as sub-commands and the others are quoted word for word in
 * `docs/workings/BENCH-LOG.md`, a dated log whose whole value is being an exact trace. The prose,
 * the Kotlin identifiers and the free text of the messages were translated.
 */
@RunWith(AndroidJUnit4::class)
class BancDataLayer {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val CUTOFF_KEY = "stop_at_minutes"

    private fun log(m: String) {
        Log.i("BANC", m)
    }

    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    /** What the bedtime screen displays, but readable without steering the interface. */
    @Test
    fun preflight() {
        val now = System.currentTimeMillis()
        val r = Preflight.check(ctx, now)
        log("BANC_PREFLIGHT cle=${WirePaths.nightKey(now)} demarrable=${r.canStart}")
        log("BANC_PREFLIGHT_BLOQUEURS " + r.blockers.joinToString { it.id.name })
        log("BANC_PREFLIGHT_AVERTISSEMENTS " + r.warnings.joinToString { it.id.name })
        log("BANC_PREFLIGHT_CONTEXTE " +
            DataLayerTransfer.isEveningContextSealed(ctx, WirePaths.nightKey(now)))
    }

    /**
     * The two readings side by side. `connectedNodes` is the one `PHONE_UNREACHABLE` depends on;
     * `getCapability(FILTER_REACHABLE)` is the one §7.1 proposes in its place.
     */
    @Test
    fun noeuds() {
        val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 20, TimeUnit.SECONDS)
        log("BANC_CONNECTED_NODES n=${nodes.size} " +
            nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" })

        val reachable = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_phone_app", CapabilityClient.FILTER_REACHABLE),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_REACHABLE n=${reachable.nodes.size} " +
            reachable.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })

        val all = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_phone_app", CapabilityClient.FILTER_ALL),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_ALL n=${all.nodes.size} " +
            all.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })
    }

    /**
     * The candidates for `Preflight.phoneReachable`, side by side, in the same second.
     *
     * Three readings and a one-way trip. The first three are deduced from a configuration or from
     * an announcement; the fourth requires that something really leaves — `sendMessage` is the
     * only Data Layer API that **fails** when the node is unreachable, where `putDataItem` buffers
     * and returns.
     *
     * The `/pendulum/banc-ping` path is declared in no phone-side filter: the message wakes no
     * component and produces no side effect. Only the trip is measured.
     */
    @Test
    fun joignabilite() {
        val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 20, TimeUnit.SECONDS)
        log("BANC_J_NODES n=${nodes.size} " +
            nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" })

        val reachable = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_phone_app", CapabilityClient.FILTER_REACHABLE),
            20, TimeUnit.SECONDS,
        )
        log("BANC_J_CAP n=${reachable.nodes.size} " +
            reachable.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })

        for (n in nodes) {
            val t0 = System.currentTimeMillis()
            val r = runCatching {
                Tasks.await(
                    Wearable.getMessageClient(ctx)
                        .sendMessage(n.id, "/pendulum/banc-ping", ByteArray(0)),
                    15, TimeUnit.SECONDS,
                )
            }
            val e = r.exceptionOrNull()
            log("BANC_J_MSG ${n.id} ok=${r.isSuccess} ms=${System.currentTimeMillis() - t0} " +
                "erreur=${e?.let { it.javaClass.simpleName + " " + it.message }}")
        }
        log("BANC_J_PREFLIGHT_ACTUEL " + Preflight.phoneReachable(nodes))
    }

    /** Everything the store carries under `/pendulum`, seen from the watch. */
    @Test
    fun listerItems() {
        val buf = Tasks.await(
            Wearable.getDataClient(ctx).getDataItems(uri("/pendulum"), DataClient.FILTER_PREFIX),
            30, TimeUnit.SECONDS,
        )
        log("BANC_ITEMS n=${buf.count}")
        buf.forEach { log("BANC_ITEM ${it.uri} ${it.data?.size ?: -1}o") }
        buf.release()
    }

    /**
     * The acknowledgement as the phone published it, decoded. This is the only way to put a figure
     * on what the watch is **entitled** to erase: `applyAck` only erases a file if `isAcked(idx)`,
     * and that boolean is read here, index by index, instead of being inferred from what has
     * disappeared.
     */
    @Test
    fun accuse() {
        val buf = Tasks.await(
            Wearable.getDataClient(ctx)
                .getDataItems(uri(WirePaths.ACK_PREFIX), DataClient.FILTER_PREFIX),
            30, TimeUnit.SECONDS,
        )
        log("BANC_ACK n=${buf.count}")
        buf.forEach { item ->
            val bytes = item.data
            if (bytes == null) {
                log("BANC_ACK_ITEM ${item.uri} NO_DATA")
                return@forEach
            }
            val a = Ack.decode(bytes)
            val acked = (0..maxOf(a.ackedUpTo, a.bitmapBase + a.ackedBitmap.size * 8))
                .filter { a.isAcked(it) }
            log(
                "BANC_ACK_ITEM ${item.uri} ackedUpTo=${a.ackedUpTo} base=${a.bitmapBase} " +
                    "bitmap=${a.ackedBitmap.size}o resend=${a.needResend.toList()} " +
                    "phoneMs=${a.phoneMs} acquittes=$acked",
            )
        }
        buf.release()
    }

    /**
     * The time divisor actually compiled into this APK. It is read here and not in the build log:
     * what counts is the installed APK, and nothing else proves that the two halves of the bench
     * received the same value.
     */
    @Test
    fun echelle() {
        val d = Durations.ACTIVE
        log(
            // The **keys** of this line stay as they are: they are quoted word for word in
            // `docs/workings/BENCH-LOG.md`, which is a dated log. Changing the output would make
            // pages of a notebook wrong whose whole value is being a trace. Only the Kotlin field
            // names, invisible in the output, followed the renaming.
            "BANC_ECHELLE diviseur=${TimeScaling.DIVISOR} rotationChunkMs=${d.chunkRotationMs} " +
                "tickServiceMs=${d.serviceTickMs} antiRebondChargeMs=${d.chargingDebounceMs} " +
                "dureeMaxSessionMs=${d.sessionMaxDurationMs} " +
                "delaiMinAvantHeureButoirMs=${d.minDelayBeforeCutoffMs}",
        )
    }

    /**
     * The local cut-off time, read and laid down. It is an ordinary user setting — `Preflight` and
     * `StopConditions` read it from `stop_at_minutes` — and the bench must be able to push it
     * aside.
     *
     * Why: `minDelayBeforeCutoffMs` **compresses** with the rest (14.4 s at scale 250) while the
     * cut-off time itself is a **local time of day**, which does not compress. Past 10 in the
     * morning, every bench recording therefore stops after 14.4 s — before the sensor FIFO, itself
     * uncompressed, has delivered its first burst at 30 s. See §11.5.5: without this setting, the
     * compressed bench cannot produce a single real sample.
     *
     * `-e minutes 1439` to push it back, `-e minutes defaut` to return the key to its absence. The
     * call without an argument only reads.
     */
    @Test
    fun heureButoir() {
        val prefs = ctx.getSharedPreferences(Preflight.PREFS, Context.MODE_PRIVATE)
        val existed = prefs.contains(CUTOFF_KEY)
        val before = prefs.getInt(CUTOFF_KEY, -1)
        when (val requested = InstrumentationRegistry.getArguments().getString("minutes")) {
            null -> Unit
            // `commit()` and not `apply()`: `am instrument` kills the process as soon as the test
            // ends, and an asynchronous write is then lost without the slightest message — the
            // setting looks laid down, it is not, and the next recording stops for the reason one
            // believed had been ruled out.
            "defaut" -> prefs.edit().remove(CUTOFF_KEY).commit()
            else -> prefs.edit().putInt(CUTOFF_KEY, requested.toInt()).commit()
        }
        log(
            "BANC_BUTOIR existait=$existed avant=$before " +
                "existe=${prefs.contains(CUTOFF_KEY)} apres=${prefs.getInt(CUTOFF_KEY, -1)}",
        )
    }

    /**
     * Re-sends the chunks present on disk, deleting their items first.
     *
     * This is **the product's own re-send path**, the one `applyAck` takes for `needResend`, and it
     * exists for a reason this bench ran into: an identical `putDataItem` is de-duplicated by the
     * Data Layer and triggers **nothing**. An item already in the phone's store but never ingested
     * would therefore stay there for ever, with no burst to wake it.
     *
     * It serves here to replay a delivery without recording another night: the files are still on
     * disk, since nothing has been acknowledged.
     */
    @Test
    fun reemettre() {
        val store = com.pendulum.wear.record.SessionStore(ctx)
        val folders = store.chunksRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        log("BANC_REEMISSION_SESSIONS n=${folders.size}")
        for (d in folders) {
            val deleted = Tasks.await(
                Wearable.getDataClient(ctx)
                    .deleteDataItems(uri(WirePaths.CHUNK_PREFIX + d.name + "/"), DataClient.FILTER_PREFIX),
                30, TimeUnit.SECONDS,
            )
            val throttled = DataLayerTransfer.pushChunks(ctx, d.name, d, urgentLast = true)
            log("BANC_REEMISSION ${d.name} items_supprimes=$deleted replafonne=$throttled")
        }
    }

    /**
     * The end-of-bench clean-up, watch side: the files of the manufactured night and its items.
     *
     * It has to be done explicitly because **nothing else will do it**: files only leave on an
     * acknowledgement, and the acknowledgement cannot arrive until §11.5.6 is fixed. Without this
     * clean-up, the user's watch carries for ever two chunks of a night that is not one, and its
     * bedtime screen displays "2 chunks from an earlier night are still waiting" every time.
     *
     * The session is passed through `-e session <hex>`: erasing "the last one" from an everyday
     * device would be a rule that gets it wrong one day.
     */
    @Test
    fun purgerBanc() {
        val hex = InstrumentationRegistry.getArguments().getString("session")
        if (hex.isNullOrBlank()) {
            log("BANC_PURGE_FAIL no session passed through -e session <hex>")
            return
        }
        val items = Tasks.await(
            Wearable.getDataClient(ctx).deleteDataItems(
                uri(WirePaths.CHUNK_PREFIX + hex + "/"), DataClient.FILTER_PREFIX,
            ),
            30, TimeUnit.SECONDS,
        )
        val session = Tasks.await(
            Wearable.getDataClient(ctx)
                .deleteDataItems(uri(WirePaths.session(hex)), DataClient.FILTER_LITERAL),
            30, TimeUnit.SECONDS,
        )
        // The live preview is published by the watch on every burst and never leaves on its own:
        // it is not part of the acknowledgement protocol. A bench night left without purging its
        // `live` leaves a kilobyte in the store of both devices, for ever.
        val live = Tasks.await(
            Wearable.getDataClient(ctx)
                .deleteDataItems(uri(WirePaths.live(hex)), DataClient.FILTER_LITERAL),
            30, TimeUnit.SECONDS,
        )
        val folder = java.io.File(java.io.File(ctx.filesDir, "chunks"), hex)
        val fileCount = folder.listFiles()?.size ?: 0
        val erased = folder.deleteRecursively()
        log(
            "BANC_PURGE hex=$hex items_chunk=$items item_session=$session item_live=$live " +
                "fichiers=$fileCount dossier_efface=$erased",
        )
    }

    /**
     * A watch -> phone delivery, as light as it can be, whose only purpose is to find out whether
     * GMS manages to **bind** to the listener service on the other side.
     *
     * The item is laid down under `/pendulum/chunk/`, the prefix the phone's filter declares, with
     * a deliberately unreadable payload: `ChunkEnvelope.decode` throws, the `catch` in
     * `onDataChanged` logs "item ignored", and **nothing is written** — no file, no database row,
     * no acknowledgement. That is exactly what is wanted of a probe running on somebody's device:
     * it measures the link, it does not manufacture state.
     *
     * The timestamp in the payload is not decorative: a `DataItem` laid down again with identical
     * bytes is **not** a change, and GMS then delivers nothing at all.
     */
    @Test
    fun livrerSonde() {
        val hex = "ba0c0000000000000000000000000000"
        val path = WirePaths.chunk(hex, 0)
        val payload = "BANC-SONDE-${System.currentTimeMillis()}".toByteArray()
        val item = Tasks.await(
            Wearable.getDataClient(ctx)
                .putDataItem(PutDataRequest.create(path).setData(payload).setUrgent()),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_PUBLIEE uri=${item.uri} ${payload.size}o")
    }

    /** Removes the item from [livrerSonde]. To be called after every measurement: nothing leaves on its own. */
    @Test
    fun retirerSonde() {
        val hex = "ba0c0000000000000000000000000000"
        val n = Tasks.await(
            Wearable.getDataClient(ctx).deleteDataItems(uri(WirePaths.chunk(hex, 0))),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_RETIREE supprimes=$n")
    }

    /** The chunk files still on disk: the "nothing erased before the acknowledgement" invariant. */
    @Test
    fun chunksSurDisque() {
        val root = java.io.File(ctx.filesDir, "chunks")
        log("BANC_CHUNKS_RACINE ${root.absolutePath} existe=${root.exists()}")
        root.listFiles()?.forEach { d ->
            val f = d.listFiles { x: java.io.File -> x.name.endsWith(".pendulum") } ?: emptyArray()
            log("BANC_CHUNKS_DIR ${d.name} n=${f.size} " +
                f.sortedBy { it.name }.joinToString { "${it.name}:${it.length()}o" })
        }
    }
}
