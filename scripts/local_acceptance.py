#!/usr/bin/env python3
"""Local Agent acceptance: read-only preflight, isolated P1 test run, Core GET probes.

Python 3 stdlib only. Reports are written OUTSIDE the source checkout.
No Git mutation, credential collection, character writes, or device permissions.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import zlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
STATUS = ("PASS", "FAIL", "BLOCKED", "NOT_RUN", "NOT_IMPLEMENTED", "TEST_DEFECT")
SCREEN_NAMES = (
    "p1-01-chat-list.png", "p1-02-direct-chat.png",
    "p1-03-character-create.png", "p1-04-group-create.png",
    "p1-05-call-mock.png", "p1-06-space-feed.png", "p1-07-settings.png",
    "p1-08-imagegen-draft.png", "p1-09-group-chat.png",
    "p1-10-call-narrow.png", "p1-11-call-landscape.png", "p1-12-chat-ime.png",
)
CORE_ROUTES = (
    ("GET", "/v1/characters"), ("GET", "/v1/chat/history-page"),
    ("POST", "/v1/chat/messages"), ("GET", "/v1/events/stream"),
    ("GET", "/v1/groups"), ("GET", "/v1/space/posts"),
)
MEDIA_ROUTES = (("POST", "/v1/asr"), ("POST", "/v1/tts"))


def utcnow():
    return datetime.now(timezone.utc).isoformat()


def shell(command, cwd=ROOT, timeout=12):
    try:
        completed = subprocess.run(command, cwd=cwd, stdin=subprocess.DEVNULL,
                                   capture_output=True, text=True, errors="replace",
                                   timeout=timeout, check=False)
        return completed.returncode, (completed.stdout + completed.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, type(exc).__name__


def git_state(expected_sha=None):
    rc, revision = shell(["git", "rev-parse", "HEAD"])
    rc2, dirt = shell(["git", "status", "--porcelain=v1", "--untracked-files=normal"])
    problems = []
    if rc or not re.fullmatch(r"[a-fA-F0-9]{40}", revision):
        problems.append("cannot identify the Git commit")
    if rc2:
        problems.append("cannot determine worktree cleanliness")
    elif dirt:
        problems.append("checkout contains tracked/untracked changes")
    if expected_sha and not revision.startswith(expected_sha):
        problems.append("actual Git commit differs from --expected-sha")
    return {"commit": revision if not rc else None,
            "clean": rc2 == 0 and not dirt, "problems": problems}


def native_path(value):
    """Translate Git Bash /c/... paths for native Windows Python."""
    if not value:
        return None
    if os.name == "nt" and re.match(r"^/[A-Za-z]/", value):
        return value[1].upper() + ":" + value[2:].replace("/", "\\")
    return value


def preflight(expected_sha=None):
    git = git_state(expected_sha)
    java_home = native_path(os.environ.get("JAVA_HOME"))
    sdk = native_path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    wrapper = ROOT / "gradlew"
    rc_java, version = shell([str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
                             if java_home else "java", "-version"])
    rc_devices, devices = shell(["adb", "devices", "-l"])
    connected = [line.split()[0] for line in devices.splitlines()
                 if re.match(r"^(?:emulator-\d+|[0-9A-Za-z_.:-]+)\s+device(?:\s|$)", line)]
    emulators = [x for x in connected if re.fullmatch(r"emulator-\d+", x)]
    flags = {
        "git_known_and_clean": git["clean"] and bool(git["commit"]),
        "expected_revision": not git["problems"],
        "java17": rc_java == 0 and bool(re.search(r'version "17[."]', version)),
        "sdk_directory": bool(sdk and Path(sdk).is_dir()),
        "gradle_wrapper": wrapper.is_file() and (ROOT / "gradle/wrapper/gradle-wrapper.jar").is_file(),
        "adb_available": rc_devices == 0,
        "exactly_one_emulator_and_no_other_device": len(connected) == 1 and len(emulators) == 1,
    }
    return {
        "status": "PASS" if all(flags.values()) else "BLOCKED",
        "git": git, "checks": flags,
        "selected_java_major": "17" if flags["java17"] else "unknown_or_wrong",
        "connected_device_count": len(connected),
        "emulator_serial": emulators[0] if len(emulators) == 1 else None,
        "note": "No private path, serial of a physical device, or environment secrets are collected.",
    }


def parse_junit(directory, min_tests, not_before=None):
    """Reject missing, stale, broken and skipped suites. No test=0 false PASS."""
    files = sorted(directory.rglob("*.xml")) if directory.is_dir() else []
    files = [p for p in files if p.name.startswith("TEST-") or p.name.endswith(".xml")]
    if not files:
        return {"status": "NOT_RUN", "reason": "No JUnit XML reports", "tests": 0}
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
    for path in files:
        if not_before is not None and path.stat().st_mtime + 3 < not_before:
            return {**totals, "status": "TEST_DEFECT", "reason": "Stale JUnit XML"}
        try:
            tree = ET.parse(path).getroot()
            suites = [tree] if tree.tag == "testsuite" else tree.findall(".//testsuite")
            if not suites:
                raise ValueError("JUnit XML has no testsuite")
            for suite in suites:
                for key in ("tests", "failures", "errors", "skipped"):
                    value = int(suite.attrib.get(key, "0"))
                    if value < 0:
                        raise ValueError("Negative test count")
                    totals[key] += value
                totals["suites"] += 1
        except (ET.ParseError, ValueError):
            return {**totals, "status": "TEST_DEFECT", "reason": "Malformed JUnit XML"}
    totals["passed"] = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    if totals["passed"] < 0:
        return {**totals, "status": "TEST_DEFECT", "reason": "Inconsistent test totals"}
    totals["status"] = ("FAIL" if totals["failures"] or totals["errors"] or totals["skipped"]
                        or totals["tests"] < min_tests else "PASS")
    totals["required_minimum"] = min_tests
    return totals


def expected_screens():
    # Match repository checkout rather than assuming pending PR #9 landed.
    src = (ROOT / "scripts/summarize_evidence.py").read_text(encoding="utf-8")
    return SCREEN_NAMES if "p1-12-chat-ime.png" in src else SCREEN_NAMES[:9]


def valid_screenshot_png(data):
    """Validate actual PNG chunks and realistic viewport size, not file suffix."""
    if not data.startswith(bytes.fromhex("89504e470d0a1a0a")) or len(data) > 20 * 1024 * 1024:
        return False
    offset = 8
    have_idat = have_iend = False
    width = height = 0
    while offset + 12 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            return False
        chunk = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        if zlib.crc32(kind + chunk) & 0xFFFFFFFF != expected_crc:
            return False
        if kind == b"IHDR":
            if width or height or length != 13:
                return False
            width, height = struct.unpack_from(">II", chunk)
        if kind == b"IDAT":
            have_idat = True
        if kind == b"IEND":
            have_iend = True
            return have_idat and have_iend and width >= 320 and height >= 400 and end == len(data)
        offset = end
    return False


def audit_screens(folder, expected, not_before=None):
    found = {}
    stale = []
    for name in expected:
        path = folder / name
        if not path.is_file():
            continue
        if not_before is not None and path.stat().st_mtime + 3 < not_before:
            stale.append(name)
        data = path.read_bytes()
        if not valid_screenshot_png(data):
            return {"status": "TEST_DEFECT", "reason": "Non-PNG or truncated screenshot",
                    "filename": name}
        found[name] = hashlib.sha256(data).hexdigest()
    missing = [name for name in expected if name not in found]
    duplicate_names = sorted(p.name for p in folder.glob("p1-*.png") if p.name not in expected)
    return {"status": "TEST_DEFECT" if stale or duplicate_names else ("FAIL" if missing else "PASS"),
            "expected": len(expected), "found": len(found), "missing": missing,
            "stale": stale, "unexpected_screenshots": duplicate_names, "sha256": found}


def result_state(steps):
    statuses = [v.get("status", "TEST_DEFECT") for v in steps.values()]
    if any(s not in STATUS for s in statuses):
        return "TEST_DEFECT"
    if "FAIL" in statuses:
        return "FAIL"
    if "TEST_DEFECT" in statuses:
        return "TEST_DEFECT"
    if "BLOCKED" in statuses:
        return "BLOCKED"
    if "NOT_RUN" in statuses or "NOT_IMPLEMENTED" in statuses:
        return "NOT_RUN"
    return "PASS"


def write_report(output, report):
    output.mkdir(parents=True, exist_ok=True)
    target = output / "acceptance.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# Character Memory local acceptance", "",
        f"- Stage: **{report['stage']}**",
        f"- Status: **{report['status']}**",
        f"- Android commit: \`{report.get('android_commit') or 'UNKNOWN'}\`",
        f"- Timestamp UTC: {report['timestamp_utc']}", "",
        "| Gate | Result |",
        "|---|---|",
    ]
    lines += [f"| {key} | {value.get('status', 'TEST_DEFECT')} |"
              for key, value in report["steps"].items()]
    lines += ["", "Evidence is local-only, read-only where possible. No secrets or API bodies recorded.",
              "Real-device and P2/P3/P4 capability claims require separate execution.", ""]
    (output / "acceptance.md").write_text("\n".join(lines), encoding="utf-8")
    return target


def choose_checkout(path):
    """Use a separate, explicit P1/PR #9 worktree without moving its HEAD."""
    target = Path(path).expanduser().resolve()
    if not (target / "settings.gradle.kts").is_file() or not (target / "app/build.gradle.kts").is_file():
        raise ValueError("Target --checkout is not a usable Android project")
    return target


