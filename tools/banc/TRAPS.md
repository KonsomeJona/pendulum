# The bench traps, and how they fail

Every trap listed here has cost at least a quarter of an hour, and **none of them produces an
error**. That is the entry criterion: a noisy failure fixes itself, a silent failure is paid for
twice, the first time by looking in the wrong place.

The measurements that produced them are in `docs/workings/BENCH-LOG.md`, §7 for the emulators, §11
and §12 for real hardware.

## `adb shell` eats standard input

A script handed to `ssh` through a here-document is read by `bash` **on its standard input**. The
first `adb shell` command in the script reads from it in turn, swallows all the rest, and the
script stops after its first command without saying anything — no error code, no message, an output
that simply looks truncated. It reads like a machine hanging up.

```bash
# wrong: everything after the first adb command is swallowed
ssh jona@192.168.86.161 'bash -ls' <<'FIN'
adb -s $W shell getprop sys.boot_completed
echo "this line will never run"
FIN

# right: every adb call closes its own input
adb -s $W shell getprop sys.boot_completed </dev/null
```

Put `</dev/null` on **every** `adb` invocation, including inside command substitutions.

## `ssh` does not reliably propagate exit codes

Reading `$?` after an `ssh` tells you about `ssh`, not always about what was launched. All the
scripts in `tools/banc/` therefore emit an explicit marker on standard output — `DATALAYER_*`,
`TRANSFERT_*`, `UICTL_OK` / `UICTL_FAIL` — and the caller reads the marker.

Corollary: do not end a remote script on the command that matters. End it on its marker.

## The Mac is reached at its local address, not through Tailscale

`ssh jona@192.168.86.161`. This machine's Tailscale profile switches without warning, and a
Tailscale timeout does not distinguish "machine switched off" from "wrong tailnet". Three wrong
diagnoses have been made for this reason. If the local address does not answer, say so — do not
conclude that the machine is off.

## The watch on its dock declares itself charging

The dock is its only USB link, so any watch reachable over `adb` is a charging watch: `AC powered:
true`, `status: 5` (FULL). `BatteryManager.isCharging()` returns true for FULL just as for
CHARGING, and `StopConditions` then closes the night after `chargingDebounceMs` — 240 ms at scale
250. **No recording survives one second.**

```bash
adb -s $W shell dumpsys battery unplug </dev/null
adb -s $W shell dumpsys battery set status 3 </dev/null   # `unplug` alone leaves status=5
# and, without fail, at the end:
adb -s $W shell dumpsys battery reset </dev/null
```

`transfert.sh preparer` and `transfert.sh restaurer` do both and check them.

## The watch screen falls back to ambient in about ten seconds

Under the watch face, `input tap` runs without error and taps on nothing, and `uiautomator dump`
returns nothing but the time. A `uiautomator dump` already costs three seconds: the screen read at
the start of a loop is no longer the one being touched at the end.

Wake **before every gesture**, not once and for all. `svc power stayon true` does not replace that
wake when the battery is declared unplugged: the hold only applies on mains power.

## Searching for a button by containment catches its label

`presse STOP` found "Long press to stop" — which contains the word, which is not clickable, and
which sits **above** the real button. The long press ran without error and did nothing. Sorting by
"exactly equal text first" does not save it: when the real button is below the fold, it is not in
the dump, and the sort ranks what it has.

`uictl.py` therefore accepts a pattern prefixed with `=` to require equality: `presse "=STOP"`. The
target then stays unfindable until something has scrolled, which is the truth.

## A whole remote script needs `</dev/null`, not just its `adb` calls

The trap above replays one level higher. `ssh … 'bash -ls' <<'FIN'` makes the script be read on
standard input, and **everything the script calls inherits that input** — including
`tools/banc/datalayer.sh`, whose internal `adb shell` calls do not all carry their `</dev/null`.
The remote script then stops halfway through, without a message, and the caller receives empty
output. The symptom changes from one run to the next depending on the size of `bash`'s buffer,
which gives the impression of a temperamental machine.

