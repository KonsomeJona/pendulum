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

    private val CLE_BUTOIR = "stop_at_minutes"

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

    /**
     * Les candidats a `Preflight.phoneReachable`, cote a cote, a la meme seconde.
     *
     * Trois lectures et un aller simple. Les trois premieres se deduisent d'une configuration ou
     * d'une annonce ; la quatrieme exige que quelque chose parte reellement — `sendMessage` est
     * la seule API du Data Layer qui **echoue** quand le noeud n'est pas joignable, la ou
     * `putDataItem` bufferise et rend la main.
     *
     * Le chemin `/pendulum/banc-ping` n'est declare dans aucun filtre du telephone : le message
     * ne reveille aucun composant et ne produit aucun effet de bord. On ne mesure que le trajet.
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

    /**
     * L'heure butoir locale, lue et posee. C'est un reglage utilisateur ordinaire — `Preflight`
     * et `StopConditions` le lisent dans `stop_at_minutes` — et le banc doit pouvoir l'ecarter.
     *
     * Pourquoi : `delaiMinAvantHeureButoirMs` **se comprime** avec le reste (14,4 s a l'echelle
     * 250) alors que l'heure butoir elle-meme est une **heure locale**, qui ne se comprime pas.
     * Passe 10 h du matin, tout enregistrement de banc s'arrete donc au bout de 14,4 s — avant
     * que le FIFO du capteur, lui aussi non comprime, n'ait livre sa premiere salve a 30 s. Voir
     * §11.5.5 : sans ce reglage, le banc comprime ne peut produire aucun echantillon reel.
     *
     * `-e minutes 1439` pour repousser, `-e minutes defaut` pour rendre la cle a son absence.
     * L'appel sans argument ne fait que lire.
     */
    @Test
    fun heureButoir() {
        val prefs = ctx.getSharedPreferences(Preflight.PREFS, Context.MODE_PRIVATE)
        val existait = prefs.contains(CLE_BUTOIR)
        val avant = prefs.getInt(CLE_BUTOIR, -1)
        when (val demande = InstrumentationRegistry.getArguments().getString("minutes")) {
            null -> Unit
            // `commit()` et non `apply()` : `am instrument` tue le processus des la fin du
            // test, et une ecriture asynchrone se perd alors sans le moindre message —
            // le reglage semble pose, il ne l'est pas, et l'enregistrement suivant
            // s'arrete pour la raison qu'on croyait avoir ecartee.
            "defaut" -> prefs.edit().remove(CLE_BUTOIR).commit()
            else -> prefs.edit().putInt(CLE_BUTOIR, demande.toInt()).commit()
        }
        log(
            "BANC_BUTOIR existait=$existait avant=$avant " +
                "existe=${prefs.contains(CLE_BUTOIR)} apres=${prefs.getInt(CLE_BUTOIR, -1)}",
        )
    }

    /**
     * Reemet les chunks presents sur le disque, en supprimant d'abord leurs items.
     *
     * C'est **le chemin de reemission du produit**, celui de `applyAck` pour `needResend`, et il
     * existe pour une raison que ce banc a rencontree : un `putDataItem` identique est dedoublonne
     * par le Data Layer et ne declenche **rien**. Un item deja dans le magasin du telephone mais
     * jamais ingere y resterait donc pour toujours, sans qu'aucune salve ne le reveille.
     *
     * Il sert ici a rejouer une livraison sans refaire une nuit : les fichiers sont encore sur le
     * disque, puisque rien n'a ete acquitte.
     */
    @Test
    fun reemettre() {
        val store = com.pendulum.wear.record.SessionStore(ctx)
        val dossiers = store.chunksRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        log("BANC_REEMISSION_SESSIONS n=${dossiers.size}")
        for (d in dossiers) {
            val supprimes = Tasks.await(
                Wearable.getDataClient(ctx)
                    .deleteDataItems(uri(WirePaths.CHUNK_PREFIX + d.name + "/"), DataClient.FILTER_PREFIX),
                30, TimeUnit.SECONDS,
            )
            val retard = DataLayerTransfer.pushChunks(ctx, d.name, d, urgentLast = true)
            log("BANC_REEMISSION ${d.name} items_supprimes=$supprimes replafonne=$retard")
        }
    }

    /**
     * Le menage de fin de banc, cote montre : les fichiers de la nuit fabriquee et ses items.
     *
     * Il faut le faire explicitement parce que **rien ne le fera** : les fichiers ne partent que
     * sur un accuse, et l'accuse ne peut pas arriver tant que le §11.5.6 n'est pas corrige. Sans
     * ce menage, la montre de l'utilisateur porte pour toujours deux chunks d'une nuit qui n'en
     * est pas une, et son ecran du coucher affiche « 2 chunks from an earlier night are still
     * waiting » a chaque fois.
     *
     * La session est passee par `-e session <hex>` : effacer « la derniere » d'un appareil du
     * quotidien serait une regle qui se trompe un jour.
     */
    @Test
    fun purgerBanc() {
        val hex = InstrumentationRegistry.getArguments().getString("session")
        if (hex.isNullOrBlank()) {
            log("BANC_PURGE_FAIL aucune session passee par -e session <hex>")
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
        // L'apercu en direct est publie par la montre a chaque salve et ne part jamais tout seul :
        // il ne fait pas partie du protocole d'accuse. Une nuit de banc laissee sans lui purger
        // son `live` laisse un kilo-octet dans le magasin des deux appareils, pour toujours.
        val live = Tasks.await(
            Wearable.getDataClient(ctx)
                .deleteDataItems(uri(WirePaths.live(hex)), DataClient.FILTER_LITERAL),
            30, TimeUnit.SECONDS,
        )
        val dossier = java.io.File(java.io.File(ctx.filesDir, "chunks"), hex)
        val fichiers = dossier.listFiles()?.size ?: 0
        val efface = dossier.deleteRecursively()
        log(
            "BANC_PURGE hex=$hex items_chunk=$items item_session=$session item_live=$live " +
                "fichiers=$fichiers dossier_efface=$efface",
        )
    }

    /**
     * Une livraison montre -> telephone, aussi legere que possible, dont le seul objet est de
     * savoir si GMS parvient a se **lier** au service d'ecoute d'en face.
     *
     * L'item est pose sous `/pendulum/chunk/`, le prefixe que le filtre du telephone declare, avec
     * une charge utile volontairement illisible : `ChunkEnvelope.decode` leve, le `catch` de
     * `onDataChanged` journalise « item ignore », et **rien n'est ecrit** — ni fichier, ni ligne
     * de base, ni accuse. C'est exactement ce qu'on veut d'une sonde qui tourne sur l'appareil de
     * quelqu'un : elle mesure la liaison, elle ne fabrique pas d'etat.
     *
     * L'horodatage dans la charge utile n'est pas decoratif : un `DataItem` repose avec des octets
     * identiques n'est **pas** un changement, et GMS ne livre alors rien du tout.
     */
    @Test
    fun livrerSonde() {
        val hex = "ba0c0000000000000000000000000000"
        val path = WirePaths.chunk(hex, 0)
        val charge = "BANC-SONDE-${System.currentTimeMillis()}".toByteArray()
        val item = Tasks.await(
            Wearable.getDataClient(ctx)
                .putDataItem(PutDataRequest.create(path).setData(charge).setUrgent()),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_PUBLIEE uri=${item.uri} ${charge.size}o")
    }

    /** Retire l'item de [livrerSonde]. A appeler apres chaque mesure : rien ne part tout seul. */
    @Test
    fun retirerSonde() {
        val hex = "ba0c0000000000000000000000000000"
        val n = Tasks.await(
            Wearable.getDataClient(ctx).deleteDataItems(uri(WirePaths.chunk(hex, 0))),
            30, TimeUnit.SECONDS,
        )
        log("BANC_SONDE_RETIREE supprimes=$n")
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
