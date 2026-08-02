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
 * Les migrations. Vide en v1 — la liste existe des maintenant pour que l'ajout d'une migration
 * soit un geste evident plutot qu'une decision d'architecture prise en urgence.
 *
 * Regle a tenir : **une migration ne perd jamais une colonne du brut.** Renommer, oui ;
 * recopier dans une table neuve, oui ; supprimer une colonne de `chunk` ou de `night_context`,
 * non — ce sont les seules donnees que rien ne permet de reconstituer.
 */
object Migrations {

    val ALL: Array<Migration> = arrayOf()
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
