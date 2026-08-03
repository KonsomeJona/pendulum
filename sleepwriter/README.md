# `:sleepwriter` — l'ecrivain d'hypnogrammes du banc

Un APK minuscule, **debug uniquement, jamais publie**, dont le seul role est d'ecrire des
`SleepSessionRecord` dans Health Connect pour que le banc puisse exercer la moitie
« denominateur » de la mesure.

## Pourquoi il existe

Pendulum lit une session de sommeil ecrite par une **application tierce independante** — bague,
montre au poignet, capteur sous matelas. Le produit repose sur le fait que cette donnee vienne
d'un autre capteur que le sien : si les deux venaient du meme accelerometre, un traitement qui
supprime des mouvements baisserait le numerateur et monterait le denominateur du meme geste
(`docs/01-overview.md` §1).

Pendulum **ne peut donc pas** ecrire lui-meme ce qu'il lit, et ne doit jamais le pouvoir : il ne
declare que `READ_SLEEP`, `READ_HEALTH_DATA_IN_BACKGROUND` et `READ_HEALTH_DATA_HISTORY`, et
l'absence de permission d'ecriture est un argument de conception verifiable par
`aapt dump permissions`. Ce module n'est pas un contournement de cette regle, il en est la
contrepartie fidele : une autre application, un autre paquet, une autre origine.

**Il n'est jamais publie.** Sa variante release est desactivee dans `build.gradle.kts`
(`androidComponents { beforeVariants... }`) : `./gradlew assembleRelease` a la racine ne produit
aucun artefact ici, et l'oubli est mecaniquement impossible plutot que simplement deconseille.

## Deux paquets, parce que `E-HC-03` n'est pas simulable autrement

Health Connect fixe le `dataOrigin` d'un enregistrement a partir du **paquet qui l'ecrit**. Le
scenario « deux sources en conflit », que `phone/.../health/SleepSourceSelector.kt` sait arbitrer,
exige donc deux APK reellement distincts.

| Variante | `applicationId` | Tache Gradle |
|---|---|---|
| `sourceA` | `com.pendulum.sleepwriter.a` | `:sleepwriter:assembleSourceADebug` |
| `sourceB` | `com.pendulum.sleepwriter.b` | `:sleepwriter:assembleSourceBDebug` |

Meme code, meme manifeste, meme signature de debug. L'`applicationId` de base
(`com.pendulum.sleepwriter`) est volontairement different de celui de `:phone` et `:wear`
(`com.pendulum`, partage entre eux parce que le Wearable Data Layer n'echange qu'entre
applications de meme identifiant et de meme signature).

---

## Construire et installer

```bash
./gradlew :sleepwriter:assembleSourceADebug :sleepwriter:assembleSourceBDebug

adb -s <serie> install -r sleepwriter/build/outputs/apk/sourceA/debug/sleepwriter-sourceA-debug.apk
adb -s <serie> install -r sleepwriter/build/outputs/apk/sourceB/debug/sleepwriter-sourceB-debug.apk
```

Verifier que les deux portent bien des paquets distincts — une collision d'`applicationId` se
manifeste par un remplacement silencieux, pas par une erreur :

```bash
aapt dump badging sleepwriter/build/outputs/apk/sourceA/debug/sleepwriter-sourceA-debug.apk | head -1
aapt dump badging sleepwriter/build/outputs/apk/sourceB/debug/sleepwriter-sourceB-debug.apk | head -1
```

---

## Accorder `WRITE_SLEEP` — la sequence, et pourquoi elle n'est pas un `pm grant`

`pm grant` **ne fonctionne pas** pour les permissions de sante : elles sont gerees par un module
Mainline avec sa propre interface de consentement. Il faut donc passer par l'ecran de Health
Connect, ce qui se pilote par `adb` de facon deterministe.

**1. Demander d'abord si c'est deja fait.** Sur un emulateur recharge depuis un instantane, la
permission est deja accordee et toute la sequence d'interface est a sauter :

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity --es mode permissions
adb -s <serie> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
# RESULT mode=permissions status=OK ... sdk=AVAILABLE write=GRANTED read=GRANTED
```

**2. Sinon, ouvrir la boite de consentement** (le bouton de l'application la declenche ; on peut
aussi la declencher par un `mode write`, qui rendra `status=PERMISSION_MISSING` puis rien) :

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity
adb -s <serie> shell input tap ...   # voir l'etape 3 : ne pas taper a l'aveugle
```

**3. Trouver le bouton par son texte, jamais par des coordonnees en dur.** La fenetre de Health
Connect change de disposition selon la version du module et la densite de l'ecran, et un tap qui
rate **ne produit aucune erreur** — il produit un echec plus loin, pour une raison sans rapport.

