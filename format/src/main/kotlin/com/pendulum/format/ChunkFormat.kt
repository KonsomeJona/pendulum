package com.pendulum.format

/**
 * Format binaire des chunks d'enregistrement accelerometrique Pendulum.
 *
 * Contraintes de conception :
 *  - **append-only** : on n'ecrit jamais en arriere, jamais de patch d'entete a la fermeture.
 *    Une session tuee brutalement (OOM, reboot, batterie) laisse un fichier exploitable.
 *  - **blocs auto-delimites et proteges par CRC** : un bloc corrompu est saute a la lecture,
 *    jamais la nuit entiere. Un unique fichier de 8 h sans delimiteurs serait un point de
 *    defaillance unique inacceptable.
 *  - **pas de timestamp par echantillon** : le FIFO materiel echantillonne uniformement, donc
 *    (tFirstNs, tLastNs) par bloc + interpolation lineaire suffisent, et on economise 8 o
 *    par echantillon (soit ~11 Mo par nuit). Cette economie n'est licite que si un bloc ne
 *    chevauche jamais deux vidages du FIFO : c'est un contrat impose a l'ecriture
 *    ([ChunkWriter.writeBlock]) et re-verifie a la lecture.
 *
 * ### Entete de fichier — 80 octets, petit-boutiste
 * ```
 * off  taille  champ                    type
 *   0       8  FILE_MAGIC "PENDCHNK"    ascii
 *   8       2  formatVersion            u16
 *  10       2  headerSize               u16   taille totale de l'entete (80 en v1)
 *  12       2  nominalRateHz            u16
 *  14       2  fifoMaxEventCount        u16
 *  16      16  sessionUuid              octets
 *  32       8  startWallMs              i64
 *  40       8  startElapsedRealtimeNs   i64
 *  48       8  firstEventTimestampNs    i64
 *  56       4  sensorResolution         f32
 *  60       4  sensorMaxRange           f32
 *  64       4  chunkIndex               i32
 *  68       2  modeFlags                u16
 *  70       2  tzOffsetMin              i16   offset UTC local a l'ouverture, en minutes
 *  72       6  reserve, a zero
 *  78       2  headerCrc                u16 = crc16(entete[0, headerSize - 2))
 * ```
 * **Regle d'evolution du format** (F-32) : le CRC occupe *toujours* les deux derniers octets
 * de l'entete, et `headerSize` en donne la longueur. Un lecteur ancien relit donc un fichier
 * produit par un ecrivain plus recent qui n'aurait fait qu'*ajouter* des champs en queue :
 * il lit ce qu'il connait a offset fixe, verifie le CRC a `headerSize - 2` et ignore le reste.
 * `formatVersion` n'est incremente que pour un changement *incompatible* (layout de bloc,
 * quantification, semantique d'un champ existant).
 *
 * **Fuseau horaire** (F-09) : un identifiant IANA (`Europe/Paris`, jusqu'a 32 octets) ne tient
 * pas dans l'entete sans la faire exploser, et le stocker par bloc serait absurde. Le choix
 * retenu est donc de mettre ici le seul champ dont la relecture binaire a besoin —
 * l'**offset UTC en minutes** (i16, couvre -18:00..+18:00) — et de laisser l'identifiant IANA
 * complet au sidecar JSON et a [com.pendulum.format.wire.SessionHeader.zoneId], qui ne sont pas
 * contraints en taille. Consequence a faire respecter par ailleurs : **aucune duree ne se
 * calcule par difference d'horloge murale**, l'offset ne sert qu'a afficher une heure locale.
 *
 * ### Entete de bloc — 32 octets, petit-boutiste
 * ```
 * off  taille  champ
 *   0       4  BLOCK_MAGIC "BLK!"
 *   4       2  count       u16   nombre d'echantillons
 *   6       8  tFirstNs    i64
 *  14       8  tLastNs     i64
 *  22       2  flags       u16
 *  24       2  crc         u16 = crc16(entete[0, 24) puis payload)
 *  26       6  reserve, a zero
 * ```
 * Le CRC couvre l'entete de bloc **et** le payload (F-02) : les six octets d'un echantillon
 * sont localement redondants et une corruption y est benigne, alors que `count`, `tFirstNs`
 * et `tLastNs` sont uniques — les corrompre decale toute la base de temps de la nuit, ou
 * fait lire une longueur de payload fausse. Le champ `crc` est ecrit en dernier, ce qui
 * permet de le calculer en une passe sur les 24 premiers octets puis sur le payload.
 *
 * ### Marqueur de fin de fichier — 32 octets
 * ```
 * off  taille  champ
 *   0       8  FILE_FOOTER_MAGIC "ENDPEND!"
 *   8       4  blockCount        u32
 *  12       8  sampleCount       i64
 *  20       8  lastTimestampNs   i64
 *  28       2  reserve, a zero
 *  30       2  crc               u16 = crc16(footer[0, 30))
 * ```
 * Sans ce marqueur (F-37), rien ne distingue un chunk complet d'un chunk en cours d'ecriture,
 * et le telephone peut acquitter — donc faire supprimer — un fichier partiel (F-13).
 * Les compteurs redondants permettent en prime de chiffrer ce qui a ete perdu a la relecture.
 */
