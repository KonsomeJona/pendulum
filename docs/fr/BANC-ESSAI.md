# Pendulum — Banc d'essai sur émulateurs : ce qui marche et ce qui ne marche pas

**Reconnaissance de la phase 0 du banc instrumenté.** Machine : `jona-mac`, Apple Silicon 8 cœurs,
16 Gio de RAM, macOS 26.3, SDK dans `~/Library/Android/sdk`, `emulator` 36.4.9.0, `adb` 1.0.41.
Mesures du 3 août 2026, toutes reproduites depuis WSL par `ssh jona@100.74.103.69`.

**Méthode.** Chaque affirmation de ce document vient d'une commande lancée sur cette machine, dont
la sortie est citée. Quand une question n'a pas été tranchée, elle est listée en §7 avec la
commande qui la trancherait. Rien ici ne vient de la documentation d'Android.

---

## 1. Le verdict

**L'appairage entièrement headless n'a pas été obtenu, et il ne le sera pas : il manque un geste
humain, exactement un, et il est reconductible par snapshot.**

Le transfert de port `adb forward tcp:5601 tcp:5601` s'établit sans erreur et ne produit **aucun**
lien applicatif : l'application Pendulum installée sur les deux émulateurs affiche, côté montre,
« Phone unreachable ». C'est `NodeClient.connectedNodes` qui répond vide, c'est-à-dire exactement
la lecture dont dépend `Preflight`.

La cause n'est pas un réglage manqué. Elle tient en trois mesures :

1. **L'application compagnon Wear OS du téléphone (`com.google.android.wearable.app`) n'est
   préinstallée sur aucune des images téléphone essayées** — ni `android-34/google_apis`
   (223 paquets), ni `android-34/google_apis_playstore` (221 paquets). Sans elle, rien ne se
   connecte au port 5601.
2. **Le Play Store de l'émulateur fonctionne, mais demande un compte.** Lancé sur l'image
   `google_apis_playstore`, il rend `text="Sign in"`, et `dumpsys account` rend `Accounts: 0`.
   L'installation du compagnon exige donc une connexion Google, qui est un geste interactif et ne
   s'automatise pas honnêtement.
3. La méthode de vérification prescrite par le plan — `dumpsys activity provider
   com.google.android.gms.wearable.provider.WearableNodeProvider` — **n'existe pas** sur ces
   images : `No providers match`. Il faut valider autrement, et le §2 dit comment.

**Le chemin qui reste, et il est praticable.** L'image `android-34;google_apis_playstore;arm64-v8a`
démarre normalement — accélérée, `sys.boot_completed` en 75 s (§3) — et porte un Play Store
fonctionnel. La séquence est donc : se connecter **une fois** à un compte Google, installer
l'application Wear OS, appairer, puis **sauvegarder un snapshot** ; toutes les exécutions
suivantes repartent de cet état en 5 secondes. Une manipulation manuelle unique, capturée, est un
compromis acceptable ; une manipulation à chaque exécution ne l'aurait pas été.

**Ce qui n'est pas prouvé et qui décide de tout :** que l'appairage, une fois obtenu, **survive au
snapshot**. Les permissions et les applications installées survivent (§5) ; l'état du Data Layer
n'a pas pu être testé, faute d'avoir jamais été atteint. Si l'appairage ne survivait pas, il
faudrait basculer sur la voie de repli du §8. C'est la première chose que l'agent suivant doit
mesurer, et §7.1 donne la séquence.

**En revanche, trois choses marchent, et bien mieux que le plan ne l'espérait :**

| | |
|---|---|
| **Les snapshots** | Sauvegarde en **2 s**, rechargement à `boot_completed` en **5 s**, coût **945 Mo**. L'application installée et les permissions accordées survivent. C'est la bonne architecture, indépendamment du reste. |
| **Health Connect** | Présent **et vivant** sur `android-34/google_apis` : le service système répond, l'interface de réglages s'ouvre. |
| **`pm grant` sur les permissions de santé** | **Marche**, contrairement à ce que le plan annonçait. `android.permission.health.READ_SLEEP` s'accorde en ligne de commande, sans écran de consentement. |

---

## 2. Question 1 — l'appairage de deux émulateurs

### Ce qui a été lancé

Les deux émulateurs ont été créés propres, avec `avdmanager`, et démarrés en headless. Le
téléphone est ici sur `google_apis` et non `google_apis_playstore` : au moment de cette mesure,
l'image Play Store en API 34 n'était pas encore téléchargée. Le résultat vaut pour les deux, la
liste des paquets étant la même sur le point qui compte — l'absence du compagnon.

