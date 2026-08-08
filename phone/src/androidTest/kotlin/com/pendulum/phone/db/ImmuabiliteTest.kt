package com.pendulum.phone.db

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Les declencheurs d'immuabilite tiennent-ils vraiment ?
 *
 * ### Pourquoi ce test existe, et pourquoi rien ne le remplacait
 *
 * `PendulumDatabase` pose a la main, dans un `RoomDatabase.Callback`, trois declencheurs SQLite :
 * `night_context_no_update`, `night_context_no_delete` et `hc_snapshot_no_update`. Ce sont eux qui
 * rendent le contexte du soir non modifiable apres coup, c'est-a-dire le **garde-fou 1** du
 * produit : la dose, la jambe, le bracelet et l'alcool sont scelles avant que la montre n'accepte
 * de demarrer, et le mode de defaillance a empecher n'est pas la fraude — c'est la retouche de
 * bonne foi au reveil, apres avoir vu le chiffre.
 *
 * Or **rien ne les verifiait**. Room ne modelise pas les declencheurs : sa validation de schema
 * compare des tables, des colonnes, des index et le texte des vues, et ignore entierement le
 * contenu de `sqlite_master` de type `trigger`. Un `Callback` mal branche, un `onOpen` qui cesse
 * d'appeler `createTriggers`, ou une migration future qui recree `night_context` : dans les trois
 * cas la base continue de s'ouvrir, tous les autres tests passent au vert, et le garde-fou n'est
 * plus qu'un paragraphe de documentation. Le seul symptome serait un `UPDATE` qui reussit — donc
 * aucun symptome.
 *
 * Ce trou etait plus grave que celui que couvrait `MigrationTest`, retire en meme temps que les
 * quatre migrations le 7 aout 2026 (voir la KDoc de [PendulumDatabase]) : une migration ratee
 * empeche d'ouvrir la base, et cela se voit ; un declencheur absent ne se voit pas.
 *
 * ### Ce que le test ouvre, et pourquoi c'est la vraie base
 *
 * Tout passe par [PendulumDatabase.get], donc par le `Callback` reel et par le fichier reel
 * `pendulum.db`. C'est volontaire et c'est la seule facon honnete de poser la question : les
 * declencheurs ne sont pas dans le schema, ils sont dans le chemin de construction, et un
 * `Room.inMemoryDatabaseBuilder` monte a la main dans le test verifierait la fidelite du test a
 * lui-meme. `GardeFousTest` prend deja cette base-la, pour la meme raison.
 *
 * La contrepartie est qu'il faut nettoyer, et que le nettoyage ne peut pas etre un `DELETE` :
 * c'est precisement ce que le declencheur interdit. [eraseEverything] est la seule sortie — elle
 * retire les declencheurs, efface, puis les repose — et c'est aussi une verification de plus, en
 * creux : si elle cessait de fonctionner, ce test ne pourrait meme pas se preparer.
 */
