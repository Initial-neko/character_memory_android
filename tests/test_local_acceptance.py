"""Acceptance harness self-tests: test failures and stale artifacts must never PASS."""
from __future__ import annotations

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import local_acceptance as a  # noqa: E402


def fake_png(width=720, height=1280):
    # Valid-enough PNG pixel stream with CRC-checked IHDR/IDAT/IEND.
    def chunk(name, data):
        return (struct.pack(">I", len(data)) + name + data
                + struct.pack(">I", zlib.crc32(name + data) & 0xFFFFFFFF))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    rows = (b"\0" + b"\x00" * (width * 3)) * height
    return (bytes.fromhex("89504e470d0a1a0a")
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(rows))
            + chunk(b"IEND", b""))


class MatrixTest(unittest.TestCase):
    def test_all_pass(self):
        self.assertEqual(a.result_state({"a": {"status": "PASS"}, "b": {"status": "PASS"}}), "PASS")

    def test_not_run_cannot_be_pass(self):
        self.assertEqual(a.result_state({"a": {"status": "PASS"}, "b": {"status": "NOT_RUN"}}), "NOT_RUN")

    def test_missing_precondition_is_blocked(self):
        self.assertEqual(a.result_state({"a": {"status": "BLOCKED"}, "b": {"status": "PASS"}}), "BLOCKED")

    def test_fail_precedes_blocked(self):
        self.assertEqual(a.result_state({"a": {"status": "FAIL"}, "b": {"status": "BLOCKED"}}), "FAIL")

    def test_unknown_status_is_test_defect(self):
        self.assertEqual(a.result_state({"a": {"status": "GREEN"}}), "TEST_DEFECT")


class JunitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write_xml(self, tests, failures=0, errors=0, skipped=0):
        path = self.root / "TEST-com.example.xml"
        path.write_text(
            f'<testsuite tests="{tests}" failures="{failures}"'
            f' errors="{errors}" skipped="{skipped}"></testsuite>', encoding="utf-8")
        return path

    def test_missing_is_not_run(self):
        self.assertEqual(a.parse_junit(self.root, 1)["status"], "NOT_RUN")

    def test_valid_minimum_passes(self):
        self.write_xml(13)
        report = a.parse_junit(self.root, 13)
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["passed"], 13)

    def test_failures_must_fail(self):
        self.write_xml(13, failures=1)
        self.assertEqual(a.parse_junit(self.root, 13)["status"], "FAIL")

    def test_errors_must_fail(self):
        self.write_xml(13, errors=1)
        self.assertEqual(a.parse_junit(self.root, 13)["status"], "FAIL")

    def test_skips_must_fail(self):
        self.write_xml(13, skipped=1)
        self.assertEqual(a.parse_junit(self.root, 13)["status"], "FAIL")

    def test_low_count_fails(self):
        self.write_xml(5)
        self.assertEqual(a.parse_junit(self.root, 13)["status"], "FAIL")

    def test_bad_xml_is_test_defect(self):
        (self.root / "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")
        self.assertEqual(a.parse_junit(self.root, 1)["status"], "TEST_DEFECT")

    def test_stale_xml_cannot_pass(self):
        path = self.write_xml(13)
        os.utime(path, (time.time() - 200, time.time() - 200))
        self.assertEqual(a.parse_junit(self.root, 13, time.time())["status"], "TEST_DEFECT")


class ScreenshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.filename = "p1-01-chat-list.png"

    def tearDown(self):
        self.tmp.cleanup()

    def screenshot(self, age=0, data=None):
        path = self.root / self.filename
        path.write_bytes(fake_png() if data is None else data)
        stamp = time.time() - age
        os.utime(path, (stamp, stamp))
        return path

    def test_valid_png_and_hash_pass(self):
        self.screenshot()
        report = a.audit_screens(self.root, (self.filename,), time.time())
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["found"], 1)
        self.assertEqual(len(report["sha256"][self.filename]), 64)

    def test_missing_png_fails(self):
        self.assertEqual(a.audit_screens(self.root, (self.filename,))["status"], "FAIL")

    def test_old_png_is_test_defect_not_success(self):
        self.screenshot(age=300)
        self.assertEqual(a.audit_screens(self.root, (self.filename,), time.time())["status"], "TEST_DEFECT")

    def test_corrupt_png_is_test_defect(self):
        self.screenshot(data=b"\x89PNG\r\n\x1a\nbroken")
        self.assertEqual(a.audit_screens(self.root, (self.filename,))["status"], "TEST_DEFECT")

    def test_bad_png_crc_is_test_defect(self):
        invalid = bytearray(fake_png())
        invalid[20] ^= 1
        self.screenshot(data=bytes(invalid))
        self.assertEqual(a.audit_screens(self.root, (self.filename,))["status"], "TEST_DEFECT")

    def test_tiny_placeholder_rejected(self):
        self.assertFalse(a.valid_screenshot_png(fake_png(width=1, height=1)))

    def test_unexpected_duplicate_screenshot_is_test_defect(self):
        self.screenshot()
        (self.root / "p1-01-chat-list (1).png").write_bytes(fake_png())
        self.assertEqual(a.audit_screens(self.root, (self.filename,))["status"], "TEST_DEFECT")