```bash
SDK=~/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n banc_phone34 \
  -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7 --force
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n banc_wear \
  -k "system-images;android-36;android-wear-signed;arm64-v8a" -d wearos_small_round --force

$SDK/emulator/emulator -avd banc_phone34 -no-window -no-audio -no-boot-anim -port 5574 &
$SDK/emulator/emulator -avd banc_wear    -no-window -no-audio -no-boot-anim -port 5576 &
```

Puis les deux APK, puis le pont, puis la lecture.

### Ce que ça a rendu

**Inventaire des paquets du téléphone** (223 paquets au total, `pm list packages`) :

```
package:com.google.android.healthconnect.controller
package:com.google.android.health.connect.backuprestore
package:com.android.vending
package:com.google.android.gms
```

`grep -i wearable` sur cette liste ne rend **rien**. L'application compagnon n'est pas là.

**Inventaire de la montre** (111 paquets) : la pile Wear OS est complète, dont
`com.google.android.wearable.app`, `com.google.android.gms`, `com.android.vending`. Attention au
faux ami : sur la montre, `com.google.android.wearable.app` est
`/system/priv-app/ClockworkWcs/ClockworkWcs.apk`, le service compagnon **côté montre**. Ce n'est
pas l'application téléphone, malgré le nom de paquet identique.

**Le fournisseur de nœuds prescrit n'existe pas**, sur aucun des deux appareils :

```
$ adb -s emulator-5574 shell dumpsys activity provider \
    com.google.android.gms.wearable.provider.WearableNodeProvider
No providers match: com.google.android.gms.wearable.provider.WearableNodeProvider
```

**Le pont s'établit sans erreur :**

```
$ adb -s emulator-5576 forward tcp:5601 tcp:5601
5601
$ adb -s emulator-5576 forward --list
emulator-5576 tcp:5601 tcp:5601
```

**Les deux APK s'installent :** `Success` des deux côtés, `package:com.pendulum` visible sur
`emulator-5574` et `emulator-5576`.

**Et la montre répond ceci**, lu sur son écran de préflight après avoir fait défiler la liste :

```
text="Ready"
text="Battery 100%"
text="Free space 5.2 GB"
text="Fill in the evening form on the phone: the watch will not start until it is sealed."
text="Phone unreachable — recording carries on, sync will happen later."
```

### Ce qu'on en conclut

Le pont de port lie les démons ADB et rien d'autre. Le second avis mentionné par le plan avait
raison : ce transfert **ne réalise pas** l'appairage applicatif. La pièce manquante est
l'application compagnon côté téléphone, et elle n'est pas dans l'image.

**La bonne méthode de validation, puisque le `dumpsys` prescrit n'existe pas :** lire l'écran de
préflight de la montre. C'est `Preflight.phoneReachable()` qui appelle
`NodeClient.connectedNodes` avec un délai de 10 s, et l'avertissement `PHONE_UNREACHABLE` apparaît
si et seulement si la liste est vide. C'est donc la lecture exacte dont dépend le produit, et elle
se lit en ligne de commande :

```bash
# le seul test d'appairage qui prouve quelque chose
adb -s <montre> shell input keyevent KEYCODE_WAKEUP
adb -s <montre> shell am start -n com.pendulum/.wear.ui.MainActivity
sleep 15
python3 tools/banc/uictl.py <montre> find "Phone unreachable"
# UICTL_FAIL  => le telephone est joignable, l'appairage tient
# UICTL_OK    => il ne tient pas
```

---

## 3. Le résultat qui déclasse tous les autres : l'accélération matérielle

Ce point n'était dans aucune des quatre questions, et il commande pourtant l'ensemble.

### La mesure

Le plan désignait `FarklePhonePortrait`, sur `android-36.1/google_apis_playstore`. Cet émulateur
**n'atteint jamais `sys.boot_completed`**. Deux tentatives, l'une depuis un snapshot, l'autre à
froid, plus de vingt minutes chacune, à 180 % de processeur en permanence. Son journal porte :

```
WARNING | hvf is not enabled on this aarch64 host.
```

Un échantillonnage du processus tranche sans ambiguïté :

```bash
sample $(pgrep -f FarklePhonePortrait) 3 -file /tmp/s.txt
grep -ciE "hv_vcpu_run|hvf" /tmp/s.txt   # -> 1
grep -ciE "cpu_tb_exec|tcg_" /tmp/s.txt  # -> 87
```

Les fils d'exécution sont des `qemu_tcg_cpu_thread_fn` : le processeur invité est **interprété en
logiciel**. Ce n'est pas une machine lente, c'est une machine sans hyperviseur.