object ChunkFormat {

    /** Magic de l'entete de fichier. 8 octets ASCII. */
    val FILE_MAGIC = "PENDCHNK".toByteArray(Charsets.US_ASCII)

    /** Magic du marqueur de fin de fichier. 8 octets ASCII, distincts de [BLOCK_MAGIC]. */
    val FILE_FOOTER_MAGIC = "ENDPEND!".toByteArray(Charsets.US_ASCII)

    /** Magic de debut de bloc. 4 octets ASCII. */
    val BLOCK_MAGIC = "BLK!".toByteArray(Charsets.US_ASCII)

    /** Version du format. A incrementer a chaque changement incompatible de layout. */
    const val FORMAT_VERSION = 1

    /** Taille de l'entete de fichier ecrite par cette version, en octets. Multiple de 16. */
    const val HEADER_SIZE = 80

    /**
     * Prefixe minimal a lire avant de connaitre `headerSize` : magic + version + headerSize.
     * Tout lecteur commence par la, quelle que soit la version du fichier.
     */
    const val HEADER_PREFIX_SIZE = 12

    /** Taille du marqueur de fin de fichier, en octets. */
    const val FOOTER_SIZE = 32

    /** Taille de l'entete de bloc, en octets. Multiple de 32. */
    const val BLOCK_HEADER_SIZE = 32

    /** Offset du champ `crc` dans l'entete de bloc : tout ce qui precede est couvert par lui. */
    const val BLOCK_CRC_OFFSET = 24

    /** Nombre d'octets par echantillon : 3 axes x i16. */
    const val BYTES_PER_SAMPLE = 6

    /** Nombre maximal d'echantillons par bloc. */
    const val MAX_SAMPLES_PER_BLOCK = 512

    /**
     * Quantification : 1 LSB = 1/2048 g. Un i16 couvre donc -16 g a +15,9995 g
     * (32767/2048, il manque un LSB du cote positif), bien au-dela de ce qu'une cheville
     * produit, et la resolution (0,00049 g) reste tres inferieure au plancher de bruit
     * d'un accelerometre MEMS.
     */
    const val LSB_PER_G = 2048.0

    /** Acceleration standard de la pesanteur, en m/s^2 (valeur exacte du SI). */
    const val G_IN_MS2 = 9.80665

    /**
     * Ecart relatif tolere entre la cadence implicite d'un bloc — `(tLast - tFirst)/(N-1)` —
     * et la periode nominale `1e9/fs`. Au-dela, le bloc chevauche vraisemblablement deux
     * vidages du FIFO et l'interpolation lineaire de [DecodedBlock.timestampNs] date *tous*
     * ses echantillons faux (F-03). Refuse a l'ecriture, signale a la lecture.
     */
    const val TIMEBASE_TOLERANCE = 0.20

    // --- Drapeaux de bloc (champ u16 `flags`) ---

    /** Ce bloc commence sur une frontiere de vidage du FIFO materiel. */
    const val FLAG_FIFO_BOUNDARY = 1 shl 0

    /** Un trou est suspecte juste avant ce bloc (ecart de timestamp anormal, ou reprise apres crash). */
    const val FLAG_GAP_BEFORE = 1 shl 1

    /** Le capteur off-body signalait "non porte" pendant ce bloc. */
    const val FLAG_OFF_BODY = 1 shl 2

    /**
     * Au moins un echantillon de ce bloc a sature a +/-32767 LSB (F-11). Pose par l'ecrivain :
     * sans lui, l'ecretage serait indetectable a la relecture, et le distracteur "clipping"
     * de la phase de verification passerait pour du mouvement reel.
     */
    const val FLAG_SATURATED = 1 shl 3

    /**
     * Au moins un echantillon de ce bloc etait NaN ou infini et a ete remplace par 0 (F-11),
     * ce qui est indiscernable d'une chute libre. Ce drapeau est la seule trace du remplacement.
     */
    const val FLAG_NON_FINITE = 1 shl 4

    // --- Drapeaux de mode d'acquisition (champ u16 `modeFlags` de l'entete) ---

