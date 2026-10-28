# VoiceDub Android (v0.4.1-alpha)

Android 10+ source project for phones **and tablets**. Initial goal: user-approved internal playback audio capture, off-line speech recognition, on-device EN/JA/ZH/DE/ES -> Russian translation, and Russian spoken translation. No root, no API key. This is a **first experimental build**, not a production-quality or tested APK.

## How to build

1. Open the folder in Android Studio (JDK 17, Android SDK 35). Sync Gradle, select app, then Build > Build APK(s).
2. Or push the contents to a new GitHub repo with default `main`, enable Actions, and run `Build VoiceDub Android APK`. The output is the `VoiceDub-debug-apk` artifact. Workflow calls installed `gradle`, no wrapper download necessary.
3. Install the generated debug APK on Android 10+ on phone or tablet.

## How to use

1. Choose the video language and download corresponding small Vosk model (approximately 40-50 MB). It is unpacked privately and can occupy hundreds of MB of RAM.
2. If you want subtitles over another app, grant Overlay permission. (Not essential for spoken translation.)
3. Start translation and grant RECORD_AUDIO and MediaProjection screen/audio capture permission.
4. When prompted Android 14+, choose **entire screen** for multi-app operation if offered. VoiceDub captures **only PCM audio**, never image/video frames.
5. Play an English/Japanese/Chinese/German/Spanish video in a compatible browser or app. Allow ML Kit to download required translation models on first use.
6. TTS must have a Russian voice installed in Android language/speech settings. Keep original volume at a comfortable level.
7. Stop from notification or app.

## Android platform limitation

AudioPlaybackCapture can capture only source playback classified as USAGE_MEDIA, USAGE_GAME or USAGE_UNKNOWN when its capture policy permits other apps, and source and target app share the same user profile. System/OEM Xiaomi AI Subtitles may be able to capture more sources because they have system privileges. Do not promise support for DRM, calls or apps that opt out. It is normal to see silent PCM from blocked sources. We do not bypass Android restrictions or DRM.

## Pipeline

MediaProjection + AudioPlaybackCapture -> AudioRecord PCM 16k mono -> Vosk small ASR model -> Google ML Kit translation model -> Android TTS Russian voice. Floating subtitle overlay optional.

## Current limitations

- The code is **not verified by an actual Android device / CI build in this environment**. Expect engineering follow-up based on build logs and test results.
- Original soundtrack remains audible. Automatic separation of music and dialogue or independent lowering of source audio is **not** implemented. You can hear original and translation at once.
- Vosk emits final segments on its end-of-utterance detection. Long speech segments can increase latency; speech/music quality varies.
- Initial language must be selected; automatic source-language identification is not yet included.
- No playback synchronization/time stretching; voice queues may lag for rapid speech. Later versions should add a bounded voice queue and overlap policy.
- Vosk model is downloaded after install; translation models via ML Kit. Offline recognition/translation works only after the models are downloaded. TTS offline availability depends on chosen Android TTS engine.
- Audio and text remain on-device in the pipeline apart from model downloads (Google ML Kit may use its documented service model delivery). No media uploading is implemented.
- Overlay not guaranteed in all Android vendor skins or protected apps.

## Source references

- Xiaomi system audio support: https://www.mi.com/global/support/faq/details/KA-565513/
- Android capture requirements: https://developer.android.com/media/platform/av-capture
- Android foreground service projection: https://developer.android.com/develop/background-work/services/fgs/service-types
- Vosk model list: https://alphacephei.com/vosk/models
- ML Kit on-device translation: https://developers.google.com/ml-kit/language/translation/android

## Diagnostics

If the PCM overlay shows `RMS=0`, check source app capturability, MediaProjection access, and audio playback. If there is a nonzero RMS and no words, check language model and speech content. If text appears but speech is missing, check downloaded ML Kit models and Android TTS Russian voices.

## Experimental browser module added in v0.2

A dedicated **BrowserActivity** with Android WebView observes visible HTML5 videos using a locally injected `assets/browser_probe.js` script. It checks open shadow roots, same-origin iframe content, site IDs (YouTube, Vimeo, VK), current play/pause position, and source type (`blob/MSE` versus direct URL). It renders a small `VoiceDub · видео обнаружено` marker near the selected video and presents metadata in the native toolbar.

The probe does NOT fetch video contents, bypass DRM, load hidden cross-origin iframe documents, transmit any information to Yandex, or synchronize translated voice yet. This is strictly detection/monitoring groundwork. Audio translation still uses the existing MediaProjection path: first start capture from the main screen, then navigate to your video in the built-in browser (experimental), or any eligible external Android browser.

The mode selector in the main activity now supports **voice**, **subtitles**, and **both**. Subtitles are the floating Android overlay (permission required); voice uses the Android Russian TTS engine. Source audio volume is currently not ducked by our app because system playback-capture permission does not grant control over another app's media volume.

