#!/usr/bin/env bash
# The real watch -> phone transfer, and the invariant that holds it: no file is erased from the
# watch before the phone has acknowledged receiving it.
#
# This script exists because the window to be measured is short. Between the moment the phone
# writes its row in the database and the moment the watch erases the file, there is one Data Layer
# round trip; measuring it from WSL, one ssh command at a time, would take longer to ask the
# question than the answer takes to change. The whole timed sequence therefore fits in a single
# invocation on the Mac.
#
# Two preconditions, discovered and not assumed:
#
#  - **The watch is on its dock**, since that is its only USB link. `BatteryManager.isCharging`
#    returns true there, and `StopConditions` then stops the night after `chargingDebounceMs` —
#    240 ms at the bench scale. Without `dumpsys battery unplug`, no recording survives a second.
#    It is reversible with `dumpsys battery reset`, and `restaurer` does it and checks it.
#  - **The screen must be woken before every gesture**: in ambient mode, `input tap` runs without
#    error and taps on nothing (§7.4). Neither `screen_off_timeout` nor `svc power stayon` is
#    touched: the first is already 600,000 ms on this device, and the second has no effect once the
#    battery is declared unplugged.
#
# Usage, from the root of a checkout:
#     bash tools/banc/transfert.sh etat      <watch> <phone>
#     bash tools/banc/transfert.sh preparer  <watch>
#     bash tools/banc/transfert.sh mesurer   <watch> <phone> <seconds> <folder>
#     bash tools/banc/transfert.sh restaurer <watch>
#
# Every command emits `TRANSFERT_*` markers: ssh does not reliably propagate exit codes, so the
# caller reads the markers.
set -o pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

timestamp() { python3 -c 'import time; print(int(time.time()*1000))'; }

wake() {
  "$ADB" -s "$1" shell input keyevent KEYCODE_WAKEUP </dev/null >/dev/null 2>&1
  sleep 0.4
}

# One gesture on the watch, scrolling for as long as the target is not in the tree.
# `uiautomator` only dumps what is **rendered**: on a Wear column, START and STOP are below the
# fold, hence absent from the dump until something has scrolled. Searching without scrolling
# returns "pattern absent", which looks like a screen that is not the right one.
gesture() {
  local s="$1" verb="$2" pattern="$3" extra="$4" i r
  for i in 1 2 3 4 5 6; do
    # Wake **before every attempt**, and not once and for all. A Wear OS falls back to ambient
    # mode in about ten seconds, and a `uiautomator dump` already costs three: the screen read at
    # the start of the loop is no longer the one being touched at the end. Under the watch face,
    # `input tap` runs without error and taps on nothing, and the dump only returns the time.
    # `svc power stayon true` does not replace this wake here: the battery is declared unplugged
    # (see `preparer`), so screen-on-while-charging does not apply.
    wake "$s"
    r=$(python3 tools/banc/uictl.py "$s" "$verb" "$pattern" $extra </dev/null 2>&1 | tail -1)
    case "$r" in
      *UICTL_OK*) echo "$r"; return 0 ;;
    esac
    "$ADB" -s "$s" shell input swipe 228 380 228 150 250 </dev/null >/dev/null 2>&1
    sleep 0.7
  done
  echo "TRANSFERT_GESTE_FAIL $verb $pattern"
  return 1
}

etat() {
  local m="$1" t="$2"
  echo "TRANSFERT_ETAT_HOTE $(timestamp)"
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
  # `unplug` alone is not enough: it removes the power sources but **leaves `status` at 5** (FULL),
  # and `BatteryManager.isCharging()` returns true for FULL just as for CHARGING. On a watch at
  # 100 % sitting on its dock, that is exactly the case. Discharge must therefore be declared too.
  # `dumpsys battery reset` undoes both.
  "$ADB" -s "$m" shell dumpsys battery set status 3 </dev/null >/dev/null 2>&1
  sleep 1
  local battery_state
  battery_state="$("$ADB" -s "$m" shell dumpsys battery </dev/null | grep -E 'AC powered|status:' | tr -d '\r' | tr '\n' ' ')"
  echo "TRANSFERT_PREPARE $battery_state"
}

