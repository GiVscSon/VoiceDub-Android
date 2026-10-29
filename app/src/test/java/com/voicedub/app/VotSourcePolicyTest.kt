package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VotSourcePolicyTest {
    @Test fun urlsRequireExactPublicCdnSuffixAndHttps() {
        assertTrue(VotSourcePolicy.allowedUrl("https://r1.googlevideo.com/videoplayback?mime=audio%2Fwebm&sig=test"))
        assertTrue(VotSourcePolicy.allowedUrl("https://a.vimeocdn.com/audio.mp4"))
        listOf("http://r1.googlevideo.com/a", "https://googlevideo.com.evil.org/a", "https://127.0.0.1/a",
            "https://user@r1.googlevideo.com/a", "https://r1.googlevideo.com:8443/a", "file:///tmp/x", "blob:https://example.com/x")
            .forEach { assertFalse(it, VotSourcePolicy.allowedUrl(it)) }
    }
    @Test fun chunksHaveNoEmptyFinalPartAndRoundTripBytes() {
        val n = VotSourcePolicy.CHUNK_BYTES
        assertEquals(1, VotSourcePolicy.parts(n.toLong()))
        assertEquals(2, VotSourcePolicy.parts(n + 1L))
        val file = File.createTempFile("vot-source-test-", ".audio")
        try {
            val data = ByteArray(n + 43) { (it % 251).toByte() }
            file.writeBytes(data)
            assertArrayEquals(data, VotSourcePolicy.readPart(file, 0) + VotSourcePolicy.readPart(file, 1))
            assertEquals(43, VotSourcePolicy.readPart(file, 1).size)
        } finally { file.delete() }
    }
    @Test fun invalidSizesAreRejected() {
        for (size in listOf(0L, -1L, VotSourcePolicy.MAX_BYTES + 1)) {
            try { VotSourcePolicy.parts(size); fail("Accepted $size") } catch (_: IllegalArgumentException) { }
        }
    }
}
