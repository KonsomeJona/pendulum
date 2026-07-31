# Pendulum — Estimation du PLM Index par accéléromètre de cheville (Wear OS)

## Contexte

**Le besoin.** Détecter les mouvements périodiques de jambes pendant le sommeil (PLMS) avec une montre Wear OS portée à la cheville, croiser le résultat avec les stades de sommeil mesurés au poignet, et produire un indicateur de **dépistage** orienté syndrome des jambes sans repos (SJSR) / PLMD — sur plusieurs nuits, à usage personnel.

**Pourquoi ça vaut le coup.** La recherche d'antériorité ne trouve **aucune app grand public ni projet open source** qui fasse ça. Les seuls précédents sont médicaux et hors de portée (SOMNOwatch, 1 862 € TTC, vendu aux labos), morts (PAM-RL de Philips, Actiwatch arrêtée fin 2022, RestEaZe dont les domaines ne résolvent plus), ou académiques sur un seul sujet (prototype iPhone, N=1).

**Pourquoi c'est faisable.** Spektor et al. (*Clocks & Sleep* 2024) valident un accéléromètre tri-axial **unilatéral à la cheville** contre la polysomnographie : r = 0,98, sensibilité 86,7 % / spécificité 92,3 % au niveau événement, et 85,7 % / 95,2 % pour classer un PLMI ≥ 15/h. C'est exactement la configuration visée. Les échecs publiés (ActiGraph GT3X, « very low similarity with PSG ») sont dus à des *activity counts* propriétaires agrégés et une fréquence d'échantillonnage trop basse — pas à une limite physique du capteur.

**Résultat attendu.** Un PLMI estimé par nuit sur ≥ 3 nuits (idéalement 5-7), un Periodicity Index de Ferri comme garde-fou, un questionnaire RLS validé, et un rapport exportable présentable à un médecin du sommeil.

---

## Hypothèses et niveau de confiance

| Hypothèse | Confiance | Note |
|---|---|---|
| L'accéléromètre brut 50 Hz est atteignable 8 h sur Pixel Watch 3 | **Moyenne** | Health Services n'expose aucun accéléro brut → `SensorManager` est la seule voie (fait vérifié). L'autonomie réelle n'est documentée nulle part : Google donne 8-10 h en GPS continu comme point d'ancrage. **À mesurer en phase 1.** |
| Une montre à la cheville est vue comme « portée » | **Basse** | La détection off-body repose sur le PPG/capacitif. Des utilisateurs rapportent une veille profonde hors poignet. Non documenté pour la cheville. **À mesurer en phase 1.** |
| Le FIFO matériel préserve les échantillons en suspend | **Basse** | Très variable selon OEM. Stratégie de repli prévue (wake lock), sans contrainte ici puisqu'on ne publie pas sur Play. |
| L'algorithme AASM/WASM transposé à l'enveloppe accélérométrique donne un PLMI utile | **Moyenne** | Extrapolation par analogie depuis S-PLMAD (EMG). Le seul point d'appui empirique direct est Spektor 2024, dont l'algorithme est propriétaire. |

**Décisions actées :** Pixel Watch 3 à la cheville · stades de sommeil depuis la Galaxy Watch 5 au poignet (Samsung Health → Health Connect) ou Sleep as Android · **usage personnel, sideload uniquement** · masque de sommeil hybride · Kotlin · post-traitement au réveil.

