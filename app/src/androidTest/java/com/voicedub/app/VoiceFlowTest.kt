package com.voicedub.app

import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceFlowTest {
    @Test fun screenWorksInPortraitAndLandscapeAndCloneNeedsOptIn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun find(v: View): CheckBox? {
                    if (v is CheckBox && v.text.toString().startsWith("Сохранять")) return v
                    if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                    return null
                }
                val box = find(activity.window.decorView)
                assertNotNull(box)
                assertFalse(box!!.isChecked)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun androidHttpClientAndMediaPlayerUseWavFixture() {
        val endpoint = InstrumentationRegistry.getArguments().getString("voiceEndpoint")
        assumeNotNull(endpoint)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reference = instrumentation.context.assets.open("reference-16k.wav").use { it.readBytes() }
        val client = CloneSpeechClient(endpoint!!)
        val received = CountDownLatch(1)
        var response: Result<ByteArray>? = null
        try {
            client.synthesize("Проверка", reference) { response = it; received.countDown() }
            assertTrue("HTTP request timed out", received.await(100, TimeUnit.SECONDS))
            val audio = response!!.getOrThrow()
            val file = File.createTempFile("instrumentation-", ".wav", instrumentation.targetContext.cacheDir)
            file.writeBytes(audio)
            val done = CountDownLatch(1)
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { done.countDown() }
                player.setOnErrorListener { _, _, _ -> done.countDown(); false }
                player.prepare()
                assertTrue(player.duration > 0)
                player.start()
                assertTrue("WAV playback timed out", done.await(45, TimeUnit.SECONDS))
            } finally { player.release(); file.delete() }
        } finally { client.close() }
    }
}