### Ce n'est ni la machine, ni l'AVD

- `sysctl kern.hv_support` → `1`.
- Le binaire QEMU porte bien `com.apple.security.hypervisor` dans ses droits (`codesign -d
  --entitlements -`).
- `emulator -accel-check` → `accel: 0`, `Hypervisor.Framework OS X Version 26.3`.
- En mode verbeux, la sélection est explicite : `CPU Acceleration: working`, puis
  `handleCpuAcceleration: feature check for hvf`. Sur les images qui marchent, l'étape suivante
  passe `-enable-hvf` à QEMU. Sur celle-ci, elle émet l'avertissement et ne le passe pas.
- Un AVD **fraîchement créé** sur la même image se comporte pareil. Ce n'est donc pas un AVD abîmé.
- `-feature HVF` en ligne de commande **ne l'emporte pas** : `grep -c enable-hvf` reste à 0.

### Le tableau des images, mesuré une par une

| Image | `-enable-hvf` | `sys.boot_completed` |
|---|---|---|
| `android-34/google_apis/arm64-v8a` | oui (2 occurrences) | **55 s** |
| `android-34/google_apis_playstore/arm64-v8a` | oui (2 occurrences) | **75 s** |
| `android-36/android-wear-signed/arm64-v8a` | oui (2 occurrences) | **60 s** |
| `android-34/android-wear/arm64-v8a` | oui (2 occurrences) | **45 s** |
| `android-36.1/google_apis_playstore/arm64-v8a` | **non (0)** | **jamais**, deux essais de 20 min |

**Le tag `google_apis_playstore` n'est pas en cause, et l'API 36 non plus** : la même famille
d'image en API 34 est accélérée, et l'image montre en API 36 l'est aussi. Ce qui est en cause est
la **révision 36.1** elle-même. C'est une mesure, pas une déduction : quatre images sur cinq
passent `-enable-hvf`, la cinquième non, et elle est la seule en 36.1.

### La conséquence, et elle est lourde

**Le téléphone du banc doit être en API 34.** Cela règle l'accélération et le Play Store, et cela
coûte deux permissions. Sur `android-34` :

```
$ adb shell pm grant com.pendulum android.permission.health.READ_SLEEP
        android.permission.health.READ_SLEEP: granted=true

$ adb shell pm grant com.pendulum android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND
java.lang.IllegalArgumentException: Unknown permission: android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND

$ adb shell pm grant com.pendulum android.permission.health.READ_HEALTH_DATA_HISTORY
java.lang.IllegalArgumentException: Unknown permission: android.permission.health.READ_HEALTH_DATA_HISTORY
```

Ces deux permissions n'existent pas avant l'API 35. Or `SleepReader.Availability` distingue
précisément le cas `BACKGROUND_READ_UNAVAILABLE`, et la KDoc de `SleepReader` dit pourquoi :
« `SleepFetchWorker` tourne par construction application fermée, et sans elle la lecture échoue
hors premier plan ». **Un banc en API 34 ne peut donc pas exercer le chemin nominal de
`SleepFetchWorker`** — il exercera la branche dégradée. Il faut le dire dans les scénarios plutôt
que de le découvrir dans un échec.

---

## 4. Question 2 — Health Connect sur l'image téléphone

### Présent

```
$ adb -s emulator-5574 shell pm list packages | grep -i health
package:com.google.android.health.connect.backuprestore
package:com.google.android.healthconnect.controller
```

### Et vivant, ce qui n'est pas la même chose

```
$ adb -s emulator-5574 shell service list | grep -i health
132  healthconnect: [android.health.connect.aidl.IHealthConnectService]

$ adb -s emulator-5574 shell am start -a android.health.connect.action.HEALTH_HOME_SETTINGS
Starting: Intent { act=android.health.connect.action.HEALTH_HOME_SETTINGS }
```

L'écran obtenu, lu par `uictl.py … dump` :

```
text="Get Started with Health Connect"
text="Share data with your apps"
text="Manage your settings and privacy"
text="Get started"
```

Le service AIDL répond et l'interface se rend. C'est ce que `HealthConnectClient.getSdkStatus`
interroge.

### Le mur attendu n'est pas là

Le plan annonçait que `pm grant` ne fonctionnerait pas pour les permissions de santé, gérées par un
module Mainline avec son propre écran de consentement. **Sur cette image, c'est faux :**

```bash
adb -s <telephone> shell pm grant com.pendulum android.permission.health.READ_SLEEP
# puis, pour verifier — et il faut verifier, la commande est silencieuse en cas de succes :
adb -s <telephone> shell dumpsys package com.pendulum | grep "health.READ_SLEEP: granted"
#         android.permission.health.READ_SLEEP: granted=true, flags=[ USER_SENSITIVE_… ]
```

