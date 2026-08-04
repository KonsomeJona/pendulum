package com.pendulum.wear.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import java.util.Locale
import android.util.Log
import androidx.core.content.ContextCompat
import com.pendulum.wear.transfer.DataLayerTransfer
import java.io.File

/**
 * Verification d'avant-nuit. C'est le seul moment ou l'utilisateur regarde la montre, donc le
 * seul moment ou un message a une chance d'etre lu : chaque bloqueur est formule en action, et
 * la separation bloqueurs / avertissements est une decision, pas une nuance de couleur.
 *
 * **Le telephone injoignable n'est jamais bloquant.** Toute l'architecture de transfert existe
 * pour que ce cas soit sans consequence : le signaler comme une erreur serait mentir a
 * l'utilisateur et l'inciter a ne pas enregistrer sa nuit.
 */
object Preflight {

    /** En dessous, on refuse de commencer : une nuit pese ~9 Mo, mais la marge protege des
     *  reliquats non acquittes qu'on ne supprimera jamais de force. */
    const val MIN_FREE_BYTES = 300L * 1024 * 1024

    /** Plafond du repertoire de chunks : ~22 nuits. */
    const val CHUNK_DIR_CAP_BYTES = 200L * 1024 * 1024

    const val PREFS = "pendulum"

    /** Pose par le service quand `startForeground` a ete refuse : le seul moyen de faire
     *  remonter a l'ecran du coucher un echec qui, sinon, ne vit que dans logcat. */
    const val PREF_FGS_REFUSED = "fgs_refused"

    /** Pose par le service quand [echelleDesaccordee] a refuse un demarrage. Meme mecanique que
     *  [PREF_FGS_REFUSED], et pour la meme raison : le refus se decide au moment ou la source est
     *  connue, c'est-a-dire trop tard pour l'ecran qui l'a declenche. */
    const val PREF_ECHELLE_DESACCORDEE = "echelle_desaccordee"

    /**
     * **Le diviseur de temps du banc ne s'applique qu'au rejeu synthetique.** Vrai quand une
     * compilation compressee s'appreterait a enregistrer le vrai capteur, ce qui ne comprime rien
     * et desaccorde tout.
     *
     * La compression est celle du temps **mural** : elle divise les durees de `Durees`. Le rejeu
     * de `SourceSynthetique` compresse en face le temps **capteur**, du meme facteur, et c'est
     * cette egalite que `CoherenceEchelleTest` protege. Avec l'accelerometre reel il n'y a aucun
     * rejeu : le temps capteur avance a 1x pendant que le temps mural avance a 250x, et le facteur
     * n'a plus rien a egaliser.
     *
     * Ce que ca produit, mesure et non redoute (`BANC-ESSAI.md` §11.5.3) : la latence de salve du
     * FIFO vaut 30 s de temps capteur, materielle et non comprimable, tandis que le delai de garde
     * de l'heure butoir tombe de 1 h a 14,4 s. L'enregistrement s'arrete donc **avant** que le
     * capteur n'ait livre son premier octet — 14,636 s, zero chunk, zero message. Un banc qui se
     * desaccorde en silence est pire qu'un banc absent : il rend des chiffres.
     *
     * Le choix est de **refuser de demarrer** plutot que de neutraliser le diviseur a l'execution.
     * Neutraliser reviendrait a faire tourner une compilation qui n'est pas celle qu'on croit
     * lancer, et `Durees.ACTIVES` est un catalogue construit une fois pour toutes a partir d'une
     * valeur compilee : il n'y a pas d'endroit honnete ou le corriger. Refuser dit quoi faire.
     */
    fun echelleDesaccordee(diviseur: Long, sourceSynthetique: Boolean): Boolean =
        diviseur != 1L && !sourceSynthetique

    /**
     * @param nowMs l'horloge, en parametre plutot que lue au fond de la fonction. C'est elle qui
     *   determine la cle de nuit, donc *quel* contexte du soir est cherche : le defaut qui a
     *   coute le plus cher a ce produit — un contexte scelle sous une cle et lu sous une autre —
     *   se rejoue ici a une milliseconde pres, et sans ce parametre il n'est pas reproductible.
     */
    fun check(ctx: Context, nowMs: Long = System.currentTimeMillis()): PreflightResult {
        val blockers = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()

        val sm = ctx.getSystemService(SensorManager::class.java)
        val wakeUp = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val sensor = wakeUp ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) blockers += Issue(IssueId.NO_ACCELEROMETER)

