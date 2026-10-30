package com.voicedub.app

import android.content.Context
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig

object LocalSpeechDetector {
    const val available = true
    fun open(context: Context): SpeechLanguageDetector {
        check(TinyLanguageAssets.installed(context)) { "Сначала скачайте модель определения языка" }
        val dir = TinyLanguageAssets.directory(context)
        val engine = SpokenLanguageIdentification(config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = dir.resolve("tiny-encoder.int8.onnx").absolutePath,
                decoder = dir.resolve("tiny-decoder.int8.onnx").absolutePath),
            numThreads = 2, debug = false, provider = "cpu"))
        return object : SpeechLanguageDetector {
            override fun detect(samples: FloatArray): String? {
                if (samples.size < 4 * 16000 || kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size) < 150.0 / 32768) return null
                fun identify(data: FloatArray): String {
                    val stream = engine.createStream()
                    try { stream.acceptWaveform(data, 16000); return engine.compute(stream) }
                    finally { stream.release() }
                }
                // The API has no confidence score: require agreement between two windows.
                val middle = samples.size / 2
                val a = identify(samples.copyOfRange(0, middle))
                val b = identify(samples.copyOfRange(middle, samples.size))
                return if (a == b && TranslationLanguages.resolve(a) != null) a else null
            }
            override fun close() = engine.release()
        }
    }
}
