#!/usr/bin/env python3
"""Evidence summary: count what ran, do not convert missing tests to PASS."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import hashlib
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(root: Path, mode: str) -> dict:
    results = root / "app" / "build"
    candidates = (
        results / "test-results" / "testDebugUnitTest"
        if mode == "jvm"
        else results / "outputs" / "androidTest-results"
    )
    xmls = list(candidates.rglob("*.xml")) if candidates.exists() else []
    tests = failures = skipped = errors = 0
    valid = 0
    for path in xmls:
        try:
            element = ET.parse(path).getroot()
            suites = [element] if element.tag == "testsuite" else element.findall(".//testsuite")
            if not suites:
                continue
            for suite in suites:
                tests += int(suite.attrib.get("tests", 0))
                failures += int(suite.attrib.get("failures", 0))
                skipped += int(suite.attrib.get("skipped", 0))
                errors += int(suite.attrib.get("errors", 0))
                valid += 1
        except (ET.ParseError, ValueError):
            continue

    shots = list((root / "artifacts" / "screenshots").rglob("*.png"))
    expected_screens = (
        "p1-01-chat-list.png",
        "p1-02-direct-chat.png",
        "p1-03-character-create.png",
        "p1-04-group-create.png",
        "p1-05-call-mock.png",
        "p1-06-space-feed.png",
        "p1-07-settings.png",
        "p1-08-imagegen-draft.png",
        "p1-09-group-chat.png",
        "p1-10-call-narrow.png",
        "p1-11-call-landscape.png",
        "p1-12-chat-ime.png",
    ) if mode == "emulator" else ()
    missing_screens = sorted(set(expected_screens) - {path.name for path in shots})
    expected_min_tests = 13 if mode == "jvm" else 5
    status = (
        "NOT_RUN" if valid == 0 or tests == 0
        else "FAIL" if failures or errors
        else "FAIL" if tests < expected_min_tests or missing_screens
        else "PASS"
    )
    return {
        "mode": mode,
        "status": status,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "git_sha": os.getenv("GITHUB_SHA") or "LOCAL_UNKNOWN",
        "run_id": os.getenv("GITHUB_RUN_ID") or "LOCAL_UNKNOWN",
        "test_suites": valid,
        "expected_min_tests": expected_min_tests,
        "tests": tests,
        "passed": max(0, tests - failures - errors - skipped),
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "actual_screenshot_count": len(shots),
        "required_screenshots": len(expected_screens),
        "missing_screenshots": missing_screens,
        "screenshot_sha256": {
            path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in shots
        },
        "real_device": "NOT_RUN",
        "core_api_integration": "NOT_RUN",
        "media_projection": "NOT_RUN",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["jvm", "emulator"], required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = summarize(Path.cwd(), args.mode)
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
