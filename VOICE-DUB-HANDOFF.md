# VoiceDub Android handoff, v0.4.1-alpha (28 October 2026)

Worktree `VoiceDub-Android-v0.4.1` is derived from previous v0.4.0-alpha with browser playback state gating, manifest fix and improved CI unit tests. See `RELEASE-0.4.1.md` for details, actual tests passed, and blockers.

## Current task priority

1. Commit the source into its own `GiVscSon/VoiceDub-Android` repo (not LOWTOWN / LocalPilot Android); GitHub connector currently has no create-repo action, and no VoiceDub repo was found. No remote changes have been made.
2. Run `.github/workflows/android-debug.yml` and inspect first Android compile error or confirm upload artifact `VoiceDub-debug-apk`.
3. Fix any Kotlin/AGP problems based on actual CI logs. Do not claim Kotlin Android compilation is complete before the workflow passes.
4. Install the actual APK on POCO X7 Pro and an Android tablet. Test user-approved internal PCM capture, ASR, ML Kit, browser captions and TTS.
5. Continue Yandex Cloud credentialed integration and optional VOT integration without embedding service credentials or bypassing protected media.

## Verifications completed locally

- Manifest duplicate permission removed and static preflight PASS.
- 18/18 Node browser tests PASS.
- Browser HTML5 Chromium smoke PASS with data URI test media (not real remote HTTPS).
- Pure Kotlin playback state machine built and executed: 11 assertions PASS.
- JUnit unit tests have been added but NOT run because Android SDK/Gradle unavailable.
- No APK produced or installed.

## Design constraints

Android 10+ phone/tablet; offline local source ASR / ML Kit translation / Android TTS by default; Yandex Cloud text API optional with user-supplied key; output selectable between captions / voice / both. System audio capture is subject to Android capture policies; must not claim support for all video apps. Do not conflate manually attached HTTPS dubbed tracks with automated VOT retrieval.
