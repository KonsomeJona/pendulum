# Pendulum — Revue critique adversariale de SPEC-v1

Portée : `docs/SPEC-v1.md`, `format/src/main/kotlin/com/pendulum/format/ChunkFormat.kt`, `ChunkCodec.kt`, `format/src/test/kotlin/com/pendulum/format/ChunkCodecTest.kt`.
Posture : je cherche à faire échouer la spec, pas à la valider. Ce qui suit suppose que l'objectif réel est **un chiffre défendable devant un médecin du sommeil**, pas une app qui tourne.

Note de méthode : le module `format` n'a manifestement **jamais été compilé** (ses dépendances ne sont pas dans le cache Gradle local, le plugin KSP n'est pas résolvable hors ligne). Les défauts F-01 et suivants sont donc issus d'une lecture octet par octet, pas d'une exécution. F-01 doit être confirmé par un `./gradlew :format:test`.

---

## 1. Tableau des défauts

| # | Sévérité | Section | Défaut |
|---|---|---|---|
| F-01 | BLOQUANT | `ChunkFormat.toRaw` | Ne compile pas : `Long.coerceIn(Int, Int)` n'a aucune surcharge. Tout le module est mort. |
| F-02 | BLOQUANT | `ChunkCodec` bloc | Le CRC ne couvre que le payload. `count`, `tFirstNs`, `tLastNs`, `flags` ne sont protégés par rien. |
| F-03 | BLOQUANT | Format §chunk + `DecodedBlock.timestampNs` | L'interpolation linéaire est fausse dès qu'un bloc chevauche deux vidages FIFO ; rien n'empêche ce cas. |
| F-04 | BLOQUANT | `computePlmi` + `deriveImmobilityMask` | Dénominateur circulaire : le temps de sommeil est dérivé du signal dont on compte les mouvements. |
| F-05 | BLOQUANT | Room `plm_result` (4 lignes/nuit) + sliders UI | Quatre chiffres concurrents, aucun critère principal préenregistré, paramètres modifiables a posteriori. |
| F-06 | BLOQUANT | Wear §auto-dégradation + P1 | La détection de trous n'est pas définie (arrivée vs `SensorEvent.timestamp`) → en mode batché elle se déclenche toujours, le wake lock est toujours pris, P1 teste autre chose que ce qu'elle croit. |
| F-07 | MAJEUR | Spec §format vs `HEADER_SIZE` | Spec : entête 64 o. Code et test : 80 o. La liste de champs de la spec somme déjà à 68 o hors réservé → 64 est arithmétiquement impossible. |
| F-08 | MAJEUR | Entête de fichier | L'entête n'est protégée par aucun CRC. Un octet corrompu dans `startWallMs` date toute la nuit faux, sans détection. |
| F-09 | MAJEUR | Entête + Room `night_session` | Aucun champ de fuseau/offset UTC malgré le piège n°10 ; rien n'interdit de calculer des durées par différence d'horloge murale. |
| F-10 | MAJEUR | `MODE_DEGRADED` | Flag d'entête dans un format append-only : impossible à écrire quand l'auto-dégradation survient en cours de chunk (jusqu'à 30 min étiquetées d'un mode faux). |
| F-11 | MAJEUR | `toRaw` | Écrêtage silencieux à ±16 g, sans flag ni compteur. Le distracteur « clipping » de la §Vérification est indétectable à la relecture. |
| F-12 | MAJEUR | `ChunkFile.truncatedTail` | Queue tronquée bénigne et désynchronisation en plein fichier produisent le même état ; `corruptBlocks = 1` peut masquer des milliers de blocs perdus. |
| F-13 | MAJEUR | Transfert §protocole | Rien n'interdit de mettre au manifeste le chunk **en cours d'écriture** : le téléphone acke un fichier partiel, la montre le supprime. |
| F-14 | MAJEUR | `ChunkWriter.writeBlock` / `ChunkReader` | Aucune validation temporelle : `tLast < tFirst` accepté (pas d'exception), `n=100, tFirst=0, tLast=1` accepté — les tests eux-mêmes verrouillent ce comportement absurde. |
| F-15 | MAJEUR | `buildSeries` / règles WASM | La règle de regroupement de WASM 2016 n'est pas implémentée : seule la fenêtre IMI change. Les « deux jeux de règles » n'en sont pas deux. |
| F-16 | MAJEUR | `plm_result.plmw` | PLMW stocké sans dénominateur, et sans `AWAKE_IN_BED` il inclut le lever et la marche → chiffre faux et alarmant. |
| F-17 | MAJEUR | `deriveImmobilityMask` | Cole-Kripke est calé sur le **poignet**, sur des époques d'1 min d'*activity counts*. Transposé à la cheville sur du RMS 30 s, ses coefficients n'ont plus de sens. |
| F-18 | MAJEUR | Stratégie FIFO | `max(fifoReservedEventCount, fifoMaxEventCount)` prend l'optimiste : `fifoMax` est partagé entre toutes les apps, `fifoReserved` est le seul garanti. |
| F-19 | MAJEUR | Formule de latence | `0.5*fifo/50` puis `clamp 10..60 s` : à fifo = 500 le clamp remonte la latence à 10 s = 500 événements = 100 % du FIFO, annulant la marge 0,5 pile au seuil de la branche. |
| F-20 | MAJEUR | Vérification §5 | « ICC inter-nuits » sur N=1 n'est pas un ICC ; « kappa masque accéléro vs HC » compare deux estimateurs dérivés du même mouvement. Deux chiffres rassurants et vides. |
| F-21 | MAJEUR | P4 | Validation circulaire (synthétique généré par les hypothèses du détecteur) + « 0 FP sur 10 min » ne borne rien (compatible avec ~144 FP/nuit à 95 %). |
| F-22 | MAJEUR | P1 critère (a) | « ≥ 97 % » et « cumul < 2 min » (= 99,6 %) sont incohérents ; « plus grand trou < 5 s » est incompatible avec une latence de batch jusqu'à 60 s. |
| F-23 | MAJEUR | P2 critère | « ≤ 1 chunk perdu » = jusqu'à 30 min, alors que toute la justification du format est de borner la perte à un bloc (10 s). |
| F-24 | MAJEUR | UI tendance + §clinique | Le seuil 15/h est un critère de PLMD, or le PLMD **s'efface** devant un SJSR. Chez ce sujet, comparer à 15/h n'a pas de sens diagnostique. |
| F-25 | MAJEUR | Contexte / Résultat attendu | Le sujet est **déjà traité** : sans ligne de base non traitée, aucun « effet du traitement » n'est mesurable. La spec ne le dit nulle part. |
| F-26 | MAJEUR | Entête | `fifoMaxEventCount.toShort()` / `nominalRateHz.toShort()` tronquent en u16 sans `require`. Un FIFO > 65535 est enregistré modulo 65536. |
| F-27 | MAJEUR | `ChunkReader.read` | Matérialise toute la nuit en `List<DecodedBlock>` (~17 Mo utiles + ~8400 tableaux) alors que l'algo est explicitement streaming. |
| F-28 | MAJEUR | Durabilité | `out.flush()` sur un `FileOutputStream` est un no-op ; le writer n'a pas accès au `FileDescriptor`. Le commentaire « borne la perte à un seul bloc » n'est pas garanti par ce code. |
| F-29 | MAJEUR | Tableau d'hypothèses | La confiance « Moyenne » accordée au transfert de validité depuis Spektor 2024 est surestimée (capteur dédié, algorithme propriétaire, cohorte enrichie, r inter-sujets ≠ justesse événementielle). |
| F-30 | MAJEUR | P1 critère (c) | « off-body porté > 95 % » n'est pas falsifiable : `TYPE_LOW_LATENCY_OFFBODY_DETECT` peut être absent, et son état n'influe pas sur la survie du service. |
| F-31 | MINEUR | `ChunkReader.readFully` | Boucle infinie si `read()` renvoie 0 sans EOF (cas réel dès qu'on branche un `Channel` du Data Layer). |
| F-32 | MINEUR | Entête | Version rejetée strictement, et pas de champ `headerSize` : impossible d'évoluer sans casser la relecture des archives. |
| F-33 | MINEUR | Spec §format | « ±16 g exact » est faux d'un LSB côté positif (32767/2048 = 15,9995 g). |
| F-34 | MINEUR | CRC16 | 1/65536 de non-détection par bloc × ~2800 blocs/nuit ≈ 4 % de chance de laisser passer un bloc corrompu **si** la corruption est fréquente. Incohérent avec le CRC32 du transfert. |
| F-35 | MINEUR | `ChunkFile` | Les blocs corrompus ne sont pas localisés (offset, plage temporelle) : impossible de convertir « 3 blocs perdus » en « 30 s manquantes à 3h12 ». |
| F-36 | MINEUR | Spec §débit | « 306 o/s » : le calcul réel donne 303,1 o/s (300 × 1,0104). Sans conséquence, mais le chiffre a été posé sans être vérifié. |
| F-37 | MINEUR | Format | Pas de marqueur de fin de fichier : impossible de distinguer « chunk complet » de « chunk en cours ». Cause racine de F-13. |
| F-38 | MINEUR | Modèle de données | Aucune définition de « la nuit du JJ/MM » ni gestion des siestes : deux sessions le même jour cassent la tendance. |

### Détail des défauts qui exigent du code

**F-01 — ne compile pas.**
`Math.round(ms2 / G_IN_MS2 * LSB_PER_G)` : `Float / Double → Double`, donc `Math.round(Double) → Long`. Puis `lsb.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())` : ni la surcharge `Long.coerceIn(Long, Long)` ni la générique `<T : Comparable<T>>` ne s'unifient avec (Long, Int, Int) — Kotlin n'élargit pas Int en Long implicitement. Correction :

```kotlin
fun toRaw(ms2: Float): Short {
    if (!ms2.isFinite()) return 0                    // sinon Math.round(NaN) = 0 en silence
    val lsb = Math.round(ms2 / G_IN_MS2 * LSB_PER_G) // Long
    return lsb.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
}
```
Corollaire : un `NaN` renvoyé par le capteur devient un 0 parfaitement plausible (chute libre) sans aucune trace. À compter et à flaguer.

**F-02 — le CRC protège le mauvais champ.**
Les 6 octets d'un échantillon sont localement redondants (voisins quasi identiques) ; une corruption y est bénigne. `tFirstNs`/`tLastNs` sont uniques et non redondants : les corrompre décale toute la base de temps de la nuit, donc la fusion avec l'hypnogramme, donc l'attribution des CLM aux stades — **sans aucune détection**. Pire, corrompre `count` fausse la longueur de payload lue : le CRC échoue, le code fait `continue` en commentant « on garde la synchro », alors que c'est exactement le cas où la synchro est perdue (décalage de `(count_vrai − count_lu) × 6` octets). Correction :
- CRC calculé sur `blockHeader[0..24)` ‖ payload, le champ `crc` étant écrit en dernier (offset 24) ;
- sur échec CRC, **resynchroniser** en cherchant le prochain `BLK!` au lieu de `continue` aveugle, et compter les octets sautés ;
- test dédié : corrompre `count`, corrompre `tLastNs`, vérifier que les deux sont rejetés (aucun test actuel ne corrompt l'entête de bloc — la lacune est visible dans `ChunkCodecTest`).

**F-03 — l'interpolation est fausse au travers d'un flush.**
`ChunkFormat` affirme « le FIFO matériel échantillonne uniformément ». Vrai **à l'intérieur** d'un batch, faux **entre** deux batches séparés par un suspend. Or `MAX_SAMPLES_PER_BLOCK = 512` = 10,24 s à 50 Hz, pour une latence de batch de 10 à 60 s : un bloc peut donc contenir la fin d'un batch et le début du suivant, avec un trou au milieu. `timestampNs(i)` étale alors l'écart linéairement : **tous** les échantillons du bloc sont datés faux, l'erreur pouvant atteindre plusieurs secondes, et `FLAG_GAP_BEFORE` ne marque que la frontière de bloc, pas la frontière interne. Correction :
- contrat writer : `require` qu'un bloc ne contienne qu'un seul batch ; le producteur coupe le bloc sur toute frontière de flush **et** sur tout écart de `SensorEvent.timestamp` > 1,5 / fs ;
- contrat reader : rejeter/flaguer `SUSPECT_TIMEBASE` si `|(tLast − tFirst)/(N−1) − 1e9/fs| > 20 %` ;
- test : bloc contenant un trou de 3 s → doit être refusé à l'écriture.

**F-06 — la détection de trous n'est pas spécifiée, et le défaut naturel est le mauvais.**
En `BATCHED_WAKEUP`, les échantillons arrivent par paquets espacés de 10 à 60 s. Si « trou > 3 s » est mesuré sur les **arrivées**, la règle « 3 fois en 10 min → wake lock » se déclenche au troisième batch, toujours. Conséquences en cascade : la stratégie batchée n'est jamais réellement testée, le wake lock est systématiquement pris, le critère batterie (b) de P1 échoue pour une raison purement logicielle, et l'escalade « batched → wake lock → 25 Hz » se déclenche sur un faux signal. Le trou doit être mesuré **exclusivement** sur les écarts de `SensorEvent.timestamp` entre échantillons consécutifs (> 3/fs), jamais sur l'horloge d'arrivée. À écrire dans la spec, et à tester avec un flux synthétique batché avant la première nuit.

**F-04 / F-05 / F-17** : traités en §3, ce sont les défauts qui produisent un chiffre faux et crédible.

---

## 2. Ce qui manque

**Calibration inter-nuits.** Le piège n°9 identifie le problème (bracelet, serrage, ×2-3 d'amplitude) et n'en tire aucun mécanisme. Sans référence physique, le seuil adaptatif absorbe le changement de couplage et le PLMI change sans que rien n'ait changé chez le sujet. À ajouter : un rituel de calibration **codé**, exécuté avant chaque nuit, 60 s au total — 20 s montre posée sur le matelas (bruit de literie), 20 s jambe immobile (plancher de bruit porté), 3 dorsiflexions marquées par un tap (réponse à un stimulus standard) — d'où un `calibrationGain` et un `noiseFloorRef` par nuit, stockés. Une nuit dont le gain s'écarte de plus de ±25 % de la nuit de référence est **exclue de la tendance par le code**, pas par le jugement de l'utilisateur. Ajouter aussi des champs obligatoires : jambe (G/D), identifiant de bracelet, cran de serrage.

**Partenaire de lit.** Un matelas transmet. Les mouvements périodiques d'un partenaire — ou d'un chat — peuvent être scorés comme les siens, et il n'existe aucun moyen de les séparer avec un seul capteur. Rien dans la spec. À ajouter : champ obligatoire « seul / accompagné » dans le formulaire pré-sommeil, exclusion des nuits accompagnées de la comparaison principale (ou stratification), et une nuit de contrôle explicite « montre sur le matelas côté partenaire, pas sur la cheville » pour mesurer le plancher de diaphonie. Sans ce contrôle, tout PLMI mesuré à deux dans un lit a une composante inconnue.

**Artefacts de literie.** La spec cite ×2-2,3 d'amplification mais ne l'enregistre jamais. Couette lourde / drap seul / jambes hors couette changent le couplage et le plancher. À ajouter au formulaire pré-sommeil (3 options), et à traiter comme un critère de comparabilité au même titre que le bracelet.

**Changement d'heure.** Le piège n°10 le nomme, aucun champ ne l'implémente : ni l'entête binaire ni `night_session` ne stockent de fuseau. À ajouter : `zoneId` (l'identifiant IANA, pas l'offset) + offset au début et à la fin, dans les 12 octets réservés de l'entête et dans Room. Règle absolue à faire respecter par des tests : **toute durée se calcule sur `SensorEvent.timestamp` ou `elapsedRealtime`, jamais par différence de `startWallMs`** — cela couvre aussi le cas plus fréquent que le DST, la resynchronisation NTP en pleine nuit qui déplace l'horloge murale de plusieurs secondes.

**Versionnement de l'algorithme et re-scoring rétroactif.** `algoVersion` existe sur `night_session` mais rien ne l'utilise. Il manque : un `paramsHash` sur **chaque** `plm_result` ; une vue SQL `comparable_night` que toute requête de tendance doit traverser ; l'interdiction structurelle de tracer une courbe mélangeant deux `(algoVersion, paramsHash)` ; et un `RescoreAllWorker` déclenché par tout changement de paramètre, qui recalcule **toutes** les nuits depuis les chunks bruts. Le slider « recalcul » du détail de nuit, tel qu'il est spécifié, produit exactement l'artefact interdit : une nuit rescorée avec de nouveaux paramètres comparée à des nuits scorées avec les anciens.

**Protection des données de santé sur le téléphone.** Rien dans la spec. Manquent : `android:allowBackup="false"` + `dataExtractionRules` (sinon la base part dans la sauvegarde Google par défaut), pas de permission `INTERNET` déclarée du tout (le Data Layer n'en a pas besoin — l'absence de la permission est une garantie vérifiable, contrairement à une promesse), verrou biométrique sur l'ouverture, `FLAG_SECURE` sur les écrans de résultat, export par SAF vers un emplacement choisi et non vers `Downloads`, aucun SDK d'analytics ni de crash reporting, et un bouton « tout supprimer » qui efface aussi les chunks bruts. Les données lues depuis Health Connect ne doivent jamais quitter l'app autrement que par l'export explicite.

**Sauvegarde / export.** 8,8 Mo/nuit × 60 nuits ≈ 530 Mo de chunks bruts, sans politique de rétention, sans archivage hors appareil, sans réimport. Les chunks bruts sont pourtant la **seule** chose qui permettra de rescorer quand l'algorithme changera — et il changera. À ajouter : export d'un bundle par nuit (chunks + sidecar + lignes de base) vers un stockage externe, réimport testé, et un test qui prouve qu'une base reconstruite depuis un bundle donne un PLMI identique au bit près.

**Nuits partiellement perdues.** Aucune règle. À coder : le dénominateur n'est pas le TST mais le **temps de sommeil analysable** = époques de sommeil ∩ époques réellement couvertes par des blocs valides (bitmap d'époques de 30 s reconstruit à la relecture). Règle de refus : moins de 4 h analysables, ou couverture < 85 % du TST, ou un trou unique > 20 min → **aucun chiffre affiché**, la nuit est marquée non exploitable et n'entre pas dans la tendance. Jamais un chiffre avec un astérisque : l'astérisque ne survit pas au trajet jusqu'au cabinet du médecin.

**Arrêt automatique au réveil.** Le piège n°11 le nomme sans le concevoir. À coder : arrêt sur mise en charge (`ACTION_POWER_CONNECTED`), ou sur off-body soutenu > 5 min, ou à `startWallMs + 11 h`, le premier des trois ; raison d'arrêt inscrite dans le sidecar ; et les 10 min précédant l'arrêt exclues de l'analyse par défaut. Symétriquement — et c'est le manque le plus lourd — le **démarrage manuel** est le mode de défaillance le plus probable : oublier de lancer l'enregistrement n'est pas aléatoire, on l'oublie les soirs où l'on est fatigué, éméché, ou en déplacement, c'est-à-dire des soirs corrélés au résultat. À ajouter : rappel programmé, et un enregistrement explicite « nuit manquante » pour que la tendance montre ses trous au lieu de les refermer.

**Manquent aussi**, hors liste demandée : aucune migration Room prévue (risque de `fallbackToDestructiveMigration` = perte de l'historique) ; aucune gestion de la révocation automatique des permissions Health Connect après 30 jours d'inactivité (le `SleepFetchWorker` échouera en silence) ; aucun instantané de ce que Health Connect a renvoyé (Samsung Health réécrit rétroactivement ses sessions — il faut stocker `metadata.lastModifiedTime` et rescorer quand il change, en conservant les deux versions) ; aucune estimation de l'écart d'horloge entre les deux montres, alors qu'un décalage de 2 min déplace des événements de part et d'autre de l'endormissement.

---

## 3. Risque d'auto-tromperie

Ce projet produit un nombre qui influencera une décision de traitement. La question n'est donc pas « le nombre est-il joli » mais « par quels chemins précis peut-il être faux tout en paraissant solide ». Il y en a neuf.

**A-1. Dénominateur circulaire (le plus grave).** Le PLMI est `PLMS / heures de sommeil`, et en l'absence de Health Connect le « sommeil » est estimé à partir du **même** accéléromètre qui fournit le numérateur. Plus il bouge, moins d'époques sont classées « sommeil », plus le dénominateur rétrécit. Le traitement réduit les mouvements : le numérateur baisse **et** le dénominateur grossit, dans le même mouvement. L'effet apparent est donc mécaniquement amplifié — un effet nul peut s'afficher comme une amélioration franche.
Garde-fou à coder : `maskSource = ACCEL_MACRO` **interdit** pour le résultat principal (contrainte au niveau du DAO, pas de l'UI) ; toujours stocker et afficher numérateur et dénominateur séparément ; calculer systématiquement un second index normalisé par le **temps au lit** (indépendant de l'algorithme) et refuser toute conclusion si les deux index divergent en direction.

**A-2. Le seuil adaptatif rend les nuits non comparables par construction.** Le seuil vaut 8 × plancher de bruit estimé sur la nuit elle-même. Une nuit plus calme abaisse le plancher, abaisse le seuil, et fait franchir la barre à des micro-mouvements auparavant invisibles : **moins de mouvement peut produire plus d'événements**. Une nuit bruyante (partenaire, couette épaisse) fait l'inverse. Le chiffre bouge sans que le sujet ait changé.
Garde-fou à coder : plancher absolu, `seuil = max(8 × plancherNuit, seuilAbsoluCalibré)` ; stockage du plancher médian de chaque nuit ; prédicat automatique de comparabilité sur ce plancher (±30 %) qui exclut la nuit de la tendance ; et surtout un **second bras de scoring à seuil absolu fixe**, calculé pour chaque nuit — si les deux bras ne varient pas dans le même sens, l'app affiche « effet non robuste » et ne trace rien.

**A-3. Effet d'attente parce qu'il connaît sa dose du soir.** Il sait ce qu'il a pris, donc : il enregistre plus fidèlement les nuits qu'il juge intéressantes ; il regarde le résultat au réveil et forme une attente pour la nuit suivante ; il bouge un slider et s'arrête quand la courbe raconte l'histoire attendue ; il écarte a posteriori les nuits « ratées », dont les drapeaux qualité sont corrélés à la quantité de mouvement, donc à la dose.
Garde-fous à coder, dans cet ordre d'efficacité :
1. **La dose du soir et le contexte sont saisis et scellés AVANT que la montre n'accepte de démarrer** (le bouton START est refusé tant que le formulaire du soir n'est pas soumis sur le téléphone) ; l'enregistrement est append-only, non modifiable, horodaté.
2. **Résultat masqué par défaut** : au réveil, l'app affiche « nuit enregistrée, qualité OK » et rien d'autre. Le résultat n'est révélé qu'au terme d'un bloc de N nuits déclaré à l'avance, ou par un geste explicite qui **journalise le dévoilement** — le nombre de coups d'œil est lui-même une donnée à exporter.
3. **Pas de slider par nuit.** Un changement de paramètre est global, bump du `paramsHash`, et déclenche un rescore de toutes les nuits. La tendance filtre sur un seul hash et refuse les mélanges.
4. **Pas de bouton « exclure cette nuit ».** Les exclusions sont des prédicats déterministes évalués avant le calcul ; les nuits exclues restent visibles en grisé avec leur motif, jamais effacées.
5. **Journal des analyses** (version, hash, horodatage, nombre de rescores) inclus dans l'export : un médecin doit pouvoir voir combien de fois les données ont été repassées.

**A-4. Comparaison de nuits non comparables.** Bracelet, serrage, jambe, literie, partenaire, alcool, caféine, maladie, chaleur, heure du coucher, durée de sommeil — chacun déplace le chiffre autant que le traitement. Garde-fou : la vue `comparable_night` évoquée en §2 comme unique porte d'entrée de la tendance, avec un prédicat explicite et testé (même jambe, même bracelet, seul, gain de calibration dans la tolérance, ≥ 4 h analysables, pas de nuit de changement d'heure). Ce qui ne passe pas le prédicat existe dans la base, s'affiche dans la liste, mais n'entre jamais dans une comparaison.

**A-5. Sur-interprétation d'une variation dans le bruit.** La spec cite elle-même le chiffre qui tue : chez des patients SJSR confirmés, le seuil de 15/h n'est franchi que sur ~34 % des nuits individuelles. La variabilité nuit à nuit intra-sujet est donc du même ordre que le signal recherché, avant même d'ajouter l'erreur de mesure de ce dispositif. Comparer « ma nuit d'hier » à « ma nuit de la semaine dernière » n'a aucun contenu informatif.
Garde-fous à coder :
- une fonction `minimumDetectableChange(sd)` dans `algo` : `MDC95 = 1,96 × √2 × SEM` estimé sur les nuits comparables déjà collectées ;
- l'UI n'affiche **jamais** un écart entre deux nuits ; elle affiche des points, une bande de MDC, et un texte explicite « variation indiscernable de la variabilité nuit à nuit » tant que l'écart est dans la bande ;
- refus de tracer une droite de tendance sous 10 points comparables ; refus d'afficher une comparaison A/B tant que le nombre de nuits par bras est inférieur à celui calculé depuis l'écart-type observé pour la différence minimale déclarée d'avance ;
- aucun verbe d'évolution (« amélioration », « aggravation », « ça baisse ») dans les ressources de chaînes — c'est vérifiable par un test unitaire sur le fichier de strings, et ce test est sérieux.

**A-6. La qualité des données biaise le chiffre dans une direction inconnue.** Blocs corrompus sautés, trous FIFO, périodes off-body, montre restée en charge sur la table de nuit : tous réduisent le numérateur sans réduire le dénominateur, ou l'inverse. Garde-fou : dénominateur = temps de sommeil **analysable** (cf. §2), assertion de test « somme des époques couvertes == échantillons décodés / fs », et refus de produire un chiffre sous 85 % de couverture.

**A-7. La règle des séries amplifie non linéairement toute erreur de détection.**

> **Correction du 29/07/2026 (vérifiée sur PubMed et par le calcul).** L'énoncé initial de ce paragraphe — « un seul événement manqué coupe une série de 6 en deux séries de 3 » — est **faux dans le régime typique**. Sous AASM, l'intervalle onset-onset doit tomber dans [5, 90] s : un IMI de 21 s doublé par un manqué vaut 42 s, reste dans la fenêtre, et **la série n'est pas rompue** — seul le compte baisse d'une unité. La rupture n'arrive que si l'intervalle fusionné dépasse 90 s, donc pour des IMI supérieurs à 45 s, ou après trois manqués consécutifs à 25 s. L'effet réel d'un taux de manqués est donc une **déflation du compte**, pas une fragmentation. Ce qui ne rend pas le problème bénin : voir `SPEC-v2.md` §5, où ce taux de manqués (39 % pour des raisons mécaniques, davantage si les mouvements alternent entre les jambes) devient l'argument central du changement de métrique principale.

Reste vrai, et c'est le fond du paragraphe : exiger 4 CLM consécutifs rend le compte non linéaire vis-à-vis de l'erreur de détection, et cette non-linéarité s'aggrave aux IMI longs, précisément là où l'on trouve les séries les plus fragiles. La sensibilité du chiffre au seuil est donc bien plus violente que celle du détecteur. Garde-fous : rapporter toujours le compte de CLM brut (robuste) **à côté** du compte de PLMS en série (fragile), et exiger l'accord de direction entre les deux ; transformer le balayage paramétrique ±20 % de la §Vérification en artefact **par nuit** (`plmiMin`, `plmiMax` stockés), pour que l'UI trace une bande et jamais un point.

**A-8. L'hypnogramme du poignet bouge avec le traitement.** Un traitement gabapentinoïde modifie l'architecture du sommeil (davantage d'ondes lentes, moins d'éveils) et réduit les mouvements ; or la Galaxy Watch 5 estime ses stades **à partir du mouvement et du rythme cardiaque**. Sous traitement, elle rapportera mécaniquement plus de sommeil profond et plus de TST — ce qui allongera le dénominateur du PLMI et confirmera l'histoire attendue, sans qu'aucune mesure indépendante ne l'ait établi. Garde-fou : lire les deux sources disponibles (Samsung Health et Sleep as Android) et traiter leur divergence de TST comme la barre d'erreur honnête du dénominateur ; drapeau si l'écart dépasse 30 min ; ne jamais présenter de PLMI par stade (deep/REM) autrement que comme exploratoire.

**A-9. Le nom du nombre.** Appeler « PLMI » un index produit par un bracelet de montre sur une cheville, avec un algorithme maison jamais confronté à une polysomnographie, garantit qu'il sera lu comme un PLMI de laboratoire — par l'utilisateur d'abord, par le médecin ensuite. Garde-fou : renommer la métrique dans **le code et l'export** (par exemple `aPLM-i`, « index de mouvements périodiques de cheville, estimé, non validé »), et rendre le bloc « méthodes et limites » structurellement inséparable de l'export (test qui échoue si l'export ne le contient pas). Le seul objectif défendable de ce rapport est d'obtenir un examen réel, pas de le remplacer.

**Corollaire méthodologique, à dire une fois clairement :** l'utilisateur suit **déjà un traitement**. Sans période de référence sans traitement, aucune donnée de cette app ne peut mesurer l'effet du traitement ; elle ne peut mesurer que la variabilité sous traitement, et éventuellement l'effet d'un changement de dose **si** ce changement est décidé par le médecin, appliqué par blocs suffisamment longs, et déclaré avant. Toute autre lecture est une reconstruction rétrospective.

---

## 4. Verdict sur (a) à (e)

**(a) Graphe de mouvement envoyé au téléphone, temps réel ou batch — REFUSÉ tel que formulé, ACCEPTÉ sous une autre forme.**
La justification avancée (« ne rien perdre si la montre s'arrête en pleine nuit ») ne tient pas : c'est déjà le travail du format append-only avec `fsync`, qui survit à un crash, à un reboot et à une batterie vide. Streamer des données brutes toute la nuit en Bluetooth ne protège que contre la destruction physique de la montre, et coûte des réveils radio des deux côtés — c'est-à-dire précisément le risque que la phase 1 doit mesurer. Ce que le besoin réel exprime, c'est « savoir que l'enregistrement est vivant ».
Compromis recommandé, trois canaux distincts :
1. **Battement de santé** toutes les 5 min via `DataClient` : ~40 octets (échantillons, trous, batterie, mode, octets écrits), ~96 items par nuit, coût négligeable, et le Data Layer se resynchronise tout seul quand le Bluetooth revient. Le téléphone déclenche une alerte **silencieuse** si le battement s'arrête > 15 min — surtout pas une alarme, qui détruirait la nuit qu'on cherche à mesurer ; le constat se lit au réveil.
2. **Aperçu de l'enveloppe à 1 Hz** (RMS décimé), poussé par blocs de 5 min : ~30 ko pour la nuit entière. C'est le « graphe de mouvement » qu'il veut voir, disponible en quasi-temps réel, pour un coût sans commune mesure avec le brut.
3. **Chunks bruts en batch uniquement**, sur `ChannelClient`, sous contrainte de charge, protocole idempotent déjà spécifié — en excluant impérativement le chunk en cours d'écriture (F-13).

**(b) Capture par service background plutôt qu'app à l'écran — ACCEPTÉ, c'est déjà la spec, mais le filet de sécurité est incomplet.**
Précision de vocabulaire qui a des conséquences : Android n'autorise pas un service *background* à échantillonner un capteur en continu ; il faut un service *foreground* avec notification persistante, ce que la spec prévoit correctement (`foregroundServiceType="health"`, pas de timeout 6 h/24 h contrairement à `dataSync`). Ce qui manque : `START_STICKY` ne redémarre pas de façon fiable un service tué par le low-memory killer pendant le Doze. Ajouter (i) un `PeriodicWorkRequest` de 15 min en chien de garde, qui relit `active_session.json` et relance le service s'il est mort, (ii) l'exemption d'optimisation de batterie — libre en sideload, (iii) une Ongoing Activity Wear OS pour que le système considère la session comme active, (iv) le déclenchement des trois voies de reprise (boot, `MY_PACKAGE_REPLACED`, chien de garde) testé en le provoquant réellement, pas en le supposant.

**(c) App montre très simple, juste le tracking — ACCEPTÉ sans réserve, avec deux ajouts non négociables.**
Une seule vue statique, pas d'animation, pas d'always-on : c'est le bon choix, et pour la bonne raison (chaque recomposition réveille le SoC). Deux ajouts : le START doit être **bloqué** tant que le formulaire du soir n'est pas scellé sur le téléphone (garde-fou A-3), et le STOP doit exiger un appui long avec confirmation — un STOP accidentel à 3 h du matin coûte une nuit entière. L'écran doit montrer d'un coup d'œil : durée, échantillons, trous, mode FIFO, batterie. Rien d'autre. **Aucun résultat, aucun chiffre de PLMI sur la montre**, jamais.

**(d) App téléphone qui récupère deep/REM via Health Connect — ACCEPTÉ pour le sommeil total, REFUSÉ pour l'usage par stade.**
Health Connect est le bon canal, et l'app doit le lire. Mais la validité s'arrête vite : les stades d'une montre grand public sont estimés par mouvement et fréquence cardiaque, et leur concordance époque par époque avec la polysomnographie pour le sommeil profond et le REM est médiocre. Usage défendable : **TST et masque veille/sommeil**, c'est-à-dire le dénominateur du PLMI. Usage non défendable : un PLMI par stade, ou toute affirmation du type « mes PLMS surviennent surtout en N2 ». À implémenter en plus : instantané de ce que HC a renvoyé, avec `dataOrigin` et `lastModifiedTime` (Samsung réécrit ses sessions après coup) ; détection de la révocation automatique des permissions après 30 jours ; estimation et stockage de l'écart d'horloge entre les deux montres ; lecture des **deux** sources (Samsung Health et Sleep as Android) en parallèle, leur divergence servant de barre d'erreur affichée. Et vérifier sur l'appareil que la Galaxy Watch 5 écrit bien des `stages` et pas seulement une durée : tout le croisement repose là-dessus.

**(e) « Beau, facile à utiliser et à comprendre » — ACCEPTÉ pour beau et facile à utiliser, NUANCÉ FERMEMENT sur « facile à comprendre ».**
Le soin apporté à l'interface est utile : une app pénible ne sera pas utilisée régulièrement, et l'irrégularité est le premier biais (A-3). Mais « facile à comprendre » veut dire « compressé », et compresser une estimation non validée en un grand chiffre coloré est exactement le mécanisme de défaillance de ce projet. La règle de conception doit être inversée : **l'interface a pour métier de rendre l'incertitude lisible**, pas de la ranger dans une note de bas de page. En pratique : jamais de chiffre sans sa bande (A-7) ; les heures analysables affichées au même poids visuel que l'index ; les drapeaux de qualité en première classe, pas en icône discrète ; aucun code couleur normal/anormal et aucune ligne à 15/h dans les graphes ; un panneau « pourquoi ce chiffre peut être faux cette nuit » qui liste les drapeaux réellement déclenchés, et non un disclaimer générique que l'œil apprend à sauter en trois jours.

---

## 5. À vérifier toi-même

1. **Que le module `format` compile et que ses tests passent** (`./gradlew :format:test`). F-01 est une erreur de compilation quasi certaine, et le fait que la question se pose signifie que la suite de tests de `ChunkCodecTest` n'a jamais été exécutée — donc qu'aucune des garanties qu'elle prétend verrouiller n'est acquise, y compris les 80 octets d'entête qui contredisent la spec.
2. **La phase 1 comme vrai go/no-go, avec un protocole écrit avant la première nuit** : état de départ de la batterie, mode avion ou non, suspend réellement provoqué (`adb shell dumpsys deviceidle force-idle`), et détection de trous calculée sur `SensorEvent.timestamp`. Sans cela, trois nuits « réussies » ne prouvent rien, et la piste du capteur dédié (Axivity AX3 / GENEActiv) doit rester ouverte plutôt que d'être écartée par acharnement.
3. **Que la Galaxy Watch 5 écrit bien des *stades* dans Health Connect, et non seulement une durée** — et à quel délai après le réveil. Tout le croisement, donc le dénominateur du PLMI, donc le chiffre entier, repose sur ce point. À vérifier sur ton appareil, avant d'écrire une ligne du module `phone`.
4. **Les règles WASM 2016 sur le texte de Ferri et al., et les critères AASM v3 sur le manuel lui-même** : ma lecture est que la différence structurante de WASM n'est pas la fenêtre d'intervalle mais la règle de regroupement des mouvements proches (F-15), et je ne l'affirme qu'avec une confiance moyenne. Si je me trompe, ton implémentation « deux jeux de règles » est encore plus creuse que je ne le dis, puisqu'elle ne différerait alors que d'une borne.
5. **Le cadrage médical, avant la première nuit et non après la septième** : demander au médecin du sommeil ce qu'il attend d'une mesure à domicile, et s'il existe une période de référence sans traitement exploitable. S'il n'y en a pas, admets d'emblée que ce dispositif ne mesure pas l'effet du traitement (F-25, corollaire A-9), et repositionne l'objectif sur ce qu'il peut réellement produire : une preuve qu'il se passe quelque chose de périodique la nuit, suffisante pour obtenir un examen réel. Et n'ajuste jamais une dose sur ce chiffre.
