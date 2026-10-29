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
import android.widget.CheckBox
import java.io.File
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
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
    private lateinit var votLabel: TextView
    private lateinit var vot: VotEngine
    private var videoIdentity = ""
    private var videoPlayerKey = ""
    private var videoDuration = 0.0
    private var votJob: String? = null
    private val dubHandoff = DubHandoffGate()
    private var votAttempts = 0
    private var votRetry: Runnable? = null
    private lateinit var autoVotLanguage: CheckBox
    private lateinit var allowSourceUpload: CheckBox
    private val sourceLoader by lazy { VotSourceLoader(this) }
    private var sourceFile: File? = null
    private var sourceKey = ""
    private var pickerKey = ""
    private var sourcePreparing = false
    private var votDeadline = 0L
    private var attachedVotUrl = ""
    private var attachedVotKey = ""

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
                    val identity = if (found) data.optString("videoId") else ""
                    val playerKey = if (found) data.optString("playerKey") else ""
                    if (identity != videoIdentity || playerKey != videoPlayerKey) { cancelVot(); clearSource() }
                    videoIdentity = identity
                    videoPlayerKey = playerKey
                    videoDuration = data.optDouble("duration", 0.0)
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
                    // Attaching a VOT URL does not mean that Android WebView can play it.
                    // Preserve the local translator until an actual audio playback event.
                    if (dubHandoff.onState(dubbing?.optString("state"))) {
                        stopService(Intent(this@BrowserActivity, AudioCaptureService::class.java))
                        votLabel.text = "VOT: дорожка перевода воспроизводится; локальная озвучка остановлена"
                    }
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
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panelScroll = ScrollView(this).apply { addView(panel) }
        val panelHeight = (resources.displayMetrics.heightPixels * 0.38).toInt()
        content.addView(panelScroll, LinearLayout.LayoutParams(-1, panelHeight))
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
        panel.addView(tools)
        label = TextView(this).apply {
            text = "Встроенный браузер: поиск HTML5-видео. Запуск аудиоперевода выполняется в основном экране VoiceDub."
            textSize = 13f
            setPadding(12, 8, 12, 8)
        }
        panel.addView(label)
        votLabel = TextView(this).apply { text = "VOT: экспериментальный перевод текущего видео"; textSize = 12f }
        panel.addView(votLabel)
        autoVotLanguage = CheckBox(this).apply { text = "Яндекс: определить язык автоматически"; isChecked = true }
        allowSourceUpload = CheckBox(this).apply { text = "Разрешить отправку полной исходной дорожки Яндексу (до 80 МБ)" }
        panel.addView(autoVotLanguage)
        panel.addView(allowSourceUpload)
        panel.addView(Button(this).apply {
            text = "Выбрать исходный аудиофайл (если источник недоступен)"
            setOnClickListener {
                if (VotPolicy.video(videoIdentity) == null) { votLabel.text = "Сначала откройте видео YouTube или Vimeo"; return@setOnClickListener }
                pickerKey = videoPlayerKey
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 205)
            }
        })
        panel.addView(Button(this).apply { text = "Отменить запрос Яндекса"; setOnClickListener { cancelVot(); votLabel.text = "Запрос Яндекса отменён" } })
        panel.addView(Button(this).apply {
            text = "Перевести видео · Яндекс VOT (эксперимент)"
            setOnClickListener {
                if (VotPolicy.video(videoIdentity) == null) {
                    votLabel.text = "VOT: сначала откройте видимый HTML5-плеер YouTube или Vimeo"
                } else {
                    cancelVot()
                    votAttempts = 0
                    votDeadline = android.os.SystemClock.elapsedRealtime() + 20 * 60 * 1000L
                    votJob = android.os.SystemClock.elapsedRealtimeNanos().toString()
                    requestVot()
                }
            }
        })
        dubUrl = EditText(this).apply {
            setSingleLine(true)
            hint = "HTTPS-ссылка на готовую аудиодорожку перевода (MP3/M4A)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(dubUrl)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Подключить дорожку"
            setOnClickListener { attachDubbing() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                web.evaluateJavascript("window.__voicedubDubbing?.detach()", null)
                attachedVotUrl = ""; attachedVotKey = ""
                dubLabel.text = "Озвучка отключена"
            }
        })
        panel.addView(controls)
        dubLabel = TextView(this).apply {
            text = "Введите прямую ссылку на готовую аудиодорожку. После подключения нажмите синюю кнопку внутри страницы."
            textSize = 12f
            setPadding(12, 4, 12, 8)
        }
        panel.addView(dubLabel)
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
                cancelVot()
                clearSource()
                videoIdentity = ""
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
        vot = VotEngine(this) { job, result -> receiveVot(job, result) }
        web.loadUrl("https://www.youtube.com")
    }

    private fun requestVot() {
        val job = votJob ?: return
        if (!visible) { cancelVot(); return }
        if (++votAttempts > 80 || android.os.SystemClock.elapsedRealtime() > votDeadline) { votLabel.text = "VOT: время ожидания истекло"; cancelVot(); return }
        val language = if (autoVotLanguage.isChecked) "auto" else getSharedPreferences("settings", MODE_PRIVATE).getString("sourceLanguage", "en") ?: "en"
        if (!VotPolicy.supportsLanguage(language)) {
            votLabel.text = "Этот исходный язык недоступен в используемом VOT. Включите автоопределение Яндекса или используйте локальный перевод."
            cancelVot(); return
        }
        val target = getSharedPreferences("settings", MODE_PRIVATE).getString("targetLanguage", "ru") ?: "ru"
        if (!VotPolicy.supportsTarget(target)) {
            votLabel.text = "Выбранный язык перевода недоступен в VOT. Используйте локальный режим для этого направления."
            cancelVot(); return
        }
        if (!vot.translate(job, videoIdentity, videoDuration, language, target)) {
            votLabel.text = "VOT ещё загружается. Повторите через несколько секунд."
            cancelVot()
        } else votLabel.text = "VOT: запрос перевода${if (VotAccount.accessToken() != null) " с авторизацией Яндекса" else " без входа"}…"
    }

    private fun receiveVot(job: String, result: JSONObject) {
        if (job != votJob || !visible) return
        if (result.optBoolean("uploading")) {
            votLabel.text = "Яндекс: отправлено частей ${result.optInt("uploadedParts")} / ${result.optInt("totalParts")}"
            return
        }
        if (result.optBoolean("uploaded")) { scheduleVot(5); return }
        if (!result.optBoolean("ok")) {
            val stage = when (result.optString("stage")) {
                "session" -> "создание сессии"
                "translate" -> "запрос перевода"
                "upload" -> "передача исходного аудио"
                else -> "ответ сервиса"
            }
            val status = result.optInt("httpStatus").takeIf { it in 100..599 }
            val reason = when (result.optString("error")) {
                "auth_required" -> "Яндекс запросил авторизацию"
                "http_auth" -> "сервис отказал в доступе"
                "rate_limit" -> "слишком много запросов, попробуйте позже"
                "server" -> "ошибка сервиса Яндекса, попробуйте позже"
                "source_unavailable" -> "не удалось прочитать исходную дорожку"
                "network" -> "нет ответа от сервиса: проверьте соединение"
                "http" -> "ошибка HTTP"
                else -> "сервис не вернул дорожку"
            }
            votLabel.text = "VOT: $reason ($stage${status?.let { ", HTTP $it" } ?: ""}). Локальный перевод доступен."
            cancelVot(); return
        }
        if (result.optBoolean("translated")) {
            val url = result.optString("url")
            if (android.net.Uri.parse(url).scheme != "https") {
                votLabel.text = "VOT вернул неподдерживаемую дорожку"; cancelVot(); return
            }
            // Do not stop live subtitles/TTS yet: audio must start after a user gesture.
            if (attachedVotUrl != url || attachedVotKey != videoPlayerKey) { dubUrl.setText(url); attachDubbing() }
            if (result.optInt("status") == 5) scheduleVot(result.optInt("remainingTime", 15))
            else cancelVot()
        } else if (result.optInt("status") in listOf(2, 3)) {
            scheduleVot(result.optInt("remainingTime", 15))
        } else if (result.optInt("status") == 6 && !result.optBoolean("sourceAlreadyUploaded")) {
            prepareVotSource(job)
        } else {
            votLabel.text = "Яндекс не принял исходную дорожку или вернул неизвестный статус. Повторите позже."
            cancelVot()
        }
    }

    private fun scheduleVot(seconds: Int) {
        val wait = seconds.coerceIn(5, 30)
        votLabel.text = "Яндекс обрабатывает видео. Следующая проверка через $wait с…"
        votRetry?.let { ui.removeCallbacks(it) }
        votRetry = Runnable { requestVot() }.also { ui.postDelayed(it, wait * 1000L) }
    }

    private fun prepareVotSource(job: String) {
        if (!allowSourceUpload.isChecked) {
            votLabel.text = "Яндекс запросил исходное аудио. Разрешите отправку дорожки и повторите запрос."
            cancelVot(); return
        }
        if (sourceKey == videoPlayerKey && sourceFile?.isFile == true) {
            if (vot.uploadSource(job, sourceFile!!)) votLabel.text = "Яндекс: передаю исходную дорожку…"
            else { votLabel.text = "Не удалось начать отправку дорожки"; cancelVot() }
            return
        }
        if (sourcePreparing) return
        sourcePreparing = true
        val key = videoPlayerKey
        web.evaluateJavascript("window.__voicedubProbe?.sourceAudio() || null") { json ->
            if (job != votJob || key != videoPlayerKey || !visible) return@evaluateJavascript
            val data = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val url = data.optString("url")
            if (data.optBoolean("drm") || data.optString("playerKey") != key || !VotSourcePolicy.allowedUrl(url)) {
                votLabel.text = if (data.optBoolean("drm")) "Защищённое видео: исходный поток не извлекается" else "Прямая аудиодорожка недоступна. Выберите исходный аудиофайл и повторите запрос."
                cancelVot(); return@evaluateJavascript
            }
            votLabel.text = "Загружаю полную исходную дорожку (до 80 МБ)…"
            sourceLoader.fromUrl(url) { result ->
                sourcePreparing = false
                if (job != votJob || key != videoPlayerKey || !visible) { result.getOrNull()?.delete(); return@fromUrl }
                result.onSuccess { file -> clearSource(); sourceFile = file; sourceKey = key; prepareVotSource(job) }
                    .onFailure { votLabel.text = "Источник не дал скачать дорожку. Выберите аудиофайл и повторите запрос."; cancelVot() }
            }
        }
    }

    private fun clearSource() { sourceFile?.delete(); sourceFile = null; sourceKey = "" }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 205 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pickerKey
        if (key.isBlank() || key != videoPlayerKey) return
        votLabel.text = "Читаю выбранную исходную дорожку…"
        sourceLoader.fromDocument(uri) { result ->
            if (key != videoPlayerKey || isFinishing) { result.getOrNull()?.delete(); return@fromDocument }
            result.onSuccess { file ->
                clearSource(); sourceFile = file; sourceKey = key
                votLabel.text = "Аудиофайл выбран для этого видео. Разрешите отправку и нажмите «Перевести видео» снова."
            }.onFailure { votLabel.text = "Не удалось прочитать аудиофайл (лимит 80 МБ)" }
        }
    }

    private fun cancelVot() {
        if (::vot.isInitialized) vot.cancel()
        sourceLoader.cancel()
        sourcePreparing = false
        votJob = null
        votRetry?.let { ui.removeCallbacks(it) }
        votRetry = null
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
        val key = videoPlayerKey
        web.evaluateJavascript(expression) { json ->
            if (key != videoPlayerKey || !visible) return@evaluateJavascript
            try {
                val result = JSONObject(json)
                dubLabel.text = if (result.optBoolean("ok")) {
                    attachedVotUrl = media; attachedVotKey = key
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
    override fun onPause() { cancelVot(); publishPlayback(false, true, false, ""); visible = false; ui.removeCallbacks(poll); ui.removeCallbacks(clearFullscreenCaption); fullscreenCaptions.visibility = View.GONE; web.onPause(); super.onPause() }
    override fun onBackPressed() { if (fullScreenView != null) exitFullscreen() else if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() {
        visible = false
        cancelVot()
        if (::vot.isInitialized) vot.close()
        sourceLoader.close()
        clearSource()
        publishPlayback(false, true, false, "")
        ui.removeCallbacks(poll)
        exitFullscreen()
        content.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
