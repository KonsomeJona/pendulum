#!/usr/bin/env bash
# Le transfert reel montre -> telephone, et l'invariant qui le tient : aucun fichier n'est efface
# de la montre avant que le telephone n'ait accuse sa reception.
#
# Ce script existe parce que la fenetre a mesurer est courte. Entre l'instant ou le telephone
# ecrit sa ligne en base et celui ou la montre efface le fichier, il y a un aller-retour du Data
# Layer ; le mesurer depuis WSL, une commande ssh a la fois, mettrait plus de temps a poser la
# question qu'il n'en faut a la reponse pour changer. Toute la sequence chronometree tient donc
# dans une seule invocation sur le Mac.
#
# Deux prealables, decouverts et non supposes :
#
#  - **La montre est sur son socle**, puisque c'est son seul lien USB. `BatteryManager.isCharging`
#    y rend vrai, et `StopConditions` arrete alors la nuit au bout de `antiRebondChargeMs` — 240 ms
#    a l'echelle du banc. Sans `dumpsys battery unplug`, aucun enregistrement ne survit une seconde.
#    C'est reversible par `dumpsys battery reset`, et `restaurer` le fait et le verifie.
#  - **L'ecran doit etre reveille avant chaque geste** : en mode ambiant, `input tap` s'execute
#    sans erreur et ne tape sur rien (§7.4). On ne touche ni `screen_off_timeout` ni
#    `svc power stayon` : le premier vaut deja 600 000 ms sur cet appareil, et le second est sans
#    effet une fois la batterie declaree debranchee.
#
# Usage, depuis la racine d'une copie du depot :
#     bash tools/banc/transfert.sh etat      <montre> <telephone>
#     bash tools/banc/transfert.sh preparer  <montre>
#     bash tools/banc/transfert.sh mesurer   <montre> <telephone> <secondes> <dossier>
#     bash tools/banc/transfert.sh restaurer <montre>
#
# Chaque commande emet des marqueurs `TRANSFERT_*` : ssh ne propage pas fiablement les codes de
# sortie, l'appelant lit les marqueurs.
set -o pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
RACINE="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RACINE" || exit 1

horodate() { python3 -c 'import time; print(int(time.time()*1000))'; }

reveiller() {
  "$ADB" -s "$1" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1
  sleep 0.4
}

# Un geste sur la montre, en faisant defiler tant que la cible n'est pas dans l'arbre.
# `uiautomator` ne dumpe que ce qui est **rendu** : sur une colonne Wear, START et STOP sont sous
# le pli, donc absents du dump tant qu'on n'a pas fait defiler. Chercher sans defiler rend
# « motif absent », ce qui ressemble a un ecran qui n'est pas le bon.
geste() {
  local s="$1" verbe="$2" motif="$3" extra="$4" i r
  for i in 1 2 3 4 5 6; do
    r=$(python3 tools/banc/uictl.py "$s" "$verbe" "$motif" $extra </dev/null 2>&1 | tail -1)
    case "$r" in
      *UICTL_OK*) echo "$r"; return 0 ;;
    esac
    "$ADB" -s "$s" shell input swipe 228 380 228 150 250 </dev/null >/dev/null 2>&1
    sleep 0.7
  done
  echo "TRANSFERT_GESTE_FAIL $verbe $motif"
  return 1
}

etat() {
  local m="$1" t="$2"
  echo "TRANSFERT_ETAT_HOTE $(horodate)"
  echo "TRANSFERT_ETAT_MONTRE_MS $("$ADB" -s "$m" shell date +%s%3N </dev/null | tr -d '\r')"
  echo "TRANSFERT_ETAT_TEL_MS $("$ADB" -s "$t" shell date +%s%3N </dev/null | tr -d '\r')"
  echo "TRANSFERT_ETAT_BATTERIE $("$ADB" -s "$m" shell dumpsys battery </dev/null | grep -E 'AC powered|status:|level:' | tr -d '\r' | tr '\n' ' ')"
  echo "TRANSFERT_ETAT_ECRAN_MONTRE $("$ADB" -s "$m" shell settings get system screen_off_timeout </dev/null | tr -d '\r')"
  echo "TRANSFERT_ETAT_SERVICE $("$ADB" -s "$m" shell dumpsys activity services com.pendulum </dev/null 2>&1 | grep -c RecordingService)"
  echo "TRANSFERT_ETAT_CHUNKS_MONTRE $("$ADB" -s "$m" shell run-as com.pendulum ls -lR files/chunks </dev/null 2>&1 | tr -d '\r' | tr '\n' '|')"
  echo "TRANSFERT_ETAT_CHUNKS_TEL $("$ADB" -s "$t" shell run-as com.pendulum ls -lR files/chunks </dev/null 2>&1 | tr -d '\r' | tr '\n' '|')"
  echo "TRANSFERT_ETAT_FIN"
}