**Ce que le sideload change :** pas de MDR (le règlement s'applique à la mise sur le marché), pas de formulaire Health apps declaration, pas de *Battery Technical Quality Enforcement*, wake lock libre. Dès qu'une publication est envisagée, un score de risque de pathologie fait très probablement basculer l'app en dispositif médical classe IIa (MDR annexe VIII règle 11) et les disclaimers ne protègent pas — le régulateur juge l'usage revendiqué, pas la mention légale.

---

## Structure du projet

Quatre modules Gradle. Règle directrice : **tout ce qui peut être testé sur JVM sort des modules Android.**

```
pendulum/
├─ settings.gradle.kts            :algo :format :wear :phone
├─ gradle/libs.versions.toml      version catalog (greenfield → on modernise)
├─ algo/    kotlin("jvm")         DSP + scoring, zéro import android.*
├─ format/  kotlin("jvm")         codec binaire des chunks, partagé wear/phone
├─ wear/    com.android.application
└─ phone/   com.android.application
```

Packages : `com.pendulum.algo.{dsp,detect,mask,model,synth}` · `com.pendulum.format` · `com.pendulum.wear.{record,transfer,ui}` · `com.pendulum.phone.{ingest,health,db,work,ui,quiz}`.

Versions : Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21 (plugin `compose-compiler`), JVM 17, `compileSdk 35`, wear `minSdk 33`, phone `minSdk 30`.

Dépendances : wear → `androidx.wear:wear:1.3.0`, `androidx.wear.compose:compose-material:1.4.1`, `play-services-wearable:19.0.0`, `work-runtime-ktx:2.10.0`, `lifecycle-service:2.8.7` · phone → `androidx.health.connect:connect-client:1.1.0`, `room:2.6.1` + KSP, `work-runtime-ktx`, `play-services-wearable`, `material3:1.3.1`, `navigation-compose:2.8.5` · algo/format → stdlib seule, tests `kotlin-test` + `junit5` + `assertj`.

---

## Module `wear` — capture

Classes : `RecordingService` (foreground) · `SensorPipeline` · `ChunkWriter` · `SessionStore` · `OffBodyLogger` · `BatteryLogger` · `BootReceiver` · `TransferWorker` · `RecordScreen`.

**Foreground service.** `foregroundServiceType="health"` — le timeout 6 h/24 h d'Android 15 ne concerne que `dataSync` et `mediaProcessing`, `health` n'en a pas. Manifest : `FOREGROUND_SERVICE_HEALTH`, `ACTIVITY_RECOGNITION` (runtime, exigée par le type `health`), `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`. `START_STICKY`, `onTaskRemoved` ne stoppe rien.

**Stratégie FIFO, décidée à l'exécution et inscrite dans l'entête du chunk :**

```kotlin
val sensor = getDefaultSensor(TYPE_ACCELEROMETER, true) ?: getDefaultSensor(TYPE_ACCELEROMETER)
val fifo   = max(sensor.fifoReservedEventCount, sensor.fifoMaxEventCount)
mode = when {
  sensor.isWakeUpSensor && fifo >= 500  -> BATCHED_WAKEUP(latency = 0.5*fifo/50 s, clamp 10..60 s)
  sensor.isWakeUpSensor && fifo in 1..499 -> BATCHED_WAKELOCK(...) + PARTIAL_WAKE_LOCK
  else                                  -> CONTINUOUS_WAKELOCK(latency = 0) + PARTIAL_WAKE_LOCK
}
```

Auto-dégradation : si un trou > 3 s est détecté 3 fois en 10 min, on reprend le wake lock et on le trace. **Ne jamais démarrer d'`ExerciseClient`** — piège Samsung documenté : il force l'accéléro à 100 Hz et écrase le rate demandé.

**Format de chunk** (append-only, blocs auto-délimités, ~1 % d'overhead → 306 o/s, ≈ 8,8 Mo pour 8 h) :

- Entête 64 o : `"PENDCHNK"`(8) | u16 formatVersion | u16 nominalRateHz | u32 chunkIndex | 16 o sessionUuid | i64 startWallMs | i64 startElapsedRealtimeNs | i64 firstEventTimestampNs | f32 resolution | f32 maxRange | u16 fifoMaxEventCount | u16 modeFlags | réservé.
- Bloc (N ≤ 512) : `"BLK!"`(4) | u16 N | i64 tFirstNs | i64 tLastNs | u16 flags (bit0 frontière de flush FIFO, bit1 trou suspecté, bit2 off-body) | u16 crc16 | 6 o réservés | N × {i16 x, i16 y, i16 z} little-endian, unité 1/2048 g (±16 g exact).
- Timestamps par échantillon = interpolation linéaire tFirst→tLast (valide : le FIFO échantillonne uniformément).

Rotation toutes les 30 min ou 8 Mo. `flush()` par bloc, `fd.sync()` toutes les 10 s. Un bloc au CRC invalide est sauté à la lecture — jamais la nuit entière. Un fichier unique de 8 h serait un point de défaillance unique inacceptable.

**Reprise après crash/reboot.** `active_session.json` (uuid, startWallMs, dernier chunkIndex, mode) ; `BootReceiver` + `ACTION_MY_PACKAGE_REPLACED` relancent le service si le marqueur existe et que `now < startWallMs + 14 h`, avec `bit1 gap` sur le premier bloc. Un `sidecar.json` par session accumule les métriques (trous, batterie, off-body, fs mesuré).

**UI** : une seule `RecordScreen` statique — START/STOP plein écran, durée, nb d'échantillons, Mo écrits, batterie %, compteur de trous, mode FIFO actif. Poll 30 s, aucune animation (les recompositions réveillent le SoC).

---

## Transfert montre → téléphone

`ChannelClient` du Wearable Data Layer (`MessageClient` plafonne à 100 Ko). Bluetooth uniquement — aucune API publique pour forcer le WiFi. Déclenchement : `TransferWorker` sous contraintes `requiresCharging` + node joignable (`CapabilityClient`), plus un bouton « Sync now ».

Protocole en 4 temps, idempotent : (1) wear envoie `/pendulum/manifest` {sessionId, chunks:[{idx,size,crc32}], sidecar} → (2) phone répond `/pendulum/need` avec les index manquants ou corrompus → (3) un canal par chunk, `openChannel` + `sendFile` → (4) phone vérifie taille + CRC32, insère la ligne `chunk`, répond `/pendulum/ack` ; **le fichier n'est supprimé sur la montre qu'après ack**. Quota disque montre 200 Mo, purge FIFO des sessions ackées.

---

## Module `phone` — ingestion, Health Connect, analyse

**Health Connect.** Permission `android.permission.health.READ_SLEEP` + activity de rationale obligatoire (intent-filter `ACTION_SHOW_PERMISSIONS_RATIONALE` + `<meta-data health-permissions>`), sinon Health Connect refuse d'afficher l'app. `SleepReader.read(startMs-2h, endMs+2h)` → `SleepSessionRecord` + stages (`STAGE_TYPE_` AWAKE, AWAKE_IN_BED, SLEEPING, OUT_OF_BED, LIGHT, DEEP, REM), dédupliqués par `dataOrigin` avec source préférée en réglage.

**Le piège de la synchro tardive.** La session n'arrive dans Health Connect qu'**après** la synchro Bluetooth de la montre vers Samsung Health ou Sleep as Android — donc pas au réveil. `SleepFetchWorker` planifié à T+30 min puis backoff 1 h/2 h/4 h/8 h, abandon à T+36 h ; succès = session couvrant ≥ 50 % de la fenêtre. **La nuit reste analysable immédiatement** grâce au masque accéléro ; `RescoreWorker` ajoute le second PLMI quand HC répond.

Chaîne WorkManager : `IngestWorker` (assemblage + continuité) → `AnalyzeWorker` → `SleepFetchWorker` → `RescoreWorker`.

**Room, 7 tables** : `night_session`(startWallMs, endWallMs, fsMeasured, sampleCount, gapCount, gapTotalMs, batteryStart/End, offBodyOnPct, mode, transferState, algoVersion) · `chunk`(sessionId, idx, path, size, crc32, tFirstNs, tLastNs, valid) · `sleep_window`(sessionId, source `ACCEL_MACRO|HEALTH_CONNECT`, stage, startMs, endMs) · `clm_event`(onsetMs, durationMs, peakAmp, noiseFloor, threshold, postural, inSeriesAasm, inSeriesWasm, stageAtOnset, duringWake) · `plm_result`(ruleSet `AASM_V3|WASM_2016`, maskSource, tstMin, plmsCount, plmi, plmw, periodicityIndexFerri, plmiFirstHalf, plmiSecondHalf, paramsJson) — **4 lignes par nuit** (2 règles × 2 masques) · `questionnaire_response` · `param_profile`.

**Écrans** : liste des nuits (badges PLMI-A/PLMI-B, TST, drapeaux qualité) · détail (enveloppe RMS + seuil adaptatif tracés, marqueurs CLM, bandes de séries, hypnogramme, PI de Ferri, sliders de paramètres + recalcul) · tendance multi-nuits (lignes 5/10/15, message bloquant sous 3 nuits) · questionnaire · réglages + export CSV · **avertissement obligatoire au premier lancement**.

---

## Module `algo` — fonctions pures

```kotlin
class Biquad(coeffs)                                          // stateful, streaming
fun highpassButter2(fs: Double, fc: Double = 0.3): Biquad     // sur X, Y, Z SÉPARÉMENT
fun magnitudeL2(x, y, z): FloatArray                          // invariant à la rotation du bracelet
fun rmsEnvelope(sig, fs, winSec = 0.15): FloatArray
fun noiseFloor(env, fs, winSec = 25.0): FloatArray            // médiane glissante, exclusion itérative
fun detectClm(env, floor, fs, cfg): List<Clm>                 // onset 8×, offset 2×, durée 0.5..10 s, fusion < 0.5 s
fun tagPostural(clms, gravityLowpass): List<Clm>
fun buildSeries(clms, rule: SeriesRule): List<PlmSeries>      // AASM_V3(IMI 5..90s) | WASM_2016(10..90s), ≥4 CLM
fun periodicityIndexFerri(clms): Double
fun deriveImmobilityMask(env, fs, cfg): List<SleepWindow>     // époques 30 s, règle type Cole-Kripke
fun mergeMasks(accel, hc: List<SleepWindow>?): SleepMask
fun computePlmi(series, mask): PlmiResult                     // PLMS / heures de SOMMEIL
```

Aucune horloge murale, aucun I/O, `fs` toujours explicite.

**Points cliniques à respecter littéralement.** Durée d'un CLM **0,5–10 s** (la borne 0,5–5 s est l'AASM v1 de 2007, obsolète depuis v2.0 en 2012 — beaucoup de revues la citent encore à tort). Intervalle onset-à-onset **[5 s, 90 s]** en AASM v3, **[10 s, 90 s]** en WASM 2016 : implémenter les deux et rapporter laquelle est active, l'écart de résultat est majeur. Séries de **≥ 4** CLM. Seuil **adaptatif** calé sur le plancher de bruit, jamais fixe en g : c'est la leçon de Yang et al. 2013 (les paramètres par défaut du PAM-RL sous-détectent gravement). Seuil d'interprétation PLMI > 15/h chez l'adulte (ICSD-3), mais le PLMI seul ne suffit jamais au diagnostic.

**Questionnaires.** « Single Question for Rapid Screening of RLS » (100 % sens / 96,8 % spéc) en filtre d'entrée, puis Cambridge-Hopkins CH-RLSq (87,2 % / 94,4 %, PDF libre au dépôt Cambridge). **Exclure l'échelle de sévérité IRLS** : sous copyright IRLSSG.

**Limites à afficher dans l'app.** Sans canal respiratoire, impossible d'exclure les *respiratory-related leg movements* → PLMI structurellement **surestimé** en cas d'apnée du sommeil (jusqu'à 42/h de RRLM selon la règle). L'AASM a une recommandation **forte** contre l'actigraphie en remplacement de l'EMG pour diagnostiquer un PLMD (Smith et al., *JCSM* 2018). Un capteur de cheville ne peut pas diagnostiquer un SJSR — le diagnostic est purement clinique (5 critères IRLSSG, symptômes à l'éveil) et les PLMS n'en sont qu'un critère de **support**.

