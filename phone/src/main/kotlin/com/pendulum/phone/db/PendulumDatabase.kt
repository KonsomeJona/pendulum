package com.pendulum.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
 * En pratique : toute evolution passe par une [Migration] explicite ajoutee a [Migrations.ALL],
 * et le schema est exporte dans `phone/schemas/` (`room.schemaLocation`) pour que la migration
 * soit verifiable plutot que crue sur parole.
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
        const val VERSION = 4
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
                .addMigrations(*Migrations.ALL)
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
 * Les migrations.
 *
 * Regle a tenir : **une migration ne perd jamais une colonne du brut.** Renommer, oui ;
 * recopier dans une table neuve, oui ; supprimer une colonne de `chunk`, de `telemetry_point` ou
 * de `night_context`, non — ce sont les seules donnees que rien ne permet de reconstituer.
 *
 * Seconde regle, tiree du defaut du 3 aout 2026 : **on ne recree une vue que si on la change**, et
 * quand on la recree, on la construit depuis [ComparableNightSql.SQL] avec `trim()`. Room ne
 * compare pas une vue champ par champ, il compare son **texte** a celui, normalise, que son
 * processeur d'annotations a genere ; un saut de ligne et douze espaces d'indentation suffisent a
 * rendre toute base deja installee inouvrable, et `fallbackToDestructiveMigration` est
 * volontairement absent pour ne pas transformer cet echec en effacement.
 */
object Migrations {

