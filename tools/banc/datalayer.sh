#!/usr/bin/env bash
# Data Layer probe on REAL HARDWARE, by instrumentation.
#
# Why this detour. The real bench's phone is the user's everyday device: it is locked by a PIN.
# `uiautomator` then only dumps the lock screen, `wm dismiss-keyguard` only brings up the PIN pad,
# and `am start` refuses even to start the process. All the tap-driven steering of §7.4 — written
# for an emulator, which has no PIN — is therefore inapplicable. `am instrument` goes through: the
# runner starts INSIDE the `com.pendulum` process, with its UID, hence with the identity GMS
# checks, and the lock does not concern it.
#
# The probes call the same code as the product (`Preflight.check`, the same `PutDataRequest` as
# `ContextPublication.put`); they do not simulate it.
#
# Usage, from the root of a checkout:
#     bash tools/banc/datalayer.sh deploy
#     bash tools/banc/datalayer.sh build  phone|wear
#     bash tools/banc/datalayer.sh push   phone|wear <serial>
#     bash tools/banc/datalayer.sh run    phone|wear <serial> <method> [-e key value ...]
#
# Every command emits a `DATALAYER_*` marker: ssh over Tailscale does not propagate exit codes, so
# the remote caller must read the marker and not `$?`.
set -o pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

# The probes are **copied** into the androidTest source sets of both modules and are not versioned
# there: they live in `tools/banc/` so that a bench test file never ends up in the APK of an
# ordinary variant by inattention.
#
# This deployment no longer touches the build files. It used to modify them, with a "do not commit"
# note — that is to say, an instruction only an attentive human applies, and the repository carried
# that patch for a whole session. The four `androidTestImplementation` dependencies the probes need
# are now declared in `wear/build.gradle.kts` and `phone/build.gradle.kts`, where they cost
# nothing: `androidTestImplementation` is neither on the compile path nor on the runtime path of
# the published variants.
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
  # What is left is passed as it is to `am instrument`: that is how `purgerBanc` receives
  # `-e session <hex>`. Without this pass-through, a parametrable probe would have to guess its
  # argument.
  local extra=("$@")
  local cls
  case "$m" in
    phone) cls="com.pendulum.phone.BancDataLayer" ;;
    wear)  cls="com.pendulum.wear.BancDataLayer" ;;
    *) echo "DATALAYER_RUN_FAIL unknown module: $m"; return 1 ;;
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
  *) echo "DATALAYER_FAIL unknown command: $1" ;;
esac
