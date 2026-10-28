package com.voicedub.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/** No network capability is exposed to the site's WebView. This engine loads local code only. */
@Suppress("SetJavaScriptEnabled")
class VotEngine(context: Context, private val onResult: (String, JSONObject) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val web = WebView(context)
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    var isReady = false
        private set
    private var loaded = false
    private var lively = false

    init {
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.domStorageEnabled = false
        web.addJavascriptInterface(Bridge(), "VotNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view: WebView?, url: String?) {
                if (loaded || closed) return
                loaded = true
                val script = context.assets.open("vot_engine.js").bufferedReader().use { it.readText() }
                web.evaluateJavascript(script, null)
            }
        }
        web.loadDataWithBaseURL("https://voicedub.invalid/", "<html><body></body></html>", "text/html", "UTF-8", null)
    }

    fun translate(job: String, videoId: String, duration: Double, language: String): Boolean {
        if (!isReady || closed) return false
        val video = VotPolicy.video(videoId) ?: return false
        lively = VotAccount.accessToken() != null
        val payload = JSONObject().put("job", job).put("url", video.first).put("videoId", videoId.substringAfter(':'))
            .put("host", video.second).put("duration", duration.takeIf { it.isFinite() && it > 0 } ?: 310.0)
            .put("language", language).put("lively", lively)
        web.evaluateJavascript("window.__votTranslate($payload)", null)
        return true
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { ui.post { if (!closed) isReady = true } }
        @JavascriptInterface fun result(job: String, json: String) {
            if (json.length > 32768) return
            ui.post { if (!closed) try { onResult(job, JSONObject(json)) } catch (_: Exception) { } }
        }
        @JavascriptInterface fun request(id: String, url: String, headers: String, body: String) {
            if (closed || id.length > 32 || headers.length > 32768 || body.length > 1_000_000) return
            executor.execute {
                var status = 0
                var bytes = ByteArray(0)
                try {
                    check(!closed && VotPolicy.allowed(url))
                    val c = URL(url).openConnection() as HttpURLConnection
                    connection = c
                    try {
                        c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 20000
                        c.requestMethod = "POST"; c.doOutput = true
                        val map = JSONObject(headers)
                        for (key in map.keys()) {
                            if (key.lowercase() in setOf("accept", "accept-language", "content-type", "cache-control", "pragma", "user-agent", "vtrans-signature", "sec-vtrans-sk", "sec-vtrans-token")) {
                                c.setRequestProperty(key, map.getString(key))
                            }
                        }
                        if (lively && URI(url).path == "/video-translation/translate") {
                            VotAccount.accessToken()?.let { c.setRequestProperty("Authorization", "OAuth $it") }
                        }
                        val data = Base64.decode(body, Base64.NO_WRAP)
                        c.setFixedLengthStreamingMode(data.size)
                        c.outputStream.use { it.write(data) }
                        status = c.responseCode
                        bytes = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytesBounded(2 * 1024 * 1024) } ?: ByteArray(0)
                    } finally { connection = null; c.disconnect() }
                } catch (_: Exception) { status = 0; bytes = ByteArray(0) }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ui.post { if (!closed) web.evaluateJavascript("window.__votNetworkResult(${JSONObject.quote(id)},$status,${JSONObject.quote(encoded)})", null) }
            }
        }
    }

    fun close() {
        closed = true; connection?.disconnect(); executor.shutdownNow()
        web.removeJavascriptInterface("VotNative"); web.stopLoading(); web.destroy()
    }
}

object VotPolicy {
    fun allowed(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == "api.browser.yandex.ru" && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && u.rawQuery == null && u.fragment == null &&
            u.path in setOf("/session/create", "/video-translation/translate")
    } catch (_: Exception) { false }

    fun video(id: String): Pair<String, String>? {
        val value = id.substringAfter(':', "")
        return when {
            id.startsWith("youtube:") && value.matches(Regex("[A-Za-z0-9_-]{6,64}")) -> "https://youtu.be/$value" to "youtube"
            id.startsWith("vimeo:") && value.matches(Regex("[0-9]{1,20}")) -> "https://vimeo.com/$value" to "vimeo"
            else -> null
        }
    }
}
