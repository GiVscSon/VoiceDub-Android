package com.voicedub.app

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Direct connection to the user-selected voice server. No redirects or credentials. */
class CloneSpeechClient(endpoint: String) {
    private val base = VoiceEndpoint.normalize(endpoint)
    private val executor = Executors.newSingleThreadExecutor()
    private val connection = AtomicReference<HttpURLConnection?>()
    private val generation = AtomicLong()
    fun synthesize(text: String, reference: ByteArray, done: (Result<ByteArray>) -> Unit) {
        val epoch = generation.get()
        executor.execute {
            done(runCatching {
                check(generation.get() == epoch) { "Запрос отменён" }
                val conn = URL("$base/v1/synthesize").openConnection() as HttpURLConnection
                connection.set(conn)
                try {
                    check(generation.get() == epoch) { "Запрос отменён" }
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 5000
                    conn.readTimeout = 90000
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val body = JSONObject().put("text", text.take(1000)).put("language", "ru")
                        .put("reference_wav", Base64.encodeToString(reference, Base64.NO_WRAP))
                        .toString().toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                    check(conn.responseCode == 200) { "Сервер голоса: HTTP ${conn.responseCode}" }
                    val data = conn.inputStream.use { it.readBytesBounded(16 * 1024 * 1024) }
                    check(data.size > 44 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(data, 8, 4, Charsets.US_ASCII) == "WAVE") { "Сервер не вернул WAV" }
                    data
                } finally { connection.compareAndSet(conn, null); conn.disconnect() }
            })
        }
    }
    fun cancel() { generation.incrementAndGet(); connection.getAndSet(null)?.disconnect() }
    fun close() { cancel(); executor.shutdownNow() }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        check(out.size() + n <= limit) { "Ответ сервера слишком большой" }
        out.write(buffer, 0, n)
    }
}
