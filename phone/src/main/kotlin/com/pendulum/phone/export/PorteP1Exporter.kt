package com.pendulum.phone.export

import android.content.Context
import android.net.Uri
import com.pendulum.phone.db.NightSessionEntity
import com.pendulum.phone.db.PendulumDatabase
import com.pendulum.phone.db.TelemetryPointEntity
import com.pendulum.phone.ui.model.Controles
import com.pendulum.phone.ui.model.PenteBatterie
import com.pendulum.phone.ui.model.PorteP1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

/**
 * L'export du rapport de la porte P1, en CSV.
 *
 * ### Pourquoi ce fichier existe
 *
 * C'est lui qui decidera si le projet continue sur Wear OS ou bascule sur un enregistreur dedie
 * type Axivity AX3 (`01-overview.md` §5). Une decision de materiel prise sur un souvenir — « ca
 * avait l'air de tenir » — est exactement le genre de decision qu'on regrette six mois plus tard
 * sans pouvoir la rejouer. Elle doit reposer sur un fichier, et ce fichier doit se relire sans
 * l'application qui l'a produit : il porte donc ses seuils, sa conclusion et ses limites en tete.
 *
 * ### Le meme chemin SAF que le reste
 *
 * L'ecriture se fait dans un `OutputStream` fourni par l'appelant, en pratique celui d'un `Uri`
 * obtenu par `ACTION_CREATE_DOCUMENT`. L'application n'ecrit **jamais** dans un repertoire
 * partage de sa propre initiative, et elle ne declare pas la permission `INTERNET` : le fichier
 * ne peut aller qu'a l'endroit que l'utilisateur a designe.
 *
 * ### Toutes les nuits, pas les quatorze dernieres
 *
 * L'ecran en montre quatorze parce qu'il faut voir une serie et ses echecs ensemble ; le fichier
 * n'en tronque aucune. Un export qui coupe les nuits anciennes serait un export qui choisit ses
 * preuves.
 */
object PorteP1Exporter {

    /** Le nom des colonnes, dans l'ordre. En anglais, comme tout ce qui sort de l'application. */
    private val ENTETE = listOf(
        "night_key",
        "session_hex",
        "zone_id",
        "start_wall_ms",
        "end_wall_ms",
        "recorded_hours",
        "sample_count",
        "expected_samples",
        "coverage",
        "coverage_min",
        "coverage_state",
        "battery_pct_end",
        "battery_min_pct",
        // La pente, ses trois chiffres de support et son resultat. Un pourcentage extrapole sans
        // le nombre de points qui portent la droite ne se relit pas : trente points et quatre
        // cents points donnent la meme colonne si on ne l'ecrit pas.
        "battery_points_used",
        "battery_points_charging",
        "battery_uah_per_h",
        "battery_pct_at_8h",
        "battery_state",
        "fs_measured_hz",
        "fs_nominal_hz",
        "fs_tolerance",
        "fs_state",
        "gap_count",
        "gap_total_s",
        "truncated",
        "session_state",
        "stop_reason",
        "verdict",
    )

