#!/usr/bin/env python3
"""Decides whether a test run is a success, from Gradle's XML reports.

**Why this file exists.** This check used to live in three copies: two in `ci.yml` (the JVM job and
the Android job) and one in `release.yml`. They did the same thing and had already diverged on the
details that matter — the glob, the format of a failing test's name, the error message. Three copies
of a guard means three occasions to fix the guard in a single place; and it is precisely the absence
of the guard from the `release.yml` copy that had let a release ship without its tests having run.

**What the guard protects.** Gradle's exit code is not enough: a CI step that neutralises it
(`|| true`, `continue-on-error`) in order to go and read the reports lets a **compilation** error
through — in that case Gradle writes no report **for that task**, the failure list comes out empty,
and the absence of a result reads as a success. Hence the non-negotiable rule below: **a task that
executed no test is a failure**, never a silent success.

Usage:
    verifier-tests.py [glob ...]

Pass **one glob per test task the job launched**, and every glob must yield at least one executed
test. This used to be a single broad glob (`*/build/test-results/*/*.xml`) with a guard on the
grand total, and the guard only fired when *every* module had failed: the jobs run several tasks
under `--continue`, so a compilation error in `:wear` still let `:phone` write its three hundred
reports, the total stayed comfortably non-zero, no test was red, and the module that never ran was
invisible. The worst instance was the release-variant step, whose only purpose is to prove the
time divisor is 1 in the shipped build — its report missing, the debug reports of the other module
made the count for it, and the signed artefacts went out with nothing proving that any more.

"Executed" and not merely "reported": a `@Disabled` test still appears in the XML, so a report
whose every test was skipped counts for nothing. The skipped count is also printed, because a
disabled guard rail hiding inside "N tests" is the same silence one size smaller.

With no argument, the default glob covers every task variant at once (`test`, `testDebugUnitTest`,
`testReleaseUnitTest`, flavour variants and so on). It exists for a manual run on a workstation,
where it says "something ran"; it is **not** what a job should pass, since a single glob can only
guard the sum.
"""

import glob
import sys
import xml.etree.ElementTree as ET

DEFAULT_GLOB = "*/build/test-results/*/*.xml"


def main(argv: list[str]) -> int:
    patterns = argv[1:] or [DEFAULT_GLOB]

    # Each pattern keeps its own count. Merging the matches into one set first — which is what the
    # previous version did — is exactly what made a task with no report indistinguishable from a
    # task whose report was counted under another pattern.
    matched: dict[str, list[str]] = {p: sorted(glob.glob(p)) for p in patterns}

    executed: dict[str, int] = {}
    total = 0
    skipped = 0
    failures: list[str] = []
    for path in sorted({path for found in matched.values() for path in found}):
        root = ET.parse(path).getroot()
        ran = 0
        for case in root.iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
                continue
            ran += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(f"{case.get('classname')}.{case.get('name')}")
        executed[path] = ran

    # The guard, per task. No executed test behind a pattern does not mean zero failures: it means
    # nothing ran there, and that is the case where carrying on would be the most serious — it is
    # the case of an untested release.
    empty = [p for p, found in matched.items() if sum(executed[path] for path in found) == 0]
    if empty:
        print("No executed test for:", file=sys.stderr)
        for p in empty:
            print(f"  {p}", file=sys.stderr)
        print(
            "compilation failed before reaching them, their test filter matched nothing, "
            "or every test they hold is disabled.",
            file=sys.stderr,
        )
        return 1

    print(f"{total} tests, {skipped} skipped, {len(failures)} failure(s), {len(executed)} report(s)")
    for f in failures:
        print(f"  {f}")

    # No test is tolerated red. The exception named `T6` that once lived here no longer exists,
    # since its denominator was redefined (docs/07-validation.md §4.1); do not reintroduce it under
    # another form.
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