preparer() {
  local m="$1"
  "$ADB" -s "$m" shell dumpsys battery unplug </dev/null >/dev/null 2>&1
  sleep 1
  local etat_batt
  etat_batt="$("$ADB" -s "$m" shell dumpsys battery </dev/null | grep -E 'AC powered|status:' | tr -d '\r' | tr '\n' ' ')"
  echo "TRANSFERT_PREPARE $etat_batt"
}

restaurer() {
  local m="$1"
  "$ADB" -s "$m" shell dumpsys battery reset </dev/null >/dev/null 2>&1
  sleep 1
  local etat_batt
  etat_batt="$("$ADB" -s "$m" shell dumpsys battery </dev/null | grep -E 'AC powered|status:|level:' | tr -d '\r' | tr '\n' ' ')"
  echo "TRANSFERT_RESTAURE $etat_batt"
}

# La sequence chronometree. Tout ce qui suit se joue sans retour a l'appelant : c'est la seule
# facon d'avoir des instants comparables.
mesurer() {
  local m="$1" t="$2" duree="$3" dir="$4"
  mkdir -p "$dir"
  local U="python3 tools/banc/uictl.py"

  "$ADB" -s "$m" logcat -c </dev/null >/dev/null 2>&1
  "$ADB" -s "$t" logcat -c </dev/null >/dev/null 2>&1
  "$ADB" -s "$m" logcat -v epoch -s PendulumRecord:V PendulumTransfer:V PendulumAck:V \
    PendulumSync:V PendulumPreflight:V >"$dir/montre.log" 2>&1 </dev/null &
  local pid_lm=$!
  "$ADB" -s "$t" logcat -v epoch -s PendulumIngest:V >"$dir/telephone.log" 2>&1 </dev/null &
  local pid_lt=$!

  # La sonde couvre toute la sequence, demarrage compris : elle doit avoir montre les disques
  # vides avant que quoi que ce soit n'y apparaisse, sinon « le fichier existait deja » reste
  # une hypothese.
  local total
  total=$(python3 -c "print(int($duree) + 75)")
  python3 tools/banc/sonde_transfert.py "$m" "$t" "$total" 350 >"$dir/sonde.txt" 2>&1 </dev/null &
  local pid_sonde=$!
  sleep 2

  reveiller "$m"
  "$ADB" -s "$m" shell am start -n com.pendulum/.wear.ui.MainActivity </dev/null >/dev/null 2>&1
  sleep 3
  reveiller "$m"
  echo "TRANSFERT_PREFLIGHT_ECRAN"
  $U "$m" dump </dev/null 2>&1 | grep -o 'text="[^"]*"' | sort -u | tr '\n' ' '
  echo

  local t0
  t0=$(horodate)
  geste "$m" tap "START"
  echo "TRANSFERT_START_MS $t0"

  sleep "$duree"

  reveiller "$m"
  local t1
  t1=$(horodate)
  geste "$m" presse "STOP" 900
  sleep 1
  geste "$m" tap "Confirm stopping the night"
  echo "TRANSFERT_STOP_MS $t1"

  # Le reste de la fenetre appartient a la sonde : la salve finale, l'ingestion, l'accuse et les
  # suppressions arrivent apres l'arret du capteur.
  wait $pid_sonde
  kill $pid_lm $pid_lt 2>/dev/null
  sleep 1
  echo "TRANSFERT_MESURE_FIN $dir"
}

case "$1" in
  etat)      etat "$2" "$3" ;;
  preparer)  preparer "$2" ;;
  restaurer) restaurer "$2" ;;
  mesurer)   mesurer "$2" "$3" "$4" "$5" ;;
  *) echo "TRANSFERT_FAIL commande inconnue: $1" ;;
esac
