# Isolated VOT protocol runtime

This uses @vot.js/core 3.2.0 (MIT) from FOSWLY/vot.js. It is not the full userscript, has no telemetry, and is loaded only in a private native WebView that blocks all page network loads. Native transport permits exactly two HTTPS Yandex endpoints and adds an account token only to the translation request. The video site's WebView has no native bridge. No original PCM is uploaded in this mode.

Rebuild the committed asset: `npm ci --ignore-scripts && npm run build`. The public VOT OAuth client and callback are taken from the user's VOT 1.12.3 script; actual service acceptance must be tested by the user. A mocked denial test and local bundle initialization test are not proof of live Yandex translation.
