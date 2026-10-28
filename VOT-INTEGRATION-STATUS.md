# VOT 1.12.3 integration status

The user supplied vot.user.js, SHA-256 0a18fd7d08302e3f340d8cbcce4caef604681dbcb334f1b3d42aa913c3b4652c, MIT-licensed. It uses Yandex OAuth authorization code with PKCE, state, and oauth.yandex.ru/verification_code. Its OAuth token is supplied only for lively-voice requests; translation additionally requires VOT session and protobuf requests, processing and playback synchronization. A login alone is not translation.

VotAuthAttempt implements and unit-tests local PKCE and strict one-use callback validation; it is not wired to a UI, token exchange or VOT. No credentials are collected. Do not inject privileged userscript APIs into untrusted pages. Remaining: design Android-compatible callback for the existing third-party OAuth client and validate access/redirect; implement isolated token handling, session protocol, request/response and synced media; obtain user consent and test on an actual device. Existing local mode remains available.