```bash
# right: drop the script off and run it with a closed input
scp script.sh jona@192.168.86.161:~/script.sh
ssh jona@192.168.86.161 "bash ~/script.sh </dev/null"
```

## `svc wifi disable` has no effect on the watch

`adb shell svc wifi disable` returns without error and `settings get global wifi_on` stays at `1`.
The watch keeps its address and its link. Any measurement resting on "I switched WiFi off" must
**re-read the state** before concluding: §12.2 came very close to concluding an isolation that
never took place.

## `am instrument` stops WorkManager from running

`am instrument` force-stops the package at the start **and at the end**. A WorkManager chain queued
by the product — `IngestWorker` → `AnalyzeWorker` → … — is cancelled at every probe, and a probe
that queries the database to find out whether the analysis ran prevents it from running by the very
same gesture. Add to that the freezing of cached processes (`ActivityManager: freezing <pid>
com.pendulum`) and the `RESTRICTED` standby bucket of an application nobody ever opens.

Practical consequence: **do not measure a WorkManager chain by instrumentation.** §12.5 tried
three times, once with the bucket raised to `ACTIVE`, and did not get there. `cmd jobscheduler run
-f com.pendulum <id>` is no use either: the identifiers read in `dumpsys jobscheduler` are
WorkManager's, not the `JobScheduler`'s, and it answers `Could not find job`.

## `am instrument` kills the process, so `apply()` is lost

`SharedPreferences.apply()` writes asynchronously. `am instrument` force-stops the package at the
end of the test: the setting looks laid down, it is not, and the next measurement fails for the
reason one believed had been ruled out. In a probe, write with `commit()`.

Corollary: `am instrument` also force-stops **at the start**, which kills any running service.
Never launch a probe on a device that is recording.

## `sqlite3` does not exist on the phone

`run-as com.pendulum sqlite3 …` returns `exec failed: No such file or directory`. Reading the
database therefore goes through a probe that uses the product's `openHelper` (`BancDataLayer#base`),
or through an `exec-out run-as com.pendulum cat databases/pendulum.db` fetched back and opened on
the host.

## The Fold corrupts `screencap`, and its work profile makes `pm list packages` fail

Two hardware displays: `exec-out screencap -p` prefixes the stream with `[Warning] Multiple
displays…` and the PNG fetched back is unreadable, without any command having failed. And `pm list
packages` returns `SecurityException: Shell does not have permission to access user 10` and then,
in spite of that, user 0's list. Read the output, not the exit code.

## What the time divisor does not compress

`-Ppendulum.temps.diviseur=250` compresses **wall-clock** durations. It compresses neither the
burst latency of the sensor FIFO (30 s in `WAKEUP 30 s` mode, a hardware figure) nor the local
cut-off time, which is a time of day and not a duration. With the real accelerometer, recording
therefore stops at 14.4 s — the compressed guard delay — **before** the sensor has delivered its
first byte, and the bench produces zero chunks without signalling anything.

The consistency protected by `ScaleConsistencyTest` only holds for `SyntheticSource`, whose replay
compresses sensor time by the same factor. On real hardware, push the cut-off time aside:

```bash
bash tools/banc/datalayer.sh run wear $W heureButoir -e minutes 1439
# ... and return it to its absence at the end:
bash tools/banc/datalayer.sh run wear $W heureButoir -e minutes defaut
```

## Leave nothing behind

A chunk the bench has manufactured will **never** leave on its own: files are only erased on an
acknowledgement. Purge explicitly, on both sides, naming the session:

```bash
bash tools/banc/datalayer.sh run wear  $W purgerBanc -e session <hex>
bash tools/banc/datalayer.sh run phone $P purgerBanc -e session <hex>
bash tools/banc/datalayer.sh run phone $P retirerContexte
```