        // Sans notification, le systeme ne peut pas afficher le service de premier plan, et il
        // finit par l'arreter : la permission n'est pas cosmetique.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            blockers += Issue(IssueId.NOTIFICATIONS_DENIED)
        }

        val store = SessionStore(ctx)
        val pending = pendingChunkCount(store.chunksRoot)
        val free = ctx.filesDir.usableSpace
        val occupied = dirSize(store.chunksRoot)
        if (free < MIN_FREE_BYTES || occupied > CHUNK_DIR_CAP_BYTES * 95 / 100) {
            // Refuser de commencer une nuit vaut mieux que d'en ecraser une silencieusement.
            blockers += Issue(IssueId.STORAGE_FULL, listOf(formatBytes(free), pending.toString()))
        }

        val nightKey = DataLayerTransfer.nightKey(nowMs)
        val sealed = try {
            DataLayerTransfer.isEveningContextSealed(ctx, nightKey)
        } catch (e: Exception) {
            Log.w(TAG, "lecture du verrou de contexte impossible", e)
            false
        }
        if (!sealed) blockers += Issue(IssueId.CONTEXT_NOT_SEALED)

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FGS_REFUSED, false)) {
            blockers += Issue(IssueId.FGS_REFUSED)
        }
        if (prefs.getBoolean(PREF_ECHELLE_DESACCORDEE, false)) {
            blockers += Issue(
                IssueId.BENCH_SCALE_MISMATCH,
                listOf(com.pendulum.wear.temps.EchelleTemps.DIVISEUR.toString()),
            )
        }

        val bm = ctx.getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (battery in 0..39) warnings += Issue(IssueId.LOW_BATTERY, listOf(battery.toString()))
        if (sensor != null && wakeUp == null) warnings += Issue(IssueId.NO_WAKEUP_SENSOR)
        if (pending > 0) warnings += Issue(IssueId.PENDING_SYNC, listOf(pending.toString()))
        if (!phoneReachable(ctx)) warnings += Issue(IssueId.PHONE_UNREACHABLE)

        return PreflightResult(blockers, warnings, battery, free, pending)
    }

    private fun phoneReachable(ctx: Context): Boolean = try {
        val nodes = com.google.android.gms.tasks.Tasks.await(
            com.google.android.gms.wearable.Wearable.getNodeClient(ctx).connectedNodes,
            10,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        phoneReachable(nodes)
    } catch (e: Exception) {
        false
    }

    /**
     * **La liste des noeuds non vide, et rien de plus.** Extrait de son appelant pour etre
     * testable sans GMS : c'est un predicat sur une liste, et c'est la seule partie qui puisse
     * se tromper.
     *
     * ### Ce qu'on sait de ce predicat, et ce qu'on ne sait pas
     *
     * Sur emulateur, il ment : l'emulateur telephone tue, `connectedNodes` rend toujours la montre
     * appairee et l'avertissement `PHONE_UNREACHABLE` ne s'affiche jamais (§7.1). C'est le defaut
     * connu, et il n'a **pas encore ete reproduit sur materiel reel** — faute d'avoir pu produire
     * un telephone reellement injoignable sans perdre le lien `adb` qui sert a le mesurer.
     *
     * ### Les deux corrections proposees, et pourquoi aucune n'est appliquee
     *
     * `getCapability(…, FILTER_REACHABLE)` : rend un noeud dans exactement les memes cas. Mesure
     * du §11.3.
     *
     * `nodes.any { it.isNearby }` : **faux rouge mesure** (§12.2). Bluetooth coupe et les deux
     * appareils sur le meme WiFi, `isNearby` passe a `false` des deux cotes — et le Data Layer
     * continue de transporter : un item publie par le telephone arrive sur la montre en moins de
     * 45 s, une suppression en moins de 60 s. La liaison bascule sur le WiFi, ce que « proximite »
     * ne decrit plus. Ce que le §11.3 lisait comme un transport mort etait une fenetre d'attente
     * de 45 s trop courte. Appliquer cette correction ferait afficher « telephone injoignable »
     * pendant que la synchronisation se fait.
     *
     * `MessageClient.sendMessage`, seule API du Data Layer qui echoue quand le noeud est hors de
     * portee : mesuree a `ok=true` en 8 a 13 ms dans **tous** les etats radio produits, y compris
     * ceux ou `isNearby` valait `false`. Onze millisecondes ne sont pas un aller-retour : c'est
     * une acceptation locale. Aucun etat reellement injoignable n'ayant pu etre produit, rien ne
     * dit qu'elle echouerait, et on ne remplace pas un predicat par un autre sur une intuition.
     *
     * L'avertissement n'est de toute facon jamais bloquant : il dit « recording carries on, sync
     * will happen later », ce qui reste vrai dans les deux sens d'erreur.
     */
    fun phoneReachable(nodes: List<com.google.android.gms.wearable.Node>): Boolean =
        nodes.isNotEmpty()

    fun pendingChunkCount(chunksRoot: File): Int =
        chunksRoot.listFiles()?.sumOf { dir ->
            dir.listFiles { f: File -> f.name.endsWith(".pendulum") }?.size ?: 0
        } ?: 0

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.UK, bytes / 1024.0 / 1024 / 1024)
        else -> "%d MB".format(Locale.UK, bytes / 1024 / 1024)
    }

    private const val TAG = "PendulumPreflight"
}

