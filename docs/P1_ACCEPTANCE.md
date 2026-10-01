# P1 原型验收基线

> Status: implementation pending CI; screenshots are only evidence when captured from a real running emulator. Generated design PNG/SVG is not actual app execution proof.

## Scope frozen

- Kotlin Compose, six product screens + compact Settings; no real Core/Media connectivity.
- Deterministic Mock roster, messages, ensemble members, Space entries.
- Interactive: home navigation, text draft/send into **local-only** memory, person/group preview, call demo toggles, local like, preference toggles.
- Explicit MOCK label on every screen; NO internet, microphone, camera or MediaProjection permission in AndroidManifest.
- V2 virtual avatar excluded.

## One command (after Android SDK + Gradle 8.9 installed)

~~~bash
gradle --no-daemon lintDebug testDebugUnitTest assembleDebug
~~~

Instrumented emulator test:

~~~bash
gradle --no-daemon connectedDebugAndroidTest
~~~

P1 CI records `app-debug.apk`, XML test results, lint, screenshot files and JSON metrics. No local Gradle wrapper JAR is shipped at this bootstrap stage: CI installs pinned Gradle 8.9 and the next verified Gradle generation PR can check in a standard Gradle wrapper.

## Quality gates

| Metric | Required | Evidence |
|---|---:|---|
| Debug compile | PASS | APK + build logs |
| JVM tests | ≥10, 0 failures | JUnit XML |
| UI tests | ≥3, 0 failures | instrumentation XML |
| Actual emulator screenshots | ≥7 | 01-home through 07-settings PNG |
| Six-screen navigation | PASS | UI semantic tags, no coordinate-only taps |
| Static lint | PASS | Android lint report |
| Real MediaProjection | NOT RUN in P1 | not implemented |
| Core API integration | NOT RUN in P1 | Mock-only |
| Original PNG design asset | pending Issue #6 | must not be claimed uploaded |

**Never** count `NOT RUN` as a pass. The evidence summarizer exits non-zero when test XML or required screenshots are missing.

## Review checklist

- [ ] Main landing displays stable character/group rows with readable labels.
- [ ] Character creation requires description and presents visibly **mock** preview.
- [ ] Group creation requires prompt, preview never silently creates group.
- [ ] Chat send appends local **not-sent** item; no AI is faked.
- [ ] Call shows subtitles and demo status, no OS permissions requested.
- [ ] Space scrolls and likes change visually, never modify real server.
- [ ] Small-screen landscape/IME overlap reviewed from emulator captures.
- [ ] Dark theme color contrast and minimum tappable control sizes reviewed.
- [ ] Actual PNG original compared after assets are uploaded.

Do not merge P1 just because this document exists.
