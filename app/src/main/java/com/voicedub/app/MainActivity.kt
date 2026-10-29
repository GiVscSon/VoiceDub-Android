package com.voicedub.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

/** Single responsive screen, suitable for phone portrait and tablet landscape. */
class MainActivity : Activity() {
    companion object {
        private const val AUDIO_PERMISSION = 101
        private const val PROJECTION_REQUEST = 102
    }
    private lateinit var status: TextView
    private lateinit var selector: Spinner
    private lateinit var targetSelector: Spinner
    private lateinit var autoLocalLanguage: CheckBox
    @Volatile private var tinyDownloadActive = false
    private lateinit var modeSelector: Spinner
    private lateinit var download: Button
    private lateinit var translatorSelector: Spinner
    private lateinit var yandexFields: LinearLayout
    private lateinit var apiKey: EditText
    private lateinit var folderId: EditText
    private lateinit var start: Button
    private lateinit var cloneEnabled: CheckBox
    private lateinit var cloneAddress: EditText
    private lateinit var accountStatus: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun paragraph(text: String, size: Float = 15f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(7), 0, dp(7))
    }
    private fun btn(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }
    private fun selectedModel() = ModelCatalog.entries[selector.selectedItemPosition]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margin = if (resources.configuration.smallestScreenWidthDp >= 600) dp(34) else dp(18)
            setPadding(margin, dp(16), margin, dp(24))
        }
        screen.addView(content)
        content.addView(paragraph("VoiceDub · голосовой перевод видео", 24f))
        content.addView(paragraph("Работает на Android 10+; телефон и планшет. Захватывает доступный внутренний звук, не записывает изображение и не требует root."))
        content.addView(paragraph("Язык исходного видео:"))
        selector = Spinner(this)
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ModelCatalog.entries.map { it.label })
        val savedLanguage = getSharedPreferences("settings", MODE_PRIVATE).getString("sourceLanguage", "en")
        selector.setSelection(ModelCatalog.entries.indexOf(ModelCatalog.findByLanguageTag(savedLanguage)).coerceAtLeast(0))
        selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("sourceLanguage", ModelCatalog.entries[position].code).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        content.addView(selector)
        content.addView(paragraph("Переводить на язык:"))
        targetSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TranslationLanguages.entries.map { it.label })
            val saved = getSharedPreferences("settings", MODE_PRIVATE).getString("targetLanguage", "ru")
            setSelection(TranslationLanguages.entries.indexOf(TranslationLanguages.resolve(saved)).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val language = TranslationLanguages.entries[position].code
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("targetLanguage", language).apply()
                    if (::cloneEnabled.isInitialized) {
                        cloneEnabled.isEnabled = TranslationLanguages.supportsMoss(language)
                        if (!TranslationLanguages.supportsMoss(language)) cloneEnabled.isChecked = false
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        content.addView(targetSelector)
        autoLocalLanguage = CheckBox(this).apply {
            text = "Определять язык речи локально при запуске · Tiny (резерв — выбранный исходный язык)"
            isEnabled = LocalSpeechDetector.available
            isChecked = LocalSpeechDetector.available && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autoLocalLanguage", false)
            setOnCheckedChangeListener { _, checked -> getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("autoLocalLanguage", checked).apply() }
        }
        content.addView(autoLocalLanguage)
        if (LocalSpeechDetector.available) content.addView(btn("Скачать Whisper tiny int8 для определения языка · 98 МиБ") { installTinyModel() })
        else content.addView(paragraph("Локальное автоопределение доступно в отдельной сборке VoiceDub Tiny. Обычный вариант Core сохраняет ручной выбор без дополнительного движка."))
        content.addView(paragraph("Озвучка использует голос Android выбранного языка. Для одинаковых языков выводится распознанная речь без перевода."))
        content.addView(paragraph("Режим перевода:"))
        modeSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OutputMode.entries.map { it.label })
            setSelection(OutputMode.entries.indexOf(OutputMode.BOTH))
        }
        content.addView(modeSelector)
        content.addView(paragraph("Переводчик текста:"))
        translatorSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Локальный · ML Kit", "Yandex Cloud · официальный API"))
        }
        content.addView(translatorSelector)
        yandexFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apiKey = EditText(this).apply {
            hint = "Yandex Cloud API-ключ (только на время сеанса)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        folderId = EditText(this).apply { hint = "Yandex Cloud Folder ID"; setSingleLine(true) }
        yandexFields.addView(apiKey)
        yandexFields.addView(folderId)
        yandexFields.addView(paragraph("API-ключ не сохраняется на диске. В облако отправляется только распознанный текст, не аудиофайл."))
        yandexFields.visibility = View.GONE
        translatorSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                yandexFields.visibility = if (position == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        content.addView(yandexFields)
        accountStatus = paragraph("Яндекс VOT: вход не выполнен")
        content.addView(accountStatus)
        content.addView(btn("Войти через Яндекс · как в VOT") {
            startActivityForResult(Intent(this, YandexAuthActivity::class.java), 104)
        })
        content.addView(btn("Выйти из Яндекс VOT") {
            VotAccount.clear()
            accountStatus.text = "Яндекс VOT: сеанс завершён"
        })
        content.addView(paragraph("Вход VOT не заменяет ML Kit и не предоставляет доступ к Yandex Cloud Translate. Запрос дорожки VOT — отдельная кнопка во встроенном браузере для YouTube/Vimeo; доступность сервиса не гарантируется."))
        cloneEnabled = CheckBox(this).apply {
            text = "Сохранять особенности исходного голоса · MOSS на ПК (только русский)"
            isEnabled = TranslationLanguages.supportsMoss(TranslationLanguages.entries[targetSelector.selectedItemPosition].code)
            isChecked = false
        }
        content.addView(cloneEnabled)
        cloneAddress = EditText(this).apply {
            hint = "http://192.168.1.10:8765"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences("settings", MODE_PRIVATE).getString("voiceEndpoint", ""))
        }
        content.addView(cloneAddress)
        content.addView(paragraph("При включении на указанный сервер отправляются русский текст и 3–8 секунд исходного звука. Образец хранится в памяти до остановки/смены видео; сервер использует временный файл. Для одного говорящего, без разделения голосов и музыки. При ошибке — обычный русский TTS с сообщением."))
        content.addView(btn("Проверить сервер голоса") {
            val base = try { VoiceEndpoint.normalize(cloneAddress.text.toString()) } catch (_: Exception) {
                status.text = "Укажите локальный IP сервера или адрес HTTPS"; return@btn
            }
            Thread({
                val message = try {
                    val c = java.net.URL("$base/health").openConnection() as java.net.HttpURLConnection
                    try {
                        c.instanceFollowRedirects = false; c.connectTimeout = 5000; c.readTimeout = 5000
                        check(c.responseCode == 200)
                        val body = c.inputStream.use { String(it.readBytesBounded(4096), Charsets.UTF_8) }
                        check(org.json.JSONObject(body).optBoolean("ready"))
                        "Сервер MOSS готов. Передача образца начнётся только при включении режима и захвата."
                    } finally { c.disconnect() }
                } catch (_: Exception) { "Сервер голоса недоступен. Проверьте IP, порт и общую сеть телефона и ПК." }
                runOnUiThread { if (!isFinishing) status.text = message }
            }, "voice-health").start()
        })
        content.addView(btn("Открыть встроенный браузер (экспериментально)") {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("sourceLanguage", selectedModel().code).apply()
            startActivity(Intent(this, BrowserActivity::class.java))
        })
        content.addView(paragraph("Для обычного Chrome, Opera и других браузеров используйте захват системного аудио. Во встроенном браузере дополнительно показывается обнаружение видеоплеера и его состояние."))
        status = paragraph("Сначала скачайте модель исходного языка (Wi-Fi рекомендуется). Затем включите перевод.")
        content.addView(status)
        download = btn("1. Скачать модель распознавания") { installModel() }
        content.addView(download)
        content.addView(paragraph("Модель перевода ML Kit автоматически скачивается отдельно при первом запуске. Озвучка использует установленный голос TTS Android выбранного языка."))
        content.addView(btn("2. Разрешить плавающее окно (необязательно)") {
            val permission = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(permission)
        })
        start = btn("3. Начать перевод системного аудио") { requestCapture() }
        content.addView(start)
        content.addView(btn("Остановить перевод") {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.STOP))
            status.text = "Перевод остановлен"
        })
        content.addView(btn("Настроить голоса Android") {
            try { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            catch (_: android.content.ActivityNotFoundException) { status.text = "Откройте настройки синтеза речи Android и установите голос выбранного языка." }
        })
        content.addView(btn("Последняя диагностика") {
            val last = getSharedPreferences("diagnostics", MODE_PRIVATE).getString("last", "Запусков ещё не было")
            val voice = getSharedPreferences("diagnostics", MODE_PRIVATE).getString("ttsWarning", "").orEmpty()
            val detection = getSharedPreferences("diagnostics", MODE_PRIVATE).getString("languageDetection", "").orEmpty()
            status.text = "Последнее сообщение: $last\n$detection\n$voice"
        })
        content.addView(paragraph("Субтитры во встроенном браузере не требуют разрешения на плавающее окно. Для субтитров поверх Chrome/Opera оно потребуется."))
        content.addView(paragraph("Важно: Android покажет запрос на захват экрана/аудио, но VoiceDub читает только звуковые PCM-сэмплы. Для большинства видеоплееров выбирайте полный экран (если система предлагает выбор). Некоторые приложения запрещают захват. Оригинальный звук автоматически не отключается."))
        content.addView(paragraph("Проверка: включите видео с иностранной речью в браузере. Если окно показывает 'PCM: тишина', источник не разрешает захват либо видео не воспроизводит звук."))
        setContentView(screen)
    }

    private fun installTinyModel() {
        if (tinyDownloadActive) return
        tinyDownloadActive = true
        Thread({
            try {
                TinyLanguageAssets.install(this) { message -> runOnUiThread { status.text = message } }
                runOnUiThread { status.text = "Whisper tiny готов. Для каждого исходного языка также нужна модель Vosk. Звук обрабатывается на устройстве." }
            } catch (_: Exception) { runOnUiThread { status.text = "Не удалось скачать модель Tiny. Проверьте сеть и свободное место; ручной режим доступен." } }
            finally { tinyDownloadActive = false }
        }, "tiny-download").start()
    }

    private fun installModel() {
        val model = selectedModel()
        if (model.directory(filesDir).resolve("am/final.mdl").isFile) {
            status.text = "Модель уже установлена: ${model.label}"
            return
        }
        download.isEnabled = false
        Thread({
            try {
                ModelInstaller.install(this, model) { message -> runOnUiThread { status.text = message } }
                runOnUiThread { status.text = "Готово: ${model.label}. Теперь можно запускать." }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Ошибка установки: ${error.message}" }
            } finally { runOnUiThread { download.isEnabled = true } }
        }, "model-download").start()
    }

    override fun onResume() {
        super.onResume()
        if (::accountStatus.isInitialized) accountStatus.text = "Яндекс VOT: ${VotAccount.username()}"
    }

    private fun requestCapture() {
        if (autoLocalLanguage.isChecked && !TinyLanguageAssets.installed(this)) {
            status.text = "Сначала скачайте модель Whisper tiny или выключите локальное автоопределение."
            return
        }
        if (cloneEnabled.isChecked) {
            try {
                val endpoint = VoiceEndpoint.normalize(cloneAddress.text.toString())
                cloneAddress.setText(endpoint)
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("voiceEndpoint", endpoint).apply()
            } catch (_: Exception) { status.text = "Укажите локальный IP сервера голоса или адрес HTTPS"; return }
        }
        if (translatorSelector.selectedItemPosition == 1 &&
            (apiKey.text.toString().isBlank() || folderId.text.toString().isBlank())) {
            status.text = "Укажите Yandex Cloud API-ключ и Folder ID. Или выберите локальный переводчик."
            return
        }
        // Overlay permission is only needed to display captions above OTHER applications.
        // Built-in WebView captions use normal DOM; spoken-only mode needs no overlay.
        if (!selectedModel().directory(filesDir).resolve("am/final.mdl").isFile) {
            status.text = "Сначала скачайте модель выбранного языка."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Notification permission is optional for the foreground service, but requested visibly.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
            return
        }
        beginProjection()
    }

    private fun beginProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION_REQUEST)
    }

    @Deprecated("Uses Activity callback for compatibility with API 29")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 104) {
            if (resultCode == RESULT_OK && data != null) {
                VotAccount.set(data.getStringExtra("token").orEmpty(), data.getLongExtra("expires", 0L), data.getStringExtra("username").orEmpty())
                accountStatus.text = "Яндекс VOT: ${VotAccount.username()} · сеанс в памяти"
            } else accountStatus.text = "Яндекс VOT: вход отменён или не завершён"
            return
        }
        if (requestCode != PROJECTION_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Доступ к системному аудио не предоставлен"
            return
        }
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.START
            putExtra("projectionResult", resultCode)
            putExtra("projectionData", data)
            putExtra("lang", selectedModel().code)
            putExtra("targetLanguage", TranslationLanguages.entries[targetSelector.selectedItemPosition].code)
            putExtra("autoLocalLanguage", autoLocalLanguage.isChecked)
            putExtra("outputMode", OutputMode.entries[modeSelector.selectedItemPosition].name)
            putExtra("voiceEndpoint", if (cloneEnabled.isChecked) cloneAddress.text.toString() else "")
            putExtra("translationEngine", if (translatorSelector.selectedItemPosition == 1) "yandex-cloud" else "local")
            if (translatorSelector.selectedItemPosition == 1) {
                putExtra("yandexApiKey", apiKey.text.toString())
                putExtra("yandexFolderId", folderId.text.toString().trim())
            }
        }
        startForegroundService(intent)
        // Keep the credential in memory only while starting. Do not persist it.
        apiKey.text?.clear()
        status.text = "Запуск захвата… Перейдите к видео. Остановить можно из уведомления VoiceDub."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION || requestCode == 103) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestCapture()
            else if (requestCode == 103) beginProjection() // optional notifications
            else status.text = "Для захвата системного звука необходимо разрешение Audio."
        }
    }
}