Aucun écran de consentement, aucun UiAutomator, aucun snapshot nécessaire pour ce point.

`appops` en revanche ne connaît pas ces opérations :

```
$ adb shell appops set com.pendulum android:read_sleep allow
Error: Unknown operation string: android:read_sleep
```

Ce n'est pas un problème : `pm grant` suffit, et `appops` n'apporte rien de plus.

**Ce qui reste à vérifier** : que `SleepReader` rende bien `READY` et non
`BACKGROUND_READ_UNAVAILABLE` — sur cette image en API 34, on attend justement le second, puisque
`READ_HEALTH_DATA_IN_BACKGROUND` n'existe pas (§3). La permission accordée est réelle au niveau du
gestionnaire de paquets ; qu'elle satisfasse aussi le module Health Connect lors d'une vraie
lecture n'a pas été exercé, faute d'un écrivain de sommeil — c'est l'agent C de la phase 1.

---

## 5. Question 3 — les snapshots

C'est le résultat le plus favorable de la reconnaissance.

### Sauvegarde

```bash
adb -s emulator-5574 emu avd snapshot save banc_pret   # OK, 2 s
adb -s emulator-5576 emu avd snapshot save banc_pret   # OK, 2 s
```

### Coût

```
945M  ~/.android/avd/banc_phone34.avd/snapshots/banc_pret     (AVD a 1536 Mo de RAM)
900M  ~/.android/avd/banc_wear.avd/snapshots/banc_pret        (AVD a 512 Mo, releve a 2048 par l'emulateur)
```

Environ **1,85 Go pour la paire**. C'est nettement moins que la taille de la RAM invitée cumulée,
et c'est tenable même sur un disque serré — à condition de n'en garder qu'un par AVD.

### Rechargement

```bash
emulator -avd banc_phone34 -no-window -no-audio -no-snapshot-save -snapshot banc_pret -port 5574
```

```
INFO | Successfully loaded snapshot 'banc_pret' using 956 ms
RELOAD_SECONDS=5
```

**5 secondes entre le lancement et `sys.boot_completed`**, contre 55 s à froid. Et l'état survit :

```
$ adb -s emulator-5574 shell pm list packages com.pendulum
package:com.pendulum
$ adb -s emulator-5574 shell dumpsys package com.pendulum | grep "health.READ_SLEEP: granted"
        android.permission.health.READ_SLEEP: granted=true
```

### Ce qui n'a pas pu être vérifié

**Qu'un snapshot préserve l'appairage du Data Layer**, puisqu'aucun appairage n'a jamais été
obtenu. La question reste entière et ne pourra être tranchée qu'après le §7.

Un piège mérite d'être noté : un snapshot enregistre l'état **tel qu'il est**, y compris mauvais.
En tuant l'émulateur bloqué en émulation logicielle, l'émulateur a sauvegardé de lui-même son
`default_boot` figé, et le rechargement suivant est reparti du même blocage — quinze minutes
perdues à chercher une cause qui était dans le snapshot. Toujours supprimer un `default_boot`
suspect avant de conclure quoi que ce soit :

```bash
rm -rf ~/.android/avd/<nom>.avd/snapshots/default_boot
```

---

## 6. Question 4 — le coût en ressources

### Les deux émulateurs ensemble

Mesuré au repos, les deux démarrés, applications installées :

```
banc_phone34  cpu=6.2%  rss=3404 Mo
banc_wear     cpu=4.1%  rss=1084 Mo
                        somme = 4914 Mo
```

**Environ 4,5 Go de mémoire résidente pour la paire**, et un coût processeur négligeable au repos.
Sur 16 Gio, les deux émulateurs ne sont pas le problème.

### Ce qui est le problème

**L'état de la machine avant de commencer.** Au premier contact :

```
Load Avg: 15.64, 7.19, 5.33
PhysMem: 15G used (1867M wired, 6176M compressor), 93M unused
vm.swapusage: total = 23552.00M  used = 22579.69M  free = 972.31M
```

93 Mo de RAM libre et 972 Mo d'espace d'échange restant. **Cinq émulateurs traînaient depuis des
sessions précédentes**, dont un depuis deux jours à 173 % de processeur. En arrêter trois a rendu
4,3 Go immédiatement. C'est la première étape de tout démarrage du banc, et elle doit être dans le
script :

```bash
# a lancer avant toute chose
adb devices | grep emulator | cut -f1 | while read s; do adb -s "$s" emu kill; done
```

