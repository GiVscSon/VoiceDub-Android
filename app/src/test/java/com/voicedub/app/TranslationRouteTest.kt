package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class TranslationRouteTest {
    @Test fun regionalTagsKeepTheChosenDirection() {
        val route = TranslationRoute.create("pt-BR", "en-US")
        assertEquals("pt", route.source); assertEquals("en", route.target)
        assertFalse(route.supportsMoss); assertFalse(route.isIdentity)
        assertTrue(TranslationRoute.create("de", "ru").supportsMoss)
        assertTrue(TranslationRoute.create("en", "en").isIdentity)
    }
    @Test fun unknownTargetsFailInsteadOfSilentlyTranslatingIntoRussian() {
        try { TranslationRoute.create("en", "not-a-language"); fail("Unknown target accepted") } catch (_: IllegalArgumentException) { }
        assertNull(TranslationLanguages.resolve("auto"))
        assertEquals(TranslationLanguages.entries.size, TranslationLanguages.entries.map { it.code }.toSet().size)
    }
    @Test fun languageWindowRejectsSilenceAndBoundsStoredSamples() {
        val window = LanguageSampleWindow()
        window.append(ByteArray(6 * 16000 * 2), 6 * 16000 * 2)
        assertFalse(window.ready)
        val voiced = ByteArray(7 * 16000 * 2) { if (it % 2 == 0) 0xe8.toByte() else 3 }
        window.append(voiced, voiced.size)
        assertTrue(window.ready)
        assertEquals(6 * 16000, window.samples().size)
        assertTrue(window.samples().all { it in -1f..1f })
        window.clear(); assertFalse(window.ready)
    }
}
