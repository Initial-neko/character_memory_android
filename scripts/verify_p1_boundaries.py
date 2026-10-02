#!/usr/bin/env python3
"""Protect the explicit P1 offline demo while allowing P2 network transport.

Source-level check; manifest-merger/instrumented permission verification remains
a separate CI/device acceptance task.
"""
from __future__ import annotations

from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
manifest = ROOT / "app/src/main/AndroidManifest.xml"
root = ET.parse(manifest).getroot()
ns = "{http://schemas.android.com/apk/res/android}"
permissions = [
    child.attrib.get(ns + "name", "")
    for child in root
    if child.tag in {"uses-permission", "uses-permission-sdk-23"}
]
forbidden = ("CAMERA", "FOREGROUND_SERVICE", "MEDIA_PROJECTION", "POST_NOTIFICATIONS")
violations = [permission for permission in permissions if any(token in permission for token in forbidden)]
app = root.find("application")
if app is None:
    violations.append("missing application")
elif app.attrib.get(ns + "usesCleartextTraffic") != "false":
    violations.append("cleartext traffic not explicitly disabled")
expected = {
    "screen-home", "screen-chat", "screen-character", "screen-group",
    "screen-call", "screen-space", "screen-settings",
}
code = "\n".join(
    path.read_text(encoding="utf-8")
    for path in (ROOT / "app/src/main/java/com/charactermemory/android").rglob("*.kt")
)
missing = sorted(tag for tag in expected if ('"' + tag + '"') not in code)
delegation_import_errors = []
mock_network_errors = []
package = ROOT / "app/src/main/java/com/charactermemory/android"
mock_sources = sorted((package / "screens").glob("*.kt")) + sorted(package.glob("Prototype*.kt"))
network_pattern = re.compile(
    r"^import\s+(?:okhttp3|retrofit2|java\.net|javax\.net|android\.net|"
    r"com\.charactermemory\.android\.(?:data|live|media))\b|"
    r"\b(?:CoreApi|LiveViewModel|AudioRecord|HttpURLConnection|Socket)\s*\(|\bURL\s*\(", re.MULTILINE
)
for source in mock_sources:
    if network_pattern.search(source.read_text(encoding="utf-8")):
        mock_network_errors.append(str(source.relative_to(ROOT)))
if not mock_sources:
    violations.append("missing P1 Mock sources")
violations.extend("network code in P1 Mock source: " + source for source in mock_network_errors)
entry = (package / "MainActivity.kt").read_text(encoding="utf-8")
explicit_mock_entry = bool(re.search(r'getBooleanExtra\(\s*"p1_mock"\s*,\s*false\s*\)', entry))
if not explicit_mock_entry:
    violations.append("missing explicit p1_mock=false launch boundary")
for screen in (ROOT / "app/src/main/java/com/charactermemory/android/screens").glob("*.kt"):
    body = screen.read_text(encoding="utf-8")
    if "by rememberSaveable" in body and (
        "import androidx.compose.runtime.getValue" not in body
        or "import androidx.compose.runtime.setValue" not in body
    ):
        delegation_import_errors.append(screen.name)
violations.extend("missing Compose delegate imports: " + f for f in delegation_import_errors)
status = "PASS" if not violations and not missing else "FAIL"
result = {
    "status": status,
    "source": str(manifest.relative_to(ROOT)),
    "requested_permissions": permissions,
    "prohibited_requested_permissions": violations,
    "expected_screen_count": len(expected),
    "missing_screen_tags": missing,
    "missing_compose_delegate_imports": delegation_import_errors,
    "mock_sources_checked": [str(path.relative_to(ROOT)) for path in mock_sources],
    "mock_network_violations": mock_network_errors,
    "explicit_mock_entry": explicit_mock_entry,
    "note": "P2/P3 shared APK may request INTERNET and RECORD_AUDIO. P1 Mock must remain offline and never import microphone implementation; camera/projection/foreground permissions stay prohibited. Built manifest and runtime permission acceptance remain separate."
}
out = ROOT / "artifacts/p1-boundary-evidence.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if status == "PASS" else 1)