Le reste de la mémoire est pris par Android Studio (857 Mo), la machine virtuelle de Docker
(542 Mo), Chrome, Dropbox et Claude. **Le banc ne cohabite pas avec une session de développement
ouverte** ; c'est à dire dans le mode d'emploi.

### Les temps

| | |
|---|---|
| Démarrage à froid, téléphone API 34 | 55 s |
| Démarrage à froid, montre API 36 | 60 s |
| Rechargement depuis snapshot | **5 s** |
| Sauvegarde d'un snapshot | 2 s |
| Compilation des deux APK (`--no-daemon`, cache chaud) | 19 s |

### Le disque

| Moment | Libre |
|---|---|
| Avant de commencer | **22,5 Go** |
| Après création de `banc_phone34` et `banc_wear` | 18,2 Go |
| Après les deux snapshots | 15,9 Go |
| Après téléchargement de l'image API 34 Play Store | 12,3 Go |
| Après un troisième AVD et l'arrêt des émulateurs | **8,9 Go** |
| Après ménage (un AVD supprimé, `default_boot` effacés) | **14,5 Go** |

**Le plancher de 12 Go a été franchi**, et il l'a été d'un coup : arrêter deux émulateurs a coûté
3,4 Go, parce que chacun **sauvegarde son `default_boot` en se fermant**. Un émulateur qui s'arrête
consomme du disque au lieu d'en rendre. C'est la mesure la plus contre-intuitive de la session, et
c'est celle qui met un banc en panne un soir de scénario long.

Deux règles qui en découlent, à mettre dans les scripts :

- **toujours arrêter avec `-no-snapshot-save`** à moins de vouloir explicitement le snapshot ;
- **ne garder qu'un snapshot nommé par AVD**, et effacer les `default_boot` à chaque passage :
  ils coûtent presque 1 Go pièce et ne servent à rien quand on charge un snapshot nommé.

Après ménage, un AVD téléphone et un AVD montre avec leur snapshot occupent 1,7 Go et 1,8 Go. C'est
le régime de croisière du banc.

À noter aussi : 23,5 Go du disque sont occupés par les fichiers d'échange de macOS, gonflés par la
saturation mémoire. Ils ne se résorbent que lentement. Une machine qui a swappé rend son disque
plus tard, pas tout de suite.

---

## 7. Ce qui reste inconnu, et comment le trancher

### 7.1 L'appairage survit-il au snapshot — la question qui décide de tout

L'AVD `banc_phone34ps` est prêt sur le Mac (`android-34;google_apis_playstore;arm64-v8a`,
`PlayStore.enabled=yes`, accéléré, `boot_completed` en 75 s). La séquence à mener :

```bash
SDK=~/Library/Android/sdk; ADB=$SDK/platform-tools/adb
$SDK/emulator/emulator -avd banc_phone34ps -no-window -no-audio -port 5578 &
$SDK/emulator/emulator -avd banc_wear      -no-window -no-audio -port 5576 &

# 1. LE GESTE MANUEL, une seule fois. Le Play Store rend « Sign in » et `dumpsys account`
#    rend « Accounts: 0 ». Passer par scrcpy et laisser l'utilisateur se connecter.
#    Puis installer « Wear OS » (com.google.android.wearable.app) depuis le magasin.

# 2. le pont, puis l'appairage par le compagnon
$ADB -s emulator-5576 forward tcp:5601 tcp:5601

# 3. la verification — sur l'ecran de preflight, pas sur un dumpsys qui n'existe pas
$ADB -s emulator-5576 shell input keyevent KEYCODE_WAKEUP
$ADB -s emulator-5576 shell am start -n com.pendulum/.wear.ui.MainActivity
sleep 15
python3 tools/banc/uictl.py emulator-5576 find "Phone unreachable"   # UICTL_FAIL = appaire

# 4. LA MESURE QUI DECIDE : sauvegarder, tuer, recharger, re-verifier
$ADB -s emulator-5578 emu avd snapshot save banc_pret
$ADB -s emulator-5576 emu avd snapshot save banc_pret
$ADB -s emulator-5578 emu kill; $ADB -s emulator-5576 emu kill
# ... recharger avec -snapshot banc_pret, refaire l'etape 2 (le forward ne survit pas), puis 3
```

Deux issues :

- l'appairage **survit** → le banc complet est viable, et le geste manuel n'est à refaire que si
  le snapshot est perdu ;
- l'appairage **ne survit pas** → il faudrait le refaire à chaque exécution, ce que la consigne
  exclut. **Voie de repli du §8.**

**Un piège à ne pas retraverser :** un AVD créé par ce `avdmanager` porte `PlayStore.enabled=no`
malgré l'image, parce que les `cmdline-tools` installés ne savent pas lire le format de métadonnées
courant :

