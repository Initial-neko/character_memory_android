# Android V1 测试与 AI 验收规范

Status: **P1 historical CI executed; P2 source and fixture automation added; current execution evidence is commit-specific; P3–P5 remain design**. Historical P1 CI [run #36872285876](https://github.com/Initial-neko/character_memory_android/actions/runs/36872285876) has its own APK/JUnit and nine screen captures. Current P1/P2 gates are listed below. Real Core and Android media hardware remain NOT RUN until separately verified.

## 当前自动化门槛

`scripts/summarize_evidence.py` 按 `--profile p1`（默认）或 `--profile p2` 分开汇总。P1 只计 `PrototypeRulesTest` / `PrototypeViewModelTest` / `PrototypeUiTest`；P2 计新增 API/business/UI 测试。任何 skipped、failure/error、缺失/非法 XML、重复 testcase identity 都不能 PASS。截图按 `p1-` / `p2-` 前缀隔离，缺失、重复或多余的同阶段文件名会失败；JSON 保存原始 PNG 的 SHA-256 和实际 git SHA。

| 证据 | P1 | P2 |
|---|---|---|
| JVM | ≥13，0 失败/错误/跳过 | ≥25（transport/projection 16 + LiveRules 9），0 失败/错误/跳过 |
| 模拟器 | PrototypeUiTest ≥5 | LiveApiUiTest ≥6，注入 HTTPS MockWebServer |
| 截图 | p1-01 至 p1-12，固定完整文件名 | 下列 9 个固定文件名 |
| 真实 Core/真机媒体 | NOT RUN | 用户后端验收；媒体 P3–P5 尚未实现 |

P2 截图保存到 `/sdcard/Pictures/CharacterMemoryP2/`：`p2-01-settings.png`、`p2-02-roster.png`、`p2-03-direct-chat.png`、`p2-04-chat-ime.png`、`p2-05-space.png`、`p2-06-character-draft.png`、`p2-07-ensemble-preview.png`、`p2-08-image-draft.png`、`p2-09-group-chat.png`。不能使用设计图或旧提交截图填补。

CI 先运行 `python3 -m unittest discover -s scripts -p 'test_*.py' -v`，覆盖 skipped/非法与缺失 XML、阶段隔离、缺图和 SHA。Android JVM 使用两次真实 `--rerun-tasks` 执行，第二轮先清除上一轮输出；各轮 XML/JSON 保存在 `artifacts/jvm-run-1/` 和 `artifacts/jvm-run-2/`。构建任务保留 APK/JUnit/lint，模拟器保留截图/JUnit/logcat。没有 `continue-on-error` 或静默忽略必需截图的回退。

源码边界脚本允许共享 APK 的 INTERNET；它禁止媒体/捕获权限，并检查 P1 Mock 源码不依赖网络或 `live/data` 客户端。P1 必须通过显式 `p1_mock=true` 启动，普通用户启动真实 P2。脚本只证明源码约束，不能替代最终 APK 合并 Manifest 或设备行为验证。

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

Read the [pinned Core 508c6f0 mobile API contract](https://github.com/Initial-neko/character_memory/blob/508c6f0/docs/current/MOBILE_API_CONTRACT.md) and actual source models before adding/changing fixtures. P2 uses test-local synthetic JSON/SSE fixtures; the directory above is a future-stage catalog, not a claim that all files exist. Provenance and backend acceptance are maintained in [P2_SETUP.md](P2_SETUP.md). Later add automated Core-generated schema checks so docs and fixtures do not drift.

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

## 5. CI and developer commands

~~~bash
./gradlew --offline --no-daemon lintDebug testDebugUnitTest assembleDebug --rerun-tasks
./gradlew --offline --no-daemon connectedDebugAndroidTest
adb logcat -d > logcat.txt
~~~

本地命令要求已有 JDK17、Gradle8.9 分发包、SDK 和依赖缓存；任一缺失就报告阻塞，不自动联网安装。CI 使用既有 runner 环境。The instrumentation task requires an available emulator/device. Publishing `app-debug.apk` without running a hardware test is not proof of screen-sharing support.

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
