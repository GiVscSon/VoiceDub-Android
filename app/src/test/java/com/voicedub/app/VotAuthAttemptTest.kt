package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class VotAuthAttemptTest {
    @Test fun challengeMatchesRfc7636Vector() {
        val attempt = VotAuthAttempt.forTest("state", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", attempt.challenge)
    }
    @Test fun acceptsOnlyMatchingCallbackOnce() {
        val a = VotAuthAttempt.forTest("expected", "verifier")
        assertNull(a.consumeCallback("https://evil.example/verification_code?state=expected&code=abc"))
        assertNull(a.consumeCallback("https://oauth.yandex.ru/verification_code?state=wrong&code=abc"))
        assertNull(a.consumeCallback("https://oauth.yandex.ru/verification_code?state=expected&state=expected&code=abc"))
        assertNull(a.consumeCallback("https://oauth.yandex.ru/verification_code?state=expected&code=abc&code=def"))
        assertEquals("abc", a.consumeCallback("https://oauth.yandex.ru/verification_code?state=expected&code=abc"))
        assertNull(a.consumeCallback("https://oauth.yandex.ru/verification_code?state=expected&code=abc"))
    }
    @Test fun freshAttemptsHaveDifferentSecrets() {
        val a = VotAuthAttempt.create()
        val b = VotAuthAttempt.create()
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.verifier, b.verifier)
        assertEquals(43, a.verifier.length)
    }
}
