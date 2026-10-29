package com.voicedub.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A complete encoded source track, never PCM captured from part of a playing video. */
object VotSourcePolicy {
    const val MAX_BYTES = 80L * 1024 * 1024
    // @vot.js/shared 3.2.0 config.minChunkSize; all but the last chunk have this size.
    const val CHUNK_BYTES = 5_295_308
    fun allowedUrl(value: String): Boolean = try {
        val u = URI(value)
        val h = u.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        u.scheme == "https" && u.userInfo == null && u.fragment == null &&
            (u.port == -1 || u.port == 443) && value.length <= 16384 &&
            listOf("googlevideo.com", "vimeocdn.com").any { h == it || h.endsWith(".$it") }
    } catch (_: Exception) { false }

    fun parts(size: Long): Int {
        require(size in 1..MAX_BYTES) { "Размер исходной дорожки вне лимита 80 МБ" }
        return ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
    }
    fun readPart(file: File, part: Int): ByteArray {
        val size = file.length()
        require(part in 0 until parts(size))
        val offset = part.toLong() * CHUNK_BYTES
        val bytes = ByteArray(minOf(CHUNK_BYTES.toLong(), size - offset).toInt())
        RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) }
        return bytes
    }
}

/** Private, bounded staging. No cookies, OAuth headers, URL logging or executable content. */
class VotSourceLoader(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    fun fromUrl(url: String, done: (Result<File>) -> Unit) = prepare(done) { token ->
        require(VotSourcePolicy.allowedUrl(url)) { "Нет доступной прямой аудиодорожки" }
        var current = url
        var response: HttpURLConnection? = null
        for (hop in 0..3) {
            check(token == epoch.get() && !closed)
            require(VotSourcePolicy.allowedUrl(current)) { "Перенаправление на неподдерживаемый адрес" }
            val c = URL(current).openConnection() as HttpURLConnection
            connection = c
            c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 15000
            c.setRequestProperty("Accept-Encoding", "identity")
            val status = try { c.responseCode } catch (e: Exception) { c.disconnect(); connection = null; throw e }
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                require(hop < 3 && location != null) { "Слишком много перенаправлений" }
                current = URI(current).resolve(location).toString()
            } else {
                response = c
                break
            }
        }
        val c = checkNotNull(response)
        try {
            require(c.responseCode == 200) { "Исходная дорожка недоступна (HTTP ${c.responseCode})" }
            val type = c.contentType.orEmpty().substringBefore(';').lowercase(java.util.Locale.ROOT)
            require(type.startsWith("audio/") || type == "application/octet-stream") { "Источник не вернул аудиофайл" }
            val expected = c.contentLengthLong
            require(expected == -1L || expected in 1..VotSourcePolicy.MAX_BYTES) { "Дорожка превышает лимит 80 МБ" }
            c.inputStream.use { stage(it, token, expected) }
        } finally { c.disconnect(); connection = null }
    }

    fun fromDocument(uri: Uri, done: (Result<File>) -> Unit) = prepare(done) { token ->
        require(uri.scheme == "content") { "Выберите аудиофайл через системный диалог" }
        context.contentResolver.openInputStream(uri).use { stage(checkNotNull(it), token, -1) }
    }

    private fun stage(input: InputStream, token: Long, expected: Long): File {
        val file = File.createTempFile("vot-source-", ".audio", context.cacheDir)
        try {
            var size = 0L
            file.outputStream().use { out ->
                val buffer = ByteArray(32768)
                while (true) {
                    check(!closed && token == epoch.get())
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= VotSourcePolicy.MAX_BYTES) { "Дорожка превышает лимит 80 МБ" }
                    out.write(buffer, 0, n)
                }
            }
            VotSourcePolicy.parts(size)
            require(expected < 0 || expected == size) { "Исходная дорожка загружена не полностью" }
            val prefix = file.inputStream().use { stream -> ByteArray(12).also { require(stream.read(it) == it.size) { "Аудиофайл слишком короткий" } } }
            val signature = String(prefix, Charsets.ISO_8859_1)
            require(signature.startsWith("RIFF") || signature.startsWith("OggS") || signature.startsWith("fLaC") ||
                signature.startsWith("ID3") || signature.substring(4, 8) == "ftyp" ||
                (prefix[0].toInt() and 255 == 0xff && prefix[1].toInt() and 0xe0 == 0xe0) ||
                prefix.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte())) {
                "Неизвестный формат аудиофайла"
            }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun prepare(done: (Result<File>) -> Unit, block: (Long) -> File) {
        cancel()
        if (closed) return
        val token = epoch.get()
        executor.execute {
            val result = try { Result.success(block(token)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                if (!closed && token == epoch.get()) done(result)
                else result.getOrNull()?.delete()
            }
        }
    }
    fun cancel() { epoch.incrementAndGet(); connection?.disconnect() }
    fun close() { closed = true; cancel(); executor.shutdownNow() }
}
