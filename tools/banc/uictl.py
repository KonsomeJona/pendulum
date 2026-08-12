#!/usr/bin/env python3
"""Screen steering by dump-and-tap, for the instrumented bench.

Why this script rather than an `input tap` at hard-coded coordinates: the Health Connect consent
window and the pairing wizard change their layout depending on the module version and the screen
density. A blind tap that misses produces **no error at all** — it produces a failure further on,
for an unrelated reason. The dump makes the gesture deterministic and checkable: one can re-dump
after the tap and confirm that the screen changed.

The sub-command names are in French and stay that way: they are quoted word for word in
`docs/workings/BENCH-LOG.md`, a dated log whose value is being an exact trace of what was typed.

Usage:
    uictl.py <serial> dump                     # writes the tree on standard output
    uictl.py <serial> find  <pattern>          # returns elements whose text/id contains the pattern
    uictl.py <serial> tap   <pattern>          # taps at the centre of the first element found
    uictl.py <serial> presse <pattern> [ms]    # long press (default 900 ms)
    uictl.py <serial> wait  <pattern> [timeout] # waits for an element to appear (default 30 s)
    uictl.py <serial> shot  <file.png>         # screenshot
    uictl.py <serial> cocher [n]               # ticks the first n unticked boxes

The pattern is searched, case-insensitively, in the `text`, `content-desc` and `resource-id`
attributes.

Exit code 0 if found, 1 otherwise. Every command emits a marker on standard output (`UICTL_OK` /
`UICTL_FAIL`): ssh over Tailscale does not propagate exit codes, so the remote caller must read the
marker and not `$?`.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(
    os.path.expanduser("~"), "Library/Android/sdk/platform-tools/adb"
)
if not os.path.exists(ADB):
    ADB = "adb"

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial, *args, binary=False):
    cmd = [ADB, "-s", serial] + list(args)
    out = subprocess.run(cmd, capture_output=True, timeout=120)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def dump(serial):
    """Returns the interface tree. uiautomator writes sometimes to /sdcard, sometimes to stdout
    depending on the version: the file route is tried first, being the more reliable."""
    adb(serial, "shell", "rm", "-f", "/sdcard/uictl.xml")
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/uictl.xml")
    xml = adb(serial, "shell", "cat", "/sdcard/uictl.xml")
    if "<hierarchy" not in xml:
        xml = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty")
    start = xml.find("<hierarchy")
    return xml[start:] if start >= 0 else ""


def exact_pattern(pattern):
    """A pattern prefixed with `=` asks for equality, not for containment.

    Learned on the watch recording screen: `presse STOP` caught "Long press to stop", which
    contains the word, which sits above the button and which is not clickable. The long press ran
    without error and did nothing — the usual failure mode of that screen. The "text exactly equal"
    priority of the sort is not enough when the real button is **below the fold** and therefore
    absent from the dump: the sort ranks what it has, and what it has is the wrong element. With
    `=STOP`, the element stays unfindable until something has scrolled, which is the truth.
    """
    return (pattern[1:], True) if pattern.startswith("=") else (pattern, False)


def nodes(xml, pattern):
    """Elements whose text, description or identifier contains the pattern."""
    found = []
    raw, exact = exact_pattern(pattern)
    pat = raw.lower()
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return found
    for node in root.iter("node"):
        attrs = [node.get(a, "") for a in ("text", "content-desc", "resource-id")]
        hay = " ".join(attrs).lower()
        if (any(a.strip().lower() == pat for a in attrs) if exact else pat in hay):
            m = BOUNDS.match(node.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            found.append(
                {
                    "w": x2 - x1,
                    "h": y2 - y1,
                    "text": node.get("text", ""),
                    "desc": node.get("content-desc", ""),
                    "id": node.get("resource-id", ""),
                    "clickable": node.get("clickable", "false"),
                    "checkable": node.get("checkable", "false"),
                    "checked": node.get("checked", ""),
                    "cls": node.get("class", ""),
                    "cx": (x1 + x2) // 2,
                    "cy": (y1 + y2) // 2,
                    "bounds": node.get("bounds", ""),
                }
            )
    return found


def area(n):
    return n["w"] * n["h"]


def describe(n):
    return (
        f"text={n['text']!r} desc={n['desc']!r} id={n['id']!r} "
        f"clickable={n['clickable']} bounds={n['bounds']} centre=({n['cx']},{n['cy']})"
    )


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    serial, cmd = sys.argv[1], sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else None

    if cmd == "dump":
        print(dump(serial))
        print("UICTL_OK")
        return 0

    if cmd == "shot":
        data = adb(serial, "exec-out", "screencap", "-p", binary=True)
        with open(arg, "wb") as f:
            f.write(data)
        print(f"UICTL_OK {arg} {len(data)} bytes")
        return 0

    if cmd == "clic":
        # The elements that are really actionable. To be used when `tap <text>` fails: in
        # Compose, a checkbox label is a distinct node and is not clickable, and tapping it ticks
        # nothing without producing the slightest error.
        found = nodes(dump(serial), "")
        for n in found:
            if n["clickable"] == "true" or n["checkable"] == "true":
                print(f"{n['cls']} ticked={n['checked']} {describe(n)}")
        print("UICTL_OK")
        return 0

    if cmd == "cocher":
        # Tick the boxes one at a time, re-dumping between each: the bounds shift as soon as a
        # ticked box changes the height of a block, and a batch of taps computed from a single
        # dump misses from the second one on. Non-negotiable precondition, learned in §7.4: the
        # screen must be awake (`svc power stayon true`), otherwise `input tap` runs without error
        # and ticks nothing.
        want = int(arg) if arg else 1
        done = 0
        for _ in range(want * 3):
            if done >= want:
                break
            boxes = [
                n
                for n in nodes(dump(serial), "")
                if n["checkable"] == "true" and n["checked"] == "false"
            ]
            if not boxes:
                break
            n = boxes[0]
            adb(serial, "shell", "input", "tap", str(n["cx"]), str(n["cy"]))
            time.sleep(1.5)
            done += 1
        print(f"UICTL_OK {done} box(es) ticked" if done else "UICTL_FAIL no box to tick")
        return 0 if done else 1

    if cmd == "find":
        found = nodes(dump(serial), arg)
        for n in found:
            print(describe(n))
        print("UICTL_OK" if found else "UICTL_FAIL no element")
        return 0 if found else 1

    if cmd == "tap":
        found = nodes(dump(serial), arg)
        # Priority order, learned by tapping beside the target: (1) text exactly equal to the
        # pattern — without which "Allow" catches the title "Allow Pendulum to send you
        # notifications?", which contains the word and occupies half the screen; (2) clickable
        # element; (3) the smallest, since a button is smaller than the container holding it.
        pat = exact_pattern(arg)[0].lower()
        found.sort(
            key=lambda n: (
                n["text"].strip().lower() != pat,
                n["clickable"] != "true",
                area(n),
            )
        )
        if not found:
            print(f"UICTL_FAIL pattern absent: {arg}")
            return 1
        n = found[0]
        adb(serial, "shell", "input", "tap", str(n["cx"]), str(n["cy"]))
        print(f"UICTL_OK tap ({n['cx']},{n['cy']}) on {describe(n)}")
        return 0

    if cmd == "presse":
        # Long press, through a `swipe` with no movement: `input` has no verb for it and
        # `motionevent DOWN/UP` produces nothing on Compose buttons (§7.4). The watch STOP is
        # deliberately a long press followed by a confirmation — two gestures, neither of them
        # accidental — so the bench must know how to produce one.
        duration = int(sys.argv[4]) if len(sys.argv) > 4 else 900
        found = nodes(dump(serial), arg)
        pat = exact_pattern(arg)[0].lower()
        found.sort(
            key=lambda n: (
                n["text"].strip().lower() != pat,
                n["clickable"] != "true",
                area(n),
            )
        )
        if not found:
            print(f"UICTL_FAIL pattern absent: {arg}")
            return 1
        n = found[0]
        adb(
            serial, "shell", "input", "swipe",
            str(n["cx"]), str(n["cy"]), str(n["cx"]), str(n["cy"]), str(duration),
        )
        print(f"UICTL_OK long press {duration} ms ({n['cx']},{n['cy']}) on {describe(n)}")
        return 0

    if cmd == "wait":
        timeout = float(sys.argv[4]) if len(sys.argv) > 4 else 30.0
        deadline = time.time() + timeout
        while time.time() < deadline:
            found = nodes(dump(serial), arg)
            if found:
                for n in found:
                    print(describe(n))
                print("UICTL_OK")
                return 0
            time.sleep(2)
        print(f"UICTL_FAIL absent after {timeout} s: {arg}")
        return 1

    print(f"UICTL_FAIL unknown command: {cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