def output_dir(arg):
    path = Path(arg).expanduser().resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError("Evidence output must be OUTSIDE the repository checkout")
    return path


def run_command(command, log_file, timeout=1800):
    log_file.parent.mkdir(parents=True, exist_ok=True)
    with log_file.open("w", encoding="utf-8") as log:
        try:
            completed = subprocess.run(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT,
                                       timeout=timeout, check=False)
            return completed.returncode
        except (OSError, subprocess.TimeoutExpired) as exc:
            log.write("\nACCEPTANCE-RUNNER: " + type(exc).__name__ + "\n")
            return 124


def run_p1(output, expected_sha):
    pre = preflight(expected_sha)
    steps = {"G0-preflight": pre}
    started = time.time()
    if pre["status"] == "PASS":
        command = ["bash", "./gradlew"] if os.name == "nt" else ["./gradlew"]
        command += ["--no-daemon", "--rerun-tasks"]
        rc = run_command(command + ["lintDebug", "testDebugUnitTest", "assembleDebug"],
                         output / "logs/build.log")
        jvm = parse_junit(ROOT / "app/build/test-results/testDebugUnitTest", 13, started)
        apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
        steps["G1-build-and-unit"] = {
            "status": ("FAIL" if rc != 0 else "TEST_DEFECT"
                       if jvm["status"] in {"NOT_RUN", "TEST_DEFECT"} or not apk.is_file()
                       else "FAIL" if jvm["status"] != "PASS" else "PASS"),
            "gradle_exit": rc, "jvm": jvm, "apk_present": apk.is_file(),
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest()
            if rc == 0 and apk.is_file() else None,
            "log": "logs/build.log",
        }
        if steps["G1-build-and-unit"]["status"] == "PASS":
            emu_start = time.time()
            rc = run_command(command + ["connectedDebugAndroidTest"],
                             output / "logs/emulator.log", timeout=2700)
            ui = parse_junit(ROOT / "app/build/outputs/androidTest-results",
                             5 if len(expected_screens()) == 12 else 3, emu_start)
            # Screens are generated on the Android device. Copy to the unique
            # acceptance folder; do not delete historical MediaStore files.
            serial = pre["emulator_serial"]
            screenshots = output / "screenshots"
            screenshots.mkdir(parents=True, exist_ok=True)
            pull_exit = run_command(["adb", "-s", serial, "pull", "-a",
                         "/sdcard/Pictures/CharacterMemoryP1", str(screenshots)],
                        output / "logs/screenshot-pull.log", timeout=90)
            # adb pull creates <output>/screenshots/CharacterMemoryP1/.
            actual = screenshots / "CharacterMemoryP1"
            screens = audit_screens(actual, expected_screens(), emu_start)
            steps["G1-emulator-and-screens"] = {
                "status": ("FAIL" if rc != 0 else "TEST_DEFECT" if pull_exit != 0
                           or ui["status"] in {"NOT_RUN", "TEST_DEFECT"}
                           or screens["status"] == "TEST_DEFECT"
                           else "FAIL" if ui["status"] != "PASS"
                           or screens["status"] != "PASS" else "PASS"),
                "gradle_exit": rc, "screenshot_pull_exit": pull_exit, "ui": ui, "screens": screens,
                "log": "logs/emulator.log",
                "note": "ADB pull -a retains device mtime; old MediaStore screenshots cannot silently satisfy this run.",
            }
        else:
            steps["G1-emulator-and-screens"] = {
                "status": "BLOCKED", "reason": "Build/JVM gate did not pass"}
    else:
        steps["G1-build-and-unit"] = {"status": "BLOCKED", "reason": "G0 not satisfied"}
        steps["G1-emulator-and-screens"] = {"status": "BLOCKED", "reason": "G0 not satisfied"}
    return {"stage": "G0+G1", "timestamp_utc": utcnow(),
            "android_commit": pre["git"]["commit"],
            "steps": steps, "status": result_state(steps),
            "core_api_integration": "NOT_RUN", "real_device": "NOT_RUN"}


