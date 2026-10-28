package com.voicedub.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubHandoffGateTest {
    @Test fun attachedTrackMustNotInterruptLocalTranslation() {
        val gate = DubHandoffGate()
        for (state in listOf(null, "armed", "loading", "gesture-required", "paused", "audio-error")) {
            assertFalse("State $state is not real playback", gate.onState(state))
        }
    }

    @Test fun realPlaybackStopsLocalOnlyOnce() {
        val gate = DubHandoffGate()
        assertFalse(gate.onState("armed"))
        assertTrue(gate.onState("playing"))
        assertFalse(gate.onState("playing"))
        assertFalse(gate.onState("audio-error"))
    }
}
