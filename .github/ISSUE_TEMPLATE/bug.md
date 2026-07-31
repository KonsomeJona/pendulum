---
name: A defect
about: Something behaves differently from what the documentation says
title: ''
labels: bug
---

**What happened, and what the documentation says should happen.** A link to the paragraph helps —
the documentation is detailed enough that most behaviour is written down somewhere, and a mismatch
between the two is itself worth knowing about.

**How to reproduce.**

**Setup** — phone and watch models, Android versions, which sleep source writes to Health Connect,
and whether both applications came from the same release. That last one matters more than it looks:
the watch and the phone only exchange data when they share an application id *and* a signature, so a
release phone build paired with a debug watch build produces two applications that never see each
other, silently.

**Logs**, if you have them: `adb logcat -d | grep -i pendulum`.
