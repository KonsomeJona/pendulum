package com.pendulum.format.wire

import java.io.IOException

/**
 * Structures de fil du transfert montre -> telephone (ARCHI-CAPTURE §2.3).
 *
 * Elles vivent dans `:format` — JVM pur, zero dependance Android — pour trois raisons :
 * les deux extremites du protocole partagent le meme code d'encodage, la symetrie
 * encodage/decodage est testable sans appareil, et le module `phone` peut relire un
 * `DataItem` sans embarquer le module `wear`.
 *
 * L'encodage est un octet de version suivi de champs petit-boutistes de taille fixe, les
 * chaines et tableaux etant prefixes de leur longueur en u16. C'est plus verbeux qu'un
 * `DataMap`, et c'est le but : un `DataMap` est un dictionnaire dont la lecture echoue en
 * silence quand une cle change de nom, alors qu'ici un changement de layout est rejete au
 * premier octet.
 */
object WireProtocol {

    /** Version des structures de fil. Independante de `ChunkFormat.FORMAT_VERSION`. */
    const val VERSION = 1

    /**
     * Rotation de chunk : le chunk courant est ferme des que `elapsed >= 300 s`
     * **ou** `bytesWritten >= 92 160`. Les deux conditions existent parce qu'aucune ne suffit :
     * la duree borne la perte en cas de mort de la montre, le plafond d'octets garantit que
     * le chunk reste sous les 100 Ko d'un `DataItem` meme si `fs` reel derive a 52,6 Hz ou
     * si un mode degrade change la cadence.
     */
    const val CHUNK_ROTATION_MS = 300_000L

    /** 90 Kio. Voir [CHUNK_ROTATION_MS] : c'est le garde-fou dur des deux. */
    const val CHUNK_ROTATION_BYTES = 92_160L

    /**
     * Cadence nominale de la telemetrie de nuit
     * ([com.pendulum.format.TelemetryPoint]) : un point par minute.
     *
     * **Elle divise [CHUNK_ROTATION_MS], et ce n'est pas un reglage.** Un chunk est l'unite de
     * perte du protocole ; si la cadence de telemetrie etait une horloge independante, un chunk
     * perdu emporterait un trou de telemetrie qu'aucun autre chunk ne comblerait, et le trou ne
     * serait meme pas comptable. En divisant la rotation, chaque chunk complet porte au moins un
     * point — cinq, en marche reelle — et le telephone peut affirmer « il manque un point » plutot
     * que de constater un silence. `WireCodecTest` verrouille la divisibilite.
     *
     * Cote montre, cette cadence n'est **pas** un nouveau timer : elle est celle de
     * `RecordingService.minuteTick`, la branche qui lit deja la batterie une fois par minute. Voir
     * la KDoc de ce tick pour pourquoi il ne reveille rien.
     */
    const val TELEMETRY_PERIOD_MS = 60_000L

    /** Plafond documente de la charge utile d'un `DataItem`. */
    const val MAX_DATA_ITEM_BYTES = 100 * 1024
}

/** Espace de noms des `DataItem` et des canaux. Les chemins sont derives de l'UUID de session. */
object WirePaths {

    const val SESSION_PREFIX = "/pendulum/session/"
    const val CHUNK_PREFIX = "/pendulum/chunk/"
    const val LIVE_PREFIX = "/pendulum/live/"
    const val ACK_PREFIX = "/pendulum/ack/"
    const val SWEEP_PREFIX = "/pendulum/sweep/"
    const val SWEEP_REQUEST = "/pendulum/sweep-request"

    /**
     * Le contexte du soir scelle, publie par le **telephone** et lu par la montre.
     *
     * C'est la seule porte du produit : `Preflight` refuse le demarrage tant que l'item n'existe
     * pas. Il vivait cote montre uniquement, dans `DataLayerTransfer`, et le telephone ne
     * l'ecrivait jamais — donc START etait bloque en permanence, avec pour tout symptome le
     * message « remplissez le formulaire du soir sur le telephone » devant un formulaire qui
     * n'existait pas.
     *
     * Il est ici, dans le module partage, pour la raison qui vaut pour tous les autres chemins :
     * **le mode de defaillance du Data Layer est le silence, pas l'erreur.** Deux constantes
     * recopiees qui divergent d'un caractere ne produisent aucun message ; elles produisent une
     * montre qui ne demarre plus jamais.
     */
    const val CONTEXT_PREFIX = "/pendulum/context/"

