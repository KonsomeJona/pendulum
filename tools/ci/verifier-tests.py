#!/usr/bin/env python3
"""Decides whether a test run is a success, from Gradle's XML reports.

**Why this file exists.** This check used to live in three copies: two in `ci.yml` (the JVM job and
the Android job) and one in `release.yml`. They did the same thing and had already diverged on the
details that matter — the glob, the format of a failing test's name, the error message. Three copies
of a guard means three occasions to fix the guard in a single place; and it is precisely the absence
of the guard from the `release.yml` copy that had let a release ship without its tests having run.

**What the guard protects.** Gradle's exit code is not enough: a CI step that neutralises it
(`|| true`, `continue-on-error`) in order to go and read the reports lets a **compilation** error
through — in that case Gradle writes no report at all, the failure list comes out empty, and the
absence of a result reads as a success. Hence the non-negotiable rule below: **zero reports is a
failure**, never a silent success.

Usage:
    verifier-tests.py [glob ...]

With no argument, the default glob covers **every** test task variant (`test`,
`testDebugUnitTest`, `testReleaseUnitTest`, flavour variants and so on). A glob naming a single
variant is exactly what left more than two hundred tests out of CI without anything saying so. An
explicit glob is only passed in order to **restrict** the scope of a job that is known to produce
only part of the reports.
"""

import glob
import sys
import xml.etree.ElementTree as ET

DEFAULT_GLOB = "*/build/test-results/*/*.xml"


def main(argv: list[str]) -> int:
    patterns = argv[1:] or [DEFAULT_GLOB]

    paths = sorted({p for pattern in patterns for p in glob.glob(pattern)})

    total = 0
    failures: list[str] = []
    for path in paths:
        root = ET.parse(path).getroot()
        total += int(root.get("tests", 0))
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(f"{case.get('classname')}.{case.get('name')}")

    # The guard. Zero reports does not mean zero failures: it means nothing ran, and that is the
    # case where carrying on would be the most serious — it is the case of an untested release.
    if total == 0:
        print(f"No test report for {' '.join(patterns)} —", file=sys.stderr)
        print(
            "compilation failed before reaching the tests, or the scope is empty.",
            file=sys.stderr,
        )
        return 1

    print(f"{total} tests, {len(failures)} failure(s), {len(paths)} report(s)")
    for f in failures:
        print(f"  {f}")

    # No test is tolerated red. The exception named `T6` that once lived here no longer exists,
    # since its denominator was redefined (docs/07-validation.md §4.1); do not reintroduce it under
    # another form.
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
