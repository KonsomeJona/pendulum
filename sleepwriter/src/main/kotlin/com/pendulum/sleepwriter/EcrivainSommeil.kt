package com.pendulum.sleepwriter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ce que l'outil sait faire. Une action par lancement, jamais deux. */
enum class Mode(val cle: String) {
    /** Ecrit une session selon le scenario. */
    ECRIRE("write"),

    /** Relit et journalise, sans rien ecrire. C'est l'oracle du banc. */
    VERIFIER("verify"),

    /** Rend l'etat des permissions, sans rien ecrire ni lire. */
    PERMISSIONS("permissions"),

    /**
     * Efface les sessions ecrites **par cette application** dans la fenetre.
     *
     * Health Connect n'autorise la suppression que de ses propres enregistrements, ce qui rend ce
     * mode inoffensif pour les donnees d'une autre source. Il existe parce qu'un emulateur
     * recharge depuis un instantane garde les nuits du scenario precedent : sans lui, le scenario
     * `rien` (`E-HC-01`) ne signifie rien du tout la deuxieme fois qu'on le lance.
     */
    PURGER("purge");

    companion object {
        fun depuis(cle: String?): Mode? = entries.firstOrNull { it.cle == cle }
    }
}

/** Les quatre situations que Pendulum doit savoir encaisser cote denominateur. */
enum class Scenario(val cle: String) {
    /** Nuit complete avec stades. Voir [Hypnogramme]. */
    NUIT_COMPLETE("nuit-complete"),

    /**
     * Duree seule, sans aucun stade.
     *
     * Ce n'est pas un cas degrade exotique : beaucoup d'appareils grand public n'ecrivent que
     * cela. `Hypnogram.toWindows` produit alors une unique fenetre `SLEEP`, `SleepSourceSelector`
     * rend le verdict `STADES_ABSENTS`, et Pendulum doit le **dire** plutot que de ventiler par
     * stade une information qu'il n'a pas.
     */
    DUREE_SEULE("duree-seule"),

    /**
     * Rien du tout, pour `E-HC-01`.
     *
     * Scenario par omission : il n'y a rien a ecrire. Il existe pour que le script du banc soit
     * symetrique — un scenario par situation, meme quand la situation est une absence — et pour
     * que la trace de journal atteste qu'on a bien voulu ce vide plutot que de l'avoir subi.
     */
    RIEN("rien"),

    /**
     * Deux sources qui se chevauchent, pour `E-HC-03`.
     *
     * La variante A ecrit la nuit complete avec ses stades, la variante B une duree seule plus
     * courte et decalee. `SleepSourceSelector` doit retenir A par le critere « plus de types de
     * stade distincts » et non par un hasard d'ordre de retour de l'API. Les durees sont
     * differentes exprès : deux sources identiques ne prouveraient pas quelle a ete choisie.
     */
    CONFLIT("conflit");

    companion object {
        fun depuis(cle: String?): Scenario? = entries.firstOrNull { it.cle == cle }
    }
}

/**
 * @param delaiMs attente **en temps reel** avant d'ecrire. C'est ainsi que le banc simule
 *   l'hypnogramme qui arrive apres le reveil — cas **normal** et non panne : la montre de poignet
 *   ne transfere pas sa nuit au reveil mais quand sa politique de batterie le decide. Le banc
 *   comprime six heures en quelques secondes ; il ne decale pas la fenetre enregistree, qui reste
 *   celle de la nuit.
 * @param margeMin marge de lecture pour [Mode.VERIFIER], de part et d'autre de la fenetre. Meme
 *   valeur par defaut que `SleepReader.read` : une session de sommeil commence avant que la montre
 *   de cheville ne demarre et finit apres son arret.
 */
data class Requete(
    val mode: Mode,
    val scenario: Scenario,
    val debutMs: Long,
    val finMs: Long,
    val delaiMs: Long = 0L,
    val margeMin: Long = 180L,
)

