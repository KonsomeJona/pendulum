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
 * ### Bloc de telemetrie — entete de 16 octets, puis `count` points de [TELEMETRY_POINT_SIZE]
 * ```
 * off  taille  champ
 *   0       4  TELEMETRY_MAGIC "TLM!"
 *   4       2  count       u16   nombre de points
 *   6       2  pointSize   u16   taille d'un point, en octets
 *   8       2  flags       u16   reserve, a zero
 *  10       2  crc         u16 = crc16(entete[0, 10) puis payload)
 *  12       4  reserve, a zero
 * ```
 * **Pourquoi la telemetrie voyage dans les chunks, et pas sur un canal a elle.**
 *
 *  1. Elle herite de toute la durabilite deja construite **et deja verifiee sur materiel reel** :
 *     ecriture append-only, CRC-16 par bloc, CRC-32 de transport, poussee toutes les 15 min,
 *     accuse, suppression seulement apres accuse. Il n'y a rien de neuf a fiabiliser.
 *  2. Un second chemin de transport serait un second mode de panne. La lecon constante de ce
 *     depot est que **le Data Layer echoue par le silence** — la permission `BIND_WEARABLE_LISTENER`
 *     manquante, le `start-request` que personne n'emettait, la cle de nuit qui vivait d'un seul
 *     cote. Chaque chemin de plus est une occasion de plus d'echouer sans rien dire.
 *  3. Le debit est derisoire : un point par minute contre cinquante echantillons par seconde,
 *     soit ~23 Ko contre ~9 Mo sur une nuit de huit heures. Aucun arbitrage de taille n'est
 *     deplace — et **surtout pas** [com.pendulum.format.wire.WireProtocol.CHUNK_ROTATION_BYTES],
 *     qui reste le garde-fou dur du plafond de 100 Ko d'un `DataItem`.
 *
 * `pointSize` porte pour le point la meme regle d'evolution que `headerSize` pour l'entete de
 * fichier : un ecrivain plus recent peut **ajouter des champs en queue du point**, un lecteur
 * ancien lit ce qu'il connait a offset fixe et saute le reste. Un `pointSize` plus **petit** que
 * [TELEMETRY_POINT_SIZE] est en revanche rejete — il n'y aurait pas de quoi remplir les champs.
 *
 * ### Marqueur de fin de fichier — 32 octets
 * ```
 * off  taille  champ
 *   0       8  FILE_FOOTER_MAGIC "ENDPEND!"
 *   8       4  blockCount        u32
 *  12       8  sampleCount       i64
 *  20       8  lastTimestampNs   i64
 *  28       2  telemetryCount    u16   nombre de points de telemetrie du chunk
 *  30       2  crc               u16 = crc16(footer[0, 30))
 * ```
 * Sans ce marqueur (F-37), rien ne distingue un chunk complet d'un chunk en cours d'ecriture,
 * et le telephone peut acquitter — donc faire supprimer — un fichier partiel (F-13).
 * Les compteurs redondants permettent en prime de chiffrer ce qui a ete perdu a la relecture.
 * `telemetryCount` occupe les deux octets que la v1 laissait a zero : un chunk v1 relu par cette
 * version annonce donc zero point de telemetrie, ce qui est exactement la verite.
 */
object ChunkFormat {

    /** Magic de l'entete de fichier. 8 octets ASCII. */
    val FILE_MAGIC = "PENDCHNK".toByteArray(Charsets.US_ASCII)

    /** Magic du marqueur de fin de fichier. 8 octets ASCII, distincts de [BLOCK_MAGIC]. */
    val FILE_FOOTER_MAGIC = "ENDPEND!".toByteArray(Charsets.US_ASCII)

    /** Magic de debut de bloc. 4 octets ASCII. */
    val BLOCK_MAGIC = "BLK!".toByteArray(Charsets.US_ASCII)

    /** Magic de debut de bloc de telemetrie. 4 octets ASCII, distincts de [BLOCK_MAGIC]. */
    val TELEMETRY_MAGIC = "TLM!".toByteArray(Charsets.US_ASCII)

    /**
     * Version du format. A incrementer a chaque changement incompatible de layout.
     *
     * **v2 — le bloc de telemetrie.** L'ajout est additif a l'ecriture, mais il est
     * *incompatible a la lecture*, et c'est pour cela que la version bouge : un lecteur v1
     * ne connait pas `TLM!`, le compte comme un magic absent, se resynchronise sur le bloc de
     * signal suivant, et rend `desynchronised = true`. Il annoncerait donc un fichier **abime**
     * la ou le fichier est parfaitement sain — exactement le genre de faux rouge qui envoie
     * chercher une panne inexistante. La version l'annonce avant que ca n'arrive.
     *
     * Le sens inverse, lui, est garanti sans reserve : un chunk v1 se decode toujours par cette
     * version (`ChunkCodecTest.un chunk du format v1 se decode toujours`), parce que le depot ne
     * supprime jamais du brut — c'est la seule chose qui permettra de rescorer le jour ou
     * l'algorithme changera.
     */
    const val FORMAT_VERSION = 2

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

