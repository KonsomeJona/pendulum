#!/usr/bin/env bash
# State of the pairing between the bench phone and the bench watch, checked from the command line.
#
# To be run ON the Mac. Every step emits its own marker on standard output: ssh over Tailscale does
# not propagate exit codes, so the remote caller reads the markers and never `$?`.
#
# Usage: appairage.sh <phone-serial> <watch-serial>
#   e.g. appairage.sh emulator-5578 emulator-5576
#
# WHAT THIS SCRIPT MEASURES, AND WHAT IT DOES NOT. It establishes three distinct facts, and the
# lesson of §7.1 is that they do not imply one another: the pairing configuration can exist, the
# TCP chain can be established, and **not one application byte need cross for all that**. The only
# fact that proves a live Data Layer is the last one: an item laid down by the phone and read back
# by the watch. On an emulator, it was never obtained.
#
# In particular: `NodeClient.connectedNodes` returns a NON-EMPTY list as soon as the watch carries
# `device_paired=1`, even with the phone emulator switched off. The absence of "Phone unreachable"
# on the preflight screen therefore proves nothing about the link. See docs/workings/BENCH-LOG.md
# §7.1.

PHONE="${1:?missing phone serial}"
WATCH="${2:?missing watch serial}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
UICTL="$(dirname "$0")/uictl.py"

echo "=== 1. is the companion there ==="
# `com.google.android.apps.wear.companion` is the modern version, sideloaded from a real phone.
# `com.google.android.wearable.app` is the old one, absent from every image.
if "$ADB" -s "$PHONE" shell pm list packages 2>/dev/null | tr -d '\r' \
   | grep -q "com.google.android.apps.wear.companion"; then
  echo "APPAIRAGE_COMPAGNON_PRESENT"
else
  echo "APPAIRAGE_COMPAGNON_ABSENT — install wear-companion.apk (adb install -r -g)"
fi

echo "=== 2. the 5601 bridge, the right way round ==="
# THE DIRECTION MATTERS, and the reconnaissance report had it backwards. It is the PHONE that
# LISTENS on 5601 (checkable: `cat /proc/net/tcp` on the phone side, port 15E1 in state 0A); the
# watch dials 10.0.2.2:5601, that is to say the host loopback as seen from its network sandbox. The
# forward must therefore point at the phone, not at the watch.
"$ADB" -s "$WATCH" forward --remove-all > /dev/null 2>&1
"$ADB" -s "$PHONE" forward tcp:5601 tcp:5601 > /dev/null 2>&1
echo "APPAIRAGE_FORWARD $("$ADB" forward --list | tr -d '\r' | tr '\n' ';')"

echo "=== 3. is the TCP chain really established ==="
# 15E1 = 5601, 0202000A = 10.0.2.2. State 01 = ESTABLISHED, 0A = LISTEN.
# Trap: after a snapshot reload, the guest's TCP state is restored from RAM and the socket looks
# established even with nothing on the other end. Always cross-check with the host's view.
W_SOCK=$("$ADB" -s "$WATCH" shell cat /proc/net/tcp6 2>/dev/null | tr -d '\r' | grep -ci 15E1)
P_LISTEN=$("$ADB" -s "$PHONE" shell cat /proc/net/tcp6 2>/dev/null | tr -d '\r' | grep -ci 15E1)
H_ESTAB=$(lsof -nP -iTCP:5601 2>/dev/null | grep -c ESTABLISHED)
# The keys of this line stay as they are: they are quoted word for word in
# `docs/workings/BENCH-LOG.md` §7, which is a dated log.
echo "APPAIRAGE_CHAINE montre=$W_SOCK telephone=$P_LISTEN hote_etabli=$H_ESTAB"

echo "=== 4. the pairing configuration on the watch side ==="
# Laid down by the companion once `EmulatorActivity` has completed. Survives snapshot and reboot.
echo "APPAIRAGE_CONFIG $("$ADB" -s "$WATCH" shell settings get secure device_paired 2>/dev/null | tr -d '\r')"

echo "=== 5. the only fact that proves a live Data Layer ==="
# The watch blocks on CONTEXT_NOT_SEALED for as long as the `/pendulum/context/<key>` item laid
# down by the phone has not reached it. That is a one-way trip of a real DataItem, and not a
# configuration read: if this blocker disappears, bytes have crossed.
"$ADB" -s "$WATCH" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
"$ADB" -s "$WATCH" shell svc power stayon true > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am force-stop com.pendulum > /dev/null 2>&1
"$ADB" -s "$WATCH" shell am start -n com.pendulum/.wear.ui.MainActivity > /dev/null 2>&1
sleep 20

if ADB="$ADB" python3 "$UICTL" "$WATCH" find "Fill in the evening form" 2>/dev/null | grep -q UICTL_OK; then
  echo "APPAIRAGE_DATALAYER_MUET — the context sealed on the phone never arrived"
elif ADB="$ADB" python3 "$UICTL" "$WATCH" find "Battery" 2>/dev/null | grep -q UICTL_OK; then
  echo "APPAIRAGE_DATALAYER_VIVANT — a DataItem crossed"
else
  echo "APPAIRAGE_INDETERMINE — the preflight screen was not reached, read the capture"
  ADB="$ADB" python3 "$UICTL" "$WATCH" shot /tmp/banc-appairage-echec.png
fi

echo "APPAIRAGE_FIN"
