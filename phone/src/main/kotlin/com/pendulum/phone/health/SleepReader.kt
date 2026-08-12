package com.pendulum.phone.health

import android.content.Context
import android.os.Build
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Lecture du sommeil dans Health Connect.
 *
 * ### Pourquoi une source externe, et pas la montre de cheville
 *
 * Le PLMI est une fraction : mouvements / heures de sommeil. La montre de cheville produit tres
 * bien le numerateur et **ne peut pas** produire honnetement le denominateur — l'actigraphie
 * deduit « endormi/eveille » d'une statistique de mouvement, or l'evenement qu'on compte *est*
 * un mouvement. Chaque salve de PLMS pousserait l'algorithme a declarer « eveil » : le
 * numerateur monte pendant que le denominateur descend, et l'erreur sur le rapport est doublee
 * dans la meme direction. C'est un biais systematique, pas du bruit : il ne se moyenne pas sur
 * plusieurs nuits.
 *
 * La bonne nouvelle est solide : ce dont on a besoin (la frontiere sommeil/eveil, donc le TST)
 * est ce que les montres grand public font le **mieux** (sensibilite ~0,95) ; ce qu'elles font
 * mal (les stades, kappa 0,34-0,47) est ce dont on a le **moins** besoin — le PLMI ne pondere
 * pas par stade. Les stades servent au controle de plausibilite biologique, pas au chiffre.
 *
 * ### Le biais a connaitre et a nommer
 *
 * Specificite ~0,52 : la montre declare « endormi » pres d'une epoque d'eveil sur deux, donc
 * **surestime** le TST. Le TST etant au denominateur, l'index en sort structurellement
 * **sous-estime**. Ce biais s'oppose a celui, a la hausse, des mouvements lies a la respiration.
 * Il ne faut surtout pas pretendre qu'ils se compensent : ils se nomment separement.
 *
 * ### Les deux permissions
 *
 * `READ_SLEEP` **et** `READ_HEALTH_DATA_IN_BACKGROUND`. La seconde n'est pas un supplement de
 * confort : `SleepFetchWorker` tourne par construction application fermee, et sans elle la
 * lecture echoue hors premier plan. C'est un manque de la SPEC v1, corrige ici.
 */
