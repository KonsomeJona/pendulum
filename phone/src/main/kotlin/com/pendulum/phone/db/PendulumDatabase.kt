package com.pendulum.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * La base.
 *
 * ### `fallbackToDestructiveMigration` est interdit ici, et ce n'est pas une preference de style
 *
 * L'appel n'apparait nulle part et ne doit jamais apparaitre. La raison est dans la nature de ce
 * qui est stocke : les tables derivees (`sleep_window`, `clm_event`, `plm_result`) se
 * reconstruisent en rejouant l'analyse, mais `night_session`, `chunk`, `night_context`,
 * `hc_snapshot` et `questionnaire_response` ne se reconstruisent pas — ce sont les seules traces
 * de nuits qui ont eu lieu une fois. Or **l'algorithme changera** : c'est le seul postulat sur
 * lequel ce projet peut compter. Le jour ou il change, la valeur de la campagne passee tient
 * entierement dans la possibilite de la rescorer depuis le brut. Une migration destructive
 * transforme cette campagne en rien, silencieusement, a la premiere installation d'une version
 * dont le numero de schema a bouge.
 *
 * En pratique : toute evolution passe par une `Migration` explicite passee a `addMigrations`, et
 * le schema est exporte dans `phone/schemas/` (`room.schemaLocation`) pour que la migration soit
 * verifiable plutot que crue sur parole. `exportSchema = true` reste vrai **meme maintenant que
 * la base est en v1 et qu'il n'y a plus une seule migration** : c'est l'export d'aujourd'hui qui
 * rendra verifiable la migration de demain, et il ne se retrouve pas apres coup.
 *
 * ### La version est repartie a 1 le 7 aout 2026, et les quatre migrations ont ete retirees
 *
 * La base portait `version = 5` et quatre migrations (1→2, 2→3, 3→4, 4→5). Aucune n'a jamais
 * tourne ailleurs que sur l'appareil de developpement : **l'application n'a ete installee chez
 * personne**, il n'existe aucune base a migrer, nulle part. Quatre migrations qui racontent les
 * hesitations du schema se paient a chaque relecture et surtout a chaque migration suivante —
 * chacune est un chemin de plus a tenir juste — sans proteger la moindre donnee. La premiere
 * installation reelle creera donc une base v1, qui est la forme d'aujourd'hui.
 *
 * Ce qui a ete retire, resume ici pour qu'on n'ait pas a fouiller l'historique :
 *
 *  - **v1 → v2** : `night_context` cesse d'etre cle par `sessionHex` et l'est par la cle de nuit
 *    (`AAAA-MM-JJ`, bascule a midi) ; `night_session` gagne la meme colonne et son index. Le
 *    defaut corrige : le scellement **precede** la nuit, donc au moment du formulaire du soir il
 *    n'existait aucun `sessionHex` a ecrire, et le garde-fou 1 etait litteralement impossible a
 *    satisfaire. La forme corrigee est celle du schema actuel.
 *  - **v2 → v3** : la vue `comparable_night` expose `rhythmValid`. L'interface affichait
 *    `fundamentalSec` sans savoir que `:algo` avait refuse l'ajustement — un refus rend un nombre
 *    fini, donc indiscernable d'un resultat accepte.
 *  - **v3 → v4** : creation de `telemetry_point`. La telemetrie arrivait dans le bloc `TLM!` des
 *    chunks et l'ingestion la jetait apres verification du CRC.
 *  - **v4 → v5** : retrait de `respiratoryConfidence` de `plm_result`. La colonne portait un enum
 *    a trois valeurs dont une fermait la porte de publication, et rien ne l'alimentait : la valeur
 *    ecrite etait `MEDIUM`, en dur, pour toutes les nuits. Une porte qui ne s'est jamais fermee
 *    est pire qu'une porte absente — elle se documente comme une protection.
 *
 * Deux regles nees de ces migrations **survivent a leur retrait**, parce qu'elles vaudront le jour
 * de la premiere vraie migration :
 *
 *  1. **Une migration ne perd jamais une colonne du brut.** Renommer, oui ; recopier dans une
 *     table neuve, oui ; supprimer une colonne de `chunk`, de `telemetry_point` ou de
 *     `night_context`, non — ce sont les seules donnees que rien ne permet de reconstituer.
 *  2. **On ne recree une vue que si on la change**, et quand on la recree on la construit depuis
 *     [ComparableNightSql.SQL] avec `trim()`. Room ne compare pas une vue champ par champ : il
 *     compare son **texte** a celui, normalise, qu'a genere son processeur d'annotations. Le
 *     litteral commence par un saut de ligne et douze espaces d'indentation, et ces treize
 *     caracteres ont suffi, le 3 aout 2026, a rendre inouvrable toute base deja installee —
 *     `fallbackToDestructiveMigration` etant absent, l'exception remontait jusqu'au premier ecran
 *     qui lisait quelque chose. Le banc sur materiel reel l'a vu ; la suite de tests, non.
 *
 * `MigrationTest` disparait avec les migrations : il n'avait plus d'objet. Ce qui le remplace est
 * `ImmuabiliteTest`, qui couvre un trou bien plus grave et que rien ne couvrait — les declencheurs
 * ci-dessous. Les schemas exportes `2.json`, `3.json` et `4.json` sont supprimes ; `1.json` est
 * regenere par la compilation.
 *
 * ### Les declencheurs
 *
 * Room ne modelise pas les declencheurs SQLite. Ceux qui rendent `night_context` append-only sont
 * donc poses a la main dans [Callback.onCreate] **et** dans [Callback.onOpen] (avec
 * `IF NOT EXISTS`) : `onCreate` seul ne suffirait pas, une migration future recreant la table
 * emporterait ses declencheurs sans que rien ne le signale.
 */
