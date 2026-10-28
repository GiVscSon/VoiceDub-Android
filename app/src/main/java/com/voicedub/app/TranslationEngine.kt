package com.voicedub.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Text translation only. ASR and speech output stay separate from the selected backend. */
interface TranslationEngine {
    fun prepare(done: (Result<Unit>) -> Unit)
    fun translate(text: String, done: (Result<String>) -> Unit)
    fun close()
}

/** On-device ML Kit model after its first download. */
class LocalTranslationEngine(source: String) : TranslationEngine {
    private val client: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )

    override fun prepare(done: (Result<Unit>) -> Unit) {
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { done(Result.success(Unit)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    override fun translate(text: String, done: (Result<String>) -> Unit) {
        client.translate(text)
            .addOnSuccessListener { done(Result.success(it)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    override fun close() = client.close()
}

/** Official Yandex Cloud text Translate API. API key is session-only, never stored. */
class YandexCloudTranslationEngine(
    private val source: String,
    private val apiKey: String,
    private val folderId: String
) : TranslationEngine {
    companion object {
        const val ENDPOINT = "https://translate.api.cloud.yandex.net/translate/v2/translate"
    }
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "voicedub-yandex-translate").apply { isDaemon = true }
    }

    override fun prepare(done: (Result<Unit>) -> Unit) {
        if (apiKey.isBlank() || folderId.isBlank()) {
            done(Result.failure(IllegalArgumentException("Укажите API-ключ и Folder ID Yandex Cloud")))
        } else done(Result.success(Unit))
    }

    override fun translate(text: String, done: (Result<String>) -> Unit) {
        if (text.isBlank() || text.length > 10000) {
            done(Result.failure(IllegalArgumentException("Текст не должен быть пустым или длиннее 10000 символов")))
            return
        }
        try {
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    val activeConnection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 12000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Authorization", "Api-Key $apiKey")
                    }
                    connection = activeConnection
                    val body = JSONObject().apply {
                        put("folderId", folderId)
                        put("sourceLanguageCode", source)
                        put("targetLanguageCode", "ru")
                        put("texts", JSONArray().put(text))
                    }.toString()
                    activeConnection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = activeConnection.responseCode
                    if (code !in 200..299) throw IllegalStateException("Yandex Cloud HTTP $code")
                    val response = activeConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val result = JSONObject(response).getJSONArray("translations").getJSONObject(0).getString("text")
                    done(Result.success(result))
                } catch (error: Exception) {
                    // Never include the request, auth header, or server response in UI diagnostics.
                    done(Result.failure(IllegalStateException(error.message ?: "Ошибка Yandex Cloud")))
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            done(Result.failure(IllegalStateException("Сеанс перевода завершён")))
        }
    }

    override fun close() { executor.shutdownNow() }
}

/** Broadcast is scoped to our package; carries subtitles, never API credentials. */
object SubtitleEvents {
    const val PERMISSION = "com.voicedub.app.permission.INTERNAL_SUBTITLES"
    const val ACTION = "com.voicedub.app.INTERNAL_SUBTITLE"
    const val ORIGINAL = "original"
    const val TRANSLATED = "translated"
    const val SESSION = "session"
    const val SEQUENCE = "sequence"
}
