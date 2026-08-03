# Les pièges du banc, et comment ils échouent

Tous ceux qui sont listés ici ont coûté au moins un quart d'heure, et **aucun ne produit d'erreur**.
C'est le critère d'entrée : un échec bruyant se corrige tout seul, un échec silencieux se paie deux
fois, la première en cherchant au mauvais endroit.

Les mesures qui les ont produits sont dans `docs/fr/BANC-ESSAI.md`, §7 pour les émulateurs, §11 et
§12 pour le matériel réel.

## `adb shell` mange l'entrée standard

Un script passé à `ssh` par document en ligne est lu par `bash` **sur son entrée standard**. La
première commande `adb shell` du script y lit à son tour, avale tout le reste, et le script
s'arrête après sa première commande sans rien dire — pas de code d'erreur, pas de message, une
sortie qui paraît simplement tronquée. On croit à une machine qui raccroche.

```bash
# faux : tout ce qui suit la premiere commande adb est avale
ssh jona@192.168.86.161 'bash -ls' <<'FIN'
adb -s $W shell getprop sys.boot_completed
echo "cette ligne ne s'executera jamais"
FIN

# juste : chaque appel adb ferme son entree
adb -s $W shell getprop sys.boot_completed </dev/null
```

Mettre `</dev/null` sur **chaque** invocation `adb`, y compris dans les substitutions de commande.

## `ssh` ne propage pas fiablement les codes de sortie

Lire `$?` après un `ssh` renseigne sur `ssh`, pas toujours sur ce qu'on a lancé. Tous les scripts
de `tools/banc/` émettent donc un marqueur explicite sur la sortie standard — `DATALAYER_*`,
`TRANSFERT_*`, `UICTL_OK` / `UICTL_FAIL` — et l'appelant lit le marqueur.

Corollaire : ne pas terminer un script distant sur la commande qui compte. Terminer sur son
marqueur.

## Le Mac se joint par son adresse locale, pas par Tailscale

`ssh jona@192.168.86.161`. Le profil Tailscale de ce poste bascule sans prévenir, et un délai
d'attente Tailscale ne distingue pas « machine éteinte » de « mauvais tailnet ». Trois diagnostics
faux ont été posés pour cette raison. Si l'adresse locale ne répond pas, le dire — ne pas conclure
que la machine est éteinte.

## La montre sur son socle se déclare en charge

Le socle est son seul lien USB, donc toute montre joignable par `adb` est une montre en charge :
`AC powered: true`, `status: 5` (FULL). `BatteryManager.isCharging()` rend vrai pour FULL comme
pour CHARGING, et `StopConditions` ferme alors la nuit après `antiRebondChargeMs` — 240 ms à
l'échelle 250. **Aucun enregistrement ne survit une seconde.**

```bash
adb -s $W shell dumpsys battery unplug </dev/null
adb -s $W shell dumpsys battery set status 3 </dev/null   # `unplug` seul laisse status=5
# et, sans faute, a la fin :
adb -s $W shell dumpsys battery reset </dev/null
```

`transfert.sh preparer` et `transfert.sh restaurer` font les deux et les vérifient.

## L'écran de la montre retombe en ambiant en une dizaine de secondes

Sous le cadran, `input tap` s'exécute sans erreur et ne tape sur rien, et `uiautomator dump` ne rend
que l'heure. Un `uiautomator dump` coûte déjà trois secondes : l'écran lu au début d'une boucle
n'est plus celui qu'on touche à la fin.

Réveiller **avant chaque geste**, pas une fois pour toutes. `svc power stayon true` ne remplace pas
ce réveil quand la batterie est déclarée débranchée : le maintien ne vaut que sur secteur.

## Chercher un bouton par inclusion attrape son étiquette

`presse STOP` a trouvé « Long press to stop » — qui contient le mot, qui n'est pas cliquable, et
qui est **au-dessus** du vrai bouton. L'appui long s'est exécuté sans erreur et n'a rien fait.
Le tri « texte exactement égal d'abord » ne sauve pas : quand le vrai bouton est sous le pli, il
n'est pas dans le dump, et le tri classe ce qu'il a.

`uictl.py` accepte donc un motif préfixé de `=` pour exiger l'égalité : `presse "=STOP"`. La cible
reste alors introuvable tant qu'on n'a pas fait défiler, ce qui est la vérité.

## Un script distant entier doit avoir `</dev/null`, pas seulement ses `adb`

Le piège ci-dessus se rejoue un cran plus haut. `ssh … 'bash -ls' <<'FIN'` fait lire le script sur
l'entrée standard, et **tout ce que le script appelle hérite de cette entrée** — y compris
`tools/banc/datalayer.sh`, dont les `adb shell` internes n'ont pas tous leur `</dev/null`. Le script
distant s'arrête alors au milieu, sans message, et l'appelant reçoit une sortie vide. Le symptôme
change d'une exécution à l'autre selon la taille du tampon de `bash`, ce qui donne l'impression
d'une machine capricieuse.