    /** Le capteur utilise est la variante wake-up. */
    const val MODE_WAKEUP_SENSOR = 1 shl 0

    /** Un batching materiel est demande (maxReportLatencyUs > 0). */
    const val MODE_BATCHED = 1 shl 1

    /** Un PARTIAL_WAKE_LOCK est detenu pendant l'acquisition. */
    const val MODE_WAKE_LOCK = 1 shl 2

    /** Le wake lock a ete pris en cours de route par auto-degradation (des trous ont ete detectes). */
    const val MODE_DEGRADED = 1 shl 3

    /**
     * Convertit une acceleration en m/s^2 vers l'entier quantifie du format, avec saturation.
     *
     * `Math.round(Double)` renvoie un `Long` : les bornes doivent etre des `Long`, sans quoi
     * aucune surcharge de `coerceIn` ne s'applique et le module ne compile pas.
     * Une valeur non finie (capteur en defaut) est ramenee a 0, ce qui est indiscernable
     * d'une chute libre : l'appelant doit compter ces cas et poser un drapeau.
     */
    fun toRaw(ms2: Float): Short {
        if (!ms2.isFinite()) return 0
        val lsb = Math.round(ms2 / G_IN_MS2 * LSB_PER_G)
        return lsb.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
    }

    /** Convertit un entier quantifie du format vers une acceleration en m/s^2. */
    fun toMs2(raw: Short): Float = (raw / LSB_PER_G * G_IN_MS2).toFloat()

    /**
     * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, pas de reflexion, pas de xorout).
     * Choisi pour sa simplicite d'implementation sans table et son cout negligeable :
     * detecter un bloc corrompu importe plus que la force du code.
     *
     * @param seed etat initial, pour chainer le calcul sur plusieurs tableaux (entete de bloc
     *   puis payload) sans les concatener en memoire.
     */
    fun crc16(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        seed: Int = 0xFFFF,
    ): Int {
        var crc = seed and 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /**
     * Cadence implicite d'un bloc, en nanosecondes par echantillon. `NaN` si le bloc n'a
     * qu'un echantillon (aucun intervalle observable, donc rien a verifier).
     */
    fun meanIntervalNs(count: Int, tFirstNs: Long, tLastNs: Long): Double =
        if (count <= 1) Double.NaN else (tLastNs - tFirstNs).toDouble() / (count - 1)

    /**
     * Vrai si la cadence implicite du bloc est compatible avec `nominalRateHz` a
     * [TIMEBASE_TOLERANCE] pres. Un bloc a un seul echantillon est toujours accepte.
     */
    fun isTimebasePlausible(count: Int, tFirstNs: Long, tLastNs: Long, nominalRateHz: Int): Boolean {
        val mean = meanIntervalNs(count, tFirstNs, tLastNs)
        if (mean.isNaN()) return true
        if (nominalRateHz <= 0) return true
        val expected = 1e9 / nominalRateHz
        return Math.abs(mean - expected) / expected <= TIMEBASE_TOLERANCE
    }
}

/**
 * Metadonnees d'un fichier de chunk. Toutes ces valeurs sont figees a l'ouverture du
 * fichier et jamais reecrites.
 *
 * @param sessionUuid identifiant de la nuit (16 octets), commun a tous les chunks d'une session.
 * @param chunkIndex index du chunk dans la session, croissant a partir de 0.
 * @param nominalRateHz frequence *demandee* au capteur. La frequence reelle est recalculee
 *   a l'analyse depuis les timestamps : elle en devie systematiquement (50 -> 50,3 ou 52,6 Hz)
 *   et un fs faux decale les filtres et les durees de mouvement.
 * @param startWallMs horloge murale (epoch ms) a l'ouverture du chunk.
 * @param startElapsedRealtimeNs `SystemClock.elapsedRealtimeNanos()` a l'ouverture du chunk.
 * @param firstEventTimestampNs `SensorEvent.timestamp` du premier echantillon du chunk.
 *   Le triplet des trois horloges permet de detecter la derive : `SensorEvent.timestamp`
 *   n'est pas garanti egal a `elapsedRealtimeNanos` (certains OEM excluent le temps de suspend),
 *   et sans cette detection la fusion avec l'hypnogramme se decale de plusieurs minutes.
 * @param tzOffsetMin offset UTC local en minutes a l'ouverture du chunk. Aucun defaut :
 *   l'oubli du fuseau est precisement le defaut qu'on corrige, il doit couter une decision.
 *   L'identifiant IANA vit dans le sidecar (voir la KDoc de [ChunkFormat]).
 * @param headerSize taille de l'entete telle qu'elle a ete lue. Vaut [ChunkFormat.HEADER_SIZE]
 *   pour un fichier ecrit par cette version ; peut etre plus grande pour un fichier produit
 *   par une version plus recente qui a ajoute des champs en queue.
 */
data class ChunkHeader(
    val sessionUuid: ByteArray,
    val chunkIndex: Int,
    val nominalRateHz: Int,
    val startWallMs: Long,
    val startElapsedRealtimeNs: Long,
    val firstEventTimestampNs: Long,
    val sensorResolution: Float,
    val sensorMaxRange: Float,
    val fifoMaxEventCount: Int,
    val modeFlags: Int,
    val tzOffsetMin: Int,
    val formatVersion: Int = ChunkFormat.FORMAT_VERSION,
    val headerSize: Int = ChunkFormat.HEADER_SIZE,
) {
    init {
        require(sessionUuid.size == 16) { "sessionUuid doit faire 16 octets, recu ${sessionUuid.size}" }
        // Les champs u16 tronquaient en silence : un FIFO de 70000 evenements etait enregistre
        // a 4464, et le budget de latence calcule dessus etait faux d'un facteur 15 (F-26).
        require(nominalRateHz in 1..0xFFFF) { "nominalRateHz hors u16 : $nominalRateHz" }
        require(fifoMaxEventCount in 0..0xFFFF) { "fifoMaxEventCount hors u16 : $fifoMaxEventCount" }
        require(modeFlags in 0..0xFFFF) { "modeFlags hors u16 : $modeFlags" }
        require(chunkIndex >= 0) { "chunkIndex negatif : $chunkIndex" }
        // -18:00..+18:00 : la plage effectivement couverte par la base IANA, historique compris.
        require(tzOffsetMin in -1080..1080) { "tzOffsetMin hors plage : $tzOffsetMin" }
        require(headerSize >= ChunkFormat.HEADER_SIZE) { "headerSize trop petit : $headerSize" }
    }

    // equals/hashCode manuels : ByteArray a une identite par reference.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkHeader) return false
        return sessionUuid.contentEquals(other.sessionUuid) &&
            chunkIndex == other.chunkIndex &&
            nominalRateHz == other.nominalRateHz &&
            startWallMs == other.startWallMs &&
            startElapsedRealtimeNs == other.startElapsedRealtimeNs &&
            firstEventTimestampNs == other.firstEventTimestampNs &&
            sensorResolution == other.sensorResolution &&
            sensorMaxRange == other.sensorMaxRange &&
            fifoMaxEventCount == other.fifoMaxEventCount &&
            modeFlags == other.modeFlags &&
            tzOffsetMin == other.tzOffsetMin &&
            formatVersion == other.formatVersion &&
            headerSize == other.headerSize
    }

