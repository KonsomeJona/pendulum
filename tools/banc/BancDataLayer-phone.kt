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

    /**
     * Le diviseur de temps reellement compile dans **cet** APK.
     *
     * Rien dans le code ne peut verifier que les deux moities du banc ont recu la meme valeur :
     * `EchelleTemps` est un objet par module, alimente par une propriete Gradle, et deux
     * invocations distinctes de `gradlew` produiraient sans un mot deux applications a des
     * echelles differentes. La verification est donc **externe** : on lit la valeur des deux
     * cotes et on les compare. Son jumeau est `BancDataLayer#echelle` cote montre.
     */
    @Test
    fun echelle() {
        val d = com.pendulum.phone.temps.Durees.ACTIVES
        log(
            "BANC_ECHELLE diviseur=${com.pendulum.phone.temps.EchelleTemps.DIVISEUR} " +
                "abandonLectureMs=${d.abandonLectureMs} ageMaxNuitMs=${d.ageMaxNuitMs} " +
                "silenceAvantStaleMs=${d.silenceAvantStaleMs}",
        )
    }

    /**
     * Les lignes que l'ingestion a ecrites, lues **dans la base** et non deduites des fichiers.
     *
     * C'est la moitie telephone de l'invariant : `AckBuilder.build` ne recoit que les index
     * `complete=1` de cette table, donc ce qui n'est pas ici ne peut pas etre acquitte, donc ne
     * peut pas etre efface de la montre. Le `sqlite3` de la ligne de commande n'existe pas sur
     * cet appareil ; la lecture passe donc par le meme `openHelper` que le produit.
     */
    @Test
    fun base() {
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx).openHelper.readableDatabase
        db.query(
            "SELECT sessionHex, nightKey, state, totalChunks, startWallMs, endWallMs, stopReason " +
                "FROM night_session ORDER BY startWallMs",
        ).use { c ->
            log("BANC_DB_SESSIONS n=${c.count}")
            while (c.moveToNext()) {
                log(
                    "BANC_DB_SESSION hex=${c.getString(0)} cle=${c.getString(1)} " +
                        "etat=${c.getString(2)} totalChunks=${c.getInt(3)} " +
                        "debut=${c.getLong(4)} fin=${if (c.isNull(5)) "-" else c.getLong(5)} " +
                        "raison=${c.getString(6) ?: "-"}",
                )
            }
        }
        db.query(
            "SELECT sessionHex, idx, size, crc32, sampleCount, complete, receivedAtMs " +
                "FROM chunk ORDER BY sessionHex, idx",
        ).use { c ->
            log("BANC_DB_CHUNKS n=${c.count}")
            while (c.moveToNext()) {
                log(
                    "BANC_DB_CHUNK hex=${c.getString(0)} idx=${c.getInt(1)} taille=${c.getInt(2)} " +
                        "crc32=${c.getLong(3)} echantillons=${c.getInt(4)} " +
                        "complet=${c.getInt(5)} recuA=${c.getLong(6)}",
                )
            }
        }
    }

    /**
     * Le menage de fin de banc : la nuit fabriquee, ses chunks en base et ses fichiers.
     *
     * Elle est passee par `-e session <hex>` plutot que devinee : effacer « la derniere nuit »
     * d'une base qui est celle d'un appareil du quotidien serait une regle qui se trompe un jour.
     * La suppression de `night_session` emporte les lignes `chunk` par cle etrangere en cascade.
     */
    @Test
    fun purgerBanc() {
        val hex = InstrumentationRegistry.getArguments().getString("session")
        if (hex.isNullOrBlank()) {
            log("BANC_PURGE_FAIL aucune session passee par -e session <hex>")
            return
        }
        val db = com.pendulum.phone.db.PendulumDatabase.get(ctx).openHelper.writableDatabase
        val avant = db.query("SELECT count(*) FROM chunk WHERE sessionHex='$hex'")
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        db.execSQL("DELETE FROM night_session WHERE sessionHex='$hex'")
        val apres = db.query("SELECT count(*) FROM chunk WHERE sessionHex='$hex'")
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        val dossier = java.io.File(java.io.File(ctx.filesDir, "chunks"), hex)
        val fichiers = dossier.listFiles()?.size ?: 0
        val efface = dossier.deleteRecursively()
        log("BANC_PURGE hex=$hex chunks_avant=$avant chunks_apres=$apres " +
            "fichiers=$fichiers dossier_efface=$efface")
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
