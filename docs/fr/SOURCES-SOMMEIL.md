# Pendulum — Guide de choix de la source de données de sommeil

**Objet.** Pendulum compte des mouvements de jambe avec une Pixel Watch 3 à la cheville. Pour transformer ce comptage en **PLM Index (mouvements par heure de sommeil)**, il faut un dénominateur : le temps de sommeil total (TST), et si possible l'hypnogramme. Ce dénominateur doit venir d'un **autre** appareil, lu via **Health Connect** sur le téléphone Android. Ce document dit lequel choisir, pourquoi, et comment vérifier avant d'écrire une ligne de code.

**Date de rédaction : 29 juillet 2026.**

### Méthode et limites de cette vérification

Le quota de recherche web de la session était épuisé. Toutes les vérifications ont donc été faites par **récupération directe de pages primaires** (documentation Android/AndroidX, code source androidx, pages support constructeurs, article scientifique) et par un **moteur de recherche utilisé comme proxy** (DuckDuckGo lite) pour localiser les URL. Conséquence : les points appuyés sur une page primaire récupérée en entier sont **solides** ; ceux appuyés uniquement sur un extrait de résultat de recherche sont marqués **« non vérifié »** et il y en a plusieurs. La section 7 les liste tous. Ne construis rien de critique sur une ligne marquée « non vérifié » sans la contrôler toi-même.

---

## 1. Pourquoi Pendulum a besoin de deux capteurs distincts

### Le problème est une division

Le PLMI est une fraction :

```
PLMI = (nombre de PLMS)  /  (heures de SOMMEIL)
```

La montre de cheville produit très bien le **numérateur**. Elle ne peut pas produire honnêtement le **dénominateur**. Et c'est un problème plus profond qu'un simple manque de précision.

### Pourquoi la montre de cheville ne peut pas faire les deux

**a) La circularité.** L'actigraphie classique déduit « endormi / éveillé » d'une statistique de mouvement : beaucoup de mouvement = éveil, peu de mouvement = sommeil. Or l'événement que Pendulum cherche à compter **est** un mouvement. Si on utilise le même signal pour le numérateur et le dénominateur, chaque salve de PLMS pousse l'algorithme à déclarer « éveil ». Résultat : le temps de sommeil rétrécit **exactement pendant les périodes où il y a le plus de mouvements à compter**. Le numérateur monte, le dénominateur descend, et l'erreur sur le rapport est doublée dans la même direction. C'est un biais systématique, pas du bruit — on ne peut pas le moyenner sur plusieurs nuits.

**b) Les algorithmes de sommeil sont calibrés pour le poignet.** Les règles de type Cole-Kripke, et les modèles des montres grand public, ont été ajustés sur des statistiques de mouvement de **poignet**. La cheville d'un dormeur bouge autrement : elle est plus souvent parfaitement immobile, puis produit des accélérations bien plus fortes (le SPEC note lui-même des amplitudes ×2 à ×3 par jeu de bracelet, et ×2 à ×2,3 d'amplification par la literie). Un seuil calibré au poignet appliqué à la cheville donne un résultat sans signification.

**c) Les stades ne se lisent pas dans le mouvement seul.** Les montres grand public classent léger / profond / REM en combinant mouvement **et** fréquence cardiaque / variabilité cardiaque, mesurées au PPG. Le PPG à la cheville est peu fiable, et le SPEC prévoit déjà que la détection « porté / non porté » (off-body, elle aussi basée sur le PPG) est un risque à valider en phase 1. Sans signal cardiaque exploitable, aucun stade.

