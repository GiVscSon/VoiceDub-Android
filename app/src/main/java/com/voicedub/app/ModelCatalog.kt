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
        SpeechModel("Английский", "en", "vosk-model-small-en-us-0.15"),
        SpeechModel("Русский", "ru", "vosk-model-small-ru-0.22"),
        SpeechModel("Китайский", "zh", "vosk-model-small-cn-0.22"),
        SpeechModel("Японский", "ja", "vosk-model-small-ja-0.22"),
        SpeechModel("Немецкий", "de", "vosk-model-small-de-0.15"),
        SpeechModel("Испанский", "es", "vosk-model-small-es-0.42"),
        SpeechModel("Французский", "fr", "vosk-model-small-fr-0.22"),
        SpeechModel("Итальянский", "it", "vosk-model-small-it-0.22"),
        SpeechModel("Португальский (Бразилия)", "pt", "vosk-model-small-pt-0.3"),
        SpeechModel("Турецкий", "tr", "vosk-model-small-tr-0.3"),
        SpeechModel("Хинди", "hi", "vosk-model-small-hi-0.22"),
        SpeechModel("Вьетнамский", "vi", "vosk-model-small-vn-0.4"),
        SpeechModel("Нидерландский", "nl", "vosk-model-small-nl-0.22"),
        SpeechModel("Украинский", "uk", "vosk-model-small-uk-v3-nano"),
        SpeechModel("Польский", "pl", "vosk-model-small-pl-0.22"),
        SpeechModel("Корейский", "ko", "vosk-model-small-ko-0.22"),
        SpeechModel("Персидский", "fa", "vosk-model-small-fa-0.5"),
        SpeechModel("Грузинский", "ka", "vosk-model-small-ka-0.42"),
        SpeechModel("Гуджарати", "gu", "vosk-model-small-gu-0.42")
    )
}
