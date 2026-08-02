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
    abstract fun derivedDao(): DerivedDao
    abstract fun contextDao(): ContextDao
    abstract fun hcSnapshotDao(): HcSnapshotDao
    abstract fun questionnaireDao(): QuestionnaireDao
    abstract fun paramDao(): ParamDao
    abstract fun trendDao(): TrendDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val VERSION = 2
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
 * recopier dans une table neuve, oui ; supprimer une colonne de `chunk` ou de `night_context`,
 * non — ce sont les seules donnees que rien ne permet de reconstituer.
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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
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