---

## Vérification — validation sans polysomnographie

C'est le cœur du problème : il n'y a pas de vérité terrain. Six niveaux, du plus contrôlé au plus écologique.

1. **Signal synthétique à vérité terrain injectée** (`algo/src/test`) — bruit de fond extrait d'une vraie nuit calme + CLM synthétiques (paquets 10-15 Hz fenêtrés Gauss, durées 0,5-10 s, amplitudes 1,5× à 30× le plancher), séries d'intervalles connus, plus des distracteurs : mouvements isolés, clusters non périodiques, échelons de gravité, trous FIFO, clipping. Métriques : sens/spéc/F1 événementiel, erreur absolue de PLMI, erreur de PI. Seuils en assertions de non-régression.
2. **Protocole de mouvements volontaires chronométrés** — écran « tap-logger » sur le téléphone ; 5 séries de 4 dorsiflexions de 1 s espacées de 20 s, puis 10 min d'immobilité totale. Vérité terrain réelle **avec la vraie mécanique bracelet-cheville**. Exige 0 faux positif sur les 10 min calmes.
3. **Contrôles négatifs** — montre posée sur la table de nuit (PLMI ≈ 0 attendu), montre au poignet (signature attendue différente).
4. **Robustesse paramétrique** — rejouer une nuit à ±20 % sur chaque paramètre. **Si la décision de dépistage bascule à ±10 %, le chiffre n'est pas exploitable.**
5. **Cohérence** — split-half (1re vs 2e moitié de nuit), ICC inter-nuits sur 5-7 nuits, kappa masque accéléro vs hypnogramme HC, distribution des CLM par stade (plausibilité biologique : concentration attendue en N1/N2).
6. **Golden files** — 5 min de signal réel en resources + JSON de sortie attendu ; test de décimation 50 → 25 Hz (le PLMI doit rester stable).

