# SPÉCIFICATION ALGORITHMIQUE v2 — Détecteur PLMS par accéléromètre de cheville 50 Hz

Module `algo`. Fonctions pures, zéro I/O, `fs` explicite. Remplace la section « Module algo » de
[`SPEC-v1.md`](SPEC-v1.md), qui est historique.

> **Où ce document fait foi, et où il ne fait plus foi.** Le **§6, tableau des paramètres finaux**,
> est la référence : le code cite ces tables par leur nom, et les valeurs y survivent au mot près.
> Le **§1** et le **§2** portent le raisonnement qui les produit, et c'est ce qui n'existe nulle part
> ailleurs.
>
> En revanche, **ce document précède les mesures**. Le §5.5 énonce des seuils de non-régression à
> asserter ; ils ont depuis été écrits, exécutés, et l'un d'eux a été retourné par ce qu'il a montré.
> **L'état mesuré est dans [`../07-validation.md`](../07-validation.md) §3 et §4**, qui dit combien
> de tests passent, ce qu'a coûté leur passage, et ce que la mesure falsifie ici même. Deux
> divergences connues sont listées à l'endroit où elles se posent, aux §5.5 et §6.1.
>
> **Ce document n'a pas de section sur l'estimateur de rythme.** La métrique suivie par le produit a
> changé après lui ([`SPEC-v2.md`](SPEC-v2.md) §5) : c'est désormais la période fondamentale en
> secondes, obtenue par déconvolution harmonique, et sa spécification vit dans
> [`../03-algorithm.md`](../03-algorithm.md) §6, ses paramètres au §8.7. Rien ici n'en parle, et
> c'est le plus gros trou du fichier.
>
> Pour la mise en œuvre côté montre et le transfert, voir [`ARCHI-CAPTURE.md`](ARCHI-CAPTURE.md).
> Pour la bibliographie, [`../references.md`](../references.md) — seule liste du dépôt.

---

## 0. Résumé exécutif — ce qui change par rapport à la v1

Trois constats de la revue de littérature invalident des choix de la v1, et un quatrième déplace le centre de gravité du problème.

**(a) Le contenu spectral d'un PLMS accélérométrique est sub-6 Hz, pas 10–15 Hz.** La v1 spécifie un générateur synthétique à « paquets 10-15 Hz fenêtrés Gauss ». C'est physiquement faux et cela aurait produit un détecteur validé contre un fantôme. Toutes les bandes publiées encadrent 0,1–20 Hz avec l'énergie utile en bas : Athavale et al. (SLEEP 2019) obtiennent 87,9 % Se / 94,1 % Sp pour PLMI ≥ 15 avec un **passe-bas** à 0,4 Hz (bande d'arrêt 1,6 Hz) ; Gschliesser 2009 montre que l'Actiwatch, seul dispositif à passer-haut à 3 Hz, **sous-compte massivement** (PLMI 21,2 ± 25,6 vs 34,4 ± 30,7 en PSG, p < 0,001) alors que le PAM-RL (coude 0,3 Hz) sur-compte (63,6 ± 39,3 vs 37,0 ± 33,5, p = 0,009).

**(b) La fenêtre RMS de 0,15 s est un bug, pas un paramètre.** Elle est reprise de S-PLMAD, qui opère sur de l'EMG à 512 Hz dans une bande 10–300 Hz. Pour un signal utile à 1–5 Hz, 0,15 s = 7,5 échantillons ne moyennent même pas une demi-période d'une composante à 2 Hz. L'enveloppe ondule à 2f avec une profondeur de modulation proche de 100 %, le comparateur bat autour du seuil et **fragmente un CLM unique en plusieurs**. Aucun des deux relecteurs ne l'a vu.

**(c) Le choix du masque de sommeil domine toutes les autres sources d'erreur.** Le seul travail comparant poignet et cheville sur le même sujet (n = 29 enfants, PMC12215244) donne, pour le TST vs PSG : Cole-Kripke à la cheville **+43 min [20, 66]** (contre −8 min [−28, 13] au poignet), van Hees/GGIR à la cheville **−89 min [−116, −63]**. Sur une nuit de 420 min, cela déplace le PLMI de **−9,3 %** dans un cas et **+26,8 %** dans l'autre, soit un écart de **36 % entre deux choix d'algorithme de masque également défendables**. C'est plus que tout ce que le détecteur lui-même peut gagner ou perdre. La v1 traitait le masque comme un accessoire ; c'est le premier poste de budget d'erreur.

**(d) L'accéléromètre ne mesure pas la même grandeur que l'EMG.** Terrill et al. (EMBC 2013, PMID 24111321) : **39,0 %** des mouvements scorés à l'EMG (et 54,9 % de ceux scorés au piézo) ne s'accompagnent d'**aucun** mouvement détectable en accélérométrie. Ce n'est pas une erreur de l'algorithme, c'est de la mécanique : une dorsiflexion pure de la cheville ne déplace pas un capteur situé au-dessus de l'axe articulaire. Conséquence de conception : le PLMI accélérométrique est **une échelle différente** du PLMI EMG, pas un estimateur bruité de celui-ci. Le seuil de 15/h de l'ICSD-3 n'est pas transposable tel quel, et la vérité terrain synthétique doit porter **deux jeux d'étiquettes** (`emgTruth` / `accelTruth`).

S'ajoutent quatre erreurs de règle clinique dans la v1, détaillées en §1.5, et une circularité structurelle du PLMI traitée en §3.6.

---

## 1. Critique argumentée

### 1.1 Proposition 1 — plancher absolu : **RETENUE**, avec une valeur différente et un raisonnement corrigé

**Confirmée sur le principe, avec une justification que le relecteur n'a pas donnée.** Le calcul :

- Densité de bruit typique d'un MEMS basse consommation : 150–300 µg/√Hz. Prenons 200 µg/√Hz.
- Bande utile 0,5–8 Hz → BW = 7,5 Hz → bruit RMS par axe = 200 µg × √7,5 = **548 µg**.
- Bruit de quantification du format : LSB = 1/2048 g = 488 µg, RMS = LSB/√12 = 141 µg réparti sur 25 Hz, soit 28,2 µg/√Hz → **87 µg** dans la bande. Négligeable devant l'analogique : **la résolution du format n'est pas le facteur limitant**, c'est un bon point de la v1 à conserver tel quel.
- Norme L2 de trois axes gaussiens i.i.d. → loi de Maxwell : E[‖v‖] = 2σ√(2/π) = **1,596 σ**, soit ≈ 875 µg.
- Seuil onset à 8× ce plancher = **7,0 mg**.

Or les seuils de détection publiés à la cheville sont : Sicbaldi et al. (Sci Rep 2025, Axivity AX6, 100 Hz, 0,1–10 Hz) **15 mg** ; brevets NeuroMetrix US9731126/US10335595 (50 Hz, passe-haut 0,5 Hz — la configuration la plus proche de la nôtre) **20/30 mg** ; Actiwatch **50 mg** ; PAM-RL (Sforza et al. 2005, 40 Hz, 0,3–20 Hz) **200 mg à l'onset / 100 mg à l'offset**. Le seuil purement relatif est donc **2 à 30 fois trop bas** dans le silence. Critique confirmée.

**Mais le raisonnement du relecteur est faux dans le détail.** Le facteur 8 n'est pas « hypersensible » : pour une enveloppe de type Rayleigh, P(E > 8·médiane) = exp(−0,693 × 64) ≈ e⁻⁴⁴ — strictement zéro faux positif thermique sur une nuit. Le problème n'est pas le facteur, c'est que **l'estimateur du plancher s'effondre vers le plancher thermique** quand la nuit est mécaniquement silencieuse, et que le produit 8 × (plancher effondré) tombe sous le seuil de plausibilité physique. La correction porte donc sur le plancher, pas sur le facteur.

**Valeur retenue : Θ_abs = 0,020 g, plage 0,010–0,050 g** — et non 0,05 g comme proposé. Justification : 50 mg est le *seuil de sensibilité* de l'Actiwatch, le dispositif qui sous-compte de 38 % ; 15–30 mg est la fourchette des systèmes qui fonctionnent (Sicbaldi, NeuroMetrix). Un plancher à 50 mg tuerait les petits CLM. Sicbaldi mesure par ailleurs une magnitude typique de mouvement pendant le sommeil de **377 ± 63 mg** — un plancher à 20 mg laisse un rapport dynamique de 19:1, un plancher à 50 mg le réduit à 7,5:1.

**Formule retenue** (trois termes, pas deux) :

```
Θ_on(t) = max( k_on · floor(t) ,  Θ_abs ,  f_cal · gainCal )
```

Le troisième terme est la normalisation inter-nuits (§3.3). Le relecteur n'en a pas parlé, et c'est pourtant le terme qui rend le PLMI comparable d'une nuit à l'autre.

### 1.2 Proposition 2 — passe-bande 0,5–10 Hz : **RETENUE, mais pour une autre raison que celle avancée, et avec une réserve**

**Le coude haut est le meilleur des deux arguments, et le relecteur ne l'a pas justifié.** Passer de « pas de passe-bas » (bande 0,5–25 Hz, BW 24,5 Hz) à un passe-bas à 8 Hz (BW 7,5 Hz) réduit le bruit RMS en bande d'un facteur √(24,5/7,5) = **1,81**, soit **+5,1 dB de SNR gratuits**, sans toucher au signal utile (§5.1 : le pic spectral d'un CLM est à 1,6–5,3 Hz). C'est acquis, sans contrepartie. Je retiens **8 Hz** plutôt que 10 (plage 6–12 Hz) ; le gain supplémentaire de 10 → 8 Hz est de 0,6 dB, marginal, mais 8 Hz reste à ≥ 1,5× le pic spectral du CLM le plus rapide (T_rise = 0,15 s → 5,3 Hz).

**Le coude bas : critique juste sur la forme, mais la prémisse n'est pas vérifiée.** Aucune publication ne quantifie l'amplitude de l'artefact respiratoire **à la cheville** — la littérature ne le fait que pour le thorax, et marginalement le poignet. Le relecteur postule un problème dont l'existence n'est pas établie à ce site de mesure. Cela dit, le remède est presque gratuit :

| Filtre | \|H\| à 0,25 Hz (respiration) | \|H\| à 0,7 Hz (CLM lent) | Sonnerie sur échelon de gravité |
|---|---|---|---|
| Butterworth HP ordre 2, fc = 0,3 Hz (v1) | 0,570 (−4,9 dB) | 0,984 | ≈ 2 s |
| **Butterworth HP ordre 2, fc = 0,5 Hz (retenu)** | **0,243 (−12,3 dB)** | **0,891** | **≈ 2 s** |
| Butterworth HP ordre 4, fc = 0,5 Hz | 0,062 (−24,1 dB) | 0,968 | ≈ 4 s |

Passer de l'ordre 2 à 0,3 Hz à l'ordre 2 à 0,5 Hz gagne **7,4 dB (facteur 2,35)** de réjection respiratoire pour −0,9 dB sur une composante de CLM à 0,7 Hz. C'est un bon échange.

> **Correction du 2026-07-31.** La colonne 0,7 Hz portait **0,915** (fc = 0,5 Hz) et **0,982** (fc = 0,3 Hz), et l'échange était annoncé à −0,6 dB. Le module exact d'un passe-haut Butterworth d'ordre 2 vaut `(f/fc)² / √(1 + (f/fc)⁴)`, soit `1,96/2,2004 = 0,891` et `5,4444/5,5355 = 0,984` ; l'échange réel est de −0,9 dB. La colonne 0,25 Hz et toute la ligne d'ordre 4 étaient exactes. La conclusion ne change pas, mais un tableau que d'autres recopient ne doit pas porter de valeur fausse : `FiltersTest` asserte désormais les valeurs exactes.

**Je rejette en revanche l'ordre 4**, que la logique du relecteur appellerait naturellement (19,2 dB de gain, facteur 9,1) : la réponse indicielle d'un Butterworth d'ordre 4 sonne **deux fois plus longtemps** (≈ 4 s contre 2 s). Or un changement de posture produit un échelon de gravité pouvant atteindre **1 g** — soit 50 fois un CLM typique. Une sonnerie de 4 s tombe en plein dans la fenêtre de durée d'un CLM (0,5–10 s), là où une sonnerie de 2 s est déjà pénible. On échangerait un artefact hypothétique (respiration à la cheville) contre une aggravation d'un artefact certain et énorme (posture). **Ordre 2 par défaut, ordre 4 derrière un paramètre.**

**Réserve importante, à documenter.** Le succès d'Athavale à 0,4 Hz de coupure *basse* prouve qu'une part réelle de l'information discriminante vit **sous** 0,5 Hz (le déplacement net du membre, pas la bouffée d'accélération). Un passe-haut à 0,5 Hz jette cette information. Je ne la récupère pas par un second canal de détection — cela rouvrirait la porte à la respiration — mais **par une caractéristique par événement extraite du canal gravité** (`tiltExcursionDeg`, §2.3 et §3.1), qui mesure exactement le déplacement net du segment. On obtient l'information d'Athavale sans son coude bas.

### 1.3 Proposition 3 — percentile bas sur fenêtre causale décalée : **PRINCIPE RETENU, IMPLÉMENTATION REJETÉE POUR LA PASSE DÉFINITIVE**

**Le principe est juste, mais le mécanisme décrit est quantitativement faux.** Une série PLMS n'auto-contamine presque pas une médiane. Durée moyenne d'un PLM mesurée à la cheville : **4,2 ± 0,14 s** en SJSR (Sforza 2005), IMI moyen 31,3 ± 1,3 s → rapport cyclique **13–18 %**. Une médiane ne décroche qu'au-delà de 50 % de contamination. Concrètement, avec 18 % de contamination purement haute, la médiane globale se situe au percentile 0,50/0,82 = **61e** de la distribution propre ; pour une enveloppe de type Rayleigh, Q(0,61)/Q(0,50) = **+16,6 %**. Le plancher monte de 17 %, pas d'un facteur qui « coupe au 3ᵉ CLM ».

Ce qui casse réellement la médiane, ce sont les **mouvements corporels grossiers** : un retournement de 20 s dans une fenêtre de 25 s, c'est 80 % de contamination — décrochage complet. Le piège nº 5 de la v1 désigne la bonne conséquence pour la mauvaise cause.

**Le percentile bas seul est un remède faible.** Toujours en Rayleigh, à 18 % de contamination, le p10 passe au percentile 0,122 → **+11,1 %**. On gagne 5,5 points sur 16,6. Ce n'est pas un ordre de grandeur.

**Je rejette la fenêtre causale décalée [T−65 s, T−5 s] pour la passe définitive, pour trois raisons.**

1. **Elle résout un problème que nous n'avons pas dans cette passe.** La v1 acte le « post-traitement au réveil » : la nuit entière est en mémoire. La causalité ne coûte rien à personne et fait perdre de la précision.
2. **Elle introduit un biais de retard de 35 s.** L'estimateur est centré 35 s dans le passé. Or le plancher mécanique **change par sauts** à chaque changement de posture (le couplage bracelet-cheville-literie change). Après un saut, le seuil est faux pendant 35 s au minimum. Une série de 4 CLM à IMI 22 s dure 66 s : on peut fabriquer ou détruire **une série entière** sur un seul retard d'estimateur.
3. **Elle est dominée par une méthode meilleure et déjà nommée dans la v1** : l'exclusion itérative.

**Nuance apportée par le passage aux chunks de 5 min (§3.7).** La proposition du relecteur devient **exactement la bonne réponse pour le mode incrémental**, où la causalité est imposée par la physique du problème. Elle est donc conservée, non pas comme l'estimateur de référence mais comme la variante causale documentée, avec une fenêtre allongée à [T−125 s, T−5 s] pour l'aligner sur `floorWinSec = 120 s`. Le mode définitif reste non causal. Voir §3.7.

**Retenu pour la passe définitive — estimateur en trois passes, non causal, segmenté :**

```
Passe 1 : floor₀(t) = p25 de env sur fenêtre BILATÉRALE de W = 120 s, tronquée aux bornes de segment
Passe 2 : masque M = { i : env[i] > k_excl · floor₀[i] }    (k_excl = 4, par défaut)
Passe 3 : floor(t)  = médiane de env sur la même fenêtre, restreinte à {i ∉ M}
          si |{i ∉ M} ∩ fenêtre| < 0,25·W·fs  →  floor(t) = floor₀(t), drapeau FLOOR_EXTRAPOLATED
Puis    : floor(t) ← max(floor(t), Θ_abs / k_on)     (cohérence avec le plancher absolu)
```

Deux protections que ni la v1 ni le relecteur ne prévoient :

- **La fenêtre ne franchit jamais une frontière de changement de posture ni une frontière de segment** (§3.1, §3.4). Le plancher est ré-estimé de part et d'autre. C'est ce qui traite correctement le saut de couplage, là où la fenêtre décalée échoue.
- **W = 120 s et non 25 s.** À 25 s, un unique retournement de 20 s emporte l'estimateur ; à 120 s il pèse 17 %. Le coût est une résolution temporelle moindre, sans importance puisque le plancher mécanique varie sur l'échelle de la posture (minutes), pas de la seconde.

### 1.4 Proposition 4 — TKEO : **REJETÉE**, avec deux arguments quantitatifs — mais l'objectif visé est légitime et traité autrement

L'opérateur discret Ψ[x[n]] = x[n]² − x[n−1]·x[n+1] vaut **exactement** A²·sin²(Ω) pour x[n] = A·cos(Ωn+φ), Ω en rad/échantillon (l'expression A²Ω² usuelle est l'approximation petit-Ω, exacte à 1 % près jusqu'à Ω ≈ 0,35 rad/éch, à 10 % près à Ω ≈ 1,1). Le TKEO pondère donc l'énergie par **sin²(Ω)**.

**Argument 1 — le TKEO amplifie exactement ce dont nous voulons nous débarrasser.** À fs = 50 Hz :

| Composante | f | Ω = 2πf/fs | sin²Ω | Poids relatif |
|---|---|---|---|---|
| Signal PLMS | 2 Hz | 0,251 | 0,0619 | 1,00 |
| Bruit MEMS haut de bande | 20 Hz | 2,513 | 0,3455 | **5,59** |

