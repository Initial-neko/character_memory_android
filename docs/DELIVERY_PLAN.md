# Android V1 交付计划

Status: **V1 chat/voice/sticker/visual source integrated; full candidate CI and hardware acceptance remain required**. Historical results only prove their own commits. Current connection boundaries are maintained in [P2_SETUP.md](P2_SETUP.md). Camera uses Android Camera2 without a new capture framework; native preview/rotation/permissions must pass device acceptance.

## 0. Product scope

**Included**: character/direct chat, AI character draft/confirm, ensemble prepare/research/retry/confirm, group chat, Space feed/comments, ImageGen drafts/send, voice call ASR + TTS, camera keyframes, authorized Android screen capture/periodic observations, minimal local connection/voice/vision preferences.

**Excluded**: complete PC Settings/Dev/TTS Workbench, API keys and provider controls, on-device PersonRuntime or SQLite, AI video streaming at 30 fps, automatic hidden screen recording, permanent background push guarantee, virtual avatar animation (V2).

## 1. Stage gates

| Phase | Core changes | Android changes | Required evidence |
|---|---|---|---|
| P0 — Contract | Publish implemented endpoint inventory; agree direct conversation ID migration, pairing scopes and typed error handling | Pin Core contract; JSON/SSE fixtures; HTTP client tests | Contract docs reviewed, route fixtures verified against Core |
| P1 — Bootstrap | Minimal dev/mock fixture data | Kotlin+Compose shell, theme, six UI surfaces, status page, mock client, Gradle CI | Debug APK builds, six screenshot baselines, emulator navigation tests |
| P2 — Product | Consume existing Core APIs; Core #213 migration excluded | Chat+SSE+reconcile, character draft/create, ensemble, groups, Space, ImageGen | JVM + emulator fixture evidence; real PC 202→SSE→history separately accepted by user |
| P3 — Voice | No new ASR/TTS business logic required | Native mic record, VAD/segmenting, ASR request, TTS queue, speaker/audio focus, call state | Fixed WAV fixture + real headset/mic playback and cancel tests |
| P4 — Vision | Reuse existing bounded visual routes; freeze server and direct target during background share | Camera2 preview/switch/confirmed frame; MediaProjection prompt/FGS; keyframes/limiter | Real phone correct frame to correct character, permissions/stop/lock/network scenarios |
| P5 — Acceptance | No regression to PC WebUI | Performance+crash/security/notification/release polish | CI reports, hardware logcat + screen record, signed candidate APK, explicit test matrix |

Use **small independently reviewable PRs**, not a single giant all-features PR. Backend changes go to `character_memory`; Android implementation to `character_memory_android`. Cross-repo dependency must link the related Core PR and contract revision.

## 2. Milestones / first three PRs

1. **Core contract publication**: human-readable contract and source path inventory, distinguish existing vs proposed. This is documentation only; it does not implement device authentication or canonical direct IDs.
2. **Android repository bootstrap (delivered by merged PR #7)**: README, prototype/architecture, Kotlin+Compose app, offline screen flows and CI evidence. Remaining fix-ups tracked by #8.
3. **First end-to-end text conversation**: PC stack+tailnet online → Android sends text → HTTP 202 → SSE character event → history reconciles. This is the first runnable product gate.

Parallel spike in P1: MediaProjection prompt → ImageReader image → local JPEG → existing Vision API on a *real Android device*; keep it an isolated experiment until P4 and do not falsely claim permanent screen sharing without consent.

## 3. PR acceptance contract

Every implementation PR should report:

- `./gradlew lintDebug testDebugUnitTest assembleDebug` result (or concrete reason not yet available);
- contract fixtures updated when relevant;
- emulator UI smoke + screenshot artifacts for changed screens;
- real-device result **PASS / FAIL / NOT RUN** for Mic, Camera, MediaProjection, audio focus and background claims;
- observed regressions in PC WebUI/Core (if touched);
- installable debug APK artifact for implementation PRs once Gradle exists.

**Do not** mark a milestone complete from code inspection or build success alone when its hardware path is unverified.

## 4. Core and Android change protocol

- Core owns path/method/schema/status code/realtime semantics; changes land there with compatibility tests.
- Android always consumes **deployed** Core revisions, not undocumented assumptions.
- For breaking changes: explicit schema version/migration plan and dual-client test before removal of any field.
- Documentation updates are part of Done: Core source + Core mobile contract + Android fixtures/architecture where behavior changed.
- No implicit production release/tag until real-device acceptance.

## 5. Initial issues suggested for development

P0: document/verify existing route fixtures; canonical direct-conversation decision; device credential/security RFC.  
P1: Gradle + Compose bootstrap, six primary screens + local settings / additional mock surfaces and CI evidence **done**. MockWebServer/API fixture tests **moved to P2**, because P1 has no network stack.  
P2: Direct 202/SSE/history; character wizard; ensemble lifecycle; group history; Space feed/comments; chat AI Image.  
P3: CallController + audio turn queue; ASR and TTS binary playback; interruption tests.  
P4: Camera2 preview and switches; Android MediaProjection authorization/FGS; bounded frame selection; Vision observation.  
P5: offline/reconnect, notification scope, telemetry, power and memory, API compatibility, release checklist.

Do not create production feature issues with claimed acceptance until the corresponding stage is ready for implementation.
