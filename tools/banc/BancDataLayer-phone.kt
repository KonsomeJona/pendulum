package com.pendulum.phone

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.pendulum.format.wire.WirePaths
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Sonde du Data Layer, cote telephone, executee **dans le processus `com.pendulum`**.
 *
 * Pourquoi une instrumentation et pas des taps : le telephone du banc reel est un appareil du
 * quotidien, verrouille par un code. `uiautomator` ne voit alors que le verrou, `am start` ne
 * demarre meme pas le processus (« Requires permission not exported »), et l'assistant du soir
 * est hors d'atteinte. `am instrument`, lui, ne passe pas par le verrou : le runner demarre dans
 * le processus de l'application, avec son UID, donc avec l'identite que GMS controle.
 *
 * Chaque sonde ecrit un marqueur `BANC_*` dans logcat sous l'etiquette `BANC` : ssh via Tailscale
 * ne propage pas les codes de sortie, et la sortie standard d'un test JUnit ne remonte pas dans le
 * flux d'`am instrument`. On lit logcat, jamais `$?`.
 *
 * Se deploie par `tools/banc/datalayer.sh`.
 */
@RunWith(AndroidJUnit4::class)
class BancDataLayer {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(m: String) {
        Log.i("BANC", m)
    }

    private fun uri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    /**
     * Les deux lectures que le §7.1 oppose : `connectedNodes`, qui lit une configuration, et
     * `getCapability(FILTER_REACHABLE)`, qui teste une portee. Les afficher cote a cote est tout
     * l'objet du §3 du banc reel.
     */
    @Test
    fun noeuds() {
        val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 20, TimeUnit.SECONDS)
        log("BANC_CONNECTED_NODES n=${nodes.size} " +
            nodes.joinToString { "${it.displayName}/${it.id}/nearby=${it.isNearby}" })

        val reachable = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_watch_app", CapabilityClient.FILTER_REACHABLE),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_REACHABLE n=${reachable.nodes.size} " +
            reachable.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })

        val all = Tasks.await(
            Wearable.getCapabilityClient(ctx)
                .getCapability("pendulum_watch_app", CapabilityClient.FILTER_ALL),
            20, TimeUnit.SECONDS,
        )
        log("BANC_CAP_ALL n=${all.nodes.size} " +
            all.nodes.joinToString { "${it.displayName}/nearby=${it.isNearby}" })
    }

    /**
     * Publie **exactement** l'item que produit `EveningContextSealer.publier` : meme chemin, meme
     * charge utile, meme `setUrgent()`. La difference volontaire est que la ligne `night_context`
     * n'est **pas** ecrite : elle est scellee par declencheur SQLite, donc irreversible, et le
     * banc n'a pas a laisser une soiree fausse dans la base d'un appareil du quotidien. Ce qui est
     * mesure ici est le transport, pas le formulaire.
     */
    @Test
    fun publierContexte() {
        val cle = WirePaths.nightKey(System.currentTimeMillis())
        val requete = PutDataRequest.create(WirePaths.context(cle))
            .setData(System.currentTimeMillis().toString().toByteArray())
            .setUrgent()
        val item = Tasks.await(Wearable.getDataClient(ctx).putDataItem(requete), 30, TimeUnit.SECONDS)
        log("BANC_CONTEXTE_PUBLIE cle=$cle uri=${item.uri}")
    }

    /** Le ménage : sans lui, la montre de l'utilisateur reste debloquee pour la soiree du banc. */
    @Test
    fun retirerContexte() {
        val cle = WirePaths.nightKey(System.currentTimeMillis())
        val n = Tasks.await(
            Wearable.getDataClient(ctx).deleteDataItems(uri(WirePaths.context(cle))),
            30, TimeUnit.SECONDS,
        )
        log("BANC_CONTEXTE_RETIRE cle=$cle supprimes=$n")
    }

    /**
     * Health Connect en API 37. Le §3 du banc emulateur note que `READ_HEALTH_DATA_IN_BACKGROUND`
     * et `READ_HEALTH_DATA_HISTORY` n'existent pas avant l'API 35, et que le banc y exercait donc
     * la branche degradee `BACKGROUND_READ_UNAVAILABLE`. Ici on lit ce que le produit lit.
     */
    @Test
    fun sante() {
        val statut = androidx.health.connect.client.HealthConnectClient.getSdkStatus(ctx)
        log("BANC_HC_SDK_STATUS $statut")
        val lecteur = com.pendulum.phone.health.SleepReader(ctx)
        val dispo = kotlinx.coroutines.runBlocking { lecteur.availability() }
        log("BANC_HC_AVAILABILITY $dispo")
        val pm = ctx.packageManager
        for (p in listOf(
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
        )) {
            val existe = runCatching { pm.getPermissionInfo(p, 0) != null }.getOrDefault(false)
            val accordee = pm.checkPermission(p, ctx.packageName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            log("BANC_HC_PERM $p declaree_par_la_plateforme=$existe accordee=$accordee")
        }
    }

    /** Tout ce que le magasin porte sous `/pendulum`, vu du telephone. */
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
}
