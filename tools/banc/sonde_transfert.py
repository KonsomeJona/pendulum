#!/usr/bin/env python3
"""Simultaneous sampling of both halves of the transfer, to date the invariant.

The invariant to be measured is an **order**: no chunk file is erased from the watch before the
phone has acknowledged receiving it. An order cannot be read from a final state — by the end, the
files have disappeared from both sides of the question. Both disks must therefore be sampled
**during** the window, and fast enough for one Data Layer round trip to fit inside it.

Two threads, one per device, because the two links do not have the same latency: the watch is on
USB (~200 ms per call), the phone on WiFi (~400 ms). Alternating them in a single loop would give
the phone the watch's delay and would make any 200 ms difference indistinguishable from a
measurement artefact.

Every line carries **two** timestamps, the one from before the call and the one from after: the
observation is somewhere between the two, and it is that bracket that must be read, not a single
instant nobody has. Both are in milliseconds since the epoch, taken on the host — hence in a single
clock, which avoids having to correct the drift between the two devices.

Usage:
    sonde_transfert.py <watch_serial> <phone_serial> <duration_s> [period_ms]

Output, one line per sample:
    <ms_before> <ms_after> MONTRE n=<k> <idx:size> ...
    <ms_before> <ms_after> TEL    n=<k> <idx:size> ...

The `MONTRE` and `TEL` labels stay as they are: sample lines are quoted verbatim in
`docs/workings/BENCH-LOG.md` §11, which is a dated log whose whole value is being an exact trace.
"""

import os
import subprocess
import sys
import threading
import time

ADB = os.environ.get("ADB") or os.path.join(
    os.path.expanduser("~"), "Library/Android/sdk/platform-tools/adb"
)
if not os.path.exists(ADB):
    ADB = "adb"

LOCK = threading.Lock()


def now_ms():
    return int(time.time() * 1000)


def list_chunks(serial):
    """The application's chunk files, as seen through `run-as`.

    `ls -lR` and not a glob: the glob would be expanded by the device shell, which runs as the
    `shell` user and has no right to read the application's directory. The recursion, on the other
    hand, happens after `run-as`, hence with the right identity.
    """
    out = subprocess.run(
        [ADB, "-s", serial, "shell", "run-as", "com.pendulum", "ls", "-lR", "files/chunks"],
        capture_output=True,
        timeout=30,
    ).stdout.decode("utf-8", "replace")
    files = []
    for line in out.splitlines():
        fields = line.split()
        if not fields or not fields[-1].endswith(".pendulum"):
            continue
        name = fields[-1].rsplit(".", 1)[0]
        size = fields[4] if len(fields) >= 6 else "?"
        files.append(f"{name}:{size}")
    return sorted(files)


def loop(serial, label, end, period):
    while time.time() < end:
        before = now_ms()
        try:
            f = list_chunks(serial)
            body = f"n={len(f)} " + " ".join(f)
        except Exception as e:  # an adb call that times out must not stop the probe
            body = f"ERROR {e}"
        after = now_ms()
        with LOCK:
            print(f"{before} {after} {label} {body}", flush=True)
        left = period - (after - before) / 1000.0
        if left > 0:
            time.sleep(left)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    watch, phone = sys.argv[1], sys.argv[2]
    duration = float(sys.argv[3])
    period = (float(sys.argv[4]) if len(sys.argv) > 4 else 400) / 1000.0
    end = time.time() + duration
    threads = [
        threading.Thread(target=loop, args=(watch, "MONTRE", end, period)),
        threading.Thread(target=loop, args=(phone, "TEL", end, period)),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print("SONDE_FIN", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