    /**
     * v1 → v2 : le contexte du soir cesse d'etre cle par la session.
     *
     * ### Le defaut corrige
     *
     * En v1, `night_context` avait `sessionHex` en cle primaire. Or **le scellement precede la
     * nuit** : quand l'utilisateur remplit le formulaire du soir, la montre n'a rien annonce et
     * il n'existe aucun `sessionHex` a ecrire. Les declencheurs interdisant tout `UPDATE`, on ne
     * pouvait pas davantage le renseigner ensuite. Le garde-fou 1 etait donc, litteralement,
     * impossible a satisfaire — et son seul symptome visible etait une montre qui refusait de
     * demarrer en renvoyant vers un formulaire qui n'existait pas.
     *
     * La cle devient la **cle de nuit** (`AAAA-MM-JJ`, bascule a midi), exactement la chaine que
     * porte le chemin du `DataItem` publie vers la montre. `night_session` gagne la meme colonne,
     * et c'est par elle que la vue `comparable_night` rattache une nuit a son contexte.
     *
     * ### Ce qui est recopie, et ce qui ne peut pas l'etre
     *
     * Les lignes de `night_context` existantes sont conservees : leur `sessionHex` sert a
     * retrouver la `startWallMs` de la session correspondante, dont on derive la cle de nuit avec
     * la meme bascule a midi que `WirePaths.nightKey`. Une ligne dont la session a disparu garde
     * son ancien `sessionHex` comme cle — elle ne se rattachera a rien, mais elle n'est pas
     * perdue, et c'est la regle ci-dessus.
     *
     * En pratique aucune base ne contient de telles lignes : le scellement n'a jamais pu
     * reussir. La migration est ecrite comme si elles existaient parce qu'une migration qu'on
     * ecrit en supposant la table vide est une migration qu'on ne peut pas relire.
     *
     * SQLite ne sait pas changer une cle primaire : il faut recreer la table et recopier. Les
     * declencheurs disparaissent avec l'ancienne table, ce que `Callback.onOpen` repose a chaque
     * ouverture — c'est precisement le cas que ce doublon `onCreate`/`onOpen` existe pour couvrir.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // La bascule a midi, en SQL. `startWallMs` est en millisecondes UTC et la cle de nuit
            // est une date **locale** : `'unixepoch'` puis `'localtime'`, dans cet ordre, puis on
            // retire douze heures pour que tout ce qui precede midi retombe sur la veille.
            db.execSQL(
                """
                ALTER TABLE night_session ADD COLUMN nightKey TEXT NOT NULL DEFAULT ''
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE night_session
                SET nightKey = date((startWallMs / 1000) - 43200, 'unixepoch', 'localtime')
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_night_session_nightKey ON night_session (nightKey)")

            db.execSQL("DROP TRIGGER IF EXISTS night_context_no_update")
            db.execSQL("DROP TRIGGER IF EXISTS night_context_no_delete")

            db.execSQL(
                """
                CREATE TABLE night_context_v2 (
                    nightKey TEXT NOT NULL PRIMARY KEY,
                    sealedAtMs INTEGER NOT NULL,
                    leg TEXT NOT NULL,
                    strapId TEXT NOT NULL,
                    aloneInBed INTEGER NOT NULL,
                    bedTimeLocalMs INTEGER,
                    riseTimeLocalMs INTEGER,
                    medicationJson TEXT NOT NULL,
                    caffeineAfter16h INTEGER NOT NULL,
                    alcoholUnits REAL NOT NULL,
                    unusualExercise INTEGER NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO night_context_v2
                SELECT COALESCE(s.nightKey, c.sessionHex), c.sealedAtMs, c.leg, c.strapId,
                       c.aloneInBed, c.bedTimeLocalMs, c.riseTimeLocalMs, c.medicationJson,
                       c.caffeineAfter16h, c.alcoholUnits, c.unusualExercise, c.notes
                FROM night_context c
                LEFT JOIN night_session s ON s.sessionHex = c.sessionHex
                """.trimIndent()
            )
            db.execSQL("DROP TABLE night_context")
            db.execSQL("ALTER TABLE night_context_v2 RENAME TO night_context")

            // Les declencheurs sont reposes ici **et** a chaque ouverture. Ici parce qu'une
            // migration doit laisser la base dans un etat correct sans dependre de ce qui suit ;
            // a l'ouverture parce que ce genre de ligne s'oublie dans la prochaine migration.
            createTriggers(db)
        }
    }

    /**
     * v2 → v3 : la vue expose `rhythmValid`.
     *
     * Aucune table ne change et aucune donnee n'est touchee — `plm_result.rhythmValid` est ecrit
     * depuis le debut et n'etait simplement pas remonte. Ce qui change est ce que l'interface a
     * le droit de lire : jusqu'ici elle affichait `fundamentalSec` sans savoir que `:algo` avait
     * refuse l'ajustement dans la majorite des cas, et un refus assorti d'un `MISS_RATE_SATURATED`
     * rend un nombre **fini**, donc indiscernable d'un resultat accepte.
     *
     * Une vue n'est pas recreee toute seule par Room a la migration : la validation de schema la
     * relit telle qu'elle est en base, donc il faut la reposer ici. C'est aussi la raison pour
     * laquelle le `CREATE VIEW` reutilise [ComparableNightSql.SQL] au lieu d'en recopier le texte —
     * deux redactions de la meme vue divergeraient a la migration suivante, et la divergence ne se
     * verrait que sur un appareil deja installe.
     *
     * ### Le `trim()`, qui n'est pas une coquetterie
     *
     * Room ne compare pas les vues champ par champ comme les tables : il compare **le texte** de
     * `sqlite_master` a celui que son processeur d'annotations a genere, et ce dernier est
     * normalise. [ComparableNightSql.SQL] est un litteral triple-guillemets qui commence par un
     * saut de ligne et douze espaces d'indentation ; sans `trim()`, la vue ecrite ici differe de
     * treize caracteres de celle qu'on attend, la validation echoue, et — `fallbackToDestructive`
     * etant volontairement absent — **aucun appareil deja installe ne peut plus ouvrir sa base**.
     * Une installation neuve, elle, marche : la vue y est creee par Room lui-meme.
     *
     * Le defaut a ete trouve le 3 aout 2026 par le banc sur materiel reel, sur un telephone
     * portant une base en v1, et non par la suite de tests, qui ne comportait alors aucun test de
     * migration. `MigrationTest` couvre desormais les chemins v1 → v4, v2 → v4 et v3 → v4.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP VIEW IF EXISTS comparable_night")
            db.execSQL("CREATE VIEW `comparable_night` AS ${ComparableNightSql.SQL.trim()}")
        }
    }

    /**
     * v3 → v4 : la telemetrie de nuit entre en base.
     *
     * Elle arrivait deja dans les chunks depuis le bloc `TLM!`, et l'ingestion la jetait : le
     * telephone lisait les points pour verifier le CRC du bloc et n'en gardait rien. La table
     * `telemetry_point` est l'extraction, faite une fois a la reception.
     *
     * ### Purement additive, et c'est ce qui la rend sure
     *
     * Aucune table existante n'est touchee, aucune colonne n'est deplacee, aucune donnee n'est
     * recopiee. Une base v3 monte en v4 en creant une table vide : les nuits deja enregistrees
     * n'ont pas de telemetrie et n'en auront jamais — leurs chunks sont en v1 du format et ne
     * portent aucun bloc `TLM!`. C'est la verite, et l'ecran la dit par l'absence de la bande
     * plutot qu'en dessinant une bande vide.
     *
     * ### Le seul chemin qui compte est v3 → v4, et c'est dit ici plutot que taise
     *
     * `MIGRATION_1_2` et `MIGRATION_2_3` restent en place et restent testees — elles ne coutent
     * rien et les retirer serait perdre de la couverture deja acquise — mais **aucune base n'a
     * jamais emprunte les chemins anterieurs a la v3 en dehors du developpement**. L'application
     * est privee : un seul appareil porte des donnees, et sa base est passee en v3 le 3 aout 2026,
     * a la correction du `trim()` de `MIGRATION_2_3`. Un lecteur qui trouverait dans six mois que
     * v1 → v4 n'a pas ete pense pour le terrain aurait raison, et c'est deliberé.
     *
     * Ce qui rend v3 → v4 non negociable est la suite : P1 demande trois nuits consecutives, et
     * cette base portera alors des chunks bruts qui ne se reconstituent pas. Une migration fausse
     * le jour ou deux nuits sont deja enregistrees coute la campagne — c'est exactement le cas
     * pour lequel `fallbackToDestructiveMigration` est interdit dans ce depot, et il le reste.
     *
     * ### Ce que la vue devient, c'est-a-dire rien
     *
     * `comparable_night` n'est **pas** recreee ici. C'est deliberé, et c'est l'inverse de ce que
     * fait `MIGRATION_2_3` : cette migration-la la reecrivait parce qu'elle en changeait les
     * colonnes. Celle-ci n'y touche pas, donc la reposer serait une occasion de plus de se
     * tromper de treize caracteres — le defaut trouve le 3 aout 2026 sur le Pixel 10 Pro Fold,
     * qui empechait **toute** mise a jour d'un appareil deja installe. La regle qui en sort : on
     * ne recree une vue que si l'on en change, et si on la recree, on la construit depuis
     * [ComparableNightSql.SQL] avec `trim()`, jamais depuis une seconde redaction.
     *
     * Le DDL ci-dessous est celui que Room genere pour [TelemetryPointEntity] — quotes obliques
     * comprises. Il n'est pas recopie a la main sur la foi d'une relecture : `MigrationTest`
     * ouvre une base v1, v2 et v3 par Room apres migration, et la validation de schema compare
     * colonne par colonne et index par index.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `telemetry_point` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionHex` TEXT NOT NULL, " +
                    "`elapsedRealtimeNs` INTEGER NOT NULL, " +
                    "`sensorTsNs` INTEGER NOT NULL, " +
                    "`batteryChargeUah` INTEGER NOT NULL, " +
                    "`maxIntervalUs` INTEGER NOT NULL, " +
                    "`fsyncTotalUs` INTEGER NOT NULL, " +
                    "`fsyncMaxUs` INTEGER NOT NULL, " +
                    "`temperatureDeciC` INTEGER NOT NULL, " +
                    "`measuredRateCentiHz` INTEGER NOT NULL, " +
                    "`jitterStdUs` INTEGER NOT NULL, " +
                    "`clippedSamples` INTEGER NOT NULL, " +
                    "`fsyncCount` INTEGER NOT NULL, " +
                    "`batteryPct` INTEGER NOT NULL, " +
                    "`offBody` INTEGER NOT NULL, " +
                    "`charging` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_telemetry_point_sessionHex_elapsedRealtimeNs` " +
                    "ON `telemetry_point` (`sessionHex`, `elapsedRealtimeNs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_telemetry_point_sessionHex_sensorTsNs` " +
                    "ON `telemetry_point` (`sessionHex`, `sensorTsNs`)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
