package com.pendulum.phone.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Une base ancienne s'ouvre-t-elle apres migration ?
 *
 * ### Pourquoi ce test existe, et pourquoi il n'existait pas
 *
 * Le banc sur materiel reel a trouve le 3 aout 2026 que **non** : sur un telephone portant une
 * base en v1, l'application installee ne pouvait plus ouvrir sa base du tout. `MIGRATION_2_3`
 * recreait la vue `comparable_night` dans un texte qui differait de treize caracteres de celui que
 * Room attend, la validation de schema echouait, et `fallbackToDestructiveMigration` etant
 * volontairement absent, l'exception remontait jusqu'au premier acces. Aucun appareil deja
 * installe ne pouvait etre mis a jour ; seule une installation neuve fonctionnait.
 *
 * Rien dans le depot ne pouvait le voir. Il n'y avait aucun test de migration, et la suite JVM ne
 * peut pas en porter : ce qui casse est la comparaison que fait **SQLite et Room a l'ouverture**,
 * entre le texte stocke dans `sqlite_master` et celui que le processeur d'annotations a genere.
 * Il faut donc un vrai fichier de base et un vrai moteur, c'est-a-dire un test instrumente.
 *
 * ### Pourquoi les schemas sont ecrits ici en SQL brut
 *
 * `MigrationTestHelper` fabrique la base de depart depuis les schemas exportes de `phone/schemas/`.
 * Or `1.json` **n'a jamais ete exporte** — l'export a ete active apres la v1 — donc le seul chemin
 * qui casse serait justement celui que l'outil ne sait pas construire. Et un schema exporte est
 * cru sur parole : il decrit ce que Room pensait ecrire, pas ce qu'une base d'appareil contient.
 *
 * Les deux fixtures sont donc du DDL, pose a la main :
 *
 *  - la v2 est recopiee des `createSql` de `2.json`, qui est la verite versionnee de cette
 *    version ;
 *  - la v1 s'en deduit en **defaisant** ce que fait `MIGRATION_1_2`, seule source qui decrive la
 *    forme d'avant : `night_session` sans `nightKey` ni son index, `night_context` cle par
 *    `sessionHex`.
 *  - la v3 est la v2 **plus la vue telle que Room l'attend**. C'est la seule fixture ou le texte
 *    de la vue compte : `MIGRATION_2_3` la reecrit sur les chemins v1 et v2, alors qu'un depart
 *    en v3 la laisse telle quelle et la donne a valider a Room. Une fixture v3 qui porterait la
 *    vue simplifiee des deux autres ferait echouer le chemin v3 → v4 pour une raison qui
 *    n'aurait rien a voir avec la migration testee.
 *
 * L'empreinte d'identite de la fixture v1 n'est pas inventee : c'est celle **relevee sur le Pixel
 * 10 Pro Fold de l'utilisateur**, `0be59c8b3c17593df65ff797802f5eb4`. Elle n'est de toute facon
 * pas lue par le chemin teste — `onUpgrade` la reecrit — mais une constante fausse dans une
 * fixture finit toujours par etre recopiee ailleurs.
 *
 * Le texte de la vue en v1 est, lui, **inconnu et sans effet** : `MIGRATION_2_3` commence par la
 * supprimer. On y met celui de la v2. Ce qui a ete observe de la vraie vue v1 est seulement
 * qu'elle etait normalisee par Room (`AS SELECT`, sans saut de ligne) et qu'elle ne portait pas
 * encore `rhythmValid` — deux faits que ce test n'a pas besoin de reproduire.
 *
 * ### Ce que le test construit, et ce qu'il ne construit pas
 *
 * La base est ouverte par `Room.databaseBuilder(...).addMigrations(*Migrations.ALL)`, c'est-a-dire
 * exactement les deux lignes de `PendulumDatabase.build` qui decident du sort d'une mise a jour.
 * `Callback` n'est pas repose : il ajoute des declencheurs et un `PRAGMA`, que Room ne valide pas.
 * Le reproduire ferait croire que le test couvre les declencheurs, ce qui est faux.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val nom = "migration-test.db"

    @Before
    fun avant() {
        ctx.deleteDatabase(nom)
    }

    @After
    fun apres() {
        // La base d'essai vit dans le repertoire de l'application : ne rien laisser derriere,
        // c'est un appareil du quotidien qui heberge ces tests.
        ctx.deleteDatabase(nom)
    }

    @Test
    fun une_base_v2_s_ouvre_apres_migration() {
        poser(2, IDENTITE_V2) { db -> DDL_V2.forEach(db::execSQL) }
        ouvrirParRoom()
    }

    @Test
    fun une_base_v1_s_ouvre_apres_migration() {
        poser(1, IDENTITE_V1) { db -> DDL_V1.forEach(db::execSQL) }
        ouvrirParRoom()
    }

    @Test
    fun une_base_v3_s_ouvre_apres_migration() {
        poser(3, IDENTITE_V3) { db -> DDL_V3.forEach(db::execSQL) }
        ouvrirParRoom()
    }

    /**
     * La telemetrie ne se reconstitue pas depuis le brut, donc la migration ne doit pas la perdre —
     * mais en v4 elle n'existe pas encore en base, et il n'y a rien a conserver. Ce que ce test
     * verifie est l'autre moitie : la table est **la** et **utilisable** apres la montee de version,
     * sur les trois chemins. Une table creee avec un schema que Room refuse ne se voit pas
     * autrement : la validation echoue a la premiere ouverture, pas a la migration.
     */
    @Test
    fun apres_migration_la_table_de_telemetrie_accepte_un_point() {
        // Depuis la v3, et pas depuis la v1 : c'est la version que porte le telephone de
        // l'utilisateur aujourd'hui, et donc le seul chemin qu'une base reelle empruntera.
        poser(3, IDENTITE_V3) { db -> DDL_V3.forEach(db::execSQL) }
        val db = Room.databaseBuilder(ctx, PendulumDatabase::class.java, nom)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val brut = db.openHelper.writableDatabase
            brut.execSQL(
                "INSERT INTO night_session (sessionHex, nightKey, startWallMs, plannedStopWallMs, " +
                    "zoneId, tzOffsetStartMin, tzOffsetEndMin, nominalRateHz, modeFlags, state, " +
                    "lastChunkArrivalMs, sampleCount, gapCount, gapTotalMs, analysableMin, " +
                    "truncated, integrityRejectedFraction) " +
                    "VALUES ('deadbeef', '2026-08-03', 1, 2, 'Asia/Tokyo', 540, 540, 50, 0, " +
                    "'CLOSED', 0, 0, 0, 0, 0.0, 0, 0.0)"
            )
            kotlinx.coroutines.runBlocking {
                db.telemetryDao().insertAllIfAbsent(listOf(POINT))
                // Le meme point une seconde fois : une reemission de chunk repasse par ici, et
                // elle doit etre un no-op silencieux comme l'est celle d'un chunk.
                db.telemetryDao().insertAllIfAbsent(listOf(POINT))
                val lus = db.telemetryDao().ofSession("deadbeef")
                if (lus.size != 1) error("telemetrie non idempotente : ${lus.size} lignes")
                if (lus.first().jitterStdUs != POINT.jitterStdUs) error("point relu different")
            }
        } finally {
            db.close()
        }
    }

    /** Fabrique le fichier de base a la version voulue, sans Room. */
    private fun poser(version: Int, identite: String, ddl: (SQLiteDatabase) -> Unit) {
        val fichier = ctx.getDatabasePath(nom)
        fichier.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(fichier, null)
        try {
            ddl(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(identite))
            db.version = version
        } finally {
            db.close()
        }
    }

    /**
     * Ouvre par Room et **force un acces**. Sans la requete, rien ne se passe : `databaseBuilder`
     * est paresseux et l'echec de migration n'a lieu qu'a la premiere ouverture reelle. C'est
     * aussi pour cela que le defaut n'a jamais ete vu au lancement de l'application mais au
     * premier ecran qui lit quelque chose.
     */
    private fun ouvrirParRoom() {
        val db = Room.databaseBuilder(ctx, PendulumDatabase::class.java, nom)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            db.openHelper.readableDatabase
                .query("SELECT count(*) FROM night_session")
                .use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }

    private companion object {

        /** Relevee sur l'appareil de l'utilisateur, base v1 reelle. */
        const val IDENTITE_V1 = "0be59c8b3c17593df65ff797802f5eb4"

        /** `identityHash` de `phone/schemas/…/2.json`. */
        const val IDENTITE_V2 = "4facf3419337c094acd674fadd5c65e9"

        /** `identityHash` de `phone/schemas/…/3.json`. C'est la version portee par le terrain. */
        const val IDENTITE_V3 = "083f84b1b64d678022c610c10ec623da"

        /**
         * Un point de telemetrie quelconque, mais **pas nul de partout** : un point tout a zero
         * passerait un test d'insertion tout en cachant une colonne oubliee.
         */
        val POINT = TelemetryPointEntity(
            sessionHex = "deadbeef",
            elapsedRealtimeNs = 12_345_678_901L,
            sensorTsNs = 98_765_432_100L,
            batteryChargeUah = 312_400,
            maxIntervalUs = 41_000,
            fsyncTotalUs = 8_200,
            fsyncMaxUs = 3_100,
            temperatureDeciC = 312,
            measuredRateCentiHz = 5031,
            jitterStdUs = 1_450,
            clippedSamples = 7,
            fsyncCount = 4,
            batteryPct = 88,
            offBody = 0,
            charging = false,
        )

        const val VUE_V2 = "CREATE VIEW `comparable_night` AS SELECT " +
            "s.sessionHex AS sessionHex, s.startWallMs AS startWallMs FROM night_session s"

        val TABLES_COMMUNES = listOf(
            "CREATE TABLE IF NOT EXISTS `chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT NOT NULL, `idx` INTEGER NOT NULL, `path` TEXT NOT NULL, " +
                "`size` INTEGER NOT NULL, `crc32` INTEGER NOT NULL, `sampleCount` INTEGER NOT NULL, " +
                "`tFirstNs` INTEGER NOT NULL, `tLastNs` INTEGER NOT NULL, `flagsOr` INTEGER NOT NULL, " +
                "`complete` INTEGER NOT NULL, `receivedAtMs` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_chunk_sessionHex_idx` ON `chunk` (`sessionHex`, `idx`)",
            "CREATE INDEX IF NOT EXISTS `index_chunk_sessionHex` ON `chunk` (`sessionHex`)",
            "CREATE TABLE IF NOT EXISTS `sleep_window` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT NOT NULL, `source` TEXT NOT NULL, `stage` TEXT NOT NULL, " +
                "`startMsRel` INTEGER NOT NULL, `endMsRel` INTEGER NOT NULL, `sourcePackage` TEXT, " +
                "`paramsHash` TEXT NOT NULL, FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_sleep_window_sessionHex_source` ON `sleep_window` (`sessionHex`, `source`)",
            "CREATE TABLE IF NOT EXISTS `clm_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT NOT NULL, `paramsHash` TEXT NOT NULL, `onsetMsRel` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `peakAmpG` REAL NOT NULL, `medianAmpG` REAL NOT NULL, " +
                "`noiseFloorG` REAL NOT NULL, `thresholdOnG` REAL NOT NULL, `tiltChangeDeg` REAL NOT NULL, " +
                "`flags` INTEGER NOT NULL, `rejectReason` TEXT, `inSeriesAasm` INTEGER NOT NULL, " +
                "`inSeriesWasm` INTEGER NOT NULL, `stageAtOnset` TEXT, `duringWake` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_clm_event_sessionHex_paramsHash` ON `clm_event` (`sessionHex`, `paramsHash`)",
            "CREATE TABLE IF NOT EXISTS `plm_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT NOT NULL, `paramsHash` TEXT NOT NULL, `rule` TEXT NOT NULL, " +
                "`maskSource` TEXT NOT NULL, `computedAtMs` INTEGER NOT NULL, `algoVersion` TEXT NOT NULL, " +
                "`plmsCount` INTEGER NOT NULL, `plmwCount` INTEGER NOT NULL, `isolatedCount` INTEGER NOT NULL, " +
                "`shortImiCount` INTEGER NOT NULL, `tstMin` REAL NOT NULL, `analysableTstMin` REAL NOT NULL, " +
                "`sptMin` REAL NOT NULL, `wasoMin` REAL NOT NULL, `plmi` REAL NOT NULL, `plmiSpt` REAL NOT NULL, " +
                "`plmw` REAL NOT NULL, `plmiFirstHalf` REAL NOT NULL, `plmiSecondHalf` REAL NOT NULL, " +
                "`plmiRespWorstCase` REAL NOT NULL, `periodicityIndex` REAL NOT NULL, " +
                "`periodicityValid` INTEGER NOT NULL, `fundamentalSec` REAL NOT NULL, `muLog` REAL NOT NULL, " +
                "`sigmaLog` REAL NOT NULL, `missRate` REAL NOT NULL, `alternationSuspect` INTEGER NOT NULL, " +
                "`rhythmConverged` INTEGER NOT NULL, `rhythmValid` INTEGER NOT NULL, " +
                "`truncatedSeriesDropped` INTEGER NOT NULL, `respiratoryConfidence` TEXT NOT NULL, " +
                "`independence` TEXT NOT NULL, `gate` TEXT NOT NULL, `floorMode` TEXT NOT NULL, " +
                "FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_plm_result_sessionHex_paramsHash_rule_maskSource` " +
                "ON `plm_result` (`sessionHex`, `paramsHash`, `rule`, `maskSource`)",
            "CREATE INDEX IF NOT EXISTS `index_plm_result_paramsHash` ON `plm_result` (`paramsHash`)",
            "CREATE TABLE IF NOT EXISTS `hc_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT NOT NULL, `fetchedAtMs` INTEGER NOT NULL, `attemptIndex` INTEGER NOT NULL, " +
                "`selectedPackage` TEXT, `selectedRecordId` TEXT, `lastModifiedTimeMs` INTEGER, " +
                "`sessionStartMs` INTEGER, `sessionEndMs` INTEGER, `stageCount` INTEGER NOT NULL, " +
                "`distinctStageTypes` INTEGER NOT NULL, `stageCoverageMin` REAL NOT NULL, " +
                "`overlapFraction` REAL NOT NULL, `aggregateTstMin` REAL, `originCount` INTEGER NOT NULL, " +
                "`selectedStagesCsv` TEXT NOT NULL, `recordsJson` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                "FOREIGN KEY(`sessionHex`) REFERENCES `night_session`(`sessionHex`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_hc_snapshot_sessionHex_fetchedAtMs` " +
                "ON `hc_snapshot` (`sessionHex`, `fetchedAtMs`)",
            "CREATE TABLE IF NOT EXISTS `questionnaire_response` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionHex` TEXT, `kind` TEXT NOT NULL, `answeredAtMs` INTEGER NOT NULL, " +
                "`answersJson` TEXT NOT NULL, `score` REAL)",
            "CREATE INDEX IF NOT EXISTS `index_questionnaire_response_sessionHex` " +
                "ON `questionnaire_response` (`sessionHex`)",
            "CREATE INDEX IF NOT EXISTS `index_questionnaire_response_kind_answeredAtMs` " +
                "ON `questionnaire_response` (`kind`, `answeredAtMs`)",
            "CREATE TABLE IF NOT EXISTS `param_profile` (`paramsHash` TEXT NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, `algoVersion` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, " +
                "`active` INTEGER NOT NULL, `label` TEXT, PRIMARY KEY(`paramsHash`))",
        )

        /** `night_session` de v2 : avec `nightKey`. Recopie de `2.json`. */
        const val NIGHT_SESSION_V2 =
            "CREATE TABLE IF NOT EXISTS `night_session` (`sessionHex` TEXT NOT NULL, " +
                "`nightKey` TEXT NOT NULL, `startWallMs` INTEGER NOT NULL, " +
                "`plannedStopWallMs` INTEGER NOT NULL, `endWallMs` INTEGER, `zoneId` TEXT NOT NULL, " +
                "`tzOffsetStartMin` INTEGER NOT NULL, `tzOffsetEndMin` INTEGER NOT NULL, " +
                "`nominalRateHz` INTEGER NOT NULL, `modeFlags` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                "`stopReason` TEXT, `totalChunks` INTEGER, `lastChunkArrivalMs` INTEGER NOT NULL, " +
                "`batteryPctLast` INTEGER, `analyzedAtMs` INTEGER, `algoVersion` TEXT, `paramsHash` TEXT, " +
                "`fsMeasuredHz` REAL, `sampleCount` INTEGER NOT NULL, `gapCount` INTEGER NOT NULL, " +
                "`gapTotalMs` INTEGER NOT NULL, `analysableMin` REAL NOT NULL, `gainCalG` REAL, " +
                "`gainSource` TEXT, `truncated` INTEGER NOT NULL, " +
                "`integrityRejectedFraction` REAL NOT NULL, `revealedAtMs` INTEGER, " +
                "PRIMARY KEY(`sessionHex`))"

        /** La meme, sans `nightKey` : `MIGRATION_1_2` l'ajoute, donc la v1 ne l'avait pas. */
        val NIGHT_SESSION_V1 = NIGHT_SESSION_V2.replace("`nightKey` TEXT NOT NULL, ", "")

        /** v2 : cle par la cle de nuit. */
        const val NIGHT_CONTEXT_V2 =
            "CREATE TABLE IF NOT EXISTS `night_context` (`nightKey` TEXT NOT NULL, " +
                "`sealedAtMs` INTEGER NOT NULL, `leg` TEXT NOT NULL, `strapId` TEXT NOT NULL, " +
                "`aloneInBed` INTEGER NOT NULL, `bedTimeLocalMs` INTEGER, `riseTimeLocalMs` INTEGER, " +
                "`medicationJson` TEXT NOT NULL, `caffeineAfter16h` INTEGER NOT NULL, " +
                "`alcoholUnits` REAL NOT NULL, `unusualExercise` INTEGER NOT NULL, `notes` TEXT, " +
                "PRIMARY KEY(`nightKey`))"

        /** v1 : cle par la session, le defaut que `MIGRATION_1_2` corrige. */
        const val NIGHT_CONTEXT_V1 =
            "CREATE TABLE IF NOT EXISTS `night_context` (`sessionHex` TEXT NOT NULL, " +
                "`sealedAtMs` INTEGER NOT NULL, `leg` TEXT NOT NULL, `strapId` TEXT NOT NULL, " +
                "`aloneInBed` INTEGER NOT NULL, `bedTimeLocalMs` INTEGER, `riseTimeLocalMs` INTEGER, " +
                "`medicationJson` TEXT NOT NULL, `caffeineAfter16h` INTEGER NOT NULL, " +
                "`alcoholUnits` REAL NOT NULL, `unusualExercise` INTEGER NOT NULL, `notes` TEXT, " +
                "PRIMARY KEY(`sessionHex`))"

        /** Les declencheurs d'avant : `MIGRATION_1_2` en supprime deux, ils doivent exister. */
        val DECLENCHEURS_V1 = listOf(
            "CREATE TRIGGER IF NOT EXISTS night_context_no_update BEFORE UPDATE ON night_context " +
                "BEGIN SELECT RAISE(ABORT, 'scelle'); END",
            "CREATE TRIGGER IF NOT EXISTS night_context_no_delete BEFORE DELETE ON night_context " +
                "BEGIN SELECT RAISE(ABORT, 'scelle'); END",
        )

        val DDL_V2: List<String> =
            listOf(NIGHT_SESSION_V2) +
                "CREATE INDEX IF NOT EXISTS `index_night_session_startWallMs` ON `night_session` (`startWallMs`)" +
                "CREATE INDEX IF NOT EXISTS `index_night_session_nightKey` ON `night_session` (`nightKey`)" +
                TABLES_COMMUNES + NIGHT_CONTEXT_V2 + VUE_V2

        val DDL_V1: List<String> =
            listOf(NIGHT_SESSION_V1) +
                "CREATE INDEX IF NOT EXISTS `index_night_session_startWallMs` ON `night_session` (`startWallMs`)" +
                TABLES_COMMUNES + NIGHT_CONTEXT_V1 + DECLENCHEURS_V1 + VUE_V2

        /**
         * La v3 : les tables de la v2, et la vue **exactement telle que Room la valide**.
         *
         * `MIGRATION_3_4` ne touche pas a la vue — elle n'a aucune raison de le faire — donc c'est
         * celle de la fixture qui sera comparee au texte genere par le processeur d'annotations. Le
         * `trim()` est le meme que celui de `MIGRATION_2_3`, et pour la meme raison : le litteral
         * commence par un saut de ligne et douze espaces, et treize caracteres suffisent a faire
         * echouer la validation.
         */
        val VUE_V3 = "CREATE VIEW `comparable_night` AS ${ComparableNightSql.SQL.trim()}"

        val DDL_V3: List<String> =
            listOf(NIGHT_SESSION_V2) +
                "CREATE INDEX IF NOT EXISTS `index_night_session_startWallMs` ON `night_session` (`startWallMs`)" +
                "CREATE INDEX IF NOT EXISTS `index_night_session_nightKey` ON `night_session` (`nightKey`)" +
                TABLES_COMMUNES + NIGHT_CONTEXT_V2 + VUE_V3
    }
}
