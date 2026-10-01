# Android V1 运行架构

Status: **design / no Android implementation yet**. This document is a consumer design, not evidence of implemented Android functionality.

- API fact source: [Core-owned Mobile API Contract](https://github.com/Initial-neko/character_memory/blob/main/docs/current/MOBILE_API_CONTRACT.md)
- PC mobile deployment: [Core Mobile Access](https://github.com/Initial-neko/character_memory/blob/main/docs/current/MOBILE_ACCESS.md)
- Product screenshots: [Six screens](assets/android-v1-six-screens.svg)

## 1. Boundary

~~~mermaid
flowchart LR
  A["Android App<br/>Compose UI / Media Capture"] -->|Tailnet HTTPS :443| C["PC Character Runtime<br/>:8000<br/>PersonRuntime / Chat / Group / Space / Vision / ImageGen"]
  A -->|Tailnet HTTPS :8443| M["PC Media Runtime<br/>:8001<br/>ASR / TTS"]
  C --> S[("PC SQLite + Media Assets")]
~~~

One authoritative Character Core; Android does not run PersonRuntime/Memory, access SQLite, or host local models.

| Android owns | PC Core owns |
|---|---|
| Compose UI and navigation | Personality / reactions / memories |
| Client API + SSE connection | Persistent chat / group / Space events |
| Local cached preferences and scoped credentials | Group autonomy, World scheduler |
| Camera / microphone / screen input | ASR/TTS service endpoints, Vision, image generation |
| Android permission and foreground service lifecycle | Validation, quotas, media storage |
| User-visible connect and capture controls | Durable source of truth |

## 2. Android project structure — proposed

Initially **one Gradle app module, feature-based packages**. Split into separate Gradle modules only when a real build/ownership need appears.

~~~text
app/src/main/java/.../
  core/
    network/      CoreApi, MediaApi, SSE, errors, DTOs
    storage/      DataStore / secret handle
    design/       Compose theme and components
  feature/
    chat/         direct history / 202 / SSE / read display
    character/    draft / create / preview
    group/        ensemble prepare / confirm / group chat
    space/        feed pagination / comments / media
    image/        rewrite / generate / user-confirmed send
    call/         turn queue, ASR/TTS orchestration and controls
    settings/     network/media/theme/notifications
  media/
    camera/       CameraX preview + analysis
    screen/       MediaProjection + foreground service
    voice/        AudioRecord, playback, focus
    visual/       bounded keyframe selection / local diff
  device/
    pairing/      proposed credential + revocation
    connection/   health / offline / backoff
    notification/ foreground and, later, push
~~~

Strict ownership: MediaCapture can produce Frame objects but cannot choose character/memory policies. CallController chooses target and invokes Core. No native UI component calls a private Python module.

## 3. Core and Media network

Current Serve mapping is **two origins**, not a single mobile gateway:

~~~text
https://<node>.<tailnet>.ts.net         → CORE / 127.0.0.1:8000
https://<node>.<tailnet>.ts.net:8443    → MEDIA / 127.0.0.1:8001
~~~

Phone needs the Tailscale app connected to the same tailnet. Don't set `localhost` as the Android base; localhost on the phone is the phone.

First-run device pairing and scopes are **planned Core work**; do not ship a UI that claims pairing works before those routes exist. Tailnet access alone is a network trust boundary, not a substitute for app authorization. HTTPS certificate validation must remain enabled; no permissive trust-all client.

## 4. Business request lifecycle

~~~mermaid
sequenceDiagram
  participant UI as Android UI
  participant API as Core :8000
  participant Worker as PersonRuntime
  UI->>API: POST /v1/chat/messages
  API-->>UI: 202 accepted + persisted user event
  API->>Worker: enqueue reaction
  API-->>UI: SSE reaction_status (queued/typing)
  Worker->>API: persisted character event
  API-->>UI: SSE character_event or reaction_error / idle
  UI->>API: GET /v1/chat/history-page on (re)connect
  API-->>UI: durable history for reconciliation
~~~

Direct channel = `character_id + conversation_id`. Web currently stores some direct conversation IDs in localStorage, so cross-client consistency is **pending Core API migration**, not solved by Android choosing new UUIDs.

## 5. Mobile media capture and playback

~~~text
Mic → AudioRecord → PCM16/WAV → MEDIA /v1/asr → text
    → CORE /v1/chat/messages (or visual message) → SSE result
    → MEDIA /v1/tts → bytes → native player

CameraX / MediaProjection → local preview/consented capture
   → low-cost change filter → bounded JPEG keyframes
   → CORE /v1/visual/direct/messages or /observations
   → PersonRuntime via existing scheduler
~~~

- Keep only latest available analysis frame; cap in-flight requests and memory buffers; release image proxies and bitmaps.
- Camera preview is user-facing; LLM receives only selected frames, **not 30 fps video**.
- Display capture must be actively started and authorized by the user. Android 14+ permission/token requirements and mediaProjection foreground service lifecycle must be tested on the target OS.
- Stop means stop capture, stop foreground notification, release projection, cancel uploads and clean native resources; do not silently restart screen recording.
- Direct screen observation is supported by the current API only for DISPLAY. Camera periodic observation, group periodic observation and a formal device visual session endpoint are not current commitments.
- The Voice UI's "phone call" is a client-controlled microphone → ASR → Core message/reaction → TTS pipeline, not WebRTC peer-to-peer video.
- Background voice, Bluetooth/headset focus and screen captures need genuine hardware verification; an emulator/MockWebServer alone cannot certify them.

## 6. Minimal connection state

~~~text
UNPAIRED (future) → CONNECTING → ONLINE
                           ↓         ↓
                         OFFLINE ← DISCONNECTED
                           ↓
                    RETRYING (bounded backoff)
~~~

Text chat continues when Media Runtime is down; media control indicates degradation. On SSE reconnection reconcile durable messages. Do not conflate "network connected" with "model produced a reply".

## 7. UI surfaces and future avatar

V1 surfaces: Chat list, Direct Chat, one-click Character, one-click Ensemble, Voice/Video Call, Space Feed, basic Settings. AI image generator appears in the chat composer. PC-only Dev/TTS Workbench/Secrets are not Android destinations.

V2 avatar (Live2D/animated/3D) belongs exclusively in the **presentation layer**. Use speaker/playing/idle/capture states already emitted by CallController and introduce animation adapters later; don't add a second personality engine.