@Database(
    entities = [
        NightSessionEntity::class,
        ChunkEntity::class,
        TelemetryPointEntity::class,
        SleepWindowEntity::class,
        ClmEventEntity::class,
        PlmResultEntity::class,
        NightContextEntity::class,
        HcSnapshotEntity::class,
        QuestionnaireResponseEntity::class,
        ParamProfileEntity::class,
    ],
    views = [ComparableNight::class],
    version = PendulumDatabase.VERSION,
    exportSchema = true,
)
abstract class PendulumDatabase : RoomDatabase() {

    abstract fun nightDao(): NightDao
    abstract fun chunkDao(): ChunkDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun derivedDao(): DerivedDao
    abstract fun contextDao(): ContextDao
    abstract fun hcSnapshotDao(): HcSnapshotDao
    abstract fun questionnaireDao(): QuestionnaireDao
    abstract fun paramDao(): ParamDao
    abstract fun trendDao(): TrendDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val VERSION = 1
        const val NAME = "pendulum.db"

        @Volatile
        private var instance: PendulumDatabase? = null

        fun get(context: Context): PendulumDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): PendulumDatabase =
            Room.databaseBuilder(context, PendulumDatabase::class.java, NAME)
                // Les cles etrangeres ne sont PAS actives par defaut dans SQLite. Sans cette
                // ligne, `onDelete = CASCADE` est decoratif et supprimer une nuit laisserait
                // ses chunks et ses resultats en base, rattaches a rien.
                .addCallback(Callback)
                // Aucun `.addMigrations(...)` : la base est en v1 et aucune version anterieure
                // n'existe sur aucun appareil. La ligne reviendra avec la premiere migration.
                // Volontairement absent : .fallbackToDestructiveMigration()
                .build()

        /** Pour les tests instrumentes, qui doivent pouvoir repartir d'une base vide. */
        internal fun resetInstanceForTests() {
            instance = null
        }
    }

    private object Callback : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            createTriggers(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON")
            createTriggers(db)
        }
    }
}

/**
 * Les declencheurs qui rendent le contexte du soir non modifiable.
 *
 * Le DAO n'expose ni `@Update` ni `@Delete` — mais un DAO se modifie en une ligne, et le jour ou
 * quelqu'un (moi, dans six mois, avec une bonne raison) en ajoute un, il n'y aura aucun echec
 * pour le lui rappeler. Un declencheur, si : `RAISE(ABORT)` fait echouer la transaction, et le
 * message dit pourquoi.
 *
 * C'est la traduction en SQL du garde-fou 1 : la dose et le contexte sont scelles **avant** que
 * la montre n'accepte de demarrer, et le mode de defaillance a empecher n'est pas la fraude,
 * c'est la retouche de bonne foi au reveil, apres avoir vu le chiffre.
 *
 * Rien de tout cela n'est verifie par Room, dont la validation de schema ignore entierement les
 * declencheurs : c'est `ImmuabiliteTest` (test instrumente) qui pose la question, en tentant les
 * ecritures interdites sur la vraie base et en verifiant qu'apres une ouverture normale les trois
 * declencheurs sont bien dans `sqlite_master`.
 */
private fun createTriggers(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS night_context_no_update
        BEFORE UPDATE ON night_context
        BEGIN
            SELECT RAISE(ABORT, 'night_context est scelle : aucune modification apres coup');
        END
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS night_context_no_delete
        BEFORE DELETE ON night_context
        BEGIN
            SELECT RAISE(ABORT, 'night_context est scelle : suppression interdite (voir eraseEverything)');
        END
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TRIGGER IF NOT EXISTS hc_snapshot_no_update
        BEFORE UPDATE ON hc_snapshot
        BEGIN
            SELECT RAISE(ABORT, 'hc_snapshot est un journal : on ajoute une lecture, on n en corrige pas');
        END
        """.trimIndent()
    )
}

/**
 * Suppression totale, y compris les chunks bruts.
 *
 * Trois raisons de ne pas se contenter d'un `DELETE FROM night_session` :
 *  - les fichiers de chunks vivent sur le disque, pas en base ;
 *  - les declencheurs append-only refusent le `DELETE` sur `night_context` et doivent etre
 *    retires puis remis, faute de quoi l'effacement echoue a moitie ;
 *  - `VACUUM` est necessaire pour que les pages liberees ne restent pas lisibles dans le fichier
 *    de base. Sans lui, « tout effacer » laisse le contenu recuperable dans les pages libres.
 *
 * L'appelant efface les fichiers **avant** d'appeler ceci : voir la KDoc de [MaintenanceDao].
 */
suspend fun PendulumDatabase.eraseEverything() {
    val db = openHelper.writableDatabase
    db.execSQL("DROP TRIGGER IF EXISTS night_context_no_update")
    db.execSQL("DROP TRIGGER IF EXISTS night_context_no_delete")
    db.execSQL("DROP TRIGGER IF EXISTS hc_snapshot_no_update")
    try {
        maintenanceDao().deleteAllSessions()
        maintenanceDao().deleteAllContexts()
        maintenanceDao().deleteAllQuestionnaires()
        maintenanceDao().deleteAllProfiles()
    } finally {
        createTriggers(db)
    }
    // Hors transaction, et c'est indispensable : VACUUM echoue a l'interieur d'une transaction.
    db.execSQL("VACUUM")
}
