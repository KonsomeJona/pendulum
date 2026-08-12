# Pendulum — Spécification d'interface (v1)

Document de conception d'interface : les principes, le raisonnement écran par écran, et les
arbitrages qui ont été pesés puis tranchés. Il ne répète ni l'architecture ni l'algorithme.

> **Ce document ne fait plus foi sur l'écran, et il faut savoir dans quel sens.** Il a été écrit
> avant plusieurs décisions que le code a prises depuis, et il l'annonce lui-même en trois endroits :
> la carte « Ce soir » qui apparaît et disparaît entre 20 h et 4 h n'existe plus, l'écran de
> tendance n'est plus la destination de départ, et les couleurs du §5.1 échoueraient au test de
> contraste que celles du code passent.
>
> **L'état actuel de l'interface est dans [`../06-interface.md`](../06-interface.md)**, qui dérive de
> ce fichier et enregistre chaque écart avec sa raison. Ce qui n'existe qu'ici et qui garde toute sa
> valeur : les options de représentation du chiffre comparées une à une (§3), les textes candidats
> écrits en entier, et la trace du raisonnement — le *pourquoi* dont `06-interface.md` ne garde que
> la conclusion.
>
> Sa phrase d'origine — « toute contradiction entre les deux : `SPEC-v1.md` fait foi pour la
> technique » — désignait un document lui-même remplacé depuis. La carte des autorités est dans
> [`../README.md`](../README.md).

**Utilisateur unique** : un adulte, technicien, qui soupçonne un trouble moteur du sommeil et qui veut
savoir si son traitement change quelque chose et pouvoir montrer un rapport à un médecin du sommeil.
Il consulte l'app à 7 h du matin, embrumé, une seule main, dans le noir.

**Contrainte de conception centrale** : la mesure est incertaine, et la principale source d'incertitude n'est
pas le capteur — c'est la variabilité inter-nuits. Chez des patients SJSR confirmés, le seuil de 15/h n'est
dépassé que sur ~34 % des nuits individuelles. Une nuit ne dit rien. L'interface doit **refuser de conclure
sur une nuit**, pas se contenter d'un avertissement à côté d'un gros chiffre.

---

## 1. Principes de conception

Sept principes. Chacun est une contrainte vérifiable en revue d'écran : on peut répondre oui/non en
regardant la maquette ou le code, sans débat de goût.

### P1 — Aucune agrégation sous 3 nuits éligibles

L'écran Tendance ne calcule, n'affiche et n'exporte **aucune** valeur agrégée (médiane, catégorie, verdict,
courbe) tant que moins de 3 nuits éligibles existent. Il affiche à la place un état de collecte.
*Vérification : forcer la base à 0, 1, 2 nuits → aucun chiffre agrégé n'apparaît, aucune ligne de graphe
n'est tracée, l'export est désactivé avec un motif affiché.*

### P2 — Tout chiffre agrégé est accompagné de son incertitude, dans la même ligne de texte

Interdit d'afficher une médiane sans son intervalle et son n. Le format canonique est
`22/h · IC 95 % 14–31 · 6 nuits`. Pas d'astérisque, pas de note de bas de page, pas d'infobulle :
l'incertitude est dans la phrase, à la même taille de police que le chiffre ou à un cran en dessous maximum.
*Vérification : grep sur les composables — aucune instance de `MetricHeadline` sans paramètre `interval` et
`nightCount` non nuls.*

### P3 — Une variation non distinguable du bruit est nommée comme telle, avant d'être chiffrée

Quand l'intervalle de confiance de la différence entre deux périodes contient zéro, la première ligne
affichée est « Variation non concluante ». Le chiffre de la différence vient après, jamais avant, jamais
plus gros. Les mots « amélioration », « aggravation », « ça marche », « efficace » n'existent nulle part
dans les chaînes de l'app.
*Vérification : test instrumenté sur un jeu de données à effet nul → la chaîne « non concluante » précède la
valeur dans l'ordre de composition.*

### P4 — Trois informations maximum au-dessus de la ligne de flottaison, au réveil

L'écran d'arrivée matinal tient dans un regard de deux secondes : (a) où en est la nuit d'hier, (b) le chiffre
agrégé s'il existe, (c) une action unique. Rien d'autre n'est visible sans scroll. Aucune décision à prendre,
aucun choix multiple, aucun formulaire.
*Vérification : capture de l'écran Tendance en 411×891 dp → compter les éléments interactifs visibles sans
scroll : ≤ 3, dont au plus 1 bouton d'action.*

### P5 — Aucune échelle tronquée, aucune moyenne masquant un extremum

L'axe des ordonnées de tout graphe de résultat commence à 0. L'enveloppe du signal de nuit est décimée en
min/max par colonne de pixels, jamais en moyenne — un pic de 2 s sur 8 h doit rester visible. Un point de
donnée écrêté par les bornes de l'axe est marqué explicitement (chevron ▲ + valeur en clair).
*Vérification : test unitaire du décimateur — un pic isolé d'un échantillon survit à un facteur de décimation
de 4096.*

### P6 — La couleur n'est jamais le seul porteur d'information

Toute distinction encodée en couleur est doublée d'une distinction de forme, de position, de pointillé ou de
libellé. Le vert et le rouge ne codent jamais un résultat clinique (ils ne servent qu'à l'état technique :
transfert réussi / échoué). Les stades de sommeil sont d'abord distingués par leur position verticale.
*Vérification : passer chaque capture en niveaux de gris → toute l'information reste lisible.*

### P7 — Le chiffre par nuit est disponible mais jamais mis en avant

L'utilisateur est technicien : on ne lui cache pas la valeur d'une nuit. Mais elle n'apparaît qu'à l'échelle
« corps de texte », en couleur secondaire, dans le détail de la nuit ou la liste, toujours accompagnée de la
mention « valeur d'une seule nuit — non interprétable isolément ». Elle n'apparaît jamais en titre, jamais
dans une notification, jamais dans un widget, jamais dans le résumé de l'export.
*Vérification : le style `metricXL` n'est utilisé que dans `TrendHeadline`. Une seule occurrence dans tout
l'arbre.*

---

## 2. Parcours écran par écran

Navigation racine : `NavigationBar` à trois destinations, **Tendance en destination de départ**.

| Onglet | Icône | Route |
|---|---|---|
| Tendance | `Icons.Outlined.ShowChart` | `trend` |
| Nuits | `Icons.Outlined.Nightlight` | `nights` |
| Réglages | `Icons.Outlined.Tune` | `settings` |

Le questionnaire, le détail de nuit et l'export sont des destinations empilées, sans barre de navigation.

---

### 2.1 Premier lancement

