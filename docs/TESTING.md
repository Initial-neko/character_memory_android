# Android V1 测试与 AI 验收规范

Status: **V1 voice/call/sticker/visual source and regressions integrated; execution evidence is commit-specific**. Full CI verifies synthetic HTTPS/UI flows; local-service TTS→ASR evidence and phone microphone/speaker/camera/screen evidence are separate gates. Do not infer hardware success from CI.

## 当前自动化门槛

RSS adds JVM repository/state/HTML tests and `RssUiTest.subscriptionsSourceFiltersDetailsAndRecovery` against synthetic HTTPS. This checks the actual information tab, source/title/type/cursor combination, paragraph/image detail, add failure preservation, cancellation and restoration. Evidence is saved locally in the app's external `rss-acceptance` directory, and on API29+ also published through MediaStore to `Pictures/CharacterMemoryRss` and `Download/CharacterMemoryRss`. Public evidence survives Gradle's post-test APK uninstall; CI pulls it into `artifacts/rss`.

`RssUiTest.actualCoreFeedAndRichArticle` is annotated `RssLiveCore` and excluded from fixture CI, rather than counted as skipped evidence. Run it explicitly with `rss_real_core` (HTTPS origin), `rss_real_ca` (base64 PEM acceptance certificate), `rss_real_item` and `rss_real_source` instrumentation arguments against an actual Core deployment. It uses explicit certificate trust and hostname validation; fixture certificates are test-only. Record real RSS/API/image results separately from mocks. Emulator success does not establish phone installation or sensor acceptance.

`scripts/summarize_evidence.py` 按 `--profile p1`（默认）或 `--profile p2` 分开汇总。P1 只计 `PrototypeRulesTest` / `PrototypeViewModelTest` / `PrototypeUiTest`；P2 计新增 API/business/UI 测试。任何 skipped、failure/error、缺失/非法 XML、重复 testcase identity 都不能 PASS。截图按 `p1-` / `p2-` 前缀隔离，缺失、重复或多余的同阶段文件名会失败；JSON 保存原始 PNG 的 SHA-256 和实际 git SHA。

| 证据 | P1 | P2 |
|---|---|---|
| JVM | ≥13，0 失败/错误/跳过 | ≥27（transport/projection 16 + LiveRules 9 + time formatting 2），0 失败/错误/跳过 |
| 模拟器 | PrototypeUiTest ≥5 | P2 instrumented tests ≥40，合成 HTTPS、ASR 草稿、通话、表情 SVG/损坏禁发、视觉路由 |
| 截图 | p1-01 至 p1-12，固定完整文件名 | p2-01 至 p2-32、p2-40 和 p2-41（共 34 张），准确文件名由 scripts/summarize_evidence.py 定义 |
| 真实 Core/真机媒体 | 分开记录 | 本地服务 ASR/TTS 与手机传感器分开验收，不用源代码或合成素材推定硬件 PASS |

P2 截图保存在 `/sdcard/Pictures/CharacterMemoryP2/`，完整文件名及必需数量以 `scripts/summarize_evidence.py` 的固定清单为准（包括最近新增的通话参考、缩小窗口和 PiP 证据）。不能使用设计图或旧提交截图填补。

CI 先运行 `python3 -m unittest discover -s scripts -p 'test_*.py' -v`，覆盖 skipped/非法与缺失 XML、阶段隔离、缺图和 SHA。Android JVM 使用两次真实 `--rerun-tasks` 执行，第二轮先清除上一轮输出；各轮 XML/JSON 保存在 `artifacts/jvm-run-1/` 和 `artifacts/jvm-run-2/`。构建任务保留 APK/JUnit/lint，模拟器保留截图/JUnit/logcat。没有 `continue-on-error` 或静默忽略必需截图的回退。

源码边界脚本允许 Live 的 INTERNET、RECORD_AUDIO；CAMERA 仅在原生 CameraCapture 存在时允许，投屏前台服务权限仅在非导出、类型为 mediaProjection 的服务存在时允许。P1 Mock 源码禁止引用网络、媒体或 Live/camera/screen 包，仍通过显式 `p1_mock=true` 启动。脚本不能替代最终 APK 合并 Manifest 或设备行为验证。

## 1. Evidence levels