```
Warning: This version only understands SDK XML versions up to 3 but an SDK XML file of version 4
was encountered.
Warning: package.xml parsing problem. unexpected element (uri:"", local:"abis").
```

Le même défaut laisse `target=android-0` dans le `config.ini` — et c'est ce qu'on croit d'abord
être la cause du §3, à tort. Corriger à la main dans `~/.android/avd/<nom>.avd/config.ini` :

```bash
sed -i '' 's/^PlayStore.enabled=no/PlayStore.enabled=yes/' ~/.android/avd/banc_phone34ps.avd/config.ini
```

### 7.2 Peut-on récupérer les deux permissions perdues

`android-35` ou `android-36` en `google_apis` (aucune n'est installée) apporterait
`READ_HEALTH_DATA_IN_BACKGROUND` et `READ_HEALTH_DATA_HISTORY`. Rien ne dit qu'elles soient
accélérées : seule la révision 36.1 a été prise en défaut, mais elle est aussi la seule au-dessus
de l'API 34 à avoir été essayée côté téléphone. Le test est celui du §3, en une minute — mais il
coûte environ 2 Go de disque, et le disque est le point de tension (§6).

### 7.3 Ce que `SleepReader` rend réellement

Le service Health Connect répond et la permission est accordée, mais aucune lecture n'a été faite —
il n'y a rien à lire tant que l'écrivain de sommeil de la phase 1 n'existe pas. On **attend**
`BACKGROUND_READ_UNAVAILABLE` en API 34 ; ce n'est pas mesuré.

### 7.4 Le pilotage de l'assistant du téléphone

`uictl.py` lit l'arbre d'interface et tape correctement — l'écran de réglages de Health Connect a
été ouvert et lu, la boîte de dialogue de notification de la montre a été traitée. Mais **les
quatre cases à cocher de l'étape 1 de l'assistant du téléphone n'ont pas pu être cochées** :
`uiautomator` les voit (`android.widget.CheckBox`, `clickable=true`, centres en `(148,1148)`,
`(148,1318)`, `(148,1546)`, `(148,1774)`), `input tap` s'exécute sans erreur, et le compteur reste
à `0 of 4 confirmed`. La piste non explorée est l'état de veille de l'écran : la montre n'a répondu
aux gestes qu'après `input keyevent KEYCODE_WAKEUP` et `settings put system screen_off_timeout
2147483647`. Il faut essayer la même chose sur le téléphone avant d'en conclure quoi que ce soit.

Tant que ce point n'est pas réglé, le contexte du soir ne peut pas être scellé par script, et donc
le blocage `CONTEXT_NOT_SEALED` de la montre ne peut pas être levé.

---

## 8. La voie de repli, si l'appairage ne vient pas

Elle consiste à **tester les deux moitiés séparément** et à injecter les chunks côté téléphone par
`NightExporter.importBundle`. Ce chemin existe déjà et n'est pas un contournement improvisé :
`BundleRoundTripTest` le parcourt en aller-retour et vérifie que « une nuit reconstruite depuis un
bundle donne exactement le même résultat », champ par champ, sur le trajet complet génération →
fichiers de chunks → analyse → mise en bundle → relecture → réécriture → seconde analyse.

**Ce qu'elle garde**, et c'est la majeure partie de la valeur annoncée du banc :

- l'exactitude algorithmique de bout en bout, puisque le résultat obtenu doit égaler celui du test
  JVM sur le même signal ;
- la tenue mémoire du téléphone sur 1,44 M d'échantillons, jamais éprouvée ;
- les garde-fous d'affichage dans l'application assemblée, côté téléphone ;
- tout ce qui touche au dénominateur : Health Connect, `E-HC-01` à `E-HC-03`, l'abandon à T+36 h,
  la nuit de changement d'heure — ces scénarios ne traversent pas le Data Layer.

**Ce qu'elle perd, et il faut le nommer précisément :**

- **Toute la machine à états du transfert.** L'invariant « aucun fichier effacé avant son bit
  d'accusé » redevient raisonné et non vérifié. Le lien coupé en pleine nuit, le plafond d'items
  en vol, le chunk corrompu et sa réémission, l'ordre supprimer-puis-reposer imposé par le Data
  Layer : **rien de l'agent E de la phase 3** n'est exerçable.
- **La jointure entre les deux moitiés** — c'est-à-dire exactement la classe de défaut que le banc
  existe pour attraper. Le contexte du soir clé par une session qui n'existe pas encore avait
  précisément cette forme : chaque moitié correcte, la jointure impossible. Injecter un bundle
  fabriqué court-circuite la jointure au lieu de l'éprouver.
- **`Preflight` et `AppairageMontre` dans leur cas nominal.** Ils ne seront exercés que dans leur
  branche « rien en face », qui est la seule que l'émulateur sait produire.

Autrement dit : la voie de repli conserve l'**exactitude** et abandonne l'**orchestration
distribuée**. Comme l'orchestration distribuée est la moitié de ce que le plan promettait, il faut
le réécrire dans `docs/07-validation.md` plutôt que de laisser croire que le banc valide la chaîne.

---

## 9. Les commandes qui marchent, réutilisables telles quelles

### Préparer la machine

```bash
# 1. faire le vide — sans quoi rien d'autre n'a de sens (voir §6)
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB devices | grep emulator | cut -f1 | while read s; do $ADB -s "$s" emu kill; done

# 2. verifier qu'il reste de la place ET de la memoire
df -Pk ~ | tail -1
top -l 1 -n 0 | grep -E "PhysMem|Load Avg"
```

### Construire les APK

```bash
rsync -az --delete --exclude '.git/' --exclude '.gradle/' --exclude '.kotlin/' \
  --exclude 'build/' --exclude '**/build/' --exclude 'local.properties' --exclude 'docs-site/' \
  /mnt/e/dev/plss/ jona@100.74.103.69:builds/pendulum-banc/

# local.properties est exclu du rsync : il faut le recreer, une fois, sur le Mac
ssh jona@100.74.103.69 'echo "sdk.dir=/Users/jona/Library/Android/sdk" > ~/builds/pendulum-banc/local.properties'

# et surtout PAS de flock : la commande n'existe pas sur macOS (`flock: command not found`)
ssh jona@100.74.103.69 'bash -lc "cd ~/builds/pendulum-banc && ./gradlew --no-daemon :phone:assembleDebug :wear:assembleDebug"'
```

### Démarrer la paire

```bash
SDK=~/Library/Android/sdk
# TOUJOURS verifier qu'aucune instance ne tourne deja sur le meme AVD : deux processus sur les
# memes images corrompent l'invite, et le symptome est illisible — « Can't find service: package »
# sur un appareil pourtant marque `device`.
pgrep -f banc_phone34ps | wc -l   # doit valoir 0

# -no-snapshot-save est obligatoire : sans lui, l'arret ecrit un default_boot de ~1 Go (§6)
$SDK/emulator/emulator -avd banc_phone34ps -no-window -no-audio -no-snapshot-save \
  -snapshot banc_pret -port 5578 &
$SDK/emulator/emulator -avd banc_wear -no-window -no-audio -no-snapshot-save \
  -snapshot banc_pret -port 5576 &

# attendre, en lisant le marqueur et jamais `$?` — ssh via Tailscale ne propage pas les codes
until [ "$($SDK/platform-tools/adb -s emulator-5578 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
```

### Installer et autoriser

```bash
B=~/builds/pendulum-banc
$ADB -s emulator-5578 install -r -t $B/phone/build/outputs/apk/debug/phone-debug.apk
$ADB -s emulator-5576 install -r -t $B/wear/build/outputs/apk/debug/wear-debug.apk

$ADB -s emulator-5578 shell pm grant com.pendulum android.permission.health.READ_SLEEP
$ADB -s emulator-5578 shell pm grant com.pendulum android.permission.POST_NOTIFICATIONS
$ADB -s emulator-5576 shell pm grant com.pendulum android.permission.POST_NOTIFICATIONS
# verifier, car pm grant est silencieux en cas de succes comme en cas de non-effet
$ADB -s emulator-5578 shell dumpsys package com.pendulum | grep "health.READ_SLEEP: granted"
```

### Réveiller la montre — sans quoi tout dump est illisible

L'émulateur de montre bascule en mode ambiant et l'interface se retrouve derrière un cadran ; les
captures d'écran montrent l'application floutée sous l'heure, et `uiautomator` ne rend que
`text="12:27"`.

```bash
$ADB -s emulator-5576 shell input keyevent KEYCODE_WAKEUP
$ADB -s emulator-5576 shell svc power stayon true
$ADB -s emulator-5576 shell settings put system screen_off_timeout 2147483647
```

### Lire un écran

`tools/banc/uictl.py` fait le dump, cherche par texte, identifiant ou description, et tape au
centre de l'élément. Il émet `UICTL_OK` ou `UICTL_FAIL` sur la sortie standard, à lire par
l'appelant distant.

```bash
export ADB=~/Library/Android/sdk/platform-tools/adb
U="python3 tools/banc/uictl.py emulator-5576"
$U dump                       # arbre complet
$U find "Phone unreachable"   # cherche et rend les bornes
$U clic                       # ce qui est reellement actionnable — a utiliser quand tap echoue
$U tap  "Allow"               # tape au centre
$U wait "Ready" 60            # attend l'apparition
$U shot /tmp/ecran.png        # capture
```

Deux leçons apprises en tapant à côté, et encodées dans le script :

- il faut préférer une **égalité exacte** de texte, sinon `tap "Allow"` attrape le titre
  « Allow Pendulum to send you notifications? », qui contient le mot et occupe la moitié de
  l'écran ;
- dans Compose, **l'étiquette d'une case à cocher est un nœud distinct et non cliquable** : taper
  sur le texte ne coche rien et ne produit aucune erreur. D'où la commande `clic`.

### Sauvegarder et recharger un état

```bash
$ADB -s emulator-5578 emu avd snapshot save banc_pret     # 2 s, ~945 Mo
emulator -avd banc_phone34ps -no-window -no-audio -no-snapshot-save -snapshot banc_pret -port 5578
# 5 s jusqu'a boot_completed, application et permissions intactes

# et le menage, a faire a chaque fin de session : un default_boot coute ~1 Go et ne sert a rien
rm -rf ~/.android/avd/banc_phone34ps.avd/snapshots/default_boot
rm -rf ~/.android/avd/banc_wear.avd/snapshots/default_boot
```

---

## 10. Ce que la mesure impose comme architecture

1. **Le banc part de snapshots, toujours.** 5 s contre 55 s, et l'état accordé une fois est
   reconductible. Ce n'était que la sortie de secours du plan ; c'est en fait la seule chose qui
   ait dépassé les attentes.
2. **Le téléphone est `banc_phone34ps`**, sur `android-34;google_apis_playstore;arm64-v8a`, avec
   la perte assumée des deux permissions `READ_HEALTH_DATA_IN_BACKGROUND` et
   `READ_HEALTH_DATA_HISTORY`, qui n'existent pas en API 34. La montre est `banc_wear`, sur
   `android-36;android-wear-signed;arm64-v8a`. **Ne pas utiliser `FarklePhonePortrait`** : son
   image 36.1 ne démarre pas.
3. **Le banc commence par faire le vide**, et refuse de démarrer sous 12 Go de disque libre ou
   au-dessus de 1 Go de swap consommé. Le dépôt savait déjà qu'un build s'enlise sans message
   sous 8 Go ; cette session ajoute qu'un émulateur, lui, démarre sans jamais finir — et qu'un
   émulateur qui s'arrête coûte 1 Go de disque de plus.
4. **La vérification d'appairage se fait sur l'écran de préflight de la montre**, pas sur le
   `dumpsys` du plan, qui n'existe pas.
5. **Aucune ligne de la phase 3 « dégradés du transfert » ne doit être écrite** avant que le §7.1
   soit tranché. Si l'appairage ne survit pas au snapshot, cet agent n'a pas d'objet.

Les phases 1 et 2 du plan, elles, ne dépendent d'aucune de ces inconnues : la source de capteur, la
compression du temps et l'écrivain de sommeil peuvent être écrits tout de suite.

---

## 11. L'état laissé sur le Mac

| | |
|---|---|
| `banc_phone34ps` | AVD téléphone, `android-34;google_apis_playstore;arm64-v8a`, `PlayStore.enabled=yes` corrigé à la main. Prêt à démarrer, pas encore de snapshot, pas de compte Google. |
| `banc_wear` | AVD montre, `android-36;android-wear-signed;arm64-v8a`. Snapshot `banc_pret` (886 Mo) avec Pendulum installé et `POST_NOTIFICATIONS` accordée. |
| `banc_phone34` | **supprimé** après la session pour rendre du disque ; c'est celui des mesures du §2 et du §4, sur `android-34;google_apis`. Se recrée en une commande (§2). |
| `~/builds/pendulum-banc` | copie du dépôt avec `local.properties` renseigné, les deux APK debug construits, et `tools/banc/`. |
| `system-images/android-34/google_apis_playstore` | téléchargée pendant cette session, ~2 Go. |
| Émulateurs en marche | aucun. |

Trois émulateurs d'autres projets ont été arrêtés en début de session (`pend_wear`,
`FarkleShot34`, `MdmTest`), et deux autres en cours de route (`farkle_wear_small_round`,
`takotv_atv`), parce que la machine n'avait plus que 93 Mo de RAM libre. Leurs AVD sont intacts ;
seuls les processus ont été arrêtés proprement par `adb emu kill`.