class SleepReader(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    /**
     * Etat de la lecture. Cinq issues, et elles ne se reparent pas de la meme facon : c'est
     * pourquoi ce n'est pas un booleen.
     */
    enum class Availability {
        /** Health Connect present, permissions accordees, lecture de fond disponible. */
        READY,

        /** Health Connect absent ou trop ancien sur cet appareil. */
        SDK_UNAVAILABLE,

        /** Present mais une mise a jour est requise avant utilisation. */
        UPDATE_REQUIRED,

        /** Permissions non accordees : c'est un geste utilisateur, pas une erreur. */
        PERMISSIONS_MISSING,

        /**
         * Permissions accordees mais la lecture en arriere-plan n'est **pas** disponible sur
         * cette version. Cas a traiter a part : planifier `SleepFetchWorker` malgre tout
         * produirait des echecs silencieux toute la nuit. Voir `hasBackgroundReadFeature`.
         */
        BACKGROUND_READ_UNAVAILABLE,
    }

    suspend fun availability(): Availability {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_UNAVAILABLE -> return Availability.SDK_UNAVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                return Availability.UPDATE_REQUIRED
        }
        val c = client ?: return Availability.SDK_UNAVAILABLE
        // **L'ordre compte.** La disponibilite de la *fonctionnalite* se verifie AVANT les
        // permissions. Sur un fournisseur qui ne sait pas lire en arriere-plan,
        // `READ_HEALTH_DATA_IN_BACKGROUND` n'existe pas : elle ne peut donc jamais apparaitre
        // dans `getGrantedPermissions()`. La tester en second renvoyait `PERMISSIONS_MISSING` a
        // vie et envoyait l'utilisateur dans une demande de permission qui ne peut pas aboutir,
        // au lieu de dire que l'appareil ne sait pas faire — la branche
        // `BACKGROUND_READ_UNAVAILABLE` etait morte.
        if (!hasBackgroundReadFeature(c)) return Availability.BACKGROUND_READ_UNAVAILABLE
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.containsAll(REQUIRED_PERMISSIONS)) return Availability.PERMISSIONS_MISSING
        return Availability.READY
    }

    /**
     * Verification de la fonctionnalite « lecture en arriere-plan », **avant** de planifier le
     * worker. Une permission accordee ne dit pas que la plateforme sait l'honorer : les deux
     * choses sont distinctes dans l'API, et ne verifier que la premiere donne un worker qui
     * s'execute et ne lit rien.
     */
    private fun hasBackgroundReadFeature(c: HealthConnectClient): Boolean = runCatching {
        c.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /**
     * Le resultat d'une lecture, tel qu'il part dans `hc_snapshot`.
     *
     * @param aggregateTstMin TST rendu par `aggregate(SLEEP_DURATION_TOTAL)`, **dedoublonne par
     *   le systeme**. Il ne sert pas de resultat : il sert de controle croise. Si notre propre
     *   TST s'en ecarte de plus de ~10 %, c'est notre deduplication qui est fausse — et sans ce
     *   controle, rien ne le dirait.
     */
    data class Reading(
        val selection: SleepSourceSelector.Selection,
        val allCandidates: List<SleepSourceSelector.Candidate>,
        val aggregateTstMin: Double?,
        val verdict: String,
        val readAtMs: Long,
    )

    /**
     * Lit les sessions qui recoupent `[windowStartMs, windowEndMs]`, avec une marge.
     *
     * La marge existe parce qu'une session de sommeil commence souvent avant que la montre de
     * cheville ne demarre et finit apres son arret. Filtrer strictement sur la fenetre
     * d'enregistrement decouperait l'hypnogramme aux bords et retirerait du sommeil reel du
     * denominateur.
     */
    suspend fun read(
        windowStartMs: Long,
        windowEndMs: Long,
        preferredPackage: String?,
        marginMinutes: Long = 180,
    ): Reading? {
        val c = client ?: return null
        val from = Instant.ofEpochMilli(windowStartMs).minusSeconds(marginMinutes * 60)
        val to = Instant.ofEpochMilli(windowEndMs).plusSeconds(marginMinutes * 60)

        val candidates = readCandidates(from, to)

        // Controle croise. `aggregate` dedoublonne (Activity et Sleep uniquement) selon la
        // priorite reglee par l'utilisateur, mais ne rend jamais les stades : il ne peut donc
        // pas remplacer `readRecords`, seulement le verifier.
        val aggregateTstMin = runCatching {
            c.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                )
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(60_000.0)
        }.getOrNull()

        val selection = SleepSourceSelector.select(
            candidates, windowStartMs, windowEndMs, preferredPackage,
        )
        val verdict = selection.chosen
            ?.let { SleepSourceSelector.verdictOf(it, windowStartMs, windowEndMs) }
            ?: "AUCUNE_SESSION"

        return Reading(
            selection = selection,
            allCandidates = candidates,
            aggregateTstMin = aggregateTstMin,
            verdict = verdict,
            readAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Les sources qui ont ecrit une session de sommeil sur les [jours] derniers jours, avec le
     * nombre de nuits que chacune couvre.
     *
     * C'est ce que l'etape 4 de l'assistant affiche, a la place des deux noms qui y etaient
     * ecrits en dur. `null` — et non une liste vide — quand Health Connect n'est pas exploitable :
     * les deux cas s'affichent differemment, puisque « aucune application n'ecrit de sommeil » et
     * « Health Connect n'est pas installe » se reparent a deux endroits.
     */
    suspend fun sourcesRecentes(
        maintenantMs: Long,
        jours: Int = SourcesSommeil.JOURS_OBSERVES,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SourcesSommeil.Observee>? {
        client ?: return null
        val to = Instant.ofEpochMilli(maintenantMs)
        val from = to.minus(jours.toLong(), ChronoUnit.DAYS)
        return runCatching { SourcesSommeil.resumer(readCandidates(from, to), zone) }.getOrNull()
    }

    /**
     * Lecture paginee des sessions d'une fenetre, traduites en candidats.
     *
     * Pagination obligatoire : `ReadRecordsRequest` a une taille de page **par defaut de 1000** et
     * la reponse ne dit qu'une chose quand elle est pleine — elle rend un `pageToken` non nul.
     * Lire la premiere page seulement tronque en silence, et le mode de defaillance est exactement
     * celui qu'on cherche a eviter : une source de sommeil manquante fait perdre le denominateur
     * sans qu'aucune erreur ne remonte. Le cas est rare sur une nuit, moins sur sept jours, et
     * « rare et silencieux » est pire que « frequent et bruyant ».
     */
    private suspend fun readCandidates(
        from: Instant,
        to: Instant,
    ): List<SleepSourceSelector.Candidate> {
        val c = client ?: return emptyList()
        val records = buildList {
            var pageToken: String? = null
            do {
                val response = c.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = pageToken,
                    )
                )
                addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
        }

        return records.map { r ->
            SleepSourceSelector.Candidate(
                recordId = r.metadata.id,
                packageName = r.metadata.dataOrigin.packageName,
                startMs = r.startTime.toEpochMilli(),
                endMs = r.endTime.toEpochMilli(),
                lastModifiedMs = r.metadata.lastModifiedTime.toEpochMilli(),
                stages = r.stages.map {
                    SleepSourceSelector.StageSpan(
                        startMs = it.startTime.toEpochMilli(),
                        endMs = it.endTime.toEpochMilli(),
                        stageType = it.stage,
                    )
                },
            )
        }
    }

    companion object {

        /**
         * `READ_HEALTH_DATA_HISTORY` est demandee parce que `RescoreAllWorker` relit
         * l'hypnogramme de **toutes** les nuits depuis le brut : au-dela de 30 jours, la lecture
         * des donnees ecrites par une autre application est refusee sans elle.
         *
         * Piege a connaitre : la fenetre de 30 jours est remise a zero par une
         * desinstallation/reinstallation. Un debogage agressif peut donc faire perdre l'acces a
         * ses propres nuits anterieures — c'est aussi pourquoi `hc_snapshot` conserve ce que
         * Health Connect avait renvoye, au lieu de compter le relire plus tard.
         */
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

        val OPTIONAL_PERMISSIONS: Set<String> = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        /** Contrat a lancer depuis l'interface pour demander les permissions. */
        fun permissionRequestContract() =
            PermissionController.createRequestPermissionResultContract()

        /**
         * En deca de ce delai, la boite de dialogue n'a pas ete montree.
         *
         * Health Connect suit la regle des permissions d'execution : apres deux refus, le systeme
         * cesse d'afficher la boite et le contrat rend la main **immediatement**, sans rien
         * montrer et sans rien dire. Il n'existe aucune API pour distinguer ce cas d'un refus
         * ordinaire — `shouldShowRequestPermissionRationale` ne vaut rien ici, Health Connect
         * n'etant qu'un courtier pour ces permissions.
         *
         * Le temps ecoule est donc le seul signal disponible. Le seuil est volontairement bas :
         * un aller-retour vers une vraie boite de dialogue, meme refusee d'un geste immediat,
         * demande au systeme d'ouvrir et de fermer une activite. Se tromper dans ce sens renvoie
         * l'utilisateur vers une nouvelle demande, ce qui est benin ; se tromper dans l'autre lui
         * cacherait la seule issue qui lui reste.
         */
        const val DELAI_DIALOGUE_ETOUFFE_MS = 400L

        /**
         * L'ecran de Health Connect ou la permission se donne a la main, quand la boite ne
         * s'affiche plus.
         *
         * Deux chemins selon la version : depuis Android 14 Health Connect fait partie de la
         * plateforme et sait ouvrir la page **de cette application**, ce qui evite de faire
         * chercher Pendulum dans une liste. Avant, il n'existe que l'ecran general.
         *
         * Rend `null` quand rien ne resout l'intention — l'appelant doit alors se taire plutot
         * que de proposer un bouton qui ne fait rien.
         */
        fun intentPermissionsManuelles(context: Context): Intent? {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(ACTION_GERER_PERMISSIONS_SANTE)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            } else {
                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            }
            return intent.takeIf {
                it.resolveActivity(context.packageManager) != null
            }
        }

        /**
         * `android.health.connect.HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS`, en
         * clair : la constante vit dans une classe de la plateforme qui n'existe qu'a partir
         * d'Android 14, et l'y referencer obligerait a hausser `compileSdk` pour une chaine.
         */
        private const val ACTION_GERER_PERMISSIONS_SANTE =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
    }
}
