# Android repository agent rules

Read [README.md](README.md), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/DELIVERY_PLAN.md](docs/DELIVERY_PLAN.md) and [docs/TESTING.md](docs/TESTING.md) **before** implementing a milestone. Read the authoritative Core-side [Android/mobile API contract](https://github.com/Initial-neko/character_memory/blob/main/docs/current/MOBILE_API_CONTRACT.md).

## Non-negotiable boundaries
- This repo is an Android *client*; PersonRuntime, Memory/SQLite, Group/Space autonomies, AI model selection and data facts stay in the PC Core.
- Never invent an endpoint and mark it implemented merely because it appears in a design. The Core contract distinguishes CURRENT from PROPOSED; use the deployed Core OpenAPI/source/fixture as implementation evidence.
- Tailnet HTTPS is private transport, not Android authentication. Never disable TLS validation or expose PC-only Settings/Dev/Secrets routes to mobile to work around missing endpoints.
- No unauthorized or hidden screen capture; MediaProjection requires OS permission and lifecycle management.
- Keep a small, testable PR footprint. If a consumer needs a new Core endpoint, link a Core issue/PR; do not implement a mobile-only phantom API.
- Preserve existing WebUI/Character Core behavior when proposing cross-repo changes.

## Definition of Done
- For changes in code, run available static checks, Gradle build/unit tests and relevant emulator tests; attach evidence.
- For screen changes, capture actual emulator screenshots, compare against the agreed UX, and record overflows, IME layout and accessibility results.
- For camera, audio, headset, foreground service, MediaProjection and network switching, use a real Android device; report NOT RUN when hardware is unavailable, not PASS.
- For SSE, test 202 Accepted, waiting/typing, character event, silence/error, history reconciliation, reconnect and event deduplication.
- Keep code, Core contract references, Android fixture definitions and affected docs synchronized. Do not add a second API document that diverges from the Core authoritative contract.
- Do not create an APK release or merge stages on the basis of mock results alone; stage PR requires acceptance evidence specified in [TESTING.md](docs/TESTING.md).

## Recommended workflow
1. Read current stage issue and relevant Core contract revision.
2. Propose a minimal vertical slice; implement + fixtures + unit tests.
3. Run emulator tests and export screenshots.
4. Perform Core integration and real hardware checks as relevant.
5. Link CI artifacts/screenshots/logcat, state known limitations and hand over for acceptance.
