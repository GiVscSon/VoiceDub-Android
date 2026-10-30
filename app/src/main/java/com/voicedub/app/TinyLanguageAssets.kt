package com.voicedub.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object TinyLanguageAssets {
    const val REVISION = "65176e2deb88badc814a94058666cadccc29b61c"
    private data class Weight(val name: String, val bytes: Long, val sha: String)
    private val weights = listOf(
        Weight("tiny-encoder.int8.onnx", 12937772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
        Weight("tiny-decoder.int8.onnx", 89855401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"))
    fun directory(context: Context) = File(context.filesDir, "speech-lid-whisper-tiny")
    fun installed(context: Context): Boolean {
        val dir = directory(context)
        return runCatching { dir.resolve("verified").takeIf { it.isFile }?.readText() == REVISION &&
            weights.all { dir.resolve(it.name).length() == it.bytes } }.getOrDefault(false)
    }
    fun install(context: Context, progress: (String) -> Unit) {
        if (installed(context)) return
        val target = directory(context)
        val staging = File(context.filesDir, ".speech-lid-installing")
        staging.deleteRecursively(); check(staging.mkdirs())
        try {
            weights.forEach { weight ->
                progress("Whisper tiny: ${weight.name}…")
                val c = URL("https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$REVISION/${weight.name}").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                    require(c.responseCode == 200) { "Загрузка модели: HTTP ${c.responseCode}" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    c.inputStream.use { input -> staging.resolve(weight.name).outputStream().use { out ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            count += n; require(count <= weight.bytes) { "Неверный размер модели" }
                            digest.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    } }
                    require(count == weight.bytes && digest.digest().joinToString("") { "%02x".format(it) } == weight.sha) { "Модель не прошла проверку SHA-256" }
                } finally { c.disconnect() }
            }
            staging.resolve("verified").writeText(REVISION)
            target.deleteRecursively(); check(staging.renameTo(target))
        } finally { staging.deleteRecursively() }
    }
}
