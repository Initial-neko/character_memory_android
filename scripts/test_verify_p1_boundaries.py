"""Run the P1 source invariant in isolated synthetic repositories."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class BoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy(Path(__file__).with_name("verify_p1_boundaries.py"), scripts)
        self.package = self.root / "app/src/main/java/com/charactermemory/android"
        (self.package / "screens").mkdir(parents=True)
        (self.package / "PrototypeModels.kt").write_text("class MockModel", encoding="utf-8")
        self.tags = ' '.join('"screen-' + name + '"' for name in (
            "home", "chat", "character", "group", "call", "space", "settings"))
        (self.package / "screens/MockScreen.kt").write_text(self.tags, encoding="utf-8")
        (self.package / "MainActivity.kt").write_text(
            'intent.getBooleanExtra("p1_mock", false)', encoding="utf-8")
        self.manifest = self.root / "app/src/main/AndroidManifest.xml"
        self.write_manifest("INTERNET")

    def write_manifest(self, permission):
        self.manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            f'<uses-permission android:name="android.permission.{permission}"/>'
            '<application android:usesCleartextTraffic="false"/></manifest>', encoding="utf-8")

    def run_check(self):
        process = subprocess.run([sys.executable, str(self.root / "scripts/verify_p1_boundaries.py")],
                                 text=True, capture_output=True)
        return process.returncode, json.loads(process.stdout)

    def test_shared_internet_permission_allowed(self):
        code, evidence = self.run_check()
        self.assertEqual(0, code)
        self.assertTrue(evidence["explicit_mock_entry"])

    def test_capture_permission_prohibited(self):
        self.write_manifest("CAMERA")
        self.assertEqual(1, self.run_check()[0])

    def test_camera_permission_requires_native_capture_and_mock_remains_isolated(self):
        self.write_manifest("CAMERA")
        (self.package / "camera").mkdir()
        (self.package / "camera/CameraCapture.kt").write_text("class CameraCapture", encoding="utf-8")
        self.assertEqual(0, self.run_check()[0])
        (self.package / "PrototypeModels.kt").write_text("import com.charactermemory.android.camera.CameraCapture", encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_shared_microphone_permission_for_live_asr_allowed(self):
        self.write_manifest("RECORD_AUDIO")
        self.assertEqual(0, self.run_check()[0])

    def test_microphone_access_in_offline_mock_prohibited(self):
        (self.package / "PrototypeModels.kt").write_text("import android.media.AudioRecord", encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_projection_permissions_require_unexported_typed_native_service(self):
        self.write_manifest("FOREGROUND_SERVICE_MEDIA_PROJECTION")
        self.assertEqual(1, self.run_check()[0])
        (self.package / "screen").mkdir()
        (self.package / "screen/ScreenShareService.kt").write_text("class ScreenShareService", encoding="utf-8")
        self.manifest.write_text(self.manifest.read_text().replace(
            '<application android:usesCleartextTraffic="false"/>',
            '<application android:usesCleartextTraffic="false"><service android:name=".screen.ScreenShareService" '
            'android:exported="false" android:foregroundServiceType="mediaProjection"/></application>'), encoding="utf-8")
        self.assertEqual(0, self.run_check()[0])
        self.manifest.write_text(self.manifest.read_text().replace('android:exported="false"', 'android:exported="true"'), encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_mock_cannot_import_native_screen_capture(self):
        (self.package / "PrototypeModels.kt").write_text("import com.charactermemory.android.screen.ScreenShareService", encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_call_foreground_permission_requires_private_typed_service(self):
        self.write_manifest("FOREGROUND_SERVICE_MICROPHONE")
        self.assertEqual(1, self.run_check()[0])
        (self.package / "audio").mkdir()
        (self.package / "audio/CallSessionService.kt").write_text("class CallSessionService", encoding="utf-8")
        self.manifest.write_text(self.manifest.read_text().replace(
            '<application android:usesCleartextTraffic="false"/>',
            '<application android:usesCleartextTraffic="false"><service android:name=".audio.CallSessionService" '
            'android:exported="false" android:foregroundServiceType="microphone|mediaPlayback"/></application>'), encoding="utf-8")
        self.assertEqual(0, self.run_check()[0])
        self.manifest.write_text(self.manifest.read_text().replace('android:exported="false"', 'android:exported="true"'), encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_network_import_in_mock_prohibited(self):
        (self.package / "PrototypeModels.kt").write_text("import okhttp3.OkHttpClient", encoding="utf-8")
        code, evidence = self.run_check()
        self.assertEqual(1, code)
        self.assertEqual(1, len(evidence["mock_network_violations"]))

    def test_live_dependency_in_mock_prohibited(self):
        (self.package / "screens/MockScreen.kt").write_text(
            "import com.charactermemory.android.live.LiveViewModel\n" + self.tags, encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_explicit_mock_entry_required(self):
        (self.package / "MainActivity.kt").write_text("PrototypeApp()", encoding="utf-8")
        self.assertEqual(1, self.run_check()[0])

    def test_compose_delegate_import_gate_preserved(self):
        (self.package / "screens/MockScreen.kt").write_text(
            self.tags + "\nvar value by rememberSaveable { }", encoding="utf-8")
        code, evidence = self.run_check()
        self.assertEqual(1, code)
        self.assertEqual(["MockScreen.kt"], evidence["missing_compose_delegate_imports"])


if __name__ == "__main__":
    unittest.main()
