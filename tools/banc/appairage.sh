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
# Ce que ce script NE fait pas, et pourquoi : il n'appaire pas. L'appairage demande
# l'application compagnon Wear OS cote telephone, qui n'est preinstallee sur aucune image et
# dont l'installation passe par le Play Store, donc par une connexion a un compte Google.
# Voir docs/fr/BANC-ESSAI.md §1 et §7.1. Ce script constate, il ne repare pas.

PHONE="${1:?serie du telephone manquante}"
WATCH="${2:?serie de la montre manquante}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
UICTL="$(dirname "$0")/uictl.py"

echo "=== 1. le compagnon est-il la ==="
# Sans ce paquet cote telephone, rien ne se connecte au port 5601 et l'appairage est impossible.
if "$ADB" -s "$PHONE" shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "com.google.android.wearable.app"; then
  echo "APPAIRAGE_COMPAGNON_PRESENT"
else
  echo "APPAIRAGE_COMPAGNON_ABSENT — installer « Wear OS » depuis le Play Store (geste manuel unique)"
fi

echo "=== 2. le pont 5601 ==="
# Lie les deux demons ADB. Necessaire, tres insuffisant : mesure faite, le pont seul laisse
# `NodeClient.connectedNodes` vide.
"$ADB" -s "$WATCH" forward tcp:5601 tcp:5601 > /dev/null 2>&1
echo "APPAIRAGE_FORWARD $("$ADB" -s "$WATCH" forward --list | tr -d '\r' | tr '\n' ';')"

echo "=== 3. la seule verification qui prouve quelque chose ==="
# `dumpsys activity provider com.google.android.gms.wearable.provider.WearableNodeProvider`
# rend « No providers match » sur ces images : le fournisseur n'existe pas. On lit donc l'ecran
# de preflight de la montre, qui affiche l'avertissement PHONE_UNREACHABLE si et seulement si
# `NodeClient.connectedNodes` a rendu une liste vide — c'est exactement la lecture dont depend
# le produit.
"$ADB" -s "$WATCH" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
"$ADB" -s "$WATCH" shell svc power stayon true > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am force-stop com.pendulum > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am start -n com.pendulum/.wear.ui.MainActivity > /dev/null 2>&1
sleep 15

if ADB="$ADB" python3 "$UICTL" "$WATCH" find "Phone unreachable" 2>/dev/null | grep -q UICTL_OK; then
  echo "APPAIRAGE_ABSENT — la montre ne voit aucun noeud"
elif ADB="$ADB" python3 "$UICTL" "$WATCH" find "Battery" 2>/dev/null | grep -q UICTL_OK; then
  # L'ecran de preflight est bien affiche et l'avertissement n'y est pas : le telephone repond.
  echo "APPAIRAGE_PRESENT — la montre voit le telephone"
else
  echo "APPAIRAGE_INDETERMINE — l'ecran de preflight n'a pas ete atteint, relire la capture"
  ADB="$ADB" python3 "$UICTL" "$WATCH" shot /tmp/banc-appairage-echec.png
fi

echo "APPAIRAGE_FIN"