Le TKEO rehausse le bruit à 20 Hz de **5,6× en énergie (2,4× en amplitude)** par rapport au signal à 2 Hz, comparé à une enveloppe RMS classique. Pour un signal dont l'énergie utile est en bas de bande, c'est l'inverse de ce qu'on veut. Ce n'est pas un hasard : les deux seules applications publiées du TKEO à de l'accélérométrie (Aubol & Milner, IEEE TBME 2019, détection d'événements de marche) le font précédées d'un **passe-bande 1–20 Hz** justement parce que, dit le papier, le TKEO amplifie le bruit haute fréquence. Notre bande utile étant plus étroite et plus basse encore, le rapport de pondération est plus défavorable.

**Argument 2 — le gain visé n'a aucune valeur clinique ici.** Le bénéfice documenté du TKEO est la précision de datation de l'onset. Solnik et al. (Eur J Appl Physiol 2010, PMC2945630) : erreur moyenne poolée sur EMG de marche **124 ms → 55 ms**, SNR 12,3 → 357,7. Excellent — et sans objet. Nos critères cliniques ont une granularité de **500 ms** (durée minimale d'un CLM) et de **5 000 ms** (borne basse de l'IMI). Gagner 69 ms sur l'onset ne déplace pas un seul événement de classe. On paierait 5,6× de bruit pour une précision qu'aucune règle n'utilise.

Note d'attribution, utile parce que la littérature secondaire se trompe : le chiffre souvent cité « 40 ± 99 ms vs 229 ± 356 ms, p = 0,023 » n'est **pas** de Li, Zhou & Aruin 2007 (dont le résumé ne contient aucun résultat chiffré et dont le texte intégral est payant) mais de Solnik et al. 2008, Acta Bioeng Biomech.

**L'objectif du relecteur est néanmoins bon** — des onsets/offsets nets — et la v1 y échoue à cause de la fenêtre RMS de 0,15 s (§0-b). **Solution retenue à la place : enveloppe à deux échelles.**

- **Enveloppe grossière, W_c = 0,5 s** (25 échantillons) pour la **décision** : une fenêtre rectangulaire de durée exactement 1/f annule le résidu à la fréquence f ; à 0,5 s elle place un zéro exact sur l'ondulation à 2 Hz d'un signal à 1 Hz et une atténuation en sinc sur les ondulations supérieures. Stable, ne bat pas.
- **Enveloppe fine, W_f = 0,15 s** pour le **raffinement** de l'onset et de l'offset une fois le candidat confirmé : on remonte depuis le franchissement grossier jusqu'au dernier passage sous Θ_off de l'enveloppe fine.

Coût : deux convolutions au lieu d'une (O(n) chacune avec somme glissante). Bénéfice : la stabilité d'une longue fenêtre et la netteté d'une courte, sans amplification de bruit. C'est ce que le TKEO promettait, obtenu par un moyen qui ne combat pas la physique du signal.

### 1.5 Erreurs de règle clinique dans la v1, non relevées par le relecteur

**(i) La v1 applique la borne de durée 0,5–10 s à l'identique aux deux jeux de règles. C'est faux pour WASM 2016.** WASM 2016 règle 3.2.1 : « LM now have no maximum length. **A LM > 10 s now ends a PLM sequence.** » Un mouvement de 12 s n'est pas « ignoré », il **casse la série en cours**. La v1 aurait laissé la série se poursuivre par-dessus. Sur une nuit avec 40 retournements, cela change matériellement le compte de séries.

**(ii) La v1 réduit la différence AASM/WASM à la borne basse de l'IMI. C'est le point le moins important des deux.** Ferri et al. (Sleep Med 2015, PMID 26429751, 107 SJSR + 63 témoins) ont isolé les deux effets : « Alt1 » = borne 5 → 10 s seule, « Alt2 » = Alt1 + rupture de série sur IMI court. Résultat : « **only the Alt2 algorithm provided significantly different results** ». Autrement dit, **monter la borne basse ne change presque rien ; c'est la règle de rupture qui change tout.** La v1 implémente le paramètre sans effet et omet celui qui compte. WASM 2016 règle 3.3.6 : « Long (> 90 s) and short (< 10 s) CLM IMIs **end a sequence**. » L'AASM, elle, est **muette** sur le sujet ; la convention WASM 2006 (« si l'intervalle est < 5 s, le mouvement postérieur est ignoré et la période est calculée jusqu'au candidat suivant ») est l'interprétation par défaut retenue, explicitement étiquetée comme une interprétation.

**(iii) La v1 manque le seuil de morphologie de WASM 2016** (règle 3.2.1-d, nouveau en 2016, sans équivalent AASM) : l'événement doit contenir une période ≥ 0,5 s dont l'**amplitude médiane** dépasse le seuil d'offset. Sa fonction d'origine est d'exclure les bouffées polymyocloniques ; transposé à l'accélérométrie, c'est **le meilleur filtre anti-vibration transmise par le matelas** disponible dans le corpus des règles (§3.2). Il est gratuit à implémenter et je le retiens pour les **deux** jeux de règles.

**(iv) La règle de « fusion des CLM espacés < 0,5 s » de la v1 est une confusion.** Dans WASM 2016, le seuil offset-à-onset < 0,5 s est la règle de **combinaison bilatérale** (fusionner jambe gauche et jambe droite en un LM bilatéral). L'AASM a une règle bilatérale *différente* : onset-à-onset < 5 s. **Nous mesurons une seule jambe : aucune des deux ne s'applique.** L'unique effet de fusion en unilatéral est déjà contenu dans la définition de l'offset (« reste sous le seuil pendant 0,5 s »), qui empêche mécaniquement deux événements d'être séparés par moins de 0,5 s. La v1 introduit donc une étape redondante, et le piège nº 6 (« l'oublier double le compte ») est faux tel qu'écrit : correctement implémenté, l'offset le fait tout seul.

