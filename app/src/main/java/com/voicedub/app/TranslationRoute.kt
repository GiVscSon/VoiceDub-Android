package com.voicedub.app

/** One route shared by text providers and speech output; never infer it from UI positions. */
class TranslationRoute private constructor(val source: String, val target: String) {
    val isIdentity get() = source == target
    val supportsMoss get() = TranslationLanguages.supportsMoss(target)
    companion object {
        fun create(source: String, target: String = "ru") = TranslationRoute(
            TranslationLanguages.resolve(source)?.code ?: throw IllegalArgumentException("Исходный язык не поддерживается"),
            TranslationLanguages.resolve(target)?.code ?: throw IllegalArgumentException("Язык перевода не поддерживается")
        )
    }
}

data class TranslationLanguage(val code: String, val label: String)
object TranslationLanguages {
    // These target tags are supported by ML Kit. ASR models remain a separate catalog.
    val entries = listOf(
        TranslationLanguage("ru", "Русский"), TranslationLanguage("en", "Английский"),
        TranslationLanguage("zh", "Китайский"), TranslationLanguage("ja", "Японский"),
        TranslationLanguage("de", "Немецкий"), TranslationLanguage("es", "Испанский"),
        TranslationLanguage("fr", "Французский"), TranslationLanguage("it", "Итальянский"),
        TranslationLanguage("pt", "Португальский"), TranslationLanguage("tr", "Турецкий"),
        TranslationLanguage("hi", "Хинди"), TranslationLanguage("vi", "Вьетнамский"),
        TranslationLanguage("nl", "Нидерландский"), TranslationLanguage("uk", "Украинский"),
        TranslationLanguage("pl", "Польский"), TranslationLanguage("ko", "Корейский"),
        TranslationLanguage("fa", "Персидский"), TranslationLanguage("ka", "Грузинский"),
        TranslationLanguage("gu", "Гуджарати"), TranslationLanguage("ar", "Арабский")
    )
    fun supportsMoss(tag: String) = resolve(tag)?.code == "ru"
    fun resolve(tag: String?): TranslationLanguage? {
        val value = tag?.trim()?.replace('_', '-') ?: return null
        if (!value.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*"))) return null
        val code = value.substringBefore('-').lowercase(java.util.Locale.ROOT)
        return entries.firstOrNull { it.code == code }
    }
}