**Browser compatibility**: This WebView is intended as a diagnostic/experimental embedded browser, not a replacement for Google Chrome, Opera, or Firefox. External browsers are not readable by the embedded DOM probe. Chrome for Android does not load arbitrary desktop extensions, so external browsing still uses the system-audio path. Some websites use protected video, inaccessible cross-origin iframes, or unsupported custom playback that cannot be detected.

### Test status

- `node --check app/src/main/assets/browser_probe.js`: JavaScript syntax check.
- `node --test tests/browser-probe.test.cjs`: mocked DOM tests for identity, active-player selection, dynamic Shadow DOM and removal.
- Native WebView runtime and end-to-end speech not yet verified on a phone or tablet. No Android APK has been built in this environment.
- A Chromium headless smoke test was attempted but the browser process timed out in this container; do not treat that as a passing browser test.

### Browser research references

- VOT source: https://github.com/ilyhalight/voice-over-translation (MIT licence; VoiceDub's module is independently written).
- VOT video attachment architecture: `src/bootstrap/videoObserverBinding.ts`, `src/core/videoLifecycleController.ts`, `src/core/videoManager.ts`.
- VOT audio playback: `src/videoHandler/modules/translationPlayback.ts`.
- Yandex experimental API `vot.js`: https://github.com/FOSWLY/vot.js . It is **unofficial, not yet integrated into VoiceDub**, and may require accessible media/audio and permissions.
- Android AudioPlaybackCapture API: https://developer.android.com/media/platform/av-capture
- Android WebView JavaScript evaluation and security: https://developer.android.com/reference/android/webkit/WebView

## v0.3: Timestamp-synchronized browser dubbing (new)

`app/src/main/assets/browser_dubbing.js` now attaches **an already available, user-provided, directly accessible HTTPS audio URL** to the active HTML5 video inside VoiceDub's **experimental embedded WebView browser**. It does **not** generate translated audio, obtain audio from Yandex VOT, or attach to Chrome/Opera/Firefox directly. The separate original PCM pipeline remains in place for eligible external browsers.

1. Open VoiceDub's embedded browser and load a page with a detectable HTML5 video.
2. Start the video by tapping play. Paste a **direct HTTPS URL to an existing translated MP3/M4A/WAV track** into the audio field and tap **Подключить дорожку**.
3. Tap the blue **▶ Включить озвучку** button **inside the web page**, which is required for a real browser playback gesture. The remote audio URL is not loaded until this click.
4. Test play/pause, seeking, playback-rate changes and replacing the video. The audio is attached to the *selected HTML5 element*, not to arbitrary page text or a system notification.
5. Tap **Отключить** to stop and detach the dubbed track. Changing page will discard the old media session.

Event handlers use HTMLMediaElement `play`, `playing`, `pause`, `waiting`, `seeking`, `seeked`, `ratechange`, `timeupdate`, `ended`; a 250-ms safety tick corrects drift. Below 60 ms, normal speed is restored. From 120–600 ms, audio speed is adjusted by 5% (bounded to 0.5–2.0x); above 600 ms, the audio is repositioned to the video timestamp. On seek, binding is repositioned immediately. The engine stops when the underlying video or source changes.

### Safety/known limitations

- A user-provided direct translated track is a **prerequisite** for browser sync mode. No URL extraction from protected media, DRM bypass, or undocumented Yandex service integration.
- Embedded WebView scripts execute in the page's JavaScript context. **Do not enter private token-bearing/signed audio URLs on untrusted websites**. The test browser UI is not hardened into a secure general-purpose browser.
- A website's CSP, media permissions, cross-origin restrictions, server response headers or audio codec may prevent playback. The actual browser can require an extra in-page tap after audio loads.
- If the original video is longer than the audio track, the translated track ends; there is no fabricated extension. If the dubbed track starts at a different point, future UI should expose the already supported `offsetSeconds` engine parameter.
- Automatic control of another application's volume is not included. Embedded WebView original video still plays alongside the added track.
- `AudioPlaybackCapture` and embedded WebView's two-media sync are **separate** playback paths; they are not yet unified into a single provider/session manager.

### Automated checks

- `node --test tests/*.test.cjs`: **14 deterministic tests** for video detection, attachment, trusted page click, pause/seek/speed/drift, replacement and untrusted click rejection.
- `python tests/browser-runtime-smoke.py`: optional actual Chromium check with generated data-URI audio/video (requires Python Playwright, ffmpeg and Chromium). It validates DOM media-event behavior but **does not** validate HTTPS fetching, Android WebView, real video sites or Android APK operation. The environment's headless Chromium blocks ordinary navigation, so data URI input and a test-only Audio source substitute are used.
- GitHub Actions `Build VoiceDub Android APK` now runs the JS checks before attempting the Android build. The workflow has not been executed in this container.

Official media event references: https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement and https://developer.android.com/reference/android/webkit/WebView


## v0.4: Browser subtitles + selectable text translation provider

