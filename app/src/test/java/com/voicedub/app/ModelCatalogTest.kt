package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun resolvesRegionalTagsWithoutGuessingUnknownLanguages() {
        assertEquals("pt", ModelCatalog.findByLanguageTag("pt-BR")?.code)
        assertEquals("zh", ModelCatalog.findByLanguageTag("ZH_hans")?.code)
        assertEquals("vi", ModelCatalog.findByLanguageTag("vi-VN")?.code)
        assertNull(ModelCatalog.findByLanguageTag("auto"))
        assertNull(ModelCatalog.findByLanguageTag("kk-KZ"))
        assertNull(ModelCatalog.findByLanguageTag("en/../../x"))
        assertNull(ModelCatalog.findByLanguageTag(null))
    }

    @Test fun catalogEntriesHaveUniqueCodesAndSafeModelNames() {
        val models = ModelCatalog.entries
        assertEquals(models.size, models.map { it.code }.toSet().size)
        assertEquals(models.size, models.map { it.filename }.toSet().size)
        models.forEach {
            assertTrue(it.code.matches(Regex("[a-z]{2,3}")))
            assertTrue(it.filename.matches(Regex("vosk-model-small-[a-z0-9.-]+")))
            assertSame(it, ModelCatalog.findByLanguageTag(it.code))
        }
    }
}
