#!/usr/bin/env python3
"""Tests for `verifier-tests.py`, the script that decides whether CI and a release are green.

A guard rail with no test of its own is the one whose regression nobody sees: the workflows read
its exit code and nothing else, so a version that says "yes" too easily looks exactly like a
healthy one. The script is exercised as the workflows use it — through a subprocess, on a fake
`*/build/test-results/<task>/*.xml` tree — because its contract **is** its exit code and its
stderr, not its Python functions.

Run with `python3 tools/ci/test_verifier_tests.py`; nothing beyond the standard library is needed,
which is also why it is `unittest` and not pytest — the CI runner has no pip step.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().with_name("verifier-tests.py")


def write_report(root: pathlib.Path, module: str, task: str, name: str, cases: list[str]) -> None:
    """Writes a Gradle-shaped JUnit XML report. `cases` holds one marker per test case:
    `"ok"`, `"failed"`, or `"skipped"` (the shape JUnit 5 gives a `@Disabled` test)."""
    body = []
    for i, state in enumerate(cases):
        inner = {"ok": "", "failed": "<failure message=\"boom\"/>", "skipped": "<skipped/>"}[state]
        body.append(f'<testcase name="case {i}()" classname="{name}" time="0.0">{inner}</testcase>')
    xml = (
        f'<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{name}" tests="{len(cases)}" '
        f'skipped="{cases.count("skipped")}" failures="{cases.count("failed")}" errors="0">\n'
        + "\n".join(body)
        + "\n</testsuite>\n"
    )
    directory = root / module / "build" / "test-results" / task
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"TEST-{name}.xml").write_text(xml, encoding="utf-8")


def run(root: pathlib.Path, *patterns: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *patterns],
        cwd=root, capture_output=True, text=True, check=False,
    )


class VerifierTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_a_module_whose_tests_did_not_compile_is_refused_even_when_another_module_reported(self):
        # The case that used to pass: `--continue` lets `:phone` write its reports after
        # `:wear:compileDebugUnitTestKotlin` failed, the total is a few hundred, no test is red.
        write_report(self.root, "phone", "testDebugUnitTest", "com.pendulum.phone.A", ["ok"] * 300)
        result = run(
            self.root,
            "wear/build/test-results/testDebugUnitTest/*.xml",
            "phone/build/test-results/testDebugUnitTest/*.xml",
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("wear/build/test-results/testDebugUnitTest/*.xml", result.stderr)
        self.assertNotIn("phone/build/test-results/testDebugUnitTest/*.xml", result.stderr)

    def test_a_guard_rail_that_only_ran_disabled_tests_is_refused(self):
        # `@Disabled` on the one test that checks the release divisor: Gradle writes a report,
        # the count is non-zero, nothing is red — and the guard rail proved nothing.
        write_report(self.root, "wear", "testReleaseUnitTest", "ReleaseScaleTest", ["skipped"])
        write_report(self.root, "phone", "testDebugUnitTest", "com.pendulum.phone.A", ["ok"] * 5)
        result = run(
            self.root,
            "wear/build/test-results/testReleaseUnitTest/*.xml",
            "phone/build/test-results/testDebugUnitTest/*.xml",
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("wear/build/test-results/testReleaseUnitTest/*.xml", result.stderr)

    def test_every_task_that_ran_at_least_one_test_with_none_red_is_a_success(self):
        write_report(self.root, "wear", "testDebugUnitTest", "com.pendulum.wear.A", ["ok", "skipped"])
        write_report(self.root, "phone", "testDebugUnitTest", "com.pendulum.phone.A", ["ok"] * 3)
        result = run(
            self.root,
            "wear/build/test-results/testDebugUnitTest/*.xml",
            "phone/build/test-results/testDebugUnitTest/*.xml",
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # The skipped test is counted out loud: a disabled test that hides inside "N tests" is
        # the same silence as a module that never ran, one size smaller.
        self.assertIn("5 tests, 1 skipped, 0 failure(s), 2 report(s)", result.stdout)

    def test_a_red_test_fails_the_run_and_is_named(self):
        write_report(self.root, "algo", "test", "com.pendulum.algo.T1", ["ok", "failed"])
        result = run(self.root, "algo/build/test-results/test/*.xml")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("com.pendulum.algo.T1.case 1()", result.stdout)

    def test_no_report_anywhere_is_still_refused(self):
        result = run(self.root)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