This version connects the **existing AudioPlaybackCapture -> Vosk ASR** pipeline to real-time translation output in the experimental WebView browser, rather than requiring a prerecorded translated audio track for **all** output. A manually supplied pretranslated HTTPS audio track still supports the separate timestamp-synchronized `browser_dubbing.js` mode from v0.3.

### Operational flow (implemented, device validation pending)

1. Choose **source language**, and one of **voice**, **subtitles**, **both** on the main screen. Install the selected small Vosk model.
2. Choose **Local ML Kit** (default) or **Yandex Cloud Translate** (official REST text API). The Yandex option requires **your own Yandex Cloud API key and Folder ID**. Speech recognition stays local (Vosk) and spoken audio uses Android TTS; the Yandex provider sends recognized text only, not the original media or PCM. Yandex keys are **session-only**, never written to disk or injected into WebView.
3. Start user-approved MediaProjection audio capture; open the built-in browser or supported external browser.
4. Vosk final speech segments are translated by the chosen `TranslationEngine`. Output mode is applied **once** inside AudioCaptureService. For subtitle modes an app-scoped broadcast carries text and monotonically increasing `session` / `sequence`; the built-in browser renders it with DOM `textContent`, with no HTML interpolation. Native overlay displays captions above external browsers when overlay permission is granted. The embedded browser also has an **in-app native fullscreen caption layer** that remains above WebView fullscreen video without SYSTEM_ALERT_WINDOW permission.
5. In voice/both modes, translation is read by Android TTS. A bounded queue drops obsolete chunks in favor of the latest text. This is **approximate live commentary**, not sentence-level video-lip synchronization.
6. If a Yandex request fails, the service attempts to initialize on-device ML Kit and retries the affected segment. This fallback requires the model to be available/downloadable; it is not guaranteed offline without pre-downloading ML Kit models.

**Correct expectations:** On a browser showing a real video, incoming live captions are placed against the *current visible video playback position on receipt* and expire after 6 seconds or when paused, seeking or replacing sources. Their timestamp is **not** the original utterance start time. TTS is not synchronized to HTML5 media yet, and an external browser cannot be inspected by the built-in WebView. A cloud text API is NOT the Yandex VOT neural dubbing API: there is **no automatic VOT dubbed-track retrieval in v0.4**.

### Security and platform notes

- Internal caption events use a signature-scoped permission and non-exported dynamic receiver on Android 13+; messages contain only text, session and sequence. No credentials are broadcast.
- The Yandex Cloud API key lives in memory only, passed to an app-private service, and is cleared from the UI after launch. For distributing an app at scale, use a trusted backend proxy to protect a centrally managed service key instead.
- Native WebView executes injected JavaScript in the website context; captions are not confidential from the page. Do not use untrusted pages for private translations. Unsupported iframe/DRM media is not bypassed.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` is declared for Android 14+ compatibility. Overlay permission is optional for in-WebView subtitles and mandatory only for captions on top of unrelated applications.
- No audio/video upload in the Local mode (aside from model downloads). Yandex Cloud mode sends recognized speech **as text** to Yandex.

### Verification

- `node --check app/src/main/assets/browser_{probe,dubbing,captions}.js` (expand brace notation in shells without brace support).
- `node --test tests/*.test.cjs`: **18/18 passed**, including 4 new caption tests (HTML injection safety, pause/seek/source change, duplicate rejection).
- `python tests/browser-runtime-smoke.py`: passed in headless Chromium using test-only data URI media.
- **Not performed**: Android SDK / Gradle compilation, actual APK installation, real WebView playback capture, live Vosk -> ML Kit / Yandex Cloud tests with real audio and credentials. Do not conflate these with the passing JS tests.

Official Yandex Translate API: https://aistudio.yandex.ru/en/docs/translate/operations/translate
Android 14+ mediaProjection FGS: https://developer.android.com/about/versions/14/changes/fgs-types-required

## v0.4.1: Playback-aware live translation and build preflight

**See `RELEASE-0.4.1.md` for the current verification and remaining APK gate.**

The embedded HTML5 browser now forwards only a small playback state (playing/paused/seeking/source fingerprint) through an app-internal signature-protected broadcast. AudioCaptureService discards queued translation and stops TTS if the user pauses, seeks or switches video, preventing stale speech. This does **not** perform precise real-time lip sync or affect other browsers' audio capture when the embedded browser is inactive.

Run locally without Android SDK:

```bash
python3 scripts/check_project.py
node --test tests/*.test.cjs
python3 tests/browser-runtime-smoke.py
kotlinc app/src/main/java/com/voicedub/app/BrowserPlaybackGate.kt tests/kotlin/BrowserPlaybackGateSmoke.kt -include-runtime -d /tmp/voice-gate-test.jar
java -jar /tmp/voice-gate-test.jar
```

Android SDK 35 + Gradle 8.9 + JDK 17 required to build (as configured in `.github/workflows/android-debug.yml`). GitHub CI runs Android unit tests and `:app:assembleDebug`. This archive contains **source code only** and not a verified APK.
