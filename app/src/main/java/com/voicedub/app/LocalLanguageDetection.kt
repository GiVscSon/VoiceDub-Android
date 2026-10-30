package com.voicedub.app

import java.io.ByteArrayOutputStream

interface SpeechLanguageDetector : AutoCloseable {
    fun detect(samples: FloatArray): String?
}

/** Energy gate, not neural VAD. Keep a bounded voiced window and reject silence. */
class LanguageSampleWindow {
    private val voiced = ByteArrayOutputStream()
    val ready get() = voiced.size() >= 6 * 16000 * 2
    fun append(pcm: ByteArray, length: Int) {
        require(length in 0..pcm.size && length % 2 == 0)
        if (ready || length == 0) return
        var sum = 0.0
        for (i in 0 until length step 2) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toDouble()
            sum += s * s
        }
        if (kotlin.math.sqrt(sum / (length / 2)) < 150) return
        voiced.write(pcm, 0, minOf(length, 6 * 16000 * 2 - voiced.size()))
    }
    fun samples(): FloatArray {
        val data = voiced.toByteArray()
        return FloatArray(data.size / 2) { i ->
            (((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 255)).toShort().toFloat() / 32768f)
        }
    }
    fun clear() = voiced.reset()
}