    suspend fun exportVers(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { exportCsv(context, it) }
        }
    }

    suspend fun exportCsv(context: Context, out: OutputStream) {
        val db = PendulumDatabase.get(context)
        val sessions = db.nightDao().all()
        // La meme telemetrie que celle de l'ecran, lue de la meme facon. Le fichier et l'ecran
        // doivent rendre le meme verdict sur la meme nuit : leur faire lire deux sources serait
        // rouvrir exactement l'ecart que `Controles` a ferme sur le seuil de batterie.
        val telemetrie = sessions.associate { it.sessionHex to db.telemetryDao().ofSession(it.sessionHex) }
        out.write(csv(sessions, telemetrie).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Le document complet, en memoire. Pur, donc testable — et sans risque de taille : quatre-vingts
     * nuits font quelques kilo-octets, la ou une seule nuit de signal brut en fait quatre-vingt-dix
     * mille.
     */
    internal fun csv(
        sessions: List<NightSessionEntity>,
        telemetrie: Map<String, List<TelemetryPointEntity>> = emptyMap(),
    ): String {
        val verdicts = sessions.map { PorteP1.de(it, telemetrie[it.sessionHex].orEmpty()) }
        val campagne = PorteP1.campagne(verdicts)
        return buildString {
            // Le preambule est commente `#` : les tableurs et `pandas` savent l'ignorer, et un
            // fichier qui ne porte pas ses seuils oblige a retrouver la version de l'application
            // qui l'a produit pour savoir ce que « 0,987 » voulait dire.
            appendLine("# Pendulum — P1 hardware feasibility gate")
            appendLine("# coverage is computed on sensor timestamps, never on arrival time")
            appendLine("# coverage_min=${nombre(Controles.COUVERTURE_MIN)}")
            appendLine("# battery_min_pct=${Controles.BATTERIE_MIN_PCT} at ${PorteP1.DUREE_CIBLE_H.toInt()} h")
            appendLine("# fs_tolerance=${nombre(Controles.TOLERANCE_FS)}")
            appendLine("# nights_required_consecutive=${PorteP1.NUITS_CONSECUTIVES}")
            appendLine("# nights_examined=${campagne.nuitsExaminees}")
            appendLine("# nights_inside_p1=${campagne.nuitsConformes}")
            appendLine("# longest_consecutive_run=${campagne.serieMax}")
            appendLine("# run_from=${campagne.debutSerie ?: ""}")
            appendLine("# run_to=${campagne.finSerie ?: ""}")
            appendLine("# gate_passed=${campagne.franchie}")
            // Les deux trous de mesure, dans le fichier et pas seulement a l'ecran : une colonne
            // vide dont on ignore pourquoi elle est vide se lit comme une panne.
            appendLine("# not transmitted: largest single gap (only the total reaches the phone)")
            appendLine("# battery_pct_at_8h is a least-squares fit on the coulomb counter,")
            appendLine("# charging points removed, refused below ${PenteBatterie.POINTS_MIN} points")
            appendLine("#   or ${nombre(PenteBatterie.DUREE_MIN_H, 1)} h of observed discharge")
            appendLine(ENTETE.joinToString(","))
            for ((session, verdict) in sessions.zip(verdicts)) {
                appendLine(ligne(session, verdict, telemetrie[session.sessionHex].orEmpty()))
            }
        }
    }

    private fun ligne(
        s: NightSessionEntity,
        v: PorteP1.VerdictNuit,
        telemetrie: List<TelemetryPointEntity>,
    ): String {
        val couverture = Controles.couverture(s)
        val heures = PorteP1.heuresEnregistrees(s)
        val attendus = heures?.let { it * 3_600.0 * s.nominalRateHz }
        // La meme pente que celle qui a produit `battery_state`, pas une seconde. Un fichier dont
        // les colonnes de support ne seraient pas celles qui ont porte le verdict serait pire
        // qu'un fichier sans colonnes de support.
        val pente = PenteBatterie.de(telemetrie, PorteP1.DUREE_CIBLE_H)
        return listOf(
            v.soiree.toString(),
            s.sessionHex,
            s.zoneId,
            s.startWallMs.toString(),
            s.endWallMs?.toString() ?: "",
            heures?.let { nombre(it, 3) } ?: "",
            s.sampleCount.toString(),
            attendus?.let { nombre(it, 0) } ?: "",
            couverture?.let { nombre(it, 5) } ?: "",
            nombre(Controles.COUVERTURE_MIN),
            v.couverture.etat.name,
            s.batteryPctLast?.toString() ?: "",
            Controles.BATTERIE_MIN_PCT.toString(),
            pente?.pointsRetenus?.toString() ?: "",
            pente?.pointsSousCharge?.toString() ?: "",
            pente?.let { nombre(it.penteUahParH, 1) } ?: "",
            pente?.let { nombre(it.pctA8h, 1) } ?: "",
            v.batterie.etat.name,
            s.fsMeasuredHz?.let { nombre(it, 4) } ?: "",
            s.nominalRateHz.toString(),
            nombre(Controles.TOLERANCE_FS),
            v.frequence.etat.name,
            s.gapCount.toString(),
            nombre(s.gapTotalMs / 1000.0, 1),
            s.truncated.toString(),
            s.state,
            echapper(s.stopReason.orEmpty()),
            v.verdict.name,
        ).joinToString(",")
    }

    /**
     * Point decimal, impose par la locale.
     *
     * Sur un telephone en francais, `"%.3f".format(v)` rend `0,987` — c'est-a-dire un champ qui
     * contient le separateur de colonnes. Le fichier reste syntaxiquement valide et decale toutes
     * les colonnes d'un cran a partir de la premiere valeur non entiere, ce qui est la pire des
     * corruptions : celle qui se lit sans erreur.
     */
    private fun nombre(v: Double, decimales: Int = 2): String =
        "%.${decimales}f".format(Locale.UK, v)

    /** Aucun champ ne devrait contenir de virgule ; si l'un en contient, il est cite. */
    private fun echapper(v: String): String =
        if (v.contains(',') || v.contains('"')) "\"${v.replace("\"", "\"\"")}\"" else v
}
