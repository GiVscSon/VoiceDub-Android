package com.voicedub.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** State and PKCE for an experimental VOT login. No token storage or UI yet. */
class VotAuthAttempt private constructor(val state: String, val verifier: String) {
    private var consumed = false
    val challenge: String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    /** Accept only the exact Yandex callback, a single matching state and a single code. */
    @Synchronized fun consumeCallback(url: String): String? {
        if (consumed) return null
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.host != "oauth.yandex.ru" ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443) ||
            uri.path != "/verification_code" || uri.fragment != null) return null
        val params = try {
            (uri.rawQuery ?: return null).split("&").map {
                val pair = it.split("=", limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
        } catch (_: Exception) { return null }
        val states = params.filter { it.first == "state" }.map { it.second }
        val codes = params.filter { it.first == "code" }.map { it.second }
        if (states.size != 1 || codes.size != 1 || states[0] != state || codes[0].isBlank()) return null
        consumed = true
        return codes[0]
    }

    companion object {
        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun create(random: SecureRandom = SecureRandom()): VotAuthAttempt {
            val stateBytes = ByteArray(32).also(random::nextBytes)
            val verifierBytes = ByteArray(32).also(random::nextBytes)
            return VotAuthAttempt(encode(stateBytes), encode(verifierBytes))
        }
        internal fun forTest(state: String, verifier: String) = VotAuthAttempt(state, verifier)
    }
}
