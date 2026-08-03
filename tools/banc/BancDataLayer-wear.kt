package com.pendulum.wear

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
import com.pendulum.wear.temps.Durees
import com.pendulum.wear.temps.EchelleTemps
import com.pendulum.wear.transfer.DataLayerTransfer
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Jumelle de `BancDataLayer` cote montre. Elle repond a une question que l'ecran ne sait pas
 * poser : `Preflight` melange dans un seul avertissement le resultat de `connectedNodes` et
 * l'etat reel de la liaison. Ici les deux lectures sont separees, dans le meme processus, a la
 * meme seconde — c'est la seule facon de montrer que l'une ment et que l'autre non.
 *
 * Marqueurs `BANC_*` dans logcat, etiquette `BANC`. Se deploie par `tools/banc/datalayer.sh`.
 */
@RunWith(AndroidJUnit4::class)
class BancDataLayer {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(m: String) {
        Log.i("BANC", m)
    }

    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    /** Ce que l'ecran du coucher affiche, mais lisible sans piloter l'interface. */
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
     * Les deux lectures cote a cote. `connectedNodes` est celle dont depend `PHONE_UNREACHABLE` ;
     * `getCapability(FILTER_REACHABLE)` est celle que le §7.1 propose a la place.
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

    /** Tout ce que le magasin porte sous `/pendulum`, vu de la montre. */
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
     * L'accuse tel que le telephone l'a publie, decode. C'est la seule facon de chiffrer ce que
     * la montre a **le droit** d'effacer : `applyAck` n'efface un fichier que si `isAcked(idx)`,
     * et ce booleen se lit ici, index par index, au lieu d'etre deduit de ce qui a disparu.
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
            val octets = item.data
            if (octets == null) {
                log("BANC_ACK_ITEM ${item.uri} SANS_DONNEES")
                return@forEach
            }
            val a = Ack.decode(octets)
            val acquittes = (0..maxOf(a.ackedUpTo, a.bitmapBase + a.ackedBitmap.size * 8))
                .filter { a.isAcked(it) }
            log(
                "BANC_ACK_ITEM ${item.uri} ackedUpTo=${a.ackedUpTo} base=${a.bitmapBase} " +
                    "bitmap=${a.ackedBitmap.size}o resend=${a.needResend.toList()} " +
                    "phoneMs=${a.phoneMs} acquittes=$acquittes",
            )
        }
        buf.release()
    }

    /**
     * Le diviseur de temps reellement compile dans cet APK. Il se lit ici et non dans le journal
     * de compilation : c'est l'APK installe qui compte, et rien d'autre ne prouve que les deux
     * moities du banc ont recu la meme valeur.
     */
    @Test
    fun echelle() {
        val d = Durees.ACTIVES
        log(
            "BANC_ECHELLE diviseur=${EchelleTemps.DIVISEUR} rotationChunkMs=${d.rotationChunkMs} " +
                "tickServiceMs=${d.tickServiceMs} antiRebondChargeMs=${d.antiRebondChargeMs} " +
                "dureeMaxSessionMs=${d.dureeMaxSessionMs} " +
                "delaiMinAvantHeureButoirMs=${d.delaiMinAvantHeureButoirMs}",
        )
    }

    /** Les fichiers de chunks encore sur le disque : l'invariant « rien d'efface avant l'accuse ». */
    @Test
    fun chunksSurDisque() {
        val racine = java.io.File(ctx.filesDir, "chunks")
        log("BANC_CHUNKS_RACINE ${racine.absolutePath} existe=${racine.exists()}")
        racine.listFiles()?.forEach { d ->
            val f = d.listFiles { x: java.io.File -> x.name.endsWith(".pendulum") } ?: emptyArray()
            log("BANC_CHUNKS_DIR ${d.name} n=${f.size} " +
                f.sortedBy { it.name }.joinToString { "${it.name}:${it.length()}o" })
        }
    }
}