@RunWith(AndroidJUnit4::class)
class ImmuabiliteTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun avant() = repartirDUneBaseVide()

    @After
    fun apres() = repartirDUneBaseVide()

    /**
     * Un contexte scelle refuse tout `UPDATE`.
     *
     * Les deux moities comptent, et la seconde plus que la premiere : une exception peut etre
     * levee **apres** que l'ecriture a eu lieu, ou par autre chose que le declencheur. Le test
     * relit donc la ligne et verifie que l'alcool et la note sont ceux du scellement. C'est la
     * question posee du point de vue de l'utilisateur : la valeur d'hier soir est-elle toujours
     * celle d'hier soir ?
     */
    @Test
    fun contexteScelle_refuseUnUpdate_etLaValeurEnBaseNeBougePas() {
        val db = PendulumDatabase.get(ctx)
        runBlocking { db.contextDao().seal(CONTEXTE) }

        refuse(
            db,
            "UPDATE night_context SET alcoholUnits = 9.0, notes = 'retouche au reveil' " +
                "WHERE nightKey = '${CONTEXTE.nightKey}'",
            motifAttendu = "scelle",
        )

        val relu = runBlocking { db.contextDao().find(CONTEXTE.nightKey) }
            ?: error("le contexte a disparu : ce n'est plus un refus, c'est une perte")
        if (relu.alcoholUnits != CONTEXTE.alcoholUnits) {
            error("l'alcool a ete reecrit : ${relu.alcoholUnits} au lieu de ${CONTEXTE.alcoholUnits}")
        }
        if (relu.notes != CONTEXTE.notes) error("la note a ete reecrite : « ${relu.notes} »")
    }

    /**
     * Un contexte scelle refuse tout `DELETE`.
     *
     * Supprimer puis reinserer est la facon evidente de contourner l'interdiction de modifier, et
     * elle ne demande pas de mauvaise intention : un `@Delete` ajoute au DAO « pour corriger une
     * saisie » suffirait. Le seul effacement legitime est global et passe par [eraseEverything].
     */
    @Test
    fun contexteScelle_refuseUnDelete_etLaLigneEstToujoursLa() {
        val db = PendulumDatabase.get(ctx)
        runBlocking { db.contextDao().seal(CONTEXTE) }

        refuse(
            db,
            "DELETE FROM night_context WHERE nightKey = '${CONTEXTE.nightKey}'",
            motifAttendu = "scelle",
        )

        runBlocking { db.contextDao().find(CONTEXTE.nightKey) }
            ?: error("la ligne a ete supprimee malgre le declencheur")
    }

    /**
     * Un instantane Health Connect refuse tout `UPDATE`.
     *
     * `hc_snapshot` est un journal, et il existe parce que certains fournisseurs **reecrivent**
     * une session deja publiee : une nuit lue a T+1 h peut differer de la meme nuit a T+8 h. Si
     * l'application pouvait, elle aussi, corriger une lecture passee, la comparaison de deux
     * lignes ne prouverait plus rien — c'est la seule trace qui explique un chiffre qui bouge
     * entre deux consultations.
     */
    @Test
    fun instantaneHealthConnect_refuseUnUpdate_etLaLectureResteCelleDuJour() {
        val db = PendulumDatabase.get(ctx)
        runBlocking {
            db.nightDao().insertIfAbsent(SESSION)
            db.hcSnapshotDao().append(INSTANTANE)
        }

        refuse(
            db,
            "UPDATE hc_snapshot SET outcome = 'RETOUCHE', stageCount = 0 " +
                "WHERE sessionHex = '${SESSION.sessionHex}'",
            motifAttendu = "journal",
        )

        val relu = runBlocking { db.hcSnapshotDao().latest(SESSION.sessionHex) }
            ?: error("l'instantane a disparu")
        if (relu.outcome != INSTANTANE.outcome) error("outcome reecrit : ${relu.outcome}")
        if (relu.stageCount != INSTANTANE.stageCount) error("stageCount reecrit : ${relu.stageCount}")
    }

    /**
     * Les trois declencheurs sont **reposes a chaque ouverture**, pas seulement a la creation.
     *
     * Constater qu'ils sont dans `sqlite_master` apres un `get()` ne prouverait rien tout seul :
     * ils y seraient de toute facon, poses a la creation du fichier lors d'une execution
     * precedente. Le test les **supprime** donc d'abord, ferme la base, remet a zero l'instance
     * memorisee, puis rouvre par le chemin normal — c'est exactement ce que ferait une migration
     * qui recree `night_context` et emporte ses declencheurs au passage. S'ils reviennent, le
     * doublon `onCreate` / `onOpen` de [PendulumDatabase] fait son travail ; s'ils ne reviennent
     * pas, le garde-fou 1 disparait en silence a la prochaine evolution du schema.
     */
    @Test
    fun apresUneOuvertureNormale_lesTroisDeclencheursSontEnBase() {
        val premiere = PendulumDatabase.get(ctx)
        val brut = premiere.openHelper.writableDatabase
        for (nom in DECLENCHEURS) brut.execSQL("DROP TRIGGER IF EXISTS $nom")
        if (declencheurs(premiere).isNotEmpty()) error("le menage du test n'a pas eu lieu")
        premiere.close()
        PendulumDatabase.resetInstanceForTests()

        val trouves = declencheurs(PendulumDatabase.get(ctx))
        val manquants = DECLENCHEURS - trouves
        if (manquants.isNotEmpty()) {
            error("declencheurs absents apres ouverture : $manquants (presents : $trouves)")
        }
    }

    /** Les noms des declencheurs presents dans `sqlite_master`, parmi ceux qui nous concernent. */
    private fun declencheurs(db: PendulumDatabase): Set<String> {
        val noms = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'trigger'")
            .use { c -> while (c.moveToNext()) noms += c.getString(0) }
        return noms intersect DECLENCHEURS
    }

    /**
     * Execute une ecriture qui doit echouer, et verifie **pourquoi** elle echoue.
     *
     * Le motif attendu est un fragment du message passe a `RAISE(ABORT, …)` : sans lui, une erreur
     * de cle etrangere ou une table absente ferait passer le test pour la mauvaise raison. On
     * attrape [SQLiteException] et non `SQLiteConstraintException` — c'est bien celle-la qui est
     * levee, mais le message est une preuve plus solide que la classe, qui depend du repli d'un
     * code d'erreur etendu sur un code de base.
     */
    private fun refuse(db: PendulumDatabase, sql: String, motifAttendu: String) {
        val erreur = try {
            db.openHelper.writableDatabase.execSQL(sql)
            null
        } catch (e: SQLiteException) {
            e
        }
        if (erreur == null) error("ecriture acceptee, le declencheur n'a pas joue : $sql")
        val message = erreur.message.orEmpty()
        if (!message.contains(motifAttendu)) {
            error("refus obtenu, mais pas par le declencheur attendu (« $motifAttendu ») : $message")
        }
    }

    /**
     * Remet la base a vide par la seule voie autorisee.
     *
     * `PendulumDatabase.get` et non un fichier d'essai : c'est la base de l'application qui porte
     * les declencheurs, donc c'est elle qu'il faut. Sur un appareil du quotidien, cela efface les
     * nuits enregistrees — c'est deja le cas de `GardeFousTest`, et c'est le prix d'un test qui
     * verifie l'application assemblee plutot qu'une maquette.
     */
    private fun repartirDUneBaseVide() {
        runBlocking { PendulumDatabase.get(ctx).eraseEverything() }
    }

    private companion object {

        val DECLENCHEURS = setOf(
            "night_context_no_update",
            "night_context_no_delete",
            "hc_snapshot_no_update",
        )

        /**
         * Un contexte scelle plausible, et surtout **pas nul de partout** : un contexte tout a zero
         * passerait un test de refus tout en cachant qu'une colonne a bien ete reecrite.
         */
        val CONTEXTE = NightContextEntity(
            nightKey = "2026-08-07",
            sealedAtMs = 1_754_500_000_000L,
            leg = "LEFT",
            strapId = "strap-a",
            aloneInBed = true,
            bedTimeLocalMs = 1_754_517_600_000L,
            riseTimeLocalMs = 1_754_546_400_000L,
            medicationJson = """[{"nom":"pramipexole","doseMg":0.18}]""",
            caffeineAfter16h = false,
            alcoholUnits = 1.5,
            unusualExercise = false,
            notes = "scelle avant la nuit",
        )

        /** La nuit a laquelle l'instantane se rattache : `hc_snapshot` a une cle etrangere. */
        val SESSION = NightSessionEntity(
            sessionHex = "deadbeef",
            nightKey = CONTEXTE.nightKey,
            startWallMs = 1_754_517_600_000L,
            plannedStopWallMs = 1_754_546_400_000L,
            zoneId = "Asia/Tokyo",
            tzOffsetStartMin = 540,
            tzOffsetEndMin = 540,
            nominalRateHz = 50,
            modeFlags = 0,
            state = "CLOSED",
        )

        val INSTANTANE = HcSnapshotEntity(
            sessionHex = SESSION.sessionHex,
            fetchedAtMs = 1_754_550_000_000L,
            attemptIndex = 0,
            selectedPackage = "com.sec.android.app.shealth",
            selectedRecordId = "abc-123",
            lastModifiedTimeMs = 1_754_549_000_000L,
            sessionStartMs = SESSION.startWallMs,
            sessionEndMs = SESSION.plannedStopWallMs,
            stageCount = 42,
            distinctStageTypes = 4,
            stageCoverageMin = 411.0,
            overlapFraction = 0.97,
            aggregateTstMin = 398.0,
            originCount = 1,
            selectedStagesCsv = "1754517600000:1754519400000:4",
            recordsJson = """{"retenues":1,"ecartees":0}""",
            outcome = "OK",
        )
    }
}
