package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class VideoTextPolicyTest {
    @Test fun mapsScriptExplicitlyWithoutPretendingToDetectLanguage() {
        assertEquals(VideoTextScript.LATIN, VideoTextPolicy.scriptFor("en-US"))
        assertEquals(VideoTextScript.JAPANESE, VideoTextPolicy.scriptFor("ja"))
        assertEquals(VideoTextScript.CHINESE, VideoTextPolicy.scriptFor("zh-CN"))
        assertEquals(VideoTextScript.KOREAN, VideoTextPolicy.scriptFor("ko"))
        assertEquals(VideoTextScript.DEVANAGARI, VideoTextPolicy.scriptFor("hi-IN"))
        assertNull(VideoTextPolicy.scriptFor("ru"))
        assertNull(VideoTextPolicy.scriptFor("ar"))
        assertNull(VideoTextPolicy.scriptFor("gu"))
        assertNull(VideoTextPolicy.scriptFor("auto"))
    }

    @Test fun filtersUiMarkersNoiseAndLongLines() {
        assertEquals("Hello world!", VideoTextPolicy.cleanLine("  Hello   world!\n"))
        assertEquals("こんにちは", VideoTextPolicy.cleanLine("こんにちは"))
        assertNull(VideoTextPolicy.cleanLine("12345"))
        assertNull(VideoTextPolicy.cleanLine("VoiceDub · видео обнаружено"))
        assertNull(VideoTextPolicy.cleanLine("x".repeat(161)))
        assertNull(VideoTextPolicy.cleanLine(""))
    }

    @Test fun capturedFramesAreBoundedAndNotUpscaled() {
        assertEquals(Pair(960, 540), VideoTextPolicy.maxSampleSize(1920, 1080))
        assertEquals(Pair(320, 180), VideoTextPolicy.maxSampleSize(320, 180))
        assertEquals(Pair(405, 720), VideoTextPolicy.maxSampleSize(1080, 1920))
    }
}
