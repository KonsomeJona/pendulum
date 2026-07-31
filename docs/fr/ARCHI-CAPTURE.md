# Pendulum — Architecture de capture et de transfert (montre → téléphone)

Document de conception, complète et remplace les sections « Module `wear` » et « Transfert montre → téléphone » de `SPEC-v1.md`. Cible : Pixel Watch 3 (41 mm, ~306 mAh) portée **à la cheville**, accéléromètre 50 Hz pendant ~8 h, téléphone Android au chevet. Wear OS 5 / Android 14-15, `compileSdk 35`, sideload personnel.

---

## 0. Ce qui a été vérifié aujourd'hui, et ce qui ne l'a pas été

La consigne demandait une vérification web. Le budget `WebSearch` de la session était épuisé ; j'ai donc **récupéré directement les pages officielles** (ce qui vaut mieux qu'une recherche). Distinction stricte ci-dessous entre le documenté et le folklore.

### Vérifié aujourd'hui sur source primaire

| Fait | Source |
|---|---|
| Le timeout Android 15 (6 h par 24 h) ne concerne que **`dataSync` et `mediaProcessing`**. Passé le délai : `Service.onTimeout(int,int)`, quelques secondes pour `stopSelf()`, sinon `RemoteServiceException: "A foreground service of type dataSync did not stop within its timeout"`. Le timer se réarme si l'app repasse au premier plan. | [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15) |
| Les apps ciblant Android 15+ **ne peuvent pas démarrer depuis `BOOT_COMPLETED`** un FGS de type `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`. **`health` n'est pas dans la liste.** | [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15) |
| FGS `health` = `FOREGROUND_SERVICE_HEALTH` + **au moins une** de `BODY_SENSORS` / `READ_HEART_RATE` / `READ_SKIN_TEMPERATURE` / `READ_OXYGEN_SATURATION` / `ACTIVITY_RECOGNITION`, **ou bien** déclarer `HIGH_SAMPLING_RATE_SENSORS` au manifeste. | [fg-service-types](https://developer.android.com/develop/background-work/services/fg-service-types) |
| `BODY_SENSORS` et les `READ_*` capteurs sont soumis au **while-in-use** : impossible de créer un FGS `health` adossé à ces permissions **depuis l'arrière-plan** sans `BODY_SENSORS_BACKGROUND` (API 33-35) / `READ_HEALTH_DATA_IN_BACKGROUND` (API 36+). | [fg-service-types](https://developer.android.com/develop/background-work/services/fg-service-types) |
| **La charge utile d'un `DataItem` est limitée à 100 Ko.** | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| « If the handset and wearable devices are disconnected, **the data is buffered and synced when the connection is re-established**. » | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| Sans `setUrgent()`, le système **peut retarder la synchro jusqu'à 30 min** (en pratique quelques minutes). | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| « It's possible to set data items and assets while not connected to any devices. They're synchronized when the devices establish a network connection. » Le Data Layer **ne fonctionne pas** avec une montre appairée à iOS. | [data-layer](https://developer.android.com/training/wearables/data-layer) |
| **Capteur non-wake-up en suspend** : « the sensors must continue to function and generate events, which are put in a hardware FIFO » mais « **if the FIFO is too small to store all events, the older events are lost; the oldest data is dropped to accommodate the latest data** ». Sans FIFO, tout est perdu. | [sensors/suspend-mode](https://source.android.com/docs/core/interaction/sensors/suspend-mode) |
| **Capteur wake-up en suspend** : « When the SoC is asleep, wake-up sensors **must wake up the SoC** to deliver events » — avant de dépasser la latence de report maximale **ou de remplir le FIFO**. | [sensors/suspend-mode](https://source.android.com/docs/core/interaction/sensors/suspend-mode) |

Ce dernier point est la pièce maîtresse de toute la stratégie de capture, et il est **normatif au niveau du HAL** : avec un capteur wake-up, la perte par débordement de FIFO est interdite par le contrat ; avec un capteur non-wake-up, elle est explicitement autorisée. Toute la section 3 en découle.

### Non vérifiable aujourd'hui (pages de référence non rendues) mais documenté ailleurs — à re-confirmer

- `Sensor.getFifoReservedEventCount()` (garanti à cette app) vs `getFifoMaxEventCount()` (capacité totale, **partagée** entre clients). `SPEC-v1.md` utilise `max(reserved, max)` : **c'est optimiste et il faut le corriger** (§3.2).
- `SensorManager.registerListener(l, s, samplingPeriodUs, maxReportLatencyUs)` et le fait que `maxReportLatencyUs` est un plafond, pas une garantie.
- Ambient mode : une seule mise à jour par minute pour une activité always-on.

### Folklore assumé comme tel (aucune source primaire trouvée cette session)

- « `ExerciseClient` force l'accéléromètre à 100 Hz et écrase le taux demandé » (rapporté côté Samsung Health Services). **Non vérifié.** Le coût de la prudence est nul : on ne démarre jamais d'`ExerciseClient`. On garde la règle, on retire l'affirmation factuelle.
- Débit utile du Data Layer sur Bluetooth : **20 à 100 Ko/s** selon les retours terrain. Aucune valeur documentée. À mesurer en P3.
- Quota du magasin de `DataItem` sur la montre : **non documenté**. D'où le plafond d'items en vol de §2.6.
- Comportement off-body à la cheville : non documenté (`SPEC-v1.md` le classe déjà en confiance basse).
- Effet du mode Coucher / Économiseur de batterie de Wear OS sur un FGS `health` : non documenté. **Test P1 obligatoire, mode Coucher ACTIVÉ**, puisque c'est la configuration réelle d'usage.

---

## 1. Décision : streaming, batch ou hybride — chiffrée

### 1.1 Réponse courte

**Hybride, et la relecture « streaming = suicide énergétique, reste en batch » est fausse dans les proportions.** Le coût radio d'une synchro incrémentale toutes les 15 min est de l'ordre de **3 à 11 mAh sur la nuit, soit 1 à 3,6 % de la batterie** — c'est-à-dire **entre 20 et 60 fois moins** que l'incertitude sur la stratégie d'acquisition capteur (un wake lock partiel tenu 8 h coûte ~200 mAh, 65 %). Refuser 3 % de batterie pour accepter de perdre potentiellement une nuit entière est un mauvais échange, et surtout c'est **optimiser le mauvais poste**.

Ce qui est réellement suicidaire, c'est le **streaming à haute cadence** (maintenir le lien BT en mode actif ou envoyer un message par seconde) : 52 à 100 % de la batterie, et en prime ça ne transporte pas le signal brut, donc ça ne résout rien.

La proposition initiale — *pousser chaque chunk fermé dès qu'il est complet* — est **valide et retenue**, avec deux corrections :

1. **Le chunk descend de 30 min à 5 min** (`SPEC-v1.md` prévoyait 30 min / 8 Mo). À 5 min un chunk pèse ~91 Ko, ce qui le fait **tenir dans la charge utile de 100 Ko d'un `DataItem`** — donc pas d'`Asset`, pas de `Channel`, pas de handshake dans le chemin nominal. La granularité de perte tombe de 30 min à 5 min *sur le disque* et à ~15 min *côté téléphone*.
2. **Pas de « quand le node est joignable ».** C'est exactement ce qu'il ne faut pas coder : le `DataClient` **bufferise déjà et synchronise à la reconnexion** (documenté). Tester la joignabilité avant d'écrire, c'est réintroduire à la main une logique que la couche fournit, et se tromper au moment précis où elle compte (téléphone en doze mais lien vivant). On écrit, point.

### 1.2 Hypothèses de calcul (et pourquoi elles sont fragiles)

| Paramètre | Valeur retenue | Plage plausible | Confiance |
|---|---|---|---|
| Capacité batterie | 306 mAh @ 3,85 V ≈ 1,18 Wh | — | Haute (spec constructeur) |
| Débit utile Data Layer | 50 Ko/s | 20–100 | **Basse** — folklore |
| Courant incrémental pendant transfert (radio + AP réveillé) | 40 mA | 25–70 | **Basse** |
| Surcoût fixe par salve (sortie de suspend, latence de connexion sniff→actif) | 3 s | 1,5–5 s | **Basse** |
| Lien BT maintenu en mode actif basse latence | +20 mA continu | 10–35 | **Basse** |
| Wake lock partiel (AP éveillé) | +25 mA continu | 15–40 | **Basse** |
| Débit de données brutes | 303 o/s (50 Hz × 6 o + entêtes de bloc) | — | Haute (calcul) |

**Confiance globale sur les valeurs absolues : basse.** Confiance sur **l'ordre de grandeur relatif entre les trois options : moyenne-haute** — parce que les trois partagent les mêmes constantes inconnues, qui se simplifient largement dans les rapports. C'est ce rapport qui porte la décision, pas les milliampères-heures absolus.

### 1.3 Les trois options chiffrées

Nuit de référence : 8 h, 8,73 Mo, 96 chunks de 91 Ko.

**(i) Synchro incrémentale, 32 salves de ~275 Ko (3 chunks) toutes les 15 min**

```
durée/salve  = 275 Ko / 50 Ko/s + 3 s     = 8,5 s
charge/salve = 40 mA × 8,5 s              = 0,094 mAh
total        = 32 × 0,094                 = 3,0 mAh  →  0,98 % de 306 mAh
pire cas (20 Ko/s, 70 mA, 4 s de surcoût) = 11,0 mAh →  3,6 %
```

**(i-bis) Variante paranoïaque : une salve par chunk fermé, 96 salves de 91 Ko toutes les 5 min**

```
durée/salve  = 91/50 + 3                  = 4,8 s
total        = 96 × 40 mA × 4,8 s         = 5,1 mAh  →  1,7 %
pire cas                                  = 18 mAh   →  5,9 %
```

Noter que passer de 15 min à 5 min ne multiplie le coût que par **1,7**, pas par 3 : le surcoût fixe de réveil domine le temps d'émission. Le choix entre les deux **n'est donc pas un arbitrage énergétique** (l'écart, 2 mAh, est inférieur à mes propres barres d'erreur) ; c'est un arbitrage de prudence sur un budget batterie non encore mesuré.

**(ii) Un seul transfert de 8,8 Mo au réveil**

```
durée = 8800 Ko / 50 Ko/s                 = 176 s
coût  = 40 mA × 176 s                     = 1,96 mAh →  0,64 %
pire cas (20 Ko/s, 70 mA)                 = 8,6 mAh  →  2,8 %
```

Et ce transfert a lieu **sur le chargeur** → coût réel sur la nuit ≈ **0**. C'est bien l'option la moins chère. Elle coûte 0 mAh et peut coûter 8 heures de données.

**(iii) Streaming continu d'une enveloppe décimée (1 Hz, 4 o/échantillon = 115 Ko/nuit)**

Le volume est ridicule ; **le coût n'est pas dans les octets, il est dans le taux de service du lien**. Un message par seconde interdit au lien BT de retomber en sniff et à l'AP de suspendre :

```
20 mA × 8 h                               = 160 mAh  →  52 %  (radio seule)
+ AP jamais suspendu                      → jusqu'à 100 %
```

Variante « battement de cœur » à 30 s : 960 réveils × ~2 s × 30 mA = **16 mAh (5,2 %)** — supportable, mais **ça ne transporte toujours pas le brut**, donc ça ne répond pas au besoin de résilience. Coût non nul pour un bénéfice nul : rejeté.

### 1.4 Décision retenue

| | Coût nuit | Perte max si la montre meurt |
|---|---|---|
| Batch au réveil seul (relecteur) | ~0 % | **jusqu'à 8 h** |
| **Hybride retenu (N=3, 15 min)** | **1 – 3,6 %** | **≤ 20 min** |
| Hybride agressif (N=1, 5 min) | 1,7 – 5,9 % | ≤ 10 min |
| Streaming enveloppe 1 Hz | 52 – 100 % | 8 h de brut quand même |

**Retenu : synchro incrémentale par `DataClient`, une salve tous les `PUSH_EVERY_N_CHUNKS = 3` chunks (15 min), constante exposée en réglage développeur pour passer à 1 après la mesure P1.** Plus :

- **Un aperçu d'enveloppe embarqué gratuitement dans la même salve.** Chaque poussée met à jour un `DataItem` unique `/pendulum/live/<session>` contenant, en plus des compteurs, l'enveloppe RMS décimée à 1 Hz des 15 dernières minutes, quantifiée en u8 logarithmique : **900 octets, soit +0,3 % du volume de la salve et zéro réveil radio supplémentaire.** C'est littéralement le « graphe de mouvement en temps réel » demandé, pour un coût nul, parce qu'il voyage dans un réveil radio qu'on paie déjà.
  **Cet aperçu n'est jamais utilisé pour le scoring** — le PLMI est toujours recalculé sur le brut côté téléphone. Affichage et signe de vie uniquement.
- **Un balayage de rattrapage (`ChannelClient`) au réveil / sur chargeur**, pour le reliquat et pour les cas dégradés (téléphone éteint toute la nuit).

Cette combinaison répond à la question telle qu'elle a été posée : *temps réel* pour le graphe (15 min de latence, coût nul), *incrémental* pour le brut (perte bornée à 20 min), *batch* pour le rattrapage (gratuit, sur chargeur).

---

## 2. Protocole de transfert

### 2.1 Choix d'API, et pourquoi

| API | Sémantique | Verdict |
|---|---|---|
| `MessageClient` | Fire-and-forget. Échoue si le node est injoignable, **aucune file d'attente, aucune reprise**. | **Plan de contrôle uniquement** — et même là, on préfère un `DataItem`. Jamais pour de la donnée qu'on ne veut pas perdre. |
| `DataClient` | Magasin **répliqué et persistant**. Charge utile ≤ 100 Ko. **Bufferisé hors connexion, synchronisé à la reconnexion** (documenté). Dédupliqué : re-poser un item identique ne déclenche rien. Livré à un `WearableListenerService` que GMS démarre, même app jamais ouverte. | **Chemin nominal.** C'est la seule API dont la sémantique correspond à « ne rien perdre ». |
| `ChannelClient` | Flux d'octets, faible surcoût, **aucune persistance** : le canal meurt avec la connexion, les deux extrémités doivent être vivantes simultanément. | **Rattrapage volumineux uniquement**, quand on sait les deux côtés en ligne et qu'on veut du débit sans gonfler le magasin de données. |

Le point décisif est celui-ci : **avec `DataClient`, un chunk poussé à 2 h du matin est déjà répliqué ; que la montre meure à 3 h ne le concerne plus.** Avec `ChannelClient`, tout transfert interrompu est à refaire, et si la source a disparu il n'y a plus rien à refaire. `SPEC-v1.md` faisait de `ChannelClient` le chemin nominal en justifiant par « `MessageClient` plafonne à 100 Ko » — le raisonnement saute l'option `DataClient`, qui a le même plafond mais une sémantique de magasin persistant. **C'est cette omission qui rendait le batch au réveil inévitable.** En taillant le chunk sous 100 Ko, `DataClient` devient le transport nominal et le problème disparaît.

### 2.2 Taille de chunk et rotation

```
débit = 50 Hz × 6 o + 32 o d'entête de bloc / 10,24 s = 300 + 3,1 = 303,1 o/s
5 min → 90 930 o + 80 o d'entête de fichier          = 91 010 o
```

Règle de rotation : **fermer le chunk courant dès que `elapsed ≥ 300 s` OU `bytesWritten ≥ 92 160` (90 Kio)**. Le plafond en octets est le garde-fou dur : il tient même si `fs` réel dérive à 52,6 Hz (`SPEC-v1.md` piège n°2) ou si un mode dégradé change la cadence. Marge restante sous les 100 Ko après les ~200 o de `DataMap` : **7,4 %**.

Si un appareil refuse malgré tout la charge utile, l'échappatoire est `Asset.createFromFd()` sur le même `DataItem` — même protocole, un seul point de code à changer. Ne pas l'implémenter par anticipation.

### 2.3 Espace de noms et messages

Tout ce qui suit est un `DataItem` sauf mention contraire. Les structures de fil vivent dans le module `format` (JVM pur, testable des deux côtés).

**`/pendulum/session/<sessionHex>`** — montre → téléphone, `setUrgent()`
```
{ v, sessionHex, startWallMs, tzOffsetMin, nominalRateHz, modeFlags,
  plannedStopWallMs, state: OPEN|CLOSED, endWallMs?, totalChunks?, stopReason? }
```
Posé à l'ouverture (`OPEN`), réécrit à la fermeture propre (`CLOSED` + total). C'est ce qui permet au téléphone de savoir qu'une nuit **existe** avant d'en avoir reçu la fin — condition nécessaire pour détecter une nuit tronquée.

**`/pendulum/chunk/<sessionHex>/<idx:05d>`** — montre → téléphone, **sans** `setUrgent()` sauf le dernier de chaque salve
```
DataMap : { idx, sampleCount, tFirstNs, tLastNs, crc32, size, flagsOr }
payload : les octets exacts du fichier de chunk
```
`setUrgent()` sur le **dernier item de la salve seulement**. Hypothèse (confiance moyenne, non documentée) : le flush provoqué emporte aussi les items non urgents en attente. **Si P3 démontre le contraire, mettre `setUrgent()` sur tous** — l'impact énergétique est nul, ils partent dans le même réveil.

**`/pendulum/live/<sessionHex>`** — montre → téléphone, `setUrgent()`, **remplacé** à chaque salve, jamais accumulé
```
{ lastUpdateMs, elapsedMs, samplesWritten, bytesWritten, batteryPct,
  gapCount, gapTotalMs, mode, lastClosedChunkIdx, envU8: ByteArray(900) }
```

**`/pendulum/ack/<sessionHex>`** — téléphone → montre, `setUrgent()`, réécrit à chaque ingestion
```
{ ackedUpTo, bitmapBase, ackedBitmap: ByteArray, needResend: IntArray, phoneMs }
```
`ackedBitmap` couvre `[bitmapBase, bitmapBase + 8×len)`. `needResend` = index reçus mais **CRC32 invalide**.

**Choix structurant : l'accusé de réception est un `DataItem`, pas un message.** Un ack par `MessageClient` envoyé pendant que la montre est hors de portée est perdu, et la montre garderait ses fichiers pour toujours. Un ack en `DataItem` est un **état convergent** : la montre le lit quand elle peut, et l'état est le même quel que soit le nombre de fois qu'elle le lit. Idempotence gratuite.

**`/pendulum/sweep-request`** (Message, téléphone → montre) et **`/pendulum/sweep/<sessionHex>`** (Channel) — voir §2.5.

### 2.4 Boucle nominale

1. **Montre.** `ChunkStore` ferme le chunk *k*, `fsync`, calcule le CRC32 du fichier complet.
2. `SyncCoordinator` incrémente son compteur ; à *k* ≡ 0 mod 3, il publie les chunks fermés non acquittés via `ChunkPublisher` (`putDataItem`), le dernier avec `setUrgent()`, et met à jour `/pendulum/live`.
3. **Téléphone.** `PendulumListenerService.onDataChanged` reçoit `/pendulum/chunk/...`. `ChunkIngestor` : vérifie `size` puis **CRC32 recalculé sur les octets reçus**, écrit le fichier dans le stockage de l'app, `INSERT OR IGNORE` dans `chunk` avec `UNIQUE(sessionId, idx)`. **Idempotent par construction** : recevoir deux fois le même chunk est un no-op silencieux.
4. `AckPublisher` recalcule le bitmap depuis Room (**source de vérité = la base, pas un compteur en mémoire**) et réécrit `/pendulum/ack/<session>`.
5. **Montre.** `AckObserver` reçoit l'ack. Pour chaque bit posé : supprime le fichier de chunk **puis** le `DataItem` correspondant. Pour chaque index dans `needResend` : supprime puis re-pose le `DataItem` (la suppression est nécessaire, un re-`put` identique serait dédupliqué et ne déclencherait rien).

**Le fichier n'est jamais supprimé avant le bit d'ack. La suppression du `DataItem` non plus.** L'invariant tient tout seul : le disque de la montre est la source de vérité tant que la base du téléphone ne l'est pas devenue.

### 2.5 Rattrapage : `ChannelClient`

Déclenché quand `unackedCount > SWEEP_THRESHOLD (24)`, ou à la fermeture de session, ou par le bouton « Sync now », ou par `TransferWorker` (contraintes : chargeur **ou** batterie > 30 %, pas de contrainte réseau).

La montre ouvre `/pendulum/sweep/<sessionHex>` et écrit un flux encadré :
```
répété : [u32 idx][u32 len][len octets][u32 crc32]
fin     : [u32 0xFFFFFFFF]
```
Le téléphone lit, vérifie, persiste chunk par chunk, et met à jour l'ack **en fin de flux ou toutes les 16 trames**. Si le canal meurt au milieu : les trames complètes et vérifiées sont conservées, la trame partielle est jetée, et la reprise est triviale — la montre relit l'ack et ne renvoie que ce qui manque. **Il n'y a pas de reprise à l'octet près, et il n'en faut pas** : l'unité de reprise est le chunk de 91 Ko, dont le renvoi coûte 2 s.

### 2.6 Quotas, saturation, téléphone éteint

**Plafond d'items en vol : `MAX_INFLIGHT_ITEMS = 24`** (2 h de nuit, ~2,2 Mo dans le magasin de données). Le quota du magasin `DataItem` n'étant pas documenté, on refuse de le découvrir par un plantage à 4 h du matin. Au-delà de 24 non acquittés :

- la montre **arrête de publier** de nouveaux `DataItem`,
- elle **continue d'enregistrer sur disque, sans aucune dégradation** (le disque est la source de vérité),
- elle lève `syncBacklogged` dans `/pendulum/live` et dans l'UI,
- au premier ack reçu ou au prochain `TransferWorker`, elle bascule en **mode balayage** (`ChannelClient`, pas de plafond).

**Téléphone éteint toute la nuit** : les items s'accumulent jusqu'à 24, la montre passe en backlog, la nuit est intégralement enregistrée sur disque. Au rallumage, GMS reconnecte, les 24 items se synchronisent, l'ack arrive, `TransferWorker` démarre un balayage qui vide les 72 restants en ~3 min. **Aucune perte.** Le seul coût est que la garantie « perte bornée à 20 min » retombe à « perte bornée à ce que le disque contient », ce qui est exactement le comportement de l'option batch — donc jamais pire.

**Quota disque de la montre** : `CHUNK_DIR_CAP = 200 Mo` (~22 nuits).
- Purge à l'ouverture de session : supprimer les sessions **entièrement acquittées**, plus ancienne d'abord, tant que l'occupation > 150 Mo.
- Si après purge l'occupation > 190 Mo (donc du non-acquitté ancien qui traîne) : **bloquer le démarrage** dans le préflight avec un message explicite. Refuser de démarrer une nuit vaut mieux que l'écraser en silence.
- **Pendant l'enregistrement**, si l'espace libre passe sous 50 Mo : **arrêt propre** avec `stopReason = DISK_FULL`. Une nuit courte et intègre vaut mieux qu'une nuit longue et tronquée au milieu d'un bloc.

### 2.7 Le cas « la montre meurt à 3 h »

**Ce qui reste, par cause :**

| Cause | Sur le disque montre | Sur le téléphone |
|---|---|---|
| Batterie vide (détectée, ≤ 5 %) | tout, chunk courant fermé proprement | **tout** (arrêt propre → salve finale urgente) |
| Kill système / OOM | tout sauf le dernier bloc partiel | tout sauf ≤ 3 chunks (≤ 20 min) |
| Reboot | tout sauf le dernier bloc partiel | idem |
| Coupure d'alimentation brutale | tout sauf le dernier bloc en cache | idem |

Le format append-only à blocs CRC de `ChunkCodec.kt` fait déjà le travail : `ChunkReader` marque `truncatedTail` et rend tous les blocs valides. **Un kill brutal coûte au maximum un bloc de 512 échantillons, soit 10,2 s.**

**Comment la nuit est marquée.** `/pendulum/session/<uuid>` est resté `OPEN`. Côté téléphone, `SessionWatchdogWorker` (périodique 30 min tant qu'une session est `OPEN`) applique :

- `now − lastChunkArrivalMs > 45 min` → `state = STALE` (la montre ne parle plus, elle reviendra peut-être).
- `now > startWallMs + 14 h` **ou** heure locale > 12:00 → `state = TRUNCATED`, `endWallMs = tFirstNs du dernier chunk reçu`, et on **lance l'analyse sur ce qu'on a**.
- Si des chunks arrivent après coup (montre rechargée, rebootée) : réconciliation, `RescoreWorker` recalcule. La chaîne est idempotente, l'analyse d'une nuit tronquée n'est jamais un état final irréversible.

**Cause probable.** Le `sidecar.json` et `/pendulum/live` portent `batteryPct` par minute. Si le dernier relevé ≤ 8 % → « batterie ». Si un `BootReceiver` publie plus tard un marqueur de redémarrage → « redémarrage ». Sinon → « interruption inconnue ». Ne jamais inventer : afficher « inconnue » est une information, deviner est un bug.

**Ce que voit l'utilisateur** sur la carte de nuit :

```
Nuit du 12 → 13 juillet          ⚠ INTERROMPUE
23:41 → 03:12   ·   3 h 31 enregistrées   ·   38 chunks / 38 reçus
Dernier signal 03:12, batterie 4 % à 03:10  →  cause probable : batterie
PLMI 11,2 /h   ·  fiabilité FAIBLE (moins de 4 h de sommeil analysé)
```

Et le rappel déjà acté dans `SPEC-v1.md` : **une nuit seule ne veut rien dire**, l'UI refuse de conclure sous 3 nuits. Une nuit tronquée n'est donc pas une catastrophe — c'est une nuit qui compte moins.

---

## 3. Stratégie de capture

### 3.1 Foreground service `health` — tranché

**Décision : foreground service, `android:foregroundServiceType="health"`, qualifié par `HIGH_SAMPLING_RATE_SENSORS` déclarée au manifeste.** Aucune activité always-on.

Quatre raisons, dont trois vérifiées aujourd'hui :

1. **`dataSync` est éliminé mécaniquement** : timeout de 6 h par 24 h. Une nuit de 8 h le franchit à 6 h, `onTimeout()` est appelé, et un `stopSelf()` manqué produit un `RemoteServiceException` fatal. `health` n'a **aucun timeout documenté**. (vérifié)
2. **`dataSync` ne peut pas être démarré depuis `BOOT_COMPLETED`** sur une app ciblant Android 15+. `health` n'est pas dans la liste d'interdiction → la reprise après reboot (§3.5) est légale. (vérifié)
3. **Qualification par `HIGH_SAMPLING_RATE_SENSORS`, pas par `BODY_SENSORS`.** La doc autorise explicitement les deux voies, mais `BODY_SENSORS` et les `READ_*` capteurs sont soumis au **while-in-use** : on ne peut pas créer le FGS depuis l'arrière-plan sans `BODY_SENSORS_BACKGROUND`. Cette restriction mordrait précisément sur le chemin de reprise après reboot. `HIGH_SAMPLING_RATE_SENSORS` est une permission normale, sans invite runtime, sans while-in-use. **Corollaire : ne PAS déclarer `BODY_SENSORS`** — la déclarer sans la vouloir, c'est s'exposer à la restriction pour rien. (vérifié)
   *Repli si un appareil rejette quand même le démarrage* : ajouter `ACTIVITY_RECOGNITION` et la demander au runtime. Test go/no-go de P0, une ligne de logcat suffit à trancher.
4. **Une activité always-on est rejetée** : elle laisse l'écran en ambient (1 rafraîchissement/minute au mieux), elle éclaire le poignet — ici la cheville, sous la couette — toute la nuit, elle est tuée par le système à la moindre pression mémoire, et elle coûte l'écran en plus du capteur. Aucun avantage : un FGS `health` est *plus* protégé qu'une activité.

### 3.2 Stratégie FIFO / wake-up / wake lock

Le contrat HAL vérifié aujourd'hui rend la décision presque déterministe :

> non-wake-up + suspend + FIFO trop petit → **« the older events are lost »**
> wake-up + suspend → **le HAL doit réveiller le SoC** avant de dépasser la latence *ou* de remplir le FIFO.

Donc : **s'il existe une variante wake-up de `TYPE_ACCELEROMETER`, elle est le seul choix défendable, et la question du wake lock ne se pose qu'en son absence.**

Correction à `SPEC-v1.md` : celle-ci calcule `fifo = max(fifoReservedEventCount, fifoMaxEventCount)`. `fifoMaxEventCount` est la capacité **totale, partagée** entre tous les clients du capteur (Health Services, Fitbit, le système). Dimensionner dessus, c'est parier que personne d'autre n'écoute — pari perdu sur une Pixel Watch. **Budgéter sur `fifoReservedEventCount`, qui est la seule part garantie.**

```kotlin
val sensor    = getDefaultSensor(TYPE_ACCELEROMETER, /* wakeUpSensor = */ true)
             ?: getDefaultSensor(TYPE_ACCELEROMETER)
val reserved  = sensor.fifoReservedEventCount          // part garantie
val shared    = sensor.fifoMaxEventCount               // information, pas budget

mode = when {
    sensor.isWakeUpSensor && reserved >= 500 ->
        // ~10 s de tampon garanti minimum ; le HAL s'engage à réveiller avant débordement.
        BATCHED_WAKEUP(latencyUs = (0.5 * reserved / 50).s.clamp(10.s, 60.s))

    sensor.isWakeUpSensor ->
        // wake-up mais FIFO maigre : le HAL réveillera très souvent. On garde le batching
        // pour ce qu'il vaut, sans wake lock : le contrat wake-up suffit.
        BATCHED_WAKEUP(latencyUs = 10.s)

    reserved >= 3000 ->
        // non-wake-up mais 60 s de tampon garanti : pari acceptable, à valider en P1.
        BATCHED_WAKELOCK(latencyUs = 20.s) + PARTIAL_WAKE_LOCK

    else ->
        // non-wake-up + FIFO court = perte documentée en suspend. Wake lock obligatoire.
        CONTINUOUS_WAKELOCK(latencyUs = 0) + PARTIAL_WAKE_LOCK
}
```

Le seuil de 500 événements (10 s à 50 Hz) est **un choix d'ingénierie, pas une valeur sourcée** — je le dis parce que `SPEC-v1.md` le disait déjà et que ça reste vrai. La vraie donnée est `reserved` sur *cette* montre, à lire en P1 et à inscrire dans l'entête du chunk (`fifoMaxEventCount`, champ existant — **y écrire `reserved`, pas `max`**, ou ajouter un second champ dans les 12 octets réservés de l'entête de 80 o).

**Le wake lock est un coût de ~200 mAh (65 %) sur 8 h.** C'est le seul poste qui peut faire échouer le projet. C'est aussi celui pour lequel §1 disait qu'il est absurde de se battre sur 3 % de radio.

### 3.3 Détection de trous et auto-dégradation

`GapMonitor` observe deux signaux :
- **intra-lot** : `Δt` entre échantillons consécutifs d'un même `SensorEvent` batché > 3 × la période nominale ;
- **inter-lot** : `elapsedRealtimeNanos` entre deux livraisons > `maxReportLatency + 3 s`, ou déficit du compte d'échantillons sur une fenêtre glissante de 60 s (`reçus < 0,95 × attendus`).

Escalade, **monotone** (jamais de retour arrière dans la même session, sinon oscillation) :

| Palier | Déclencheur | Action | Trace |
|---|---|---|---|
| 1 | 3 trous > 3 s en 10 min | prendre `PARTIAL_WAKE_LOCK` | `MODE_DEGRADED` dans `modeFlags` |
| 2 | 3 trous > 3 s dans les 10 min suivantes | `maxReportLatency = 0` (continu) | nouveau chunk, `modeFlags` mis à jour |
| 3 | idem encore 10 min | ré-enregistrer à **25 Hz** | nouveau chunk, `nominalRateHz = 25` |

Le changement de palier **force une rotation de chunk** : `modeFlags` et `nominalRateHz` sont dans l'entête et ne sont jamais réécrits (format append-only). Chaque bloc suivant un trou porte `FLAG_GAP_BEFORE`.

25 Hz reste largement suffisant : les CLM durent 0,5 à 10 s et l'enveloppe RMS est calculée sur 0,15 s. Nyquist n'est pas le facteur limitant, la résolution temporelle de l'onset l'est, et 40 ms suffisent.

### 3.4 Arrêt automatique

Cinq conditions, la première qui se présente gagne. Chacune écrit `stopReason` dans le sidecar et dans `/pendulum/session`.

| # | Condition | Détail | `stopReason` |
|---|---|---|---|
| 1 | **Charge détectée** | `BatteryManager.isCharging` **soutenu ≥ 60 s** (60 s pour immuniser contre un faux positif de chargeur magnétique) | `CHARGING` |
| 2 | **Batterie ≤ 5 %** | fermeture propre + salve finale `setUrgent()` **avant** que le système ne tue quoi que ce soit | `LOW_BATTERY` |
| 3 | **Durée maximale** | `startWallMs + 10 h` | `MAX_DURATION` |
| 4 | **Heure butoir** | heure locale ≥ `stopAtLocalTime` (défaut **10:00**) | `TIME_LIMIT` |
| 5 | **Réveil détecté** | > 80 % des époques de 30 s au-dessus du seuil de locomotion sur 10 min glissantes | `WAKE_DETECTED` |
| 6 | **Disque** | espace libre < 50 Mo | `DISK_FULL` |

**La condition 2 est la plus rentable du document.** Elle transforme le scénario « la montre meurt à 3 h » en « la montre se ferme proprement à 3 h et envoie tout » — le coût est un `BatteryLogger` de trente lignes.

**Correction explicite à `SPEC-v1.md` : l'off-body ne doit JAMAIS arrêter l'enregistrement.** La détection off-body repose sur le PPG/capacitif au poignet ; à la cheville, son comportement est inconnu et probablement « non porté » en permanence. Un arrêt sur off-body couperait toutes les nuits à la première minute. `TYPE_LOW_LATENCY_OFFBODY_DETECT` est **journalisé** (sidecar + `FLAG_OFF_BODY` sur les blocs) et **jamais actionné**. Le détecteur de « montre retirée » est la condition 5, qui mesure la locomotion — c'est-à-dire l'événement qu'on veut vraiment détecter (« la personne s'est levée »), pas un proxy non validé.

### 3.5 Reprise après reboot / mise à jour

`active_session.json` (écriture atomique : `tmp` + `rename`) contient `{uuid, startWallMs, plannedStopWallMs, lastChunkIndex, modeFlags, nominalRateHz}`.

`BootReceiver` sur `BOOT_COMPLETED` + `ACTION_MY_PACKAGE_REPLACED`. **Reprendre uniquement si** :
```
marqueur présent
&& now < startWallMs + 14 h
&& now < plannedStopWallMs        ← ajouté
&& heure locale < stopAtLocalTime ← ajouté
&& !isCharging                    ← ajouté
```
Sinon : **ne pas reprendre**, mais **finaliser** — passer `/pendulum/session` en `CLOSED` avec `stopReason = CRASH`, et laisser `TransferWorker` pousser le reliquat. `SPEC-v1.md` ne testait que la fenêtre de 14 h, ce qui redémarre un enregistrement à 8 h du matin sur le chargeur après un reboot nocturne, et pollue la nuit exactement comme le piège n°11 qu'elle décrit par ailleurs.

Le premier chunk après reprise porte `FLAG_GAP_BEFORE` sur son premier bloc et un `chunkIndex` qui **continue la numérotation** (lue dans le marqueur), jamais réinitialisée : l'unicité `(sessionId, idx)` côté téléphone en dépend.

**Angle mort à mesurer : le chiffrement à la connexion.** Si la montre a un code de verrouillage, `BOOT_COMPLETED` n'est diffusé qu'après déverrouillage, et le stockage credential-encrypted est inaccessible avant. Une montre qui reboote à 3 h **au poignet** reste verrouillée jusqu'au matin → aucune reprise. Ne pas passer `directBootAware` (ça imposerait de déplacer les chunks en stockage device-encrypted, plus exposé et plus complexe pour un gain incertain). **À mesurer en P2 : combien de temps s'écoule réellement entre un reboot nocturne et la reprise.** Si le verdict est « jamais », la condition 2 (arrêt propre sur batterie faible) devient encore plus critique.

### 3.6 Manifeste `wear`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />

    <!-- Capteur haute cadence : qualifie AUSSI le FGS de type health,
         sans invite runtime et sans restriction while-in-use. -->
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Repli uniquement si le démarrage du FGS health est refusé sur l'appareil.
         <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" /> -->

    <!-- NE PAS déclarer BODY_SENSORS : soumise au while-in-use, elle casserait
         la reprise après reboot sans rien apporter (on ne lit aucun capteur corporel). -->

    <application android:allowBackup="false">

        <service
            android:name=".record.RecordingService"
            android:exported="false"
            android:foregroundServiceType="health"
            android:stopWithTask="false" />

        <service
            android:name=".transfer.AckObserver"
            android:exported="true"
            android:permission="com.google.android.gms.permission.BIND_WEARABLE_LISTENER">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data android:scheme="wear" android:host="*"
                      android:pathPrefix="/pendulum/ack" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*"
                      android:pathPrefix="/pendulum/sweep-request" />
            </intent-filter>
        </service>

        <receiver android:name=".record.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

### 3.7 Cycle de vie complet de `RecordingService`

```
IDLE
 │  ACTION_START (bouton) │ ACTION_RESUME (BootReceiver)
 ▼
PREFLIGHT ─── bloquant ──▶ IDLE + écran d'erreur (§5.3)
 │  ok / avertissements acceptés
 ▼
STARTING
 │  startForeground(id, notif, FOREGROUND_SERVICE_TYPE_HEALTH)   ← AVANT tout le reste
 │  SensorStrategy.decide(sensor) → mode
 │  SessionStore.begin(uuid, …) → active_session.json (fsync)
 │  ChunkPublisher.putSession(state = OPEN, urgent)
 │  registerListener(pipeline, sensor, 20 000 µs, latencyUs)
 │  si mode.needsWakeLock : acquire(PARTIAL_WAKE_LOCK, "pendulum:rec")   ← sans timeout
 ▼
RECORDING ◀────────────────────────────────────────────┐
 │  onSensorChanged  → SensorPipeline → blocs de ≤ 512 → ChunkWriter
 │  tick 10 s        → fd.sync()
 │  rotation 300 s / 92 160 o → ChunkStore.close() + SyncCoordinator.onChunkClosed()
 │  tick 60 s        → BatteryLogger, WakeDetector, quota disque
 │  tick 15 min      → SyncCoordinator.push()  (salve + /pendulum/live)
 │  GapMonitor palier 1/2/3 ──────── auto-dégradation ──┘
 │
 │  stop (utilisateur | §3.4 conditions 1-6 | onTimeout() défensif)
 ▼
FINALIZING
 │  unregisterListener ; dernier bloc écrit ; ChunkStore.close() ; fsync
 │  sidecar.json final ; ChunkPublisher.putSession(CLOSED, totalChunks, stopReason, urgent)
 │  SyncCoordinator.push(force = true)   ← tous les chunks restants, urgents
 │  releaseWakeLock ; SessionStore.clearActive()
 │  WorkManager.enqueueUniqueWork("pendulum-transfer", TransferWorker)
 ▼
stopForeground(STOP_FOREGROUND_REMOVE) ; stopSelf()  →  IDLE
```

Détails non négociables :

- `onStartCommand` retourne **`START_STICKY`** ; `onTaskRemoved` **ne fait rien** (le service survit au balayage de la tâche, d'où `stopWithTask="false"`).
- **`startForeground()` est appelé dans la première instruction utile de `onStartCommand`**, avant toute I/O — cinq secondes de retard et le système lève `ForegroundServiceDidNotStartInTimeException`.
- **`onTimeout(startId, fgsType)` est implémenté** bien que `health` n'y soit pas soumis : si une version future l'y soumettait, le comportement par défaut serait un crash à 6 h de nuit. L'implémentation ferme proprement (chemin `FINALIZING` complet), appelle `stopSelf()`, et programme un `AlarmManager.setExactAndAllowWhileIdle` à +30 s pour relancer une nouvelle session (nouvelle `sessionUuid`, marquée `RESTARTED_AFTER_TIMEOUT`). **Coût si inutile : zéro. Coût si nécessaire et absent : la moitié de chaque nuit.**
- Le wake lock est acquis **sans timeout** — hors Play Store, aucune contrainte, et un timeout qui expire à 4 h du matin est un bug silencieux.
- La notification est de canal `IMPORTANCE_LOW`, sans son, sans vibration, `setOngoing(true)`, `setSilent(true)`. Un `OngoingActivity` (`androidx.wear:wear-ongoing`) est optionnel et cosmétique.

---

## 4. Pièges Wear OS

### 4.1 Documentés (vérifiés aujourd'hui, sources en §0)

1. **Timeout FGS Android 15** — `dataSync`/`mediaProcessing` uniquement, 6 h/24 h. Fatal pour une nuit de 8 h. `health` exempt. Conséquence : le type de service n'est pas un détail de manifeste, c'est le choix qui décide si l'app fonctionne.
2. **`BOOT_COMPLETED` ne peut pas démarrer un FGS `dataSync`** sur Android 15+. `health` le peut.
3. **`BODY_SENSORS` et les `READ_*` sont while-in-use** → un FGS `health` qualifié par elles ne démarre pas depuis l'arrière-plan. D'où la qualification par `HIGH_SAMPLING_RATE_SENSORS`.
4. **`DataItem` ≤ 100 Ko.** Contrainte dimensionnante du chunk de 5 min.
5. **Sans `setUrgent()`, jusqu'à 30 min de retard de synchro.** Une architecture qui suppose une livraison immédiate sans `setUrgent()` a un bug de 30 min.
6. **Le Data Layer bufferise hors connexion et synchronise à la reconnexion.** C'est la garantie sur laquelle repose toute la résilience de §2.
7. **FIFO en suspend** : non-wake-up → « the older events are lost » quand le FIFO déborde ; wake-up → le HAL **doit** réveiller le SoC avant débordement. `SPEC-v1.md` classait ce point en confiance « basse » ; il est en réalité **normatif au niveau du HAL**, ce qui remonte la confiance sur *le contrat*. Ce qui reste inconnu, c'est la **valeur** de `fifoReservedEventCount` et le respect effectif du contrat par l'OEM.
8. **Data Layer inopérant si la montre est appairée à un iPhone.** Sans objet ici, mais à ne jamais oublier.

### 4.2 Documenté ailleurs, non re-vérifié cette session

9. **Ambient mode** : une activité always-on est limitée à ~1 rafraîchissement par minute et l'écran reste allumé en basse luminosité. Sans objet ici puisqu'on ne fait pas d'always-on — et c'est une des raisons de ne pas en faire.
10. **Doze / App Standby côté téléphone** : `SPEC-v1.md` note déjà que les Workers `requiresCharging` peuvent ne jamais tourner. Ici, `TransferWorker` **n'exige pas la charge** (chargeur **ou** batterie > 30 %), et l'ingestion côté téléphone n'a pas besoin d'un Worker : c'est GMS qui démarre `WearableListenerService` à la livraison. **Confiance moyenne** sur le fait que cette livraison perce le doze du téléphone. Filet de sécurité : les `DataItem` non traités **persistent** dans le magasin, et sont retraités au prochain démarrage du listener. Le pire cas est un retard, jamais une perte.
11. **`fifoReservedEventCount` vs `fifoMaxEventCount`** : partagé vs garanti (§3.2).

### 4.3 Folklore — à traiter comme des règles de prudence, pas comme des faits

12. **`ExerciseClient` écraserait le taux d'échantillonnage à 100 Hz.** Rapporté, **non vérifié**. La règle « ne jamais démarrer d'`ExerciseClient`, ne jamais dépendre de Health Services pour l'accéléromètre » est conservée parce qu'elle ne coûte rien (Health Services n'expose de toute façon aucun accéléromètre brut). **Corollaire à tester en P1 : et si c'est une *autre* app — Fitbit, un suivi d'entraînement — qui démarre un `ExerciseClient` pendant la nuit ?** Notre `fs` mesuré changerait en cours de nuit. `SensorPipeline` doit donc **mesurer `fs` en continu par fenêtre de 60 s** et lever un drapeau si l'écart au nominal dépasse 5 %, plutôt que de faire confiance au taux demandé. Ce garde-fou est utile que le folklore soit vrai ou non.
13. **Débit du Data Layer 20–100 Ko/s** : aucune source. Le critère P3 de `SPEC-v1.md` (« 8,8 Mo en < 5 min ») correspond à 30 Ko/s, ce qui est au milieu de la fourchette folklorique — donc ni garanti ni déraisonnable. **À mesurer avant d'en dépendre.**
14. **Quota du magasin de `DataItem`** : inconnu. Traité par le plafond de 24 items en vol.
15. **Off-body à la cheville** : inconnu, jamais actionné (§3.4).
16. **Mode Coucher / Économiseur de batterie de Wear OS** : effet inconnu sur un FGS `health`. **Test P1 explicite, mode Coucher ACTIVÉ**, parce que c'est la configuration réelle. Tester sans, c'est valider une configuration que l'utilisateur n'utilisera jamais.

---

## 5. UI de la montre

Principe : **l'écran de la montre est un instrument de vérification au coucher et de constat au réveil, rien d'autre.** Toute l'analyse est sur le téléphone. Chaque pixel qui bouge est un réveil du SoC.

### 5.1 Écrans

**Un seul.** Pas de navigation, pas de `NavHost`, pas de tuile, pas de complication.

| État | Contenu | Action |
|---|---|---|
| `PREFLIGHT_BLOCKED` | Une ligne rouge par bloqueur, formulée en action | bouton correctif |
| `IDLE` | « Prêt » · batterie % · espace libre · « N nuits en attente de synchro » si reliquat | **START** plein écran |
| `RECORDING` | durée `3 h 12` · `Mo` écrits · batterie % · `trous : 0` · mode (`WAKEUP 30 s`) · sync `38/38` | **STOP** (confirmation) |
| `FINALIZING` | « Fermeture… » | — |
| `SYNCING` | `72/96 envoyés` | — |
| `DONE` | « 8 h 02 · 8,7 Mo · 96/96 synchronisés » | **START** |

### 5.2 Ce qui est interdit

- Toute animation : `rememberInfiniteTransition`, `AnimatedVisibility`, `animateFloatAsState`, indicateurs de progression circulaires, transitions de couleur.
- Tout `LaunchedEffect` à cadence < 30 s. **Une seule source : un `StateFlow<RecordUiState>` émis toutes les 30 s** par le service, collecté avec `collectAsStateWithLifecycle()`.
- Tout affichage de graphe, d'historique, de PLMI, de courbe. Sur la montre, un graphe est du calcul et des recompositions pour zéro information actionnable.
- Toute vibration, tout son, tout `setOngoing` avec `IMPORTANCE_DEFAULT`.
- `keepScreenOn`, always-on, `setAmbientEnabled()`.
- Tout réglage au-delà de deux : `stopAtLocalTime` et `PUSH_EVERY_N_CHUNKS` (réglage développeur, caché).

Formulation du critère, testable : **entre le coucher et le réveil, la couche UI ne doit provoquer aucune recomposition** — l'écran est éteint, `collectAsStateWithLifecycle` est arrêté, et le service émet dans le vide. Vérifiable au `Layout Inspector` / compteur de recompositions en P1.

### 5.3 Erreurs visibles au coucher

C'est le seul moment où l'utilisateur regarde. Le préflight s'exécute **avant** `STARTING` et sépare bloqueurs et avertissements.

**Bloqueurs — le START est désactivé :**

| Cause | Message | Action |
|---|---|---|
| `POST_NOTIFICATIONS` refusée | « Autorise les notifications, sinon l'enregistrement s'arrête tout seul » | ouvre les réglages |
| Démarrage FGS refusé (`SecurityException`) | « Autorisation d'activité physique requise » | demande `ACTIVITY_RECOGNITION` |
| Espace < 300 Mo après purge | « Stockage plein — synchronise d'abord (N nuits en attente) » | force un balayage |
| Aucun `TYPE_ACCELEROMETER` | « Accéléromètre indisponible » | — |

**Avertissements — le START reste possible, une ligne ambre :**

| Cause | Message |
|---|---|
| Batterie < 40 % | « Batterie 32 % — 50 % recommandé pour 8 h » |
| Téléphone injoignable (`CapabilityClient`) | « Téléphone injoignable — l'enregistrement continue, la synchro se fera plus tard » |
| Pas de capteur wake-up | « Mode wake lock — autonomie réduite » |
| Session précédente non synchronisée | « Nuit du 12/07 : 24 chunks en attente » |

**Le téléphone injoignable n'est jamais bloquant.** L'architecture entière de §2 existe pour que ce cas soit sans conséquence ; le signaler comme une erreur serait mentir à l'utilisateur et l'inciter à ne pas enregistrer.

**Après START** : un écran statique « Enregistrement démarré · mode WAKEUP 30 s » pendant 3 s, puis extinction. La vérification de bonne marche se fait ensuite d'un coup d'œil sur le chip de notification permanent — pas en rallumant l'app.

---

## 6. Classes Kotlin

### 6.1 `format` (JVM pur, testable, partagé) — *à ajouter à l'existant*

| Classe | Responsabilité |
|---|---|
| `ChunkMeta` | Métadonnées d'un chunk sur le fil : `sessionUuid, idx, size, crc32, tFirstNs, tLastNs, sampleCount, flagsOr`. |
| `ChunkSummary` | Scanne un fichier de chunk et en dérive un `ChunkMeta` sans décoder les échantillons. |
| `AckBitmap` | Encode/décode le bitmap d'acquittement (`bitmapBase` + `ByteArray`), avec `ackedUpTo` et itération des index manquants. |
| `SessionState` | Enum + sérialisation de l'état de session (`OPEN`, `CLOSED`, `STALE`, `TRUNCATED`) et de `stopReason`. |
| `SweepFraming` | Écriture/lecture du flux encadré `[idx][len][octets][crc32]` du balayage `ChannelClient`. |
| `PreviewEnvelopeCodec` | Quantification u8 logarithmique de l'enveloppe 1 Hz et son inverse. |

### 6.2 `wear`

| Classe | Responsabilité |
|---|---|
| `SensorStrategy` | **Pur** : `(isWakeUp, fifoReserved, fifoMax, rateHz) → AcquisitionMode`. Aucune dépendance Android, entièrement testable en JVM. |
| `SensorPipeline` | Enregistre l'écouteur, convertit les lots `SensorEvent` en blocs ≤ 512, mesure `fs` réel par fenêtre de 60 s, alimente `GapMonitor` et `PreviewEnvelope`. |
| `GapMonitor` | **Pur** : accumule les statistiques de timing et décide du palier de dégradation (0 → 3). |
| `ChunkStore` | Nommage, rotation (300 s / 92 160 o), politique de `fsync`, listing, suppression, purge de quota. |
| `SessionStore` | Écriture atomique de `active_session.json` et `sidecar.json`. |
| `PreviewEnvelope` | Passe-haut + RMS en flux, décime à 1 Hz, produit la fenêtre de 900 octets. Réutilise `:algo` (JVM pur). |
| `BatteryLogger` | Relevé batterie/60 s vers le sidecar ; déclenche l'arrêt propre à ≤ 5 %. |
| `OffBodyLogger` | Écoute `TYPE_LOW_LATENCY_OFFBODY_DETECT`, journalise, positionne `FLAG_OFF_BODY`. **N'arrête jamais rien.** |
| `WakeDetector` | **Pur** : détecte la locomotion soutenue (époques 30 s sur 10 min) → arrêt automatique. |
| `RecordingService` | FGS `health` : machine à états du §3.7, wake lock, notification, ticks 10 s / 60 s / 15 min. |
| `SyncCoordinator` | Décide **quoi** pousser et **quand** : cadence `N`, plafond de 24 items en vol, bascule en mode balayage. |
| `ChunkPublisher` | Effectue les `putDataItem` / `deleteDataItems` : chunk, session, live. |
| `AckObserver` | `WearableListenerService` : `/pendulum/ack` → supprime fichiers et items, gère `needResend` ; `/pendulum/sweep-request` → lance le balayage. |
| `SweepSender` | Ouvre le `ChannelClient` et écrit le flux `SweepFraming` des chunks non acquittés. |
| `TransferWorker` | `WorkManager` : rattrapage hors service (matin, reboot, backlog), contrainte batterie > 30 % ou chargeur. |
| `BootReceiver` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → reprise **ou** finalisation, selon les cinq conditions du §3.5. |
| `PreflightChecker` | **Pur** (entrées injectées) : produit `List<Blocker>` + `List<Warning>`. |
| `RecordViewModel` / `RecordScreen` / `MainActivity` | UI unique, `StateFlow` à 30 s, zéro animation. |

### 6.3 `phone` (partie transfert seulement)

| Classe | Responsabilité |
|---|---|
| `PendulumListenerService` | `WearableListenerService` : `onDataChanged` (chunk, session, live), `onChannelOpened` (balayage). |
| `ChunkIngestor` | Vérifie taille + CRC32, écrit le fichier, `INSERT OR IGNORE` sur `(sessionId, idx)`. Idempotent. |
| `AckPublisher` | Recalcule le bitmap **depuis Room** et réécrit `/pendulum/ack/<session>`. |
| `SessionMonitor` | Machine à états `OPEN → STALE → TRUNCATED/CLOSED` et réconciliation des chunks tardifs. |
| `SessionWatchdogWorker` | Périodique 30 min tant qu'une session est `OPEN` ; applique les règles du §2.7. |
| `SweepReceiver` | Lit le flux `SweepFraming`, persiste trame par trame, met à jour l'ack toutes les 16 trames. |
| `LiveStatusRepository` | Expose le dernier `/pendulum/live` (enveloppe d'aperçu + compteurs) à l'UI téléphone. |

### 6.4 Ordre d'implémentation

L'ordre est contraint par le fait que **P1 est une phase go/no-go** : il faut le minimum absolu qui permette de mesurer l'autonomie et la complétude, et rien de plus.

**Vague 1 — le spike P1 (rien d'autre ne compte tant qu'il n'a pas passé)**
1. `SensorStrategy` (+ tests JVM sur les quatre branches)
2. `ChunkStore` (rotation, fsync ; réutilise le `ChunkWriter` existant)
3. `SensorPipeline` (blocs, `fs` mesuré)
4. `RecordingService` (FGS `health`, wake lock, START/STOP, ticks) — **valider ici le démarrage du FGS qualifié par `HIGH_SAMPLING_RATE_SENSORS`**
5. `RecordScreen` minimal (START/STOP + 5 compteurs)
6. `BatteryLogger`, `OffBodyLogger`
7. → **3 nuits de mesure, mode Coucher activé.** Critères de `SPEC-v1.md` P1.

**Vague 2 — robustesse locale (P2)**
8. `GapMonitor` + escalade
9. `SessionStore` + `BootReceiver` (avec les cinq conditions)
10. `WakeDetector` + les autres conditions d'arrêt
11. Purge de quota

**Vague 3 — le transfert (P3)**
12. `format` : `ChunkMeta`, `ChunkSummary`, `AckBitmap`, `SessionState` (+ tests JVM)
13. `phone` : `PendulumListenerService`, `ChunkIngestor`, `AckPublisher` — **le récepteur d'abord**, on ne teste pas un émetteur sans récepteur
14. `wear` : `ChunkPublisher`, `SyncCoordinator`, `AckObserver` → **boucle nominale complète, testable en coupant le Bluetooth au milieu**
15. `SessionMonitor` + `SessionWatchdogWorker` → scénario « montre morte à 3 h » (test : `adb shell am force-stop` en pleine nuit)
16. `SweepFraming`, `SweepSender`, `SweepReceiver`, `TransferWorker` → scénario « téléphone éteint toute la nuit »
17. `PreviewEnvelope`, `PreviewEnvelopeCodec`, `LiveStatusRepository` — **en dernier** : c'est du confort, pas de la résilience

**Vague 4 — finition**
18. `PreflightChecker` + états d'erreur de l'UI

Justification de l'ordre du transfert : **la boucle d'acquittement (13-14) avant le balayage (16)**, parce que le balayage a besoin du bitmap d'acquittement pour savoir quoi envoyer. Et `PreviewEnvelope` en dernier parce que c'est la seule fonctionnalité du document dont l'absence ne fait perdre aucune donnée.

---

## 7. À vérifier toi-même

- **Mes milliampères sont des estimations, pas des mesures.** Le rapport entre les options est solide, les valeurs absolues ne le sont pas. La seule mesure qui tranche est P1 : enregistre une nuit *sans aucun transfert*, puis une nuit *avec la synchro à 15 min*, et compare les deux pourcentages de batterie restants. Si l'écart dépasse 5 points, mon modèle est faux et il faut repasser au batch.
- **`fifoReservedEventCount` sur ta montre** : lis-le au runtime avant toute autre décision. S'il vaut 0 et que `isWakeUpSensor` est faux, le wake lock permanent est obligatoire et le budget batterie du projet est probablement mort — c'est le vrai go/no-go, pas les 3 % de radio.
- **Que `HIGH_SAMPLING_RATE_SENSORS` suffise à qualifier le FGS `health` sur cette montre.** La doc l'autorise, l'implémentation peut diverger. Un `startForeground()` qui lève `SecurityException` au premier essai vaut tous les raisonnements ; c'est un test de dix minutes en P0.
- **Le plafond de 100 Ko d'un `DataItem` avec 91 Ko utiles** : vérifie qu'un `putDataItem` de cette taille passe réellement, et mesure le temps de bout en bout. Si ça échoue, bascule sur `Asset` — un seul point de code.
- **Le comportement du mode Coucher de Wear OS** sur un FGS `health` : c'est la configuration réelle d'usage et personne ne l'a documentée. Teste avec, jamais sans.
- **Combien de temps une montre verrouillée met à reprendre après un reboot nocturne** (§3.5). Si la réponse est « jamais avant le matin », l'arrêt propre sur batterie faible devient la principale protection contre la perte de nuit, et il faut le tester en priorité.
- **L'affirmation sur `ExerciseClient` est du folklore que je n'ai pas pu vérifier.** La règle de prudence reste bonne, mais ne la cite pas comme un fait, et implémente la mesure continue de `fs` — elle te protège quelle que soit la vérité.