```bash
# juste : deposer le script et l'executer avec une entree fermee
scp script.sh jona@192.168.86.161:~/script.sh
ssh jona@192.168.86.161 "bash ~/script.sh </dev/null"
```

## `svc wifi disable` n'a aucun effet sur la montre

`adb shell svc wifi disable` rend la main sans erreur et `settings get global wifi_on` reste à `1`.
La montre garde son adresse et son lien. Toute mesure qui repose sur « j'ai coupé le WiFi » doit
**relire l'état** avant de conclure : le §12.2 a bien failli conclure à une isolation qui n'a jamais
eu lieu.

## `am instrument` empêche WorkManager de tourner

`am instrument` force l'arrêt du paquet au début **et à la fin**. Une chaîne WorkManager enfilée par
le produit — `IngestWorker` → `AnalyzeWorker` → … — est annulée à chaque sonde, et une sonde qui
interroge la base pour savoir si l'analyse a tourné empêche du même geste qu'elle tourne. S'y ajoute
le gel des processus en cache (`ActivityManager: freezing <pid> com.pendulum`) et le seau de veille
`RESTRICTED` d'une application que personne n'ouvre jamais.

Conséquence pratique : **on ne mesure pas une chaîne WorkManager par instrumentation.** Le §12.5 a
essayé trois fois, dont une avec le seau porté à `ACTIVE`, et n'y est pas parvenu. `cmd jobscheduler
run -f com.pendulum <id>` ne sert à rien non plus : les identifiants lus dans `dumpsys jobscheduler`
sont ceux de WorkManager, pas ceux du `JobScheduler`, et il répond `Could not find job`.

## `am instrument` tue le processus, donc `apply()` se perd

`SharedPreferences.apply()` écrit de façon asynchrone. `am instrument` force l'arrêt du paquet à la
fin du test : le réglage semble posé, il ne l'est pas, et la mesure suivante échoue pour la raison
qu'on croyait avoir écartée. Dans une sonde, écrire en `commit()`.

Corollaire : `am instrument` force aussi l'arrêt **au début**, ce qui tue tout service en cours.
Ne jamais lancer une sonde sur un appareil qui enregistre.

## `sqlite3` n'existe pas sur le téléphone

`run-as com.pendulum sqlite3 …` rend `exec failed: No such file or directory`. Lire la base passe
donc par une sonde qui utilise l'`openHelper` du produit (`BancDataLayer#base`), ou par un
`exec-out run-as com.pendulum cat databases/pendulum.db` rapatrié et ouvert sur l'hôte.

## Le Fold corrompt `screencap`, et son profil de travail fait échouer `pm list packages`

Deux affichages matériels : `exec-out screencap -p` préfixe le flux de `[Warning] Multiple
displays…` et le PNG rapatrié est illisible, sans qu'aucune commande n'ait échoué. Et
`pm list packages` rend `SecurityException: Shell does not have permission to access user 10` puis,
malgré tout, la liste de l'utilisateur 0. Lire la sortie, pas le code de retour.

## Ce que le diviseur de temps ne comprime pas

`-Ppendulum.temps.diviseur=250` comprime les durées **murales**. Il ne comprime ni la latence de
salve du FIFO du capteur (30 s en mode `WAKEUP 30 s`, du matériel), ni l'heure butoir locale, qui
est une heure et non une durée. Avec le vrai accéléromètre, l'enregistrement s'arrête donc à 14,4 s
— le délai de garde comprimé — **avant** que le capteur n'ait livré son premier octet, et le banc
produit zéro chunk sans rien signaler.

La cohérence que protège `CoherenceEchelleTest` ne vaut que pour `SourceSynthetique`, dont le rejeu
comprime le temps capteur d'autant. Sur matériel réel, écarter l'heure butoir :

```bash
bash tools/banc/datalayer.sh run wear $W heureButoir -e minutes 1439
# ... et la rendre a son absence a la fin :
bash tools/banc/datalayer.sh run wear $W heureButoir -e minutes defaut
```

## Ne rien laisser derrière

Un chunk que le banc a fabriqué ne partira **jamais** tout seul : les fichiers ne sont effacés que
sur un accusé. Purger explicitement, des deux côtés, en nommant la session :

```bash
bash tools/banc/datalayer.sh run wear  $W purgerBanc -e session <hex>
bash tools/banc/datalayer.sh run phone $P purgerBanc -e session <hex>
bash tools/banc/datalayer.sh run phone $P retirerContexte
```
