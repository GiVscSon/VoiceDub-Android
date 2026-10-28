package com.voicedub.app

/**
 * Local translation stays active until a prepared browser dubbing track
 * actually starts playing. Merely attaching a URL is not a successful handoff.
 */
internal class DubHandoffGate {
    private var completed = false

    /** True exactly once, when the translated browser audio is really playing. */
    fun onState(state: String?): Boolean {
        if (completed || state != "playing") return false
        completed = true
        return true
    }
}
