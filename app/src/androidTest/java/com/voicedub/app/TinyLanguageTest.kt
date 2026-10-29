package com.voicedub.app

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class TinyLanguageTest {
    @Test fun actualWhisperModelIdentifiesPublicSpeechLocally() {
        assertTrue("Run this test with the Tiny flavor", LocalSpeechDetector.available)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = TinyLanguageAssets.directory(context).apply { mkdirs() }
        for (name in listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")) {
            instrumentation.context.assets.open("lid/$name").use { input -> directory.resolve(name).outputStream().use { input.copyTo(it) } }
        }
        directory.resolve("verified").writeText(TinyLanguageAssets.REVISION)
        assertTrue(TinyLanguageAssets.installed(context))
        val started = SystemClock.elapsedRealtime()
        val detector = LocalSpeechDetector.open(context)
        val loadMs = SystemClock.elapsedRealtime() - started
        val metrics = JSONObject().put("loadMs", loadMs)
        val runs = JSONArray(); metrics.put("runs", runs)
        try {
            assertNull(detector.detect(FloatArray(6 * 16000)))
            for ((name, expected) in listOf("de-german.wav" to "de", "ar-arabic.wav" to "ar")) {
                val data = instrumentation.context.assets.open("lid/$name").use { it.readBytes() }
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(16000, buffer.getInt(24))
                val samples = FloatArray((data.size - 44) / 2) { buffer.getShort(44 + it * 2) / 32768f }
                val time = SystemClock.elapsedRealtime()
                val language = detector.detect(samples)
                val elapsed = SystemClock.elapsedRealtime() - time
                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                runs.put(JSONObject().put("language", language).put("inferenceMs", elapsed).put("pssKb", memory.totalPss))
                context.filesDir.resolve("tiny-metrics.json").writeText(metrics.toString())
                println("TINY_METRICS language=$language loadMs=$loadMs inferenceMs=$elapsed pssKb=${memory.totalPss}")
                assertEquals("Whisper language mismatch for $name", expected, language)
            }
        } finally { detector.close() }
    }
}
