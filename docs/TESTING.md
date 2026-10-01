# Android V1 测试与 AI 验收规范

Status: **P1 executed, future P2–P5 acceptance design**. The merged P1 CI [run #36872285876](https://github.com/Initial-neko/character_memory_android/actions/runs/36872285876) has unit tests, Compose emulator UI tests, APK/JUnit and nine screen captures. Real Core and Android media hardware are still NOT RUN.

## 1. Evidence levels

| Level | Tooling | What it can prove | What it cannot prove |
|---|---|---|---|
| A. JVM | JUnit, MockWebServer, Kotlin Coroutines test | DTOs, REST errors, SSE parsing/retry/dedupe, ViewModel & CallController state machine | Android camera/mic/OS permissions |
| B. Instrumented emulator | Compose UI Test, Espresso/UI Automator, Android emulator | navigation, forms, IME, accessibility, screenshots, fake media and mocked system state | hardware thermal/battery, all MediaProjection and headset cases |
| C. Core integration | PC stack + Tailnet + Android client | real 202→SSE, character/group/Space, Vision routing, ASR/TTS API | all device/OEM background behaviors |
| D. Hardware acceptance | ADB/logcat + screen recording + real phone, user-approved capture | real CameraX, AudioRecord, MediaProjection consent, lock/rotation/network/headsets | universal compatibility across every device and OEM |

Report **PASS, FAIL, SKIPPED or NOT RUN** separately for every level. Never replace missing hardware evidence with simulator PASS.

## 2. Mandatory functional scenarios

| Case | Setup/action | Observable acceptance |
|---|---|---|
| NET-01 | Core online / offline / restart / media offline | clear status and retry; text still works if Media fails |
| CHAT-01 | Direct user message | 202 + one durable user event + one stream/reconcile projection, no duplicate message |
| CHAT-02 | SSE reconnect and missed event | history-page reconciliation, stable order and no duplicates |
| CHAT-03 | valid silence or reaction_error | no fake AI reply; user fact preserved and error state explained |
| CHAR-01 | describe character, request draft, confirm | draft preview, soft-limit 409 handled, returned character usable |
| GROUP-01 | ensemble prepare → retry/preview → confirm | no duplicate build/character on accidental repeat, correct group entry |
| GROUP-02 | user sends group turn | 202, group SSE, member completion and deduped history |
| SPACE-01 | list → paginate → comments/replies | cursor respected, <=10 server page cap, no loss on refresh |
| IMAGE-01 | rewrite → generate → preview → confirm send | no unconfirmed draft becomes a durable chat message |
| CALL-01 | audio segment → ASR → Core 202/SSE → TTS | correct conversation and speaker, ordered subtitles/audio |
| CALL-02 | incoming speech while AI waiting/speaking | no duplicate/overlapping queue, cancellation releases mic and player |
| CAM-01 | camera start/front-back switch/stop | proper preview, bounded keyframes, camera released on exit |
| VIS-01 | camera frame + text to LLM | captured frame metadata and correct target PersonRuntime |
| SCR-01 | user approves full screen capture, switches apps | MediaProjection service captures real user-consented content; model receives bounded DISPLAY frame |
| SCR-02 | permission declined/OS stops capture/phone rotates | status updates, no extra capture or silent authorization bypass |
| SCR-03 | direct periodic observation | observes server busy/quota/dedupe refusal; no busy-loop retransmission |
| SEC-01 | revoke device / expired token (after pairing API implemented) | authorization rejected; no access to PC-only Dev/Settings |
| LIFE-01 | app pause/resume, lock, Wi-Fi↔cell, tailnet drop | documented degradation + recovery; no stuck service or leaked resources |
| PERF-01 | 30 minute audio+visual session | logged frame count/bytes, CPU/RAM, battery/thermal (hardware-only); no assumed thresholds |

## 3. Mock fixtures

Create and pin a fixture set that models **actual Core payloads**, not convenient invented DTOs:

~~~text
fixtures/
  characters-list.json
  character-draft.json
  character-create-conflict-409.json
  chat-accepted-202.json
  chat-history-page.json
  group-history-page.json
  ensemble-build-in-progress.json
  space-feed-page.json
  imagegen-draft.json
  sse-direct.txt
  sse-group.txt
  sse-reconnect.txt
  media-asr.json
  media-tts.wav
  visual-observation-accepted.json
  visual-observation-rejected.json
~~~

Read the [Core mobile API contract](https://github.com/Initial-neko/character_memory/blob/main/docs/current/MOBILE_API_CONTRACT.md) and source models before adding/changing fixtures. Later add automated Core-generated schema checks so docs and fixtures do not drift.

## 4. AI-driven UI inspection

For every changed screen:

1. Build debug APK and install emulator.
2. Run semantic-tagged Compose UI tests (not coordinate-only taps).
3. Capture baseline and current screenshots at representative phone sizes, dark/light modes and IME-open states.
4. Let AI review **actual captured screenshots**, compare against [six-screen storyboard](assets/android-v1-six-screens.svg), flag cropping, overlapping controls, inaccessible touch targets and unreadable text.
5. Save screenshots, logcat and JUnit XML under CI artifact paths. Record any intentionally accepted visual difference.

Suggested keys:

~~~text
screenshots/chat-list.png
screenshots/direct-chat.png
screenshots/character-create.png
screenshots/ensemble-preview.png
screenshots/group-chat.png
screenshots/space-feed.png
screenshots/call-listening.png
screenshots/call-speaking.png
screenshots/call-camera.png
screenshots/call-display.png
~~~

The P1 baseline has actual screenshot and CI build evidence; **future-stage screenshots** listed here are expectations only. See `docs/P1_ACCEPTANCE.md` for the concrete P1 filenames and current acceptance.

## 5. CI and developer commands (AFTER Gradle bootstrap)

~~~bash
./gradlew lintDebug testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
adb logcat -d > logcat.txt
~~~

The instrumentation task requires an available emulator/device; its CI runner configuration must be explicitly provided. Publishing `app-debug.apk` without running a hardware test is not proof of screen-sharing support.

## 6. PR verification template

~~~text
Core contract revision:
Android commit/PR:
Build: PASS / FAIL / NOT RUN
Unit and mocked contract: PASS / FAIL / NOT RUN
Emulator Compose UI: PASS / FAIL / NOT RUN
Screenshot review: PASS / FAIL / NOT RUN
Real PC Core: PASS / FAIL / NOT RUN
Real Android Mic: PASS / FAIL / NOT RUN
Real Android Camera: PASS / FAIL / NOT RUN
Real Android MediaProjection: PASS / FAIL / NOT RUN
App background / network / permission: PASS / FAIL / NOT RUN
Artifacts (APK/JUnit/screenshots/logcat):
Known limitations and reproduction:
~~~

Human confirmation is still required for OS recording consent, sensitive on-device interactions and final acceptance of physical microphone/camera/screen behavior.
