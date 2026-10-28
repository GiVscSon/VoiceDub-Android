package com.voicedub.app

import org.junit.Assert.*
import org.junit.Test

class BrowserPlaybackGateTest {
    @Test fun pauseCancelsPendingSpeech() {
        val gate = BrowserPlaybackGate()
        gate.update(BrowserPlaybackState(true, false, false, "video-a"))
        val pause = gate.update(BrowserPlaybackState(true, true, false, "video-a"))
        assertTrue(pause.cancelQueuedSpeech)
        assertFalse(pause.allowNewSpeech)
    }

    @Test fun seekingAndVideoReplacementCancelStaleSpeech() {
        val gate = BrowserPlaybackGate()
        gate.update(BrowserPlaybackState(true, false, false, "video-a"))
        assertTrue(gate.update(BrowserPlaybackState(true, false, true, "video-a")).cancelQueuedSpeech)
        assertTrue(gate.update(BrowserPlaybackState(true, false, false, "video-b")).cancelQueuedSpeech)
        assertTrue(gate.allowsSpeech())
    }

    @Test fun externalBrowserModeIsNotBlocked() {
        val gate = BrowserPlaybackGate()
        assertTrue(gate.allowsSpeech())
        gate.update(BrowserPlaybackState(true, true, false, "video-a"))
        assertFalse(gate.allowsSpeech())
        assertTrue(gate.reset().allowNewSpeech)
    }

    @Test fun stablePlaybackDoesNotCancelVoice() {
        val gate = BrowserPlaybackGate()
        gate.update(BrowserPlaybackState(true, false, false, "video-a"))
        val repeated = gate.update(BrowserPlaybackState(true, false, false, "video-a"))
        assertFalse(repeated.cancelQueuedSpeech)
        assertTrue(repeated.allowNewSpeech)
    }
}
