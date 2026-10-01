#!/usr/bin/env python3
"""Protect P1's offline demonstration boundary against accidental OS permissions.

Source-level check; manifest-merger/instrumented permission verification remains
a separate CI/device acceptance task.
"""
from __future__ import annotations

from pathlib import Path
import json
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
forbidden = ("INTERNET", "RECORD_AUDIO", "CAMERA", "FOREGROUND_SERVICE", "MEDIA_PROJECTION", "POST_NOTIFICATIONS")
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
status = "PASS" if not violations and not missing else "FAIL"
result = {
    "status": status,
    "source": str(manifest.relative_to(ROOT)),
    "requested_permissions": permissions,
    "prohibited_requested_permissions": violations,
    "expected_screen_count": len(expected),
    "missing_screen_tags": missing,
    "note": "Source invariant only; built APK manifest still requires verification."
}
out = ROOT / "artifacts/p1-boundary-evidence.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(0 if status == "PASS" else 1)