**d) La montre de cheville est déjà occupée.** Elle enregistre l'accéléromètre brut à 50 Hz toute la nuit via `SensorManager`, avec un budget batterie serré. Lui demander en plus de faire tourner un modèle de staging n'est ni possible (Health Services ne donne pas l'accéléro brut, et `ExerciseClient` est explicitement proscrit par le SPEC) ni souhaitable.

Le SPEC prévoit tout de même un masque de secours `deriveImmobilityMask()` calculé sur l'accéléro de cheville. **C'est un filet de sécurité, pas la source principale.** Il existe pour qu'une nuit reste analysable immédiatement si Health Connect ne répond pas — et c'est précisément pour ça que le PLMI est calculé deux fois (masque accéléro / masque Health Connect) et que les deux sont rapportés. L'écart entre les deux est en soi une information.

### Ce que les stades apportent réellement — et ce qu'ils n'apportent pas

**Ce qu'ils n'apportent pas : rien sur le chiffre du PLMI lui-même.** La définition AASM du PLMI est un simple comptage divisé par des heures de sommeil, sans pondération par stade. Un masque **binaire** « endormi / éveillé » suffit à calculer un PLMI correct. Passer de binaire à quatre stades ne rend pas l'index plus juste d'un pouième.

**Ce qu'ils apportent vraiment, et c'est loin d'être nul :**

1. **Séparer PLMS et PLMW.** Les mouvements en éveil ne comptent pas dans le PLMI et doivent être rapportés à part (piège n°7 du SPEC ; un PLMW élevé est lui-même un indice de SJSR). Mais un masque binaire fait déjà ça.
2. **Le contrôle de plausibilité biologique.** C'est l'apport principal, et il correspond au niveau 5 de vérification du SPEC. Les PLMS sont censés se concentrer en N1/N2 et se raréfier en REM (atonie musculaire du sommeil paradoxal). Si ta distribution de CLM par stade est plate, ou pire, dense en REM, ton détecteur attrape probablement autre chose que des PLMS. **Sans les stades, tu n'as aucun moyen de détecter cette erreur.** C'est un test de non-régression sur le monde réel, là où le synthétique ne peut rien dire.
3. **Le kappa masque-accéléro contre hypnogramme**, prévu au SPEC. Il te dit à quel point ton filet de sécurité est fiable, donc à quel point tu peux faire confiance aux nuits où Health Connect n'a rien renvoyé.
4. **La lecture par un médecin du sommeil.** Un rapport qui montre un hypnogramme avec les CLM superposés se discute ; un rapport qui montre juste « PLMI = 22 » ne se discute pas.

### La bonne nouvelle, et elle est solide

Ce dont Pendulum a **besoin** (le TST, donc la frontière sommeil/éveil) est précisément ce que les montres grand public font le **mieux**. Ce qu'elles font **mal** (les stades) est ce dont Pendulum a le **moins** besoin.

Validation Galaxy Watch 3 contre polysomnographie, N = 32 (Kim D, Joo EY, Choi SJ, *J Sleep Med* 2023;20(1):28-34, [doi:10.13078/jsm.230004](https://doi.org/10.13078/jsm.230004)) :

| Mesure | Résultat |
|---|---|
| Sensibilité de détection du sommeil | **0,954** |
| Spécificité (détection de l'éveil) | 0,524 |
| Précision de classification à 4 stades | 0,651 |
| Kappa de Cohen : éveil / léger / profond / REM | 0,47 / 0,34 / 0,34 / 0,39 |
| Biais TST (montre − PSG) | **+9,5 min** |
| Biais léger / profond / REM | −28,6 / +11,6 / +26,3 min |
| ICC REM | 0,153 (très mauvais) |

Lecture : le TST est excellent (biais +9,5 min sur une nuit de ~7 h, soit ~2 %), les stades sont médiocres (kappa 0,34–0,47 = accord faible à modéré). Ce résultat est cohérent avec Chinoy et al., *Sleep* 2021;44(5):zsaa291 ([academic.oup.com](https://academic.oup.com/sleep/article/44/5/zsaa291/6055610)), qui conclut sur sept appareils grand public que la détection du sommeil est bonne mais que « device sleep stage assessments were inconsistent ».

**Le biais qu'il faut connaître.** La spécificité de 0,524 signifie que la montre déclare « endormi » près d'une époque d'éveil sur deux. Elle **surestime** donc le temps de sommeil. Comme le TST est au dénominateur, ton PLMI est structurellement **sous-estimé** par cette source. Ça s'oppose au biais RRLM à la hausse décrit dans le SPEC (surestimation en cas d'apnée) — et, comme pour la mesure unilatérale, **il ne faut pas prétendre que ces biais se compensent**. Il faut les nommer séparément dans le rapport.

Attention : cette étude porte sur la **Galaxy Watch 3**, pas la 5. Extrapoler à la GW5 est raisonnable (même famille d'algorithmes Samsung) mais reste une extrapolation non vérifiée.

---

## 2. Correction de deux confusions

### 2.1 « Une montre avec gyroscope » — non, c'est l'accéléromètre qui travaille

**Ce que mesure un accéléromètre :** l'accélération linéaire, en m/s², sur trois axes — plus la gravité en permanence. Il répond à la question « à quelle vitesse ce point se met-il en mouvement, et dans quel sens penche-t-il ? ».

**Ce que mesure un gyroscope :** la vitesse **angulaire**, en degrés par seconde. Il répond à « à quelle vitesse cet objet tourne-t-il sur lui-même ? ».

Un PLMS est typiquement une dorsiflexion de la cheville et une extension du gros orteil, d'une durée de 0,5 à 10 s. Vu de la cheville, c'est une **salve d'accélération** : le pied part, s'arrête, revient. L'accéléromètre l'enregistre directement. C'est exactement le montage validé par Spektor et al. 2024 (*Clocks & Sleep*), cité en fondation du SPEC : un accéléromètre **tri-axial** unilatéral à la cheville, r = 0,98 contre polysomnographie. Aucun gyroscope dans l'histoire.

Le gyroscope ajouterait la composante rotationnelle du geste. Et c'est justement redondant ici, pour deux raisons :

1. Le pipeline du SPEC calcule `magnitudeL2(x, y, z)`, c'est-à-dire la norme du vecteur accélération, qui est **invariante à l'orientation du bracelet**. On a délibérément jeté l'information directionnelle parce qu'elle dépend de comment la montre est sanglée, pas du mouvement. Le gyroscope apporterait de l'information dans un espace qu'on écarte de toute façon.
2. La détection repose sur une **enveloppe RMS** comparée à un plancher de bruit adaptatif. C'est une mesure d'énergie. Un axe de mesure supplémentaire n'améliore pas la détection d'un événement énergétique déjà bien au-dessus du plancher.

**Le coût en batterie, chiffré.** Bosch BMI270 (IMU explicitement destinée aux montres et bracelets), datasheet BST-BMI270-DS000-08, table de consommation :

| Mode | Courant typique |
|---|---|
| Accéléromètre + gyroscope, *performance*, ODR max | 970 µA |
| Accéléromètre + gyroscope, *normal*, ODR max | 685 µA |
| Accéléromètre + gyroscope, *low power*, 25 Hz | 420 µA |
| **Accéléromètre seul, *normal*, ODR max** | **210 µA** |
| **Accéléromètre seul, *low power*, 25 Hz** | **10 µA** |
| Suspend | 3,5 µA |

Sources : [page produit BMI270](https://www.bosch-sensortec.com/products/motion-sensors/imus/bmi270/) (confirme les 685 µA « in full ODR and aliasing free operation ») et le PDF de la datasheet. **Réserve d'honnêteté :** l'alignement colonnes/valeurs a été reconstruit depuis une extraction texte du PDF ; l'ordre des six valeurs est cohérent et corroboré par la page produit pour la valeur 685 µA, mais vérifie la table dans un lecteur PDF si tu veux citer ces chiffres ailleurs.

Ordres de grandeur à retenir : à débit maximal, allumer le gyroscope **triple** la consommation du capteur (210 → 685 µA). En mode basse consommation à 25 Hz, il la multiplie par **~40** (10 → 420 µA). Sur une nuit de 8 h avec un objectif « batterie > 20 % au réveil » (critère bloquant P1 du SPEC), c'est la différence entre un plan qui tient et un plan qui ne tient pas.

Ces chiffres concernent le **die du capteur**, pas la montre entière — sur une Pixel Watch 3, la consommation du SoC réveillé, du wake lock et de l'écriture disque dominent probablement. Mais la conclusion ne bouge pas : **le gyroscope coûte cher et n'apporte rien ici. Ne l'active jamais.**

Ce qui compte réellement pour choisir une montre de cheville n'est donc pas « a-t-elle un gyroscope » mais : **peut-on lire son accéléromètre brut, à 50 Hz, pendant 8 h, depuis une app tierce ?**

### 2.2 Capteur brut accessible vs données agrégées de l'écosystème — le vrai critère

C'est la distinction la plus utile de tout ce document.

**Donnée brute.** Une app tierce appelle `SensorManager.getDefaultSensor(TYPE_ACCELEROMETER)` et reçoit des échantillons `(x, y, z, timestamp)` à la fréquence demandée. Elle voit le signal physique. Elle écrit son propre algorithme. Le résultat est reproductible, débogable, et testable contre du synthétique. C'est ce que fait le module `wear` de Pendulum.

**Donnée agrégée.** Le constructeur applique **son** traitement propriétaire et ne publie que le résultat : « activity counts », « score de sommeil », « restlessness », « minutes actives ». Les paramètres ne sont pas documentés, changent avec les mises à jour firmware, et ne sont pas reproductibles.

Le SPEC contient déjà la preuve par l'échec : les tentatives publiées avec l'ActiGraph GT3X donnent « very low similarity with PSG » **non pas** parce que le capteur est mauvais, mais parce qu'on n'avait accès qu'à des *activity counts* propriétaires agrégés à une fréquence trop basse. Le capteur était bon ; l'accès était mauvais.

**Et voici le retournement, qui est tout l'objet de ce document : le critère s'inverse selon le rôle de l'appareil.**

| | Montre de CHEVILLE (numérateur) | Source de SOMMEIL (dénominateur) |
|---|---|---|
| Ce qu'il faut | Accéléromètre **brut**, 50 Hz, 8 h | Un **hypnogramme agrégé** |
| Donc il faut | Une plateforme à API capteur ouverte → **Wear OS** (ou un logger de recherche type Axivity AX3) | N'importe quel écosystème **qui exporte vers Health Connect** |
| Ce qui disqualifie | Bague, bracelet fermé, tout appareil qui ne rend que des scores | Un appareil qui **n'écrit pas** dans Health Connect, ou qui n'y écrit que la durée |
| La qualité du capteur | Critique | **Presque indifférente** — voir §1, le TST est fiable partout |

Autrement dit : pour la cheville, une Oura Ring avec un capteur excellent est **inutilisable** parce que fermée. Pour le sommeil, un appareil au capteur médiocre est **parfaitement acceptable** dès lors qu'il pousse un `SleepSessionRecord` dans Health Connect. Le seul critère qui compte pour le second rôle est celui de la **plomberie**, pas celui du capteur.

---

## 3. Tableau comparatif des sources d'hypnogramme (état 2026)

Légende : `[v]` vérifié sur source primaire · `[?]` probable mais **non vérifié** · `[x]` non / exclu

| Source | Écrit dans Health Connect ? | Stades ou durée seule ? | Délai de synchro | Prix | Abonnement obligatoire ? | Autonomie | Validation publiée |
|---|---|---|---|---|---|---|---|
| **Samsung Health / Galaxy Watch 5** | `[v]` Oui, depuis Samsung Health 6.22.5 (oct. 2022) | `[?]` **Stades** (Awake / Light / REM / Deep) — voir note | Montre → tél. : géré par la **politique batterie de la montre** (non spécifié). Tél. → HC : « as soon as data is created or changed » | Déjà possédée | Non | ~2-3 j (GW5) | `[v]` GW3 : TST +9,5 min, 4 stades 0,651, kappa 0,34-0,47 |
| **Sleep as Android** (app tél., éventuellement + montre) | `[v]` Oui | `[v]` **Phases de sommeil** + FC repos + SpO2 | Écrit à la fin du suivi, au réveil (pas de synchro Bluetooth constructeur à attendre si suivi par le téléphone) | App payante après essai (unverified tarif non vérifié) | `[?]` non vérifié | n/a (téléphone sur secteur) | `[x]` Le mode sonar/micro seul n'a pas de validation de staging connue |
| **Pixel Watch + Google Health** (ex-Fitbit) | `[v]` « third-party connections through Android Health Connect » | `[?]` Stades **non confirmés** sur page officielle | `[?]` non vérifié | Montre déjà possédée — **mais occupée à la cheville** | Non pour le sommeil de base ; Google Health Premium (ex-Fitbit Premium) pour l'analyse avancée | ~24 h (PW3) | Non trouvée |
| **Garmin (Garmin Connect)** | `[v]` Oui, depuis ~juillet 2025 · **sens unique** (Garmin écrit, ne lit pas) | `[?]` Sommeil oui ; **stades non confirmés** | `[?]` non vérifié | 200–1000 € | Non (Connect+ optionnel) | 5-20 j | Non trouvée |
| **Oura Ring** | `[v]` Oui, Android **uniquement**, Gen2/Gen3+ | `[?]` La page support liste Activité / Mensurations / Vitals — **le sommeil et les stades n'y figurent pas explicitement** | `[?]` Piège documenté : « might not import data if both apps were not opened before midnight », Background App Refresh requis | ~350 € + | `[v]` **Oui, membership actif requis** | ~7 j | Non trouvée |
| **Whoop** | `[v]` Oui (Recovery, Strain, Sleep) | `[?]` Stades non confirmés | `[?]` non vérifié | Matériel inclus dans l'abo | `[v]` **Oui, modèle 100 % abonnement** | ~4-5 j | Non trouvée |
| **Polar (Polar Flow)** | `[v]` Oui | `[v]` **« Sleep: start time, end time, stages from Polar sleep phases »** — le seul constructeur à le documenter noir sur blanc | `[?]` « continuously while the connection remains active » | 150–500 € | **Non** | 3-7 j | Non trouvée |
| **Withings (Health Mate)** — ScanWatch, Sleep Analyzer sous matelas | `[v]` Oui (article support existe) | `[?]` Stades non confirmés ; remontées d'utilisateurs de synchro sommeil **incomplète** | `[?]` Sleep Analyzer : sur secteur, synchro Wi-Fi → potentiellement le plus rapide | ScanWatch ~250-350 € · Sleep Analyzer ~130 € | Non | ScanWatch ~30 j · Sleep Analyzer : **secteur, illimité** | Non trouvée |
| **Xiaomi / Amazfit — Zepp** | `[v]` Oui, élargi en janv. 2025 à **26 types**, **sens unique** | `[?]` « sleep stats » ; stades non confirmés | `[?]` non vérifié | 40–300 € | Non | 7-20 j | Non trouvée |
| **Apple Watch** | `[x]` **Exclu** | — | — | — | — | — | — |
| **Sleep Cycle** (app tél. seule) | `[?]` **Non confirmé** | Détecte les stades en interne, mais export HC non vérifié | Au réveil | Freemium | `[?]` non vérifié | n/a | Non trouvée |
| **Google Nest Hub 2 — Sleep Sensing** (radar Soli) | `[x]` **Aucun chemin vers Health Connect trouvé** | — | — | ~100 € (unverified statut produit incertain) | Gratuit à ce jour (bascule vers Premium repoussée plusieurs fois) | Secteur | Non trouvée |

### Notes et pièges par source

**Samsung Health — la nuance sur les stades.** La FAQ développeur officielle ([developer.samsung.com/health/health-connect-faq.html](https://developer.samsung.com/health/health-connect-faq.html)) dit que « Activity data, such as steps and exercise, heart rate, and sleep, are synchronized between Samsung Health and Health Connect » — elle dit **sommeil**, sans préciser « stades ». Le blog développeur Samsung ([Managing Sleep Data with Samsung Health and Health Connect](https://developer.samsung.com/health/blog/en/managing-sleep-data-with-samsung-health-and-health-connect)) travaille explicitement avec les quatre stades **Awake, Light, REM, Deep** et décrit la synchronisation des sessions **et des stades** dans les deux sens. La lecture combinée est fortement en faveur des stades, mais **aucune page ne l'affirme littéralement pour le sens Samsung Health → Health Connect**. C'est exactement pourquoi la section 5 existe, et pourquoi le SPEC lui-même le note en « à vérifier toi-même ».

**Samsung Health — activation obligatoire.** Ce n'est pas automatique. Dans Samsung Health : `Paramètres > Health Connect > Autorisations d'applications`. Puis `Paramètres > Synchroniser avec le compte Samsung > Synchroniser maintenant`.

**Apple Watch — pourquoi c'est exclu, et ce n'est pas un jugement de valeur.** HealthKit est une API iOS. Il n'existe pas d'app Apple Watch pour Android, l'appairage exige un iPhone, et il n'y a aucun pont officiel HealthKit → Health Connect. Même parfaite, la donnée n'atteindrait jamais le téléphone Android. Hors sujet par construction.

**Pixel Watch — le piège d'inventaire.** La Pixel Watch 3 écrit probablement de bonnes données de sommeil dans Health Connect via Google Health. Mais dans Pendulum **elle est à la cheville**. Elle ne peut pas mesurer le sommeil au poignet et compter les mouvements à la cheville la même nuit. Elle est comptabilisée dans le tableau pour mémoire, pas comme option réelle — sauf à acheter une seconde Pixel Watch.

**Le changement Fitbit → Google Health, 2026.** Depuis le **19 mai 2026**, l'app Fitbit est devenue l'app **Google Health** ([support.google.com/googlehealth/answer/17068213](https://support.google.com/googlehealth/answer/17068213)) : quatre onglets Today / Fitness / Sleep / Health, Fitbit Premium devenu Google Health Premium, essai de 3 mois. La page confirme les connexions tierces « through Android Health Connect, Apple Health, and other third-party apps ». Une migration des comptes Fitbit vers des comptes Google a accompagné ce basculement (dates de bascule et de suppression des données non migrées : **non vérifiées**, l'information provenait d'un moteur avec des URL de redirection non résolvables).

**Les trois pièges à repérer dans n'importe quelle source :**
1. **Durée seule.** L'app écrit un `SleepSessionRecord` avec `stages` vide ou un unique `STAGE_TYPE_SLEEPING`. Techniquement conforme, inutile pour le contrôle de plausibilité. Seule la lecture par l'API le révèle (§5).
2. **Stades derrière un paywall.** Modèle historique de Fitbit Premium. Vérifie l'état de ton abonnement **avant** de conclure que la source « ne marche pas ».
3. **Synchro manuelle.** Cas Oura documenté explicitement (les deux apps doivent avoir été ouvertes, Background App Refresh actif). Une source qui exige un geste utilisateur chaque matin casse le critère P6 du SPEC (« parcours à froid sans intervention manuelle »).

---

## 4. Recommandation classée

### Rang 1 — Galaxy Watch 5 + Samsung Health. Option zéro achat.

**Pourquoi.** Déjà possédée. Écrit dans Health Connect depuis 2022 sur une chaîne éprouvée. Stades presque certains. C'est la seule option dont la précision de staging soit **chiffrée dans la littérature** (sur la GW3). Aucune dépense, aucune app supplémentaire, une seule case à cocher.

**Le protocole de nuit.** Pixel Watch 3 à la cheville, Galaxy Watch 5 au poignet, la même nuit. Deux montres, oui. C'est la contrainte inhérente au projet, elle ne disparaîtra avec aucune source.

**Le risque connu.** Le SPEC l'appelle « le piège de la synchro tardive » et la FAQ Samsung le confirme dans son propre vocabulaire : le transfert montre → téléphone est régi par la **politique batterie de la montre**, pas par le réveil. Une fois sur le téléphone, l'écriture vers Health Connect est immédiate. La parade `SleepFetchWorker` (T+30 min puis backoff 1/2/4/8 h, abandon à T+36 h) est correctement dimensionnée. Ouvrir Samsung Health le matin accélère les choses.

### Rang 2 — Sleep as Android. Option zéro achat, repli.

**Pourquoi en repli et pas en premier.** Il écrit bien les **phases** dans Health Connect ([documentation officielle](https://sleep.urbandroid.org/docs/services/health_connect.html)), ce qui est confirmé. Mais la page indique que le service Health Connect est encore **en BETA**, et si tu l'utilises en mode sonar/micro depuis le téléphone seul, la classification en stades n'a **aucune validation publiée connue** — bien plus faible que la GW5.

**Où il est excellent en revanche :** il n'attend aucune synchro Bluetooth de constructeur. Il écrit au réveil. Si le rang 1 échoue **uniquement** sur la latence, c'est le remplaçant naturel. Il peut aussi servir de deuxième source pour croiser, à condition de gérer la déduplication (§6) — et le kappa entre les deux sources serait une donnée de qualité intéressante en soi.

**Non vérifié :** son modèle tarifaire exact en 2026.

### Rang 3 — Si tu acceptes de dépenser

Par ordre décroissant de pertinence pour Pendulum :

**a) Un appareil Polar (~150-250 € en entrée de gamme).** C'est **le seul constructeur** dont la page support écrit littéralement « Sleep: start time, end time, **stages** from Polar sleep phases » ([support.polar.com](https://support.polar.com/en/flow-app-health-connect)). Aucun abonnement. Si le critère décisif est « je veux la certitude documentée d'avoir des stades dans Health Connect sans payer d'abonnement », c'est celui-là et c'est sans concurrent.

**b) Withings Sleep Analyzer (~130 €), tapis sous le matelas.** Le seul candidat qui règle un vrai problème d'ergonomie : **rien à porter, rien à recharger, rien à oublier**. Sur secteur, synchro Wi-Fi, donc a priori le meilleur profil de latence de toute la liste — un point qui compte beaucoup vu le piège de la synchro tardive. Deux réserves sérieuses : le support des **stades** dans Health Connect n'est **pas vérifié**, et des utilisateurs signalent des synchros de sommeil incomplètes. **À ne pas acheter avant d'avoir vérifié auprès de Withings.**

**c) Ce qu'il ne faut pas acheter pour ce besoin.** Oura et Whoop : abonnement obligatoire pour un usage strictement personnel, et pour Oura la page support ne liste même pas explicitement le sommeil dans ce qui part vers Health Connect. On paierait un abonnement mensuel pour un dénominateur qu'une montre déjà possédée fournit gratuitement.

### Le critère de bascule, à appliquer littéralement

Après **3 nuits consécutives** avec la source du rang 1 :

```
BASCULER si, dans les 36 h suivant chaque réveil, Health Connect ne contient PAS
un SleepSessionRecord qui, tout à la fois :
  (a) chevauche ≥ 50 % de la fenêtre d'enregistrement de la montre de cheville  ; ET
  (b) contient ≥ 2 types de stade distincts autres que STAGE_TYPE_UNKNOWN       ; ET
  (c) dont les stages couvrent ≥ 80 % de la durée de la session
Échec sur (a) seul      → problème de LATENCE  → rang 2 (Sleep as Android).
Échec sur (b) ou (c)    → problème de STADES   → rang 3a (Polar).
```

### Le garde-fou qui remet tout à l'échelle

**Ne surinvestis pas dans cette décision.** L'architecture du SPEC est déjà conçue pour survivre à l'absence totale de Health Connect : `deriveImmobilityMask()` produit un masque accéléro exploitable immédiatement, `RescoreWorker` ajoute le second PLMI plus tard, et chaque nuit produit **4 lignes `plm_result`** (2 jeux de règles × 2 masques) précisément pour rendre l'écart visible plutôt que de le cacher. La source de sommeil est une **amélioration de la qualité et de l'interprétabilité**, pas un prérequis. Si la vérification de la section 5 échoue, tu perds le contrôle de plausibilité par stade — c'est réel, ce n'est pas bloquant. Ne repousse pas la phase 1, qui est le seul vrai go/no-go du projet.

---

## 5. Vérification pratique — à faire AVANT d'écrire du code

Objectif : prouver que ta source écrit des **stades**, pas seulement une durée. Deux niveaux : l'UI pour un premier verdict, l'API pour le verdict qui fait foi.

### Étape 0 — préparer (soir J-1)

1. Samsung Health → `Paramètres > Health Connect > Autorisations d'applications` → activer **Sommeil** en lecture **et** écriture.
2. Vérifier que le suivi du sommeil est actif sur la Galaxy Watch 5.
3. Porter la GW5 la nuit. Noter l'heure de coucher.

### Étape 1 — au réveil, forcer la synchro

Ouvrir Samsung Health sur le téléphone, puis `Paramètres > Synchroniser avec le compte Samsung > Synchroniser maintenant`. **Noter l'heure exacte à laquelle la nuit apparaît** dans Samsung Health, puis dans Health Connect. Cette valeur calibre directement le backoff de `SleepFetchWorker` — c'est une mesure, pas une supposition.

### Étape 2 — inspection dans l'UI Health Connect

Ouvrir Health Connect : `Paramètres Android` → rechercher « Health Connect ». Sur Android 14+ il est intégré au système (`Sécurité et confidentialité > Plus de paramètres de confidentialité`) ; sur Android 13 et antérieur c'est l'app séparée du Play Store. Puis, sous `Autorisations et données`, ouvrir `Parcourir les données de santé` ([support.google.com/android/answer/12201872](https://support.google.com/android/answer/12201872)) → **Sommeil** → date d'aujourd'hui.

**Ce que tu dois voir en cas de succès :** une session avec heure de début, heure de fin, **et une ventilation par stade** avec les durées (Léger / Profond / Paradoxal ou REM / Éveillé). La ventilation est la preuve.

**À quoi ressemble un échec :**

| Symptôme | Diagnostic |
|---|---|
| Aucune entrée « Sommeil » | Autorisation non accordée, ou synchro pas encore arrivée. Réessayer plus tard avant de conclure. |
| Une entrée avec juste « 7 h 12 min », sans ventilation | **Durée seule.** C'est le piège n°1. |
| Une entrée présente, mais l'app source n'est pas celle attendue | Une autre app écrit aussi — problème de déduplication (§6). |
| Ventilation présente mais uniquement « Sommeil » | `STAGE_TYPE_SLEEPING` unique = binaire déguisé. Insuffisant. |

Vérifier aussi `Gérer les données > Sources de données et priorité` : la liste des apps qui écrivent le type Sommeil, dans l'ordre de priorité. Retiens cet ordre, il sert au §6.

### Étape 3 — la vérification qui fait foi (API)

L'UI peut arrondir ou masquer. Seul un `readRecords` tranche. Sonde jetable à lancer avant d'écrire quoi que ce soit de définitif :

```kotlin
val response = healthConnectClient.readRecords(
    ReadRecordsRequest(
        SleepSessionRecord::class,
        timeRangeFilter = TimeRangeFilter.between(hier20h, aujourdhui14h)
    )
)
for (r in response.records) {
    val types = r.stages.map { it.stage }.distinct()
    val couverture = r.stages.sumOf {
        Duration.between(it.startTime, it.endTime).toMinutes()
    }
    Log.i("Pendulum-HC", buildString {
        append("source=${r.metadata.dataOrigin.packageName} ")
        append("debut=${r.startTime} fin=${r.endTime} ")
        append("nbStages=${r.stages.size} typesDistincts=$types ")
        append("couvertureStages=${couverture}min ")
        append("dureeSession=${Duration.between(r.startTime, r.endTime).toMinutes()}min")
    })
}
```

**Grille de lecture :**

| Sortie observée | Verdict |
|---|---|
| `nbStages` de plusieurs dizaines, `typesDistincts` contenant 4, 5, 6 (LIGHT, DEEP, REM) et 1 (AWAKE), couverture ≈ durée | `[v]` **Vrai hypnogramme.** Feu vert. |
| `nbStages=0` ou liste vide | `[x]` Durée seule. |
| `typesDistincts=[2]` (uniquement `STAGE_TYPE_SLEEPING`) | `[x]` Binaire déguisé — utilisable pour le TST, inutile pour la plausibilité par stade. |
| `typesDistincts=[0]` (`STAGE_TYPE_UNKNOWN`) | `[x]` La source remplit le champ sans le renseigner. |
| Plusieurs `packageName` différents pour la même nuit | `[?]` Déduplication obligatoire avant tout calcul — voir §6. |
| `couvertureStages` très inférieure à `dureeSession` | `[?]` Hypnogramme troué. Les trous sont autorisés par l'API ; à toi de décider comment les traiter dans `mergeMasks()`. |

Répète l'étape 3 **trois nuits** avant de trancher. Une nuit réussie peut être un coup de chance de synchro.

---

## 6. Intégration technique — état 2026

### Version de la bibliothèque

| | Version | Date |
|---|---|---|
| Stable | **`androidx.health.connect:connect-client:1.1.0`** | 8 octobre 2025 |
| Alpha | `1.2.0-alpha04` | 22 avril 2026 |

Source : [developer.android.com/jetpack/androidx/releases/health-connect](https://developer.android.com/jetpack/androidx/releases/health-connect).

**Le SPEC épingle déjà `1.1.0` — c'est la bonne version, rien à changer.** Le guide « get started » suggère l'alpha ; ne le suis pas pour ce projet, la 1.2.0-alpha n'apporte que des API « Matchmaking » (`checkIfMatchmakingIsPossible()`, `createMatchmakingIntent()`) et des enrichissements Exercise sans rapport avec le sommeil.

### Changements d'API récents à connaître

- **`SleepStageRecord` a été SUPPRIMÉ** en `1.1.0-alpha01` ; les stades sont désormais **imbriqués dans `SleepSessionRecord`**. Toute documentation ou tout exemple qui manipule un `SleepStageRecord` séparé est obsolète.
- **`isProviderAvailable()` et `isApiSupported()` sont dépréciés**, remplacés par **`HealthConnectClient.getSdkStatus()`**.
- Ajouts 1.1.0 : Personal Health Record (FHIR, expérimental), Mindfulness Session, `SkinTemperatureRecord`. Sans impact ici.
- Permissions de lecture en **arrière-plan** et d'**historique** étendues à Android 13 et antérieur en `1.1.0-alpha11`.

### Permissions et manifeste

```xml
<!-- Permission de lecture du sommeil -->
<uses-permission android:name="android.permission.health.READ_SLEEP" />

<!-- INDISPENSABLE si SleepFetchWorker lit hors premier plan (c'est le cas ici) -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" />

<!-- Seulement si tu rescores des nuits de plus de 30 jours -->
<uses-permission android:name="android.permission.health.READ_HEALTH_DATA_HISTORY" />

<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>
```

Chaînes exactes confirmées sur [la page des types de données](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types) : `android.permission.health.READ_SLEEP` et `android.permission.health.WRITE_SLEEP`. Pendulum n'a besoin que de la lecture.

**`READ_HEALTH_DATA_IN_BACKGROUND` est un manque du SPEC v1.** Le SPEC ne mentionne que `READ_SLEEP`, mais l'architecture repose sur un `SleepFetchWorker` planifié en WorkManager qui, par construction, tourne app fermée. Sans cette permission, la lecture en arrière-plan échoue. Vérifier la disponibilité avant de planifier le worker ([doc read-data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)) :

```kotlin
if (healthConnectClient.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
) { /* planifier le worker */ }
```

**Limite d'historique de 30 jours.** Par défaut, une app ne lit que les 30 jours précédant l'octroi de la permission. Sur Android 14+, pas de limite pour les données que l'app a elle-même écrites ; limite de 30 jours pour celles des autres — donc **la limite s'applique bien au sommeil venu de Samsung Health**. Sur Android 13 et antérieur, 30 jours pour tout. Sans importance pour une campagne de 5-7 nuits, mais **la désinstallation/réinstallation de l'app remet la fenêtre à zéro** : un debug agressif peut te faire perdre l'accès à tes propres nuits antérieures. Pour aller au-delà : `HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY()`.

### L'activity de rationale — attention, il en faut DEUX déclarations

Le SPEC ne mentionne que la forme pré-Android 14. Avec `phone minSdk 30`, **les deux chemins doivent coexister**, sinon Health Connect refuse d'afficher l'app sur une partie du parc ([get-started](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started)).

```xml
<!-- Chemin Android 13 et antérieur (APK Health Connect) -->
<activity android:name=".PermissionsRationaleActivity" android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>

<!-- Chemin Android 14+ (Health Connect intégré au framework) -->
<activity-alias
    android:name="ViewPermissionUsageActivity"
    android:exported="true"
    android:targetActivity=".PermissionsRationaleActivity"
    android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
    <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
        <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
    </intent-filter>
</activity-alias>
```

Actions d'onboarding, si tu en ajoutes une : `androidx.health.ACTION_SHOW_ONBOARDING` (pré-14) et `android.health.connect.action.SHOW_ONBOARDING` (14+, avec `android:permission="android.permission.health.START_ONBOARDING"`).

**Divergence à signaler avec le SPEC :** le SPEC mentionne un `<meta-data health-permissions>`. La documentation « get started » actuelle **ne demande aucun meta-data** — les permissions se déclarent en simples `<uses-permission>`. Le meta-data relève d'une approche antérieure. Le laisser est vraisemblablement inoffensif, mais il n'est plus requis. À trancher au moment du code.

### `SleepSessionRecord` — structure exacte

Vérifiée dans le code source androidx ([SleepSessionRecord.kt](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/health/connect/connect-client/src/main/java/androidx/health/connect/client/records/SleepSessionRecord.kt)) :

```kotlin
const val STAGE_TYPE_UNKNOWN      = 0
const val STAGE_TYPE_AWAKE        = 1
const val STAGE_TYPE_SLEEPING     = 2
const val STAGE_TYPE_OUT_OF_BED   = 3
const val STAGE_TYPE_LIGHT        = 4
const val STAGE_TYPE_DEEP         = 5
const val STAGE_TYPE_REM          = 6
const val STAGE_TYPE_AWAKE_IN_BED = 7

class Stage(
    val startTime: Instant,
    val endTime: Instant,
    @property:StageTypes val stage: Int
)   // exige startTime < endTime

class SleepSessionRecord(
    override val startTime: Instant,
    override val startZoneOffset: ZoneOffset?,
    override val endTime: Instant,
    override val endZoneOffset: ZoneOffset?,
    override val metadata: Metadata,
    val title: String? = null,
    val notes: String? = null,
    val stages: List<Stage> = emptyList(),
) : IntervalRecord
```

Les huit constantes du SPEC sont **exactes**. Deux contraintes documentées ([features/sleep-sessions](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions)) : les stades doivent être **séquentiels et sans chevauchement** ; les **trous sont autorisés**. `mergeMasks()` doit donc gérer un hypnogramme troué, pas seulement une couverture pleine.

Note utile : `startZoneOffset` / `endZoneOffset` sont fournis par la source. C'est ta parade au piège n°10 du SPEC (heure d'été) — utilise-les plutôt que de recalculer un offset local.

### Déduplication par `dataOrigin` — le point le plus contre-intuitif

Fait vérifié sur [la doc d'agrégation](https://developer.android.com/health-and-fitness/guides/health-connect/develop/aggregate-data) :

- **`readRecords()` renvoie TOUS les enregistrements de TOUTES les sources.** Aucune déduplication. Si Samsung Health et Sleep as Android écrivent la même nuit, tu obtiens **deux sessions qui se chevauchent**, et si tu concatènes naïvement leurs stades tu obtiens un hypnogramme incohérent et un TST à peu près doublé — donc un PLMI divisé par deux, sans le moindre avertissement.
- **`aggregate()` déduplique, mais seulement pour Activity et Sleep**, et selon la priorité d'app réglée par l'utilisateur : « Only the Activity and Sleep data types are deduped by Health Connect, and the data totals shown are the values after the dedupe has been performed by the Aggregate API. »
- Mais `aggregate()` ne sait rendre que **`SLEEP_DURATION_TOTAL`**. **Il n'existe aucune agrégation qui renvoie les stades.**

D'où une stratégie concrète en deux temps :

1. `aggregate(SleepSessionRecord.SLEEP_DURATION_TOTAL)` → un TST dédupliqué par le système. Sert de **contrôle croisé** sur ton propre calcul. Si ton TST s'en écarte de plus de ~10 %, ta déduplication est fausse.
2. `readRecords()` + **ta propre déduplication** pour l'hypnogramme. Le SPEC prévoit déjà correctement une « source préférée en réglage » : c'est la bonne approche.

Règle d'or : **choisir une source et une seule pour une nuit donnée. Ne jamais fusionner les stades de deux sources.** Ordre de sélection suggéré : (1) la source préférée réglée par l'utilisateur si elle couvre ≥ 50 % de la fenêtre ; (2) sinon celle qui a le plus de `stages` distincts ; (3) à égalité, la plus longue couverture. Consigner le `dataOrigin.packageName` retenu dans `sleep_window` — sans ça, une nuit anormale est indébogable.

Côté utilisateur, la priorité se règle dans Health Connect (`Gérer les données`), mais Google précise que les apps lectrices restent libres de tout lire et de fusionner à leur façon ([support.google.com/android/answer/13770384](https://support.google.com/android/answer/13770384)). **Ne compte pas sur la priorité système pour te protéger** : c'est le boulot de Pendulum.

### Le piège de la synchro tardive — confirmé à la source

Le SPEC l'anticipe ; la FAQ développeur Samsung le confirme dans les termes du constructeur : le transfert montre → téléphone est régi par « the watch's own policy due to battery considerations », **sans délai garanti** ; en revanche, une fois la donnée sur le téléphone, « Samsung Health inserts or updates its data to Health Connect as soon as data is created or changed ».

Conséquences pratiques :
- Le goulot est **montre → téléphone**, pas téléphone → Health Connect. Ouvrir Samsung Health le matin est le levier efficace.
- Le backoff T+30 min / 1 h / 2 h / 4 h / 8 h, abandon à T+36 h, est bien dimensionné — mais **mesure la vraie latence sur 3 nuits (§5, étape 1) et recale-le sur ta valeur observée** plutôt que de garder ces chiffres.
- Attention au piège n°12 du SPEC : `SleepFetchWorker` sous contrainte `requiresCharging` peut ne jamais s'exécuter. Combine-le avec `READ_HEALTH_DATA_IN_BACKGROUND`, `setExpedited`, et garde un déclenchement manuel.
- Cas limite : Samsung Health peut **mettre à jour** (« inserts or **updates** ») une session déjà écrite. Une nuit lue à T+1 h peut différer de la même nuit à T+8 h. `RescoreWorker` doit être **idempotent** et écraser proprement, pas empiler.

---

## 7. Ce que je n'ai pas pu vérifier

Liste explicite. Chaque ligne est un point où j'extrapole ou où la source est indirecte.

1. **Que Samsung Health écrive les *stades* (et pas seulement la durée) vers Health Connect.** Fortement suggéré par le blog développeur Samsung, jamais affirmé littéralement dans le sens Samsung Health → Health Connect. **C'est le point le plus important de tout ce document** — d'où la section 5.
2. **Les performances de la Galaxy Watch 5.** L'étude validée porte sur la **Galaxy Watch 3**. Transposition raisonnable, non démontrée.
3. **Les délais de synchro chiffrés** pour toutes les sources sauf Samsung (et encore, Samsung dit « politique de la montre » sans chiffre).
4. **Le support des stades dans Health Connect** pour : Garmin, Oura, Whoop, Withings, Zepp/Amazfit, Google Health (ex-Fitbit). Confirmé uniquement pour **Polar**, **Sleep as Android**, et (fortement probable) **Samsung**.
5. **Oura :** la page support liste Activité / Mensurations / Vitals ; **le sommeil n'y figure pas explicitement**. Soit la liste est incomplète, soit Oura n'exporte pas le sommeil vers Health Connect. Non tranché.
6. **La page support Whoop** ([support.whoop.com/s/article/Google-Health-Integration-For-Android](https://support.whoop.com/s/article/Google-Health-Integration-For-Android)) et **la page support Withings** ont toutes deux renvoyé HTTP 403. Contenu connu uniquement par extraits de recherche.
7. **La page support officielle Garmin** ([faq JToBEy0jfe6pIygark2Ui5](https://support.garmin.com/en-US/?faq=JToBEy0jfe6pIygark2Ui5)) n'a pas rendu son contenu. La liste des « 15 types de données » est de seconde main.
8. **Sleep Cycle :** aucune confirmation de support Health Connect, ni dans un sens ni dans l'autre.
9. **Nest Hub Sleep Sensing :** aucun chemin vers Health Connect trouvé, mais absence de preuve n'est pas preuve d'absence. Le statut commercial du Nest Hub 2 en 2026 n'est pas vérifié.
10. **Les dates de la migration des comptes Fitbit vers Google** (bascule, suppression des données non migrées) : issues d'un moteur avec URL de redirection non résolvables. **Non vérifiées.**
11. **Le modèle tarifaire de Sleep as Android en 2026.**
12. **L'alignement de la table de consommation du BMI270** : reconstruit depuis une extraction texte du PDF. La valeur 685 µA est corroborée par la page produit ; les cinq autres ne le sont pas.
13. **Les prix indiqués** dans le tableau §3 sont des ordres de grandeur de mémoire, non vérifiés en 2026.

---

## À vérifier toi-même

- **Fais la section 5 avant tout.** C'est une soirée de travail et elle décide de tout le croisement. Le SPEC le note déjà en dernière ligne de ses propres « à vérifier » — c'est le même point, développé.
- **Ne laisse pas ce document retarder la phase 1.** Le vrai go/no-go du projet est l'autonomie et le off-body de la Pixel Watch 3 à la cheville, pas le choix de la source de sommeil. Si la phase 1 échoue, la question du dénominateur ne se pose plus.
- **Ajoute `READ_HEALTH_DATA_IN_BACKGROUND` au SPEC.** C'est un manque réel, pas un détail : sans lui, `SleepFetchWorker` ne lit rien app fermée.
- **Mesure la latence réelle de ta chaîne GW5 → Samsung Health → Health Connect sur 3 nuits** et recale le backoff sur cette valeur, au lieu de garder les chiffres du SPEC.
- **Tranche le point 1 de la section 7 par l'API, pas par l'UI.** L'écran Health Connect peut afficher une ventilation sans que `stages` soit exploitable, et l'inverse.
- **Décide explicitement quoi faire des trous d'hypnogramme** (l'API les autorise). Compter un trou comme sommeil ou comme éveil change le dénominateur, donc le PLMI, donc potentiellement la décision de dépistage. Ce choix mérite d'être un paramètre tracé dans `paramsJson`, pas une valeur en dur.