/**
 * L'ecriture et la relecture dans Health Connect.
 *
 * ### Ce que ce fichier suppose de Health Connect, faute d'avoir pu l'executer
 *
 * Trois comportements sont deduits de la documentation et non mesures ; ils sont ecrits ici pour
 * que le premier qui execute le banc sache ou regarder si quelque chose casse :
 *  1. **une session dont la fin est dans le futur est refusee.** L'ecriture leve alors une
 *     exception plutot que d'ecrire un enregistrement decale. Le cas est detecte et signale par le
 *     champ `warn`, mais l'ecriture est tout de meme tentee : masquer l'erreur reelle de Health
 *     Connect derriere un refus maison rendrait le diagnostic plus difficile, pas plus facile ;
 *  2. **les stades doivent tenir dans la session, ne pas se chevaucher et etre ordonnes.**
 *     [Hypnogramme] garantit les trois par construction ;
 *  3. **`clientRecordId` fait une mise a jour et non un doublon.** C'est ce qui rend une commande
 *     rejouable : relancer exactement la meme ecriture remplace l'enregistrement au lieu de creer
 *     une seconde session qui se chevauche elle-meme — ce qui fabriquerait un faux `E-HC-03` avec
 *     une seule application.
 */
class EcrivainSommeil(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    /** Execute la requete et rend la ligne de journal emise. */
    suspend fun executer(r: Requete): String = when (r.mode) {
        Mode.PERMISSIONS -> permissions()
        Mode.ECRIRE -> ecrire(r)
        Mode.VERIFIER -> verifier(r)
        Mode.PURGER -> purger(r)
    }

    private suspend fun permissions(): String {
        val statutSdk = when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "AVAILABLE"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "UPDATE_REQUIRED"
            else -> "UNAVAILABLE"
        }
        val c = client
        val accordees = if (c == null) emptySet() else runCatching {
            c.permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        val ecriture = PERMISSION_ECRITURE in accordees
        val lecture = PERMISSION_LECTURE in accordees
        return emettre(
            Mode.PERMISSIONS,
            if (c == null) Journal.Statut.HC_INDISPONIBLE
            else if (ecriture && lecture) Journal.Statut.OK
            else Journal.Statut.PERMISSION_MANQUANTE,
            listOf(
                "sdk" to statutSdk,
                "write" to if (ecriture) "GRANTED" else "DENIED",
                "read" to if (lecture) "GRANTED" else "DENIED",
            ),
        )
    }

    private suspend fun ecrire(r: Requete): String {
        val champsFenetre = fenetre(r)

        if (r.scenario == Scenario.RIEN) {
            return emettre(Mode.ECRIRE, Journal.Statut.RIEN_A_ECRIRE, champsFenetre + listOf("records" to 0))
        }
        val c = client
            ?: return emettre(Mode.ECRIRE, Journal.Statut.HC_INDISPONIBLE, champsFenetre)
        if (PERMISSION_ECRITURE !in runCatching {
                c.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
        ) {
            return emettre(Mode.ECRIRE, Journal.Statut.PERMISSION_MANQUANTE, champsFenetre)
        }
        if (r.finMs <= r.debutMs) {
            return emettre(Mode.ECRIRE, Journal.Statut.PARAMETRE_INVALIDE, champsFenetre)
        }

        // Le scenario de conflit ecrit deux choses differentes selon la variante. Le code est le
        // meme dans les deux APK ; c'est `BuildConfig.SOURCE_LABEL` qui tranche, parce que ce qui
        // distingue les deux sources n'est pas leur comportement mais leur nom de paquet.
        val estSourceB = BuildConfig.SOURCE_LABEL == "B"
        val debut = if (r.scenario == Scenario.CONFLIT && estSourceB) {
            r.debutMs + 45 * 60_000L
        } else {
            r.debutMs
        }
        val fin = if (r.scenario == Scenario.CONFLIT && estSourceB) {
            r.finMs - 30 * 60_000L
        } else {
            r.finMs
        }
        val avecStades = when (r.scenario) {
            Scenario.NUIT_COMPLETE -> true
            Scenario.DUREE_SEULE -> false
            Scenario.CONFLIT -> !estSourceB
            Scenario.RIEN -> false
        }

        val stades = if (avecStades) Hypnogramme.nuitComplete(debut, fin) else emptyList()
        val zone = ZoneId.systemDefault()
        val instantDebut = Instant.ofEpochMilli(debut)
        val instantFin = Instant.ofEpochMilli(fin)

        val enregistrement = SleepSessionRecord(
            startTime = instantDebut,
            startZoneOffset = zone.rules.getOffset(instantDebut),
            endTime = instantFin,
            endZoneOffset = zone.rules.getOffset(instantFin),
            title = "Banc Pendulum ${BuildConfig.SOURCE_LABEL}",
            notes = r.scenario.cle,
            stages = stades.map {
                SleepSessionRecord.Stage(
                    startTime = Instant.ofEpochMilli(it.debutMs),
                    endTime = Instant.ofEpochMilli(it.finMs),
                    stage = it.type,
                )
            },
            // `autoRecorded` et `TYPE_WATCH` : c'est ce qu'ecrirait une montre de poignet, et la
            // methode d'enregistrement est un champ que Health Connect expose aux lecteurs. Un
            // `manualEntry` ferait passer le banc pour une saisie a la main, ce qu'aucune source
            // reelle de denominateur n'est.
            metadata = Metadata.autoRecorded(
                device = Device(
                    type = Device.TYPE_WATCH,
                    manufacturer = "Pendulum",
                    model = "SleepWriter${BuildConfig.SOURCE_LABEL}",
                ),
                clientRecordId = "pendulum-banc-${r.scenario.cle}-$debut-$fin",
                clientRecordVersion = System.currentTimeMillis(),
            ),
        )

        val futur = if (fin > System.currentTimeMillis()) "window-in-the-future" else null

        return runCatching { c.insertRecords(listOf(enregistrement)) }.fold(
            onSuccess = { reponse ->
                emettre(
                    Mode.ECRIRE, Journal.Statut.OK,
                    champsFenetre + listOf(
                        "records" to 1,
                        "stages" to stades.size,
                        "writtenStartMs" to debut,
                        "writtenEndMs" to fin,
                        "writtenDurationMin" to (fin - debut) / 60_000L,
                        "ids" to reponse.recordIdsList.joinToString(";"),
                        "warn" to futur,
                    ),
                )
            },
            onFailure = { erreur ->
                emettre(
                    Mode.ECRIRE, Journal.Statut.ERREUR,
                    champsFenetre + listOf(
                        "records" to 0,
                        "warn" to futur,
                        "err" to "${erreur.javaClass.simpleName}:${erreur.message}",
                    ),
                )
            },
        )
    }

    /**
     * Relit **toutes** les sources de la fenetre, pas seulement la sienne.
     *
     * Ne relire que ses propres enregistrements suffirait a dire « l'ecriture a abouti », mais pas
     * a dire ce que Pendulum va voir. Le champ `origins` porte donc une source par element, avec
     * son nombre d'enregistrements et de stades : c'est exactement l'entree de
     * `SleepSourceSelector`, et c'est ce qui permet a un script de verifier qu'un scenario de
     * conflit a bien produit deux origines et non une seule ecrite deux fois.
     */
    private suspend fun verifier(r: Requete): String {
        val c = client
            ?: return emettre(Mode.VERIFIER, Journal.Statut.HC_INDISPONIBLE, fenetre(r))
        if (PERMISSION_LECTURE !in runCatching {
                c.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
        ) {
            return emettre(Mode.VERIFIER, Journal.Statut.PERMISSION_MANQUANTE, fenetre(r))
        }

        val de = Instant.ofEpochMilli(r.debutMs).minusSeconds(r.margeMin * 60)
        val a = Instant.ofEpochMilli(r.finMs).plusSeconds(r.margeMin * 60)

        val enregistrements = runCatching { lire(c, de, a) }.getOrElse { erreur ->
            return emettre(
                Mode.VERIFIER, Journal.Statut.ERREUR,
                fenetre(r) + listOf("err" to "${erreur.javaClass.simpleName}:${erreur.message}"),
            )
        }

        val parOrigine = enregistrements.groupBy { it.metadata.dataOrigin.packageName }
        val origines = parOrigine.entries.sortedBy { it.key }.joinToString(";") { (paquet, liste) ->
            "$paquet:${liste.size}:${liste.sumOf { it.stages.size }}"
        }
        val tstMin = enregistrements.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } / 60_000L

        return emettre(
            Mode.VERIFIER,
            if (enregistrements.isEmpty()) Journal.Statut.RIEN_A_ECRIRE else Journal.Statut.OK,
            fenetre(r) + listOf(
                "marginMin" to r.margeMin,
                "records" to enregistrements.size,
                "stages" to enregistrements.sumOf { it.stages.size },
                "sumDurationMin" to tstMin,
                "mine" to (parOrigine[context.packageName]?.size ?: 0),
                "origins" to origines,
            ),
        )
    }

    private suspend fun purger(r: Requete): String {
        val c = client
            ?: return emettre(Mode.PURGER, Journal.Statut.HC_INDISPONIBLE, fenetre(r))
        val de = Instant.ofEpochMilli(r.debutMs).minusSeconds(r.margeMin * 60)
        val a = Instant.ofEpochMilli(r.finMs).plusSeconds(r.margeMin * 60)
        return runCatching {
            c.deleteRecords(SleepSessionRecord::class, TimeRangeFilter.between(de, a))
        }.fold(
            onSuccess = { emettre(Mode.PURGER, Journal.Statut.OK, fenetre(r)) },
            onFailure = {
                emettre(
                    Mode.PURGER, Journal.Statut.ERREUR,
                    fenetre(r) + listOf("err" to "${it.javaClass.simpleName}:${it.message}"),
                )
            },
        )
    }

    /**
     * Lecture paginee. Meme raison que dans `SleepReader.readCandidates` : la taille de page par
     * defaut est de 1000 et une reponse pleine ne se signale que par un `pageToken` non nul. Lire
     * la premiere page seulement tronque en silence — et un oracle qui tronque en silence est pire
     * qu'une absence d'oracle.
     */
    private suspend fun lire(
        c: HealthConnectClient,
        de: Instant,
        a: Instant,
    ): List<SleepSessionRecord> = buildList {
        var page: String? = null
        do {
            val reponse = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(de, a),
                    pageToken = page,
                )
            )
            addAll(reponse.records)
            page = reponse.pageToken
        } while (page != null)
    }

    private fun fenetre(r: Requete): List<Pair<String, Any?>> = listOf(
        "scenario" to r.scenario.cle,
        "source" to BuildConfig.SOURCE_LABEL,
        "pkg" to context.packageName,
        "startMs" to r.debutMs,
        "endMs" to r.finMs,
        "start" to ISO.format(Instant.ofEpochMilli(r.debutMs)),
        "end" to ISO.format(Instant.ofEpochMilli(r.finMs)),
        "durationMin" to (r.finMs - r.debutMs) / 60_000L,
    )

    private fun emettre(mode: Mode, statut: String, champs: List<Pair<String, Any?>>): String =
        Journal.emettre(
            Journal.RESULTAT,
            listOf<Pair<String, Any?>>("mode" to mode.cle, "status" to statut) + champs,
        )

    companion object {

        val PERMISSION_ECRITURE: String =
            HealthPermission.getWritePermission(SleepSessionRecord::class)

        val PERMISSION_LECTURE: String =
            HealthPermission.getReadPermission(SleepSessionRecord::class)

        /**
         * Les deux permissions demandees ensemble.
         *
         * En une seule demande et non deux : chaque passage par l'ecran de consentement de Health
         * Connect est une sequence UiAutomator a piloter, et deux sequences valent deux fois plus
         * d'occasions de taper a cote sans qu'aucune erreur ne le dise.
         */
        val PERMISSIONS: Set<String> = setOf(PERMISSION_ECRITURE, PERMISSION_LECTURE)

        /**
         * Contrat de demande de permission.
         *
         * `pm grant` **ne fonctionne pas** pour les permissions de sante : elles sont gerees par un
         * module Mainline avec sa propre interface de consentement, et la commande echoue ou
         * n'a aucun effet. Ce contrat est le seul chemin, exactement comme pour la lecture cote
         * `:phone` (`SleepReader.permissionRequestContract`).
         */
        fun contratDePermission() = PermissionController.createRequestPermissionResultContract()

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
