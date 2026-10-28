package com.voicedub.app

/** Runs without Android SDK: kotlinc BrowserPlaybackGate.kt this-file -include-runtime -d /tmp/voice-gate.jar */
fun main() {
    val gate = BrowserPlaybackGate()
    fun state(found: Boolean, paused: Boolean, seeking: Boolean, key: String) =
        BrowserPlaybackState(found, paused, seeking, key)
    check(gate.allowsSpeech()) { "Unattached external browser must remain enabled" }
    check(gate.update(state(true, false, false, "videoA")).allowNewSpeech)
    check(!gate.update(state(true, false, false, "videoA")).cancelQueuedSpeech)
    check(gate.update(state(true, true, false, "videoA")).cancelQueuedSpeech)
    check(!gate.allowsSpeech()) { "Paused player must block stale speech" }
    check(gate.update(state(true, false, false, "videoA")).allowNewSpeech)
    check(gate.update(state(true, false, true, "videoA")).cancelQueuedSpeech)
    check(!gate.allowsSpeech()) { "Seeking must block stale speech" }
    check(gate.update(state(true, false, false, "videoB")).cancelQueuedSpeech)
    check(gate.update(state(false, true, false, "")).cancelQueuedSpeech)
    check(gate.allowsSpeech()) { "Leaving browser must permit external browsers" }
    println("PASS: BrowserPlaybackGate: 11 assertions")
}