---

## Phases et critères objectifs

- **P0** (0,5 j) — squelette 4 modules, sideload wear + phone. *Critère : les 2 APK s'installent et affichent leur écran.*
- **P1 — BLOQUANTE** (3-5 nuits) — spike capteur seul : accéléro 50 Hz + chunks + log off-body + batterie/60 s + détecteur de trous. **Aucune ligne d'algorithme avant validation.** *Critères : (a) ≥ 97 % des 8 h × 50 Hz reçus, plus grand trou < 5 s, cumul < 2 min ; (b) batterie > 20 % après 8 h ; (c) off-body « porté » > 95 % du temps à la cheville ; (d) reproductible 3 nuits.* Escalade en cas d'échec : batched → wake lock → 25 Hz (Nyquist reste largement suffisant pour des événements de 0,5-10 s).
- **P2** — format + robustesse. *Critère : `kill` du process en pleine nuit → session reprise, tous les chunks CRC-valides, relecture JVM avec ≤ 1 chunk perdu.*
- **P3** — transfert. *Critère : 8,8 Mo vérifiés CRC en < 5 min avec coupure Bluetooth au milieu et reprise ; suppression uniquement après ack.*
- **P4** — algorithme en TDD sur synthétique. *Critère : F1 ≥ 0,90 à SNR ≥ 4, 0 faux positif sur 10 min de bruit, erreur PLMI < 10 %.*
- **P5** — masque hybride + Health Connect + Room + WorkManager. *Critère : une nuit réelle produit les 4 `plm_result`, kappa rapporté, retry HC prouvé (montre en mode avion puis resynchro).*
- **P6** — UI phone + questionnaires + disclaimers + export CSV. *Critère : parcours à froid nuit → chargeur → résultat affiché, sans intervention manuelle.*
- **P7** — campagne 7 nuits + protocole volontaire. *Critère : rapport de tendance, ICC, courbe de sensibilité paramétrique.*