class PathAndSafetyTest(unittest.TestCase):
    def test_reject_checkout_subdirectory(self):
        with self.assertRaises(ValueError):
            a.output_dir(str(a.ROOT / "acceptance-runs"))

    def test_allow_external_output(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(a.output_dir(d), Path(d).resolve())

    def test_choose_checkout_rejects_non_android_directory(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                a.choose_checkout(d)

    def test_choose_checkout_accepts_android_skeleton(self):
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            (target / "app").mkdir()
            (target / "settings.gradle.kts").write_text("include(\":app\")")
            (target / "app/build.gradle.kts").write_text("plugins {}")
            self.assertEqual(a.choose_checkout(d), target.resolve())

    def test_checkout_switch_is_explicit(self):
        # A different checkout may be selected, but the tool must NOT run
        # a Git checkout, reset or modify its HEAD.
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            (target / "app").mkdir()
            (target / "settings.gradle.kts").touch()
            (target / "app/build.gradle.kts").touch()
            with mock.patch.object(a, "ROOT", target):
                self.assertRaises(ValueError, a.output_dir, str(target / "artifacts"))


    def test_disallow_nonloopback_or_credentials(self):
        bad = (
            "https://example.com", "http://10.0.2.2:8000",
            "http://127.0.0.1:8000/hidden",
            "http://me:secret@127.0.0.1:8000",
            "http://localhost:8000?token=sensitive",
            "http://evil.example:8000",
        )
        self.assertTrue(all(not a.local_only_url(x) for x in bad))
        self.assertTrue(a.local_only_url("http://127.0.0.1:8000"))
        self.assertTrue(a.local_only_url("http://localhost:8001"))

    def test_git_bash_path_resolution(self):
        with mock.patch.object(a.os, "name", "nt"):
            self.assertEqual(a.native_path("/c/Users/cute/AppData/Local/Android/Sdk"),
                             "C:\\Users\\cute\\AppData\\Local\\Android\\Sdk")

    def test_blocked_preflight_never_starts_gradle(self):
        blocked = {"status": "BLOCKED", "git": {"commit": "a" * 40},
                   "checks": {"gradle_wrapper": False}}
        with tempfile.TemporaryDirectory() as d, mock.patch.object(a, "preflight", return_value=blocked):
            with mock.patch.object(a, "run_command") as forbidden:
                result = a.run_p1(Path(d), "a" * 40)
                forbidden.assert_not_called()
                self.assertEqual(result["status"], "BLOCKED")

    def test_reports_are_local_and_never_masquerade_as_pass(self):
        with tempfile.TemporaryDirectory() as d:
            report = {"stage": "G2-READONLY", "status": "BLOCKED", "android_commit": "a" * 40,
                      "timestamp_utc": "2026-10-02T10:00:00+00:00",
                      "steps": {"Core": {"status": "BLOCKED"}}}
            a.write_report(Path(d), report)
            self.assertEqual(json.loads((Path(d) / "acceptance.json").read_text())["status"], "BLOCKED")
            self.assertIn("BLOCKED", (Path(d) / "acceptance.md").read_text())


class LocalApiHandler(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.requests.append(("GET", self.path))
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "https://example.com/forbidden")
            self.end_headers()
            return
        if self.path == "/health":
            data = {"ok": True}
        elif self.path == "/openapi.json":
            routes = a.CORE_ROUTES + a.MEDIA_ROUTES
            data = {"paths": {path: {method.lower(): {}}
                              for method, path in routes}}
        else:
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class ReadOnlyCoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        LocalApiHandler.requests = []
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), LocalApiHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def test_only_four_gets_no_unsafe_post(self):
        LocalApiHandler.requests = []
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(a, "git_state", return_value={"commit": "a" * 40,
                                                                  "problems": []}):
                result = a.run_core(Path(d), "a" * 40, self.url, self.url)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(result["steps"]["G2-Core"]["schema"]["checked"], len(a.CORE_ROUTES))
            self.assertTrue(all(method == "GET" for method, _ in LocalApiHandler.requests))
            self.assertTrue(set(path for _, path in LocalApiHandler.requests)
                            <= {"/health", "/openapi.json"})

    def test_missing_route_is_fail(self):
        result = a.inspect_routes({"paths": {"/v1/characters": {"get": {}}}}, a.CORE_ROUTES)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("POST /v1/chat/messages", result["missing"])

    def test_openapi_redirect_refused(self):
        self.assertEqual(a.request_json(self.url, "/redirect")["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
