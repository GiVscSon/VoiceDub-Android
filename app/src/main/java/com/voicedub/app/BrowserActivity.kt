package com.voicedub.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** Experimental first-party browser: detects HTML5 video. Audio is still captured by AudioCaptureService. */
class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var label: TextView
    private lateinit var address: EditText
    private lateinit var dubUrl: EditText
    private lateinit var dubLabel: TextView
    private lateinit var root: FrameLayout
    private lateinit var fullscreenCaptions: TextView
    private val clearFullscreenCaption = Runnable {
        if (::fullscreenCaptions.isInitialized) fullscreenCaptions.visibility = View.GONE
    }
    private lateinit var content: LinearLayout
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private val ui = Handler(Looper.getMainLooper())
    private var visible = false
    private lateinit var probeScript: String
    private lateinit var dubbingScript: String
    private lateinit var captionsScript: String
    private var latestSession = -1L
    private var latestSequence = -1L
    private var lastBrowserState = ""

    private fun publishPlayback(found: Boolean, paused: Boolean, seeking: Boolean, key: String) {
        val signature = "$found|$paused|$seeking|$key"
        if (signature == lastBrowserState) return
        lastBrowserState = signature
        sendBroadcast(Intent(BrowserPlaybackEvents.ACTION).apply {
            setPackage(packageName)
            putExtra(BrowserPlaybackEvents.FOUND, found)
            putExtra(BrowserPlaybackEvents.PAUSED, paused)
            putExtra(BrowserPlaybackEvents.SEEKING, seeking)
            putExtra(BrowserPlaybackEvents.PLAYER_KEY, key.take(160))
        }, SubtitleEvents.PERMISSION)
    }
    private val subtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SubtitleEvents.ACTION || !visible) return
            val session = intent.getLongExtra(SubtitleEvents.SESSION, -1L)
            val sequence = intent.getLongExtra(SubtitleEvents.SEQUENCE, -1L)
            if (session < 0 || sequence < 0 ||
                (session == latestSession && sequence <= latestSequence)) return
            latestSession = session
            latestSequence = sequence
            val text = intent.getStringExtra(SubtitleEvents.TRANSLATED).orEmpty()
            if (text.isBlank()) return
            if (fullScreenView != null) {
                fullscreenCaptions.text = text.take(500)
                fullscreenCaptions.visibility = View.VISIBLE
                ui.removeCallbacks(clearFullscreenCaption)
                ui.postDelayed(clearFullscreenCaption, 6000)
            }
            // JSON serialize untrusted caption text; never insert it into JS source raw.
            val payload = JSONObject().apply {
                put("translated", text)
                put("original", intent.getStringExtra(SubtitleEvents.ORIGINAL).orEmpty())
                put("session", session.toString())
                put("sequence", sequence)
            }
            web.evaluateJavascript("window.__voicedubCaptions?.push($payload)", null)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!visible || isFinishing) return
            web.evaluateJavascript("window.__voicedubProbe ? ({player:window.__voicedubProbe.snapshot(),dubbing:window.__voicedubDubbing ? window.__voicedubDubbing.status() : null}) : null") { json ->
                try {
                    if (!visible || json == "null" || json.isBlank()) return@evaluateJavascript
                    val result = JSONObject(json)
                    val data = result.getJSONObject("player")
                    val found = data.optBoolean("found")
                    publishPlayback(
                        found = found,
                        paused = data.optBoolean("paused", true) || data.optBoolean("ended", false),
                        seeking = data.optBoolean("seeking", false),
                        key = data.optString("playerKey")
                    )
                    if (found) {
                        val name = data.optString("platform")
                        val id = data.optString("videoId")
                        val pos = data.optDouble("position", 0.0)
                        val state = if (data.optBoolean("paused")) "пауза" else "воспроизведение"
                        val source = data.optString("sourceType")
                        label.text = "Видео: $name · $state · ${pos} с · $source\n$id"
                    } else {
                        label.text = "HTML5-плеер пока не обнаружен (iframe/DRM могут ограничивать доступ)"
                    }
                    val dubbing = result.optJSONObject("dubbing")
                    dubLabel.text = if (dubbing?.optBoolean("attached") == true) {
                        val drift = dubbing.optDouble("driftSeconds", 0.0)
                        "Озвучка: ${dubbing.optString("state")} · рассинхронизация ${"%.2f".format(java.util.Locale.US, drift)} с"
                    } else "Готовая аудиодорожка не подключена"
                } catch (_: Exception) {
                    label.text = "Определение плеера: ожидание загрузки…"
                }
            }
            ui.postDelayed(this, 450)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeScript = assets.open("browser_probe.js").bufferedReader().use { it.readText() }
        dubbingScript = assets.open("browser_dubbing.js").bufferedReader().use { it.readText() }
        captionsScript = assets.open("browser_captions.js").bufferedReader().use { it.readText() }
        root = FrameLayout(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        fullscreenCaptions = TextView(this).apply {
            textSize = 19f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xCC101820.toInt())
            setPadding(20, 12, 20, 12)
            gravity = Gravity.CENTER
            maxLines = 4
            visibility = View.GONE
        }
        root.addView(fullscreenCaptions, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "‹"; setOnClickListener { if (web.canGoBack()) web.goBack() else finish() } }
        address = EditText(this).apply {
            setSingleLine(true)
            setText("https://www.youtube.com")
            hint = "https://сайт-с-видео"
        }
        val go = Button(this).apply { text = "Открыть"; setOnClickListener { navigate() } }
        tools.addView(back)
        tools.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(go)
        content.addView(tools)
        label = TextView(this).apply {
            text = "Встроенный браузер: поиск HTML5-видео. Запуск аудиоперевода выполняется в основном экране VoiceDub."
            textSize = 13f
            setPadding(12, 8, 12, 8)
        }
        content.addView(label)
        dubUrl = EditText(this).apply {
            setSingleLine(true)
            hint = "HTTPS-ссылка на готовую русскую аудиодорожку (MP3/M4A)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(dubUrl)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Подключить дорожку"
            setOnClickListener { attachDubbing() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                web.evaluateJavascript("window.__voicedubDubbing?.detach()", null)
                dubLabel.text = "Озвучка отключена"
            }
        })
        content.addView(controls)
        dubLabel = TextView(this).apply {
            text = "Введите прямую ссылку на готовую аудиодорожку. После подключения нажмите синюю кнопку внутри страницы."
            textSize = 12f
            setPadding(12, 4, 12, 8)
        }
        content.addView(dubLabel)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                dubLabel.text = "Страница изменилась: предыдущая дорожка отключена"
                publishPlayback(false, true, false, "")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.scheme != "https"
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                address.setText(url ?: "")
                val page = view ?: return
                page.evaluateJavascript(probeScript) {
                    page.evaluateJavascript(dubbingScript) { page.evaluateJavascript(captionsScript, null) }
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullScreenView != null) { callback?.onCustomViewHidden(); return }
                fullScreenView = view
                fullScreenCallback = callback
                content.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                // Fullscreen video is a sibling of the WebView; render captions above it.
                root.removeView(fullscreenCaptions)
                root.addView(fullscreenCaptions)
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        content.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl("https://www.youtube.com")
    }

    private fun attachDubbing() {
        val media = dubUrl.text.toString().trim()
        val url = try { android.net.Uri.parse(media) } catch (_: Exception) { null }
        if (url?.scheme != "https" || url.host.isNullOrBlank()) {
            dubLabel.text = "Нужна HTTPS-ссылка непосредственно на аудиофайл"
            return
        }
        // Escape user input as a JSON string; never interpolate raw URL into JS code.
        val escaped = JSONObject.quote(media)
        val expression = "window.__voicedubDubbing ? window.__voicedubDubbing.attach({url:$escaped}) : ({ok:false,error:'player-not-ready'})"
        web.evaluateJavascript(expression) { json ->
            try {
                val result = JSONObject(json)
                dubLabel.text = if (result.optBoolean("ok")) {
                    "Дорожка прикреплена к ${result.optString("videoId")}. Нажмите кнопку ▶ внутри видео для запуска звука."
                } else "Не удалось подключить дорожку: ${result.optString("error")}."
            } catch (_: Exception) {
                dubLabel.text = "Не удалось выполнить скрипт. Дождитесь загрузки страницы."
            }
        }
    }

    private fun navigate() {
        val text = address.text.toString().trim()
        val normalized = if (text.contains("://")) text else "https://$text"
        val uri = android.net.Uri.parse(normalized)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            label.text = "Для встроенного браузера нужен адрес HTTPS"
            return
        }
        web.loadUrl(normalized)
    }

    private fun exitFullscreen() {
        val view = fullScreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullScreenView = null
        fullscreenCaptions.visibility = View.GONE
        ui.removeCallbacks(clearFullscreenCaption)
        content.visibility = View.VISIBLE
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SubtitleEvents.ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(subtitleReceiver, filter, SubtitleEvents.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(subtitleReceiver, filter, SubtitleEvents.PERMISSION, null)
        }
    }
    override fun onStop() {
        unregisterReceiver(subtitleReceiver)
        super.onStop()
    }
    override fun onResume() { super.onResume(); visible = true; ui.removeCallbacks(poll); ui.post(poll); web.onResume() }
    override fun onPause() { publishPlayback(false, true, false, ""); visible = false; ui.removeCallbacks(poll); ui.removeCallbacks(clearFullscreenCaption); fullscreenCaptions.visibility = View.GONE; web.onPause(); super.onPause() }
    override fun onBackPressed() { if (fullScreenView != null) exitFullscreen() else if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() {
        visible = false
        publishPlayback(false, true, false, "")
        ui.removeCallbacks(poll)
        exitFullscreen()
        content.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