---

## Pièges anticipés

1. **`SensorEvent.timestamp` n'est pas garanti égal à `elapsedRealtimeNanos`** — certains OEM excluent le temps de suspend. Ancrer un triplet (event.timestamp, elapsedRealtimeNanos, currentTimeMillis) à chaque chunk pour détecter la dérive, sinon la fusion avec l'hypnogramme est décalée de plusieurs minutes.
2. **fs réel ≠ fs demandé** (50 → 50,3 ou 52,6 Hz) — le calculer depuis les timestamps ; un fs faux décale filtres et durées de CLM.
3. **Transitoire du passe-haut** — filtrer par blocs sans conserver l'état du biquad crée un artefact périodique toutes les N valeurs. Implémentation streaming stateful obligatoire.
4. **Changement de posture = échelon de gravité** → burst résiduel de 1-2 s indiscernable d'un CLM. Tagger `postural` via un saut durable du vecteur gravité passe-bas dans ±2 s, exclu par défaut.
5. **Plancher de bruit auto-contaminé** — si la fenêtre glissante contient déjà la série, le seuil monte et coupe au 3ᵉ CLM → série < 4 → PLMI effondré. MAD avec exclusion itérative ou fenêtre causale décalée.
6. **Règle de fusion des CLM proches** (< 0,5 s = un seul mouvement) — l'oublier double le compte.
7. **PLMW vs PLMS** — ne pas compter les mouvements en éveil dans le PLMI ; les rapporter séparément (un PLMW élevé est en soi un indice de SJSR).
8. **Mesure unilatérale** — biais à la baisse (jambe opposée manquée) qui s'oppose au biais RRLM à la hausse. Ne pas prétendre qu'ils se compensent.
9. **Bracelet 45 mm sur une cheville** — jeu mécanique, amplitudes ×2-3, résonances. Garder le même bracelet et le même serrage entre nuits, sinon le seuil adaptatif change de sens. La literie amplifie aussi le signal (×2-2,3 mesuré sur d'autres technos).
10. **Heure d'été / fuseau** — stocker epoch UTC + offset ; la nuit du changement d'heure duplique ou supprime une heure.
11. **Auto-stop** — montre reposée sur le chargeur au réveil sans stop → l'enregistrement pollue la nuit. Couper sur détection de charge ou heure limite.
12. **Doze côté téléphone** — les Workers `requiresCharging` peuvent ne jamais tourner si le téléphone n'est pas chargé. Prévoir `setExpedited` + déclenchement manuel garanti.
13. **Variabilité inter-nuits** — chez des patients SJSR confirmés, le seuil de 15/h n'est dépassé que sur ~34 % des nuits individuelles (52 % pour 10/h, 70 % pour 5/h). Sur 5 nuits, la probabilité de le dépasser au moins une fois monte à 63 %. Une nuit unique ne veut **rien** dire — l'UI doit le refuser, pas seulement l'avertir.

---

## Fichiers critiques

- `algo/src/main/kotlin/com/pendulum/algo/detect/PlmDetector.kt` — machine d'états AASM/WASM
- `algo/src/main/kotlin/com/pendulum/algo/dsp/Envelope.kt` — passe-haut, magnitude, RMS, plancher adaptatif
- `algo/src/test/kotlin/com/pendulum/algo/synth/SyntheticNight.kt` — générateur de vérité terrain
- `format/src/main/kotlin/com/pendulum/format/ChunkCodec.kt` — codec binaire partagé
- `wear/src/main/kotlin/com/pendulum/wear/record/RecordingService.kt` — foreground service + FIFO
- `phone/src/main/kotlin/com/pendulum/phone/health/SleepReader.kt` — Health Connect + retry
- `phone/src/main/kotlin/com/pendulum/phone/work/AnalyzeWorker.kt` — orchestration
- `gradle/libs.versions.toml`

## Contraintes de build (règles globales)

Builds délégués via `delegate-android-build` / `run-remote` — hôte Lenovo d'abord, Mac en repli, local seulement si les deux sont injoignables. Préfixer les builds lourds de `flock /tmp/build.lock`. Sorties Gradle sur le disque D. Poser `note add "WIP: Pendulum <phase>"` à la racine au démarrage, `note rm` en fin de phase.

---

## Alternative crédible écartée

**Un capteur dédié plutôt que la montre** — Axivity AX3 ou GENEActiv (~250-400 €, 100 Hz, autonomie de semaines, validés en recherche pour l'actigraphie de cheville). Avantages : aucun problème d'autonomie, aucun problème off-body, aucun jeu de bracelet, données brutes garanties sans trou. Inconvénients : achat matériel, pas de temps réel, pas de croisement automatique avec Health Connect, et surtout aucune app à écrire — donc pas le projet demandé. **À reconsidérer sérieusement si la phase 1 échoue** sur l'autonomie ou l'off-body, plutôt que de s'acharner sur la montre.

## À vérifier toi-même

- **La phase 1 est un vrai go/no-go, pas une formalité** : si la Pixel Watch 3 tombe à 5 % après 6 h ou passe en veille profonde à la cheville, tout le reste du plan est mort. Ne code pas l'algorithme avant.
- **Le brevet US 10 335 085** « Device and method for detection of periodic leg movements » mentionne explicitement l'accéléromètre d'un appareil maintenu sur la jambe par un bracelet. Justia m'a renvoyé un 403 : titulaire, revendications et statut de maintien **non vérifiés**. Sans conséquence en usage strictement personnel, mais à lire avant toute diffusion.
- **Le comportement du FIFO en suspend sur ta montre précise** — lis `fifoMaxEventCount` et `isWakeUpSensor` à l'exécution avant de choisir la stratégie ; mes seuils (500 events) sont un choix d'ingénierie, pas une valeur sourcée.
- **La licence du Cambridge-Hopkins CH-RLSq** avant intégration, même en usage perso — et confirme que l'IRLS reste exclu.
- **Que la Galaxy Watch 5 écrit bien les *stades* dans Health Connect**, pas seulement la durée totale : c'est documenté pour Samsung Health, mais vérifie-le sur ton appareil avant de bâtir le croisement dessus.
