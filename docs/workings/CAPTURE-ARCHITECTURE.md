# Pendulum — Capture and transfer architecture (watch → phone)

Design document; it completes and supersedes the "`wear` module" and "Watch → phone transfer" sections of [`SPEC-v1.md`](SPEC-v1.md), which is historical. Target: Pixel Watch 3 (41 mm, ~306 mAh) worn **on the ankle**, accelerometer at 50 Hz for ~8 h, Android phone on the bedside table. Wear OS 5 / Android 14-15, `compileSdk 35`, personal sideload.

> **This document is authoritative on the energy budget and on the protocol**, that is, on §1 (the
> three options costed in mAh) and on §2.3 (the exact schema of each `DataItem`). They exist
> nowhere else. Its counterpart among the numbered files, shorter and kept up to date, is
> [`../04-architecture.md`](../04-architecture.md); **the byte-by-byte layout of the chunk format as
> it stands today** is over there in §3, because here we only write down the differences.
>
> **Everything costed here in milliampere-hours is an engineer's estimate, not a
> measurement**, and §0 honestly separates what has been verified against a primary source from what
> is assumed folklore. Since then, several of the questions left open have been settled on real
> hardware: the `health` foreground service survived 32 minutes of forced deep Doze, screen
> off, with no wake lock and without being killed, and the transfer invariant — nothing is erased from
> the watch before the phone's acknowledgement — has been verified in both directions.
> **[`BENCH-LOG.md`](BENCH-LOG.md) §12 is that report**, with the commands; its §12.6 says
> exactly what is still not proven, starting with the battery, which a watch left on
> its dock does not allow one to measure.
>
> §0 dates its verifications to "today" without saying which day. The repository places the writing at
> the end of July 2026; every API or library version line must be re-checked before being
> used.

---

## 0. What was verified today, and what was not

The instruction called for a web check. The session's `WebSearch` budget was exhausted; I therefore **fetched the official pages directly** (which is better than a search). Strict distinction below between what is documented and what is folklore.

### Verified today against a primary source

