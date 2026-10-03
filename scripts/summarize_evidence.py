#!/usr/bin/env python3
"""Fail-closed JUnit/screenshot evidence, with independent P1 and P2 gates."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

P1_CLASSES = {"PrototypeRulesTest", "PrototypeViewModelTest", "PrototypeUiTest"}
P1_SCREENS = (
    "p1-01-chat-list.png", "p1-02-direct-chat.png", "p1-03-character-create.png",
    "p1-04-group-create.png", "p1-05-call-mock.png", "p1-06-space-feed.png",
    "p1-07-settings.png", "p1-08-imagegen-draft.png", "p1-09-group-chat.png",
    "p1-10-call-narrow.png", "p1-11-call-landscape.png", "p1-12-chat-ime.png",
)
P2_SCREENS = (
    "p2-01-settings.png", "p2-02-roster.png", "p2-03-direct-chat.png",
    "p2-04-chat-ime.png", "p2-05-space.png", "p2-06-character-draft.png",
    "p2-07-ensemble-preview.png", "p2-08-image-draft.png", "p2-09-group-chat.png",
    "p2-10-usage.png", "p2-11-sticker-packs.png", "p2-12-space-mentions.png",
    "p2-13-asr-draft.png",
    "p2-14-voice-states.png", "p2-15-voice-playback-error.png", "p2-16-tts-playback.png",
    "p2-17-call-listening.png", "p2-18-call-speaking.png",
    "p2-19-visual-controls.png",
    "p2-20-media-routing-error.png",
)


def git_sha(root: Path) -> str:
    if os.getenv("GITHUB_SHA"):
        return os.environ["GITHUB_SHA"]
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"], text=True,
            stderr=subprocess.DEVNULL, timeout=10,
        ).strip() or "LOCAL_UNKNOWN"
    except (OSError, subprocess.SubprocessError):
        return "LOCAL_UNKNOWN"


def summarize(root: Path, mode: str, profile: str = "p1", min_tests: int | None = None) -> dict:
    if mode not in {"jvm", "emulator"} or profile not in {"p1", "p2"}:
        raise ValueError("unknown evidence mode/profile")
    if min_tests is not None and (profile == "p1" or min_tests < 1):
        raise ValueError("only P2 may configure a positive minimum")
    candidates = root / "app/build" / (
        "test-results/testDebugUnitTest" if mode == "jvm" else "outputs/androidTest-results"
    )
    xmls = sorted(candidates.rglob("*.xml")) if candidates.exists() else []
    tests = failures = skipped = errors = valid = 0
    invalid_xmls, duplicate_tests, identities, sources = [], [], set(), []
    for path in xmls:
        try:
            element = ET.parse(path).getroot()
            suites = [element] if element.tag == "testsuite" else element.findall(".//testsuite")
            if not suites:
                raise ValueError("no JUnit testsuite")
            for suite in suites:
                cases = suite.findall("testcase")
                attributes = {key: int(suite.attrib.get(key, 0)) for key in ("tests", "failures", "skipped", "errors")}
                if any(value < 0 for value in attributes.values()) or attributes["tests"] != len(cases):
                    raise ValueError("inconsistent JUnit test count")
                name = suite.attrib.get("name", "")
                # Android combines multiple classes in one device testsuite.
                # Filter testcase classes, not suite totals, to keep P1/P2 separate.
                if any(not case.attrib.get("classname", name) or not case.attrib.get("name") for case in cases):
                    raise ValueError("missing testcase class/name")
                selected = [case for case in cases if (
                    case.attrib.get("classname", name).rsplit(".", 1)[-1] in P1_CLASSES
                ) == (profile == "p1")]
                if not selected:
                    continue
                valid += 1
                sources.append(str(path.relative_to(root)))
                # If an attribute reports additional outcomes absent from testcase
                # nodes, conservatively block each selected profile in that suite.
                counts = {}
                for key, node in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                    overall = sum(case.find(node) is not None for case in cases)
                    counts[key] = sum(case.find(node) is not None for case in selected) + max(0, attributes[key] - overall)
                failures += counts["failures"]
                errors += counts["errors"]
                skipped += counts["skipped"]
                for case in selected:
                    classname = case.attrib.get("classname", name)
                    identity = classname + "." + case.attrib.get("name", "")
                    if identity in identities:
                        duplicate_tests.append(identity)
                    else:
                        identities.add(identity)
                        tests += 1
        except (ET.ParseError, OSError, ValueError) as error:
            invalid_xmls.append({"path": str(path.relative_to(root)), "reason": str(error)})

    shots = sorted((root / "artifacts/screenshots").rglob(profile + "-*.png")) if mode == "emulator" else []
    expected_screens = (P1_SCREENS if profile == "p1" else P2_SCREENS) if mode == "emulator" else ()
    shot_names = [path.name for path in shots]
    missing_screens = sorted(set(expected_screens) - set(shot_names))
    unexpected_screens = sorted(set(shot_names) - set(expected_screens))
    duplicate_screens = sorted(name for name in set(shot_names) if shot_names.count(name) > 1)
    expected_min_tests = (13 if mode == "jvm" else 5) if profile == "p1" else (min_tests or (27 if mode == "jvm" else 6))
    status = (
        "FAIL" if invalid_xmls or duplicate_tests
        else "NOT_RUN" if valid == 0 or tests == 0
        else "FAIL" if failures or errors or skipped or tests < expected_min_tests
        or missing_screens or unexpected_screens or duplicate_screens
        else "PASS"
    )
    return {
        "mode": mode, "profile": profile, "status": status,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "git_sha": git_sha(root), "run_id": os.getenv("GITHUB_RUN_ID") or "LOCAL_UNKNOWN",
        "test_suites": valid, "test_result_files": sources,
        "invalid_xmls": invalid_xmls, "duplicate_tests": sorted(set(duplicate_tests)),
        "expected_min_tests": expected_min_tests, "tests": tests,
        "passed": max(0, tests - failures - errors - skipped),
        "failures": failures, "errors": errors, "skipped": skipped,
        "actual_screenshot_count": len(shots), "required_screenshots": len(expected_screens),
        "missing_screenshots": missing_screens, "unexpected_screenshots": unexpected_screens,
        "duplicate_screenshots": duplicate_screens,
        "screenshot_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in shots},
        "real_device": "NOT_RUN", "core_api_integration": "NOT_RUN", "media_projection": "NOT_RUN",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["jvm", "emulator"], required=True)
    parser.add_argument("--profile", choices=["p1", "p2"], default="p1")
    parser.add_argument("--min-tests", type=int, help="positive P2 test minimum; P1 gates cannot be overridden")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = summarize(Path.cwd(), args.mode, args.profile, args.min_tests)
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
