package com.voicedub.app

import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VotUploadTest {
    @Test fun nativeBridgeTransfersFullSizePartsAndResumesTranslation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("vot-network-fixtures.json").bufferedReader().use { it.readText() })
        fun data(key: String) = Base64.decode(fixture.getString(key), Base64.DEFAULT)
        val uploads = AtomicInteger()
        val calls = AtomicInteger()
        val file = File.createTempFile("vot-instrumentation-", ".audio", instrumentation.targetContext.cacheDir)
        file.outputStream().use { out ->
            val block = ByteArray(32768) { 42 }
            var remaining = VotSourcePolicy.CHUNK_BYTES + 43
            while (remaining > 0) { val size = minOf(remaining, block.size); out.write(block, 0, size); remaining -= size }
        }
        val complete = CountDownLatch(1)
        var failure: String? = null
        var engine: VotEngine? = null
        instrumentation.runOnMainSync {
            engine = VotEngine(instrumentation.targetContext, transport = { method, url, headers, body ->
                calls.incrementAndGet()
                val key = when {
                    url.endsWith("/session/create") -> "session"
                    url.endsWith("/audio") -> {
                        check(method == "PUT" && headers["Authorization"] == null)
                        val part = uploads.getAndIncrement()
                        check(if (part == 0) body.size > VotSourcePolicy.CHUNK_BYTES else body.size in 44..65536)
                        "uploaded"
                    }
                    else -> if (uploads.get() == 2) "ready" else "requested"
                }
                200 to data(key)
            }) { job, result ->
                if (!result.optBoolean("ok")) { failure = result.optString("error"); complete.countDown() }
                else if (result.optInt("status") == 6) {
                    if (!engine!!.uploadSource(job, file)) { failure = "upload-not-started"; complete.countDown() }
                } else if (result.optBoolean("uploaded")) engine!!.translate(job, "youtube:abc_def-123", 100.0, "auto")
                else if (result.optBoolean("translated")) complete.countDown()
            }
        }
        try {
            val deadline = System.currentTimeMillis() + 20000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = engine!!.isReady }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("VOT runtime not ready", ready)
            instrumentation.runOnMainSync { assertTrue(engine!!.translate("instrumentation", "youtube:abc_def-123", 100.0, "auto")) }
            assertTrue("Native source bridge timed out", complete.await(90, TimeUnit.SECONDS))
            assertNull(failure)
            assertEquals(2, uploads.get())
            assertEquals(5, calls.get()) // session, request, two PUTs, final poll
        } finally {
            instrumentation.runOnMainSync { engine?.close() }
            file.delete()
        }
    }

    @Test fun browserKeepsVideoAreaWithScrollableControls() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                fun find(v: View): WebView? {
                    if (v is WebView) return v
                    if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                    return null
                }
                val web = find(activity.window.decorView)
                assertNotNull(web)
                assertTrue("Controls consumed the video area", web!!.height >= activity.window.decorView.height * 0.45)
            }
        }
    }
}
