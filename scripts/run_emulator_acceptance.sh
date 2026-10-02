#!/usr/bin/env bash
# Keep test exit status and evidence collection in one shell. The emulator-runner
# executes separate YAML script lines separately, so inline `set +e` is insufficient.
set +e
./gradlew --no-daemon connectedDebugAndroidTest --stacktrace
test_result=$?
mkdir -p artifacts/screenshots
adb pull /sdcard/Pictures/CharacterMemoryP1 artifacts/screenshots
p1_pull=$?
adb pull /sdcard/Pictures/CharacterMemoryP2 artifacts/screenshots
p2_pull=$?
adb logcat -d -t 3000 > artifacts/emulator-logcat.txt
logs=$?
if [ "$test_result" -ne 0 ]; then
  exit "$test_result"
fi
if [ "$p1_pull" -ne 0 ] || [ "$p2_pull" -ne 0 ] || [ "$logs" -ne 0 ]; then
  exit 1
fi
