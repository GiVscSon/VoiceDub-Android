package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class VotPolicyTest {
    @Test fun onlyExactYandexEndpointsAllowed() {
        assertTrue(VotPolicy.allowed("https://api.browser.yandex.ru/session/create"))
        assertTrue(VotPolicy.allowed("https://api.browser.yandex.ru/video-translation/translate"))
        listOf("http://api.browser.yandex.ru/session/create", "https://api.browser.yandex.ru.evil.com/session/create",
            "https://user@api.browser.yandex.ru/session/create", "https://api.browser.yandex.ru/session/create?token=x",
            "https://api.browser.yandex.ru/unknown").forEach { assertFalse(it, VotPolicy.allowed(it)) }
    }
    @Test fun uploadAllowsOnlyPutToTheAudioEndpoint() {
        assertTrue(VotPolicy.allowed("https://api.browser.yandex.ru/video-translation/audio", "PUT"))
        assertFalse(VotPolicy.allowed("https://api.browser.yandex.ru/video-translation/audio", "POST"))
        assertFalse(VotPolicy.allowed("https://api.browser.yandex.ru/session/create", "PUT"))
        assertFalse(VotPolicy.allowed("https://api.browser.yandex.ru/video-translation/fail-audio-js", "PUT"))
    }
    @Test fun votLanguagesAreIndependentFromTheLocalCatalog() {
        assertTrue(VotPolicy.supportsLanguage("auto"))
        assertTrue(VotPolicy.supportsLanguage("ar"))
        assertFalse(VotPolicy.supportsLanguage("pt-BR"))
        assertFalse(VotPolicy.supportsLanguage("gu"))
    }
    @Test fun onlyCanonicalVideoUrls() {
        assertEquals("https://youtu.be/abc_def-123", VotPolicy.video("youtube:abc_def-123")!!.first)
        assertNull(VotPolicy.video("youtube:abc/../../x"))
        assertNull(VotPolicy.video("page:https://example.com"))
        assertEquals("https://vimeo.com/12345", VotPolicy.video("vimeo:12345")!!.first)
    }
}