    /**
     * Montre → telephone : « ouvre le formulaire du soir ».
     *
     * Repli du chemin `RemoteActivityHelper`. Le telephone n'a **pas** le droit de lancer une
     * activite en recevant ce message — Android bloque les lancements depuis l'arriere-plan — il
     * poste une notification dont le tap, lui, est une exemption explicite.
     */
    const val OPEN_PHONE = "/pendulum/open-phone"

    /**
     * Telephone → montre : « demarre l'enregistrement ».
     *
     * Ecart assume vis-a-vis de `docs/06-interface.md` §2.2, qui reserve le demarrage a un geste
     * physique sur la montre. Le garde-fou reste entier : `RecordingService` re-verifie le
     * preflight avant `startSession`, donc une demande sans contexte scelle est refusee cote
     * montre quelle qu'en soit l'origine.
     */
    const val START_REQUEST = "/pendulum/start-request"

    fun context(nightKey: String) = CONTEXT_PREFIX + nightKey

    fun session(sessionHex: String) = SESSION_PREFIX + sessionHex

    /**
     * L'index est zero-pade sur 5 chiffres : l'ordre lexicographique des chemins doit
     * coincider avec l'ordre des chunks, faute de quoi un listing trie livre le chunk 10
     * avant le chunk 2.
     */
    fun chunk(sessionHex: String, idx: Int) = "$CHUNK_PREFIX$sessionHex/${"%05d".format(idx)}"

    fun live(sessionHex: String) = LIVE_PREFIX + sessionHex

    fun ack(sessionHex: String) = ACK_PREFIX + sessionHex

    fun sweep(sessionHex: String) = SWEEP_PREFIX + sessionHex

    /**
     * Cle de nuit : la date locale de la **soiree**, la bascule ayant lieu a midi.
     *
     * Un START a 1 h 30 se rattache donc au formulaire rempli la veille au soir, et non a une
     * soiree qui n'a pas encore eu lieu. Sans cette bascule, quiconque se couche apres minuit
     * verrait son contexte scelle rattache a la mauvaise nuit — et la montre refuserait de
     * demarrer en affirmant qu'il n'a rien rempli.
     *
     * La fonction est ici plutot que dans chacun des deux modules parce que les deux cotes
     * doivent produire **exactement** la meme chaine : le telephone ecrit `context(nightKey(...))`
     * et la montre lit `context(nightKey(...))`, et un decalage d'un jour est indiscernable d'une
     * absence de contexte.
     *
     * @param zone fuseau explicite. Les deux appareils sont normalement dans le meme, mais le
     *   defaut implicite rendrait le decalage invisible a la lecture du code le jour ou ils ne le
     *   seraient plus.
     */
    fun nightKey(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val local = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val soiree = if (local.hour < BASCULE_SOIREE_HEURE) local.minusDays(1) else local
        return "%04d-%02d-%02d".format(soiree.year, soiree.monthValue, soiree.dayOfMonth)
    }

    /** Midi. Avant, on est encore dans la nuit de la veille ; apres, dans la soiree du jour. */
    const val BASCULE_SOIREE_HEURE = 12
}

/** Etat d'une session vu du telephone (ARCHI-CAPTURE §2.7). */
enum class SessionState(val code: Int) {
    /** La montre a annonce la nuit et n'a pas encore annonce sa fin. */
    OPEN(0),

    /** Fermeture propre annoncee par la montre. */
    CLOSED(1),

    /** Plus de nouvelles depuis plus de 45 min : la montre reviendra peut-etre. */
    STALE(2),

    /** Delai depasse : on analyse ce qu'on a, sans attendre la suite. */
    TRUNCATED(3),
    ;

