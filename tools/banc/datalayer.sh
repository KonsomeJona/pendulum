#!/usr/bin/env bash
# Sonde du Data Layer sur MATERIEL REEL, par instrumentation.
#
# Pourquoi ce detour. Le telephone du banc reel est l'appareil du quotidien de l'utilisateur : il
# est verrouille par un code. `uiautomator` ne dumpe alors que l'ecran de verrouillage, `wm
# dismiss-keyguard` ne fait qu'afficher le clavier du code, et `am start` refuse jusqu'a demarrer
# le processus. Tout le pilotage par taps du §7.4 — ecrit pour un emulateur, qui n'a pas de code —
# est donc inapplicable. `am instrument` traverse : le runner demarre DANS le processus
# `com.pendulum`, avec son UID, donc avec l'identite que GMS controle, et le verrou ne le concerne
# pas.
#
# Les sondes appellent le meme code que le produit (`Preflight.check`, le meme `PutDataRequest` que
# `EveningContextSealer.publier`) ; elles ne le simulent pas.
#
# Usage, depuis la racine d'une copie du depot :
#     bash tools/banc/datalayer.sh deploy
#     bash tools/banc/datalayer.sh build  phone|wear
#     bash tools/banc/datalayer.sh push   phone|wear <serie>
#     bash tools/banc/datalayer.sh run    phone|wear <serie> <methode> [-e cle valeur ...]
#
# Chaque commande emet un marqueur `DATALAYER_*` : ssh via Tailscale ne propage pas les codes de
# sortie, l'appelant distant doit lire le marqueur et non `$?`.
set -o pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
RACINE="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RACINE" || exit 1

# Les sondes sont **copiees** dans les source sets androidTest des deux modules et n'y sont pas
# versionnees : elles vivent dans `tools/banc/` pour qu'un fichier de test du banc ne se retrouve
# jamais dans l'APK d'une variante ordinaire par distraction.
#
# Ce deploiement ne touche plus aux fichiers de build. Il les modifiait, avec la mention « a ne
# pas commiter » — c'est-a-dire une consigne que seul un humain attentif applique, et le depot a
# porte ce correctif pendant toute une session. Les quatre dependances `androidTestImplementation`
# dont les sondes ont besoin sont desormais declarees dans `wear/build.gradle.kts` et
# `phone/build.gradle.kts`, ou elles ne coutent rien : `androidTestImplementation` n'est ni sur le
# chemin de compilation ni sur le chemin d'execution des variantes publiees.
deploy() {
  mkdir -p phone/src/androidTest/kotlin/com/pendulum/phone
  mkdir -p wear/src/androidTest/kotlin/com/pendulum/wear
  cp tools/banc/BancDataLayer-phone.kt phone/src/androidTest/kotlin/com/pendulum/phone/BancDataLayer.kt
  cp tools/banc/BancDataLayer-wear.kt  wear/src/androidTest/kotlin/com/pendulum/wear/BancDataLayer.kt
  echo "DATALAYER_DEPLOY_OK"
}

build() {
  local m="$1"
  ./gradlew --no-daemon ":$m:assembleDebug" ":$m:assembleDebugAndroidTest" 2>&1 | tail -12
  local apk="$m/build/outputs/apk/androidTest/debug/$m-debug-androidTest.apk"
  [ -f "$apk" ] && echo "DATALAYER_BUILD_OK $apk" || echo "DATALAYER_BUILD_FAIL"
}

push() {
  local m="$1" s="$2"
  "$ADB" -s "$s" install -r -t "$m/build/outputs/apk/debug/$m-debug.apk" 2>&1 | tail -1
  "$ADB" -s "$s" install -r -t "$m/build/outputs/apk/androidTest/debug/$m-debug-androidTest.apk" 2>&1 | tail -1
  echo "DATALAYER_PUSH_FIN"
}

run() {
  local m="$1" s="$2" meth="$3"
  shift 3
  # Ce qui reste est passe tel quel a `am instrument` : c'est par la que `purgerBanc` recoit
  # `-e session <hex>`. Sans ce passe-plat, une sonde parametrable devrait deviner son argument.
  local extra=("$@")
  local cls
  case "$m" in
    phone) cls="com.pendulum.phone.BancDataLayer" ;;
    wear)  cls="com.pendulum.wear.BancDataLayer" ;;
    *) echo "DATALAYER_RUN_FAIL module inconnu: $m"; return 1 ;;
  esac
  "$ADB" -s "$s" logcat -c 2>/dev/null
  "$ADB" -s "$s" shell am instrument -w -r \
    -e class "$cls#$meth" "${extra[@]}" \
    com.pendulum.test/androidx.test.runner.AndroidJUnitRunner 2>&1 \
    | grep -E "INSTRUMENTATION_(STATUS_)?CODE|shortMsg|Error|Failure|junit" | head -20
  sleep 2
  echo "--- logcat ---"
  "$ADB" -s "$s" logcat -d -s BANC:I 2>/dev/null | sed -n 's/.*BANC *: //p'
  echo "DATALAYER_RUN_FIN"
}

case "$1" in
  deploy) deploy ;;
  build)  build "$2" ;;
  push)   push "$2" "$3" ;;
  run)    run "$2" "$3" "$4" "${@:5}" ;;
  *) echo "DATALAYER_FAIL commande inconnue: $1" ;;
esac
