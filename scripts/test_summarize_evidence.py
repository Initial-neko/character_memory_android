"""Synthetic regression evidence; never substitutes for Android JUnit runs."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from summarize_evidence import summarize


P1_SCREENS = (
    "chat-list", "direct-chat", "character-create", "group-create", "call-mock",
    "space-feed", "settings", "imagegen-draft", "group-chat", "call-narrow",
    "call-landscape", "chat-ime",
)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, mode="jvm", count=13, skipped=0, classname=None):
        folder = self.root / "app/build" / (
            "test-results/testDebugUnitTest" if mode == "jvm" else "outputs/androidTest-results/connected/debug"
        )
        folder.mkdir(parents=True, exist_ok=True)
        classname = classname or "com.charactermemory.android." + (
            "PrototypeRulesTest" if mode == "jvm" else "PrototypeUiTest"
        )
        suite = ET.Element("testsuite", name=classname, tests=str(count),
                           skipped=str(skipped), failures="0", errors="0")
        for index in range(count):
            case = ET.SubElement(suite, "testcase", classname=classname, name=f"case_{index}")
            if index < skipped:
                ET.SubElement(case, "skipped")
        path = folder / f"TEST-{classname}.xml"
        ET.ElementTree(suite).write(path)
        return path

    def screenshots(self):
        folder = self.root / "artifacts/screenshots/CharacterMemoryP1"
        folder.mkdir(parents=True)
        for index, name in enumerate(P1_SCREENS, 1):
            (folder / f"p1-{index:02}-{name}.png").write_bytes(b"synthetic" + bytes([index]))
        return folder

    def test_skipped_prevents_pass(self):
        self.report(skipped=1)
        result = summarize(self.root, "jvm")
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(1, result["skipped"])

    def test_missing_xml_is_not_run(self):
        self.assertEqual("NOT_RUN", summarize(self.root, "jvm")["status"])

    def test_invalid_xml_prevents_pass_even_beside_valid_results(self):
        path = self.report()
        path.with_name("broken.xml").write_text("<broken>")
        self.assertEqual("FAIL", summarize(self.root, "jvm")["status"])

    def test_missing_screenshot_prevents_pass(self):
        self.report("emulator", count=5)
        folder = self.screenshots()
        (folder / "p1-12-chat-ime.png").unlink()
        self.assertEqual(["p1-12-chat-ime.png"], summarize(self.root, "emulator")["missing_screenshots"])
        self.assertEqual("FAIL", summarize(self.root, "emulator")["status"])

    def test_p2_tests_cannot_fill_p1_minimum(self):
        self.report(count=12)
        self.report(count=10, classname="com.charactermemory.android.data.CoreApiTest")
        result = summarize(self.root, "jvm")
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(12, result["tests"])

    def test_extra_p1_screenshot_fails_but_p2_does_not_contaminate(self):
        self.report("emulator", count=5)
        folder = self.screenshots()
        (folder / "p2-01-roster.png").write_bytes(b"p2")
        self.assertEqual("PASS", summarize(self.root, "emulator")["status"])
        (folder / "p1-unexpected.png").write_bytes(b"extra")
        self.assertEqual("FAIL", summarize(self.root, "emulator")["status"])

    def test_screenshot_hash_matches_actual_bytes(self):
        self.report("emulator", count=5)
        folder = self.screenshots()
        name = "p1-12-chat-ime.png"
        expected = hashlib.sha256((folder / name).read_bytes()).hexdigest()
        self.assertEqual(expected, summarize(self.root, "emulator")["screenshot_sha256"][name])

    def test_local_sha_is_actual_git_head(self):
        self.report()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "-c", "commit.gpgsign=false", "-c", f"core.hooksPath={self.root / 'no-hooks'}",
                        "commit", "--allow-empty", "-qm", "fixture"], check=True)
        expected = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()
        with patch.dict(os.environ, {"GITHUB_SHA": ""}):
            self.assertEqual(expected, summarize(self.root, "jvm")["git_sha"])

    def test_p2_profile_is_separate(self):
        self.report()
        self.report(count=4, classname="com.charactermemory.android.data.CoreApiTest")
        result = summarize(self.root, "jvm", profile="p2", min_tests=4)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(4, result["tests"])

    def test_duplicate_results_cannot_fill_minimum(self):
        path = self.report(count=7)
        path.with_name("duplicate.xml").write_bytes(path.read_bytes())
        self.assertEqual("FAIL", summarize(self.root, "jvm")["status"])

    def test_p2_skips_are_independent_and_fail_p2(self):
        self.report()
        self.report(count=4, skipped=1, classname="com.charactermemory.android.data.CoreApiTest")
        self.assertEqual("PASS", summarize(self.root, "jvm")["status"])
        self.assertEqual("FAIL", summarize(self.root, "jvm", profile="p2", min_tests=4)["status"])

    def test_cannot_lower_p1_gate(self):
        with self.assertRaises(ValueError):
            summarize(self.root, "jvm", min_tests=1)

    def test_github_sha_is_preserved(self):
        with patch.dict(os.environ, {"GITHUB_SHA": "1234567890abcdef"}):
            self.assertEqual("1234567890abcdef", summarize(self.root, "jvm")["git_sha"])

    def test_invalid_count_is_rejected(self):
        path = self.report()
        root = ET.parse(path).getroot()
        root.set("tests", "99")
        ET.ElementTree(root).write(path)
        self.assertEqual("FAIL", summarize(self.root, "jvm")["status"])

    def test_android_mixed_class_suite_counts_profiles_separately(self):
        path = self.report("emulator", count=5)
        suite = ET.parse(path).getroot()
        suite.set("tests", "10")
        for index in range(5):
            ET.SubElement(suite, "testcase", classname="com.charactermemory.android.LiveApiUiTest", name=f"live_{index}")
        ET.ElementTree(suite).write(path)
        self.screenshots()
        self.assertEqual("PASS", summarize(self.root, "emulator")["status"])
        result = summarize(self.root, "emulator", profile="p2")
        self.assertEqual(5, result["tests"])
        self.assertEqual(19, len(result["missing_screenshots"]))

    def test_attribute_only_skip_prevents_pass(self):
        path = self.report()
        suite = ET.parse(path).getroot()
        suite.set("skipped", "1")
        ET.ElementTree(suite).write(path)
        self.assertEqual("FAIL", summarize(self.root, "jvm")["status"])


if __name__ == "__main__":
    unittest.main()