    /** Taille de l'entete d'un bloc de telemetrie, en octets. */
    const val TELEMETRY_HEADER_SIZE = 16

    /** Offset du champ `crc` dans l'entete de telemetrie : tout ce qui precede est couvert par lui. */
    const val TELEMETRY_CRC_OFFSET = 10

    /** Taille d'un point de telemetrie ecrit par cette version, en octets. Multiple de 8. */
    const val TELEMETRY_POINT_SIZE = 48

    /**
     * Nombre maximal de points dans un bloc de telemetrie. Garde-fou de corruption, comme
     * [MAX_SAMPLES_PER_BLOCK] : c'est lui qui empeche un `count` corrompu de faire lire une
     * longueur de payload aberrante. Il est choisi pour qu'un bloc de telemetrie plein reste
     * plus petit qu'un bloc de signal plein, et donc pour que la fenetre du lecteur — dimensionnee
     * sur le plus grand bloc — n'ait pas a grandir.
     */
    const val MAX_TELEMETRY_POINTS = 64

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

    /**
     * Au moins un echantillon de ce bloc a touche la **dynamique du capteur** — `sensorMaxRange`
     * de l'entete, typiquement 78,45 m/s2 (8 g) ou 39,23 (4 g).
     *
     * Distinct de [FLAG_SATURATED], qui marque le plafond du **format** a 16 g. Les deux ne se
     * recouvrent pas : un capteur a 8 g s'ecrete a la moitie de ce que le format sait coder, donc
     * un mouvement peut etre ecrete par le materiel sans jamais approcher [FLAG_SATURATED] — et
     * l'ecretage etait alors totalement invisible a la relecture.
     *
     * Ce que ce drapeau explique : au-dela du rail, l'enveloppe du mouvement est artificiellement
     * **plate au sommet**. L'artefact ressemble a un vrai plateau, il sous-estime l'amplitude, et
     * c'est l'amplitude qui decide du seuil de detection. Un mouvement retenu ou rejete sur un bloc
     * qui porte ce drapeau n'a pas ete decide sur le signal, il a ete decide sur son ecretage.
     */
    const val FLAG_SENSOR_CLIPPED = 1 shl 5

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

/**
 * L'etat de l'appareil a un instant de la nuit — 48 octets, petit-boutiste.
 *
 * Deux usages, et chaque champ sert au moins l'un des deux :
 *
 *  1. **Rendre la porte P1 auto-suffisante.** Son second critere est « batterie > 20 % restants a
 *     huit heures ». La montre tenait deja une serie de batterie complete qu'elle ne publiait
 *     pas : la mesure de veille du 3 aout 2026 (`docs/fr/BANC-ESSAI.md` §12.4) n'a donc pas pu
 *     chiffrer la batterie du tout, et la seule facon envisagee de la chiffrer etait de garder un
 *     lien ADB pendant la nuit — c'est-a-dire de laisser la montre sur son socle, ce qui fausse
 *     precisement la grandeur mesuree.
 *  2. **Expliquer pourquoi un mouvement a ete retenu ou non.** Chacune de ces grandeurs decide du
 *     seuil de detection ou de la datation, et aucune n'etait visible cote telephone.
 *
 * ### Layout — 48 octets
 * ```
 * off  taille  champ                 type
 *   0       8  elapsedRealtimeNs     i64
 *   8       8  sensorTsNs            i64
 *  16       4  batteryChargeUah      i32
 *  20       4  maxIntervalUs         u32
 *  24       4  fsyncTotalUs          u32
 *  28       4  fsyncMaxUs            u32
 *  32       2  temperatureDeciC      i16
 *  34       2  measuredRateCentiHz   u16
 *  36       2  jitterStdUs           u16
 *  38       2  clippedSamples        u16
 *  40       2  fsyncCount            u16
 *  42       1  batteryPct            u8
 *  43       1  offBody               u8
 *  44       1  charging              u8
 *  45       3  reserve, a zero
 * ```
 *
 * **Convention des compteurs** : `fsyncCount`, `fsyncTotalUs`, `fsyncMaxUs` et `clippedSamples`
 * comptent **depuis le point precedent**, pas depuis le debut de la nuit. Le point porte sa propre
 * datation, donc un point perdu coute une minute d'attribution et rien de plus ; des compteurs
 * cumulatifs auraient demande au telephone de differencier une serie dont il ne saurait pas si
 * elle a des trous.
 *
 * @param elapsedRealtimeNs `SystemClock.elapsedRealtimeNanos()` au moment du point. C'est la seule
 *   horloge sur laquelle une duree se calcule — l'horloge murale saute au changement d'heure et a
 *   la resynchronisation NTP —, et l'entete du chunk porte deja de quoi la ramener a une heure
 *   murale (`startWallMs` / `startElapsedRealtimeNs`). D'ou l'absence d'un champ d'horloge murale.
 * @param sensorTsNs dernier `SensorEvent.timestamp` vu au moment du point, ou 0 si aucun.
 *   C'est lui qui ancre la telemetrie sur la **base de temps des echantillons**, la seule qui date
 *   les mouvements : sans elle, aligner « la temperature a chute » sur « ce mouvement a ete
 *   rejete » passerait par une conversion d'horloge dont le §12.4 montre qu'elle derive.
 * @param batteryChargeUah `BATTERY_PROPERTY_CHARGE_COUNTER`, en micro-amperes-heures, ou
 *   [CHARGE_INCONNUE]. C'est **la** grandeur qui rend le critere batterie de P1 mesurable sur une
 *   nuit courte : le pourcentage est quantifie au point qu'une demi-heure de veille le laisse a
 *   100 %, alors que le compteur coulombmetrique donne une pente, et une pente s'extrapole a huit
 *   heures. Le courant moyen instantane n'est **pas** collecte : il est la derivee de deux points
 *   consecutifs de ce compteur, donc redondant.
 * @param maxIntervalUs pire intervalle entre deux echantillons consecutifs sur la derniere fenetre
 *   de mesure close. La moyenne le cache : c'est le pire cas qui borne l'erreur de datation d'un
 *   mouvement, puisque le format interpole lineairement entre `tFirstNs` et `tLastNs`.
 * @param fsyncTotalUs temps cumule passe dans les `fsync` depuis le point precedent, en
 *   microsecondes. Un `fsync` gele brievement le processeur ; c'est le budget de gel de la periode.
 * @param fsyncMaxUs pire `fsync` de la periode. C'est celui-la, et pas le cumul, qui explique une
 *   interruption capteur ratee a un instant precis.
 * @param temperatureDeciC temperature de la batterie en dixiemes de degre Celsius, ou
 *   [TEMPERATURE_INCONNUE]. **Validateur croise de l'off-body** : une chute franche, c'est la perte
 *   du couplage thermique avec la peau, donc la montre retiree. Plus fiable que le detecteur
 *   off-body seul, dont la KDoc de `RecordingService` dit qu'a la cheville il lit tres
 *   probablement « non porte » en permanence.
 * @param measuredRateCentiHz `fs` reellement delivre sur la derniere fenetre close, en centiemes
 *   de hertz (50 Hz -> 5000). Deja calcule par `GapMonitor` et jusqu'ici jamais transmis point par
 *   point : seule sa derniere valeur partait, dans le sidecar.
 * @param jitterStdUs ecart-type des intervalles inter-echantillons sur la derniere fenetre close,
 *   en microsecondes, sature a 65 535. **C'est la dispersion, pas la moyenne, qui decide de la
 *   datation** : une cadence moyenne parfaite obtenue en alternant 10 et 30 ms date chaque
 *   echantillon a 10 ms pres, et le moniteur de trous n'y voyait rien.
 * @param clippedSamples echantillons ayant touche la dynamique du capteur depuis le point
 *   precedent. Voir [ChunkFormat.FLAG_SENSOR_CLIPPED] pour ce que l'ecretage fausse ; le drapeau
 *   localise l'artefact au bloc pres, ce compteur en donne le volume.
 * @param batteryPct 0..100, ou [BATTERIE_INCONNUE].
 * @param offBody [OFF_BODY_PORTE], [OFF_BODY_RETIRE] ou [OFF_BODY_ABSENT]. Lu par
 *   `RecordingService` depuis `TYPE_LOW_LATENCY_OFFBODY_DETECT` depuis toujours, **journalise et
 *   jamais transmis**.
 * @param charging vrai si le chargeur est connecte. Un point sous charge ne dit rien de
 *   l'autonomie et doit sortir de toute regression de pente — c'est exactement le cas du §12.4,
 *   ou la montre est restee sur son socle.
 *
 * **Ecartes, et pourquoi.** `modeFlags` et le palier de degradation : ils vivent dans l'entete du
 * chunk, et tout changement de mode **force une rotation** (`RecordingService.applyDegradation`),
 * donc l'entete du fichier decrit deja exactement tous ses blocs et tous ses points — les repeter
 * par point serait une seconde source de verite pour rien. L'espace disque libre : il decide d'un
 * arret (`StopConditions`), il n'explique aucun mouvement. L'etat thermique du systeme
 * (`PowerManager.getCurrentThermalStatus`) : un accelerometre a 50 Hz ne fait pas etrangler une
 * montre, et on ne saurait rien en faire.
 */
data class TelemetryPoint(
    val elapsedRealtimeNs: Long,
    val sensorTsNs: Long,
    val batteryChargeUah: Int,
    val maxIntervalUs: Long,
    val fsyncTotalUs: Long,
    val fsyncMaxUs: Long,
    val temperatureDeciC: Int,
    val measuredRateCentiHz: Int,
    val jitterStdUs: Int,
    val clippedSamples: Int,
    val fsyncCount: Int,
    val batteryPct: Int,
    val offBody: Int,
    val charging: Boolean,
) {
    init {
        // Meme discipline que `ChunkHeader` (F-26) : un `.toShort()` silencieux enregistrait un
        // FIFO de 70 000 evenements a 4 464, et tout ce qui etait budgete dessus etait faux.
        // Un depassement se refuse ici ; c'est a l'appelant de saturer explicitement, avec
        // [borneU16] et [borneU32], parce que saturer est une decision.
        require(maxIntervalUs in 0..0xFFFFFFFFL) { "maxIntervalUs hors u32 : $maxIntervalUs" }
        require(fsyncTotalUs in 0..0xFFFFFFFFL) { "fsyncTotalUs hors u32 : $fsyncTotalUs" }
        require(fsyncMaxUs in 0..0xFFFFFFFFL) { "fsyncMaxUs hors u32 : $fsyncMaxUs" }
        require(temperatureDeciC in -32768..32767) { "temperatureDeciC hors i16 : $temperatureDeciC" }
        require(measuredRateCentiHz in 0..0xFFFF) { "measuredRateCentiHz hors u16 : $measuredRateCentiHz" }
        require(jitterStdUs in 0..0xFFFF) { "jitterStdUs hors u16 : $jitterStdUs" }
        require(clippedSamples in 0..0xFFFF) { "clippedSamples hors u16 : $clippedSamples" }
        require(fsyncCount in 0..0xFFFF) { "fsyncCount hors u16 : $fsyncCount" }
        // Bornes de **largeur** uniquement, et pas de plage de valeurs. Toute valeur decodable
        // depuis un u8 doit pouvoir construire un point : sans cela, un octet corrompu dont le
        // CRC retomberait juste — ou un ecrivain plus recent ayant ajoute une sentinelle — ferait
        // lever le lecteur, dont le contrat est de ne lever que sur une entete de fichier
        // invalide. Un `batteryPct` hors 0..100 et different de [BATTERIE_INCONNUE] est une
        // lecture a ne pas croire, pas une raison de perdre le reste du chunk.
        require(batteryPct in 0..0xFF) { "batteryPct hors u8 : $batteryPct" }
        require(offBody in 0..0xFF) { "offBody hors u8 : $offBody" }
    }

    companion object {

        /** Aucune lecture de batterie n'a abouti. Distinct de 0 %, qui est une vraie valeur. */
        const val BATTERIE_INCONNUE = 255

        /** `BATTERY_PROPERTY_CHARGE_COUNTER` non supporte par l'appareil. */
        const val CHARGE_INCONNUE = Int.MIN_VALUE

        /** Aucune temperature lisible. Vaut -3276,8 degres, donc jamais confondable avec une mesure. */
        const val TEMPERATURE_INCONNUE = -32768

        const val OFF_BODY_PORTE = 0
        const val OFF_BODY_RETIRE = 1

        /** L'appareil n'a pas de `TYPE_LOW_LATENCY_OFFBODY_DETECT`. */
        const val OFF_BODY_ABSENT = 255

        /**
         * Saturation explicite vers un u16. Une valeur qui deborde est **plafonnee**, pas
         * tronquee : un ecart-type de 80 ms tronque ressortirait a 14 464 us, c'est-a-dire a une
         * cadence saine, et le defaut se lirait comme son contraire.
         */
        fun borneU16(v: Long): Int = v.coerceIn(0L, 0xFFFFL).toInt()

        /** Voir [borneU16]. */
        fun borneU32(v: Long): Long = v.coerceIn(0L, 0xFFFFFFFFL)
    }
}
