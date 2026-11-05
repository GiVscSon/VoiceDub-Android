package com.voicedub.app

import java.util.Locale

/** OCR recognizers are script-specific. This is NOT automatic script detection. */
enum class VideoTextScript { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

object VideoTextPolicy {
    fun scriptFor(languageTag: String?): VideoTextScript? = when (
        languageTag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
    ) {
        "en", "de", "es", "fr", "it", "pt", "tr", "vi", "nl", "pl" -> VideoTextScript.LATIN
        "zh" -> VideoTextScript.CHINESE
        "ja" -> VideoTextScript.JAPANESE
        "ko" -> VideoTextScript.KOREAN
        "hi" -> VideoTextScript.DEVANAGARI
        // ML Kit Text Recognition v2 has no bundled recognizers for these scripts.
        else -> null
    }

    fun cleanLine(raw: String): String? {
        val line = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (line.length !in 2..160 || line.none(Char::isLetter)) return null
        // Ignore our own browser marker, which would otherwise repeatedly enter OCR.
        if (line.startsWith("VoiceDub", ignoreCase = true)) return null
        return line
    }

    fun maxSampleSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1f, 960f / width, 720f / height)
        return Pair(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    }
}
