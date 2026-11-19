#!/usr/bin/env python3
"""Fails the build when a JUnit test was skipped and is not on the allow list.

Gradle reports a skipped test as a success, so a test that guards itself with
assumeTrue(Files.exists(...)) silently stops testing anything once an asset or
source file moves (review M13). This reads the JUnit XML reports under the given
directories and lists every skipped test. Each one must be named in
tools/ci/allowed_skips.txt (a class name, or class#method), with the reason it may
skip in CI; anything else fails the job.

Usage: check_skips.py <results dir>...
"""
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))


def allowed():
    out = set()
    with open(os.path.join(HERE, "allowed_skips.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                out.add(line.split()[0])
    return out


def main(dirs):
    ok = allowed()
    skipped, reports = [], 0
    for d in dirs:
        for root, _, files in os.walk(d):
            for name in files:
                if not (name.startswith("TEST-") and name.endswith(".xml")):
                    continue
                reports += 1
                for case in ET.parse(os.path.join(root, name)).getroot().iter("testcase"):
                    if case.find("skipped") is not None:
                        skipped.append((case.get("classname"), case.get("name")))
    if reports == 0:
        print("no JUnit reports found under", dirs, file=sys.stderr)
        return 1
    bad = sorted({(c, m) for c, m in skipped if c not in ok and f"{c}#{m}" not in ok})
    print(f"{reports} reports, {len(skipped)} skipped tests, {len(bad)} not allowed")
    for c, m in bad:
        print(f"::error title=Skipped test::{c}#{m} was skipped. A missing asset or source file? "
              f"Fix it, or add it to tools/ci/allowed_skips.txt with the reason.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
