package com.voicedub.app

/** User-facing output selection. All modes share the same ASR and translation pipeline. */
enum class OutputMode(val label: String) {
    VOICE("Только озвучка"),
    SUBTITLES("Только субтитры"),
    BOTH("Озвучка + субтитры");

    companion object {
        fun parse(value: String?): OutputMode = entries.firstOrNull { it.name == value } ?: BOTH
    }
}
