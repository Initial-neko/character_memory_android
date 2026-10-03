"""Expose bounded synthetic instrumentation failure details in CI logs as JSON."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

failures = []
for path in sorted(Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        for tag in ("failure", "error"):
            for failure in case.findall(tag):
                failures.append({"class": case.get("classname"), "test": case.get("name"),
                                 "detail": (failure.text or failure.get("message", ""))[:6000]})
print(json.dumps({"instrumented_failures": failures}, ensure_ascii=False, indent=2))