**(v) La v1 ne dit rien des mouvements corporels grossiers.** Un retournement dure 2–20 s et atteint 0,3–2,5 g (Sicbaldi : 2 506 ± 240 mg à l'éveil). Un retournement de 5 s satisfait **exactement** les critères de durée d'un CLM et sera compté. Il faut un classifieur explicite (§2, étape 5) plus une période réfractaire.

**(vi) La v1 fixe le PI de Ferri sans en donner les bornes ; la valeur souvent recopiée (10–50 s) est fausse.** Les bornes sont **10–90 s**, et la condition est **≥ 3 intervalles qualifiants consécutifs** (soit ≥ 4 LM). Il faut aussi une condition de validité : le PI est ininterprétable sous ~10 LM/h au total (Drakatos et al., J Thorac Dis 2021), le dénominateur étant alors composé d'une poignée d'intervalles.

---

## 2. Chaîne v2 — étape par étape

Convention : `fs` est la fréquence réelle estimée puis ramenée à 50,000 Hz exactement (§3.4) ; toutes les durées sont en secondes ; toutes les amplitudes en **g**.

### Étape −1 — Contrôles d'intégrité à l'entrée (l'algo ne fait confiance à rien)

Le CRC16 du format ne couvre **que le payload** : `count`, `tFirstNs`, `tLastNs` et `flags` de l'en-tête de bloc ne sont pas protégés. Une base de temps corrompue passe donc silencieusement le décodeur, et toute la chaîne v2 repose sur les timestamps (estimation de `fs`, rééchantillonnage, durées, IMI). **Le module `algo` doit donc revalider lui-même la cohérence temporelle**, indépendamment de ce que `:format` a accepté. C'est une exigence de conception, pas une précaution : sans elle, un bloc corrompu déplace toute la ligne de temps en aval.

Contrôles, dans cet ordre, tous à O(n) :

| # | Contrôle | Rejet |
|---|---|---|
| 1 | `1 ≤ N ≤ MAX_SAMPLES_PER_BLOCK` et `x.size == y.size == z.size == N` | bloc |
| 2 | `tFirstNs ≤ tLastNs` ; les deux strictement positifs | bloc |
| 3 | `fs_bloc = (N−1)·1e9/(tLastNs−tFirstNs)` dans ±20 % de `nominalRateHz` | bloc |
| 4 | **Monotonie inter-blocs** : `tFirstNs(b+1) ≥ tLastNs(b)` | bloc b+1 |
| 5 | **Non-chevauchement** : `tFirstNs(b+1) − tLastNs(b) ≥ 0,5/fs` | bloc b+1 |
| 6 | **Écart plausible** : `tFirstNs(b+1) − tLastNs(b) ≤ maxGapNs` (défaut 14 h — au-delà, remise à zéro d'horloge) | coupure de session |
| 7 | `chunkIndex` strictement croissant, `sessionUuid` identique sur tous les chunks | chunk |
| 8 | **Jerk impossible** : `‖a[i] − a[i−1]‖ > 8 g` entre deux échantillons consécutifs à 50 Hz est physiquement impossible à la cheville → corruption de décodage (typiquement un dépassement de saturation ou un `toRaw` défectueux) | bloc |
| 9 | **Plausibilité gravitaire** : sur les fenêtres statiques, `médiane(‖a‖)` doit être dans [0,80 ; 1,20] g | session marquée `DECODE_SUSPECT` |
| 10 | **Saturation** : un bloc dont > 5 % des échantillons valent ±32767 LSB est corrompu, pas saturé (la cheville n'atteint pas ±16 g) | bloc |
| 11 | Cohérence de `FLAG_GAP_BEFORE` avec l'écart réellement mesuré ; incohérence → drapeau, pas rejet | rapport |

Tout bloc rejeté est converti en **trou** de sa durée nominale et traité par la politique de trous de l'étape 0. Le nombre de blocs rejetés par contrôle est publié dans `IntegrityReport` et doit apparaître dans le rapport de qualité de la nuit : un taux de rejet > 1 % invalide la nuit.

Note sur `ChunkFormat.toRaw` : le défaut de compilation signalé (`Math.round(Double)` renvoie un `Long`, et `Long.coerceIn(Int, Int)` n'existe pas) est un bug du module `:format`, pas de `:algo`. Mais il a une conséquence pour nous : **une saturation mal implémentée produit un repliement de signe** (±16 g qui bascule d'un extrême à l'autre entre deux échantillons voisins). Le contrôle nº 8 le détecte de manière indépendante de la correction du bug — c'est exactement pourquoi il est là. Ne pas le retirer une fois `:format` corrigé.

### Étape 0 — Reconstruction de la ligne de temps et rééchantillonnage

**Entrée** : liste de blocs décodés `(tFirstNs, tLastNs, flags, x[], y[], z[])` ayant passé l'étape −1.

**Formules.**

```
fs_bloc(b)  = (N_b − 1) · 1e9 / (tLast_b − tFirst_b)
fs_session  = médiane pondérée par N_b des fs_bloc, après rejet des blocs
              tels que |fs_bloc − méd| / méd > 0,05
t_échantillon(b, i) = tFirst_b + round(i · (tLast_b − tFirst_b) / (N_b − 1))
```

Puis interpolation linéaire sur une grille uniforme à `fs_cible = 50,000 Hz`. L'atténuation point à point introduite par l'interpolation linéaire à 3 Hz est **au plus 1,8 %** — négligeable, vérifié par `TimelineTest`.

> **Correction du 2026-07-31.** Cette phrase annonçait « inférieure à 0,2 % pour un ratio de rééchantillonnage < 1,06 ». C'est faux, et ce n'est pas le ratio qui gouverne l'atténuation mais la **période d'échantillonnage source**. Pour un décalage fractionnaire `μ ∈ [0,1]`, le module de l'interpolateur vaut `√(1 − 4μ(1−μ)·sin²(πfT))`, minimal en `μ = 0,5` où il se réduit à `|cos(πfT)|` : à 3 Hz cela donne **1,6 %** pour `T = 1/52 s` et **1,8 %** pour `T = 1/50 s`, borne fermée `(πfT)²/2`. Même moyennée sur `μ` uniforme — la seule lecture qui aurait pu justifier 0,2 % — l'atténuation reste ≈ **1,1 %**. La conclusion d'ingénierie tient : moins de 2 % à 3 Hz est négligeable devant le biais de 5,2 % sur les durées que le rééchantillonnage supprime.

**Paramètres** : `targetFsHz = 50.0` (fixe) ; `fsOutlierTol = 0.05` (plage 0,02–0,10) ; `gapMicroSec = 0.10`, `gapSegmentSec = 2.0`.

**Justification.** Un `fs` faux de 5,2 % (50 → 52,6 Hz) ne déplace le coude du filtre que de 0,500 à 0,526 Hz — sans effet — mais fausse **toutes les durées de 5,2 %** : un CLM mesuré à 10,0 s en dure 9,5 s réellement, un IMI de 90 s en vaut 85,5 s. Les événements aux bornes basculent de classe. Ramener sur une grille fixe rend les coefficients de filtre constants, les durées exactes, et le traitement des trous explicite. C'est gratuit et cela supprime une classe entière de bugs.

**Comportement aux bords.**

- Trou < 0,10 s (≤ 5 éch.) : interpolation linéaire silencieuse, aucun drapeau. Plus court que la plus rapide caractéristique d'un CLM (T_rise ≥ 0,15 s).
- Trou 0,10–2,0 s : les échantillons manquants sont marqués `NaN`. Le canal mouvement les traite comme des zéros, le canal gravité **maintient la dernière valeur**. Une **zone aveugle** est marquée sur [début_trou − T_settle, fin_trou + T_settle] avec `T_settle = 2,0 s` (temps d'établissement d'un Butterworth ordre 2 à 0,5 Hz). Aucun CLM ne peut débuter ni s'achever dans une zone aveugle ; un CLM qui l'enjambe est rejeté (`IN_BLIND_ZONE`).
- Trou > 2,0 s : **frontière de segment dure**. Tous les états de filtre sont remis à zéro, toute série PLM ouverte est terminée. C'est exactement la règle WASM 3.3.3 : « The prior IMI is not measured … for the first CLM after starting or **re-starting the recording** » — nous ne l'inventons pas, nous l'appliquons.
- Bloc à CRC invalide ou rejeté à l'étape −1 : traité comme un trou de sa durée nominale.
- Comptabilité : le temps aveugle et le temps de trou sont **retirés du dénominateur du PLMI**. Avec le critère P1 de la v1 (cumul de trous < 2 min sur 8 h = 0,42 %) l'effet est nul ; sur une nuit dégradée à 30 min de trous, ne pas le faire déflaterait le PLMI de 7 %.

### Étape 1 — Séparation gravité / mouvement (deux chemins parallèles)

```
ĝ(t)      = ButterLP(ordre 2, fc_g = 0,15 Hz) appliqué séparément à x, y, z
a_lin(t)  = ButterBP(0,5 Hz – 8,0 Hz, ordre 2 par section) appliqué séparément à x, y, z
```

**Paramètres** : `fc_g = 0.15 Hz` (plage 0,08–0,25) ; `fc_hp = 0.50 Hz` (plage 0,30–0,70) ; `fc_lp = 8.0 Hz` (plage 6–12) ; `hpOrder = 2` (2 ou 4).

**Justification.** Deux chemins et non une soustraction, alors que `a − LP(a)` est mathématiquement un passe-haut valide : l'intérêt n'est pas mathématique, il est que **ĝ devient un signal de première classe**, utilisé pour la détection de posture (§3.1), pour la caractéristique `tilt` par événement (§2, étape 5), pour le masque d'immobilité (§3.6) et pour l'autocalibration (§3.3). `fc_g = 0,15 Hz` et non 0,25 : un CLM de 10 s a un fondamental à 0,1 Hz ; un passe-bas gravité trop rapide « avalerait » la partie lente des CLM longs et fausserait `tiltExcursion`.

**Comportement aux bords.** Chaque section biquad est initialisée à l'état stationnaire correspondant à la moyenne des **2 premières secondes** du segment (au lieu d'un état nul, qui produirait un transitoire de la taille de la gravité, soit ~1 g). Les **5 premières secondes** de chaque segment sont malgré tout exclues de l'analyse et du dénominateur (`warmupSec = 5.0`, plage 3–10). C'est le piège nº 3 de la v1, traité correctement : implémentation **streaming stateful** obligatoire, un seul passage sur le segment entier, jamais bloc par bloc.

### Étape 2 — Magnitude et enveloppe à deux échelles

```
m(t)      = √(a_lin,x² + a_lin,y² + a_lin,z²)          [invariant par rotation constante du bracelet]
env_c(t)  = √( moyenne_glissante( m², W_c = 0,50 s ) ) [décision]
env_f(t)  = √( moyenne_glissante( m², W_f = 0,15 s ) ) [raffinement onset/offset]
```

**Paramètres** : `W_c = 0.50 s` (plage 0,35–0,80) ; `W_f = 0.15 s` (plage 0,10–0,25).

**Justification.** Voir §1.4. Point statistique à connaître et à ne pas corriger : `m` sur du bruit pur suit une loi de Maxwell de moyenne 1,596 σ et de coefficient de variation 42 % — **la magnitude L2 n'est pas de moyenne nulle**, elle a un piédestal. Ce n'est pas un problème tant que le plancher est estimé **sur la même grandeur** (ce que fait l'étape 3) : le ratio `k_on` est alors autocohérent. Il faut simplement savoir que « 8× le plancher » signifie 8× une moyenne de Maxwell, pas 8× un écart-type par axe (= 12,8 σ).

**Comportement aux bords.** Fenêtres tronquées sur les `W/2` premières et dernières secondes de chaque segment, avec normalisation par le nombre d'échantillons valides ; ces zones sont incluses dans le `warmup` déjà exclu.

### Étape 3 — Plancher de bruit adaptatif

Estimateur en trois passes, non causal, segmenté — formule complète en §1.3. Variante causale pour le mode incrémental en §3.7.

**Paramètres** : `floorWinSec = 120.0` (plage 60–240) ; `floorP1 = 25` (percentile de la passe 1, plage 15–40) ; `k_excl = 4.0` (plage 3–6) ; `minValidFrac = 0.25` (plage 0,15–0,40).

**Comportement aux bords.** Fenêtre bilatérale tronquée aux frontières de segment **et aux frontières de changement de posture**. Si moins de 40 s de données valides sont disponibles dans la fenêtre, le plancher est repris de la valeur valide la plus proche et l'intervalle est marqué `FLOOR_EXTRAPOLATED` ; les CLM détectés dans ces intervalles portent le drapeau et sont comptés séparément dans le rapport de qualité. La fin d'une nuit tronquée est systématiquement dans ce cas sur les 60 dernières secondes (§3.7).

### Étape 4 — Seuils

```
Θ_on(t)  = max( k_on  · floor(t),  Θ_abs,        f_cal · gainCal )
Θ_off(t) = max( k_off · floor(t),  Θ_abs · 0,31,  f_cal · gainCal · 0,31 )
```

Le facteur 0,31 = k_off/k_on = 2,5/8,0 préserve l'hystérésis quel que soit le terme dominant. Rapport d'hystérésis = 3,2.

**Paramètres** : `k_on = 8.0` (plage 5–12) ; `k_off = 2.5` (plage 2,0–4,0) ; `Θ_abs = 0.020 g` (plage 0,010–0,050) ; `f_cal = 0.12` (plage 0,08–0,20).

**Justification du choix de k_on.** Le facteur n'est **pas** dicté par le bruit thermique : pour une enveloppe de type Rayleigh, avec ~2 échantillons indépendants par seconde après la fenêtre de 0,5 s, soit 5,76 × 10⁴ tirages sur 8 h, un facteur de **4,8 sur la médiane** suffirait déjà à garantir moins de 0,01 faux positif thermique par nuit. Le facteur 8 est donc entièrement un budget **anti-artefact**, pas anti-bruit. C'est important à savoir : il ne faut pas le régler en regardant du bruit, il faut le régler en regardant des nuits réelles (§3.3, test T11).

### Étape 5 — Détection des LM, puis classification en CLM

Machine d'états sur `env_c`, raffinement sur `env_f` :

1. **Onset provisoire** : premier `t` tel que `env_c(t) ≥ Θ_on(t)`.
2. **Offset provisoire** : premier `t' > t` tel que `env_c` reste `< Θ_off` pendant **au moins `offHoldSec = 0,50 s`** en continu. L'offset est daté au **début** de cette période (règle AASM/WASM littérale : « the START of a period lasting at least 0.5 s during which the EMG does not exceed 2 µV above resting »).
3. **Raffinement** : reculer l'onset jusqu'au dernier passage de `env_f` sous `Θ_off` avant l'onset provisoire ; avancer l'offset jusqu'au dernier passage de `env_f` au-dessus de `Θ_off` avant l'offset provisoire.
4. **Seuil de morphologie (WASM 3.2.1-d, appliqué aux deux jeux de règles)** : rejeter si l'événement ne contient aucune fenêtre de 0,50 s dont la **médiane** de `env_f` est ≥ `Θ_off`. Rejet `MORPHOLOGY`.
5. **Classification** :
   - `durée < 0,5 s` → rejet `TOO_SHORT` (ce n'est pas un LM).
   - `0,5 s ≤ durée ≤ 10 s` → **CLM**.
   - `durée > 10 s` → **LM long** : jamais un CLM ; conservé dans la liste avec le drapeau `LM_LONG` car il **casse la série** (WASM 3.3.6, et par option en AASM).
6. **Gros mouvement corporel (GBM)** : si `crête ≥ k_gbm · floor` OU `|tiltChange| > postureDeg` OU `durée > 10 s` → classé `GROSS_BODY`, exclu des CLM, et une **période réfractaire** de `refractorySec` est appliquée de part et d'autre (aucun CLM n'y est accepté ; le plancher y est déjà protégé par l'exclusion itérative).
7. **Posture** : voir §3.1.

**Caractéristiques extraites de ĝ pour chaque événement** (elles portent l'information que le passe-haut à 0,5 Hz jette, cf. §1.2) :

```
tiltChangeDeg    = angle( ĝ_u(offset + 2 s), ĝ_u(onset − 2 s) )                  // changement net, persistant
tiltExcursionDeg = max sur [onset, offset] de angle( ĝ_u(t), ĝ_u(onset − 2 s) )  // excursion transitoire
```

**Paramètres** : `offHoldSec = 0.50` (fixe, clinique) ; `clmMinSec = 0.50` (fixe, clinique) ; `clmMaxSec = 10.0` (fixe, clinique) ; `morphoWinSec = 0.50` (plage 0,3–0,8) ; `k_gbm = 40.0` (plage 25–60) ; `refractorySec = 2.0` (plage 1–4).

**Comportement aux bords.** Un LM dont l'onset précède le début du segment analysable, ou dont l'offset n'est pas atteint avant la fin du segment, est marqué `TRUNCATED` : il n'est pas compté comme CLM (sa durée est inconnue) mais il **casse la série** comme un LM long, ce qui est le comportement conservateur.

### Étape 6 — Construction des séries

Deux implémentations littérales, jamais un paramètre unique.

**AASM_V3** — IMI onset-à-onset ∈ [5, 90] s ; ≥ 4 CLM consécutifs ; **au moins une partie de chaque CLM doit tomber dans une époque de sommeil** (c'est le changement de la v3, cf. Summary of Updates v3) ; IMI < 5 s : le mouvement postérieur est ignoré et la période est mesurée jusqu'au candidat suivant (convention WASM 2006, faute de règle AASM — étiquetée `INTERPRETED`) ; `breakOnLongLm = false` par défaut pour rester littéral.

**WASM_2016** — IMI ∈ [10, 90] s ; ≥ 4 CLM (= 3 IMI) ; **IMI < 10 s ou > 90 s casse la série** (3.3.6) ; **un LM > 10 s casse la série** ; une série peut **traverser** une transition veille/sommeil (2.4.4) ; `breakOnLongLm = true`.

**Sortie** : `plmsCount` (CLM en série, pendant le sommeil), `plmwCount` (CLM en série, pendant l'éveil intra-SPT — métrique WASM, non définie par l'AASM), `isolatedCount` (CLM hors série), `shortImiCount` (CLM à IMI < borne basse).

**Comportement aux bords.** Une série tronquée par le début ou la fin de l'enregistrement reste valide si elle compte déjà ≥ 4 CLM ; sinon elle est abandonnée et comptée dans `truncatedSeriesDropped` (à rapporter : c'est un biais à la baisse mesurable, et il augmente sur une nuit interrompue).

### Étape 7 — Indices

```
PLMI = plmsCount / TST_analysable_heures
PLMW = plmwCount / WASO_analysable_heures
```

`TST_analysable` = TST ∩ (segments valides) ∩ (hors zones aveugles) ∩ (hors off-body) ∩ (hors warmup).

**Periodicity Index de Ferri**, sur les CLM pendant le sommeil :

```
IMI_k = onset_{k+1} − onset_k                      k = 1..N−1
qualifiant(k)  ⟺  10 < IMI_k ≤ 90    (secondes)
Découper la suite des IMI en séquences maximales d'intervalles qualifiants consécutifs.
PI = ( Σ longueur(R) pour toute séquence R de longueur ≥ 3 ) / (N − 1)
piValid = ( N / TST_h ≥ 10 )
```

Convention documentée : **borne basse stricte, borne haute inclusive**, et **numérateur en intervalles** (Ferri publie aussi une forme en mouvements, `PLMS_alt / LMS_total`, légèrement supérieure ; les deux existent dans ses propres articles, il ne faut jamais les mélanger). Seuil de référence ≈ **0,50** (Ferri et al., Sleep Med 2016;22:97-99). Valeurs de référence : SJSR 0,601 ± 0,189 ; témoins 0,092 ± 0,152 (Ferri, JCSM 2022) — mais 0,220 ± 0,229 pour des témoins dans Mogavero 2024, l'instabilité du groupe témoin étant exactement l'effet du dénominateur faible.

Sont également produits : `plmiSpt` (dénominateur = SPT, voir §3.6), `plmiFirstHalf` / `plmiSecondHalf` (split-half), l'**histogramme des IMI** (bins de 2 s de 0 à 100 s — la bimodalité 2–4 s / 22–26 s est l'information diagnostique brute et doit être affichée), et l'encadrement `[plmiLowerBound, plmiUpperBound]` issu des deux masques (§3.6).

---

## 3. Traitement explicite des problèmes désignés

### 3.1 Gravité, mouvement, et changements de posture

**Le problème, chiffré.** Un retournement change la projection de la gravité sur un axe de jusqu'à **1 g** en 0,5–3 s. Passé dans le passe-haut, cet échelon produit un transitoire d'amplitude initiale ≈ l'amplitude de l'échelon et de constante de temps τ = 1/(2π·f_c) = **0,32 s** à 0,5 Hz, soit une sonnerie sensible sur ~2 s (ordre 2). Un CLM typique fait 30–200 mg. **L'artefact de posture est donc 5 à 30 fois plus gros qu'un vrai CLM et dure exactement la bonne durée pour être compté.** C'est, de loin, la première source de faux positifs.

**Détecteur de posture** (opère sur ĝ, pas sur l'enveloppe) :

```
ĝ_u(t)    = ĝ(t) / ‖ĝ(t)‖
Δφ(t, τ)  = arccos( clamp(ĝ_u(t+τ) · ĝ_u(t−τ), −1, 1) ) · 180/π

Changement de posture en t  ⟺  Δφ(t, τ_p) > postureDeg
                              ET  ĝ_u reste dans un cône de stableDeg autour de ĝ_u(t+τ_p)
                                  pendant au moins stableSec
```

Les CLM candidats dont l'onset tombe dans `[t − guardSec, t + guardSec]` sont marqués `POSTURAL` et **exclus par défaut** (mais conservés en base, avec le drapeau, pour permettre un recalcul).

**Paramètres** : `τ_p = 2.0 s` ; `postureDeg = 20.0°` (plage 12–30) ; `stableDeg = 10.0°` ; `stableSec = 10.0 s` (plage 5–20) ; `guardSec = 2.5 s` (plage 1,5–4).

Deux effets de bord traités : (a) les frontières de posture **coupent les fenêtres d'estimation du plancher** (§1.3) ; (b) elles définissent aussi les segments d'homogénéité du gain mécanique (§3.3).

**Discrimination par les caractéristiques de tilt.** Elles séparent trois classes : `tiltExcursion ≈ 0` → vibration transmise ou bruit ; excursion modérée avec `tiltChange` faible → CLM authentique (le membre bouge et revient) ; `tiltChange` grand et persistant → posture.

### 3.2 Artefacts transmis par le matelas

**État de la connaissance : il n'existe aucune quantification publiée** de la transmission d'un mouvement de partenaire à travers un matelas vers un capteur porté au corps. Ni amplitude en mg, ni fonction de transfert, ni caractérisation fréquentielle, ni étude spécifique à la cheville. C'est un vrai trou. Toute valeur donnée ici est une hypothèse d'ingénierie à mesurer, pas une donnée.

**Trois défenses, par ordre d'efficacité attendue.**

1. **Le seuil de morphologie WASM 3.2.1-d** (étape 5.4). Une vibration transmise est une **sonnerie brève** : impulsion + décroissance sur 0,05–0,4 s, à la fréquence de résonance du couple matelas/bracelet. Exiger une fenêtre de 0,50 s dont la **médiane** de l'enveloppe dépasse Θ_off élimine par construction tout ce qui n'a pas de plateau d'un demi-seconde. Une sonnerie de 0,3 s a une crête élevée mais une médiane sur 0,5 s faible. C'est le seul filtre de ce catalogue qui soit une règle clinique publiée et non une invention.

2. **La caractéristique `tiltExcursionDeg`**. Un mouvement propre de la jambe déplace le segment : ĝ_u tourne. Une vibration transmise secoue le capteur sans réorienter le segment : ĝ_u ne tourne pas. Règle : marquer `TRANSMITTED_SUSPECT` si `tiltExcursionDeg < minExcursionDeg` (défaut **1,5°**, plage 0,5–4) **et** `durée < 1,5 s`. **Rapporté, pas exclu par défaut** — parce qu'une dorsiflexion isolée peut aussi produire une excursion quasi nulle au capteur (c'est le mécanisme de Terrill, §0-d), et exclure ces événements aggraverait le biais à la baisse déjà présent. On aurait deux erreurs qui vont dans le même sens.

3. **Le plancher adaptatif lui-même.** Un flux continu de micro-vibrations relève le plancher local, ce qui relève le seuil : le système s'auto-immunise contre un partenaire agité, au prix d'une sensibilité réduite cette nuit-là. C'est le comportement souhaitable, et le drapeau à afficher est le **rapport plancher-médian de la nuit sur la médiane inter-nuits** ; au-delà de +50 %, la nuit doit être marquée « environnement bruyant, sensibilité réduite ».

**Protocole de mesure à faire** (il n'existe pas dans la littérature, il faut le produire) : une nuit de contrôle avec la montre à la cheville et personne d'autre dans le lit, puis une session éveillée où un tiers se retourne délibérément 30 fois à distance connue. Cela donne l'amplitude et le spectre réels de la transmission pour **ce** matelas — et c'est de toute façon la seule valeur qui compte, la transmission dépendant entièrement de la construction du matelas (ressorts vs mousse à mémoire : plusieurs ordres de grandeur d'écart).

### 3.3 Normalisation inter-nuits — serrage du bracelet et position du boîtier

C'est le problème le plus sérieux du projet après le masque de sommeil, parce qu'il attaque directement la comparabilité, qui est **toute la valeur** d'un dépistage sur 5–7 nuits. La v1 se contente de « garder le même bracelet et le même serrage », ce qui n'est pas une solution mais un vœu.

**Deux volets, indépendants et cumulatifs.**

**Volet A — autocalibration statique du capteur (corrige le capteur, pas le couplage).**

Procédure, à la manière de l'autocalibration GGIR/van Hees, entièrement dérivée de la nuit elle-même, sans intervention :

```
1. Fenêtres statiques : toutes les fenêtres de 10 s où sd(x), sd(y), sd(z) < 13 mg.
2. Couverture de la sphère : exiger  max(gᵢ) − min(gᵢ) ≥ 0,30 g  pour chacun des 3 axes
   sur l'ensemble des fenêtres statiques. Sinon → ABANDON, on garde la calibration de la
   nuit précédente et on lève le drapeau CALIB_INSUFFICIENT_COVERAGE.
3. Moindres carrés itératifs sur (offset o ∈ R³, gain diagonal S ∈ R³) :
        minimiser   Σ_f ( ‖ S ⊙ (a_f − o) ‖ − 1 )²
   3 à 5 itérations de Gauss-Newton suffisent ; initialiser à o = 0, S = 1.
4. Rejeter la calibration si  ‖o‖ > 0,10 g  ou  max|S − 1| > 0,05  (capteur suspect).
5. Appliquer  a ← S ⊙ (a_raw − o)  avant toute autre étape.
```

Cela retire le décalage et le gain du MEMS, qui dérivent avec la température et le vieillissement. Cela ne fait **rien** contre le serrage du bracelet.

**Volet B — rituel de calibration mécanique (corrige le couplage). C'est le volet qui compte.**

Écran de la montre, 70 s, au coucher, avant `START` de la nuit :

```
Phase 1 — 30 s : immobilité totale, jambe posée.        → floorCal
Phase 2 — 10 dorsiflexions volontaires « confortables », guidées par un métronome
          visuel à 3 s d'intervalle (durée totale 30 s). → gainCal
Phase 3 — 10 s : immobilité.                             → contrôle de retour au calme
```

Extraction :

```
floorCal = p50( env_c ) sur la phase 1
gainCal  = médiane des 10 amplitudes crête de env_c dans les fenêtres [tᵢ, tᵢ + 2 s]
           (i = 1..10, tᵢ donné par le métronome — la vérité terrain est connue)
snrCal   = gainCal / floorCal
```

**Pourquoi cela marche.** `gainCal` est la **mesure directe du gain de la chaîne mécanique cheville → bracelet → boîtier → MEMS de cette nuit-là**, pour un geste physiologique de référence dont l'amplitude est raisonnablement reproductible d'une nuit à l'autre chez le même sujet. C'est exactement la variable que le serrage fait bouger, et elle est mesurée, pas supposée. C'est le troisième terme du seuil (étape 4) : `f_cal · gainCal`, avec `f_cal = 0,12` — un CLM est déclaré s'il atteint 12 % de l'amplitude d'une dorsiflexion volontaire confortable.

**Contrôle qualité inter-nuits.** Si `gainCal` d'une nuit s'écarte de plus de **±35 %** de la médiane des nuits précédentes de la même campagne, la nuit est marquée `CALIB_OUTLIER`, la valeur reste affichée mais **est exclue de la tendance et de l'ICC**, et l'UI affiche « serrage du bracelet probablement différent — resserrer et refaire ». Sans ce garde-fou, on compare des pommes et des poires en croyant mesurer une variabilité biologique.

**Repli si le rituel n'est pas fait** (l'utilisateur oublie, l'écran ne s'affiche pas) : utiliser comme référence de gain interne la **médiane des amplitudes crête des mouvements corporels grossiers** de la nuit. Les retournements sont un événement physiologiquement stéréotypé, fréquent (20–60/nuit) et d'amplitude relativement stable (Sicbaldi : 377 ± 63 mg pendant le sommeil, un CV de 17 % à travers les sujets). C'est un étalon interne moins bon que le rituel, mais bien meilleur que rien. Le champ `gainSource ∈ {RITUAL, GROSS_BODY, NONE}` doit accompagner **chaque** PLMI publié.

**Test de non-régression associé (T11)** : multiplier tout le canal mouvement d'une nuit synthétique par 0,6 puis 1,8 (bracelet lâche / serré). Calibration active → écart de PLMI ≤ 10 %. Calibration désactivée → écart attendu > 40 %. C'est le test qui **prouve** que le volet B sert à quelque chose ; s'il ne montre pas cet écart, le volet B est du folklore et il faut le retirer.

### 3.4 Dérive de la fréquence d'échantillonnage réelle

Traité intégralement aux étapes −1 et 0. Trois points à retenir :

- **Ne jamais faire confiance à `nominalRateHz`.** Le champ existe dans l'en-tête pour la traçabilité, pas pour le calcul. `fs` est recalculé depuis `(tFirstNs, tLastNs, N)` de chaque bloc — après revalidation, puisque ces champs ne sont pas couverts par le CRC (étape −1).
- **Rééchantillonner sur une grille fixe plutôt qu'adapter les coefficients.** Adapter les filtres à un `fs` variable oblige à recalculer les biquads en cours de session, ce qui provoque des transitoires à chaque changement — on remplace un problème de 5 % par des artefacts localisés. Le rééchantillonnage coûte une interpolation linéaire et rend tout le reste exact.
- **Un `fs` bloc aberrant est un symptôme de timestamps corrompus**, pas de dérive : rejet du bloc au-delà de ±5 % de la médiane de session, comptabilisé comme un trou.

Le triplet d'horloges de l'en-tête (`startWallMs`, `startElapsedRealtimeNs`, `firstEventTimestampNs`) sert à une chose distincte et tout aussi importante : **détecter la dérive entre l'échelle `SensorEvent.timestamp` et l'horloge murale** — certains OEM excluent le temps de suspend. La dérive est estimée par régression de `(startWallMs − startWallMs[0])` sur `(firstEventTimestampNs − firstEventTimestampNs[0])` à travers les chunks de la session. Une pente s'écartant de 1 de plus de 1e-4 (soit > 2,9 s sur 8 h) doit lever `CLOCK_DRIFT` : **la fusion avec l'hypnogramme Health Connect serait décalée**, ce qui déplace les CLM d'un stade à l'autre et fausse le partage PLMS/PLMW. Le recalage par corrélation croisée (§3.6) le rattrape en partie, mais il faut le savoir.

**Bénéfice du passage aux chunks de 5 min.** Le nombre de chunks par nuit passe de ~16 à ~96, donc le nombre de points d'ancrage de la régression de dérive est multiplié par 6 : l'incertitude sur la pente s'améliore d'un facteur **√6 ≈ 2,4**. C'est un gain gratuit et non négligeable pour la fusion avec l'hypnogramme. L'arithmétique du format se vérifie : 5 min × 60 × 50 Hz × 6 o = 90 000 o, plus l'entête et l'overhead de bloc ≈ **91 Ko**, cohérent avec la contrainte `DataItem`.

### 3.5 Mouvements liés à la respiration (RRLM) sans canal respiratoire

**Ce qu'on ne peut pas faire, et il faut le dire franchement.** Les deux règles d'exclusion RRLM publiées sont temporelles et exigent le canal respiratoire : AASM exclut tout LM « de 0,5 s avant une apnée/hypopnée/RERA à 0,5 s après » — l'événement **entier** est encadré, l'AASM l'a confirmé explicitement en FAQ (item M.5) — tandis que WASM 2016 recommande deux règles alternatives, dont celle issue de Manconi et al. (Sleep 2015) : **−2,0 s à +10,25 s autour de la fin** de l'événement respiratoire. Sans canal respiratoire, aucune des deux n'est calculable. Point final.

**La périodicité ne sauve rien.** On pourrait espérer séparer PLMS et RRLM par leur IMI. Le mode de l'IMI des PLMS est à **22–26 s** (Ferri) ; le cycle apnéique en SAOS est typiquement de **25–45 s**. **Les distributions se recouvrent.** Toute règle fondée sur l'IMI seul confondrait les deux. Le PI de Ferri ne sauve rien non plus, pour la même raison : **les RRLM sont périodiques**. Il faut le dire, plutôt que de laisser croire que le garde-fou de périodicité couvre ce risque.

**L'ampleur du biais est énorme, pas marginale.** Sur la même cohorte, la règle WASM (−2/+10,25 s) classe **90,7 ± 112,1 événements/h** comme liés à la respiration, contre **50,5 ± 70,2** pour la règle AASM (±0,5 s) — un facteur 1,8 **entre deux définitions officielles**, à canal respiratoire disponible (Sleep Breath 2023, PMC10163289). L'incertitude introduite par le simple choix de la fenêtre d'exclusion **dépasse d'un ordre de grandeur** l'erreur propre du détecteur. Prétendre à ±10 % sur le PLMI d'un sujet apnéique serait malhonnête.

**Ce qu'on peut faire, concrètement, par ordre de solidité.**

1. ~~**Verrouiller le rapport derrière un dépistage SAOS.**~~ `RespiratoryConfidence ∈ {HIGH, MEDIUM, LOW}`, dérivé d'un STOP-BANG dans le questionnaire, de l'AHI si Health Connect en expose un, et du drapeau du questionnaire SJSR ; sous `LOW`, l'UI n'aurait pas affiché de PLMI.

   > **Abandonné, et il faut dire pourquoi plutôt que de rayer la ligne.** Ce verrou a été écrit, et
   > **rien ne l'a jamais alimenté** : aucune source ne remplissait `RespiratoryConfidence`, donc
   > toutes les nuits sortaient au même niveau, donc la porte ne pouvait pas se fermer. **Une
   > protection qui ne se déclenche jamais est pire qu'une protection absente, parce qu'elle se
   > documente comme une protection.** Le verrou et le niveau de confiance ont été retirés du produit,
   > et avec eux toute prétention au dépistage de l'apnée : Pendulum mesure des mouvements de jambe
   > dans le contexte du syndrome des jambes sans repos, et rien d'autre. Ce qui reste est le point 2
   > ci-dessous — un encadrement qui ne détecte rien et ne prétend rien. Voir
   > [`../02-science.md`](../02-science.md), section *Pendulum does not screen for sleep apnoea, and
   > will not*.

2. **Publier un encadrement, jamais un point.** Calculer et afficher `plmiRaw` et `plmiRespWorstCase`, où le pire cas retire tous les CLM appartenant à des séries dont l'IMI médian tombe dans la bande apnéique 25–45 s. Ce n'est pas une exclusion RRLM — c'est **une borne inférieure garantie**. La vraie valeur est entre les deux. L'écart entre les deux bornes est en soi l'indicateur d'incertitude à afficher.

3. **Afficher l'histogramme des IMI.** Une bimodalité nette avec un mode secondaire à 30–40 s est un signal visuel de RRLM interprétable par un médecin du sommeil, là où un scalaire ne l'est pas. C'est de l'information rendue, pas une décision prise à sa place.

4. **Surrogat respiratoire dérivé de l'accéléromètre de cheville — EXPÉRIMENTAL, désactivé par défaut.** Il n'existe **aucune preuve publiée** qu'un accéléromètre de cheville capte la respiration ; le corpus ne le documente qu'au thorax et marginalement au poignet. La piste : chercher, dans la bande 0,15–0,50 Hz du signal *avant* le passe-haut, une modulation d'amplitude cyclique de 25–45 s (signature crescendo-decrescendo de la respiration périodique), puis tester la corrélation de sa phase avec les onsets de CLM. Cela doit être implémenté derrière un drapeau, produire une métrique dédiée, et **ne jamais influencer le PLMI** tant que la validation n'existe pas.

**Direction du biais, à rappeler dans le rapport.** Le biais RRLM est **à la hausse** ; le biais de mesure unilatérale et le biais de Terrill (39 % des LM EMG invisibles en accélérométrie) sont **à la baisse**. Ils ne se compensent pas — ils dépendent de sujets et de mécanismes différents, et leurs variances s'ajoutent. La v1 avait raison de l'écrire ; il faut le maintenir mot pour mot.

### 3.6 Masque de sommeil accélérométrique, circularité du dénominateur, et fusion avec l'hypnogramme

#### 3.6.1 Rejet de Cole-Kripke

Trois raisons.

1. **Il ne prend pas des g en entrée**, mais des *activity counts* — une transformation propriétaire ActiGraph (filtrage, rectification, seuillage, intégration par époque). Il n'y a pas de conversion publiée de g bruts vers des counts. Toute implémentation « Cole-Kripke » sur des g bruts est une invention qui emprunte les coefficients de Cole sans emprunter le prétraitement pour lequel ils ont été ajustés. Les implémentations open source elles-mêmes ne s'accordent ni sur ce prétraitement (`actigraph.sleepr` divise par 100 et écrête à 300 ; BioPsyKit ne fait rien) ni sur les coefficients (poids central à 30 s : 121 chez BioPsyKit, 12 chez pyActigraphy).
2. **Il est validé au poignet.** À la cheville, il surestime le TST de **+43 min [20, 66]**, contre −8 min [−28, 13] au poignet.
3. **Le sens du biais est le pire possible pour nous.** Un TST surestimé **déflate** le PLMI : +43 min sur 420 min → PLMI × 0,907. Un PLMI vrai de 15,0 s'affiche à **13,6** — sous le seuil de dépistage. L'algorithme le plus « précis » au poignet est celui qui fait rater le diagnostic à la cheville.

#### 3.6.2 Retenu : règle d'inactivité soutenue de type van Hees, adaptée à la cheville et rendue invariante par orientation

Motif du choix : dans la même étude, les algorithmes GGIR (van Hees, Galland) sont les **seuls** à ne montrer **aucune différence significative poignet/cheville** — exactement la propriété dont nous avons besoin, puisque la position du boîtier varie d'une nuit à l'autre.

Règle originale (van Hees 2015, PLOS ONE 10(11):e0142533) : médiane glissante de 5 s par axe, angle du bras `atan(a_z / √(a_x² + a_y²))·180/π`, moyenne par époque de 5 s, **inactivité soutenue = aucun changement d'angle > 5° pendant ≥ 5 min consécutives**. Validée à 83 % d'exactitude vs PSG (n = 28) avec une **surestimation de 31 min**.

Adaptation retenue :

```
ĝ_k        = moyenne de ĝ_u sur l'époque k (durée epochSec = 5 s)
Δφ_k       = arccos( ĝ_k · ĝ_{k−1} ) · 180/π                  // invariant par orientation
amp_k      = p95( env_c ) sur l'époque k

immobile(k) ⟺ Δφ_k ≤ angleDeg  ET  amp_k < k_move · floor(k)

Inactivité soutenue = bouffée d'époques immobiles consécutives de durée ≥ sustainedMin
SPT = du début de la première bouffée ≥ sptMinMin jusqu'à la fin de la dernière
WASO = époques non immobiles à l'intérieur du SPT
TST  = SPT − WASO
```

**Paramètres** : `epochSec = 5.0` ; `angleDeg = 5.0` (plage 3–8) ; `k_move = 6.0` (plage 4–10) ; `sustainedMin = 5.0` (plage 3–10) ; `sptMinMin = 15.0` (plage 10–30).

Deux améliorations sur van Hees : le **Δφ du vecteur gravité unitaire** remplace l'angle sur un axe nommé (`angle_z` suppose une orientation anatomique connue, ce qui n'est pas notre cas) ; le **critère d'amplitude additionnel** rattrape les mouvements vibratoires sans réorientation, invisibles à un critère purement angulaire.

Biais connu à assumer : van Hees à la cheville sous-estime le TST de **−89 min [−116, −63]** dans la seule étude disponible (enfants, n = 29), ce qui **inflate** le PLMI de ~27 %. Nous échangeons donc un biais de −9 % (Cole-Kripke) contre un biais de +27 % (van Hees), en gagnant l'invariance du site. **Ni l'un ni l'autre n'est acceptable non corrigé** — d'où la fusion en §3.6.4.

#### 3.6.3 La circularité du dénominateur — énoncé, gravité, et réponse codable

**Énoncé précis.** `PLMI = numérateur(mouvement) / dénominateur(TST déduit de l'absence de mouvement)`. Les deux termes sortent du même signal, et ils sont **anti-corrélés par construction**. Plus le sujet a de PLMS, plus le masque d'immobilité voit du mouvement, moins il score de sommeil, plus le TST baisse, plus le PLMI monte. **C'est une rétroaction positive : la métrique s'auto-amplifie.**

**La gravité est bien pire qu'un simple biais de quelques pour cent.** La règle van Hees exige **≥ 5 min consécutives** sans mouvement. Une série PLMS à IMI 22 s place ~13 mouvements dans n'importe quelle fenêtre de 5 min. **La probabilité qu'une fenêtre de 5 min soit libre de tout mouvement pendant une série est nulle.** Conséquence : appliqué naïvement, le masque score **toute la période de série comme de l'éveil**. Deux effets contradictoires et tous deux catastrophiques s'enchaînent :

- le TST s'effondre → le PLMI explose ;
- et simultanément la règle AASM v3 « au moins une partie du CLM dans une époque de sommeil » **supprime les CLM eux-mêmes**, puisqu'ils sont désormais en éveil.

Le sujet le plus malade est celui pour lequel l'algorithme se comporte le plus mal. C'est un mode de défaillance disqualifiant s'il n'est pas traité.

**Réponse, en trois couches, toutes codables.**

**Couche 1 — rendre le masque aveugle aux CLM.** Le masque d'immobilité doit être construit sur des **preuves de mobilité dont les CLM sont exclus**. Cliniquement, c'est la seule position tenable : un PLMS est *par définition* un mouvement **pendant** le sommeil ; s'en servir comme preuve d'éveil est une erreur de catégorie. L'AASM score les PLMS *dans* des époques de sommeil ; un mouvement de jambe ne rend pas l'époque éveillée sauf critère d'éveil cortical, que nous ne pouvons pas évaluer sans EEG.

```
ImmobilityMask.build(gravity, env, floor, segments, offBody, ignoreIntervals = clmIntervals, cfg)
```

Les intervalles passés en `ignoreIntervals` sont **neutralisés** : l'époque qui les contient est rescorée à partir des époques voisines (interpolation du plus proche voisin immobile/mobile), et non comptée comme mobile. Restent comme preuves d'éveil : les **mouvements corporels grossiers**, les **changements de posture**, et les périodes de mobilité soutenue de plus de `epochSec` non attribuables à un CLM. C'est exactement la bonne discrimination, et elle est déjà disponible : le classifieur GBM (étape 5.6) et le détecteur de posture (§3.1) produisent ces étiquettes.

**Couche 2 — point fixe borné à 2 itérations.**

```
1. Masque provisoire M₀  : immobilité SANS ignoreIntervals (dégradé, mais borne le SPT)
2. Détection C₀           : CLM avec M₀ (la contrainte AASM « portion en sommeil » n'est PAS
                            encore appliquée à cette itération)
3. Masque M₁              : immobilité avec ignoreIntervals = intervalles de C₀
4. Détection C₁ + séries  : cette fois avec M₁ et toutes les règles
5. Contrôle de convergence: |TST(M₁) − TST(M₀)| doit être < 25 % de TST(M₀)
                            sinon → drapeau MASK_NON_CONVERGENT
```

**Deux itérations, jamais plus.** Un point fixe itéré sans borne sur un critère non monotone peut osciller ; deux passes suffisent parce que la couche 1 supprime la boucle de rétroaction, elle ne fait pas que l'atténuer. La non-convergence est un signal, pas une erreur à masquer : elle indique une nuit où mouvement et immobilité ne se séparent pas, et cette nuit ne doit pas produire de PLMI publiable.

**Couche 3 — préférer un dénominateur indépendant chaque fois qu'il existe.** C'est l'argument décisif en faveur de Health Connect, et il n'est pas seulement « HC apporte les stades » : **HC est indépendant**. Autre poignet, autre capteur, autre algorithme, autre appareil. Aucune circularité. Quand HC répond, la circularité disparaît entièrement, et c'est la meilleure raison de le prioriser.

#### 3.6.4 Fusion avec Health Connect, en quatre temps

```
1. RECALAGE TEMPOREL. Construire s_accel(k) ∈ {0,1} (immobile) et s_hc(k) ∈ {0,1} (SLEEP*)
   sur la même grille de 5 s. Chercher le décalage λ ∈ [−10 min, +10 min] maximisant l'accord.
   Appliquer λ. Si |λ| > 5 min → drapeau HC_LAG_SUSPECT.
   Motif : deux appareils, deux horloges, plus une latence d'endormissement réellement
   différente entre poignet et cheville. Sans ce recalage, on impute des CLM au mauvais stade.
2. ACCORD. Calculer κ de Cohen entre s_accel et s_hc après recalage, et ΔTST.
   Rapporté systématiquement (critère P5 de la v1).
3. CORRECTION APPRISE. Sur les nuits disposant des deux masques (≥ 3 requises),
   ajuster  TST_HC ≈ α · TST_accel + β  (régression robuste, ou simplement la médiane du
   ratio si n < 5). Sur les nuits sans HC, appliquer la correction et marquer TST_CORRECTED.
   Si n < 3 → aucune correction, uniquement l'encadrement du point 4.
4. ENCADREMENT. Toujours produire les QUATRE PlmiResult (2 règles × 2 masques) et publier
   plmiLowerBound = min, plmiUpperBound = max sur les deux masques, à règle fixée.
   L'UI affiche l'intervalle, pas le point.
```

Quand HC répond, le masque **HEALTH_CONNECT** est primaire (il apporte les stades, donc la répartition N1/N2/N3/REM des CLM, qui est le contrôle de plausibilité biologique du niveau 5 de la v1, **et** il rompt la circularité). Quand HC ne répond pas, le masque **ACCEL_IMMOBILITY** corrigé devient primaire, `stage = SLEEP` indifférencié, et la répartition par stade n'est pas produite.

Un point de règle à ne pas manquer : l'AASM v3 exige qu'**au moins une partie de chaque CLM tombe dans une époque de sommeil**, alors que WASM 2016 autorise explicitement une série à traverser une transition sommeil/éveil (2.4.4). Les deux implémentations doivent donc consommer le masque **différemment** ; un masque unique branché sur un `SeriesRule` paramétrique produirait un résultat faux pour l'une des deux.

#### 3.6.5 Quand Health Connect ne répond JAMAIS — sur aucune nuit

C'est le cas qu'il faut traiter explicitement, parce que la couche 3 disparaît et qu'il ne reste que les couches 1 et 2, qui atténuent la circularité sans la supprimer. Quatre réponses, par ordre de préférence.

**(a) Le journal de sommeil manuel — la solution la moins chère et la meilleure.** Deux champs dans l'UI du téléphone : heure de coucher, heure de lever. C'est un dénominateur **totalement indépendant du signal**, donc strictement non circulaire. Rappel : van Hees 2015 lui-même **exigeait** un journal ; la version sans journal (van Hees 2018) est une extension postérieure et moins précise. Dix lignes de code côté `phone`, un `DiaryWindow(bedTimeMs, riseTimeMs)` optionnel côté `algo`, et le problème structurel disparaît. **C'est la recommandation.**

**(b) Basculer le dénominateur sur le SPT plutôt que sur le TST.** Le SPT ne dépend que des **deux transitions extrêmes** de la nuit (première et dernière bouffée d'inactivité ≥ 15 min), qui sont loin du cœur dense en PLMS. Il est donc quasi insensible à la circularité, là où le TST y est directement exposé via le WASO. `plmiSpt = plmsCount / SPT_h` est produit systématiquement et devient **l'indice primaire en l'absence de HC**. Il est numériquement plus bas qu'un PLMI/TST (le SPT étant plus long que le TST) — il ne doit donc **jamais** être comparé au seuil de 15/h, ce qui doit être écrit dans l'UI et non seulement dans le code.

**(c) Faire du PI de Ferri l'indice primaire.** Le PI est un **ratio d'intervalles sur intervalles** : numérateur et dénominateur viennent tous deux du côté « mouvement ». **Il n'a aucun dénominateur temporel, donc aucune circularité, ni aucune dépendance au masque de sommeil.** C'est structurellement la métrique la plus robuste dont nous disposons, et la littérature le confirme indépendamment : sa variabilité inter-nuits est **6,5 fois plus faible** que celle du PLMI en SJSR et 2 fois plus faible en PLMD (Ferri et al., Sleep Med 2013;14:293-296). Seuil de référence 0,50. Condition de validité : ≥ 10 LM/h. En l'absence définitive de HC, **le rapport doit mettre le PI en avant et le PLMI en second**, ce qui est aussi le meilleur choix scientifique, pas seulement un repli.

**(d) Refuser la comparaison au seuil.** Si (a) n'est pas fourni, la sortie est : `piValue`, `plmsCount` brut par nuit, `plmiSpt` avec son encadrement — et **aucun affichage « PLMI = X, seuil = 15 »**. Le champ `denominatorIndependence ∈ {INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR}` accompagne chaque résultat et pilote directement ce que l'UI a le droit d'afficher.

### 3.7 Traitement incrémental et nuit tronquée (chunks de 5 min)

Le passage à des chunks de 5 min poussés toutes les ~15 min change le contrat d'exécution : l'analyse **peut** être incrémentale, et la nuit **peut** s'arrêter brutalement à 3 h du matin (batterie, crash, montre retirée).

#### 3.7.1 Ce que la chaîne v2 exige et ce qu'elle tolère

**La chaîne v2 n'exige PAS la nuit entière — mais son résultat de référence, oui.** Il faut distinguer deux modes, et ne jamais les confondre dans la base.

| Étape | Streamable ? | Contrainte |
|---|---|---|
| −1 intégrité | oui | purement local, sauf contrôles inter-blocs (fenêtre de 1 bloc) |
| 0 ligne de temps | oui | `fs_session` doit être ré-estimé et **figé après ~15 min** de données, sinon la grille change rétroactivement |
| 1 gravité / mouvement | **oui, nativement** | biquads déjà `stateful streaming` (exigence v1, piège nº 3) |
| 2 enveloppes | oui | sommes glissantes, latence `W_c/2 = 0,25 s` |
| 3 plancher | **non en bilatéral** | variante causale requise (ci-dessous) |
| 4–5 détection CLM | oui, avec latence | latence = `floorWinSec/2 + settle` en bilatéral, `settle` seul en causal |
| 6 séries | oui | machine d'états incrémentale ; une série reste « ouverte » jusqu'à `imiMaxSec` de silence |
| 3.1 posture | oui | latence `τ_p + stableSec = 12 s` |
| 3.3 autocalibration | **non** | exige la couverture de la sphère, donc plusieurs postures : disponible seulement en fin de nuit |
| 3.6 masque | **non** | le SPT exige la dernière bouffée d'inactivité de la nuit |
| 3.6.3 point fixe | non | exige le masque |

**Conclusion architecturale : deux modes, un seul résultat de référence.**

- **MODE PROVISOIRE (incrémental, toutes les ~15 min).** Filtres, enveloppes, posture, détection CLM et machine d'états de séries tournent en flux avec un **plancher causal**. Produit : compte de CLM, compte de séries fermées, PI provisoire, et un `plmiSpt` provisoire calculé sur le temps écoulé. Destiné **uniquement à l'affichage temps réel** et au diagnostic de qualité en cours de nuit (« la sensibilité s'est effondrée, le bracelet a bougé »). **Ne s'écrit jamais dans `plm_result`.**
- **MODE DÉFINITIF (au réveil, sur toute la session).** Rejoue l'intégralité de la chaîne depuis les chunks, avec le plancher bilatéral, l'autocalibration complète, le masque de sommeil complet, le point fixe à 2 itérations et la fusion HC. **Seul mode qui produit les 4 lignes `plm_result`.**

Le rejeu complet coûte O(n) sur 1,44 × 10⁶ échantillons : quelques centaines de millisecondes sur un téléphone. **Il n'y a aucune raison de conserver un résultat incrémental comme résultat final**, et la tentation de le faire pour « économiser » serait une régression méthodologique.

**Variante causale du plancher (mode provisoire uniquement).** C'est ici que la proposition 3 du relecteur trouve sa place légitime :

```
Passe 1 : floor₀(t) = p25 de env sur fenêtre CAUSALE DÉCALÉE [t − 125 s, t − 5 s]
Passe 2 : masque M = { i : env[i] > k_excl · floor₀[i] }        (mêmes k_excl)
Passe 3 : floor(t)  = médiane de env sur la même fenêtre, restreinte à {i ∉ M}
Puis    : max avec Θ_abs / k_on, comme en bilatéral.
```

Le décalage de 5 s empêche l'événement en cours de contaminer son propre plancher ; la fenêtre de 120 s conserve la robustesse. Le biais de retard de 35 s décrit en §1.3 subsiste — il est **acceptable en provisoire, inacceptable en définitif**, ce qui est exactement pourquoi les deux modes existent. Le champ `floorMode ∈ {BILATERAL, CAUSAL_LAGGED}` accompagne tout résultat.

#### 3.7.2 Ce qui se passe si la nuit s'arrête à 3 h du matin

Cinq conséquences, chacune avec une règle.

1. **Le SPT n'a pas de fin.** La dernière bouffée d'inactivité est ouverte. Règle : `SPT_end = dernier échantillon valide`, drapeau `TRUNCATED_NIGHT`. Ne pas extrapoler.
2. **Le PLMI d'une nuit tronquée est biaisé À LA HAUSSE, et de manière non corrigeable.** Les PLMS ne sont pas répartis uniformément : ils se concentrent en N1/N2 et en première moitié de nuit. Une nuit coupée à 3 h échantillonne préférentiellement la partie riche. C'est précisément ce que mesure déjà le split-half `plmiFirstHalf / plmiSecondHalf` de la v1 — sur une nuit tronquée, ce ratio est le meilleur indicateur de l'ampleur du biais et doit être affiché à côté du chiffre.
3. **Portes de publication, en dur** :
   - `analysableTstMin ≥ 240` (4 h) → PLMI publié normalement.
   - `180 ≤ analysableTstMin < 240` → PLMI publié avec `TRUNCATED_NIGHT`, **exclu de la tendance multi-nuits, de l'ICC et du calcul de la correction TST** (§3.6.4 point 3).
   - `analysableTstMin < 180` (3 h) → **aucun PLMI publié.** Sont publiés : `plmsCount`, `piValue` si `piValid`, et le rapport de qualité. Le PI reste valide parce qu'il n'a pas de dénominateur temporel — c'est encore un point pour §3.6.5(c).
4. **Les 60 dernières secondes ont un plancher tronqué** (fenêtre bilatérale amputée) → `FLOOR_EXTRAPOLATED` systématique en fin de nuit tronquée. Aucune action supplémentaire : la mécanique de l'étape 3 le gère et le signale.
5. **La série en cours au moment de la coupure** est soumise à la règle de l'étape 6 : conservée si elle compte déjà ≥ 4 CLM, sinon abandonnée et comptée dans `truncatedSeriesDropped`. Sur une nuit tronquée, ce compteur doit être affiché : il chiffre le biais à la baisse qui s'oppose partiellement au biais à la hausse du point 2. Les deux ne se compensent pas et il ne faut pas prétendre le contraire.

**Bénéfice collatéral des chunks de 5 min à noter** : un crash coûte au plus 5 min de données au lieu de 30, ce qui rend le critère P1 de la v1 (« plus grand trou < 5 s, cumul < 2 min ») nettement plus facile à tenir en cas de reprise après reboot.

---

## 4. API publique du module `algo`

Fonctions pures, aucune I/O, aucune horloge murale, `fs` toujours explicite, aucun `import android.*`. **`:algo` ne doit pas dépendre de `:format`** : l'entrée est une interface minimale, l'adaptateur vit dans `:phone`. Cela garde `:algo` testable sans le codec, permet d'injecter du synthétique, et — point important au vu de l'étape −1 — permet à `algo` de revalider les timestamps sans hériter des garanties (absentes) du CRC de `:format`.

### 4.1 `com.pendulum.algo.model`

```kotlin
package com.pendulum.algo.model

/** Entrée minimale. Implémentée par un adaptateur au-dessus de com.pendulum.format.DecodedBlock. */
interface SampleBlock {
    val tFirstNs: Long
    val tLastNs: Long
    val flags: Int
    val x: FloatArray   // g
    val y: FloatArray
    val z: FloatArray
}

/** Signal tri-axial sur grille uniforme. NaN = échantillon absent. */
class TriAxial(
    val fsHz: Double,
    val t0Ns: Long,
    val x: FloatArray, val y: FloatArray, val z: FloatArray,
) {
    val n: Int get() = x.size
    fun tNs(i: Int): Long = t0Ns + Math.round(i * 1e9 / fsHz)
    fun tMsRel(i: Int): Long = Math.round(i * 1000.0 / fsHz)
}

class Signal1D(val fsHz: Double, val t0Ns: Long, val v: FloatArray) { val n: Int get() = v.size }

data class DualEnvelope(
    val coarse: Signal1D, val fine: Signal1D,
    val coarseWinSec: Double, val fineWinSec: Double,
)

enum class GapKind { MICRO, BLIND, SEGMENT_BREAK }
data class Gap(val fromIdx: Int, val toIdx: Int, val kind: GapKind, val durationSec: Double)

/** Intervalle continu et analysable. Bornes en index de la grille uniforme. */
data class Segment(val fromIdx: Int, val toIdx: Int) { val length: Int get() = toIdx - fromIdx }

// --- Intégrité (étape −1) ---

enum class IntegrityViolation {
    BAD_COUNT, BAD_TIMESTAMP_ORDER, IMPLAUSIBLE_RATE, NON_MONOTONIC, OVERLAP,
    IMPLAUSIBLE_GAP, BAD_CHUNK_INDEX, IMPOSSIBLE_JERK, SATURATED, GRAVITY_IMPLAUSIBLE,
    FLAG_INCONSISTENT,
}

data class IntegrityReport(
    val blocksTotal: Int, val blocksRejected: Int,
    val byViolation: Map<IntegrityViolation, Int>,
    val rejectedFraction: Double,
    val decodeSuspect: Boolean,
) { val acceptable: Boolean get() = rejectedFraction <= 0.01 && !decodeSuspect }

data class IntegrityConfig(
    val maxRateDeviation: Double = 0.20,
    val maxGapNs: Long = 14L * 3600 * 1_000_000_000,
    val maxJerkG: Float = 8.0f,
    val saturationFraction: Double = 0.05,
    val gravityRangeG: ClosedFloatingPointRange<Float> = 0.80f..1.20f,
)

data class FsEstimate(
    val fsSessionHz: Double,
    val fsNominalHz: Double,
    val blocksRejected: Int,
    val clockDriftPpm: Double,          // dérive SensorEvent.timestamp vs horloge murale
    val clockDriftSuspect: Boolean,
)

data class Timeline(
    val signal: TriAxial,               // grille uniforme à targetFsHz, NaN dans les trous
    val gaps: List<Gap>,
    val segments: List<Segment>,
    val blindZones: List<Segment>,
    val fs: FsEstimate,
    val offBody: List<Segment>,
    val integrity: IntegrityReport,
    val analysableSec: Double,
    val truncated: Boolean,             // la session s'arrête sans marqueur de fin propre
)

// --- Événements ---

object ClmFlags {
    const val POSTURAL             = 1 shl 0
    const val GROSS_BODY           = 1 shl 1
    const val LM_LONG              = 1 shl 2   // > clmMaxSec : casse la série, jamais un CLM
    const val TRUNCATED            = 1 shl 3
    const val IN_BLIND_ZONE        = 1 shl 4
    const val FLOOR_EXTRAPOLATED   = 1 shl 5
    const val TRANSMITTED_SUSPECT  = 1 shl 6
    const val ABS_FLOOR_LIMITED    = 1 shl 7   // le seuil était dominé par Θ_abs
    const val CAL_FLOOR_LIMITED    = 1 shl 8   // le seuil était dominé par f_cal·gainCal
    const val DURING_WAKE          = 1 shl 9
    const val OFF_BODY             = 1 shl 10
    const val RESP_SUSPECT         = 1 shl 11  // série à IMI médian dans la bande apnéique
}

enum class ClmRejectReason { TOO_SHORT, MORPHOLOGY, POSTURAL, GROSS_BODY, BLIND_ZONE, OFF_BODY, TRUNCATED }

data class Clm(
    val onsetIdx: Int, val offsetIdx: Int,
    val onsetMsRel: Long, val durationMs: Int,
    val peakAmpG: Float, val medianAmpG: Float,
    val noiseFloorG: Float, val thresholdOnG: Float, val thresholdOffG: Float,
    val tiltChangeDeg: Float, val tiltExcursionDeg: Float,
    val flags: Int,
    val reject: ClmRejectReason?,
) {
    val isClm: Boolean get() = reject == null && (flags and ClmFlags.LM_LONG) == 0
}

data class PostureChange(val atIdx: Int, val atMsRel: Long, val deltaDeg: Float, val settleMs: Int)

enum class SeriesRule { AASM_V3, WASM_2016 }
enum class ShortImiPolicy { BREAK_SERIES, SKIP_LATER }

data class PlmSeries(
    val rule: SeriesRule,
    val clmIndices: IntArray,          // index dans la liste de Clm retenus
    val imiSec: FloatArray,            // taille = clmIndices.size - 1
    val truncatedAtStart: Boolean, val truncatedAtEnd: Boolean,
    val duringSleepFraction: Float,
)

// --- Masque ---

enum class Stage { WAKE, SLEEP, LIGHT, DEEP, REM, AWAKE_IN_BED, OUT_OF_BED, UNKNOWN }
enum class MaskSource { ACCEL_IMMOBILITY, HEALTH_CONNECT, DIARY, FUSED }

/** Dénominateur : d'où vient-il, et est-il circulaire ? Pilote ce que l'UI a le droit d'afficher. */
enum class DenominatorIndependence { INDEPENDENT_HC, INDEPENDENT_DIARY, SPT_QUASI_INDEPENDENT, CIRCULAR }

data class SleepWindow(val startMsRel: Long, val endMsRel: Long, val stage: Stage)

/** Journal de sommeil manuel : dénominateur totalement indépendant du signal (§3.6.5-a). */
data class DiaryWindow(val bedTimeMsRel: Long, val riseTimeMsRel: Long)

data class SleepMask(
    val windows: List<SleepWindow>,
    val source: MaskSource,
    val sptMin: Double, val tstMin: Double, val wasoMin: Double,
    val analysableTstMin: Double,      // TST ∩ segments valides ∩ hors zones aveugles ∩ hors off-body
    val analysableSptMin: Double,
    val corrected: Boolean,
    val lagAppliedMs: Long,
    val independence: DenominatorIndependence,
    val fixedPointConverged: Boolean,
)

data class MaskAgreement(val kappa: Double, val tstDeltaMin: Double, val overlapPct: Double, val bestLagMs: Long)

// --- Calibration ---

data class SensorCalibration(val offsetG: FloatArray, val scale: FloatArray, val residualG: Float, val valid: Boolean)

enum class GainSource { RITUAL, GROSS_BODY, NONE }

data class NightCalibration(
    val sensor: SensorCalibration?,
    val gainCalG: Float, val floorCalG: Float, val snrCal: Float,
    val gainSource: GainSource,
    val outlierVsBaseline: Boolean,
)

// --- Résultats ---

enum class RespiratoryConfidence { HIGH, MEDIUM, LOW }
enum class FloorMode { BILATERAL, CAUSAL_LAGGED }
enum class PublicationGate { FULL, TRUNCATED_NO_TREND, NO_PLMI }

data class PiResult(
    val periodicityIndex: Double, val valid: Boolean,
    val totalIntervals: Int, val lmRatePerHour: Double,
)

data class PlmiResult(
    val rule: SeriesRule, val maskSource: MaskSource,
    val plmsCount: Int, val plmwCount: Int, val isolatedCount: Int, val shortImiCount: Int,
    val tstMin: Double, val analysableTstMin: Double, val sptMin: Double, val wasoMin: Double,
    val plmi: Double, val plmiSpt: Double, val plmw: Double,
    val pi: PiResult,
    val plmiFirstHalf: Double, val plmiSecondHalf: Double,
    val imiHistogram: IntArray, val imiBinEdgesSec: FloatArray,
    val truncatedSeriesDropped: Int,
    val plmiRespWorstCase: Double,
    val respiratoryConfidence: RespiratoryConfidence,
    val independence: DenominatorIndependence,
    val gate: PublicationGate,
    val floorMode: FloorMode,
    val paramsHash: String,
)

data class QualityReport(
    val analysableFraction: Double, val gapCount: Int, val gapTotalSec: Double, val longestGapSec: Double,
    val postureChanges: Int, val grossBodyMovements: Int,
    val medianFloorG: Float, val floorVsBaselineRatio: Double,
    val offBodyFraction: Double, val clockDriftSuspect: Boolean,
    val integrity: IntegrityReport,
    val truncatedNight: Boolean, val maskNonConvergent: Boolean,
    val warnings: List<String>,
)

data class NightAnalysis(
    val timeline: Timeline,
    val calibration: NightCalibration,
    val clms: List<Clm>, val postures: List<PostureChange>,
    val masks: Map<MaskSource, SleepMask>, val agreement: MaskAgreement?,
    val results: List<PlmiResult>,      // 4 lignes : 2 règles × 2 masques
    val plmiLowerBound: Double, val plmiUpperBound: Double,
    val quality: QualityReport,
    val algoVersion: String,
)
```

### 4.2 `com.pendulum.algo.dsp`

```kotlin
package com.pendulum.algo.dsp

/** Biquad forme directe II transposée. Stateful, streaming — jamais réinitialisé par bloc. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun reset()
    fun resetToDc(dcValue: Float)     // état stationnaire pour une entrée constante : évite le transitoire de 1 g
    fun step(x: Float): Float
    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray
    fun snapshot(): DoubleArray       // pour le mode incrémental
    fun restore(state: DoubleArray)
}

class BiquadCascade(private val stages: List<Biquad>) {
    fun reset(); fun resetToDc(dcValue: Float); fun step(x: Float): Float
    fun process(src: FloatArray, dst: FloatArray = FloatArray(src.size)): FloatArray
    fun snapshot(): Array<DoubleArray>; fun restore(state: Array<DoubleArray>)
    val settlingTimeSec: Double
}

object Filters {
    fun butterHighpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade
    fun butterLowpass(fsHz: Double, fcHz: Double, order: Int = 2): BiquadCascade
    fun butterBandpass(fsHz: Double, fLowHz: Double, fHighHz: Double, order: Int = 2): BiquadCascade
}

/** Étape −1 : l'algo ne fait confiance ni au CRC ni à l'entête de bloc. */
object Integrity {
    fun check(blocks: List<SampleBlock>, nominalHz: Double,
              cfg: IntegrityConfig = IntegrityConfig()): Pair<List<SampleBlock>, IntegrityReport>
}

object Rate {
    fun estimate(blocks: List<SampleBlock>, nominalHz: Double, outlierTol: Double = 0.05): FsEstimate
    fun clockDrift(wallMs: LongArray, eventNs: LongArray): Double
}

data class TimelineConfig(
    val targetFsHz: Double = 50.0,
    val gapMicroSec: Double = 0.10, val gapSegmentSec: Double = 2.0,
    val settleSec: Double = 2.0, val warmupSec: Double = 5.0,
    val fsOutlierTol: Double = 0.05,
    val integrity: IntegrityConfig = IntegrityConfig(),
)
object TimelineBuilder {
    fun build(blocks: List<SampleBlock>, nominalHz: Double, cfg: TimelineConfig = TimelineConfig()): Timeline
}

data class GravitySplit(val gravity: TriAxial, val linear: TriAxial)
object Gravity {
    fun split(raw: TriAxial, segments: List<Segment>, fcGravityHz: Double = 0.15,
              fcHpHz: Double = 0.50, fcLpHz: Double = 8.0, hpOrder: Int = 2): GravitySplit
    fun unitVectors(gravity: TriAxial): TriAxial
    fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float
}

object Envelope {
    fun magnitudeL2(t: TriAxial): Signal1D
    fun rms(s: Signal1D, winSec: Double, segments: List<Segment>): Signal1D
    fun dual(m: Signal1D, segments: List<Segment>, coarseSec: Double = 0.50, fineSec: Double = 0.15): DualEnvelope
}

data class NoiseFloorConfig(
    val winSec: Double = 120.0, val pass1Percentile: Int = 25,
    val excludeFactor: Double = 4.0, val minValidFraction: Double = 0.25,
    val mode: FloorMode = FloorMode.BILATERAL,
    val causalLagSec: Double = 5.0,          // utilisé seulement si mode == CAUSAL_LAGGED
)
object NoiseFloor {
    /** boundaries : frontières de segment ET de changement de posture — la fenêtre ne les franchit jamais. */
    fun estimate(env: Signal1D, segments: List<Segment>, boundaries: IntArray,
                 cfg: NoiseFloorConfig = NoiseFloorConfig()): Pair<Signal1D, BooleanArray>
}

object Calibration {
    fun autocalibrate(raw: TriAxial, segments: List<Segment>,
                      staticWinSec: Double = 10.0, sdThresholdG: Float = 0.013f,
                      minSphereSpanG: Float = 0.30f): SensorCalibration
    fun apply(raw: TriAxial, c: SensorCalibration): TriAxial
    /** Rituel guidé : les instants du métronome sont la vérité terrain. */
    fun fromRitual(env: Signal1D, stillFromMs: Long, stillToMs: Long, kickOnsetsMs: LongArray): NightCalibration
    fun fromGrossBodyMovements(clms: List<Clm>): NightCalibration
}
```

### 4.3 `com.pendulum.algo.detect`

```kotlin
package com.pendulum.algo.detect

data class ThresholdConfig(
    val kOn: Double = 8.0, val kOff: Double = 2.5,
    val absFloorG: Float = 0.020f, val calFraction: Double = 0.12,
)
data class ClmConfig(
    val thresholds: ThresholdConfig = ThresholdConfig(),
    val offHoldSec: Double = 0.50, val minDurSec: Double = 0.50, val maxDurSec: Double = 10.0,
    val morphologyWinSec: Double = 0.50, val grossBodyFactor: Double = 40.0,
    val refractorySec: Double = 2.0, val minExcursionDeg: Double = 1.5,
)
data class PostureConfig(
    val tauSec: Double = 2.0, val postureDeg: Double = 20.0,
    val stableDeg: Double = 10.0, val stableSec: Double = 10.0, val guardSec: Double = 2.5,
)

object PostureDetector {
    fun detect(gravity: TriAxial, segments: List<Segment>, cfg: PostureConfig = PostureConfig()): List<PostureChange>
}

object ClmDetector {
    fun detect(env: DualEnvelope, floor: Signal1D, floorExtrapolated: BooleanArray,
               gravity: TriAxial, segments: List<Segment>, blindZones: List<Segment>,
               postures: List<PostureChange>, calibration: NightCalibration,
               cfg: ClmConfig = ClmConfig(), postureCfg: PostureConfig = PostureConfig()): List<Clm>
}

data class SeriesConfig(
    val rule: SeriesRule,
    val imiMinSec: Double, val imiMaxSec: Double = 90.0, val minClmPerSeries: Int = 4,
    val shortImiPolicy: ShortImiPolicy, val breakOnLongLm: Boolean, val requirePortionInSleep: Boolean,
) {
    companion object {
        fun aasmV3()   = SeriesConfig(SeriesRule.AASM_V3,   5.0,  90.0, 4, ShortImiPolicy.SKIP_LATER,   false, true)
        fun wasm2016() = SeriesConfig(SeriesRule.WASM_2016, 10.0, 90.0, 4, ShortImiPolicy.BREAK_SERIES, true,  false)
    }
}
object SeriesBuilder { fun build(events: List<Clm>, mask: SleepMask, fsHz: Double, cfg: SeriesConfig): List<PlmSeries> }

data class PeriodicityConfig(
    val imiLowExclusiveSec: Double = 10.0, val imiHighInclusiveSec: Double = 90.0,
    val minRunLength: Int = 3, val minLmRatePerHour: Double = 10.0,
)
object Periodicity {
    fun ferriIndex(clms: List<Clm>, mask: SleepMask, fsHz: Double,
                   cfg: PeriodicityConfig = PeriodicityConfig()): PiResult
}

data class PlmiConfig(
    val respSuspectImiLowSec: Double = 25.0, val respSuspectImiHighSec: Double = 45.0,
    val imiHistogramBinSec: Double = 2.0, val imiHistogramMaxSec: Double = 100.0,
    val minTstFullMin: Double = 240.0, val minTstAnyMin: Double = 180.0,
)
object Plmi {
    fun compute(clms: List<Clm>, series: List<PlmSeries>, mask: SleepMask, fsHz: Double,
                rule: SeriesRule, respiratory: RespiratoryConfidence,
                pi: PiResult, floorMode: FloorMode, truncated: Boolean,
                cfg: PlmiConfig = PlmiConfig()): PlmiResult
}
```

### 4.4 `com.pendulum.algo.mask`

```kotlin
package com.pendulum.algo.mask

data class ImmobilityConfig(
    val epochSec: Double = 5.0, val angleDeg: Double = 5.0, val moveFactor: Double = 6.0,
    val sustainedMin: Double = 5.0, val sptMinMin: Double = 15.0,
    val maxFixedPointIterations: Int = 2, val convergenceTstFraction: Double = 0.25,
)

object ImmobilityMask {
    /**
     * @param ignoreIntervals intervalles (typiquement les CLM détectés) NEUTRALISÉS comme
     *   preuve de mobilité. Couche 1 de la réponse à la circularité (§3.6.3) : un PLMS est
     *   par définition un mouvement PENDANT le sommeil, pas une preuve d'éveil.
     */
    fun build(gravity: TriAxial, env: Signal1D, floor: Signal1D,
              segments: List<Segment>, offBody: List<Segment>,
              ignoreIntervals: List<Segment> = emptyList(),
              diary: DiaryWindow? = null,
              cfg: ImmobilityConfig = ImmobilityConfig()): SleepMask
}

data class FusionConfig(val maxLagMs: Long = 600_000, val lagStepMs: Long = 5_000, val minCoverage: Double = 0.50)
data class TstCorrection(val alpha: Double, val beta: Double, val nNights: Int, val valid: Boolean)

object MaskFusion {
    fun align(accel: SleepMask, hc: List<SleepWindow>, cfg: FusionConfig = FusionConfig()): MaskAgreement
    fun fuse(accel: SleepMask, hc: List<SleepWindow>?, diary: DiaryWindow?,
             cfg: FusionConfig = FusionConfig()): SleepMask
    fun fitCorrection(pairs: List<Pair<Double, Double>>): TstCorrection      // (TST_accel, TST_HC)
    fun applyCorrection(accel: SleepMask, c: TstCorrection): SleepMask
}
```

### 4.5 Points d'entrée — mode définitif et mode incrémental

```kotlin
package com.pendulum.algo

data class AlgoParams(
    val timeline: TimelineConfig = TimelineConfig(),
    val gravityFcHz: Double = 0.15, val hpFcHz: Double = 0.50, val lpFcHz: Double = 8.0, val hpOrder: Int = 2,
    val coarseEnvSec: Double = 0.50, val fineEnvSec: Double = 0.15,
    val noiseFloor: NoiseFloorConfig = NoiseFloorConfig(),
    val clm: ClmConfig = ClmConfig(), val posture: PostureConfig = PostureConfig(),
    val immobility: ImmobilityConfig = ImmobilityConfig(), val fusion: FusionConfig = FusionConfig(),
    val periodicity: PeriodicityConfig = PeriodicityConfig(), val plmi: PlmiConfig = PlmiConfig(),
) { fun hash(): String }

data class RitualMarkers(val stillFromMs: Long, val stillToMs: Long, val kickOnsetsMs: LongArray)

/** MODE DÉFINITIF. Seul mode qui produit des lignes plm_result. Rejoue toute la session. */
object PendulumPipeline {
    fun analyse(
        blocks: List<SampleBlock>,
        nominalRateHz: Double,
        hcWindows: List<SleepWindow>? = null,
        diary: DiaryWindow? = null,
        ritual: RitualMarkers? = null,
        baselineGainG: Float? = null,
        tstCorrection: TstCorrection? = null,
        respiratory: RespiratoryConfidence = RespiratoryConfidence.MEDIUM,
        params: AlgoParams = AlgoParams(),
    ): NightAnalysis
}

/**
 * MODE PROVISOIRE. Plancher causal, pas d'autocalibration, pas de masque complet.
 * Sortie destinée à l'AFFICHAGE TEMPS RÉEL uniquement — ne jamais persister dans plm_result.
 */
data class PartialAnalysis(
    val elapsedSec: Double,
    val clms: List<Clm>, val closedSeries: List<PlmSeries>, val openSeriesClmCount: Int,
    val piProvisional: PiResult,
    val plmiSptProvisional: Double,
    val quality: QualityReport,
    val floorMode: FloorMode,               // toujours CAUSAL_LAGGED
) { val isProvisional: Boolean get() = true }

/** État opaque et sérialisable porté d'une itération incrémentale à la suivante. */
class StreamState internal constructor(/* états de biquads, sommes glissantes, machine d'états séries */)

object PendulumStream {
    fun start(nominalRateHz: Double, params: AlgoParams = AlgoParams()): StreamState
    fun push(state: StreamState, newBlocks: List<SampleBlock>): PartialAnalysis
}
```

---

## 5. Générateur de signal synthétique à vérité terrain

`com.pendulum.algo.synth`. Déterministe : même `seed` → sortie bit-identique (test T13).

### 5.1 Modèle physique d'un CLM à la cheville

Un PLMS est une **réponse en triple flexion** : dorsiflexion du pied, flexion du genou, parfois de la hanche. Le capteur est à la cheville, quelques centimètres au-dessus de l'articulation talo-crurale. Deux contributions, de nature très différente :

**(1) Rotation du segment jambier autour du genou ou de la hanche.** Le capteur, à une distance `r` du centre de rotation, subit une accélération tangentielle `r·θ̈`, plus une accélération centripète `r·θ̇²` (d'ordre supérieur, gardée dans le modèle), plus une rotation du vecteur gravité vue dans le repère capteur.

**(2) Rotation pure de la cheville.** Le capteur étant sur la jambe **au-dessus** de l'axe, il ne se déplace quasiment pas : `r_eff ≈ 0`. **C'est le mécanisme physique du taux de manqués de Terrill et al.** Le modèle doit le représenter explicitement, sans quoi la vérité terrain surestime ce que le capteur peut voir.

**Profil angulaire — polynôme de jerk minimal d'ordre 5** (le geste balistique standard en biomécanique) :

```
s(u)   = 10u³ − 15u⁴ + 6u⁵          u ∈ [0,1]
θ(t)   = θ_max · s(t / T_rise)                        phase de flexion
       = θ_max − d · s(v)                             maintien entretenu, durée T_hold
       = θ_max · (1 − s((t − t₂)/T_fall))             phase de retour

θ̈(t)   = (θ_max / T_rise²) · (60u − 180u² + 120u³)    impulsion BIPOLAIRE
```

Les extrema de `θ̈` sont en `u = (3 ± √3)/6 = 0,2113 et 0,7887`, de valeur `± 5,7735 · θ_max / T_rise²` (valeur exacte).

**Signature accélérométrique d'un CLM complet : quadripolaire** — une paire bipolaire à la flexion, une paire bipolaire au retour, séparées par la phase de maintien. Celle-ci n'est pas muette : voir « Phase de maintien » plus bas.

**Contenu spectral.** Le pic de l'impulsion bipolaire est à `f_pic ≈ 0,8 / T_rise`. Avec `T_rise ∈ [0,15 ; 0,50] s`, cela donne **f_pic ∈ [1,6 ; 5,3] Hz**. C'est cohérent avec toutes les bandes publiées et **incompatible avec les « paquets 10-15 Hz » de la v1**, qu'il faut supprimer.

**Amplitude, en g :**

```
a_tang(t) = r · θ̈(t) / 9,80665
a_grav(t) = sin(θ(t) − θ₀) − sin(θ₀)          composante de projection de la gravité, en g
a_cent(t) = r · θ̇(t)² / 9,80665
```

Vérification de calibration du modèle contre la littérature :

| Cas | θ_max | T_rise | r | crête tangentielle |
|---|---|---|---|---|
| Petit CLM | 4° | 0,45 s | 0,15 m | **30 mg** |
| CLM moyen | 10° | 0,35 s | 0,22 m | **184 mg** |
| Gros CLM | 20° | 0,25 s | 0,30 m | **985 mg** |
| Cheville seule (invisible) | 15° | 0,30 s | 0,02 m | **34 mg** — et 0 mg si le boîtier est sur le tibia |

Le modèle produit donc naturellement la plage **15–600 mg** qui encadre le seuil de détection de Sicbaldi (15 mg) et la magnitude typique de mouvement pendant le sommeil (377 ± 63 mg), et il produit aussi les événements invisibles. **La physique se cale d'elle-même sur les chiffres publiés** — c'est le meilleur argument de validité du modèle disponible en l'absence de spectre PLMS publié.

Noter que pour les grands angles la composante `a_grav` **domine** : à 20°, `sin(20°) = 0,342 g` sur ~0,25 s, soit un contenu vers 1,6 Hz d'amplitude 342 mg, comparable au terme tangentiel. Les deux doivent être modélisées — et c'est exactement ce qui rend la caractéristique `tiltExcursionDeg` du détecteur informative.

**Durées.** Durée totale d'un CLM tirée d'une loi log-normale de moyenne **4,2 s** (Sforza 2005, SJSR) — à comparer aux **4,5 ± 0,4 s** de Sicbaldi 2025 (mouvements de sommeil) — tronquée à [0,5 ; 10] s. Le mode ~2–3 s parfois avancé n'est **confirmé par aucun histogramme publié** — ne pas le coder comme acquis.

> **Correction du 2026-07-31 — la dispersion n'est pas publiée.** Ce paragraphe portait « 4,2 ± 1,4 s » tandis que `02-science.md`, `references.md` et les tableaux portaient « 4,2 ± 0,14 s ». Retour à la source : le §2.5 de Sforza dit « *results in the text and in the tables are expressed as mean ± standard error of the mean* », et le groupe SJSR compte 11 patients. **Le ± 0,14 est une erreur type, pas un écart-type** ; l'écart-type inter-patients des moyennes individuelles vaut `0,14 × √11 ≈ 0,46 s`, et la dispersion **événement par événement** n'est publiée nulle part. Aucun des deux chiffres n'était donc « la bonne valeur ». Le générateur garde **σ = 1,4 s** en le déclarant pour ce qu'il est — un **choix de modélisation** (CV = 0,33, quantiles ±2σ à ~1,9–8,0 s, à l'intérieur de la fenêtre de cotation 0,5–10 s de Coleman) — et non une donnée de Sforza. Un σ de 0,14 s ferait de la loi une masse de Dirac, incompatible avec l'existence même de cette fenêtre et avec la moyenne de 3,2 s du groupe TMPJ du même article.

**Phase de maintien — elle n'est pas immobile.** Avec une durée de 4,2 s et `T_rise ∈ [0,15 ; 0,50] s`, un maintien à angle strictement constant durerait ~3,6 s. Pendant ce temps `θ̇ = θ̈ = 0` et le terme gravitaire est un palier continu : après le passe-haut à 0,5 Hz, **le signal est nul**. Le modèle dessinait donc ~2,3 s de silence accélérométrique au milieu de chaque mouvement, et le détecteur — en appliquant littéralement `offHoldSec = 0,50 s` — en émettait deux. C'était la cause racine de l'échec de T6 (voir `07-validation.md` §4.1 et §5.5).

**La source de la durée interdit cette forme.** Sforza mesure ses 4,2 s **avec le PAM-RL** : seuil d'entrée 200 mg, seuil de décroissance 100 mg, et **drop-out time de 1 s** (§2.4 de l'article) — un « kick » ne se termine qu'après une seconde entière sous 100 mg. Un événement contenant 2,3 s de silence aurait été scindé par le PAM-RL lui-même, et la durée moyenne publiée aurait été de l'ordre de la moitié. Le même raisonnement vaut si l'on lit les 4,2 s comme une durée de bouffée EMG au sens de Coleman : 4,2 s de bouffée, c'est 4,2 s de contraction active. Et le corpus de règles le dit lui-même : la règle d'offset à 0,50 s n'existe que parce qu'un mouvement de jambe est un **train d'activations** séparées de moins d'une demi-seconde.

Le maintien est donc modélisé comme une **flexion entretenue** : l'angle oscille autour de `θ_max` en creux successifs, chaque demi-creux étant un profil à jerk minimal comme la flexion elle-même. Deux constantes, aucune ajustée sur un test :

| Grandeur | Valeur | D'où elle vient |
|---|---|---|
| Période d'un creux | `2 · T_rise` | L'échelle balistique propre du mouvement. Pic spectral d'un demi-creux = `0,8/T_rise`, soit la bande 1,6–5,3 Hz déjà publiée ci-dessus ; cadence de répétition `1/(2·T_rise)` ∈ [1,0 ; 3,3] Hz, la bande clonique |
| Profondeur des creux | `0,50 · θ_max` | Donne un pic d'accélération de maintien égal à **0,50 ×** le pic balistique — exactement le rapport 100 mg / 200 mg du PAM-RL, le minimum qu'un événement doit soutenir pour avoir été compté comme un seul kick |

Les raccords sont **C²** (`s'(0) = s'(1) = s''(0) = s''(1) = 0`) : ni saut de vitesse ni saut d'accélération entre flexion, creux et retour, donc aucun artefact large bande introduit. Le tableau de calibration ci-dessus ne dépend que de `θ_max`, `T_rise` et `r` : il est **inchangé**, et `MovementModelTest` l'asserte désormais.

**Amplitudes.** Loi log-normale, médiane 80 mg, σ_log = 0,6 (facteur ~1,8 par écart-type), tronquée à [10 ; 800] mg. Le σ_log = 0,6 est un choix d'ingénierie, pas une donnée publiée : il est calibré pour que le quantile 5 % tombe sous le seuil de détection et le quantile 95 % au-dessus de 300 mg. Il pilote directement la pente de la courbe de sensibilité (test T5) et doit donc être un paramètre du générateur, pas une constante.

### 5.2 Structure d'une nuit synthétique

```
NightSpec
  durationH = 8.0            fsRealHz = 50.0 | 50.3 | 52.6 | dérive linéaire ±0,5 %
  trueSeries : liste de (nSeries, clmPerSeries, imiMeanSec, imiCvPct)
  isolatedClmPerHour, ankleOnlyFraction, gainMultiplier
  truncateAtH : Double?      // nuit coupée à 3 h du matin (test T18)
  distractors : DistractorSpec
  noise : NoiseSpec
```

**Distracteurs à générer (12 familles).** Les six premières sont indispensables ; les six suivantes couvrent les modes de défaillance identifiés en §1 et §3.

| # | Distracteur | Paramètres | Ce qu'il teste |
|---|---|---|---|
| 1 | Changements de posture | 15–40/nuit, rotation de ĝ de 20–120° en 0,5–3 s | §3.1, la première source de FP (transitoire jusqu'à 1 g) |
| 2 | Mouvements corporels grossiers | 20–60/nuit, 2–20 s, 300–2500 mg | Classifieur GBM + période réfractaire ; contamination du plancher |
| 3 | Artefact respiratoire | sinusoïde 0,20–0,33 Hz, 1–10 mg, modulée en amplitude | Coude bas du passe-haut (§1.2) |
| 4 | Vibration matelas | 50–500/nuit, transitoires 0,05–0,4 s, 3–40 mg, sonnerie amortie 8–20 Hz, **tilt inchangé** | Seuil de morphologie WASM 3.2.1-d (§3.2) |
| 5 | Bruit MEMS + quantification | 150–300 µg/√Hz + LSB 1/2048 g | Plancher absolu Θ_abs (§1.1) |
| 6 | Trous FIFO | 20–60 trous de 0,1–5 s + un trou long de 30–120 s | Étape 0, zones aveugles, rupture de série |
| 7 | Dérive de fs | 50,0 / 50,3 / 52,6 Hz + dérive linéaire | §3.4, test T8 |
| 8 | Off-body | montre sur la table 10 min, gravité constante + bruit seul | Exclusion numérateur **et** dénominateur |
| 9 | Tremblement hypnagogique du pied / ALMA | bouffées 0,3–4 Hz de 10–15 s, 2–8/nuit | Distracteur clinique réel, ne doit **jamais** être compté |
| 10 | Clusters non périodiques | rafales de 5–10 CLM à IMI aléatoire 1–8 s | Divergence AASM/WASM sur la rupture de série (§1.5-ii) |
| 11 | RRLM | séries à IMI 25–45 s couplées à la modulation respiratoire du distracteur 3 | Mesure du biais résiduel (§3.5) |
| 12 | Saut de gain mécanique | multiplication du canal mouvement par 0,7 à mi-nuit | Le bracelet qui se desserre en cours de nuit (§3.3) |

**Corruption de format à injecter (nouveau — teste l'étape −1)** : blocs à `tFirstNs > tLastNs`, blocs se chevauchant, `N` incohérent avec la taille du payload, saut d'horloge de 3 h, et repliement de signe à ±16 g simulant le bug `toRaw`. Ces cas ne passent pas par `NightSpec` mais par un mutateur post-génération, `Corrupt.inject(blocks, spec, seed)`.

### 5.3 Vérité terrain — deux jeux d'étiquettes

C'est la conséquence directe de Terrill : un unique jeu d'étiquettes rendrait tous les scores faux.

```kotlin
data class TruthEvent(
    val onsetMsRel: Long, val durationMs: Int,
    val peakG: Float, val thetaMaxDeg: Float, val tRiseSec: Float, val radiusM: Float,
    val ankleOnly: Boolean,            // r_eff ≈ 0 → invisible en accélérométrie
    val kind: TruthKind,               // PLM_IN_SERIES | ISOLATED | RRLM | GROSS_BODY | POSTURE | ALMA | MATTRESS
    val seriesId: Int?,
)

data class GroundTruth(
    val emgTruth: List<TruthEvent>,     // tous les mouvements générés — l'échelle EMG
    val accelTruth: List<TruthEvent>,   // sous-ensemble mécaniquement visible au capteur
    val postures: List<Long>, val gaps: List<Gap>,
    val expectedPlmiAasm: Double, val expectedPlmiWasm: Double,
    val expectedPlmw: Double, val expectedPi: Double,
    val expectedTstMin: Double, val expectedSptMin: Double,
    val gainMultiplierApplied: Float, val fsRealHz: Double,
    val truncatedAtMs: Long?,
)

class SynthNight(val blocks: List<SampleBlock>, val truth: GroundTruth, val seed: Long)
object NightSynth { fun generate(spec: NightSpec, seed: Long): SynthNight }
```

`accelTruth` = `emgTruth` privé des événements `ankleOnly` et de ceux dont la crête simulée tombe sous un seuil de visibilité physique (défaut 8 mg, soit ~0,4× le plancher absolu). `ankleOnlyFraction` par défaut **0,39**, calé sur Terrill (39,0 % des LM EMG sans mouvement détectable), plage 0,25–0,55.

**Toutes les métriques du détecteur sont scorées contre `accelTruth`.** `emgTruth` sert à une seule chose, mais elle est essentielle : mesurer et **rapporter** le facteur de conversion entre l'échelle accélérométrique et l'échelle EMG, c'est-à-dire le biais structurel à la baisse du PLMI mesuré. C'est le chiffre qui interdit de comparer directement notre PLMI au seuil de 15/h de l'ICSD-3.

### 5.4 Appariement et métriques

```kotlin
data class MatchResult(val tp: Int, val fp: Int, val fn: Int,
                       val sensitivity: Double, val precision: Double, val f1: Double,
                       val onsetBiasMs: Double, val onsetSdMs: Double)
object Scoring {
    fun match(detected: List<Clm>, truth: List<TruthEvent>, toleranceSec: Double = 1.0): MatchResult
    fun plmiError(actual: PlmiResult, truth: GroundTruth): Double
    fun sensitivityCurve(nights: List<SynthNight>, params: AlgoParams): List<Pair<Double, Double>>  // (ratio amp/plancher, Se)
}
```

Appariement glouton par ordre chronologique, tolérance d'onset **1,0 s**. Justification de la tolérance : la granularité clinique la plus fine est la borne basse de l'IMI (5 s), et l'enveloppe grossière de 0,5 s introduit un biais d'onset borné à ~0,25 s. Une tolérance de 1,0 s est large par rapport au biais et étroite par rapport à la règle.

### 5.5 Seuils de non-régression à asserter

Chaque test tourne sur ≥ 20 seeds ; le seuil porte sur la médiane, avec une assertion secondaire sur le pire cas quand c'est indiqué.

> **Ce tableau est la commande, pas le compte rendu. Ne pas le lire comme un état.** Les tests
> existent aujourd'hui et [`../07-validation.md`](../07-validation.md) §3 en tient la liste vivante,
> avec deux différences qu'il faut connaître avant de citer une ligne d'ici.
>
> **T6 n'est plus assis sur le même dénominateur.** Il se mesurait contre `accelTruth` entier ; il se
> mesure désormais contre `accelTruth` **restreint aux événements au-dessus du seuil d'onset**, et le
> prix de cette restriction est publié à côté, sous le numéro **T22** : environ **70 %** de la vérité
> mécaniquement visible tombe sous le seuil, et une fois la règle des quatre mouvements consécutifs
> appliquée, il ne survit que **6 %** de l'index vrai. Déplacer le dénominateur sans publier T22
> aurait été déplacer les poteaux ; c'est la paire qui en fait une mesure.
> [`../07-validation.md`](../07-validation.md) §4.1 est le récit complet, y compris les deux
> diagnostics successifs qui étaient eux-mêmes faux.
>
> **T12 n'est pas écrit.** Le balayage ±20 % décrit ici n'existe pas comme assertion. Ce qui existe
> est deux balayages paramétriques `@Disabled`, lancés à la main ([`../07-validation.md`](../07-validation.md)
> §4.1 et §4.4). La distinction compte, parce qu'un de ces balayages contredit un chiffre que ce
> projet a porté « en attendant T12 » pendant toute son histoire.
>
> T18 à T21 ci-dessous ne sont pas écrits non plus. C'est aussi la raison pour laquelle le test
> suivant s'appelle T22 et non T18 : réutiliser le numéro aurait créé une collision silencieuse dans
> un tableau que plusieurs documents citent.

| ID | Scénario | Assertion |
|---|---|---|
| T1 | Bruit MEMS seul, 30 min | **0 CLM**. Non négociable, pire cas inclus. |
| T2 | Respiration seule (8 mg à 0,25 Hz), 30 min | **0 CLM** |
| T3 | Vibrations matelas seules, 300 transitoires, 30 min | **≤ 2 CLM** (≤ 0,7 % de FP) |
| T4 | 40 changements de posture seuls | **0 CLM non tagué `POSTURAL`** ; rappel du détecteur de posture **≥ 0,95** |
| T5 | CLM isolés, amplitude croissante | Se **≤ 0,05** à 4× le plancher ; Se **≥ 0,95** à 16× ; Se ∈ [0,35 ; 0,65] à 8× (le seuil, par construction) |
| T6 | Nuit nominale : PLMI vrai 25/h, PI vrai 0,60, tous distracteurs | F1 **≥ 0,90** vs `accelTruth` ; \|ΔPLMI\|/PLMI **≤ 0,10** ; \|ΔPI\| **≤ 0,05** ; biais d'onset **≤ 300 ms**, écart-type **≤ 400 ms** |
| T7 | Nuit négative : PLMI vrai 2/h | PLMI estimé **≤ 5/h** (pas de faux positif de dépistage) |
| T8 | Invariance fs : 50,0 / 50,3 / 52,6 Hz, mêmes événements | ΔPLMI **≤ 2 %** entre les trois |
| T9 | Décimation 50 → 25 Hz | ΔPLMI **≤ 5 %** |
| T10 | 2 % de trous répartis + un trou de 90 s | ΔPLMI **≤ 3 %** vs la même nuit sans trous |
| T11 | Gain mécanique ×0,6 et ×1,8 | Calibration active : ΔPLMI **≤ 10 %**. Calibration inactive : ΔPLMI **> 40 %** (assertion inversée — si elle échoue, le volet B de §3.3 ne sert à rien et doit être retiré) |
| T12 | Sensibilité paramétrique, ±20 % sur chaque paramètre | Aucun paramètre ne fait varier le PLMI de **> 15 %** ; à ±10 %, la décision PLMI ≷ 15 ne bascule pour **aucun** paramètre |
| T13 | Déterminisme | Même seed → sortie **bit-identique** |
| T14 | Divergence des règles, cluster non périodique | PLMI_AASM > PLMI_WASM sur les nuits à clusters ; les deux égaux à ±2 % sur une nuit purement périodique à IMI 22 s |
| T15 | ALMA / tremblement du pied | **0 CLM** attribué à ces bouffées |
| T16 | Saut de gain à mi-nuit (×0,7) | \|PLMI_1ʳᵉ moitié − PLMI_2ᵉ moitié\| **≤ 20 %** — teste l'adaptativité du plancher |
| T17 | Golden file | 5 min de signal réel + JSON attendu, comparaison exacte des CLM et du PLMI |
| **T18** | **Nuit tronquée à 3 h** (`truncateAtH = 3.0`) | `gate == NO_PLMI` ; `piValue` toujours produit et \|ΔPI\| **≤ 0,08** ; aucune exception ; `truncatedSeriesDropped` renseigné |
| **T19** | **Circularité** : nuit à PLMI vrai 60/h, masque accéléro seul | Avec `ignoreIntervals` : TST estimé à **≤ 15 %** du TST vrai. **Sans** `ignoreIntervals` : TST vrai/2 ou pire (assertion inversée — démontre que la couche 1 de §3.6.3 est nécessaire) |
| **T20** | **Intégrité** : blocs corrompus injectés (chevauchement, jerk ±16 g, `tFirst > tLast`) | 100 % des blocs corrompus rejetés ; **0 %** de blocs sains rejetés ; PLMI inchangé à **≤ 3 %** vs la nuit non corrompue |
| **T21** | **Équivalence incrémental / définitif** | Les CLM produits par `PendulumStream` en mode causal recouvrent **≥ 90 %** de ceux du mode définitif ; l'écart de `plmiSpt` est **≤ 15 %**. Sert de garde-fou : au-delà, le mode provisoire induit l'utilisateur en erreur en cours de nuit |

Note sur T6 : le F1 ≥ 0,90 de la v1 est conservé, **mais il est désormais mesuré contre `accelTruth`**, pas contre l'ensemble des mouvements générés. Contre `emgTruth`, le F1 plafonnerait mécaniquement à ~0,76 (Se ≤ 0,61 par Terrill), et un seuil de 0,90 serait impossible à atteindre. Cette distinction est ce qui empêche le critère P4 de la v1 d'être inatteignable pour une raison qui n'est pas la faute de l'algorithme.

---

## 6. Tableau des paramètres finaux

Les impacts à ±20 % sont des **estimations d'ingénierie à confirmer par le test T12**, pas des valeurs mesurées. Elles sont données pour prioriser l'effort de validation.

### 6.1 Intégrité et prétraitement

| Paramètre | Défaut | Unité | Plage | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `maxRateDeviation` | 0,20 | — | 0,10–0,30 | Contrôle nº 3 de l'étape −1 | < 1 % |
| `maxJerkG` | 8,0 | g/éch. | 4–16 | Contrôle nº 8 : détecte le repliement de saturation indépendamment du bug `toRaw` | < 1 % |
| `saturationFraction` | 0,05 | — | 0,02–0,10 | Contrôle nº 10 | < 1 % |
| `targetFsHz` | 50,000 | Hz | fixe | Grille de rééchantillonnage ; rend coefficients et durées exacts | n/a |
| `fsOutlierTol` | 0,05 | — | 0,02–0,10 | Rejet des blocs à timestamps corrompus | < 1 % (nuits saines) |
| `gapMicroSec` | 0,10 | s | 0,05–0,20 | < T_rise minimal (0,15 s) : interpolable sans artefact | < 1 % |
| `gapSegmentSec` | 2,0 | s | 1,0–5,0 | = temps d'établissement du filtre ; au-delà, rupture de série (WASM 3.3.3) | < 2 % |
| `settleSec` | 2,0 | s | 1,5–4,0 | Établissement Butterworth ordre 2 à 0,5 Hz | < 2 % |
| `warmupSec` | 5,0 | s | 3–10 | ≈ 2,5× settle ; sur 8 h, ~0,03 % du dénominateur | < 0,5 % |
| `fcGravityHz` | 0,15 | Hz | 0,08–0,25 | Sous le fondamental d'un CLM de 10 s (0,1 Hz) sans avaler la respiration dans ĝ | 2–5 % (via `tilt`) |
| `fcHpHz` | **0,50** | Hz | 0,30–0,70 | Brevets NeuroMetrix (50 Hz, 0,5 Hz) ; +7,4 dB de réjection à 0,25 Hz vs 0,3 Hz, **−0,9 dB** à 0,7 Hz (§1.2, corrigé le 2026-07-31 ; cette case portait encore −0,6 dB) | 3–8 % ; **> 15 % si respiration présente** |
| `hpOrder` | 2 | — | 2 ou 4 | Ordre 4 : +19 dB à 0,25 Hz mais sonnerie de posture 2 s → 4 s (§1.2) | discret, tester les deux |
| `fcLpHz` | **8,0** | Hz | 6–12 | +5,1 dB de SNR (BW 24,5 → 7,5 Hz) ; ≥ 1,5× le pic du CLM le plus rapide (5,3 Hz) | < 3 % |

### 6.2 Enveloppe et plancher

| Paramètre | Défaut | Unité | Plage | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `coarseEnvSec` | **0,50** | s | 0,35–0,80 | ≥ 1 période à 2 Hz ; annule l'ondulation à 2f. Remplace le 0,15 s de la v1 (§0-b) | 3–6 % (durées, fragmentation) |
| `fineEnvSec` | 0,15 | s | 0,10–0,25 | Raffinement onset/offset uniquement (§1.4) | < 2 % (onset ±30 ms) |
| `floorWinSec` | **120** | s | 60–240 | Un retournement de 20 s pèse 17 % au lieu de 80 % à 25 s (§1.3) | < 2 % |
| `floorP1` | 25 | percentile | 15–40 | Passe 1 robuste avant exclusion itérative | < 2 % |
| `excludeFactor` | 4,0 | × | 3–6 | Masquage passe 2 ; sous `k_on` pour couvrir les CLM sous-seuil | 2–4 % |
| `minValidFraction` | 0,25 | — | 0,15–0,40 | Sous ce seuil, plancher extrapolé + drapeau | < 1 % |
| `floorMode` | `BILATERAL` | — | + `CAUSAL_LAGGED` | Définitif vs incrémental (§3.7) | voir T21 : ≤ 15 % |
| `causalLagSec` | 5,0 | s | 3–10 | Empêche l'événement en cours de contaminer son propre plancher (mode causal) | 3–6 % (mode causal seul) |

### 6.3 Seuils de détection

| Paramètre | Défaut | Unité | Plage | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `kOn` | **8,0** | × plancher | 5–12 | **Budget anti-artefact, pas anti-bruit** : 4,8 suffirait contre le thermique (§2, étape 4). Valeur v1 conservée faute de mieux | **10–15 % — paramètre dominant** |
| `kOff` | 2,5 | × plancher | 2,0–4,0 | Hystérésis 3,2 ; transposition du rapport 8 µV / 2 µV de l'AASM | 3–6 % |
| `absFloorG` | **0,020** | g | 0,010–0,050 | NeuroMetrix 0,02/0,03 g ; Sicbaldi 15 mg. Le seuil relatif seul tomberait à 7 mg (§1.1) | 0 % nuit normale ; **jusqu'à 15 % nuit très calme** |
| `calFraction` | 0,12 | × gainCal | 0,08–0,20 | 12 % d'une dorsiflexion volontaire confortable (§3.3) | 5–10 % si ce terme domine |
| `offHoldSec` | 0,50 | s | **fixe** | Règle AASM/WASM littérale | ne pas varier |
| `minDurSec` | 0,50 | s | **fixe** | AASM VII / WASM 3.3.1 | ne pas varier (< 3 % si on le fait) |
| `maxDurSec` | 10,0 | s | **fixe** | AASM : borne du CLM. WASM : au-delà, LM long qui **casse** la série | ne pas varier |
| `morphologyWinSec` | 0,50 | s | 0,3–0,8 | WASM 3.2.1-d ; meilleur filtre anti-matelas disponible (§3.2) | 2–5 % ; **> 20 % sur nuit à matelas bruyant** |
| `grossBodyFactor` | 40 | × plancher | 25–60 | Sicbaldi : 2 506 mg éveil vs ~380 mg sommeil vs ~15 mg plancher | 2–4 % |
| `refractorySec` | 2,0 | s | 1–4 | Sonnerie du filtre après un GBM | 2–4 % |
| `minExcursionDeg` | 1,5 | ° | 0,5–4,0 | Marqueur `TRANSMITTED_SUSPECT` — **rapporté, non exclu** | 0 % (non exclusif) |

### 6.4 Posture

| Paramètre | Défaut | Unité | Plage | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `postureTauSec` | 2,0 | s | 1,0–3,0 | Demi-fenêtre de comparaison de ĝ | 2–4 % |
| `postureDeg` | 20,0 | ° | 12–30 | Seuil de rotation persistante | 3–6 % |
| `stableDeg` | 10,0 | ° | 6–15 | Cône de stabilité post-transition | 2–3 % |
| `stableSec` | 10,0 | s | 5–20 | Distingue un changement durable d'un mouvement | 2–4 % |
| `guardSec` | 2,5 | s | 1,5–4,0 | Fenêtre d'exclusion autour de la transition | 3–5 % |

### 6.5 Règles cliniques (non ajustables — la variation est le test T14, pas T12)

| Paramètre | AASM_V3 | WASM_2016 | Source |
|---|---|---|---|
| `imiMinSec` | **5,0** | **10,0** | AASM ISR / WASM 3.3.4 |
| `imiMaxSec` | 90,0 | 90,0 | Identique |
| `minClmPerSeries` | 4 | 4 (= 3 IMI) | AASM / WASM 3.3.5 |
| `shortImiPolicy` | `SKIP_LATER` *(interprété)* | **`BREAK_SERIES`** | AASM muette → convention WASM 2006 ; WASM 3.3.6. **C'est le paramètre qui change tout** (Ferri 2015) |
| `breakOnLongLm` | false | **true** | WASM 3.3.6 |
| `requirePortionInSleep` | **true** | false | Nouveauté v3 / WASM 2.4.4 (série traversante autorisée) |
| `piImiLow / piImiHigh` | 10 (exclu) / 90 (inclus) | idem | Ferri 2006 ; **pas 10–50 s** |
| `piMinRunLength` | 3 intervalles (= 4 LM) | idem | Ferri 2006 |
| `piMinLmRatePerHour` | 10 | idem | Drakatos 2021 : sous ce taux, PI ininterprétable |

### 6.6 Masque de sommeil, circularité, publication

| Paramètre | Défaut | Unité | Plage | Source / justification | Impact ±20 % |
|---|---|---|---|---|---|
| `epochSec` | 5,0 | s | fixe | van Hees 2015 | n/a |
| `angleDeg` | 5,0 | ° | 3–8 | van Hees 2015 (5°/5 min) | **8–15 % — 2ᵉ paramètre le plus sensible** |
| `moveFactor` | 6,0 | × plancher | 4–10 | Critère d'amplitude additionnel (ajout) | 4–8 % |
| `sustainedMin` | 5,0 | min | 3–10 | van Hees 2015 | 5–10 % |
| `sptMinMin` | 15,0 | min | 10–30 | Bornage du SPT | 3–6 % |
| `maxFixedPointIterations` | 2 | — | **fixe** | §3.6.3 couche 2 ; plus de 2 peut osciller | n/a |
| `convergenceTstFraction` | 0,25 | — | 0,15–0,40 | Seuil de `MASK_NON_CONVERGENT` | n/a (drapeau) |
| `maxLagMs` | 600 000 | ms | 300–900 k | Recalage croisé accel ↔ HC (§3.6.4) | 0 % (recherche, pas seuil) |
| `minTstFullMin` | 240 | min | 210–300 | Porte de publication pleine (§3.7.2) | n/a (porte) |
| `minTstAnyMin` | 180 | min | 150–240 | Porte sous laquelle aucun PLMI n'est publié | n/a (porte) |

**Deux paramètres à surveiller en priorité : `kOn` (10–15 %) et `angleDeg` (8–15 %).** Le premier se maîtrise par la calibration mécanique (§3.3), le second par la correction apprise contre Health Connect (§3.6.4) ou, mieux, par le journal manuel (§3.6.5-a). Ce sont les deux boucles de calibration du système, et elles ne sont pas optionnelles : sans elles, le critère nº 4 de vérification de la v1 (« si la décision bascule à ±10 %, le chiffre n'est pas exploitable ») ne sera vraisemblablement pas tenu.

---

## 7. Hypothèses, confiance, et ce qui reste non vérifié

| Élément | Confiance | Note |
|---|---|---|
| Règles AASM v3 / WASM 2016 chiffrées | **Haute** | WASM 2016 et 2006 lus verbatim en PDF intégral. AASM v3 **non lu** (payant) : les chiffres sont triangulés depuis le Summary of Updates v3 officiel (qui liste exactement deux changements, aucun numérique), la FAQ AASM, l'aide Sleep ISR, et des restitutions revues par les pairs |
| IMI AASM = 5–90 s (et non 10–90) | **Haute** | Trois sources indépendantes, dont l'aide ISR de l'AASM elle-même |
| PI de Ferri : 10–90 s, ≥ 3 intervalles | **Haute** sur les bornes, **moyenne** sur strict vs inclusif | Ferri 2006 non lu (payant) ; formule reconstruite depuis trois citations verbatim, dont une méthode co-signée Ferri |
| Énergie PLMS sub-6 Hz | **Moyenne** | **Aucune analyse spectrale de PLMS accélérométrique n'est publiée.** Inférence depuis le filtre d'Athavale (0,4/1,6 Hz, 87,9 % Se) et l'écart Actiwatch/PAM-RL de Gschliesser. Le modèle physique §5.1 le retrouve indépendamment |
| Amplitudes en g à la cheville | **Moyenne-basse** | Aucune mesure publiée de la crête d'un PLMS. Encadrement par Sicbaldi (plancher 15 mg, mouvement de sommeil 377 ± 63 mg) et les seuils commerciaux |
| Transmission matelas | **Nulle** | Aucune quantification publiée. Toutes les valeurs de §3.2 sont des hypothèses à mesurer |
| Respiration à la cheville | **Nulle** | Quantifiée au thorax seulement. La correction de §1.2 est une assurance bon marché, pas une réponse à un problème mesuré |
| Biais de masque poignet/cheville | **Moyenne** | Une seule étude (n = 29, **enfants**). Aucune validation adulte poignet-vs-cheville trouvée. C'est pourtant le premier poste d'erreur — c'est inconfortable |
| Taux de manqués de 39 % (Terrill) | **Moyenne** | Résumé EMBC seul, texte intégral inaccessible. La cohérence avec la mécanique (§5.1) le rend plausible |
| Gravité de la circularité (§3.6.3) | **Haute** sur le mécanisme, **moyenne** sur l'ampleur | Le calcul « 13 mouvements par fenêtre de 5 min à IMI 22 s » est arithmétique et certain. L'ampleur de l'effondrement du TST dépend du réglage réel et n'est mesurable que par T19 |

### Sources principales

> **Ce n'est pas la bibliographie du projet, et il ne faut pas l'enrichir.** La bibliographie est
> [`../references.md`](../references.md), et elle seule porte ce qui compte d'une source : si le
> texte intégral a été lu ou seulement le résumé, ce que le projet en tire, et les réserves
> attachées. Une deuxième liste est exactement ce qui a produit la citation de Ferri 2016 sous deux
> paginations différentes dans ce dépôt. **Une source nouvelle va dans `references.md` ; cette liste
> peut être élaguée jusqu'aux seuls documents normatifs.**

- AASM Summary of Updates in Version 3 (2023) — https://aasm.org/wp-content/uploads/2023/02/Summary-of-Updates-v3.pdf
- AASM Sleep ISR, Scoring Limb Movements — https://isr.aasm.org/helpv5/ScoringLimbMovementsL.html
- AASM Scoring Manual FAQ (items M.4, M.5) — https://aasm.org/resources/pdf/faqsscoringmanual.pdf
- WASM 2016, Ferri et al., Sleep Med 2016;26:86-95 — https://www.irlssg.org/wp-content/uploads/2025/05/WASM-2016-Standards-for-Recording-and-Scoring-Leg-movements-2016.pdf
- WASM 2006, Zucconi et al., Sleep Med 2006;7(2):175-183 — https://worldsleepsociety.org/wp-content/uploads/2018/06/PIIS1389945706000049.pdf
- Ferri et al., Sleep Med 2015;16:1229-1235 (Alt1/Alt2) — https://pubmed.ncbi.nlm.nih.gov/26429751/
- Ferri et al., Sleep Med 2016;22:97-99 (seuils, PI ≈ 0,50) — https://pubmed.ncbi.nlm.nih.gov/26922620/
- Ferri et al., Sleep Med 2013;14:293-296 (stabilité inter-nuits du PI)
- Manconi et al., Sleep 2015;38(2):295-304 (RRLM −2,0/+10,25 s) — https://pmc.ncbi.nlm.nih.gov/articles/PMC4288611
- Sleep Breath 2023 (AASM vs WASM RRLM, 50,5 vs 90,7 /h) — https://pmc.ncbi.nlm.nih.gov/articles/PMC10163289/
- Athavale et al., SLEEP 2019 (25 Hz, LP 0,4/1,6 Hz, 87,9 %/94,1 %)
- Sicbaldi et al., Sci Rep 2025 (Axivity AX6, 0,1-10 Hz, seuil 15 mg cheville, 377 ± 63 mg) — https://pmc.ncbi.nlm.nih.gov/articles/PMC12770513/
- Sforza et al., Sleep Med 2005;6:407-413 (PAM-RL, 40 Hz, 0,3-20 Hz, 200/100 mg, durée moyenne 4,2 s ; le ± 0,14 est une **erreur type sur 11 patients**, pas un écart-type — voir §5.1) — https://worldsleepsociety.org/wp-content/uploads/2018/06/Sleep-Medicine-6-2005-407%E2%80%93413.pdf
- Gschliesser et al. 2009 (Actiwatch sous-compte, PAM-RL sur-compte) — https://pubmed.ncbi.nlm.nih.gov/18656421/
- Brevets NeuroMetrix US9731126 / US10335595 (50 Hz, HP 0,5 Hz, 0,02/0,03 g)
- Terrill et al., EMBC 2013 (39,0 % / 54,9 % de LM sans mouvement détectable) — https://pubmed.ncbi.nlm.nih.gov/24111321/
- van Hees et al., PLOS ONE 2015;10(11):e0142533 (règle 5°/5 min) — https://pmc.ncbi.nlm.nih.gov/articles/PMC4646630/
- Wiedemann et al. (poignet vs cheville, Cole-Kripke +43 min, van Hees −89 min) — https://pmc.ncbi.nlm.nih.gov/articles/PMC12215244/
- Solnik et al., Eur J Appl Physiol 2010;110:489-498 (TKEO EMG) — https://pmc.ncbi.nlm.nih.gov/articles/PMC2945630/
- Aubol & Milner, IEEE TBME 2019 (TKEO accélérométrie, BP 1-20 Hz obligatoire) — https://pubmed.ncbi.nlm.nih.gov/31150328/
- Drakatos et al., J Thorac Dis 2021 (PI ininterprétable sous 10 LM/h) — https://pmc.ncbi.nlm.nih.gov/articles/PMC8662505/
- Marino et al., Sleep 2013;36(11):1747 (actigraphie Se 0,965 / Sp 0,329) — https://pubmed.ncbi.nlm.nih.gov/24179309/
- ICSD-3 / AASM CPG 2025 (PLMI > 15/h adulte, > 5/h enfant) — https://aasm.org/wp-content/uploads/2024/03/Treatment-of-RLS-and-PLMD-CPG.pdf

---

## À vérifier toi-même

1. **Acheter le manuel AASM v3 et lire le chapitre VII section B directement.** Trois points précis : que l'IMI 5–90 s y est toujours écrit tel quel ; le libellé exact de « at least a portion … in an epoch of sleep » et son interaction avec une série traversant une frontière veille/sommeil ; le libellé courant de la note RRLM après le retrait de « sleep-disordered breathing event » en v2.4. Toute la §6.5 en dépend.
2. **Obtenir le PDF de Ferri 2006 (Sleep 29:759-769)** pour trancher deux choses qui décalent chaque valeur de PI calculée : borne basse stricte (`> 10`) ou inclusive (`≥ 10`), et numérateur en **intervalles** ou en **mouvements** (les deux formes existent dans les publications de Ferri lui-même). Choisir, documenter, ne jamais mélanger. Le PI devenant l'indice primaire en l'absence de Health Connect (§3.6.5-c), ce point monte en priorité.
3. **Mesurer toi-même la transmission du matelas et la respiration à la cheville.** Ce sont deux trous complets de la littérature, et les deux nuits de contrôle nécessaires coûtent moins cher que n'importe quelle recherche bibliographique supplémentaire. Sans ces mesures, §3.2 reste de la conjecture paramétrée.
4. **Le test T11 est un test de falsification, pas de conformité.** S'il ne montre pas > 40 % d'écart sans calibration, le rituel de §3.3 est du folklore : retire-le au lieu de le garder « au cas où ». Même logique pour T19 et la couche 1 de §3.6.3.
5. **Le choix du masque de sommeil pèse plus que tout le détecteur.** Avant d'investir dans le réglage de `kOn`, mesure κ et ΔTST entre le masque accéléro et Health Connect sur tes 5–7 nuits. Si κ < 0,4, le PLMI n'est pas comparable d'une nuit à l'autre quel que soit le soin apporté à la détection, et il faut régler ça d'abord.
6. **Ajoute les deux champs du journal de sommeil avant d'écrire une ligne de masque sophistiqué.** C'est dix lignes d'UI et cela supprime structurellement la circularité du dénominateur (§3.6.5-a), là où toute l'ingénierie de §3.6.2–3.6.4 ne fait que l'atténuer. Le rapport coût/bénéfice n'est comparable à rien d'autre dans ce document.
7. **Le seuil de 15/h de l'ICSD-3 n'est pas transposable à cette mesure.** Il est défini sur de l'EMG bilatéral. Avec un biais à la baisse structurel (unilatéral + 39 % de manqués mécaniques) et un biais à la hausse (RRLM non exclus), l'app doit afficher une tendance inter-nuits et un intervalle, jamais un chiffre comparé à 15.
8. **Corrige `ChunkFormat.toRaw` dans `:format`, mais ne retire pas le contrôle nº 8 de l'étape −1.** Le contrôle de jerk est une défense indépendante contre toute future régression du codec, et il coûte une soustraction par échantillon.
