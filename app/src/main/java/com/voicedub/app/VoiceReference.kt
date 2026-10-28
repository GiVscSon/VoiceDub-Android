package com.voicedub.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URI

/** Bounded, session-only reference. Energy filtering is not speaker diarization or music removal. */
class VoiceReference {
    private val pcm = ByteArrayOutputStream()
    @Synchronized fun clear() { pcm.reset() }
    @Synchronized fun append(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size && length % 2 == 0)
        if (length == 0 || pcm.size() >= 8 * 16000 * 2) return
        var power = 0.0
        for (i in 0 until length step 2) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 255)).toShort().toDouble()
            power += sample * sample
        }
        if (power / (length / 2) < 100.0 * 100.0) return
        pcm.write(bytes, 0, minOf(length, 8 * 16000 * 2 - pcm.size()))
    }
    @Synchronized fun wav(): ByteArray? {
        if (pcm.size() < 3 * 16000 * 2) return null
        val data = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(data.size + 36).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.size)
        return header.array() + data
    }
}

/** HTTP allowed only to explicit private IPv4/loopback; public endpoints require HTTPS. */
object VoiceEndpoint {
    fun normalize(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        val host = uri.host ?: throw IllegalArgumentException("Укажите адрес сервера")
        require(uri.scheme == "https" || (uri.scheme == "http" && isPrivate(host))) {
            "Для HTTP нужен локальный IP (например, 192.168.1.10); для внешнего сервера — HTTPS"
        }
        require(uri.port == -1 || uri.port in 1..65535)
        return uri.toString().trimEnd('/')
    }
    private fun isPrivate(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return false
        val p = parts.map { it.toIntOrNull() ?: return false }
        if (p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }
}
