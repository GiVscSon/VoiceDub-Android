package com.voicedub.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads a selected Vosk model into private app storage. Never executes archive contents. */
object ModelInstaller {
    fun install(context: Context, model: SpeechModel, progress: (String) -> Unit) {
        val root = File(context.filesDir, "models").apply { mkdirs() }
        val target = model.directory(context.filesDir)
        if (File(target, "am/final.mdl").exists()) return
        val archive = File(context.cacheDir, "${model.filename}.zip")
        val tempRoot = File(root, ".${model.filename}.installing")
        tempRoot.deleteRecursively()
        tempRoot.mkdirs()
        try {
            progress("Скачивание ${model.label}…")
            val conn = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                connect()
            }
            try {
                require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                val expected = conn.contentLengthLong
                require(expected <= 150_000_000 || expected == -1L) { "Архив слишком большой" }
                conn.inputStream.use { source ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var downloaded = 0L
                        var lastProgressPercent = -1L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            downloaded += n
                            require(downloaded <= 150_000_000) { "Превышен лимит скачивания" }
                            output.write(buffer, 0, n)
                            if (expected > 0) {
                                val percent = (downloaded * 100 / expected).coerceIn(0, 100)
                                if (percent != lastProgressPercent) {
                                    lastProgressPercent = percent
                                    progress("Загрузка: $percent%")
                                }
                            }
                        }
                    }
                }
                require(expected < 0 || archive.length() == expected) { "Загрузка прервалась: размер архива не совпадает" }
            } finally { conn.disconnect() }
            progress("Распаковка языковой модели…")
            var expanded = 0L
            var entries = 0
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries < 5000) { "Слишком много файлов в архиве" }
                    val prefix = "${model.filename}/"
                    require(entry.name.startsWith(prefix)) { "Неожиданная структура архива" }
                    val relative = entry.name.removePrefix(prefix)
                    if (relative.isNotEmpty()) {
                        val output = File(tempRoot, relative)
                        require(output.canonicalPath.startsWith(tempRoot.canonicalPath + File.separator)) {
                            "Недопустимый путь в архиве"
                        }
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile?.mkdirs()
                            output.outputStream().buffered().use { dest ->
                                val bytes = ByteArray(32768)
                                while (true) {
                                    val n = zip.read(bytes)
                                    if (n < 0) break
                                    expanded += n
                                    require(expanded <= 650_000_000) { "Модель слишком большая" }
                                    dest.write(bytes, 0, n)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(tempRoot, "am/final.mdl").isFile) { "Файлы модели неполные" }
            target.deleteRecursively()
            check(tempRoot.renameTo(target)) { "Не удалось перенести модель" }
            progress("Модель готова")
        } finally {
            archive.delete()
            tempRoot.deleteRecursively()
        }
    }
}
