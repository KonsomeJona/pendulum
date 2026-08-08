# Pendulum — Spécification v2 (carnet de décisions)

Ce document **remplace `SPEC-v1.md` partout où les deux se contredisent**. Il ne réécrit pas v1 : il enregistre ce qui a changé, pourquoi, et où lire le détail. `SPEC-v1.md` reste au dépôt comme trace de l'état initial.

## Index des documents

| Document | Contenu | Autorité |
|---|---|---|
| `SPEC-v1.md` | Plan initial (recherche d'antériorité, structure des modules, phases) | Historique. Ne plus s'y référer sans passer par ce fichier. |
| `REVUE-CRITIQUE.md` | 38 défauts (6 bloquants), ce qui manque, 9 mécanismes d'auto-tromperie | Fait foi sur les défauts |
| `ALGO-v2.md` | Chaîne DSP v2, API du module `algo`, générateur synthétique, tableau des paramètres | Fait foi sur l'algorithme |
| `ARCHI-CAPTURE.md` | Capture montre, transfert, protocole, manifeste, cycle de vie | Fait foi sur `wear` et le transfert |
| `SOURCES-SOMMEIL.md` | Choix de la source d'hypnogramme, Health Connect, vérification préalable | Fait foi sur `phone/health` |
| `UX.md` | Principes, écrans, textes, data-viz, système visuel, Compose | Fait foi sur l'interface |

**Lire le §5 en premier.** Il change la métrique principale du produit, après coup, sur la base de résultats extraits de PubMed que les cinq relectures n'avaient pas. Tout ce qui est écrit ailleurs en supposant que la métrique est le compte horaire doit être relu à sa lumière — en particulier `UX.md` §3 et §4.2, dont la logique reste valable mais dont la grandeur tracée change.

---

## 1. Ce qui a été demandé, ce qui a été décidé

| Demande | Décision | Détail |
|---|---|---|
| Graphe de mouvement envoyé **en temps réel ou en batch** pour ne rien perdre | **Hybride.** Le brut part par chunks de 5 min via `DataClient` (répliqué et persistant), une salve toutes les 15 min → perte bornée à ~20 min au lieu de 8 h. Une enveloppe RMS 1 Hz (900 o) voyage dans la même salve : c'est le graphe « temps réel », à coût radio nul. `ChannelClient` n'est plus que le rattrapage. | `ARCHI-CAPTURE.md` §1-2 |
| Capture par **service background** plutôt qu'app ouverte | **Confirmé**, avec la précision qui compte : c'est un *foreground service* `type="health"` (pas de timeout Android 15, contrairement à `dataSync`), plus un chien de garde WorkManager de 15 min, parce que `START_STICKY` ne suffit pas face au low-memory killer. | `ARCHI-CAPTURE.md` §3 |
| App montre **très simple, juste le tracking** | **Accepté.** Un seul écran statique, aucune animation, aucun chiffre de résultat sur la montre. Deux ajouts non négociables : START refusé tant que le formulaire du soir n'est pas scellé, STOP par appui long confirmé. | `ARCHI-CAPTURE.md` §5, `UX.md` §6 |
| Données de sommeil (**deep, REM**) depuis une autre app via Google Health | **Accepté pour le temps de sommeil total, refusé pour l'usage par stade.** Les montres grand public estiment bien le TST (sensibilité ≈ 0,95) et mal les stades (kappa 0,34–0,47). Le TST est justement tout ce dont le PLMI a besoin. Un PLMI par stade ne sera jamais présenté autrement que comme exploratoire. | `SOURCES-SOMMEIL.md` §1, §3 |
| **Beau, facile à utiliser et à comprendre** | **Accepté, avec une inversion de l'objectif** : l'interface a pour métier de rendre l'incertitude *lisible*, pas de la compresser en un grand chiffre coloré. Un chiffre net et faux est le mode de défaillance principal de ce projet. | `UX.md` §1, §3 |

---

## 2. Corrections majeures apportées à v1

### 2.1 Capteur : accéléromètre, pas gyroscope

Le gyroscope ne sert à rien ici et coûte, selon le mode, **3 à 40 fois** le courant de l'accéléromètre seul (BMI270 : 210 µA contre 685 µA en mode normal ; 10 µA contre 420 µA en basse consommation à 25 Hz). Un mouvement périodique de cheville de 0,5 à 10 s est entièrement décrit par l'accélération. Le vrai critère de choix d'un appareil n'est pas « a-t-il un gyroscope » mais **« une app tierce peut-elle lire le capteur brut »** — ce que ni Fitbit, ni Oura, ni Whoop, ni Garmin ne permettent, et ce qu'une Wear OS permet. (`SOURCES-SOMMEIL.md` §2)

### 2.2 Transport : `DataClient`, pas `ChannelClient`

v1 écartait `MessageClient` sur son plafond de 100 Ko et retenait `ChannelClient` — **sans considérer `DataClient`**, qui a le même plafond mais la sémantique d'un magasin répliqué et persistant. En taillant le chunk à 5 min (≈ 91 Ko), il tient dans un `DataItem`. Un chunk poussé à 2 h est déjà chez le téléphone : que la montre meure à 3 h ne le concerne plus. C'est cette omission qui rendait le batch au réveil « inévitable ».

Coût mesuré en ordre de grandeur : synchro incrémentale **1 à 3,6 % de batterie** sur la nuit, contre ~65 % pour un wake lock partiel tenu 8 h. Le débat streaming/batch portait sur 20 à 60 fois moins d'énergie que le vrai poste de dépense.

### 2.3 Le dénominateur du PLMI n'est plus dérivé du signal qu'on compte

Défaut bloquant F-04 : en l'absence de Health Connect, v1 estimait les heures de sommeil à partir du **même** accéléromètre qui fournit les mouvements. Le traitement réduit les mouvements → le numérateur baisse **et** le dénominateur grossit, dans le même geste. Un effet nul peut s'afficher comme une amélioration franche.

Règles v2, appliquées au niveau du DAO et pas de l'interface :
- `maskSource = ACCEL_MACRO` **interdit** pour le résultat principal ;
- numérateur et dénominateur toujours stockés et affichés séparément ;
- second index normalisé par le **temps au lit** (indépendant de l'algorithme) calculé systématiquement ; si les deux index divergent en direction, aucune conclusion n'est affichée ;
- dénominateur = temps de sommeil **analysable** (époques de sommeil ∩ époques réellement couvertes par des blocs valides), jamais le TST brut.

*Conflit tranché ici* : `SOURCES-SOMMEIL.md` §4 conclut que l'absence de Health Connect n'est « pas bloquante » puisqu'un masque accéléro reste calculable. C'est exact pour **faire tourner** l'app, faux pour **produire un chiffre comparable d'une nuit à l'autre**. Les deux positions cohabitent ainsi : le masque accéléro reste calculé et affiché comme second bras, mais il ne peut jamais porter le résultat principal ni alimenter la tendance.

### 2.4 Masque de sommeil : van Hees, pas Cole-Kripke

Cole-Kripke est calé sur des *activity counts* du **poignet** en époques d'une minute, avec un capteur (Motionlogger AMI, mode passage par zéro) qui n'a plus d'équivalent — il n'existe aucune conversion publiée de g bruts vers des counts, et les implémentations open source ne s'accordent ni sur le prétraitement ni sur les coefficients. À la cheville il surestime le TST de +43 min, ce qui **déflate** le PLMI : un PLMI vrai de 15,0 s'affiche à 13,6, sous le seuil de dépistage. Le choix de l'algorithme de masque déplace le PLMI de ~36 % — **davantage que toute erreur du détecteur lui-même**. C'est le premier poste du budget d'erreur, à traiter avant d'optimiser le seuil de détection.

**Et le piège qui disqualifie une implémentation naïve** : la règle van Hees retenue exige ≥ 5 min consécutives sans mouvement. Une série de mouvements à 22 s d'intervalle place ~13 mouvements dans toute fenêtre de 5 min — la probabilité qu'une fenêtre soit libre pendant une série est **nulle**. Appliqué tel quel, le masque score donc toute la période de série comme de l'éveil : le TST s'effondre, le PLMI explose, et simultanément la règle AASM « au moins une partie du mouvement dans une époque de sommeil » **supprime les mouvements eux-mêmes**. Le sujet le plus atteint est celui pour qui l'algorithme se comporte le plus mal. Réponse retenue : le masque est construit **aveugle aux mouvements périodiques** (seuls les mouvements corporels grossiers et les changements de posture comptent comme preuve d'éveil), puis un point fixe borné à deux itérations. (`ALGO-v2.md` §3.6)

### 2.5 Chaîne DSP durcie

Retenu : passe-bande **0,5–8 Hz** (le coude bas écarte la respiration à 0,2–0,33 Hz, le coude haut gagne ~5 dB de rapport signal/bruit), enveloppe grossière **0,5 s** au lieu de 0,15 s, plancher de bruit par **percentile bas sur 120 s avec exclusion itérative** au lieu d'une médiane 25 s auto-contaminée, et surtout un **plancher absolu de 0,020 g** sans lequel le seuil relatif devient hypersensible dans le silence.

Rejeté après examen : **TKEO** (amplifie le bruit haute fréquence pour un gain de datation de ~69 ms, sans valeur devant une granularité clinique de 500 ms) et la fenêtre causale décalée (biais de 35 s qui peut fabriquer ou détruire une série entière ; on analyse en différé, pas en flux).

Deux erreurs de v1 corrigées au passage, qu'aucun relecteur n'avait vues :

- **La fenêtre RMS de 0,15 s était un bug, pas un paramètre.** Elle vient de S-PLMAD, qui traite de l'EMG à 512 Hz dans une bande 10–300 Hz. Sur un signal utile à 1–5 Hz, 0,15 s ne moyenne même pas une demi-période à 2 Hz : l'enveloppe ondule, le comparateur bat autour du seuil et **fragmente un mouvement unique en plusieurs**. Portée à 0,50 s.
- **L'accéléromètre ne mesure pas la même grandeur que l'EMG.** 39 % des mouvements scorés à l'EMG ne s'accompagnent d'**aucun** mouvement détectable en accélérométrie (Terrill 2013) — une dorsiflexion pure ne déplace pas un capteur situé au-dessus de l'axe articulaire. Ce n'est pas du bruit : c'est une **échelle différente**. Conséquence : le seuil de 15/h n'est pas transposable, et la vérité terrain synthétique porte deux jeux d'étiquettes (`emgTruth` / `accelTruth`) — sans quoi le critère « F1 ≥ 0,90 » de v1 était inatteignable pour une raison qui n'est pas la faute de l'algorithme.

Corrigé aussi : v1 supposait un contenu spectral de 10–15 Hz pour un CLM — **aucune analyse spectrale de PLMS accélérométrique n'est publiée**, et les seules bornes défendables sont la consigne WASM 2006 (linéarité du capteur sur 0,8–14 Hz) et les 5–8 Hz du clonus de cheville. Tout chiffre au-delà est une inférence, pas une citation.

### 2.6 Les deux jeux de règles cliniques diffèrent enfin vraiment

v1 croyait que AASM v3 et WASM 2016 ne se distinguaient que par la borne basse de l'intervalle (5 s contre 10 s). C'est faux : la différence structurante est la **règle de rupture de série** (WASM casse la série sur un intervalle court ou sur un mouvement long ; AASM est muette). Sans cette règle, « implémenter les deux jeux » ne produisait que deux fois le même chiffre à une borne près.

### 2.7 Format binaire

- `ChunkFormat.toRaw` **ne compilait pas** (`Math.round(Double)` renvoie un `Long`, et `Long.coerceIn(Int, Int)` n'existe pas). **Corrigé**, avec garde sur les valeurs non finies.
- Le CRC ne couvre que le payload : `count`, `tFirstNs`, `tLastNs`, `flags` ne sont protégés par rien. Une base de temps corrompue décale toute la nuit **sans détection**. À corriger : CRC sur `blockHeader[0..24)` ‖ payload, et resynchronisation sur `BLK!` au lieu d'un `continue` aveugle.
- Un bloc de 512 échantillons (10,24 s) peut chevaucher deux vidages FIFO séparés par un suspend : l'interpolation linéaire date alors **tous** ses échantillons faux. À interdire à l'écriture.
- L'entête fait **80 octets** (le code a raison, la spec v1 se trompait : sa propre liste de champs sommait déjà à 68 o hors réservé).
- Les 12 octets réservés accueillent le `zoneId` IANA et l'offset. Règle absolue : **toute durée se calcule sur `SensorEvent.timestamp` ou `elapsedRealtime`, jamais par différence d'horloge murale** — cela couvre le changement d'heure et, plus fréquent, une resynchronisation NTP en pleine nuit.

---

## 3. Les garde-fous anti-auto-tromperie (à coder, pas à afficher)

Ce projet produit un nombre qui influencera une décision de dosage. Les six garde-fous non négociables :

1. **La dose du soir et le contexte sont scellés avant que la montre n'accepte de démarrer.** Enregistrement append-only, non modifiable.
2. **Résultat masqué par défaut au réveil** : « nuit enregistrée, qualité OK » et rien d'autre. Le dévoilement est journalisé et exporté.
3. **Aucun réglage par nuit.** Un changement de paramètre est global, bump du `paramsHash`, et déclenche un rescore de **toutes** les nuits depuis le brut. La tendance refuse de mélanger deux hashs.
4. **Aucun bouton « exclure cette nuit ».** Les exclusions sont des prédicats déterministes évalués avant le calcul (vue SQL `comparable_night` : même jambe, même bracelet, seul dans le lit, gain de calibration dans la tolérance, ≥ 4 h analysables). Les nuits écartées restent visibles, grisées, avec leur motif.
5. **`MDC95` (plus petite variation détectable) calculé et tracé en bande.** L'interface n'affiche jamais l'écart entre deux nuits ; sous ce seuil, elle écrit « variation indiscernable de la variabilité nuit à nuit ». Aucun verbe d'évolution dans les ressources de chaînes — vérifiable par un test unitaire sur le fichier de textes.
6. **La métrique est renommée** dans le code et l'export : `aPLM-i`, « index de mouvements périodiques de cheville, estimé, non validé ». Appeler « PLMI » un chiffre produit par un bracelet de montre sur une cheville garantit qu'il sera lu comme un PLMI de laboratoire.

### Le cadrage honnête, à dire une fois

L'utilisateur suit **déjà un traitement**. Sans période de référence sans traitement, ce dispositif **ne peut pas mesurer l'effet du traitement** — il mesure la variabilité sous traitement, et éventuellement l'effet d'un changement de dose si ce changement est décidé par le médecin, appliqué par blocs longs, et déclaré à l'avance. Le seul objectif défendable de ce projet est **d'obtenir un examen réel**, pas de le remplacer. Aucune dose ne s'ajuste sur ce chiffre.

---

## 4. Ordre de travail révisé

Les critères de phase de v1 étaient partiellement incohérents (P1 : « ≥ 97 % » et « cumul < 2 min » ne sont pas le même seuil ; « plus grand trou < 5 s » est incompatible avec une latence de batch de 60 s). Version corrigée :

| Phase | Contenu | Critère de sortie |
|---|---|---|
| **P0** | Squelette 4 modules, `format` qui compile et dont les tests passent | `./gradlew :format:test` vert, 2 APK installés |
| **P1 — BLOQUANTE** | Spike capteur seul, 3 à 5 nuits. Aucune ligne d'algorithme avant. | Couverture ≥ 99 % des échantillons attendus **mesurée sur les écarts de `SensorEvent.timestamp`** (jamais sur l'horloge d'arrivée, sinon en mode batché la règle se déclenche toujours) ; batterie > 20 % à 8 h ; reproductible 3 nuits. Échec ⇒ envisager sérieusement un capteur dédié (Axivity AX3, GENEActiv) plutôt que s'acharner. |
| **P2** | Durcissement du format : CRC étendu, interdiction du chevauchement de flush, fuseau, marqueur de fin, resynchronisation | Kill du process en pleine nuit ⇒ session reprise, perte ≤ 1 bloc (10 s), pas ≤ 1 chunk |
| **P3** | Transfert `DataClient` incrémental + rattrapage `ChannelClient` | Nuit complète reçue avec Bluetooth coupé au milieu ; montre tuée à 3 h ⇒ tout ce qui a été poussé est intact et la nuit est marquée tronquée |
| **P4** | Algorithme en TDD sur signal synthétique | Les 17 assertions de non-régression de `ALGO-v2.md` §5.5 |
| **P5** | Masque de sommeil + Health Connect + Room + WorkManager | Kappa masque/HC rapporté ; nuit réelle produisant les résultats des deux jeux de règles |
| **P6** | Interface téléphone, questionnaires, export | Parcours à froid nuit → chargeur → résultat, sans intervention |
| **P7** | Campagne 7 nuits + protocole de mouvements volontaires | Bande de MDC95, courbe de sensibilité paramétrique |

**Avant P5, et idéalement avant P0** : exécuter la procédure de vérification de `SOURCES-SOMMEIL.md` §5 pour confirmer que la Galaxy Watch 5 écrit bien des *stades* (et pas seulement une durée) dans Health Connect, et à quel délai après le réveil. Tout le dénominateur repose là-dessus.

---

## 5. La métrique principale change : périodicité plutôt que compte horaire

*Ajouté le 29/07/2026 après interrogation directe de PubMed. C'est la décision la plus lourde de ce document et elle arrive après coup — les cinq relectures avaient toutes travaillé en supposant que la métrique était le compte horaire.*

### 5.1 Les trois résultats qui l'imposent

| Source | Résultat |
|---|---|
| Skeba, Hiranniramol, Earley, Allen — *Sleep Med* 2016;17:138-43 (PMID 26847989) · 29 SJSR non traités + 22 témoins, 2 nuits consécutives | Variabilité nuit à nuit, en % de la moyenne des deux nuits : **moyenne du log IMI = 3,6 % ± 3,7** contre **PLMS/h = 43,2 % ± 37,1** (p < 0,001). L'IMI suit une loi log-normale. La variabilité du log IMI est aussi meilleure que celle du Periodicity Index. |
| Ferri et al. — *Sleep Med* 2013;14(3):293-6 (PMID 23068780) | Le Periodicity Index varie **plus de 6,5 fois moins** que le PLMS index chez les SJSR (2 fois chez les PLMD). |
| Ferri et al. — *Sleep Med* 2016;17:32-8 (PMID 26922620) · 107 SJSR + 48 témoins | Seuils diagnostiques optimaux : **15-16/h** (index standard), **~13/h** (index alternatif), **~0,5** (Periodicity Index), aires sous ROC similaires. La périodicité a donc son propre seuil publié. |

À quoi s'ajoute une revue de 2026 signée Ferri lui-même (*Sleep*, PMID 42213077) dont la thèse est que « périodicité, agrégation en salves, dépendance au stade et couplage autonomique portent plus d'information clinique que les seuls comptes d'événements ».

### 5.2 Pourquoi cela résout trois problèmes d'un coup

1. **Douze fois moins de variabilité nuit à nuit.** C'est le problème que `UX.md` traitait en refusant de conclure sous trois nuits — traitement correct pour le compte, mais qui reste un pansement.
2. **Ni le log IMI ni le Periodicity Index n'ont besoin d'un dénominateur.** Ils se calculent sur les seuls instants d'apparition des mouvements. **La circularité décrite en §2.3 disparaît pour la métrique de suivi**, et Health Connect redevient facultatif pour elle (il reste nécessaire au compte horaire du rapport médical).
3. **Le seuil de 15/h n'était de toute façon pas transposable** à une mesure accélérométrique (§2.5, Terrill : 39 % des mouvements EMG sont mécaniquement invisibles). Le compte accélérométrique est sur une autre échelle ; la périodicité, elle, est une propriété du rythme, pas de l'amplitude.

### 5.3 Ce qui ne se transpose pas — et qu'il faut coder

**Un taux de manqués biaise la moyenne du log IMI, fortement.** Si les manqués sont indépendants avec une probabilité `p`, un intervalle observé recouvre `N` intervalles vrais, `N` géométrique, et le biais vaut `E[ln N] = Σ p^(k−1)(1−p)·ln k` :

| `p` | Biais sur la moyenne du log IMI | Effet sur l'intervalle |
|---|---|---|
| 0,20 | +0,158 nats | ×1,17 |
| 0,30 | +0,255 nats | ×1,29 |
| **0,39** (Terrill, manqués mécaniques) | **+0,357 nats** | **×1,43** |
| 0,50 | +0,508 nats | ×1,66 |
| **0,70** (39 % mécaniques **et** alternance gauche/droite) | **+0,915 nats** | **×2,50** | ← corrigé le 2026-08-05 : +0,901 correspondait à une somme arrêtée vers k = 15, la série converge lentement à p = 0,70

À comparer à la variabilité nuit à nuit publiée : 3,6 % de la moyenne du log IMI, soit ≈ 0,11 nats pour un IMI de 21 s. **Le biais à 39 % vaut 3,3 fois cette variabilité.** La moyenne brute du log IMI est donc inutilisable telle quelle.

**Le problème de latéralité est le plus sérieux, et aucune des cinq relectures ne l'avait vu sous cet angle.** Les PLMS peuvent être bilatéraux simultanés, alternants, ou unilatéraux. Un capteur sur une seule jambe voit, en cas d'alternance parfaite, un intervalle exactement doublé — un harmonique pur, pas du bruit. Et si la latéralité varie d'une nuit à l'autre, la stabilité même qui justifie le changement de métrique s'effondre. C'est une hypothèse ouverte : je n'ai pas trouvé de donnée sur la stabilité intra-sujet de la symétrie gauche/droite.

**La réponse : un modèle de mélange sur les harmoniques.** On modélise la distribution observée comme `log I_obs ~ Σ w_k · N(μ + ln k, σ²)` avec `w_k = p^(k−1)(1−p)`, et on estime `(μ, σ, p)` par espérance-maximisation. Les centroïdes sont séparés de `ln 2 ≈ 0,69` nats pour un `σ` typique de 0,2 à 0,4 : la déconvolution est bien posée même à `p = 0,39`, où le pic fondamental pèse encore 61 % et le premier harmonique 24 %.

Trois bénéfices, dont deux non recherchés :
- `μ` est la **période fondamentale**, débarrassée du taux de manqués ;
- `p` est estimé, donc **mesuré** : c'est une métrique de qualité gratuite, et un `p` qui saute d'une nuit à l'autre est exactement le drapeau de non-comparabilité que §3 réclamait ;
- un `p` proche de 0,5 avec un pic fondamental faible **signe une alternance gauche/droite** et devient un résultat clinique en soi.

Réserve honnête, formulée par le second avis et retenue : l'hypothèse d'indépendance des manqués est probablement fausse. L'accéléromètre rate d'abord les mouvements de faible amplitude ; si une salve décroît en amplitude, les manqués s'agglomèrent en fin de série et le pic 2× sera sous-peuplé par rapport au modèle géométrique. À simuler dans le générateur synthétique (`ALGO-v2.md` §5) avant de croire l'estimation de `p`.

### 5.4 Décision d'affichage

Le compte horaire **reste**, pour une raison qui n'est pas technique : c'est la langue des somnologues, et les seuils internationaux reposent dessus. Mais il change de rôle.

| Usage | Métrique | Justification |
|---|---|---|
| **Suivi nuit après nuit** (le graphe de tendance, l'écran principal) | Période fondamentale `μ` en secondes, et Periodicity Index | Stable, sans dénominateur, sans seuil transposé abusivement |
| **Rapport pour le médecin** (export) | Compte horaire, avec son dénominateur explicite, ses barres d'erreur et le taux de manqués estimé | C'est ce qu'un somnologue sait lire |
| **Écran de résultat** | « Rythme fondamental : 22 secondes · périodicité élevée », le compte en second rang | Un chiffre stable devant, un chiffre familier derrière |

Ce qu'il ne faut pas faire : afficher « 0,58 » nu. La périodicité se présente comme **un rythme en secondes** — un intervalle est intuitif, un indice sans unité ne l'est pas.

---

## 6. Questions ouvertes qui appartiennent à l'utilisateur

1. **Le cadrage médical avant la première nuit** : demander au médecin du sommeil ce qu'il attend d'une mesure à domicile, et s'il existe une période de référence sans traitement exploitable.
2. **La compilation du module `format`** : la correction de `toRaw` est faite mais n'a pas pu être vérifiée (pas de cache Gradle ni de réseau sur cette machine). Un `./gradlew :format:test` reste à passer.
3. **Le comportement du FIFO sur la Pixel Watch 3 précise** : lire `fifoReservedEventCount` (le seul garanti — `fifoMaxEventCount` est partagé entre applications) au démarrage, avant de choisir la stratégie.
4. **La licence du Cambridge-Hopkins CH-RLSq** avant intégration, même en usage personnel. L'échelle de sévérité IRLS reste exclue (copyright IRLSSG).
5. **Le brevet US 10 335 085** (Johns Hopkins, R. P. Allen) mentionne l'accéléromètre maintenu sur la jambe par un bracelet. Sans conséquence en usage strictement personnel, à lire avant toute diffusion.
6. **La stabilité de ta latéralité** (§5.3). Si tes mouvements alternent entre les jambes, un capteur unilatéral voit un intervalle doublé ; si la latéralité change d'une nuit à l'autre, la stabilité qui justifie tout le §5 s'effondre. Aucune donnée trouvée sur la stabilité intra-sujet de la symétrie gauche/droite. Deux nuits avec la montre sur la jambe opposée trancheraient la question à peu de frais — et le taux de manqués estimé par le modèle de mélange le dira aussi, à condition de ne pas le croire aveuglément.