def local_only_url(base):
    parts = urlsplit(base)
    return (parts.scheme == "http" and parts.hostname in
            {"127.0.0.1", "localhost", "::1"} and parts.username is None
            and parts.password is None and not parts.query and not parts.fragment
            and parts.path in {"", "/"})


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(base, path):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(base.rstrip("/") + path,
                      headers={"Accept": "application/json",
                               "User-Agent": "CharacterMemoryAcceptance/1 (read-only)"})
    try:
        with opener.open(request, timeout=8) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                return {"status": "FAIL", "reason": "Response exceeded safety bound"}
            value = json.loads(raw)
            return {"status": "PASS" if isinstance(value, dict) else "FAIL",
                    "http_status": response.status, "data": value if isinstance(value, dict) else {}}
    except HTTPError as error:
        return {"status": "FAIL", "reason": f"HTTP {error.code}"}
    except (URLError, TimeoutError, OSError):
        return {"status": "BLOCKED", "reason": "Endpoint unavailable"}
    except (ValueError, UnicodeDecodeError):
        return {"status": "FAIL", "reason": "Response is not JSON"}


def inspect_routes(openapi, expected):
    paths = openapi.get("paths", {})
    if not isinstance(paths, dict):
        return {"status": "FAIL", "reason": "OpenAPI paths missing"}
    missing = [f"{method} {path}" for method, path in expected
               if not isinstance(paths.get(path), dict)
               or method.lower() not in paths[path]]
    return {"status": "FAIL" if missing else "PASS",
            "checked": len(expected), "missing": missing}