restaurer() {
  local m="$1"
  "$ADB" -s "$m" shell dumpsys battery reset </dev/null >/dev/null 2>&1
  sleep 1
  local battery_state
  battery_state="$("$ADB" -s "$m" shell dumpsys battery </dev/null | grep -E 'AC powered|status:|level:' | tr -d '\r' | tr '\n' ' ')"
  echo "TRANSFERT_RESTAURE $battery_state"
}

# The timed sequence. Everything below plays out without returning to the caller: that is the only
# way to get comparable instants.
mesurer() {
  local m="$1" t="$2" duration="$3" dir="$4"
  mkdir -p "$dir"
  local U="python3 tools/banc/uictl.py"

  "$ADB" -s "$m" logcat -c </dev/null >/dev/null 2>&1
  "$ADB" -s "$t" logcat -c </dev/null >/dev/null 2>&1
  "$ADB" -s "$m" logcat -v epoch -s PendulumRecord:V PendulumTransfer:V PendulumAck:V \
    PendulumSync:V PendulumPreflight:V >"$dir/montre.log" 2>&1 </dev/null &
  local pid_lm=$!
  "$ADB" -s "$t" logcat -v epoch -s PendulumIngest:V >"$dir/telephone.log" 2>&1 </dev/null &
  local pid_lt=$!

  # The probe covers the whole sequence, start-up included: it must have shown the disks empty
  # before anything appeared on them, otherwise "the file was already there" stays a hypothesis.
  local total
  total=$(python3 -c "print(int($duration) + 75)")
  python3 tools/banc/sonde_transfert.py "$m" "$t" "$total" 250 >"$dir/sonde.txt" 2>&1 </dev/null &
  local pid_probe=$!
  sleep 2

  wake "$m"
  "$ADB" -s "$m" shell am start -n com.pendulum/.wear.ui.MainActivity </dev/null >/dev/null 2>&1
  sleep 3
  wake "$m"
  echo "TRANSFERT_PREFLIGHT_ECRAN"
  $U "$m" dump </dev/null 2>&1 | grep -o 'text="[^"]*"' | sort -u | tr '\n' ' '
  echo

  local t0
  t0=$(timestamp)
  gesture "$m" tap "START"
  echo "TRANSFERT_START_MS $t0"

  sleep "$duration"

  wake "$m"
  echo "TRANSFERT_ECRAN_ENREGISTREMENT"
  $U "$m" dump </dev/null 2>&1 | grep -o 'text="[^"]*"' | sort -u | tr '\n' ' '
  echo

  # The stop, and its fallback. A recording that survives the bench is the only real risk to
  # somebody's device: we do not leave here without having closed it. The nominal path is the
  # product's own gesture — long press then confirmation. If that fails, the watch is declared
  # charging again: `StopConditions` then closes the night **cleanly**, with `StopReason.CHARGING`,
  # the current file closed and a final urgent burst. That is a product path, not a `kill`.
  local t1 means
  t1=$(timestamp)
  means=ECRAN
  if gesture "$m" presse "=STOP" 900; then
    sleep 1
    gesture "$m" tap "Confirm stopping the night" || means=CHARGEUR
  else
    means=CHARGEUR
  fi
  if [ "$means" = CHARGEUR ]; then
    "$ADB" -s "$m" shell dumpsys battery reset </dev/null >/dev/null 2>&1
  fi
  echo "TRANSFERT_STOP_MS $t1 moyen=$means"

  # The rest of the window belongs to the probe: the final burst, the ingestion, the acknowledgement
  # and the deletions all arrive after the sensor has stopped.
  wait $pid_probe
  kill $pid_lm $pid_lt 2>/dev/null
  sleep 1
  echo "TRANSFERT_MESURE_FIN $dir"
}

case "$1" in
  etat)      etat "$2" "$3" ;;
  preparer)  preparer "$2" ;;
  restaurer) restaurer "$2" ;;
  mesurer)   mesurer "$2" "$3" "$4" "$5" ;;
  *) echo "TRANSFERT_FAIL unknown command: $1" ;;
esac
