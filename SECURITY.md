# Security and privacy

## Reporting something

Open a [private security advisory](https://github.com/KonsomeJona/pendulum/security/advisories/new)
rather than a public issue.

This is a personal project with one maintainer and no service behind it, so there is no response-time
commitment and no bounty. What there is: the report will be read, and if it is right it will be fixed
and credited.

## What is worth reporting

The application handles sleep data, medication context and questionnaire answers. All of it stays on
the device. So the interesting attack surface is not a server — there isn't one — but everything that
could move data off the device, or let another application on the same device read it.

Particularly worth reporting:

- **Any path that could send data off the device.** The phone application declares no `INTERNET`
  permission, so it cannot open a socket at all — the system enforces this, and you can check it on
  the installed package with `aapt dump permissions`. If you find a way around that, through a
  content provider, an implicit intent, a shared file, or a transitive dependency that pulls the
  permission back in through manifest merging, that is the most serious class of bug this project
  can have.
- **Anything readable by another application**: exported components, world-readable files, a
  `FileProvider` scoped too widely, data surviving in a backup. `allowBackup` is off and both
  extraction rule files are set, but the device-to-device transfer channel is separate from backup
  and worth checking.
- **The Data Layer surface.** `PendulumListenerService` is exported by necessity — Google Play
  services starts it — and guarded by `BIND_WEARABLE_LISTENER` plus a path prefix filter. If a
  malicious application can reach it, say so.
- **Anything in a release artifact that should not be there**: keys, tokens, paths from a build
  machine, or a debug-signed artifact published as a release.

## What is not a vulnerability here

- **That the estimate can be wrong.** It can, and the ways it can are documented at length in
  [`docs/07-validation.md`](docs/07-validation.md), including the measurements that still fall
  short of what the product needs (§4.2 and §4.3). Wrong numbers are a correctness problem — open a
  normal issue.
- **That sideloading is unsafe.** It is a deliberate choice, and its consequences are in
  [`fastlane/README.md`](fastlane/README.md).
- **That releases are signed with a self-managed key.** They are, and losing it would be a real
  problem, but that is a continuity risk rather than a vulnerability.

## Dependencies

The project deliberately depends on very little: AndroidX, Room, Health Connect, Play services
wearable. The two pure-JVM modules — the chunk codec and the whole signal-processing chain — depend
on nothing beyond the Kotlin standard library, which is why they can be audited by reading them.