def run_core(output, expected_sha, core, media):
    git = git_state(expected_sha)
    steps = {}
    if git["problems"]:
        steps["G0-core-revision"] = {"status": "BLOCKED",
                                     "reason": "; ".join(git["problems"])}
    else:
        steps["G0-core-revision"] = {"status": "PASS"}
    for name, base, needed in (("Core", core, CORE_ROUTES),
                               ("Media", media, MEDIA_ROUTES)):
        if not local_only_url(base):
            steps[f"G2-{name}"] = {
                "status": "BLOCKED",
                "reason": "Only explicit loopback HTTP endpoints permitted; Tailnet requires a separate approved check"}
            continue
        health = request_json(base, "/health")
        spec = request_json(base, "/openapi.json")
        schema = inspect_routes(spec.get("data", {}), needed) if spec["status"] == "PASS" else {
            "status": spec["status"], "reason": spec.get("reason", "OpenAPI inaccessible")}
        steps[f"G2-{name}"] = {
            "status": result_state({"health": health, "schema": schema}),
            "health": {k: health[k] for k in ("status", "http_status", "reason") if k in health},
            "schema": schema,
            "note": "Only GET /health and GET /openapi.json executed; no API bodies persisted",
        }
    return {"stage": "G2-READONLY", "timestamp_utc": utcnow(),
            "android_commit": git["commit"], "steps": steps,
            "status": result_state(steps),
            "real_character_writes": "NOT_RUN",
            "sse_live_delivery": "NOT_IMPLEMENTED",
            "device_pairing": "BLOCKED",
            "tailnet_check": "NOT_RUN"}


def main(argv=None):
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["env", "p1", "core-readonly"])
    parser.add_argument("--checkout", help="Android checkout to test, defaults to this tool checkout; never mutates HEAD")
    parser.add_argument("--output", required=True,
                        help="Existing/new directory outside this checkout. No auto-upload.")
    parser.add_argument("--expected-sha", help="Full Git commit hash or unique prefix")
    parser.add_argument("--core-url", default="http://127.0.0.1:8000")
    parser.add_argument("--media-url", default="http://127.0.0.1:8001")
    args = parser.parse_args(argv)
    try:
        if args.checkout:
            ROOT = choose_checkout(args.checkout)
        dest = output_dir(args.output)
    except ValueError as exc:
        parser.error(str(exc))
    dest.mkdir(parents=True, exist_ok=True)
    if args.stage == "env":
        pre = preflight(args.expected_sha)
        report = {"stage": "G0", "timestamp_utc": utcnow(),
                  "android_commit": pre["git"]["commit"],
                  "steps": {"G0-preflight": pre}, "status": pre["status"]}
    elif args.stage == "p1":
        report = run_p1(dest, args.expected_sha)
    else:
        report = run_core(dest, args.expected_sha, args.core_url, args.media_url)
    path = write_report(dest, report)
    print(f"{report['status']}: {path}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