Cinq étapes, non sautables, dans un `HorizontalPager` non swipable (progression par bouton uniquement, pour
éviter de survoler l'avertissement). Indicateur de progression : `LinearProgressIndicator` fin en haut,
1/5 → 5/5. Pas de bouton « Passer ».

#### Écran 1/5 — Avertissement

> **Titre** : Ce que Pendulum ne peut pas faire

Corps (texte intégral, scrollable ; le bouton reste désactivé tant que le scroll n'a pas atteint le bas) :

```
Pendulum estime le nombre de mouvements de jambes par heure de sommeil à partir de l'accéléromètre d'une
montre portée à la cheville. C'est un instrument de mesure personnel. Ce n'est pas un dispositif médical
et ce n'est pas un diagnostic.

Quatre limites qui ne disparaîtront jamais, quelle que soit la qualité de vos nuits :

• Pendulum ne peut pas diagnostiquer un syndrome des jambes sans repos. Ce diagnostic est clinique : il
  repose sur vos symptômes à l'éveil, pas sur un capteur. Les mouvements pendant le sommeil ne sont
  qu'un critère de support.

• Pendulum ne mesure pas votre respiration. Il ne peut donc pas distinguer un mouvement périodique d'un
  mouvement provoqué par une apnée. Si vous faites des apnées du sommeil, le chiffre est surestimé, et
  l'écart peut atteindre plusieurs dizaines de mouvements par heure.

• Pendulum ne mesure qu'une jambe. Les mouvements de l'autre jambe sont manqués. Ce biais tire le chiffre
  vers le bas. Il ne compense pas le précédent : les deux erreurs ne s'annulent pas, elles s'ajoutent à
  l'incertitude.

• L'American Academy of Sleep Medicine recommande explicitement de ne pas remplacer l'électromyogramme
  par l'actigraphie pour diagnostiquer un trouble des mouvements périodiques des membres.

Ce que Pendulum fait utilement : suivre une tendance sur plusieurs nuits, dans des conditions stables, et
produire un document que vous pouvez apporter à un médecin du sommeil.
```

Bouton primaire : **J'ai lu et compris** (désactivé jusqu'au bas du scroll).
Sous le bouton, en caption tertiaire : `Cet avertissement reste consultable dans Réglages › À propos.`

#### Écran 2/5 — Ce dont Pendulum a besoin

> **Titre** : Deux montres, ou une montre et une app

Trois blocs `RequirementRow` (icône + titre + description) :

1. **Une montre Wear OS à la cheville**
   `Elle enregistre l'accéléromètre toute la nuit à 50 Hz. C'est elle qui mesure les mouvements. Elle doit
   être chargée à 100 % au coucher : une nuit consomme entre 40 et 70 % de batterie.`
2. **Une source de stades de sommeil**
   `Une seconde montre au poignet (Samsung Health) ou Sleep as Android, qui écrit l'hypnogramme dans Health
   Connect. Sans elle, Pendulum sait quand vous bougez mais pas quand vous dormez : il utilise alors son propre
   masque d'immobilité, moins fiable, et le signale sur chaque nuit concernée.`
3. **Trois nuits minimum, cinq à sept de préférence**
   `Pendulum refuse de calculer une tendance sous trois nuits. Ce n'est pas de la prudence décorative : sur une
   nuit isolée, le résultat est dominé par le hasard.`

Bouton : **Continuer**.

#### Écran 3/5 — Appairage de la montre

État initial : `Recherche de la montre…` avec `CircularProgressIndicator` et sous-texte
`Ouvrez Pendulum sur votre montre. Les deux appareils doivent être appairés dans l'app Watch.`

Succès : carte avec nom du nœud (`Pixel Watch 3`), version d'app montre, batterie, espace libre.
Ligne de vérification : `Accéléromètre : 50 Hz demandés · FIFO 1 024 événements · capteur wake-up : oui`
(valeurs lues à l'exécution — c'est un contrôle, pas de la décoration).

Échec après 30 s : voir §7, message `E-PAIR-01`.

Bouton : **Continuer** (activé seulement si un nœud a répondu).
Lien secondaire : `Continuer sans montre pour l'instant` → autorise l'exploration de l'app, désactive
l'enregistrement, affiche un bandeau persistant sur Tendance.

#### Écran 4/5 — Source de sommeil

> **Titre** : D'où viennent vos stades de sommeil ?

Bouton primaire **Autoriser Health Connect** → déclenche la demande de permission `READ_SLEEP`.
(L'activité de rationale exigée par Health Connect affiche : `Pendulum lit vos sessions et stades de sommeil
pour rapporter les mouvements de jambes par heure de sommeil réel, et non par heure passée au lit. Aucune
donnée ne quitte le téléphone.`)

Après autorisation, Pendulum lit les 7 derniers jours et affiche la liste des sources trouvées :

```
Sources détectées
  Samsung Health        6 nuits sur 7 · stades détaillés (REM, léger, profond)   ● source préférée
  Sleep as Android      2 nuits sur 7 · stades détaillés
```

Si une source ne fournit que la durée totale sans stades :
`Cette source ne fournit pas de stades détaillés. Pendulum pourra calculer le temps de sommeil total, mais pas
répartir les mouvements par stade.`

Si aucune source : message `E-HC-01` (§7) + bouton **Continuer sans hypnogramme**.

#### Écran 5/5 — Notifications et conditions de mesure

Bouton **Autoriser les notifications** (`POST_NOTIFICATIONS`), avec la raison :
`Une seule notification par nuit, au réveil, quand l'analyse est prête. Aucune autre.`

Puis un bloc **Conditions à garder identiques d'une nuit à l'autre**, présenté comme une consigne, pas comme
un réglage :

```
Le seuil de détection s'adapte au bruit de fond de chaque nuit. Si les conditions mécaniques changent, les
nuits ne sont plus comparables entre elles.

Gardez identiques :
  • le même bracelet, au même trou de serrage ;
  • la même jambe ;
  • la montre sur la face avant du tibia, juste au-dessus de la malléole ;
  • le même matelas.

Notez le trou de serrage utilisé : Pendulum vous le rappellera au coucher.
```

Champ `OutlinedTextField` court : `Repère de serrage (ex. « 4e trou »)` — stocké, réaffiché chaque soir.

Bouton final : **Terminer**.

---

### 2.2 Rituel du coucher

Le coucher est un rituel à deux mains : la montre lance, le téléphone confirme. Rien d'autre.

#### Sur la montre

Écran unique (voir §6). L'utilisateur ouvre Pendulum et appuie sur **DÉMARRER**. Deux secondes.

#### Sur le téléphone — carte « Ce soir »

Visible en tête de l'écran Tendance **entre 20 h 00 et 04 h 00 uniquement**. Hors de cette plage, elle
disparaît complètement (P4 : rien d'inutile au réveil).

État A — enregistrement non démarré :

```
┌──────────────────────────────────────────────┐
│ CE SOIR                                      │
│                                              │
│ Montre       98 %  ·  1,2 Go libre    ✓      │
│ Bracelet     4e trou, jambe droite    ⓘ      │
│ Sommeil      Samsung Health, actif    ✓      │
│                                              │
│ Appuyez sur DÉMARRER sur la montre.          │
└──────────────────────────────────────────────┘
```

Si la batterie de la montre < 85 % : la ligne passe en ambre avec le texte
`98 % recommandé — une nuit consomme 40 à 70 %.` Aucun blocage : c'est un avis, pas une porte.

État B — enregistrement en cours :

```
┌──────────────────────────────────────────────┐
│ ENREGISTREMENT EN COURS                      │
│                                              │
│ Depuis 23:12  ·  2 h 47 min                  │
│ 498 200 échantillons  ·  50,2 Hz mesurés     │
│ Batterie montre 71 %  ·  0 trou              │
│                                              │
│ L'arrêt se fait sur la montre, ou            │
│ automatiquement dès qu'elle est sur le socle.│
└──────────────────────────────────────────────┘
```

Rafraîchissement : 60 s, uniquement quand l'écran du téléphone est allumé et l'app au premier plan.
Pas de service, pas de notification persistante côté téléphone.

---

### 2.3 Réveil

Il n'y a qu'une notification par nuit, envoyée **après** l'analyse, jamais avant :
`Pendulum — Nuit du 12 mars analysée. 6 nuits disponibles.` Pas de chiffre dans la notification (P7).

L'écran Tendance affiche en haut une bande d'état (`StatusStrip`) qui prend l'un des états suivants.
Un seul état à la fois, une seule action possible.

#### État 1 — En attente de transfert

```
Nuit du 12 mars enregistrée sur la montre
8,8 Mo à transférer. Le transfert démarre quand la montre est sur son socle et à
portée Bluetooth. Comptez 4 minutes.
                                                       [ Transférer maintenant ]
```

#### État 2 — Transfert en cours

```
Transfert en cours — 4,1 Mo sur 8,8 Mo
[████████░░░░░░░░]  chunk 8/17
Vous pouvez fermer l'app.
```
Le transfert reprend où il s'est arrêté ; ne jamais afficher de pourcentage qui recule.

#### État 3 — Analyse en cours

```
Analyse de la nuit du 12 mars
[████████████░░░░]  détection des mouvements
Environ 40 secondes.
```
Trois libellés d'étape seulement : `assemblage du signal`, `détection des mouvements`,
`croisement avec le sommeil`. Pas de log technique ici (il est dans Réglages › Journal).

#### État 4 — Analyse partielle (cas le plus fréquent au réveil)

L'analyse accéléro est finie, l'hypnogramme n'est pas encore arrivé dans Health Connect. C'est **normal** et
le texte doit le dire, sinon l'utilisateur croit à une panne tous les matins.

```
Nuit du 12 mars — résultat provisoire
Les mouvements sont détectés. Les stades de sommeil de votre montre de poignet ne sont pas encore
arrivés dans Health Connect : la synchronisation se fait souvent plusieurs heures après le réveil.

En attendant, Pendulum utilise son propre masque d'immobilité. Le chiffre sera recalculé automatiquement,
sans rien faire de votre part.

Dernière tentative : 07:12 · prochaine : 08:12
                                                     [ Réessayer maintenant ]
```

La nuit apparaît dans la liste et dans la tendance, marquée `masque accéléro` (drapeau, pas erreur).
Après recalcul, un `Snackbar` : `Nuit du 12 mars recalculée avec l'hypnogramme.`

Abandon à T+36 h : la nuit reste éligible mais garde définitivement le drapeau `masque accéléro`.

#### État 5 — Échec

Une seule carte, motif explicite, action explicite. Les textes exacts sont en §7. Structure :

```
Nuit du 12 mars — analyse impossible
<ce qui s'est passé, une phrase>
<ce qu'il faut faire, une phrase>
                             [ Action de réparation ]   [ Voir le détail technique ]
```

Une nuit en échec n'est **jamais** silencieusement écartée : elle apparaît dans la liste des nuits, barrée,
avec son motif. Le compteur « nuits éligibles » explique toujours d'où vient l'écart :
`7 nuits enregistrées · 5 éligibles · 2 écartées (voir Nuits).`

---

### 2.4 Tendance — écran principal

C'est l'écran conçu en premier et l'écran de départ. Tout le reste du produit est un détail à côté.

#### Cas A — moins de 3 nuits éligibles : refus d'agréger

Aucun graphe. Aucune médiane. Aucune catégorie. Une seule carte pleine largeur :

```
┌────────────────────────────────────────────────────────┐
│                                                        │
│                    2 nuits sur 3                       │
│              ●        ●        ○                       │
│                                                        │
│  Pendulum ne calcule pas de résultat avant trois nuits.    │
│                                                        │
│  Sur une nuit isolée, le nombre de mouvements varie    │
│  énormément d'une nuit à l'autre, y compris chez des   │
│  personnes dont le trouble est confirmé : le seuil de  │
│  15 mouvements par heure n'est dépassé que sur environ │
│  une nuit sur trois. Un résultat affiché maintenant    │
│  vous tromperait, dans un sens ou dans l'autre.        │
│                                                        │
│  Enregistrez encore une nuit.                          │
│                                                        │
│  Nuits enregistrées                                    │
│   11 mars   7 h 42 de sommeil   qualité correcte    →  │
│   12 mars   6 h 58 de sommeil   qualité correcte    →  │
│                                                        │
└────────────────────────────────────────────────────────┘
```

Les trois pastilles sont pleines/vides, jamais une barre de progression (une barre suggère un score qui
monte). L'export est désactivé, avec le motif affiché sur le bouton lui-même :
`Export indisponible — 3 nuits minimum`.

Le lien vers le détail d'une nuit reste actif : le technicien peut inspecter son signal. C'est le **détail**
qui montre un chiffre par nuit, pas la tendance (P7).

#### Cas B — 3 ou 4 nuits : résultat provisoire

Identique au cas C, avec deux différences :
- un bandeau au-dessus du chiffre : `Résultat provisoire — 4 nuits. Cinq à sept nuits sont recommandées ;
  l'intervalle ci-dessous restera large tant que vous n'en aurez pas davantage.` ;
- **aucune catégorie textuelle n'est affichée** (voir §3), quel que soit l'intervalle.

#### Cas C — 5 nuits ou plus : écran complet

```
┌────────────────────────────────────────────────────────┐
│  Tendance                                 [7 nuits ▾]  │   ← sélecteur de période
├────────────────────────────────────────────────────────┤
│                                                        │
│   22 /h                                                │   metricXL, tabulaire
│   IC 95 % : 14 – 31   ·   6 nuits éligibles            │   body, secondaire
│                                                        │
│   Index de mouvements périodiques, médiane des nuits   │   caption, tertiaire
│                                                        │
│   ┌──────────────────────────────────────────────┐     │
│   │ Au-dessus du seuil de 15/h retenu en          │     │
│   │ pratique clinique. L'intervalle reste         │     │
│   │ compatible avec une valeur inférieure.        │     │
│   │ À discuter avec un médecin du sommeil.        │     │
│   └──────────────────────────────────────────────┘     │
│                                                        │
├────────────────────────────────────────────────────────┤
│   [ GRAPHE DE TENDANCE ]           voir §4.2           │
│                                                        │
├────────────────────────────────────────────────────────┤
│  Comparer deux périodes                             →  │
│  Avant / après un changement de traitement             │
├────────────────────────────────────────────────────────┤
│  Règle de comptage        AASM v3 (5–90 s)          ▾  │
│  Masque de sommeil        Health Connect (5/6 nuits)   │
│  Mouvements en éveil      PLMW 9/h                     │
│  Périodicité (Ferri)      0,71                         │
├────────────────────────────────────────────────────────┤
│  7 nuits enregistrées · 6 éligibles · 1 écartée     →  │
├────────────────────────────────────────────────────────┤
│  [ Questionnaire de dépistage ]     non rempli      →  │
├────────────────────────────────────────────────────────┤
│         [ Préparer un rapport pour le médecin ]        │
└────────────────────────────────────────────────────────┘
```

Notes de contenu :

- Le sélecteur de période propose : `7 dernières nuits`, `14 dernières nuits`, `30 dernières nuits`,
  `Tout`, `Période personnalisée…`. Changer de période recalcule médiane et intervalle. Le nombre de nuits
  éligibles est **toujours** réaffiché à côté du chiffre : c'est le garde-fou contre l'illusion de précision
  d'une longue période contenant peu de nuits.
- Le bloc encadré sous le chiffre est la **phrase de position** (§3). Elle est générée par une fonction
  déterministe, pas rédigée à la volée, et il en existe exactement cinq variantes (§3.4).
- Le changement de règle AASM v3 / WASM 2016 recalcule tout à l'écran, immédiatement, et l'écart entre les
  deux est significatif : c'est voulu qu'il soit visible en un geste. Un `Snackbar` rappelle :
  `WASM 2016 exige un intervalle d'au moins 10 s entre deux mouvements ; AASM v3 accepte 5 s. Les chiffres
  ne sont pas comparables entre les deux règles.`

#### Comparaison de deux périodes (écran empilé)

Deux sélecteurs de plage de dates (`Période A`, `Période B`), avec un préréglage
`Avant / après un changement de traitement` qui demande simplement une date pivot.

Sous 5 nuits éligibles dans l'une des deux périodes :
```
Comparaison indisponible
Période A : 3 nuits éligibles. Il en faut au moins 5 dans chaque période.
Avec moins de nuits, la différence mesurée serait dominée par la variabilité normale
d'une nuit à l'autre, et non par un effet réel.
```

Sinon, résultat en trois lignes, dans cet ordre imposé (P3) :

```
Variation non concluante
Période A  31 /h   (IC 95 % 19 – 44)   6 nuits   1–15 février
Période B  22 /h   (IC 95 % 14 – 31)   6 nuits   1–15 mars
Différence  −9 /h  (IC 95 % −24 à +5)

L'intervalle de la différence contient zéro : avec ces données, on ne peut pas
distinguer un changement réel d'une fluctuation ordinaire entre nuits. Vos nuits
varient elles-mêmes de ±12/h autour de leur médiane.

Pour trancher, il faudrait environ 11 nuits par période.
```

L'estimation du nombre de nuits nécessaires est calculée par simulation à partir de la dispersion observée,
et présentée comme un ordre de grandeur (`environ`, arrondi à l'unité supérieure). Si le calcul dépasse
30 nuits par période : `Avec une variabilité aussi forte, un effet de cette taille n'est pas mesurable par
cette méthode.` — dire que c'est hors de portée est plus honnête que d'afficher « environ 84 nuits ».

Quand l'intervalle de la différence exclut zéro, la première ligne devient
`Différence supérieure à la variabilité entre nuits` — jamais « amélioration ».

---

### 2.5 Liste des nuits

`LazyColumn` de `NightRow`, groupées par mois (`stickyHeader`). Ordre antichronologique.

Une ligne :

```
 12 mars   ven                                                  →
 23:12 → 06:58 · 6 h 58 de sommeil (Health Connect)
 24 /h   valeur d'une seule nuit                    ● éligible
```

- La date en `titleM`, le chiffre en `body` couleur secondaire (P7).
- Pastille d'état à droite : `● éligible` (accent), `◐ provisoire` (ambre, hypnogramme manquant),
  `○ écartée` (tertiaire, texte barré sur le chiffre) + motif court en dessous
  (`écartée : 2 h 10 de sommeil`).
- Drapeaux qualité en `AssistChip` compacts sous la ligne, maximum 3 visibles puis `+2` :
  `masque accéléro`, `trou 47 s`, `hors-corps 12 %`, `batterie 8 %`, `posture ×14`.

Filtre en haut : `Toutes` / `Éligibles` / `Écartées`.

---

### 2.6 Détail d'une nuit

Écran empilé, scroll vertical, cinq sections.

**Section 1 — En-tête**
```
Nuit du 12 mars
23:12 → 06:58  ·  7 h 46 au lit  ·  6 h 58 de sommeil
```
Puis, immédiatement, la valeur et son avertissement fusionnés en un seul bloc :
```
24 /h   PLMI de cette nuit
Une seule nuit ne permet aucune conclusion. Ce chiffre existe pour vérifier la mesure,
pas pour l'interpréter. Voir la tendance.
                                                            [ Voir la tendance → ]
```

**Section 2 — Graphe de nuit + hypnogramme** (spécifiés en §4.1 et §4.3)
Les deux graphes partagent le même axe horizontal et le même curseur. Un bandeau de valeurs sous le doigt :
`02:14:38 · amplitude ×9,2 · seuil ×8,0 · N2 · CLM #143, série 12`.

**Section 3 — Événements détectés**
```
Mouvements détectés            412
  dont en sommeil (PLMS)       278
  dont en éveil (PLMW)          64
  écartés — posture             57
  écartés — durée hors 0,5–10 s 13

Séries (≥ 4 mouvements)         31   couvrant 3 h 12
Intervalle médian onset-onset  23,4 s
Index de périodicité (Ferri)   0,71
```
Chaque ligne est cliquable et filtre le graphe au-dessus (les marqueurs non concernés passent à 20 %
d'opacité). Une liste `Tous les événements →` ouvre un tableau : `#, début, durée, amplitude/plancher,
stade, série, motif d'exclusion`.

**Section 4 — Qualité de la nuit**
Liste de contrôles, chacun avec sa valeur mesurée, son seuil, et un état :
```
Couverture du signal      99,2 %   seuil 97 %      ✓
Plus grand trou            1,8 s   seuil 5 s       ✓
Cumul des trous            11 s    seuil 120 s     ✓
Fréquence mesurée         50,21 Hz  attendu 50 Hz  ✓
Batterie en fin de nuit     34 %   seuil 20 %      ✓
Porté (hors-corps)        96,4 %   seuil 90 %      ✓
Sommeil total           6 h 58     seuil 4 h       ✓
Source du sommeil       Health Connect (Samsung Health)
Règle appliquée         AASM v3 · algo 1.4.0 · profil « défaut »
```
Un seul contrôle en échec suffit à rendre la nuit non éligible ; il est alors affiché en tête, en ambre,
avec le libellé exact du §7.

**Section 5 — Paramètres et recalcul** (repliée par défaut, `Paramètres avancés`)
Sliders : seuil d'onset (4–12 × plancher), seuil d'offset (1–4 ×), durée min/max du CLM,
fenêtre du plancher de bruit (10–60 s), fusion des CLM proches (0–1 s), inclusion des mouvements posturaux.
Bouton **Recalculer cette nuit** + **Appliquer à toutes les nuits** (avec confirmation :
`Toutes les nuits seront recalculées avec ces paramètres. Les résultats précédents sont conservés dans le
journal. Les nuits calculées avec des paramètres différents ne sont pas comparables entre elles.`).

Un bandeau permanent apparaît dès qu'un profil non-défaut est actif, sur Tendance **et** dans l'export :
`Paramètres personnalisés actifs (profil « seuil 6× »). Les valeurs ne sont pas comparables aux valeurs de
référence.`

---

### 2.7 Questionnaire de dépistage

Deux étapes, sans score global affiché en gros.

**Étape 1 — Question unique de dépistage rapide du SJSR**
```
Quand vous essayez de vous détendre le soir ou de dormir, ressentez-vous parfois
un besoin irrépressible de bouger les jambes, ou des sensations désagréables dans
les jambes, qui sont soulagées quand vous bougez ?

                          [ Oui ]        [ Non ]
```
Réponse « Non » :
`Cette question unique écarte le syndrome des jambes sans repos dans la très grande majorité des cas. Le
questionnaire détaillé reste disponible si vous voulez le remplir quand même.`
Réponse « Oui » → étape 2.

**Étape 2 — Cambridge-Hopkins CH-RLSq**
Une question par écran, `LinearProgressIndicator`, retour en arrière possible.
En tête : `Ce questionnaire porte sur ce que vous ressentez à l'éveil. Il est indépendant de la mesure
faite par la montre : les deux se complètent, aucun ne remplace l'autre.`

Résultat, sans chiffre-score en titre :
```
Réponses compatibles avec un syndrome des jambes sans repos
Ce questionnaire est un outil de dépistage. Il ne pose pas de diagnostic : les cinq critères
diagnostiques doivent être vérifiés par un médecin, notamment pour écarter les affections qui
imitent le SJSR (crampes, neuropathie, inconfort de position, akathisie liée à un médicament).

Vos réponses sont incluses dans le rapport exportable.
                                                        [ Revoir mes réponses ]
```
Trois issues possibles : `compatibles` / `non compatibles` / `incomplet`. Aucune échelle de sévérité (l'IRLS
est sous copyright et exclue). Aucune mise en couleur alarmante.

La date de remplissage est affichée sur Tendance ; au-delà de 6 mois : `rempli il y a 8 mois — à refaire`.

---

### 2.8 Réglages

Sections `ListItem` classiques, pas de recherche, pas de sous-menus profonds.

**Mesure**
- Règle de comptage : `AASM v3 (5–90 s)` / `WASM 2016 (10–90 s)` — avec la note de non-comparabilité.
- Source de sommeil préférée : liste des `dataOrigin` détectées + `Masque accéléro seul`.
- Profil de paramètres : `défaut` / profils enregistrés / `Gérer…`.
- Repère de port : bracelet, trou de serrage, jambe (rappelé au coucher).
- Arrêt automatique : `Sur mise en charge` (défaut) et `Heure limite` (défaut 11:00).

**Appareils**
- Montre : nom, version, batterie, espace, dernier contact. Bouton `Synchroniser maintenant`.
- Health Connect : état des permissions, bouton `Gérer dans Health Connect`.

**Données**
- Espace occupé, `Purger les signaux bruts de plus de 90 jours` (les résultats sont conservés).
- `Journal technique` : liste horodatée des workers, trous, retries HC, erreurs. Copiable.
- `Effacer toutes les données` (double confirmation, saisie du mot `EFFACER`).

**À propos**
- Version d'app, version d'algorithme, `Relire l'avertissement`, sources scientifiques
  (liste de références, hors ligne).

### 2.9 Export d'un rapport pour le médecin

Écran empilé, prévisualisation en haut, options en bas.

Deux formats, cases à cocher :
- **Rapport PDF (1 à 2 pages)** — destiné à être imprimé et lu en consultation.
- **Données CSV** — trois fichiers zippés : `nights.csv`, `events.csv`, `params.csv`.

Options : plage de dates, règle de comptage (les deux peuvent être incluses côte à côte),
inclure le questionnaire (oui/non), inclure les nuits écartées (oui, listées avec leur motif — par défaut
**oui**, parce que masquer les nuits ratées à un médecin est trompeur).

**Contenu imposé du PDF** — cet ordre, ce contenu, en thème clair haute lisibilité :

1. **Bandeau de tête** : `Mesure personnelle par accéléromètre de cheville — n'est pas un examen médical.`
2. Identité minimale : prénom facultatif (champ libre), période, nombre de nuits enregistrées/éligibles.
3. **Résultat** : `PLMI médian 22/h · IC 95 % 14–31 · 6 nuits · règle AASM v3 · masque Health Connect
   (5 nuits) / accéléro (1 nuit)`. PLMW médian. Index de périodicité de Ferri médian.
4. **Graphe de tendance** (vectoriel, §4.2).
5. **Tableau par nuit** : date, heures, TST, source du masque, PLMI, PLMW, PI, nb de séries, drapeaux,
   éligibilité et motif d'exclusion.
6. **Un graphe de nuit + hypnogramme** pour la nuit médiane (§4.1, §4.3), en pleine largeur paysage.
7. **Méthode**, en clair et en six lignes : capteur, fréquence, filtrage, seuil adaptatif calé sur le
   plancher de bruit, règle de série, source du temps de sommeil, version d'algorithme.
8. **Limites**, reprise littérale des quatre limites de l'écran 1/5 de l'accueil.
9. Questionnaire : issue + réponses détaillées, si inclus.

Bouton **Partager** → `Intent.ACTION_SEND` (FileProvider). Pas d'envoi réseau, jamais.
Sous le bouton : `Le fichier reste sur votre téléphone jusqu'à ce que vous le partagiez.`

Si l'export est impossible : le bouton reste visible mais désactivé avec le motif écrit dessus
(`3 nuits minimum`), jamais un bouton actif qui échoue.

---

## 3. Le problème de la représentation du chiffre

### 3.1 Les options

**Option A — le chiffre nu.** `24/h` en gros, par nuit et en tendance.
Avantages : lisible instantanément, aucun calcul, aucune explication.
Défauts rédhibitoires : donne une précision qui n'existe pas ; invite au suivi quotidien, exactement le
comportement que la variabilité inter-nuits rend absurde ; fait franchir un seuil symbolique à 15 un soir
sur trois sans que rien n'ait changé. Sur cette mesure, le chiffre nu est un mensonge par omission.

**Option B — chiffre + intervalle par nuit.** `24/h (18–31)` pour une nuit.
Avantage : a l'air rigoureux.
Défaut : l'intervalle serait fabriqué. Nous n'avons aucun modèle défendable de l'incertitude *à l'intérieur*
d'une nuit — pas de vérité terrain, pas de rééchantillonnage légitime (les événements ne sont pas
indépendants, ils sont périodiques par définition). Un intervalle inventé est pire qu'aucun intervalle :
il déplace la fausse confiance d'un cran sans la supprimer. **Écartée.**

**Option C — bande de confiance sur la tendance multi-nuits.** Points par nuit, médiane de la période, et
intervalle de confiance de cette médiane obtenu par bootstrap sur les nuits.
Avantage décisif : l'incertitude affichée est **mesurée sur les données réelles de l'utilisateur**, et elle
capture la source d'erreur dominante — la variation d'une nuit à l'autre. Elle se resserre naturellement
quand il enregistre plus de nuits, ce qui rend le bon comportement (enregistrer plus) visible et
récompensé sans gamification.
Défaut : demande au moins 3 nuits, idéalement 5. C'est un défaut acceptable : c'est aussi la vérité.

**Option D — catégorie seule.** `Élevé` / `Intermédiaire` / `Faible`.
Avantage : aucune fausse précision, très peu de charge cognitive.
Défauts : le médecin a besoin du nombre ; la frontière à 15/h est précisément le point le plus instable,
donc une catégorie sèche y est aussi arbitraire qu'un chiffre nu ; et une catégorie a un parfum de
diagnostic que le chiffre n'a pas.

### 3.2 Recommandation

**Option C en principal, option D en secondaire et sous condition, option A relégué au détail de nuit.**

Concrètement, la hiérarchie d'affichage est figée :

| Niveau | Contenu | Style | Où |
|---|---|---|---|
| 1 | Médiane de la période | `metricXL` | Tendance, en tête |
| 2 | `IC 95 % : a – b · n nuits` | `body`, secondaire | même carte, ligne suivante |
| 3 | Phrase de position (§3.4) | `body`, encadré | même carte |
| 4 | Nuages de points + bande | graphe | §4.2 |
| 5 | PLMI d'une nuit | `body`, secondaire | détail de nuit, liste, CSV |

L'argument : l'incertitude affichée doit être **estimée, pas décorative**. La bande de confiance sur la
tendance est la seule des quatre options dont le nombre affiché est calculé à partir de données observées.
Elle a aussi la bonne propriété pédagogique : elle rétrécit quand on collecte plus, ce qui apprend à
l'utilisateur — sans lui faire la leçon — que la mesure est une statistique et pas une lecture d'instrument.

**Calcul, à implémenter exactement ainsi :**
```
estimateur      = médiane des PLMI des nuits éligibles de la période
IC              = bootstrap percentile 95 %, 2 000 rééchantillonnages avec remise sur les nuits
                  (graine fixe dérivée de l'ensemble des sessionId → même écran = même IC, reproductible)
dispersion      = MAD des PLMI de nuit × 1,4826  (affichée comme « vos nuits varient de ±X/h »)
arrondi         = médiane et bornes à l'entier ; jamais de décimale sur un PLMI
```
La graine fixe est une exigence d'interface, pas de statistique : un intervalle qui bouge à chaque
recomposition détruit la confiance.

### 3.3 Comportement sous 3 nuits

**Refus, pas avertissement.** Sous 3 nuits éligibles :
- aucune médiane n'est calculée ni stockée ni exportée ;
- le graphe de tendance n'est pas rendu (pas même vide avec des axes : un axe vide invite à imaginer une
  courbe) ;
- aucune catégorie, aucune phrase de position ;
- l'écran affiche l'état de collecte du §2.4 cas A, avec la raison chiffrée (« environ une nuit sur trois »),
  parce que l'utilisateur est technicien et qu'une raison chiffrée est plus convaincante qu'une consigne ;
- l'export est désactivé avec son motif visible ;
- le détail d'une nuit reste entièrement accessible, chiffre compris, pour permettre le contrôle de la
  mesure.

De 3 à 4 nuits : agrégation autorisée, **catégorie interdite**, bandeau « résultat provisoire ».
À partir de 5 nuits : écran complet.

### 3.4 Variation non distinguable du bruit

Cinq phrases de position possibles, générées par une fonction pure
`positionStatement(median, ciLow, ciHigh, n): PositionStatement`. Aucune autre formulation n'existe.

| Condition | Texte |
|---|---|
| `n < 3` | *(pas de phrase — écran de refus)* |
| `n ∈ [3,4]` | `Résultat provisoire sur n nuits. Aucune catégorie n'est proposée avant cinq nuits.` |
| `ciHigh < 15` | `Sous le seuil de 15/h retenu en pratique clinique, y compris en haut de l'intervalle.` |
| `ciLow > 15` | `Au-dessus du seuil de 15/h retenu en pratique clinique, y compris en bas de l'intervalle. À discuter avec un médecin du sommeil.` |
| `ciLow ≤ 15 ≤ ciHigh` | `L'intervalle englobe le seuil de 15/h : avec ces nuits, Pendulum ne peut pas dire de quel côté vous êtes. Enregistrer davantage de nuits resserrera l'intervalle.` |

Toute phrase se termine sans exclamation, sans emoji, sans couleur de fond sémantique (le cadre est neutre
dans les cinq cas — colorer le cadre en rouge quand `ciLow > 15` transformerait une mesure en verdict).

Pour la **comparaison de deux périodes**, la règle est celle du §2.4, avec cet ordre non négociable :
1. verdict de distinguabilité ;
2. les deux estimations avec leurs intervalles ;
3. la différence avec son intervalle ;
4. la dispersion inter-nuits de l'utilisateur, comme étalon du bruit ;
5. le nombre de nuits qu'il faudrait.

Un point supplémentaire, souvent oublié : quand la différence est distinguable, l'app ne dit pas non plus
que le traitement en est la cause. Texte imposé :
`Cette différence dépasse la variabilité habituelle entre vos nuits. Pendulum ne peut pas dire ce qui l'a
causée : un changement de traitement, de sommeil, d'alcool, de fer, de literie ou de position de la montre
produirait le même effet à l'écran.`

---

## 4. Data-viz

Règles communes aux trois graphes :

- **Rendu dans une fonction pure de dessin**, pas dans un composable :
  `fun DrawScope.drawNightChart(spec: NightChartSpec, tokens: ChartTokens)`. Les composables sont des
  enveloppes minces. La même fonction est appelée depuis un `Canvas` Compose (écran) et depuis le canvas
  d'une `PdfDocument.Page` (export) → un seul code de rendu, un seul risque de divergence.
- **Écran = thème sombre. Export = thème clair.** Un PDF sombre est illisible imprimé. Les tokens de graphe
  sont donc paramétrés, jamais codés en dur.
- Épaisseur minimale des traits : 1,5 dp à l'écran, 0,6 pt à l'export. Police des axes : 11 sp / 8 pt.
- Toutes les valeurs numériques en chiffres tabulaires.
- Chaque graphe expose un bouton `Valeurs` qui ouvre un `ModalBottomSheet` contenant le même contenu sous
  forme de tableau. C'est à la fois l'alternative accessible (TalkBack) et le mode « je veux le chiffre
  exact ». Le `contentDescription` du Canvas résume en une phrase :
  `Graphe de la nuit du 12 mars, 412 mouvements détectés entre 23:12 et 06:58. Bouton Valeurs pour le
  tableau.`

### 4.1 Graphe de nuit

**Ce qu'on trace** — six couches, de l'arrière vers l'avant :

1. **Bandes hors sommeil** : rectangles pleins, `surfaceMuted`, sur les intervalles classés éveil/hors-lit
   par le masque actif. Elles disent visuellement « ici, rien n'est compté ».
2. **Trous de signal** : rectangles hachurés (lignes à 45°, 2 dp d'espacement, `outline`), avec la durée
   écrite au-dessus si > 5 s.
3. **Plancher de bruit** : ligne pointillée fine (`dash 2,3`), couleur `textTertiary`.
4. **Seuil d'onset adaptatif** (8 × plancher) : ligne tiretée (`dash 6,4`), couleur `accent` à 55 %.
   Le seuil d'offset (2 ×) n'est pas tracé par défaut — il encombre ; option dans les réglages du graphe.
5. **Enveloppe RMS** : trait plein 1,5 dp, `accent`. Décimation min/max (voir plus bas).
6. **Marqueurs d'événements**, sur une bande dédiée de 14 dp sous l'aire du signal, jamais superposés à la
   courbe :
   - CLM comptés : trait vertical plein 2 dp, `accent` ;
   - CLM exclus posture : croix fine, `textTertiary` ;
   - CLM en éveil (PLMW) : trait vertical creux (contour), `accent` ;
   - séries PLM : rectangle horizontal continu 4 dp sous les marqueurs, `accent` à 35 %, avec le numéro de
     série au-dessus quand le zoom le permet.

**Axe X.** Heure murale locale, du début à la fin de session. Origine et fin figées sur l'heure locale
enregistrée avec son offset UTC — la nuit du changement d'heure affiche une marque `+1 h` ou `−1 h` sur
l'axe plutôt que de mentir sur la durée. Graduations majeures à l'heure ronde (`00:00`, `01:00`), mineures
toutes les 15 min. Zoom horizontal par pincement de ×1 (nuit entière) à ×480 (1 minute visible),
translation à un doigt, double-tap = retour à ×1. Le Y ne zoome jamais.

**Axe Y.** Amplitude **relative au plancher de bruit**, sans dimension : `×1, ×2, ×4, ×8, ×16, ×32`, échelle
**log₂**, plancher de l'axe à ×0,5. Justification : les amplitudes utiles s'étalent de 1,5× à 30× ; en
linéaire, les petits événements sont invisibles, en log ils restent lisibles et le seuil à ×8 devient une
ligne droite constante, ce qui rend la logique du détecteur immédiatement compréhensible. Le titre de l'axe
est écrit en toutes lettres : `amplitude ÷ plancher de bruit (échelle log₂)`. Un interrupteur
`log₂ / linéaire` existe dans les options du graphe ; en linéaire, l'axe part de 0 et le haut n'est jamais
tronqué.
Une valeur au-delà de ×32 n'est pas coupée : l'axe s'étend au ×2 suivant. Si un seul échantillon oblige à
doubler l'échelle, on l'étend quand même et on l'annote (`pic ×61 à 03:12`) — jamais d'écrêtage silencieux.

**Décimation.** 8 h × 50 Hz = 1,44 M échantillons pour ~1 100 colonnes de pixels. Construction d'une
**pyramide min/max** (niveaux successifs de facteur 2, `FloatArray` de paires) une seule fois au chargement,
hors thread principal. Au dessin, on choisit le niveau donnant ≈ 1 paire par colonne et on trace un segment
vertical `min→max` par colonne via `drawPoints(PointMode.Lines, prebuiltFloatArray)`. Jamais de moyenne :
un pic de 40 ms doit survivre à la nuit entière affichée (P5, testé unitairement).

**Interaction.** Appui long ou glissement → curseur vertical + bandeau de valeurs (§2.6). Le curseur est
partagé avec l'hypnogramme. Un tap sur un marqueur ouvre une fiche événement :
`CLM #143 · 02:14:38 · durée 2,4 s · amplitude ×9,2 · plancher 0,0031 g · série 12, position 3/6 · N2`.

### 4.2 Graphe de tendance

Le graphe le plus important. Il doit se lire en deux secondes et supporter l'examen d'un médecin.

**Axe X — calendaire, pas ordinal.** Les nuits sont positionnées à leur date réelle. Une semaine sans mesure
laisse un trou visible ; c'est une information, pas un défaut. Graduations : chaque jour si ≤ 14 jours,
sinon chaque lundi. Libellés `12/03` en format court.

**Axe Y — PLMI, départ à 0 imposé.** Maximum = `max(20, ceil(1,15 × plus haute valeur / 5) × 5)`.
Graduations tous les 5/h.

**Couches** (arrière → avant) :

1. **Ligne de référence 15/h** : tiretée `dash 6,4`, `textTertiary`, libellée à droite `15/h`. Une note en
   légende, hors du graphe : `Seuil retenu en pratique clinique chez l'adulte (ICSD-3).` Les lignes 5/h et
   10/h sont disponibles en option, en pointillé plus léger, non affichées par défaut.
2. **Bande d'IC 95 % de la médiane** : rectangle horizontal couvrant toute la période, `accent` à 14 %,
   bordures haute et basse tiretées 1 dp. C'est la représentation du niveau d'incertitude retenu au §3.
3. **Ligne de médiane** : trait horizontal plein 2 dp, `accent`, sur l'étendue de la période, avec la valeur
   écrite à droite (`22/h`).
4. **Points par nuit** :
   - nuit éligible : disque plein 5 dp, `accent` ;
   - nuit éligible mais masque accéléro seul : disque plein 5 dp `accent` **avec anneau ambre** de 1,5 dp ;
   - nuit écartée : cercle creux 5 dp, `textTertiary`, positionné à sa valeur, **exclu** des calculs — sa
     présence visuelle est délibérée : cacher les nuits ratées fausserait la lecture d'ensemble.
   Les points ne sont **pas** reliés par une ligne. Une polyligne entre nuits suggère une trajectoire
   continue et une causalité qui n'existent pas. Si une tendance monotone est réellement visible, elle le
   sera par la position des points.
5. **Marqueurs d'événements de vie** (facultatif, saisi par l'utilisateur) : trait vertical fin sur toute la
   hauteur + puce en bas (`traitement 150→225 mg`). Utile pour la comparaison de périodes ; jamais
   interprété par l'app.

**Mode comparaison** : deux bandes de médiane + IC côte à côte, séparées par une ligne verticale à la date
pivot, chacune libellée `A` / `B`. La différence est écrite en texte sous le graphe, jamais dessinée comme
une flèche (une flèche vers le bas se lit « ça va mieux »).

**Interaction** : tap sur un point → `ModalBottomSheet` de la nuit (date, PLMI, TST, drapeaux, bouton
`Ouvrir le détail`). Pas de zoom : la période est choisie par le sélecteur, pas par pincement.

### 4.3 Hypnogramme

Placé directement sous le graphe de nuit, même largeur, même transformation X, hauteur 96 dp.

**Voies (de haut en bas)** :

| Voie | Hauteur | Contenu |
|---|---|---|
| Masque accéléro | 12 dp | deux états : `mobile` / `immobile`, barre pleine `textTertiary` sur immobile |
| Hypnogramme HC | 72 dp | 5 niveaux : Éveil, REM, N1, N2, N3 (du haut vers le bas) |
| Désaccord | 6 dp | segments où les deux masques divergent, hachure ambre |

**Y discret**, dans l'ordre conventionnel des laboratoires de sommeil (éveil en haut, sommeil profond en
bas). Tracé en escalier (`drawPath` avec segments horizontaux + verticaux), trait 2 dp, remplissage
optionnel des marches jusqu'à la ligne d'éveil à 10 % d'opacité.

**REM** : c'est le seul stade qui mérite un traitement visuel distinct (peu de PLMS y sont attendus). Il est
distingué par **une hachure diagonale** dans sa marche, pas seulement par une couleur, et par son libellé de
voie. La hachure est dessinée par `clipRect` + boucle de `drawLine`, pas par un `PathEffect` de trait.

**Sans hypnogramme** : la voie HC n'est pas dessinée vide ; elle est remplacée par une bande de la même
hauteur, `surfaceMuted`, portant le texte centré
`Hypnogramme indisponible — masque d'immobilité accéléro utilisé.` La voie « désaccord » disparaît.

**Légende** : sous l'hypnogramme, une ligne de puces avec forme + libellé (jamais de pastilles de couleur
seules). Une ligne de statistiques : `6 h 58 de sommeil · éveil 48 min · REM 1 h 24 · N3 1 h 02 ·
source Samsung Health via Health Connect · accord avec le masque accéléro : κ = 0,71`.

### 4.4 Couleurs, contraste, accessibilité des graphes

- **Mode sombre par défaut** (`isSystemInDarkTheme()` ignoré au premier lancement : Pendulum force le sombre et
  laisse un réglage `Système / Sombre / Clair`). Motif : consultation nocturne et matinale, souvent dans le
  noir.
- **Rôles de couleur des graphes** — quatre teintes maximum, toutes distinguables en deutéranopie et
  protanopie (bleu / ambre / violet / gris ; aucune paire rouge-vert) :

| Rôle | Sombre | Clair | Usage |
|---|---|---|---|
| Donnée principale | `#6FB2FF` | `#1E63C8` | enveloppe, points, médiane |
| Qualité / attention | `#E0A33E` | `#8A5D0B` | drapeaux, désaccord, masque dégradé |
| Second signal | `#8E7BEF` | `#4F3FB8` | REM, seconde règle en superposition |
| Neutre structurel | `#7C8695` | `#666F7D` | plancher, axes, nuits écartées |

- Contrastes vérifiés sur le fond de graphe (`#0E1116` / `#FFFFFF`) : toutes ces valeurs sont à ≥ 4,4:1,
  au-delà de l'exigence de 3:1 pour les éléments graphiques.
- **Redondance obligatoire** : trait plein / tireté / pointillé pour les trois lignes du graphe de nuit ;
  disque plein / disque cerclé / cercle creux pour les trois états de nuit ; position verticale pour les
  stades ; hachure pour REM et pour les trous.
- **Test de recette** : convertir chaque capture en niveaux de gris et vérifier que rien n'est perdu.
- **Échelle de police** : les libellés d'axes restent en `sp` et suivent `fontScale` jusqu'à 1,3 ; au-delà,
  la densité de graduations est réduite de moitié plutôt que de laisser les libellés se chevaucher.
- **TalkBack** : le Canvas est `mergeDescendants` avec une description synthétique + le bouton `Valeurs`
  qui donne le tableau complet, seul chemin réellement utilisable sans vision.

---

## 5. Système visuel

Registre : **sobre et clinique**. Instrument de mesure, pas app de fitness. Aucun score, aucun badge, aucune
série de jours consécutifs, aucune félicitation, aucune illustration, aucun emoji dans l'interface. Le seul
« objectif » que l'app encourage est d'enregistrer assez de nuits, et il est représenté par des pastilles
factuelles, pas par une jauge.

### 5.1 Palette

**Sombre (défaut)**

| Rôle | Hex | Usage |
|---|---|---|
| `background` | `#0E1116` | fond d'écran |
| `surface` | `#161A21` | cartes |
| `surfaceElevated` | `#1E232C` | feuilles modales, menus |
| `surfaceMuted` | `#12161C` | bandes inactives des graphes |
| `outline` | `#2A313C` | bordures de cartes, séparateurs |
| `textPrimary` | `#E6EAF0` | titres, chiffres |
| `textSecondary` | `#A3ADBB` | corps, valeurs secondaires |
| `textTertiary` | `#7C8695` | légendes, axes, désactivé |
| `accent` | `#6FB2FF` | donnée, actions primaires |
| `onAccent` | `#0B1017` | texte sur bouton plein |
| `attention` | `#E0A33E` | qualité dégradée, provisoire |
| `secondSignal` | `#8E7BEF` | REM, seconde règle |
| `error` | `#E5736A` | échec **technique** uniquement |
| `success` | `#5FBF8C` | transfert réussi **uniquement** |

**Clair (et export)**

| Rôle | Hex |
|---|---|
| `background` | `#F7F8FA` |
| `surface` | `#FFFFFF` |
| `surfaceElevated` | `#FFFFFF` |
| `surfaceMuted` | `#EEF1F5` |
| `outline` | `#D8DDE4` |
| `textPrimary` | `#12161C` |
| `textSecondary` | `#4C5663` |
| `textTertiary` | `#666F7D` |
| `accent` | `#1E63C8` |
| `onAccent` | `#FFFFFF` |
| `attention` | `#8A5D0B` |
| `secondSignal` | `#4F3FB8` |
| `error` | `#B3352C` |
| `success` | `#1F7A4D` |

**Règle sémantique stricte** : `error` et `success` ne décrivent jamais un résultat de santé. Un PLMI élevé
n'est pas rouge. Un PLMI bas n'est pas vert. Ces deux couleurs ne servent qu'aux états techniques
(transfert, permission, appairage).

### 5.2 Typographie

Famille : **Roboto Flex** (variable, fournie par le système sur Wear/Pixel), repli `Roboto`. Chiffres
tabulaires activés partout où une valeur peut changer :
`fontFeatureSettings = "tnum"` — obligatoire, sinon les compteurs qui s'incrémentent tressautent.

| Style | Taille / graisse | Interlignage | Usage |
|---|---|---|---|
| `metricXL` | 44 sp / 300 / tnum / −0,5 | 48 | médiane de tendance, **un seul usage dans l'app** |
| `metricL` | 26 sp / 400 / tnum | 32 | valeurs de section |
| `titleL` | 22 sp / 500 | 28 | titres d'écran |
| `titleM` | 17 sp / 500 | 24 | titres de carte, dates |
| `body` | 15 sp / 400 | 22 | texte courant |
| `bodyEmph` | 15 sp / 500 | 22 | premières lignes de verdict |
| `label` | 13 sp / 500 / +0,3 | 18 | puces, en-têtes de tableau |
| `caption` | 12 sp / 400 | 16 | légendes, mentions |
| `mono` | 13 sp monospace | 18 | journal technique, valeurs brutes |

Longueur de ligne des paragraphes explicatifs : bornée à 60 caractères par `widthIn(max = 340.dp)` — les
blocs d'explication sont longs, ils doivent rester lisibles.

### 5.3 Espacement, formes, densité

- Échelle : `4 · 8 · 12 · 16 · 24 · 32 · 48` dp. Rien d'autre. Marge d'écran 16 dp, espace inter-cartes
  12 dp, padding interne de carte 16 dp.
- Rayons : carte 14 dp, champ/puce 10 dp, bouton 10 dp, feuille modale 20 dp en haut.
  **Pas de boutons en gélule** : la forme pilule lit « consommateur ».
- **Pas d'ombres.** En sombre, une carte est définie par `surface` + bordure `outline` 1 dp. En clair,
  bordure 1 dp également, sans élévation. Cela garantit que capture d'écran et PDF se ressemblent.
- Densité : confortable. Hauteur de ligne de liste 72 dp (deux lignes de texte + puces). Cible tactile
  48 dp minimum partout.
- **Aucune animation d'entrée de contenu.** Transitions de navigation : fondu `tween(150)` uniquement, pas
  de glissement. Aucune animation de chiffre qui compte, aucun graphe qui se dessine progressivement — un
  graphe animé lu à 7 h est un graphe illisible, et une courbe qui « monte » raconte une histoire.
  Seuls indicateurs animés autorisés : les barres de progression de transfert et d'analyse.

### 5.4 Material 3 : ce qu'on prend, ce qu'on écarte

**On prend** : `MaterialTheme` comme porteur de tokens, `Scaffold`, `TopAppBar` (variante `small`,
non repliable), `NavigationBar`, `Card`, `ListItem`, `AssistChip`/`FilterChip`, `Slider`,
`SegmentedButton`, `ModalBottomSheet`, `Snackbar`, `LinearProgressIndicator`, `AlertDialog`, les cibles
tactiles de 48 dp, la sémantique d'accessibilité.

**On écarte, et pourquoi :**

| Écarté | Motif |
|---|---|
| **Dynamic color / Material You** | Les couleurs des graphes doivent être identiques d'un appareil à l'autre et entre écran et PDF. Une capture montrée à un médecin ne peut pas dépendre du fond d'écran. |
| **FAB** | Il n'y a aucune action de création sur le téléphone. L'enregistrement démarre sur la montre. |
| **Élévation tonale empilée** | Illisible en sombre, ne survit pas à l'impression. Remplacée par bordure 1 dp. |
| **Boutons en gélule (`fullyRounded`)** | Registre consommateur. Rayon 10 dp. |
| **Badges, compteurs de notification** | Aucun contenu à réclamer d'urgence. |
| **`NavigationBar` à 4-5 items** | Trois destinations suffisent ; plus dilue la hiérarchie. |
| **Ripple prononcé, transitions partagées** | Distraction ; conflit avec « aucune animation ». |
| **Sliders avec valeur en bulle flottante** | Les paramètres de l'algorithme s'affichent en clair sous le slider, avec unité. |
| **Icônes remplies (`Filled`)** | Jeu `Outlined` uniquement, plus sobre et plus léger visuellement. |

---

## 6. Interface de la montre

### 6.1 Écran unique

Un seul `RecordScreen`, pas de navigation, pas de swipe, pas de liste, pas de complication.
Compose for Wear (`androidx.wear.compose.material`), fond noir pur `#000000` (OLED : le noir n'allume pas
le pixel — c'est de l'autonomie, pas du style).

**État ARRÊTÉ** — bouton plein écran :
```
        ┌─────────────────┐
        │                 │
        │     DÉMARRER    │      Chip circulaire ou bouton
        │                 │      hauteur 88 dp, texte 20 sp
        └─────────────────┘

        Batterie 98 %
        Libre 1,2 Go
        Cheville droite · 4e trou
```
Si batterie < 60 % : ligne en ambre, `Batterie 54 % — insuffisant pour une nuit complète.`
Le bouton reste actif : c'est l'utilisateur qui décide.

**État ENREGISTREMENT** :
```
        03:14                     durée, 28 sp tabulaire

        564 300 échantillons
        50,2 Hz  ·  0 trou
        Batterie 71 %
        Mode FIFO groupé 20 s
        Porté ✓

        ┌─────────────────┐
        │      ARRÊTER    │      bouton secondaire, contour
        └─────────────────┘
```
L'arrêt demande une confirmation (`Arrêter l'enregistrement ?` / `Arrêter` / `Continuer`) : un appui
accidentel à 3 h du matin perd la nuit.

**Rafraîchissement : 30 s exactement**, et uniquement quand l'écran est allumé
(`LifecycleEventObserver` sur `ON_RESUME`/`ON_PAUSE`). Aucune recomposition en ambient.

### 6.2 Erreurs sur la montre

Textes courts (l'écran est petit et l'utilisateur est couché), toujours actionnables :

| Situation | Texte |
|---|---|
| Espace insuffisant | `Espace insuffisant. Il reste 60 Mo, il en faut 300. Synchronisez depuis le téléphone.` |
| Accéléromètre indisponible | `Capteur inaccessible. Redémarrez la montre.` |
| Permission refusée | `Autorisation Activité physique requise. Réglages › Applications › Pendulum.` |
| Trous répétés | `Signal interrompu 3 fois. Passage en mode continu — la batterie va baisser plus vite.` |
| Service tué et relancé | `Enregistrement repris après une interruption de 2 min.` |
| Reprise après redémarrage | `La montre a redémarré. Enregistrement repris, un trou est marqué.` |
| Arrêt automatique | `Enregistrement arrêté : montre sur le chargeur à 07:04.` |

### 6.3 Pourquoi aucune animation

Trois raisons, dans l'ordre d'importance :

1. **Énergie.** Chaque recomposition réveille le SoC. Sur une nuit de 8 h, une animation à 60 fps même
   discrète coûte plus cher que l'acquisition à 50 Hz elle-même. La cible d'autonomie (> 20 % après 8 h)
   ne survit pas à une interface animée.
2. **Contamination de la mesure.** Une interface qui invite à regarder l'écran invite à bouger la jambe.
   L'écran de la montre est à la cheville : le regarder implique de se pencher, donc de produire un
   artefact. L'écran doit être ennuyeux exprès.
3. **Aucun bénéfice.** Il n'y a rien à surveiller en temps réel. Les chiffres affichés sont des contrôles
   de bon fonctionnement, lus deux fois par nuit au maximum.

Corollaires : pas de `AnimatedVisibility`, pas de `animate*AsState`, pas d'indicateur de progression
indéterminé qui tourne, pas de vibration en dehors de la confirmation d'arrêt (un `HapticFeedback` court
au démarrage et à l'arrêt — utile parce qu'on ne voit pas forcément l'écran à la cheville).

---

## 7. États d'erreur et textes exacts

Format imposé de tout message : **titre neutre** (ce qui s'est passé) + **une phrase de cause** +
**une phrase d'action** + **bouton d'action** quand une action existe. Jamais « Oups », jamais
« Une erreur est survenue », jamais de code seul.

Chaque erreur porte un identifiant stable, présent dans le journal technique et affiché en `caption`
tertiaire sous le message (l'utilisateur est technicien : le code lui sert).

### Nuit et mesure

**`E-NIGHT-01` — Aucune donnée de sommeil**
```
Nuit du 12 mars — pas de stades de sommeil
Aucune session de sommeil couvrant cette nuit n'a été trouvée dans Health Connect,
même 36 h après le réveil. Votre montre de poignet ne l'a peut-être pas enregistrée
ou pas synchronisée.
Pendulum a utilisé son propre masque d'immobilité. Le résultat est conservé et compte
dans la tendance, mais il est marqué « masque accéléro » : le temps de sommeil est
alors estimé, pas mesuré.
                                        [ Réessayer ]   [ Choisir une autre source ]
```

**`E-NIGHT-02` — Nuit trop courte**
```
Nuit du 12 mars — écartée, 2 h 10 de sommeil
Le calcul du nombre de mouvements par heure exige au moins 4 h de sommeil : sur une
durée plus courte, quelques mouvements groupés suffisent à faire exploser le chiffre.
Cette nuit reste consultable dans le détail, mais elle n'entre pas dans la tendance.
Rien à faire.
```

**`E-NIGHT-03` — Montre déchargée en cours de nuit**
```
Nuit du 12 mars — enregistrement interrompu à 03:41
La montre s'est arrêtée à 8 % de batterie, après 4 h 29 d'enregistrement sur les
7 h 40 passées au lit.
La partie enregistrée est analysée et donne 4 h 12 de sommeil, au-dessus du minimum
de 4 h : cette nuit est donc conservée, avec le drapeau « nuit partielle ». Les
mouvements se concentrant plutôt en deuxième moitié de nuit, le chiffre est
probablement sous-estimé.
Chargez la montre à 100 % avant le coucher.
                                                             [ Voir le détail ]
```
Si la partie enregistrée est sous 4 h, le message bascule sur `E-NIGHT-02` et la nuit est écartée.

**`E-NIGHT-04` — Trous dans le signal**
```
Nuit du 12 mars — signal interrompu
Le capteur a cessé d'envoyer des données pendant 4 min 12 au total, dont une
coupure de 2 min 30 à 02:14. Cela arrive quand le système suspend la montre plus
profondément que prévu.
94,1 % de la nuit est exploitable — sous le seuil de 97 %. La nuit est écartée de la
tendance pour ne pas la biaiser, et reste consultable.
Si cela se reproduit, forcez le mode continu dans Réglages › Mesure. La batterie
tiendra moins longtemps.
                                        [ Forcer le mode continu ]   [ Détail ]
```
Sous 2 min de trous cumulés et plus grand trou < 5 s, la nuit reste éligible et le drapeau est purement
informatif (`trou 47 s`), sans carte d'erreur.

**`E-NIGHT-05` — Montre restée sur la table**
```
Nuit du 12 mars — la montre ne semble pas avoir été portée
Le capteur hors-corps signale « non porté » sur 87 % de la nuit, et le signal est
resté plat : 3 mouvements détectés en 7 h, avec un plancher de bruit dix fois plus
bas que celui de vos autres nuits.
Cette nuit est écartée. Un enregistrement fait sur une table de nuit produit un
PLMI proche de zéro qui tirerait votre tendance vers le bas.
Vérifiez que la montre est bien serrée à la cheville avant de démarrer.
                                              [ Garder quand même comme contrôle ]
```
L'option « garder comme contrôle » existe parce que c'est exactement le contrôle négatif prévu par le
protocole de validation : la nuit est alors étiquetée `contrôle négatif` et reste hors tendance.

**`E-NIGHT-06` — Signal saturé / bracelet desserré**
```
Nuit du 12 mars — amplitudes anormalement élevées
Le plancher de bruit est 4 fois supérieur à celui de vos autres nuits et 214
mouvements dépassent le seuil, contre 40 en moyenne. Un bracelet desserré ou un
autre point de serrage produit exactement cette signature.
La nuit est écartée. Le seuil de détection s'adapte au bruit, mais pas à un
changement de mécanique.
Reprenez le repère habituel : cheville droite, 4e trou.
```

**`E-NIGHT-07` — Transfert incomplet**
```
Nuit du 12 mars — transfert incomplet
15 fichiers sur 17 ont été reçus. Le Bluetooth a été coupé pendant le transfert.
Les fichiers manquants sont toujours sur la montre : ils ne sont supprimés
qu'après réception confirmée.
Rapprochez le téléphone de la montre et relancez.
                                                       [ Reprendre le transfert ]
```

**`E-NIGHT-08` — Décalage d'horloge**
```
Nuit du 12 mars — décalage entre la montre et le téléphone
Les horodatages de la montre dérivent de 3 min 40 par rapport au téléphone. Le
croisement avec l'hypnogramme serait décalé d'autant, ce qui déplacerait des
mouvements d'un stade de sommeil à l'autre.
Les mouvements sont comptés normalement ; la répartition par stade est masquée pour
cette nuit. Redémarrez la montre pour resynchroniser son horloge.
```

### Appareils et permissions

**`E-PAIR-01` — Montre introuvable**
```
Montre introuvable
Aucun appareil Wear OS avec Pendulum n'a répondu en 30 secondes.
Vérifiez que la montre est appairée dans l'app Watch, que le Bluetooth est activé,
et que Pendulum est bien installé sur la montre.
                                                              [ Chercher à nouveau ]
```

**`E-HC-01` — Aucune source de sommeil**
```
Aucune source de sommeil trouvée
Health Connect ne contient aucune session de sommeil sur les 7 derniers jours.
Aucune app n'y écrit vos nuits, ou la permission est refusée.
Vous pouvez continuer : Pendulum estimera le sommeil à partir de l'immobilité mesurée à
la cheville. Le résultat sera moins précis et chaque nuit concernée sera marquée.
                            [ Ouvrir Health Connect ]   [ Continuer sans hypnogramme ]
```

**`E-HC-02` — Permission révoquée**
```
Accès au sommeil révoqué
La permission de lecture du sommeil a été retirée à Pendulum. Les nuits déjà analysées
sont conservées ; les nouvelles utiliseront le masque accéléro.
                                                     [ Rétablir la permission ]
```

**`E-HC-03` — Sources multiples en conflit**
```
Deux sources de sommeil pour cette nuit
Samsung Health indique 6 h 58 de sommeil, Sleep as Android 7 h 24. Pendulum ne peut pas
les combiner sans risquer de compter deux fois les mêmes minutes.
La source préférée (Samsung Health) a été utilisée.
                                                     [ Changer la source préférée ]
```

### Analyse et stockage

**`E-ANA-01` — Analyse échouée**
```
Analyse impossible
Le signal de la nuit du 12 mars n'a pas pu être reconstitué : 9 fichiers sur 17
ont un contrôle d'intégrité invalide.
Les données brutes sont conservées. Vous pouvez relancer l'analyse ; si elle échoue
à nouveau, exportez le journal technique.
                            [ Relancer l'analyse ]   [ Exporter le journal ]
```

**`E-STO-01` — Espace insuffisant sur le téléphone**
```
Espace insuffisant
Il reste 120 Mo sur le téléphone ; une nuit en occupe environ 300 Mo pendant
l'analyse.
Purgez les signaux bruts anciens : les résultats et les graphes sont conservés,
seul le signal brut est supprimé.
                        [ Purger les signaux de plus de 90 jours ]  ( libère 2,1 Go )
```

**`E-EXP-01` — Export impossible**
```
Export indisponible — 3 nuits minimum
La période sélectionnée contient 2 nuits éligibles. Un rapport bâti sur moins de
trois nuits induirait en erreur le médecin qui le lit.
Élargissez la période ou enregistrez une nuit supplémentaire.
```

### Règle transversale

Aucun message n'utilise le mot « erreur » quand rien n'est cassé. Une nuit sans hypnogramme, une nuit courte,
une nuit non portée sont des **situations**, pas des pannes : elles s'affichent en `attention` (ambre) et
non en `error` (rouge). Le rouge est réservé à ce qui est réellement cassé : transfert, permission, intégrité
de fichier, stockage.

---

## 8. Implémentation Compose

### 8.1 Arborescence des composables — module `phone`

```
com.pendulum.phone.ui
├─ PendulumApp()                                   NavHost + NavigationBar + Snackbar host
├─ theme/
│   ├─ PendulumTheme(darkTheme, content)           ColorScheme M3 + PendulumColors + PendulumTypography
│   ├─ PendulumColors                              data class des rôles hors M3 (attention, secondSignal…)
│   ├─ ChartTokens                             couleurs/épaisseurs/dash des graphes, screen|print
│   ├─ LocalPendulumColors / LocalChartTokens      CompositionLocal
│   └─ PendulumShapes, PendulumTypography, Spacing
├─ common/
│   ├─ PendulumScaffold(title, actions, content)
│   ├─ PendulumCard(content)                       surface + bordure 1dp, pas d'ombre
│   ├─ SectionHeader(text)
│   ├─ MetricHeadline(value, unit, interval, nightCount, statement)   ← seul usage de metricXL
│   ├─ InlineValue(label, value, note)
│   ├─ QualityChip(flag)                       AssistChip + icône Outlined
│   ├─ QualityChecklist(items)                 section 4 du détail de nuit
│   ├─ BlockingState(title, body, footer)      refus < 3 nuits, comparaison impossible
│   ├─ ErrorCard(error: PendulumError)             titre + cause + action + code (§7)
│   ├─ StatusStrip(state: WakeState)           §2.3, un état à la fois
│   ├─ DataTableSheet(columns, rows)           alternative accessible des graphes
│   └─ ConfirmDialog(...)
├─ onboarding/
│   ├─ OnboardingPager()
│   ├─ DisclaimerPage()   RequirementsPage()   PairingPage()
│   ├─ SleepSourcePage()  NotificationsPage()
├─ trend/
│   ├─ TrendScreen(state)                      écran de départ
│   ├─ TonightCard(state)                      visible 20h–4h uniquement
│   ├─ CollectionProgress(done, required)      pastilles pleines/vides
│   ├─ TrendChart(spec, onNightClick)          ← composant non trivial n°2
│   ├─ PeriodPicker(...)
│   └─ compare/ ComparePeriodsScreen(), ComparisonVerdict(result)
├─ nights/
│   ├─ NightListScreen(...)  NightRow(...)
│   └─ detail/
│       ├─ NightDetailScreen(...)
│       ├─ NightSignalChart(spec, transform, onCursor)   ← composant non trivial n°1
│       ├─ Hypnogram(spec, transform, onCursor)          ← composant non trivial n°3
│       ├─ CursorReadout(sample)
│       ├─ EventBreakdown(...)  EventTableSheet(...)
│       └─ ParamsPanel(profile, onRecompute)
├─ quiz/  ScreeningQuizScreen(), QuestionPage(), QuizOutcome()
├─ settings/ SettingsScreen(), DeviceSection(), DataSection(), LogScreen()
└─ export/ ExportScreen(), ExportPreview(), ReportRenderer (hors Compose, PdfDocument)
```

**État** : un `ViewModel` par écran, exposant un unique `StateFlow<XxxUiState>` scellé
(`Loading | Blocked(reason) | Ready(data) | Failed(error)`). Les composables d'écran ne prennent que
`state` + lambdas ; aucun accès Room/Health Connect depuis l'UI. Les `Spec` de graphe sont calculés dans le
ViewModel (donc testables sans écran) : le composable ne fait que dessiner.

### 8.2 Thème

```kotlin
@Composable
fun PendulumTheme(
    mode: ThemeMode = ThemeMode.Dark,     // défaut sombre, pas isSystemInDarkTheme()
    render: RenderTarget = RenderTarget.Screen,
    content: @Composable () -> Unit,
) {
    val dark = mode == ThemeMode.Dark ||
               (mode == ThemeMode.System && isSystemInDarkTheme())
    val pendulum = if (dark) PendulumColors.Dark else PendulumColors.Light
    CompositionLocalProvider(
        LocalPendulumColors provides pendulum,
        LocalChartTokens provides ChartTokens.of(pendulum, render),
        LocalSpacing provides Spacing,
    ) {
        MaterialTheme(
            colorScheme = pendulum.toMaterialScheme(),   // pas de dynamicColorScheme()
            typography  = PendulumTypography,
            shapes      = PendulumShapes,
            content     = content,
        )
    }
}
```

`RenderTarget.Print` force la palette claire et des épaisseurs en points : l'export réutilise exactement les
mêmes fonctions de dessin.

### 8.3 Les trois composants non triviaux

Principe commun, à respecter pour les trois : **le dessin est une fonction d'extension de `DrawScope`, pas
un composable.** Cela permet de l'appeler depuis un `Canvas` Compose et depuis un canvas de PDF, et de le
tester par capture d'image.

```kotlin
fun DrawScope.drawNightChart(spec: NightChartSpec, t: ChartTokens, x: XTransform)
fun DrawScope.drawHypnogram(spec: HypnogramSpec, t: ChartTokens, x: XTransform)
fun DrawScope.drawTrendChart(spec: TrendChartSpec, t: ChartTokens)
```

`XTransform` (scale + offset + bornes, hissé dans le parent) est partagé entre graphe de nuit et
hypnogramme : c'est ce qui garantit l'alignement des axes et du curseur.

#### (1) `NightSignalChart` — 1,44 M points

**Structure de données.** Une `EnvelopePyramid` construite une fois, hors thread principal, dans le
ViewModel :

```kotlin
class EnvelopePyramid(base: FloatArray) {          // enveloppe RMS à fs mesuré
    // niveau k : minmax[k] = FloatArray(2 * ceil(n / 2^k)), [min0, max0, min1, max1, …]
    val levels: List<FloatArray>                    // ~19 niveaux pour 1,44 M
    fun levelFor(columnCount: Int): Int
    fun readInto(level: Int, from: Int, to: Int, out: FloatArray)
}
```
Construction ascendante : le niveau k+1 agrège les paires du niveau k par `min`/`max`. Coût O(2n),
mémoire ≈ 2 × la base en `Float` (≈ 11 Mo pour 8 h — acceptable, sinon `ShortArray` quantifié en log₂).

**Dessin.** Pour `w` colonnes de pixels, on choisit `level = levelFor(w)`, on remplit un `FloatArray`
pré-alloué (`remember`) de `4 × w` coordonnées `x, yMin, x, yMax`, et on appelle une fois
`drawPoints(points, PointMode.Lines, color, strokeWidth)`. **Zéro allocation par frame**, zéro `Path` pour
la couche la plus lourde.

Plancher de bruit et seuil (échantillonnés à ~1 Hz, quelques milliers de points) : `Path` reconstruit
uniquement quand `XTransform` change, mémorisé par `remember(spec, transform)`.

Marqueurs d'événements : `drawLine` par événement visible, avec un index trié par temps et une recherche
binaire sur la fenêtre visible — jamais de boucle sur les 412 événements complets.

Texte des axes : `rememberTextMeasurer()` + cache `Map<String, TextLayoutResult>` mémorisé (mesurer du texte
par frame est le premier goulot d'un Canvas Compose).

**Gestes.**
```kotlin
Modifier.pointerInput(Unit) {
    detectTransformGestures { centroid, pan, zoom, _ ->
        transform.applyPinch(centroid.x, pan.x, zoom)   // X uniquement, clampé 1f..480f
    }
}.pointerInput(Unit) {
    detectTapGestures(
        onDoubleTap = { transform.reset() },
        onTap = { hitTestEvent(it)?.let(onEventClick) },
    )
}.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { onCursor(transform.timeAt(it.x)) },
        onDrag = { c, _ -> onCursor(transform.timeAt(c.position.x)) },
    )
}
```
`XTransform` est un `@Stable` avec des `mutableFloatStateOf` : seule la couche Canvas est invalidée, pas la
hiérarchie.

#### (2) `TrendChart` — peu de points, forte charge sémantique

Peu de données (≤ 60 nuits), donc pas de problème de performance : tout le travail est dans la justesse.

- Bornes Y calculées par une fonction pure testée : `yMax = max(20f, ceil(1.15f * maxValue / 5f) * 5f)`,
  `yMin = 0f` **en dur, non paramétrable** (P5 — c'est une invariante du produit, pas une option).
- Bande d'IC : `drawRect` translucide + deux `drawLine` tiretées via
  `PathEffect.dashPathEffect(floatArrayOf(6f, 4f))`.
- Points : `drawCircle` plein / `drawCircle(style = Stroke(1.5.dp))` pour les nuits écartées /
  plein + `drawCircle(style = Stroke)` en `attention` pour le masque accéléro.
- **Aucune polyligne entre points** — l'absence de `Path` reliant les points est une décision de conception
  à commenter dans le code, sinon un futur contributeur « corrigera » ce qu'il prendra pour un oubli.
- Position X : `x = padStart + (date - firstDate) / (lastDate - firstDate) * plotWidth`, donc calendaire :
  les nuits manquantes creusent un vide visible.
- Hit test : recherche du point le plus proche en distance euclidienne, rayon d'acceptation 24 dp, retour
  du `sessionId` au callback.

#### (3) `Hypnogram` — escalier discret, deux voies, alignement strict

- Cinq lanes de hauteur égale ; `laneY(stage) = top + stageOrder(stage) * laneHeight`.
  `stageOrder` : `AWAKE=0, REM=1, LIGHT/N1=2, N2=3, DEEP/N3=4`.
- Chemin en escalier construit **une seule fois par jeu de données** (`remember(spec)`), en coordonnées
  normalisées `0..1` sur X, puis transformé au dessin par `withTransform { scale/translate }` — le zoom ne
  reconstruit jamais le `Path`.
- Hachure REM : `clipRect(rect)` autour de chaque marche REM puis boucle
  `for (i in -h..w step 6.dp) drawLine(from = Offset(i, h), to = Offset(i + h, 0f))`. Un `PathEffect` sur le
  trait ne produit pas un remplissage hachuré ; il faut clipper et boucler.
- Voie « masque accéléro » (12 dp) et voie « désaccord » (6 dp) dessinées comme des suites de `drawRect`
  fusionnés : on pré-fusionne les intervalles contigus dans le `Spec` pour éviter des milliers de
  rectangles de 30 s.
- Le curseur est le même objet que celui du graphe de nuit ; l'hypnogramme ne gère aucun geste, il reçoit
  `cursorTimeMs` en paramètre. Un seul propriétaire de l'interaction évite les désalignements.

#### Tests attendus sur ces trois composants

- `EnvelopePyramidTest` : un pic d'un échantillon survit à tous les niveaux (P5).
- `TrendScaleTest` : `yMin == 0` quelles que soient les données ; `yMax ≥ 20`.
- `PositionStatementTest` : les cinq branches du §3.4, dont les bornes exactes `ciLow == 15`.
- `BootstrapCiTest` : graine fixe → même intervalle sur 100 exécutions.
- Tests de capture (Roborazzi ou `captureToImage`) sur les trois graphes, en sombre et en clair, avec un jeu
  de données de référence ; comparaison en niveaux de gris pour valider P6.

---

## 9. Ce qui reste à trancher

- **Le format du PDF** suppose que le médecin acceptera un document non standard. À valider auprès de lui
  avant d'investir dans la mise en page fine.
- **La pyramide min/max en `Float`** coûte ~11 Mo de tas par nuit ouverte. Si le profilage montre une
  pression mémoire, quantifier en `ShortArray` (log₂ × 512) ; ne pas optimiser avant mesure.
- **Le nombre de nuits nécessaires** affiché en comparaison repose sur une simulation à partir de la
  dispersion observée, elle-même estimée sur peu de nuits. C'est un ordre de grandeur, et le texte doit le
  dire — vérifier qu'il n'est jamais lu comme une promesse.