enum class IssueId {
    // bloqueurs
    CONTEXT_NOT_SEALED,
    NOTIFICATIONS_DENIED,
    NO_ACCELEROMETER,
    STORAGE_FULL,
    FGS_REFUSED,

    /** Compilation de banc — temps mural comprime — sur le vrai capteur. Voir
     *  [Preflight.echelleDesaccordee]. N'existe pas en release : le diviseur y vaut 1. */
    BENCH_SCALE_MISMATCH,

    // avertissements
    LOW_BATTERY,
    PHONE_UNREACHABLE,
    NO_WAKEUP_SENSOR,
    PENDING_SYNC,
}

/**
 * Ce qui est **casse**, par opposition a ce qui n'est **pas encore fait**.
 *
 * Bloquer le demarrage et etre une panne sont deux choses differentes, et la couleur encode la
 * seconde, pas la premiere. La regle est celle de `PendulumColors` cote telephone : le rouge est
 * reserve a ce qui est casse ; une situation que l'utilisateur peut lever lui-meme est ambre.
 *
 * Le defaut repare ici se voyait en mettant les deux ecrans cote a cote : pour le **meme** fait —
 * le contexte du soir pas encore scelle — le telephone affichait de l'ambre et la montre du rouge.
 * Deux appareils, deux verdicts, un seul etat. Le telephone avait raison : remplir un formulaire
 * qu'on n'a pas encore rempli n'est pas une panne.
 *
 * `NO_ACCELEROMETER`, `STORAGE_FULL` et `FGS_REFUSED` restent rouges : l'utilisateur ne peut rien
 * y faire depuis cet ecran. `BENCH_SCALE_MISMATCH` aussi — une compilation de banc branchee sur le
 * vrai capteur produirait des mesures fausses, et c'est le pire cas silencieux du projet.
 */
val IssueId.estUnePanne: Boolean
    get() = when (this) {
        IssueId.NO_ACCELEROMETER,
        IssueId.STORAGE_FULL,
        IssueId.FGS_REFUSED,
        IssueId.BENCH_SCALE_MISMATCH -> true

        IssueId.CONTEXT_NOT_SEALED,
        IssueId.NOTIFICATIONS_DENIED -> false

        // Les avertissements sont ambre par construction ; la question ne se pose pas pour eux.
        IssueId.LOW_BATTERY,
        IssueId.PHONE_UNREACHABLE,
        IssueId.NO_WAKEUP_SENSOR,
        IssueId.PENDING_SYNC -> false
    }

/** Un probleme et ses arguments deja formates. Les libelles vivent dans `strings.xml`. */
data class Issue(val id: IssueId, val args: List<String> = emptyList())

data class PreflightResult(
    val blockers: List<Issue>,
    val warnings: List<Issue>,
    val batteryPct: Int,
    val freeBytes: Long,
    val pendingChunks: Int,
) {
    val canStart: Boolean get() = blockers.isEmpty()
}
