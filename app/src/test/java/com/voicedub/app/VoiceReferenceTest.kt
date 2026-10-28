package com.voicedub.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceReferenceTest {
    private val chunk = ByteArray(32000) { if (it % 2 == 0) 0 else 8 }
    @Test fun silenceIsNotAReference() {
        val r = VoiceReference()
        repeat(10) { r.append(ByteArray(32000), 32000) }
        assertNull(r.wav())
    }
    @Test fun referenceIsBoundedAndCleared() {
        val r = VoiceReference()
        repeat(2) { r.append(chunk, chunk.size) }
        assertNull(r.wav())
        repeat(12) { r.append(chunk, chunk.size) }
        val wav = r.wav()!!
        assertEquals(44 + 8 * 32000, wav.size)
        assertEquals(8 * 32000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        r.clear()
        assertNull(r.wav())
    }
    @Test fun endpointPolicy() {
        assertEquals("http://192.168.1.10:8765", VoiceEndpoint.normalize("http://192.168.1.10:8765/"))
        assertEquals("https://example.com", VoiceEndpoint.normalize("https://example.com"))
        listOf("http://example.com", "http://192.168.1.10.evil.com", "http://169.254.169.254", "https://user:pass@example.com", "https://example.com/?secret=x").forEach {
            try { VoiceEndpoint.normalize(it); fail(it) } catch (_: IllegalArgumentException) { }
        }
    }
}
