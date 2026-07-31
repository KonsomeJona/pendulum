package com.pendulum.phone.ingest

/**
 * La conversion entre l'horloge murale et la ligne de temps du capteur.
 *
 * ### Pourquoi ce n'est pas une soustraction
 *
 * Trois horloges cohabitent, et l'en-tete de chunk les enregistre toutes les trois exactement
 * parce qu'elles ne sont pas interchangeables :
 *
 *  - `startWallMs` — horloge murale, sujette au changement d'heure et a la resynchronisation NTP ;
 *  - `startElapsedRealtimeNs` — temps depuis le boot, monotone ;
 *  - `firstEventTimestampNs` — `SensorEvent.timestamp`, qui n'est **pas** garanti egal a
 *    `elapsedRealtimeNanos` : certains constructeurs en excluent le temps de suspend.
 *
 * Tout ce que produit `:algo` est date en millisecondes **relatives a la ligne de temps du
 * capteur**. Health Connect, lui, date en horloge murale UTC. Les deux ne se rapprochent que par
 * l'ancrage ci-dessous. Faire la difference de deux horloges murales — par exemple « debut de la
 * session de sommeil moins `startWallMs` » — donnerait un decalage silencieux de plusieurs
 * minutes des que l'horloge du telephone se resynchronise pendant la nuit, et un decalage d'une
 * heure entiere la nuit du changement d'heure. Un hypnogramme decale d'une heure ne leve aucune
 * exception : il produit simplement un aPLM-i faux.
 *
 * @param startWallMs horloge murale a l'ouverture du **premier** chunk.
 * @param firstEventTimestampNs `SensorEvent.timestamp` du premier echantillon du premier chunk.
 * @param timelineT0Ns `tFirstNs` du premier bloc effectivement decode. Il peut differer de
 *   [firstEventTimestampNs] si les tout premiers blocs ont ete rejetes par le controle
 *   d'integrite — d'ou deux champs et non un.
 */
data class TimeAnchor(
    val startWallMs: Long,
    val firstEventTimestampNs: Long,
    val timelineT0Ns: Long,
) {

    /** Decalage, en millisecondes, entre l'origine de la ligne de temps et `startWallMs`. */
    private val t0OffsetMs: Long get() = (timelineT0Ns - firstEventTimestampNs) / 1_000_000L

    /** Horloge murale (epoch ms) -> millisecondes relatives a la ligne de temps. */
    fun toMsRel(wallMs: Long): Long = (wallMs - startWallMs) - t0OffsetMs

    /** Millisecondes relatives -> horloge murale. Pour l'affichage et l'export uniquement. */
    fun toWallMs(msRel: Long): Long = startWallMs + msRel + t0OffsetMs
}