```bash
adb -s <serie> shell uiautomator dump /sdcard/ui.xml
adb -s <serie> pull /sdcard/ui.xml /tmp/ui.xml
# chercher dans /tmp/ui.xml le noeud dont text vaut "Allow all" / "Allow" / "Autoriser tout",
# lire ses bounds="[x1,y1][x2,y2]", taper au centre :
adb -s <serie> shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
```

**4. Verifier ce qu'on a fait**, a l'image et au journal :

```bash
adb -s <serie> exec-out screencap -p > /tmp/ecran.png
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity --es mode permissions
adb -s <serie> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

A refaire pour `com.pendulum.sleepwriter.b` : les deux paquets ont chacun leur consentement.

---

## Le pilotage

Forme generale — **les extras viennent apres le composant** :

```bash
adb -s <serie> shell am start -n <paquet>/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode <write|verify|permissions|purge> \
  --es scenario <nuit-complete|duree-seule|rien|conflit> \
  --el startMs <epoch ms> --el endMs <epoch ms> \
  --el delayMs <ms> --el marginMin <minutes> \
  --ez finish <true|false>
```

### Le piege du typage des extras

`am start` type les extras par le drapeau et non par la valeur :

| Drapeau | Type | A utiliser pour |
|---|---|---|
| `--es` | chaine | `mode`, `scenario` |
| `--ei` | entier 32 bits | **jamais ici** |
| `--el` | long 64 bits | `startMs`, `endMs`, `delayMs`, `marginMin` |
| `--ez` | booleen | `finish` |

Un instant d'epoque en millisecondes **depasse 2^31** : passe en `--ei`, il fait echouer la
commande ; passe en `--es`, il arrive mais dans le mauvais type. L'activite lit defensivement le
long puis retombe sur la chaine, mais mieux vaut ecrire `--el` du premier coup.

Aucune valeur ne contient d'espace, donc aucun echappement n'est necessaire dans `adb shell`.

### Les valeurs par defaut

Sans `startMs` ni `endMs`, la fenetre est **les huit dernieres heures** : toujours dans le passe,
donc toujours acceptable par Health Connect, et suffisante pour un essai de fumee. Le scenario par
defaut est `nuit-complete`, la marge de relecture 180 minutes, et l'activite se termine seule.

Fabriquer une fenetre precise :

```bash
FIN=$(( $(date -d '07:00' +%s) * 1000 ))          # ce matin 7 h
DEBUT=$(( FIN - 8 * 3600 * 1000 ))                 # huit heures plus tot
```

---

## Les quatre scenarios

### 1. Nuit complete avec stades

Cycles d'environ 90 minutes, profond concentre en premiere moitie de nuit et decroissant,
paradoxal croissant en seconde, micro-eveils de fin de cycle, latence d'endormissement et eveil
final. Les stades couvrent la fenetre **sans trou** : la politique de trous change le
denominateur, donc l'index, et merite un scenario a elle plutot que d'etre melangee au cas
nominal. Les invariants (jamais eveil -> profond, jamais profond -> paradoxal) sont verifies par
`HypnogrammeTest`.

Health Connect ne connait pas N1/N2/N3 : N1 et N2 tombent dans `LIGHT`, N3 dans `DEEP`. Inutile de
chercher un N2 dans la donnee relue.

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario nuit-complete --el startMs $DEBUT --el endMs $FIN
```

### 2. Duree seule, sans stades

Ce que beaucoup d'appareils grand public ecrivent reellement. Pendulum calcule alors un temps de
sommeil total mais ne peut pas ventiler par stade, et il doit le **dire** : `SleepSourceSelector`
rend le verdict `STADES_ABSENTS`.

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario duree-seule --el startMs $DEBUT --el endMs $FIN
```

### 3. Rien du tout — `E-HC-01`

Scenario par omission : il n'y a rien a ecrire. Il existe pour que le script du banc soit
symetrique et pour que la trace atteste qu'on a voulu ce vide plutot que de l'avoir subi.

```bash
# purger d'abord ce qu'un scenario precedent aurait laisse (ne supprime que nos propres
# enregistrements : Health Connect n'autorise pas d'effacer ceux d'une autre application)
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode purge --el startMs $DEBUT --el endMs $FIN
adb -s <serie> shell am start -n com.pendulum.sleepwriter.b/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode purge --el startMs $DEBUT --el endMs $FIN

adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario rien --el startMs $DEBUT --el endMs $FIN
```

### 4. Deux sources en conflit — `E-HC-03`

La meme commande sur les deux paquets. La variante A ecrit la nuit complete avec ses stades ; la
variante B une duree seule, decalee de 45 minutes au debut et raccourcie de 30 a la fin. Les
durees sont differentes **exprès** : deux sources identiques ne prouveraient pas laquelle a ete
retenue. `SleepSourceSelector` doit choisir A par le critere « plus de types de stade distincts ».

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario conflit --el startMs $DEBUT --el endMs $FIN
adb -s <serie> shell am start -n com.pendulum.sleepwriter.b/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario conflit --el startMs $DEBUT --el endMs $FIN
```

### L'hypnogramme qui arrive apres le reveil

C'est le cas **normal**, pas une panne : la montre de poignet ne transfere pas sa nuit au reveil
mais quand sa politique de batterie le decide. `delayMs` est une attente **en temps reel** avant
l'ecriture — la fenetre enregistree reste celle de la nuit. Le banc comprime six heures en
quelques secondes.

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode write --es scenario nuit-complete --el startMs $DEBUT --el endMs $FIN --el delayMs 30000
# une ligne PENDING part tout de suite, la ligne RESULT trente secondes plus tard
```

L'attente vit dans le processus de l'activite : au-dela de quelques dizaines de secondes, il vaut
mieux lancer la commande plus tard depuis le script que d'allonger `delayMs`.

---

## Lire le resultat

Une seule ligne par action, etiquette stable, aucun espace dans les valeurs.

```bash
adb -s <serie> logcat -c                      # avant l'action
adb -s <serie> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

```
RESULT mode=write status=OK scenario=nuit-complete source=A pkg=com.pendulum.sleepwriter.a
  startMs=1754000000000 endMs=1754028800000 start=2025-07-31T21:33:20Z end=2025-08-01T05:33:20Z
  durationMin=480 records=1 stages=34 writtenStartMs=... writtenEndMs=... writtenDurationMin=480 ids=<uuid>
```

(la ligne reelle tient sur une seule ligne ; elle est coupee ici pour la lisibilite)

| Prefixe | Sens |
|---|---|
| `RESULT` | resultat definitif — c'est ce qu'un script attend |
| `PENDING` | annonce d'une ecriture differee, **jamais** un aboutissement |

| `status` | Ce qu'il faut faire |
|---|---|
| `OK` | rien |
| `NOTHING_TO_WRITE` | attendu pour le scenario `rien` ; en `verify`, signifie qu'aucune session n'a ete trouvee |
| `PERMISSION_MISSING` | rejouer la sequence de consentement ci-dessus |
| `HC_UNAVAILABLE` | Health Connect absent ou trop ancien sur cette image d'emulateur |
| `BAD_PARAM` | `endMs <= startMs` |
| `ERROR` | le champ `err` porte le type et le message de l'exception |

### Le mode `verify`, qui est l'oracle du banc

Il relit **toutes** les sources de la fenetre, pas seulement la sienne : ne relire que ses propres
enregistrements suffirait a dire « l'ecriture a abouti », pas a dire ce que Pendulum va voir.

```bash
adb -s <serie> shell am start -n com.pendulum.sleepwriter.a/com.pendulum.sleepwriter.EcrivainActivity \
  --es mode verify --el startMs $DEBUT --el endMs $FIN --el marginMin 180
adb -s <serie> logcat -d -s SLEEPWRITER:I | grep RESULT | tail -1
```

```
RESULT mode=verify status=OK ... records=2 stages=34 sumDurationMin=915 mine=1
  origins=com.pendulum.sleepwriter.a:1:34;com.pendulum.sleepwriter.b:1:0
```

`origins` porte `paquet:enregistrements:stades` par source, separes par `;`. C'est exactement
l'entree de `SleepSourceSelector`, et c'est ce qui permet de verifier qu'un scenario de conflit a
bien produit **deux** origines et non une seule ecrite deux fois.

---

## Ce que ce module ne fait pas

- **Il ne genere pas de signal accelerometrique.** L'hypnogramme est independant du signal ; le
  generateur de nuits de `algo/synth/` n'est pas une dependance d'ici, et ne doit pas le devenir.
- **Il ne prouve rien sur le capteur ni sur l'energie.** Voir la frontiere honnete du plan de
  banc : ce banc valide l'orchestration distribuee et l'exactitude algorithmique face aux cas
  limites de synchronisation, ni la consommation, ni le capteur, ni le comportement du systeme
  sous contrainte.
- **Il n'ecrit pas dans le futur.** Une session dont la fin depasse l'heure courante est signalee
  par un champ `warn=window-in-the-future` et l'ecriture est tentee quand meme : masquer l'erreur
  reelle de Health Connect derriere un refus maison rendrait le diagnostic plus difficile.
