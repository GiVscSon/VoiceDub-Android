package com.voicedub.app

/** Pure state machine: a WebView's playback controls may invalidate queued live TTS. */
data class BrowserPlaybackState(
    val found: Boolean,
    val paused: Boolean,
    val seeking: Boolean,
    val key: String
)

data class PlaybackDecision(val cancelQueuedSpeech: Boolean, val allowNewSpeech: Boolean)

class BrowserPlaybackGate {
    private var previous: BrowserPlaybackState? = null

    fun update(next: BrowserPlaybackState): PlaybackDecision {
        val old = previous
        previous = next
        val cancel = (old?.found == true && (
            !next.found || old.key != next.key || next.paused || next.seeking
        )) || (next.found && (next.paused || next.seeking) && old?.found != true)
        return PlaybackDecision(cancel, allowsSpeech())
    }

    /** If the internal browser isn't presenting a player, external app capture is unaffected. */
    fun allowsSpeech(): Boolean = previous?.let { !it.found || (!it.paused && !it.seeking) } ?: true

    fun reset(): PlaybackDecision = update(BrowserPlaybackState(false, true, false, ""))
}

/** Signature-protected, app-internal browser events: no media URL or user credentials. */
object BrowserPlaybackEvents {
    const val ACTION = "com.voicedub.app.INTERNAL_BROWSER_PLAYBACK"
    const val FOUND = "found"
    const val PAUSED = "paused"
    const val SEEKING = "seeking"
    const val PLAYER_KEY = "playerKey"
}