    companion object {
        fun fromCode(code: Int): SessionState = entries.firstOrNull { it.code == code }
            ?: throw WireFormatException("SessionState inconnu : $code")
    }
}

/** Cause d'arret d'une session (ARCHI-CAPTURE §3.4). Jamais devinee : `UNKNOWN` est une reponse. */
enum class StopReason(val code: Int) {
    USER(0),
    CHARGING(1),
    LOW_BATTERY(2),
    MAX_DURATION(3),
    TIME_LIMIT(4),
    WAKE_DETECTED(5),
    DISK_FULL(6),

    /** Reprise apres reboot/kill : la session n'a pas ete fermee par une condition d'arret. */
    CRASH(7),
    UNKNOWN(255),
    ;

    companion object {
        fun fromCode(code: Int): StopReason = entries.firstOrNull { it.code == code }
            ?: throw WireFormatException("StopReason inconnu : $code")
    }
}

/** Charge utile mal formee ou produite par une version incompatible. */
class WireFormatException(message: String) : IOException(message)

/** Representation hexadecimale minuscule d'un UUID de session, telle qu'elle apparait dans les chemins. */
fun ByteArray.toSessionHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

/** Inverse de [toSessionHex]. */
fun sessionHexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "chaine hexadecimale de longueur impaire : ${hex.length}" }
    return ByteArray(hex.length / 2) {
        ((digit(hex[it * 2]) shl 4) or digit(hex[it * 2 + 1])).toByte()
    }
}

private const val HEX = "0123456789abcdef"

private fun digit(c: Char): Int {
    val d = Character.digit(c, 16)
    require(d >= 0) { "caractere hexadecimal invalide : $c" }
    return d
}

/** Ecriture petit-boutiste sur un tableau qui grandit. */
internal class WireWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + n))
    }

    fun u8(v: Int) = apply {
        need(1)
        buf[pos++] = v.toByte()
    }

    fun i16(v: Int) = apply {
        need(2)
        buf[pos++] = (v and 0xFF).toByte()
        buf[pos++] = ((v shr 8) and 0xFF).toByte()
    }

    fun i32(v: Int) = apply {
        need(4)
        for (i in 0 until 4) buf[pos++] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun i64(v: Long) = apply {
        need(8)
        for (i in 0 until 8) buf[pos++] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun bool(v: Boolean) = u8(if (v) 1 else 0)

    fun optI32(v: Int?) = apply { if (v == null) bool(false) else bool(true).i32(v) }

    fun optI64(v: Long?) = apply { if (v == null) bool(false) else bool(true).i64(v) }

    fun blob(b: ByteArray) = apply {
        i16(b.size)
        need(b.size)
        System.arraycopy(b, 0, buf, pos, b.size)
        pos += b.size
    }

    fun str(s: String) = blob(s.toByteArray(Charsets.UTF_8))

    fun ints(v: IntArray) = apply {
        i16(v.size)
        for (x in v) i32(x)
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
}

/** Lecture petit-boutiste. Toute lecture au-dela de la fin leve [WireFormatException]. */
internal class WireReader(private val buf: ByteArray) {
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > buf.size) throw WireFormatException("charge utile tronquee a l'offset $pos")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun i16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or (buf[pos + 1].toInt() shl 8)
        pos += 2
        return v
    }

    fun u16(): Int = i16() and 0xFFFF

    fun i32(): Int {
        need(4)
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bool(): Boolean = u8() != 0

    fun optI32(): Int? = if (bool()) i32() else null

    fun optI64(): Long? = if (bool()) i64() else null

    fun blob(): ByteArray {
        val n = u16()
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun str(): String = String(blob(), Charsets.UTF_8)

    fun ints(): IntArray {
        val n = u16()
        return IntArray(n) { i32() }
    }

    /** Verifie l'octet de version en tete de charge utile. */
    fun version(what: String): Int {
        val v = u8()
        if (v != WireProtocol.VERSION) {
            throw WireFormatException("$what : version de fil $v, attendue ${WireProtocol.VERSION}")
        }
        return v
    }
}