| Fact | Source |
|---|---|
| The Android 15 timeout (6 h per 24 h) only concerns **`dataSync` and `mediaProcessing`**. Once the deadline passes: `Service.onTimeout(int,int)`, a few seconds to `stopSelf()`, otherwise `RemoteServiceException: "A foreground service of type dataSync did not stop within its timeout"`. The timer re-arms if the app returns to the foreground. | [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15) |
| Apps targeting Android 15+ **cannot start from `BOOT_COMPLETED`** an FGS of type `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`. **`health` is not on the list.** | [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15) |
| FGS `health` = `FOREGROUND_SERVICE_HEALTH` + **at least one** of `BODY_SENSORS` / `READ_HEART_RATE` / `READ_SKIN_TEMPERATURE` / `READ_OXYGEN_SATURATION` / `ACTIVITY_RECOGNITION`, **or else** declaring `HIGH_SAMPLING_RATE_SENSORS` in the manifest. | [fg-service-types](https://developer.android.com/develop/background-work/services/fg-service-types) |
| `BODY_SENSORS` and the `READ_*` sensor permissions are subject to **while-in-use**: it is impossible to create a `health` FGS backed by these permissions **from the background** without `BODY_SENSORS_BACKGROUND` (API 33-35) / `READ_HEALTH_DATA_IN_BACKGROUND` (API 36+). | [fg-service-types](https://developer.android.com/develop/background-work/services/fg-service-types) |
| **A `DataItem` payload is limited to 100 KB.** | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| "If the handset and wearable devices are disconnected, **the data is buffered and synced when the connection is re-established**." | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| Without `setUrgent()`, the system **may delay the sync by up to 30 min** (in practice a few minutes). | [data-items](https://developer.android.com/training/wearables/data/data-items) |
| "It's possible to set data items and assets while not connected to any devices. They're synchronized when the devices establish a network connection." The Data Layer **does not work** with a watch paired to iOS. | [data-layer](https://developer.android.com/training/wearables/data-layer) |
| **Non-wake-up sensor in suspend**: "the sensors must continue to function and generate events, which are put in a hardware FIFO" but "**if the FIFO is too small to store all events, the older events are lost; the oldest data is dropped to accommodate the latest data**". Without a FIFO, everything is lost. | [sensors/suspend-mode](https://source.android.com/docs/core/interaction/sensors/suspend-mode) |
| **Wake-up sensor in suspend**: "When the SoC is asleep, wake-up sensors **must wake up the SoC** to deliver events" — before exceeding the maximum reporting latency **or filling the FIFO**. | [sensors/suspend-mode](https://source.android.com/docs/core/interaction/sensors/suspend-mode) |

This last point is the keystone of the whole capture strategy, and it is **normative at the HAL level**: with a wake-up sensor, loss through FIFO overflow is forbidden by the contract; with a non-wake-up sensor, it is explicitly permitted. All of section 3 follows from it.

### Not verifiable today (reference pages did not render) but documented elsewhere — to be re-confirmed

- `Sensor.getFifoReservedEventCount()` (guaranteed to this app) vs `getFifoMaxEventCount()` (total capacity, **shared** between clients). `SPEC-v1.md` uses `max(reserved, max)`: **that is optimistic and must be corrected** (§3.2).
- `SensorManager.registerListener(l, s, samplingPeriodUs, maxReportLatencyUs)` and the fact that `maxReportLatencyUs` is a ceiling, not a guarantee.
- Ambient mode: a single update per minute for an always-on activity.

### Folklore assumed as such (no primary source found this session)

- "`ExerciseClient` forces the accelerometer to 100 Hz and overrides the requested rate" (reported on the Samsung Health Services side). **Not verified.** The cost of caution is nil: we never start an `ExerciseClient`. We keep the rule, we drop the factual claim.
- Useful Data Layer throughput over Bluetooth: **20 to 100 KB/s** according to field reports. No documented value. To be measured in P3.
- `DataItem` store quota on the watch: **not documented**. Hence the in-flight item ceiling of §2.6.
- Off-body behaviour on the ankle: not documented (`SPEC-v1.md` already classes it as low confidence).
- Effect of Wear OS Bedtime mode / Battery Saver on a `health` FGS: not documented. **Mandatory P1 test, Bedtime mode ON**, since that is the real usage configuration.

---

## 1. Decision: streaming, batch or hybrid — costed

### 1.1 Short answer

**Hybrid, and the review comment "streaming = energy suicide, stay in batch" is wrong about the proportions.** The radio cost of an incremental sync every 15 min is on the order of **3 to 11 mAh over the night, that is 1 to 3.6 % of the battery** — which is **between 20 and 60 times less** than the uncertainty on the sensor acquisition strategy (a partial wake lock held for 8 h costs ~200 mAh, 65 %). Refusing 3 % of battery in exchange for accepting the potential loss of an entire night is a bad trade, and above all it is **optimising the wrong item**.

What is genuinely suicidal is **high-rate streaming** (keeping the BT link in active mode or sending a message per second): 52 to 100 % of the battery, and on top of that it does not carry the raw signal, so it solves nothing.

The initial proposal — *push each closed chunk as soon as it is complete* — is **valid and adopted**, with two corrections:

1. **The chunk goes down from 30 min to 5 min** (`SPEC-v1.md` planned 30 min / 8 MB). At 5 min a chunk weighs ~91 KB, which makes it **fit inside the 100 KB payload of a `DataItem`** — so no `Asset`, no `Channel`, no handshake on the nominal path. The loss granularity drops from 30 min to 5 min *on disk* and to ~15 min *on the phone side*.
2. **No "when the node is reachable".** That is exactly what must not be coded: the `DataClient` **already buffers and synchronises on reconnection** (documented). Testing reachability before writing means reintroducing by hand a logic the layer provides, and getting it wrong at the precise moment it matters (phone in doze but link alive). We write, full stop.

### 1.2 Calculation assumptions (and why they are fragile)

| Parameter | Value used | Plausible range | Confidence |
|---|---|---|---|
| Battery capacity | 306 mAh @ 3.85 V ≈ 1.18 Wh | — | High (manufacturer spec) |
| Useful Data Layer throughput | 50 KB/s | 20–100 | **Low** — folklore |
| Incremental current during transfer (radio + AP awake) | 40 mA | 25–70 | **Low** |
| Fixed overhead per burst (leaving suspend, sniff→active connection latency) | 3 s | 1.5–5 s | **Low** |
| BT link kept in low-latency active mode | +20 mA continuous | 10–35 | **Low** |
| Partial wake lock (AP awake) | +25 mA continuous | 15–40 | **Low** |
| Raw data rate | 303 B/s (50 Hz × 6 B + block headers) | — | High (calculation) |

**Overall confidence in the absolute values: low.** Confidence in **the relative order of magnitude between the three options: medium-high** — because all three share the same unknown constants, which largely cancel out in the ratios. It is that ratio that carries the decision, not the absolute milliampere-hours.

### 1.3 The three options, costed

Reference night: 8 h, 8.73 MB, 96 chunks of 91 KB.

**(i) Incremental sync, 32 bursts of ~275 KB (3 chunks) every 15 min**

```
time/burst   = 275 KB / 50 KB/s + 3 s     = 8.5 s
charge/burst = 40 mA × 8.5 s              = 0.094 mAh
total        = 32 × 0.094                 = 3.0 mAh  →  0.98 % of 306 mAh
worst case (20 KB/s, 70 mA, 4 s overhead) = 11.0 mAh →  3.6 %
```

**(i-bis) Paranoid variant: one burst per closed chunk, 96 bursts of 91 KB every 5 min**

```
time/burst   = 91/50 + 3                  = 4.8 s
total        = 96 × 40 mA × 4.8 s         = 5.1 mAh  →  1.7 %
worst case                                = 18 mAh   →  5.9 %
```

Note that going from 15 min to 5 min multiplies the cost only by **1.7**, not by 3: the fixed wake-up overhead dominates the transmission time. The choice between the two **is therefore not an energy trade-off** (the gap, 2 mAh, is smaller than my own error bars); it is a caution trade-off against a battery budget that has not yet been measured.

**(ii) A single 8.8 MB transfer on waking**

```
time  = 8800 KB / 50 KB/s                 = 176 s
cost  = 40 mA × 176 s                     = 1.96 mAh →  0.64 %
worst case (20 KB/s, 70 mA)               = 8.6 mAh  →  2.8 %
```

And this transfer happens **on the charger** → real cost over the night ≈ **0**. It is indeed the cheapest option. It costs 0 mAh and can cost 8 hours of data.

**(iii) Continuous streaming of a decimated envelope (1 Hz, 4 B/sample = 115 KB/night)**

The volume is ridiculous; **the cost is not in the bytes, it is in the link's service rate**. One message per second prevents the BT link from dropping back to sniff and the AP from suspending:

```
20 mA × 8 h                               = 160 mAh  →  52 %  (radio alone)
+ AP never suspended                      → up to 100 %
```

"Heartbeat" variant at 30 s: 960 wake-ups × ~2 s × 30 mA = **16 mAh (5.2 %)** — bearable, but **it still does not carry the raw data**, so it does not answer the resilience need. Non-zero cost for zero benefit: rejected.

### 1.4 Adopted decision

| | Night cost | Max loss if the watch dies |
|---|---|---|
| Batch on waking only (reviewer) | ~0 % | **up to 8 h** |
| **Adopted hybrid (N=3, 15 min)** | **1 – 3.6 %** | **≤ 20 min** |
| Aggressive hybrid (N=1, 5 min) | 1.7 – 5.9 % | ≤ 10 min |
| 1 Hz envelope streaming | 52 – 100 % | 8 h of raw data all the same |

**Adopted: incremental sync via `DataClient`, one burst every `PUSH_EVERY_N_CHUNKS = 3` chunks (15 min), the constant exposed as a developer setting so it can be moved to 1 after the P1 measurement.** Plus:

- **An envelope preview carried for free in the same burst.** Each push updates a single `DataItem` `/pendulum/live/<session>` containing, in addition to the counters, the RMS envelope of the last 15 minutes decimated to 1 Hz, quantised as logarithmic u8: **900 bytes, that is +0.3 % of the burst volume and zero extra radio wake-up.** This is literally the "real-time movement chart" that was asked for, at zero cost, because it travels inside a radio wake-up we are already paying for.
  **This preview is never used for scoring** — the PLMI is always recomputed from the raw data on the phone. Display and sign of life only.
- **A catch-up sweep (`ChannelClient`) on waking / on the charger**, for the remainder and for degraded cases (phone switched off all night).

This combination answers the question as it was asked: *real time* for the chart (15 min of latency, zero cost), *incremental* for the raw data (loss bounded at 20 min), *batch* for the catch-up (free, on the charger).

---

## 2. Transfer protocol

### 2.1 API choice, and why

| API | Semantics | Verdict |
|---|---|---|
| `MessageClient` | Fire-and-forget. Fails if the node is unreachable, **no queue, no resume**. | **Control plane only** — and even there, a `DataItem` is preferred. Never for data we do not want to lose. |
| `DataClient` | **Replicated and persistent** store. Payload ≤ 100 KB. **Buffered offline, synchronised on reconnection** (documented). Deduplicated: re-putting an identical item triggers nothing. Delivered to a `WearableListenerService` that GMS starts, even if the app has never been opened. | **Nominal path.** It is the only API whose semantics match "lose nothing". |
| `ChannelClient` | Byte stream, low overhead, **no persistence**: the channel dies with the connection, both ends must be alive at the same time. | **Bulk catch-up only**, when both sides are known to be online and throughput is wanted without inflating the data store. |

The decisive point is this one: **with `DataClient`, a chunk pushed at 2 a.m. is already replicated; the watch dying at 3 a.m. no longer concerns it.** With `ChannelClient`, every interrupted transfer has to be redone, and if the source has vanished there is nothing left to redo. `SPEC-v1.md` made `ChannelClient` the nominal path, justified by "`MessageClient` caps out at 100 KB" — the reasoning skips the `DataClient` option, which has the same cap but persistent-store semantics. **It is that omission which made batch-on-waking inevitable.** By cutting the chunk below 100 KB, `DataClient` becomes the nominal transport and the problem disappears.

### 2.2 Chunk size and rotation

```
rate  = 50 Hz × 6 B + 32 B block header / 10.24 s    = 300 + 3.1 = 303.1 B/s
5 min → 90 930 B + 80 B file header                  = 91 010 B
```

Rotation rule: **close the current chunk as soon as `elapsed ≥ 300 s` OR `bytesWritten ≥ 92 160` (90 KiB)**. The byte ceiling is the hard guard rail: it holds even if the real `fs` drifts to 52.6 Hz (`SPEC-v1.md` trap no. 2) or if a degraded mode changes the rate. Remaining margin below 100 KB after the ~200 B of `DataMap`: **7.4 %**.

If a device nevertheless refuses the payload, the escape hatch is `Asset.createFromFd()` on the same `DataItem` — same protocol, a single point of code to change. Do not implement it in anticipation.

### 2.3 Namespace and messages

Everything that follows is a `DataItem` unless stated otherwise. The wire structures live in the `format` module (pure JVM, testable on both sides).

**`/pendulum/session/<sessionHex>`** — watch → phone, `setUrgent()`
```
{ v, sessionHex, startWallMs, tzOffsetMin, nominalRateHz, modeFlags,
  plannedStopWallMs, state: OPEN|CLOSED, endWallMs?, totalChunks?, stopReason? }
```
Put at session open (`OPEN`), rewritten on clean close (`CLOSED` + total). This is what lets the phone know that a night **exists** before having received its end — a necessary condition for detecting a truncated night.

**`/pendulum/chunk/<sessionHex>/<idx:05d>`** — watch → phone, **without** `setUrgent()` except the last of each burst
```
DataMap : { idx, sampleCount, tFirstNs, tLastNs, crc32, size, flagsOr }
payload : the exact bytes of the chunk file
```
`setUrgent()` on the **last item of the burst only**. Assumption (medium confidence, undocumented): the flush it provokes also carries the pending non-urgent items. **If P3 shows otherwise, put `setUrgent()` on all of them** — the energy impact is nil, they leave in the same wake-up.

**`/pendulum/live/<sessionHex>`** — watch → phone, `setUrgent()`, **replaced** at each burst, never accumulated
```
{ lastUpdateMs, elapsedMs, samplesWritten, bytesWritten, batteryPct,
  gapCount, gapTotalMs, mode, lastClosedChunkIdx, envU8: ByteArray(900) }
```

**`/pendulum/ack/<sessionHex>`** — phone → watch, `setUrgent()`, rewritten at each ingestion
```
{ ackedUpTo, bitmapBase, ackedBitmap: ByteArray, needResend: IntArray, phoneMs }
```
`ackedBitmap` covers `[bitmapBase, bitmapBase + 8×len)`. `needResend` = indices received but with an **invalid CRC32**.

**Structuring choice: the acknowledgement is a `DataItem`, not a message.** An ack sent by `MessageClient` while the watch is out of range is lost, and the watch would keep its files forever. An ack as a `DataItem` is a **convergent state**: the watch reads it when it can, and the state is the same however many times it reads it. Idempotence for free.

**`/pendulum/sweep-request`** (Message, phone → watch) and **`/pendulum/sweep/<sessionHex>`** (Channel) — see §2.5.

### 2.4 Nominal loop

1. **Watch.** `ChunkStore` closes chunk *k*, `fsync`, computes the CRC32 of the complete file.
2. `SyncCoordinator` increments its counter; at *k* ≡ 0 mod 3, it publishes the closed unacknowledged chunks via `ChunkPublisher` (`putDataItem`), the last one with `setUrgent()`, and updates `/pendulum/live`.
3. **Phone.** `PendulumListenerService.onDataChanged` receives `/pendulum/chunk/...`. `ChunkIngestor`: checks `size` then the **CRC32 recomputed over the received bytes**, writes the file into the app's storage, `INSERT OR IGNORE` into `chunk` with `UNIQUE(sessionId, idx)`. **Idempotent by construction**: receiving the same chunk twice is a silent no-op.
4. `AckPublisher` recomputes the bitmap from Room (**source of truth = the database, not an in-memory counter**) and rewrites `/pendulum/ack/<session>`.
5. **Watch.** `AckObserver` receives the ack. For each bit set: delete the chunk file **then** the corresponding `DataItem`. For each index in `needResend`: delete then re-put the `DataItem` (the deletion is necessary, an identical re-`put` would be deduplicated and would trigger nothing).

**The file is never deleted before the ack bit. Nor is the `DataItem`.** The invariant holds by itself: the watch's disk is the source of truth as long as the phone's database has not become it.

### 2.5 Catch-up: `ChannelClient`

Triggered when `unackedCount > SWEEP_THRESHOLD (24)`, or at session close, or by the "Sync now" button, or by `TransferWorker` (constraints: charger **or** battery > 30 %, no network constraint).

The watch opens `/pendulum/sweep/<sessionHex>` and writes a framed stream:
```
repeated : [u32 idx][u32 len][len bytes][u32 crc32]
end      : [u32 0xFFFFFFFF]
```
The phone reads, checks, persists chunk by chunk, and updates the ack **at the end of the stream or every 16 frames**. If the channel dies part-way: the complete, verified frames are kept, the partial frame is discarded, and resuming is trivial — the watch re-reads the ack and only resends what is missing. **There is no byte-level resume, and none is needed**: the unit of resume is the 91 KB chunk, whose resend costs 2 s.

### 2.6 Quotas, saturation, phone switched off

**In-flight item ceiling: `MAX_INFLIGHT_ITEMS = 24`** (2 h of night, ~2.2 MB in the data store). Since the `DataItem` store quota is not documented, we refuse to discover it through a crash at 4 a.m. Beyond 24 unacknowledged:

- the watch **stops publishing** new `DataItem`s,
- it **keeps recording to disk, with no degradation whatsoever** (the disk is the source of truth),
- it raises `syncBacklogged` in `/pendulum/live` and in the UI,
- on the first ack received or at the next `TransferWorker`, it switches to **sweep mode** (`ChannelClient`, no ceiling).

**Phone switched off all night**: items accumulate up to 24, the watch goes into backlog, the night is recorded to disk in full. On power-up, GMS reconnects, the 24 items synchronise, the ack arrives, `TransferWorker` starts a sweep that drains the remaining 72 in ~3 min. **No loss.** The only cost is that the "loss bounded at 20 min" guarantee falls back to "loss bounded by what the disk holds", which is exactly the behaviour of the batch option — so never worse.

**Watch disk quota**: `CHUNK_DIR_CAP = 200 MB` (~22 nights).
- Purge at session open: delete the **fully acknowledged** sessions, oldest first, as long as occupancy > 150 MB.
- If after the purge occupancy is still > 190 MB (meaning old unacknowledged data is hanging around): **block the start** in the preflight with an explicit message. Refusing to start a night is better than overwriting one in silence.
- **During recording**, if free space drops below 50 MB: **clean stop** with `stopReason = DISK_FULL`. A short, intact night is better than a long night truncated in the middle of a block.

### 2.7 The "the watch dies at 3 a.m." case

**What is left, by cause:**

| Cause | On the watch disk | On the phone |
|---|---|---|
| Battery empty (detected, ≤ 5 %) | everything, current chunk closed cleanly | **everything** (clean stop → final urgent burst) |
| System kill / OOM | everything except the last partial block | everything except ≤ 3 chunks (≤ 20 min) |
| Reboot | everything except the last partial block | same |
| Abrupt power loss | everything except the last cached block | same |

The append-only, CRC-blocked format of `ChunkCodec.kt` already does the job: `ChunkReader` marks `truncatedTail` and returns all the valid blocks. **An abrupt kill costs at most one block of 512 samples, that is 10.2 s.**

**How the night is marked.** `/pendulum/session/<uuid>` has stayed `OPEN`. On the phone side, `SessionWatchdogWorker` (periodic, 30 min, for as long as a session is `OPEN`) applies:

- `now − lastChunkArrivalMs > 45 min` → `state = STALE` (the watch has stopped talking, it may come back).
- `now > startWallMs + 14 h` **or** local time > 12:00 → `state = TRUNCATED`, `endWallMs = tFirstNs of the last chunk received`, and we **run the analysis on what we have**.
- If chunks arrive afterwards (watch recharged, rebooted): reconciliation, `RescoreWorker` recomputes. The chain is idempotent; the analysis of a truncated night is never an irreversible final state.

**Probable cause.** The `sidecar.json` and `/pendulum/live` carry `batteryPct` per minute. If the last reading is ≤ 8 % → "battery". If a `BootReceiver` later publishes a restart marker → "restart". Otherwise → "unknown interruption". Never invent: displaying "unknown" is information, guessing is a bug.

**What the user sees** on the night card:

```
Night of 12 → 13 July            INTERRUPTED
23:41 → 03:12   ·   3 h 31 recorded       ·   38 chunks / 38 received
Last signal 03:12, battery 4 % at 03:10  →  probable cause: battery
No index published — 3 h 31 of analysable sleep, 4 are needed
```

> **The last line of this card used to say `PLMI 11.2 /h · reliability LOW`. It has been corrected
> here, because it announced a figure that the product refuses to announce.** Below four hours of
> analysable sleep, the publication gate returns `NO_PLMI` and there is **no** index to display —
> over a handful of clustered movements, the index blows up. And the confidence level that
> accompanied it no longer exists: it was removed from the product at the same time as the
> respiratory screening lock, because nothing fed it. A mock-up card that displays a figure more
> confident than the one in the code is exactly the defect that this project writes everywhere that
> it refuses.
>
> Two further reservations about this drawing, left as they are because they mislead no one about a
> figure: the quantity put forward is no longer the hourly count but the fundamental period in
> seconds ([`SPEC-v2.md`](SPEC-v2.md) §5), and "38 chunks" corresponds to 30-minute chunks that no
> longer exist — a night holds about 96 five-minute ones.

And the reminder already recorded in [`SPEC-v1.md`](SPEC-v1.md): **a single night means nothing**, the UI refuses to conclude below 3 nights. A truncated night is therefore not a catastrophe — it is a night that counts for less.

---

## 3. Capture strategy

### 3.1 `health` foreground service — settled

**Decision: foreground service, `android:foregroundServiceType="health"`, qualified by `HIGH_SAMPLING_RATE_SENSORS` declared in the manifest.** No always-on activity.

Four reasons, three of them verified today:

1. **`dataSync` is eliminated mechanically**: timeout of 6 h per 24 h. An 8 h night crosses it at 6 h, `onTimeout()` is called, and a missed `stopSelf()` produces a fatal `RemoteServiceException`. `health` has **no documented timeout**. (verified)
2. **`dataSync` cannot be started from `BOOT_COMPLETED`** on an app targeting Android 15+. `health` is not on the prohibition list → resuming after a reboot (§3.5) is legal. (verified)
3. **Qualification by `HIGH_SAMPLING_RATE_SENSORS`, not by `BODY_SENSORS`.** The documentation explicitly allows both routes, but `BODY_SENSORS` and the `READ_*` sensor permissions are subject to **while-in-use**: the FGS cannot be created from the background without `BODY_SENSORS_BACKGROUND`. That restriction would bite on exactly the reboot-resume path. `HIGH_SAMPLING_RATE_SENSORS` is a normal permission, no runtime prompt, no while-in-use. **Corollary: do NOT declare `BODY_SENSORS`** — declaring it without wanting it means exposing yourself to the restriction for nothing. (verified)
   *Fallback if a device rejects the start anyway*: add `ACTIVITY_RECOGNITION` and request it at runtime. A P0 go/no-go test; one line of logcat is enough to settle it.
4. **An always-on activity is rejected**: it leaves the screen in ambient (1 refresh per minute at best), it lights up the wrist — here the ankle, under the duvet — all night, it is killed by the system at the slightest memory pressure, and it costs the screen on top of the sensor. No advantage: a `health` FGS is *more* protected than an activity.

### 3.2 FIFO / wake-up / wake lock strategy

The HAL contract verified today makes the decision almost deterministic:

> non-wake-up + suspend + FIFO too small → **"the older events are lost"**
> wake-up + suspend → **the HAL must wake the SoC** before exceeding the latency *or* filling the FIFO.

Therefore: **if a wake-up variant of `TYPE_ACCELEROMETER` exists, it is the only defensible choice, and the wake lock question only arises in its absence.**

Correction to `SPEC-v1.md`: it computes `fifo = max(fifoReservedEventCount, fifoMaxEventCount)`. `fifoMaxEventCount` is the **total capacity, shared** between all clients of the sensor (Health Services, Fitbit, the system). Sizing on it means betting that nobody else is listening — a lost bet on a Pixel Watch. **Budget on `fifoReservedEventCount`, which is the only guaranteed share.**

```kotlin
val sensor    = getDefaultSensor(TYPE_ACCELEROMETER, /* wakeUpSensor = */ true)
             ?: getDefaultSensor(TYPE_ACCELEROMETER)
val reserved  = sensor.fifoReservedEventCount          // guaranteed share
val shared    = sensor.fifoMaxEventCount               // information, not a budget

mode = when {
    sensor.isWakeUpSensor && reserved >= 500 ->
        // ~10 s of guaranteed buffer at minimum; the HAL commits to waking before overflow.
        BATCHED_WAKEUP(latencyUs = (0.5 * reserved / 50).s.clamp(10.s, 60.s))

    sensor.isWakeUpSensor ->
        // wake-up but a thin FIFO: the HAL will wake very often. We keep the batching
        // for what it is worth, with no wake lock: the wake-up contract is enough.
        BATCHED_WAKEUP(latencyUs = 10.s)

    reserved >= 3000 ->
        // non-wake-up but 60 s of guaranteed buffer: an acceptable bet, to be validated in P1.
        BATCHED_WAKELOCK(latencyUs = 20.s) + PARTIAL_WAKE_LOCK

    else ->
        // non-wake-up + short FIFO = documented loss in suspend. Wake lock mandatory.
        CONTINUOUS_WAKELOCK(latencyUs = 0) + PARTIAL_WAKE_LOCK
}
```

The 500-event threshold (10 s at 50 Hz) is **an engineering choice, not a sourced value** — I say so because `SPEC-v1.md` already said so and it remains true. The real datum is `reserved` on *this* watch, to be read in P1 and written into the chunk header (`fifoMaxEventCount`, an existing field — **write `reserved` there, not `max`**, or add a second field in the 12 reserved bytes of the 80 B header).

**The wake lock costs ~200 mAh (65 %) over 8 h.** It is the only item that can make the project fail. It is also the one for which §1 said it is absurd to fight over 3 % of radio.

### 3.3 Gap detection and auto-degradation

`GapMonitor` watches two signals:
- **intra-batch**: `Δt` between consecutive samples of the same batched `SensorEvent` > 3 × the nominal period;
- **inter-batch**: `elapsedRealtimeNanos` between two deliveries > `maxReportLatency + 3 s`, or a sample-count deficit over a 60 s sliding window (`received < 0.95 × expected`).

Escalation, **monotone** (never a step back within the same session, otherwise it oscillates):

| Level | Trigger | Action | Trace |
|---|---|---|---|
| 1 | 3 gaps > 3 s in 10 min | take `PARTIAL_WAKE_LOCK` | `MODE_DEGRADED` in `modeFlags` |
| 2 | 3 gaps > 3 s in the following 10 min | `maxReportLatency = 0` (continuous) | new chunk, `modeFlags` updated |
| 3 | same again for 10 min | re-register at **25 Hz** | new chunk, `nominalRateHz = 25` |

A change of level **forces a chunk rotation**: `modeFlags` and `nominalRateHz` are in the header and are never rewritten (append-only format). Each block following a gap carries `FLAG_GAP_BEFORE`.

25 Hz remains largely sufficient: CLMs last 0.5 to 10 s and the decision RMS envelope is computed over **0.50 s**. Nyquist is not the limiting factor, the temporal resolution of the onset is, and 40 ms is enough.

*This sentence used to carry 0.15 s, a value taken over from v1. [`ALGO-v2.md`](ALGO-v2.md) §0-b shows that 0.15 s was not a parameter but a transposition bug — the window comes from S-PLMAD, which deals with EMG at 512 Hz in a 10–300 Hz band. The conclusion about 25 Hz does not move; the reason given for it does.*

### 3.4 Automatic stop

**Six** conditions; the first one to occur wins. Each writes `stopReason` into the sidecar and into `/pendulum/session`. (This paragraph long announced five conditions above a table that holds six, and two other places in the document copied the wrong count.)

| # | Condition | Detail | `stopReason` |
|---|---|---|---|
| 1 | **Charging detected** | `BatteryManager.isCharging` **sustained ≥ 60 s** (60 s to immunise against a false positive from the magnetic charger) | `CHARGING` |
| 2 | **Battery ≤ 5 %** | clean close + final `setUrgent()` burst **before** the system kills anything | `LOW_BATTERY` |
| 3 | **Maximum duration** | `startWallMs + 10 h` | `MAX_DURATION` |
| 4 | **Cut-off time** | local time ≥ `stopAtLocalTime` (default **10:00**) | `TIME_LIMIT` |
| 5 | **Waking detected** | > 80 % of the 30 s epochs above the locomotion threshold over a sliding 10 min | `WAKE_DETECTED` |
| 6 | **Disk** | free space < 50 MB | `DISK_FULL` |

**Condition 2 is the most profitable in the document.** It turns the "the watch dies at 3 a.m." scenario into "the watch closes cleanly at 3 a.m. and sends everything" — the cost is a thirty-line `BatteryLogger`.

**Explicit correction to `SPEC-v1.md`: off-body must NEVER stop the recording.** Off-body detection relies on the PPG/capacitive sensor at the wrist; on the ankle, its behaviour is unknown and probably "not worn" permanently. A stop on off-body would cut every night off in its first minute. `TYPE_LOW_LATENCY_OFFBODY_DETECT` is **logged** (sidecar + `FLAG_OFF_BODY` on the blocks) and **never acted upon**. The "watch taken off" detector is condition 5, which measures locomotion — that is, the event we actually want to detect ("the person got up"), not an unvalidated proxy.

### 3.5 Resuming after a reboot / update

`active_session.json` (atomic write: `tmp` + `rename`) contains `{uuid, startWallMs, plannedStopWallMs, lastChunkIndex, modeFlags, nominalRateHz}`.

`BootReceiver` on `BOOT_COMPLETED` + `ACTION_MY_PACKAGE_REPLACED`. **Resume only if**:
```
marker present
&& now < startWallMs + 14 h
&& now < plannedStopWallMs        ← added
&& local time < stopAtLocalTime   ← added
&& !isCharging                    ← added
```
Otherwise: **do not resume**, but **finalise** — move `/pendulum/session` to `CLOSED` with `stopReason = CRASH`, and let `TransferWorker` push the remainder. `SPEC-v1.md` only tested the 14 h window, which restarts a recording at 8 in the morning on the charger after a night-time reboot, and pollutes the night in exactly the way trap no. 11, which it describes elsewhere, warns about.

The first chunk after a resume carries `FLAG_GAP_BEFORE` on its first block and a `chunkIndex` that **continues the numbering** (read from the marker), never reset: the `(sessionId, idx)` uniqueness on the phone side depends on it.

**Blind spot to measure: credential encryption.** If the watch has a lock code, `BOOT_COMPLETED` is only broadcast after unlocking, and credential-encrypted storage is inaccessible before that. A watch that reboots at 3 a.m. **on the wrist** stays locked until morning → no resume at all. Do not go `directBootAware` (it would force moving the chunks into device-encrypted storage, more exposed and more complex for an uncertain gain). **To measure in P2: how much time actually elapses between a night-time reboot and the resume.** If the verdict is "never", condition 2 (clean stop on low battery) becomes even more critical.

### 3.6 `wear` manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />

    <!-- High-rate sensor: ALSO qualifies the health-type FGS,
         with no runtime prompt and no while-in-use restriction. -->
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Fallback only if starting the health FGS is refused on the device.
         <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" /> -->

    <!-- Do NOT declare BODY_SENSORS: subject to while-in-use, it would break
         the reboot resume without bringing anything (we read no body sensor). -->

    <application android:allowBackup="false">

        <service
            android:name=".record.RecordingService"
            android:exported="false"
            android:foregroundServiceType="health"
            android:stopWithTask="false" />

        <service
            android:name=".transfer.AckObserver"
            android:exported="true"
            android:permission="com.google.android.gms.permission.BIND_WEARABLE_LISTENER">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data android:scheme="wear" android:host="*"
                      android:pathPrefix="/pendulum/ack" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*"
                      android:pathPrefix="/pendulum/sweep-request" />
            </intent-filter>
        </service>

        <receiver android:name=".record.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

### 3.7 Full lifecycle of `RecordingService`

```
IDLE
 │  ACTION_START (button) │ ACTION_RESUME (BootReceiver)
 ▼
PREFLIGHT ─── blocking ──▶ IDLE + error screen (§5.3)
 │  ok / warnings accepted
 ▼
STARTING
 │  startForeground(id, notif, FOREGROUND_SERVICE_TYPE_HEALTH)   ← BEFORE everything else
 │  SensorStrategy.decide(sensor) → mode
 │  SessionStore.begin(uuid, …) → active_session.json (fsync)
 │  ChunkPublisher.putSession(state = OPEN, urgent)
 │  registerListener(pipeline, sensor, 20 000 µs, latencyUs)
 │  if mode.needsWakeLock: acquire(PARTIAL_WAKE_LOCK, "pendulum:rec")   ← no timeout
 ▼
RECORDING ◀────────────────────────────────────────────┐
 │  onSensorChanged  → SensorPipeline → blocks of ≤ 512 → ChunkWriter
 │  tick 10 s        → fd.sync()
 │  rotation 300 s / 92 160 B → ChunkStore.close() + SyncCoordinator.onChunkClosed()
 │  tick 60 s        → BatteryLogger, WakeDetector, disk quota
 │  tick 15 min      → SyncCoordinator.push()  (burst + /pendulum/live)
 │  GapMonitor level 1/2/3 ──────── auto-degradation ───┘
 │
 │  stop (user | §3.4 conditions 1-6 | defensive onTimeout())
 ▼
FINALIZING
 │  unregisterListener ; last block written ; ChunkStore.close() ; fsync
 │  final sidecar.json ; ChunkPublisher.putSession(CLOSED, totalChunks, stopReason, urgent)
 │  SyncCoordinator.push(force = true)   ← all remaining chunks, urgent
 │  releaseWakeLock ; SessionStore.clearActive()
 │  WorkManager.enqueueUniqueWork("pendulum-transfer", TransferWorker)
 ▼
stopForeground(STOP_FOREGROUND_REMOVE) ; stopSelf()  →  IDLE
```

Non-negotiable details:

- `onStartCommand` returns **`START_STICKY`**; `onTaskRemoved` **does nothing** (the service survives the task being swiped away, hence `stopWithTask="false"`).
- **`startForeground()` is called in the first useful instruction of `onStartCommand`**, before any I/O — five seconds of delay and the system raises `ForegroundServiceDidNotStartInTimeException`.
- **`onTimeout(startId, fgsType)` is implemented** even though `health` is not subject to it: if a future version made it subject, the default behaviour would be a crash 6 h into the night. The implementation closes cleanly (full `FINALIZING` path), calls `stopSelf()`, and schedules an `AlarmManager.setExactAndAllowWhileIdle` at +30 s to start a new session (new `sessionUuid`, marked `RESTARTED_AFTER_TIMEOUT`). **Cost if useless: zero. Cost if necessary and absent: half of every night.**
- The wake lock is acquired **without a timeout** — outside the Play Store there is no constraint, and a timeout that expires at 4 a.m. is a silent bug.
- The notification uses an `IMPORTANCE_LOW` channel, no sound, no vibration, `setOngoing(true)`, `setSilent(true)`. An `OngoingActivity` (`androidx.wear:wear-ongoing`) is optional and cosmetic.

---

## 4. Wear OS traps

### 4.1 Documented (verified today, sources in §0)

1. **Android 15 FGS timeout** — `dataSync`/`mediaProcessing` only, 6 h/24 h. Fatal for an 8 h night. `health` exempt. Consequence: the service type is not a manifest detail, it is the choice that decides whether the app works.
2. **`BOOT_COMPLETED` cannot start a `dataSync` FGS** on Android 15+. `health` can.
3. **`BODY_SENSORS` and the `READ_*` permissions are while-in-use** → a `health` FGS qualified by them does not start from the background. Hence the qualification by `HIGH_SAMPLING_RATE_SENSORS`.
4. **`DataItem` ≤ 100 KB.** The constraint that sizes the 5 min chunk.
5. **Without `setUrgent()`, up to 30 min of sync delay.** An architecture that assumes immediate delivery without `setUrgent()` has a 30 min bug.
6. **The Data Layer buffers offline and synchronises on reconnection.** This is the guarantee on which all the resilience of §2 rests.
7. **FIFO in suspend**: non-wake-up → "the older events are lost" when the FIFO overflows; wake-up → the HAL **must** wake the SoC before overflow. `SPEC-v1.md` classed this point as "low" confidence; it is in fact **normative at the HAL level**, which raises confidence in *the contract*. What remains unknown is the **value** of `fifoReservedEventCount` and whether the OEM actually honours the contract.
8. **The Data Layer is inoperative if the watch is paired to an iPhone.** Not applicable here, but never to be forgotten.

### 4.2 Documented elsewhere, not re-verified this session

9. **Ambient mode**: an always-on activity is limited to ~1 refresh per minute and the screen stays lit at low brightness. Not applicable here since we do no always-on — and it is one of the reasons not to.
10. **Doze / App Standby on the phone side**: `SPEC-v1.md` already notes that `requiresCharging` Workers may never run. Here, `TransferWorker` **does not require charging** (charger **or** battery > 30 %), and ingestion on the phone side does not need a Worker: it is GMS that starts `WearableListenerService` on delivery. **Medium confidence** that this delivery pierces the phone's doze. Safety net: unprocessed `DataItem`s **persist** in the store, and are reprocessed the next time the listener starts. The worst case is a delay, never a loss.
11. **`fifoReservedEventCount` vs `fifoMaxEventCount`**: shared vs guaranteed (§3.2).

### 4.3 Folklore — to be treated as caution rules, not as facts

12. **`ExerciseClient` would override the sampling rate to 100 Hz.** Reported, **not verified**. The rule "never start an `ExerciseClient`, never depend on Health Services for the accelerometer" is kept because it costs nothing (Health Services exposes no raw accelerometer anyway). **Corollary to test in P1: what if it is *another* app — Fitbit, a workout tracker — that starts an `ExerciseClient` during the night?** Our measured `fs` would change mid-night. `SensorPipeline` must therefore **measure `fs` continuously over a 60 s window** and raise a flag if the deviation from nominal exceeds 5 %, rather than trusting the requested rate. This guard rail is useful whether the folklore is true or not.
13. **Data Layer throughput 20–100 KB/s**: no source. The P3 criterion in `SPEC-v1.md` ("8.8 MB in < 5 min") corresponds to 30 KB/s, which is in the middle of the folkloric range — so neither guaranteed nor unreasonable. **To be measured before depending on it.**
14. **`DataItem` store quota**: unknown. Handled by the ceiling of 24 in-flight items.
15. **Off-body on the ankle**: unknown, never acted upon (§3.4).
16. **Wear OS Bedtime mode / Battery Saver**: unknown effect on a `health` FGS. **Explicit P1 test, Bedtime mode ON**, because that is the real configuration. Testing without it means validating a configuration the user will never use.

---

## 5. Watch UI

Principle: **the watch screen is an instrument for checking at bedtime and for taking stock on waking, nothing else.** All the analysis is on the phone. Every pixel that moves is a SoC wake-up.

### 5.1 Screens

**One only.** No navigation, no `NavHost`, no tile, no complication.

| State | Content | Action |
|---|---|---|
| `PREFLIGHT_BLOCKED` | One red line per blocker, phrased as an action | corrective button |
| `IDLE` | "Ready" · battery % · free space · "N nights waiting to sync" if there is a remainder | full-screen **START** |
| `RECORDING` | duration `3 h 12` · `MB` written · battery % · `gaps: 0` · mode (`WAKEUP 30 s`) · sync `38/38` | **STOP** (confirmation) |
| `FINALIZING` | "Closing…" | — |
| `SYNCING` | `72/96 sent` | — |
| `DONE` | "8 h 02 · 8.7 MB · 96/96 synchronised" | **START** |

### 5.2 What is forbidden

- Any animation: `rememberInfiniteTransition`, `AnimatedVisibility`, `animateFloatAsState`, circular progress indicators, colour transitions.
- Any `LaunchedEffect` at a rate < 30 s. **A single source: a `StateFlow<RecordUiState>` emitted every 30 s** by the service, collected with `collectAsStateWithLifecycle()`.
- Any display of a chart, of history, of PLMI, of a curve. On the watch, a chart is computation and recompositions for zero actionable information.
- Any vibration, any sound, any `setOngoing` with `IMPORTANCE_DEFAULT`.
- `keepScreenOn`, always-on, `setAmbientEnabled()`.
- Any setting beyond two: `stopAtLocalTime` and `PUSH_EVERY_N_CHUNKS` (developer setting, hidden).

The criterion, phrased so it can be tested: **between going to bed and waking, the UI layer must cause no recomposition** — the screen is off, `collectAsStateWithLifecycle` is stopped, and the service emits into the void. Verifiable with the `Layout Inspector` / recomposition counter in P1.

### 5.3 Errors visible at bedtime

This is the only moment when the user looks. The preflight runs **before** `STARTING` and separates blockers from warnings.

**Blockers — START is disabled:**

| Cause | Message | Action |
|---|---|---|
| `POST_NOTIFICATIONS` denied | "Allow notifications, otherwise the recording will stop on its own" | opens the settings |
| FGS start refused (`SecurityException`) | "Physical activity permission required" | requests `ACTIVITY_RECOGNITION` |
| Space < 300 MB after the purge | "Storage full — sync first (N nights waiting)" | forces a sweep |
| No `TYPE_ACCELEROMETER` | "Accelerometer unavailable" | — |

**Warnings — START stays possible, one amber line:**

| Cause | Message |
|---|---|
| Battery < 40 % | "Battery 32 % — 50 % recommended for 8 h" |
| Phone unreachable (`CapabilityClient`) | "Phone unreachable — recording continues, the sync will happen later" |
| No wake-up sensor | "Wake lock mode — reduced battery life" |
| Previous session not synchronised | "Night of 12/07: 24 chunks waiting" |

**An unreachable phone is never blocking.** The entire architecture of §2 exists so that this case has no consequence; reporting it as an error would be lying to the user and would discourage them from recording.

**After START**: a static screen "Recording started · WAKEUP 30 s mode" for 3 s, then the screen goes off. Checking that all is well is then done with a glance at the persistent notification chip — not by opening the app again.

---

## 6. Kotlin classes

### 6.1 `format` (pure JVM, testable, shared) — *to be added to what exists*

| Class | Responsibility |
|---|---|
| `ChunkMeta` | Metadata of a chunk on the wire: `sessionUuid, idx, size, crc32, tFirstNs, tLastNs, sampleCount, flagsOr`. |
| `ChunkSummary` | Scans a chunk file and derives a `ChunkMeta` from it without decoding the samples. |
| `AckBitmap` | Encodes/decodes the acknowledgement bitmap (`bitmapBase` + `ByteArray`), with `ackedUpTo` and iteration over the missing indices. |
| `SessionState` | Enum + serialisation of the session state (`OPEN`, `CLOSED`, `STALE`, `TRUNCATED`) and of `stopReason`. |
| `SweepFraming` | Writing/reading the framed stream `[idx][len][bytes][crc32]` of the `ChannelClient` sweep. |
| `PreviewEnvelopeCodec` | Logarithmic u8 quantisation of the 1 Hz envelope and its inverse. |

### 6.2 `wear`

| Class | Responsibility |
|---|---|
| `SensorStrategy` | **Pure**: `(isWakeUp, fifoReserved, fifoMax, rateHz) → AcquisitionMode`. No Android dependency, entirely testable on the JVM. |
| `SensorPipeline` | Registers the listener, converts `SensorEvent` batches into blocks of ≤ 512, measures the real `fs` over a 60 s window, feeds `GapMonitor` and `PreviewEnvelope`. |
| `GapMonitor` | **Pure**: accumulates timing statistics and decides the degradation level (0 → 3). |
| `ChunkStore` | Naming, rotation (300 s / 92 160 B), `fsync` policy, listing, deletion, quota purge. |
| `SessionStore` | Atomic writing of `active_session.json` and `sidecar.json`. |
| `PreviewEnvelope` | Streaming high-pass + RMS, decimates to 1 Hz, produces the 900-byte window. Reuses `:algo` (pure JVM). |
| `BatteryLogger` | Battery reading every 60 s into the sidecar; triggers the clean stop at ≤ 5 %. |
| `OffBodyLogger` | Listens to `TYPE_LOW_LATENCY_OFFBODY_DETECT`, logs, sets `FLAG_OFF_BODY`. **Never stops anything.** |
| `WakeDetector` | **Pure**: detects sustained locomotion (30 s epochs over 10 min) → automatic stop. |
| `RecordingService` | `health` FGS: the state machine of §3.7, wake lock, notification, 10 s / 60 s / 15 min ticks. |
| `SyncCoordinator` | Decides **what** to push and **when**: rate `N`, ceiling of 24 in-flight items, switch to sweep mode. |
| `ChunkPublisher` | Performs the `putDataItem` / `deleteDataItems`: chunk, session, live. |
| `AckObserver` | `WearableListenerService`: `/pendulum/ack` → deletes files and items, handles `needResend`; `/pendulum/sweep-request` → starts the sweep. |
| `SweepSender` | Opens the `ChannelClient` and writes the `SweepFraming` stream of the unacknowledged chunks. |
| `TransferWorker` | `WorkManager`: catch-up outside the service (morning, reboot, backlog), constraint battery > 30 % or charger. |
| `BootReceiver` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → resume **or** finalise, according to the five resume conditions of §3.5 — not to be confused with the **six** stop conditions of §3.4. |
| `PreflightChecker` | **Pure** (injected inputs): produces `List<Blocker>` + `List<Warning>`. |
| `RecordViewModel` / `RecordScreen` / `MainActivity` | Single UI, `StateFlow` at 30 s, zero animation. |

### 6.3 `phone` (transfer part only)

| Class | Responsibility |
|---|---|
| `PendulumListenerService` | `WearableListenerService`: `onDataChanged` (chunk, session, live), `onChannelOpened` (sweep). |
| `ChunkIngestor` | Checks size + CRC32, writes the file, `INSERT OR IGNORE` on `(sessionId, idx)`. Idempotent. |
| `AckPublisher` | Recomputes the bitmap **from Room** and rewrites `/pendulum/ack/<session>`. |
| `SessionMonitor` | The `OPEN → STALE → TRUNCATED/CLOSED` state machine and the reconciliation of late chunks. |
| `SessionWatchdogWorker` | Periodic, 30 min, for as long as a session is `OPEN`; applies the rules of §2.7. |
| `SweepReceiver` | Reads the `SweepFraming` stream, persists frame by frame, updates the ack every 16 frames. |
| `LiveStatusRepository` | Exposes the latest `/pendulum/live` (preview envelope + counters) to the phone UI. |

### 6.4 Implementation order

The order is constrained by the fact that **P1 is a go/no-go phase**: what is needed is the absolute minimum that allows battery life and completeness to be measured, and nothing more.

**Wave 1 — the P1 spike (nothing else counts until it has passed)**
1. `SensorStrategy` (+ JVM tests on the four branches)
2. `ChunkStore` (rotation, fsync; reuses the existing `ChunkWriter`)
3. `SensorPipeline` (blocks, measured `fs`)
4. `RecordingService` (`health` FGS, wake lock, START/STOP, ticks) — **validate here the start of the FGS qualified by `HIGH_SAMPLING_RATE_SENSORS`**
5. Minimal `RecordScreen` (START/STOP + 5 counters)
6. `BatteryLogger`, `OffBodyLogger`
7. → **3 nights of measurement, Bedtime mode on.** Criteria from `SPEC-v1.md` P1.

**Wave 2 — local robustness (P2)**
8. `GapMonitor` + escalation
9. `SessionStore` + `BootReceiver` (with the five resume conditions of §3.5)
10. `WakeDetector` + the other stop conditions
11. Quota purge

**Wave 3 — the transfer (P3)**
12. `format`: `ChunkMeta`, `ChunkSummary`, `AckBitmap`, `SessionState` (+ JVM tests)
13. `phone`: `PendulumListenerService`, `ChunkIngestor`, `AckPublisher` — **the receiver first**, one does not test a sender without a receiver
14. `wear`: `ChunkPublisher`, `SyncCoordinator`, `AckObserver` → **complete nominal loop, testable by cutting Bluetooth in the middle**
15. `SessionMonitor` + `SessionWatchdogWorker` → the "watch dead at 3 a.m." scenario (test: `adb shell am force-stop` in the middle of the night)
16. `SweepFraming`, `SweepSender`, `SweepReceiver`, `TransferWorker` → the "phone switched off all night" scenario
17. `PreviewEnvelope`, `PreviewEnvelopeCodec`, `LiveStatusRepository` — **last**: it is comfort, not resilience

**Wave 4 — finishing**
18. `PreflightChecker` + the UI error states

Justification of the transfer order: **the acknowledgement loop (13-14) before the sweep (16)**, because the sweep needs the acknowledgement bitmap to know what to send. And `PreviewEnvelope` last because it is the only feature in the document whose absence loses no data.

---

## 7. Check this yourself

- **My milliamperes are estimates, not measurements.** The ratio between the options is solid, the absolute values are not. The only measurement that settles it is P1: record one night *with no transfer at all*, then one night *with the 15 min sync*, and compare the two remaining-battery percentages. If the gap exceeds 5 points, my model is wrong and we have to go back to batch.
- **`fifoReservedEventCount` on your watch**: read it at runtime before any other decision. If it is 0 and `isWakeUpSensor` is false, the permanent wake lock is mandatory and the project's battery budget is probably dead — that is the real go/no-go, not the 3 % of radio.
- **That `HIGH_SAMPLING_RATE_SENSORS` is enough to qualify the `health` FGS on this watch.** The documentation allows it, the implementation may diverge. A `startForeground()` that raises `SecurityException` on the first attempt is worth all the reasoning; it is a ten-minute test in P0.
- **The 100 KB ceiling of a `DataItem` with 91 KB of payload**: check that a `putDataItem` of that size actually goes through, and measure the end-to-end time. If it fails, switch to `Asset` — a single point of code.
- **The behaviour of Wear OS Bedtime mode** on a `health` FGS: it is the real usage configuration and nobody has documented it. Test with it, never without.
- **How long a locked watch takes to resume after a night-time reboot** (§3.5). If the answer is "never before morning", the clean stop on low battery becomes the main protection against losing a night, and it must be tested as a priority.
- **The claim about `ExerciseClient` is folklore that I could not verify.** The caution rule remains good, but do not cite it as a fact, and implement the continuous measurement of `fs` — it protects you whatever the truth is.