| Level | Tooling | What it can prove | What it cannot prove |
|---|---|---|---|
| A. JVM | JUnit, MockWebServer, Kotlin Coroutines test | DTOs, REST errors, SSE parsing/retry/dedupe, ViewModel & CallController state machine | Android camera/mic/OS permissions |
| B. Instrumented emulator | Compose UI Test, Espresso/UI Automator, Android emulator | navigation, forms, IME, accessibility, screenshots, fake media and mocked system state | hardware thermal/battery, all MediaProjection and headset cases |
| C. Core integration | PC stack + Tailnet + Android client | real 202→SSE, character/group/Space, Vision routing, ASR/TTS API | all device/OEM background behaviors |
| D. Hardware acceptance | ADB/logcat + screen recording + real phone, user-approved capture | real Camera2, AudioRecord, MediaProjection consent, lock/rotation/network/headsets | universal compatibility across every device and OEM |

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
| CAM-CALL-02 | call video, mute, switch lens, spoken ASR turn, close/hangup | Preview fills ≥95% of call stage; no frame/send UI; real JPEG attaches once to the spoken turn, no duplicate chat POST, no periodic CAMERA observation POST; closed/stale frames cannot cross call/source ownership |
| CHAT-UI-01 | open composer tools, long-press plain character reply, explicit speech tap | Same-size image/call cards; video/share entered inside call; no ordinary TTS control before long-press, no TTS request from long-press alone; cache/error/stop behavior preserved |
| VOICE-UI-01 | persisted VOICE_MESSAGE in pending/ready/failed/unknown states | Voice-bar layout in every state; ready bar is an entire ≥176dp by ≥48dp playback target without long-press; transcript and failures retained; no invented duration or auto playback |
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

Existing P2 fixtures began at historical Core 508c6f0. Before modifying them, compare the [current Core mobile API contract](https://github.com/Initial-neko/character_memory/blob/main/docs/current/MOBILE_API_CONTRACT.md), machine-readable route inventory and source models; historical fixture provenance is not current Core compatibility evidence. P2 uses test-local synthetic JSON/SSE fixtures; the directory above is a future-stage catalog, not a claim that all files exist. Provenance and backend acceptance are maintained in [P2_SETUP.md](P2_SETUP.md). Later add automated Core-generated schema checks so docs and fixtures do not drift.

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

`LiveApiUiTest.callStageReferenceScreensPreserveOneSession` captures the normal, Live2D renderer container and shared-screen stages on an emulator. It checks unchanged call start identity/backend writes across display/history switches and speaker state across minimizing, and rejects the old static Live2D placeholder tag. Its shared frame and recording are fixtures; its synthetic Core does not provide the licensed/local runtime. This test does not certify real model rendering, MediaProjection, physical microphone, camera or PC Core integration. `CallStageTest` checks display precedence and restoration without access to call factories. `Live2dOriginPolicyTest` checks exact HTTPS origin/port enforcement and malformed URL rejection.

`LiveApiUiTest.minimizedExplicitCallHasReturnControlWithoutNewSession` verifies that the ongoing-call strip remains visible on Home, Space, Settings and character creation, showing the same character, duration and current call state. It checks unchanged start identity and recorder across navigation, live mute/reconnect updates, returning to the existing call, and removal after hangup. Screenshots `40-minimized-call-home` and `41-minimized-call-create` cover the strip outside scrolling content. Recording and transport here are fixtures; physical audio continuity requires separate device acceptance.

`Live2dRendererGpuTest.realMoc3LoadsAndProducesGpuScreenshots` is an opt-in real Core probe, which mounts only the renderer and does not create a voice session or start any capture. Install the debug app and its `app-debug-androidTest.apk`, then run against an explicit device and existing configured model:

~~~text
adb -s TARGET shell am instrument -w -e class com.charactermemory.android.Live2dRendererGpuTest -e live2d_core_url CORE_HTTPS -e live2d_character_id CHARACTER_ID com.charactermemory.android.test/androidx.test.runner.AndroidJUnitRunner
adb -s TARGET shell run-as com.charactermemory.android cat files/live2d-renderer-evidence.json
~~~

Without the two instrumentation arguments this test cannot run successfully; fixture CI excludes its Live2dRealCore annotation and records real GPU acceptance separately. It requires ready canvas, real renderer presentation context and an observed MOC3 request; then it records two actual GPU screenshots and JSON under app-private `files/live2d-gpu-probe/`. It also checks that inset resizing keeps the same presentation token, and verifies lifecycle pause/resume plus full-stage restoration, recording `inset.png`, `inset.json`, `paused.json` and `resumed.json`. Pull those files via `run-as`, review that the expected character is visible and compare the frames for actual model motion. The latest renderer snapshot is marked `destroyed:true,ready:false` after probe teardown; the saved probe JSON files retain the active render results. The frame counter is document RAF, so the automated load assertions alone cannot establish blinking, breathing, lip sync or successful motion playback. Separately verify avatar-mode teardown, model-load failure, and rapid character/Core changes. Hardware microphone/camera/projection acceptance remains independent.

The probe uses `Live2dProbeActivity` from `src/debug`, which is absent from release builds and has no voice ViewModel or capture owner. For devices that cannot run instrumentation, open this same pure-renderer activity with `adb shell am start -n com.charactermemory.android/.Live2dProbeActivity --es core_url CORE_HTTPS --es character_id CHARACTER_ID`. Send a single-top intent with `--ez inset true` or `false` to resize the existing renderer; verify unchanged presentation token and inspect real screenshots. Keep the device unlocked and the probe foreground. A locked, paused renderer with a loaded MOC3 is not a visual or animation pass. Check `nativeHeight`, `viewportHeight`, `bodyHeight`, `stageHeight` and both canvas dimensions; a zero-height canvas must never pass load acceptance.

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
