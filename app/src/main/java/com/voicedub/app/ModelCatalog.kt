package com.voicedub.app

import java.io.File

/** Small offline ASR models published at alphacephei.com/vosk/models (Apache-2.0). */
data class SpeechModel(val label: String, val code: String, val filename: String) {
    val downloadUrl get() = "https://alphacephei.com/vosk/models/$filename.zip"
    fun directory(root: File) = File(root, "models/$filename")
}

object ModelCatalog {
    /** Resolve regional BCP-47 tags to an available speech model without guessing. */
    fun findByLanguageTag(tag: String?): SpeechModel? {
        val normalized = tag?.trim()?.replace('_', '-') ?: return null
        if (!normalized.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*"))) return null
        val language = normalized.substringBefore('-').lowercase(java.util.Locale.ROOT)
        return entries.firstOrNull { it.code == language }
    }

    val entries = listOf(
        SpeechModel("Английский → русский", "en", "vosk-model-small-en-us-0.15"),
        SpeechModel("Китайский → русский", "zh", "vosk-model-small-cn-0.22"),
        SpeechModel("Японский → русский", "ja", "vosk-model-small-ja-0.22"),
        SpeechModel("Немецкий → русский", "de", "vosk-model-small-de-0.15"),
        SpeechModel("Испанский → русский", "es", "vosk-model-small-es-0.42"),
        SpeechModel("Французский → русский", "fr", "vosk-model-small-fr-0.22"),
        SpeechModel("Итальянский → русский", "it", "vosk-model-small-it-0.22"),
        SpeechModel("Португальский (Бразилия) → русский", "pt", "vosk-model-small-pt-0.3"),
        SpeechModel("Турецкий → русский", "tr", "vosk-model-small-tr-0.3"),
        SpeechModel("Хинди → русский", "hi", "vosk-model-small-hi-0.22"),
        SpeechModel("Вьетнамский → русский", "vi", "vosk-model-small-vn-0.4"),
        SpeechModel("Нидерландский → русский", "nl", "vosk-model-small-nl-0.22"),
        SpeechModel("Украинский → русский", "uk", "vosk-model-small-uk-v3-nano"),
        SpeechModel("Польский → русский", "pl", "vosk-model-small-pl-0.22"),
        SpeechModel("Корейский → русский", "ko", "vosk-model-small-ko-0.22"),
        SpeechModel("Персидский → русский", "fa", "vosk-model-small-fa-0.5"),
        SpeechModel("Грузинский → русский", "ka", "vosk-model-small-ka-0.42"),
        SpeechModel("Гуджарати → русский", "gu", "vosk-model-small-gu-0.42")
    )
}
