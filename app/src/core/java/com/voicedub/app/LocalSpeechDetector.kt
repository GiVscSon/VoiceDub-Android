package com.voicedub.app

import android.content.Context
object LocalSpeechDetector {
    const val available = false
    fun open(context: Context): SpeechLanguageDetector = throw IllegalStateException("Нужна сборка VoiceDub Tiny")
}
