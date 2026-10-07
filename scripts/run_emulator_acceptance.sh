#!/usr/bin/env bash
# Keep test exit status and evidence collection in one shell. The emulator-runner
# executes separate YAML script lines separately, so inline `set +e` is insufficient.
set +e
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
mkdir -p artifacts/screenshots artifacts/rss
adb logcat -b all -v threadtime > artifacts/emulator-continuous-logcat.txt &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null; wait "$log_pid" 2>/dev/null' EXIT
# Do not start UI tests underneath a system ANR dialog. Cold compilation now
# happens before emulator boot, so Launcher and the IME can settle independently.
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME > artifacts/emulator-home-start.txt
home_result=$?
adb shell dumpsys activity lastanr > artifacts/emulator-before-lastanr.txt
adb shell dumpsys window windows > artifacts/emulator-before-windows.txt
adb logcat -b all -d > artifacts/emulator-before-logcat.txt
if [ "$home_result" -ne 0 ] || ! grep -Fq '<no ANR has occurred since boot>' artifacts/emulator-before-lastanr.txt; then
  adb shell screencap -p /sdcard/character-memory-system-failure.png
  adb pull /sdcard/character-memory-system-failure.png artifacts/emulator-system-failure.png
  echo 'Emulator system readiness failed; UI acceptance was not run.' >&2
  exit 1
fi
./gradlew --no-daemon --max-workers=2 connectedDebugAndroidTest --stacktrace -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.charactermemory.android.RssLiveCore
test_result=$?
adb pull /sdcard/Pictures/CharacterMemoryP1 artifacts/screenshots
p1_pull=$?
adb pull /sdcard/Pictures/CharacterMemoryP2 artifacts/screenshots
p2_pull=$?
adb pull /sdcard/Pictures/CharacterMemoryRss artifacts/rss/screenshots
rss_pull=$?
adb pull /sdcard/Download/CharacterMemoryRss artifacts/rss/requests
rss_requests_pull=$?
adb logcat -b all -d > artifacts/emulator-logcat.txt
logs=$?
adb shell dumpsys activity lastanr > artifacts/emulator-lastanr.txt
adb shell dumpsys window windows > artifacts/emulator-windows.txt
if [ "$test_result" -ne 0 ]; then
  adb shell screencap -p /sdcard/character-memory-system-failure.png
  adb pull /sdcard/character-memory-system-failure.png artifacts/emulator-system-failure.png
  adb shell uiautomator dump /sdcard/character-memory-failure.xml
  adb pull /sdcard/character-memory-failure.xml artifacts/emulator-system-failure.xml
fi
if [ "$test_result" -ne 0 ]; then
  exit "$test_result"
fi
if [ "$p1_pull" -ne 0 ] || [ "$p2_pull" -ne 0 ] || [ "$rss_pull" -ne 0 ] || [ "$rss_requests_pull" -ne 0 ] || [ "$logs" -ne 0 ]; then
  exit 1
fi
