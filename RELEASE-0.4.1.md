# VoiceDub Android 0.4.1-alpha — build readiness patch

## Changes

- Fixed duplicate `FOREGROUND_SERVICE_MEDIA_PROJECTION` declaration in AndroidManifest.xml.
- Added app-internal playback state bridge from embedded WebView to `AudioCaptureService`, scoped with the existing signature-only permission.
- The service now stops Russian TTS and discards stale ASR/translation queue on pause, seek, video source changes or exiting the embedded browser. In-flight translation callbacks with an old epoch cannot speak after the video changes.
- External Chrome, Opera, Firefox and other audio capture remain independent when the VoiceDub WebView is not showing a player.
- Added a source fingerprint and `seeking` to the HTML video snapshot; browser poll interval is 450 ms instead of 1100 ms. No raw media URLs are sent in the internal playback broadcasts.
- Throttled Vosk model download progress updates to changed percentages.
- Added pure Kotlin playback policy smoke tests, Android Gradle unit tests (JUnit4), XML permission preflight and CI hooks.

## Verification in this environment

PASS: `python3 scripts/check_project.py` (AndroidManifest parse + duplicate permissions + required assets and CI)
PASS: `node --check app/src/main/assets/browser_{probe,dubbing,captions}.js` (run each file separately)
PASS: `node --test tests/*.test.cjs` — 18/18
PASS: `kotlinc ... BrowserPlaybackGate.kt ... BrowserPlaybackGateSmoke.kt -include-runtime -d /tmp/voice-gate-test.jar && java -jar /tmp/voice-gate-test.jar` — 11 assertions
PASS: `python3 tests/browser-runtime-smoke.py` — Chromium HTML5 data URI media tests
NOT RUN: `gradle :app:testDebugUnitTest :app:assembleDebug` — this container does not have Android SDK/Gradle and cannot fetch them due to network DNS failure
NOT RUN: installation and audio capture on a real phone/tablet
NOT RUN: live Yandex Cloud with a valid API key or unofficial Yandex VOT automatic audio retrieval

## How to obtain an APK on GitHub

Create a **separate** `GiVscSon/VoiceDub-Android` repository (do not put this code into `LocalPilot-Android` or `LOWTOWN`), upload this project's contents to `main` including `.github/workflows/android-debug.yml`, then open GitHub → Actions → "Build VoiceDub Android APK" → Run workflow. If the CI build succeeds, download the `VoiceDub-debug-apk` artifact and extract `app-debug.apk`.

No APK is bundled in this source archive. Do not mistake the zipped source for an installable build.

## Device matrix after APK generation

1. Android 16 phone (POCO X7 Pro): request RECORD_AUDIO and MediaProjection, download the Vosk model, play an unprotected browser video in a source browser that opts into audio capture, observe RMS nonzero, subtitle and Russian voice.
2. Tablet: repeat in both portrait and landscape; try the embedded WebView with accessible HTML5 video and fullscreen subtitles.
3. Embedded browser pause/seek/source replacement: verify voice stops or discards old text within one polling interval plus broadcast latency. Verify resumes after play.
4. Chrome/Opera: external playback unaffected by embedded browser gate.
5. Yandex Cloud: test only with user's own key, confirm failure falls back to ML Kit when translation model available.

## Known limitations

Browser synchronization still concerns *live TTS queue invalidation*, not word-accurate speech/video timestamp matching. System AudioPlaybackCapture is subject to source app capture policy, Android permission, and user consent. Built-in browser is not hardened for trusted credential-bearing browsing. The official Yandex Translate API returns translated text, not Yandex Browser's original dubbed track.