    override fun hashCode(): Int {
        var result = sessionUuid.contentHashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + nominalRateHz
        result = 31 * result + startWallMs.hashCode()
        result = 31 * result + startElapsedRealtimeNs.hashCode()
        result = 31 * result + firstEventTimestampNs.hashCode()
        result = 31 * result + sensorResolution.hashCode()
        result = 31 * result + sensorMaxRange.hashCode()
        result = 31 * result + fifoMaxEventCount
        result = 31 * result + modeFlags
        result = 31 * result + tzOffsetMin
        result = 31 * result + formatVersion
        result = 31 * result + headerSize
        return result
    }
}

/**
 * Un bloc decode. Les echantillons sont en m/s^2, deja dequantifies.
 *
 * @param tFirstNs timestamp du premier echantillon (echelle `SensorEvent.timestamp`).
 * @param tLastNs timestamp du dernier echantillon. Egal a `tFirstNs` si le bloc n'a qu'un echantillon.
 * @param suspectTimebase pose par le lecteur quand la cadence implicite du bloc s'ecarte de plus
 *   de [ChunkFormat.TIMEBASE_TOLERANCE] du nominal : les timestamps interpoles sont alors faux
 *   d'un montant inconnu et le bloc ne doit pas servir a dater un evenement.
 */
class DecodedBlock(
    val tFirstNs: Long,
    val tLastNs: Long,
    val flags: Int,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray,
    val suspectTimebase: Boolean = false,
) {
    val sampleCount: Int get() = x.size

    /**
     * Timestamp interpole du i-eme echantillon. Valide parce que le FIFO materiel
     * echantillonne a cadence uniforme entre deux vidages — d'ou l'interdiction faite a
     * l'ecrivain de laisser un bloc chevaucher deux vidages.
     */
    fun timestampNs(i: Int): Long {
        if (sampleCount <= 1) return tFirstNs
        val step = (tLastNs - tFirstNs).toDouble() / (sampleCount - 1)
        return tFirstNs + Math.round(step * i)
    }
}
