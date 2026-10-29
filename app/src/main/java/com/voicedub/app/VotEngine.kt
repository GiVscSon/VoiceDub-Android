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
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Privileged transport lives only in a local isolated WebView, never in a video page. */
@Suppress("SetJavaScriptEnabled")
class VotEngine(
    context: Context,
    private val transport: ((String, String, Map<String, String>, ByteArray) -> Pair<Int, ByteArray>)? = null,
    private val onResult: (String, JSONObject) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val web = WebView(context)
    @Volatile private var closed = false
    @Volatile private var activeJob: String? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var source: File? = null
    private var loaded = false
    private var lively = false
    var isReady = false
        private set

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

    fun translate(job: String, videoId: String, duration: Double, language: String, target: String = "ru"): Boolean {
        if (!isReady || closed || !VotPolicy.supportsLanguage(language) || !VotPolicy.supportsTarget(target)) return false
        val video = VotPolicy.video(videoId) ?: return false
        if (job != activeJob) { cancel(); activeJob = job; lively = VotAccount.accessToken() != null }
        val payload = JSONObject().put("job", job).put("url", video.first).put("videoId", videoId.substringAfter(':'))
            .put("host", video.second).put("duration", duration.takeIf { it.isFinite() && it > 0 } ?: 310.0)
            .put("language", language).put("target", target).put("lively", lively)
        web.evaluateJavascript("window.__votTranslate($payload)", null)
        return true
    }

    /** File must already be complete and bound to the current video by BrowserActivity. */
    fun uploadSource(job: String, file: File): Boolean {
        if (closed || activeJob != job || !file.isFile) return false
        val parts = try { VotSourcePolicy.parts(file.length()) } catch (_: Exception) { return false }
        source = file
        val data = JSONObject().put("job", job).put("parts", parts)
        web.evaluateJavascript("window.__votUploadSource($data)", null)
        return true
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { ui.post { if (!closed) isReady = true } }
        @JavascriptInterface fun result(job: String, json: String) {
            if (json.length > 32768) return
            ui.post { if (!closed && activeJob == job) try { onResult(job, JSONObject(json)) } catch (_: Exception) { } }
        }
        // Executed on WebView's bridge thread. One bounded part crosses the bridge at a time.
        @JavascriptInterface fun sourcePart(job: String, index: Int): String {
            if (closed || job != activeJob) return ""
            val file = source ?: return ""
            return try {
                val bytes = VotSourcePolicy.readPart(file, index)
                if (closed || job != activeJob) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Exception) { "" }
        }
        @JavascriptInterface fun request(job: String, id: String, method: String, url: String, headers: String, body: String) {
            if (closed || job != activeJob || id.length > 32 || headers.length > 32768 || body.length > 7_200_000) return
            val useLively = lively
            try { executor.execute {
                if (closed || job != activeJob) return@execute
                var status = 0
                var bytes = ByteArray(0)
                try {
                    check(VotPolicy.allowed(url, method))
                    val data = Base64.decode(body, Base64.NO_WRAP)
                    check(data.size <= VotSourcePolicy.CHUNK_BYTES + 65536)
                    val map = JSONObject(headers)
                    val safeHeaders = mutableMapOf<String, String>()
                    for (key in map.keys()) {
                        if (key.lowercase(java.util.Locale.ROOT) in setOf("accept", "accept-language", "content-type", "cache-control", "pragma", "user-agent", "vtrans-signature", "sec-vtrans-sk", "sec-vtrans-token")) {
                            safeHeaders[key] = map.getString(key)
                        }
                    }
                    if (useLively && URI(url).path == "/video-translation/translate") {
                        VotAccount.accessToken()?.let { safeHeaders["Authorization"] = "OAuth $it" }
                    }
                    if (transport != null) {
                        val result = transport.invoke(method, url, safeHeaders, data)
                        status = result.first; bytes = result.second
                    } else {
                        val c = URL(url).openConnection() as HttpURLConnection
                        connection = c
                        try {
                            check(!closed && job == activeJob)
                            c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 20000
                            c.requestMethod = method; c.doOutput = true
                            safeHeaders.forEach { (key, value) -> c.setRequestProperty(key, value) }
                            c.setFixedLengthStreamingMode(data.size)
                            c.outputStream.use { it.write(data) }
                            status = c.responseCode
                            bytes = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytesBounded(2 * 1024 * 1024) } ?: ByteArray(0)
                        } finally { connection = null; c.disconnect() }
                    }
                } catch (_: Exception) { status = 0; bytes = ByteArray(0) }
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ui.post { if (!closed && activeJob == job) web.evaluateJavascript("window.__votNetworkResult(${JSONObject.quote(id)},$status,${JSONObject.quote(encoded)})", null) }
            } } catch (_: RejectedExecutionException) { /* Activity was closed. */ }
        }
    }

    fun cancel() {
        activeJob = null; source = null; connection?.disconnect()
        if (!closed && isReady) web.evaluateJavascript("window.__votCancel()", null)
    }
    fun close() {
        cancel(); closed = true; executor.shutdownNow()
        web.removeJavascriptInterface("VotNative"); web.stopLoading(); web.destroy()
    }
}

object VotPolicy {
    /** Supported request tags of the pinned @vot.js/shared 3.2.0, independent of Vosk. */
    fun supportsTarget(code: String) = code in setOf("ru", "en", "kk")
    fun supportsLanguage(code: String) = code in setOf("auto", "ru", "en", "zh", "ko", "ar", "fr", "it", "es", "de", "ja")

    fun allowed(url: String, method: String = "POST"): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == "api.browser.yandex.ru" && u.userInfo == null &&
            (u.port == -1 || u.port == 443) && u.rawQuery == null && u.fragment == null &&
            when (u.path) {
                "/session/create", "/video-translation/translate" -> method == "POST"
                "/video-translation/audio" -> method == "PUT"
                else -> false
            }
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
