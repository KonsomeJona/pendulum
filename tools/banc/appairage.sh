#!/usr/bin/env bash
# Etat de l'appairage entre le telephone et la montre du banc, verifie en ligne de commande.
#
# A lancer SUR le Mac. Chaque etape emet son propre marqueur sur la sortie standard : ssh via
# Tailscale ne propage pas les codes de sortie, donc l'appelant distant lit les marqueurs et
# jamais `$?`.
#
# Usage : appairage.sh <serie-telephone> <serie-montre>
#   ex.   appairage.sh emulator-5578 emulator-5576
#
# CE QUE CE SCRIPT MESURE, ET CE QU'IL NE MESURE PAS. Il constate trois faits distincts, et la
# lecon de §7.1 est qu'ils ne s'impliquent pas : la configuration d'appairage peut exister, la
# chaine TCP peut etre etablie, et **aucun octet applicatif ne traverser pour autant**. Le seul
# fait qui prouve un Data Layer vivant est le dernier : un item pose par le telephone et relu
# par la montre. Sur emulateur, il n'a jamais ete obtenu.
#
# En particulier : `NodeClient.connectedNodes` rend une liste NON VIDE des que la montre porte
# `device_paired=1`, meme emulateur telephone eteint. L'absence de « Phone unreachable » sur
# l'ecran de preflight ne prouve donc rien sur la liaison. Voir docs/fr/BANC-ESSAI.md §7.1.

PHONE="${1:?serie du telephone manquante}"
WATCH="${2:?serie de la montre manquante}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
UICTL="$(dirname "$0")/uictl.py"

echo "=== 1. le compagnon est-il la ==="
# `com.google.android.apps.wear.companion` est la version moderne, sideloadee depuis un vrai
# telephone. `com.google.android.wearable.app` est l'ancienne, absente de toutes les images.
if "$ADB" -s "$PHONE" shell pm list packages 2>/dev/null | tr -d '\r' \
   | grep -q "com.google.android.apps.wear.companion"; then
  echo "APPAIRAGE_COMPAGNON_PRESENT"
else
  echo "APPAIRAGE_COMPAGNON_ABSENT — installer wear-companion.apk (adb install -r -g)"
fi

echo "=== 2. le pont 5601, dans le bon sens ==="
# LE SENS COMPTE, et le rapport de reconnaissance l'avait inverse. C'est le TELEPHONE qui ECOUTE
# sur 5601 (verifiable : `cat /proc/net/tcp` cote telephone, port 15E1 en etat 0A) ; la montre
# compose 10.0.2.2:5601, c'est-a-dire la boucle locale de l'hote vue depuis son bac a sable
# reseau. Le transfert doit donc pointer vers le telephone, pas vers la montre.
"$ADB" -s "$WATCH" forward --remove-all > /dev/null 2>&1
"$ADB" -s "$PHONE" forward tcp:5601 tcp:5601 > /dev/null 2>&1
echo "APPAIRAGE_FORWARD $("$ADB" forward --list | tr -d '\r' | tr '\n' ';')"

echo "=== 3. la chaine TCP est-elle reellement etablie ==="
# 15E1 = 5601, 0202000A = 10.0.2.2. Etat 01 = ESTABLISHED, 0A = LISTEN.
# Piege : apres un rechargement de snapshot, l'etat TCP de l'invite est restaure depuis la RAM et
# la socket parait etablie meme sans rien en face. Toujours croiser avec la vue de l'hote.
W_SOCK=$("$ADB" -s "$WATCH" shell cat /proc/net/tcp6 2>/dev/null | tr -d '\r' | grep -ci 15E1)
P_LISTEN=$("$ADB" -s "$PHONE" shell cat /proc/net/tcp6 2>/dev/null | tr -d '\r' | grep -ci 15E1)
H_ESTAB=$(lsof -nP -iTCP:5601 2>/dev/null | grep -c ESTABLISHED)
echo "APPAIRAGE_CHAINE montre=$W_SOCK telephone=$P_LISTEN hote_etabli=$H_ESTAB"

echo "=== 4. la configuration d'appairage cote montre ==="
# Pose par le compagnon quand `EmulatorActivity` a abouti. Survit au snapshot et au redemarrage.
echo "APPAIRAGE_CONFIG $("$ADB" -s "$WATCH" shell settings get secure device_paired 2>/dev/null | tr -d '\r')"

echo "=== 5. le seul fait qui prouve un Data Layer vivant ==="
# La montre bloque sur CONTEXT_NOT_SEALED tant que l'item `/pendulum/context/<cle>` pose par le
# telephone ne lui est pas parvenu. C'est un aller simple d'un DataItem reel, et non une lecture
# de configuration : si ce bloqueur disparait, des octets ont traverse.
"$ADB" -s "$WATCH" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
"$ADB" -s "$WATCH" shell svc power stayon true > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am force-stop com.pendulum > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am start -n com.pendulum/.wear.ui.MainActivity > /dev/null 2>&1
sleep 20

if ADB="$ADB" python3 "$UICTL" "$WATCH" find "Fill in the evening form" 2>/dev/null | grep -q UICTL_OK; then
  echo "APPAIRAGE_DATALAYER_MUET — le contexte scelle sur le telephone n'est pas arrive"
elif ADB="$ADB" python3 "$UICTL" "$WATCH" find "Battery" 2>/dev/null | grep -q UICTL_OK; then
  echo "APPAIRAGE_DATALAYER_VIVANT — un DataItem a traverse"
else
  echo "APPAIRAGE_INDETERMINE — l'ecran de preflight n'a pas ete atteint, relire la capture"
  ADB="$ADB" python3 "$UICTL" "$WATCH" shot /tmp/banc-appairage-echec.png
fi

echo "APPAIRAGE_FIN"
