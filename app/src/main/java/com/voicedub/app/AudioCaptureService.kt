package com.voicedub.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Android 10+ user-approved internal PCM -> Vosk ASR -> ML Kit -> Russian TTS. */
class AudioCaptureService : Service() {
    companion object {
        const val START = "com.voicedub.app.START"
        const val STOP = "com.voicedub.app.STOP"
        private const val CHANNEL_ID = "voicedub_translation"
        private const val NOTIFICATION_ID = 2026
        private const val SAMPLE_RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())
    private val recording = AtomicBoolean(false)
    private val pendingTexts = ArrayDeque<String>()
    private var translating = false
    private var translatorReady = false
    private var translator: TranslationEngine? = null
    private var sourceLanguage = "en"
    private var yandexActive = false
    private var translationSession: Long = 0L
    private var translationSequence: Long = 0L
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var overlay: FloatingStatus? = null
    private var outputMode = OutputMode.BOTH
    private var lastSpoken = ""
    private var lastStatusUpdateMs = 0L
    private val playbackGate = BrowserPlaybackGate()
    private var translationEpoch = 0L
    private val browserPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BrowserPlaybackEvents.ACTION) return
            val state = BrowserPlaybackState(
                found = intent.getBooleanExtra(BrowserPlaybackEvents.FOUND, false),
                paused = intent.getBooleanExtra(BrowserPlaybackEvents.PAUSED, true),
                seeking = intent.getBooleanExtra(BrowserPlaybackEvents.SEEKING, false),
                key = intent.getStringExtra(BrowserPlaybackEvents.PLAYER_KEY).orEmpty()
            )
            val decision = playbackGate.update(state)
            if (decision.cancelQueuedSpeech) {
                translationEpoch++
                pendingTexts.clear()
                tts?.stop()
                lastSpoken = ""
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val browserFilter = IntentFilter(BrowserPlaybackEvents.ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(browserPlaybackReceiver, browserFilter, SubtitleEvents.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(browserPlaybackReceiver, browserFilter, SubtitleEvents.PERMISSION, null)
        }
        overlay = FloatingStatus(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val languageStatus = tts?.setLanguage(Locale("ru", "RU"))
                if (languageStatus == TextToSpeech.LANG_MISSING_DATA || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                    showStatus("Русский голос TTS отсутствует: установите голосовой пакет")
                }
                // Accessibility audio is excluded from third-party playback capture:
                // avoid feeding dubbed speech back into the input.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build()
                )
                ttsReady = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                    languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || !recording.compareAndSet(false, true)) return START_NOT_STICKY
        outputMode = OutputMode.parse(intent.getStringExtra("outputMode"))
        translationSession = SystemClock.elapsedRealtimeNanos()

        createNotificationChannel()
        val stop = Intent(this, AudioCaptureService::class.java).setAction(STOP)
        val stopPending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPending = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val note = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoiceDub: перевод включён")
            .setContentText("Перевод системного звука · нажмите, чтобы открыть")
            .setContentIntent(openPending)
            .addAction(Notification.Action.Builder(null, "Остановить", stopPending).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("Нет разрешения RECORD_AUDIO")
            }
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>("projectionData")
                ?: throw IllegalStateException("Нет разрешения MediaProjection")
            val resultCode = intent.getIntExtra("projectionResult", Activity.RESULT_CANCELED)
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val grantedProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection недоступен")
            projection = grantedProjection
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }.also { grantedProjection.registerCallback(it, main) }
            val model = ModelCatalog.entries.firstOrNull { it.code == intent.getStringExtra("lang") }
                ?: throw IllegalArgumentException("Неизвестный язык")
            initTranslator(
                model.code,
                intent.getStringExtra("translationEngine") ?: "local",
                intent.getStringExtra("yandexApiKey").orEmpty(),
                intent.getStringExtra("yandexFolderId").orEmpty()
            )
            overlay?.update("VoiceDub запущен · подготовка модели…")
            captureThread = Thread({ captureLoop(grantedProjection, model) }, "voicedub-asr").also { it.start() }
        } catch (e: Exception) {
            showStatus("Ошибка запуска: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun initTranslator(source: String, backend: String, apiKey: String, folderId: String) {
        sourceLanguage = source
        yandexActive = backend == "yandex-cloud"
        translator = if (yandexActive) {
            YandexCloudTranslationEngine(source, apiKey, folderId)
        } else LocalTranslationEngine(source)
        translatorReady = false
        showStatus("Подготовка движка: ${if (backend == "yandex-cloud") "Yandex Cloud" else "ML Kit"}")
        translator?.prepare { result -> main.post {
            if (!recording.get()) return@post
            result.onSuccess {
                translatorReady = true
                showStatus("Переводчик готов · слушаю видео")
                translateNext()
            }.onFailure {
                showStatus("Не удалось запустить переводчик: ${it.message}")
                stopSelf()
            }
        } }
    }

    private fun captureLoop(grantedProjection: MediaProjection, speechModel: SpeechModel) {
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(grantedProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBytes > 0) { "Неподдерживаемый формат AudioRecord" }
            val capture = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBytes, 32768))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord = capture
            check(capture.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован" }
            Model(speechModel.directory(filesDir).absolutePath).use { model ->
                Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
                    val buffer = ByteArray(8192)
                    capture.startRecording()
                    showStatus("Захват PCM активен · ожидаю речь")
                    while (recording.get()) {
                        val n = capture.read(buffer, 0, buffer.size)
                        if (n <= 0) {
                            if (n < 0) showStatus("Ошибка чтения AudioRecord: $n")
                            break
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStatusUpdateMs >= 2500) {
                            lastStatusUpdateMs = now
                            var sum = 0.0
                            var count = 0
                            for (i in 0 until n - 1 step 2) {
                                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toDouble()
                                sum += sample * sample
                                count++
                            }
                            val rms = if (count > 0) sqrt(sum / count).toInt() else 0
                            if (rms < 15) showStatus("PCM: тишина (RMS=$rms); проверьте источник")
                            else if (lastSpoken.isBlank()) showStatus("PCM: звук получен (RMS=$rms); распознаю…")
                        }
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            val original = JSONObject(recognizer.result).optString("text").trim()
                            if (original.isNotEmpty()) main.post { enqueueText(original) }
                        }
                    }
                    if (recording.get()) {
                        val rest = JSONObject(recognizer.finalResult).optString("text").trim()
                        if (rest.isNotEmpty()) main.post { enqueueText(rest) }
                    }
                }
            }
        } catch (e: Exception) {
            showStatus("Ошибка захвата / Vosk: ${e.message}")
        } finally {
            main.post { stopSelf() }
        }
    }

    private fun enqueueText(original: String) {
        if (!recording.get() || original.isBlank() || !playbackGate.allowsSpeech()) return
        if (pendingTexts.size >= 3) pendingTexts.removeFirst()
        pendingTexts.addLast(original)
        translateNext()
    }

    private fun translateNext() {
        if (!recording.get() || translating || !translatorReady || pendingTexts.isEmpty() || !playbackGate.allowsSpeech()) return
        val text = pendingTexts.removeFirst()
        val requestEpoch = translationEpoch
        translating = true
        translator?.translate(text) { result -> main.post {
            if (!recording.get()) return@post
            if (requestEpoch != translationEpoch || !playbackGate.allowsSpeech()) {
                translating = false
                translateNext()
                return@post
            }
            translating = false
            if (result.isFailure && yandexActive) {
                startLocalFallback(text, result.exceptionOrNull()?.message.orEmpty())
                return@post
            }
            result.onSuccess { translated ->
                lastSpoken = translated
                if (outputMode != OutputMode.VOICE) {
                    overlay?.update("$text\n\n$translated")
                    sendBroadcast(Intent(SubtitleEvents.ACTION).apply {
                        setPackage(packageName)
                        putExtra(SubtitleEvents.ORIGINAL, text)
                        putExtra(SubtitleEvents.TRANSLATED, translated)
                        putExtra(SubtitleEvents.SESSION, translationSession)
                        putExtra(SubtitleEvents.SEQUENCE, ++translationSequence)
                    }, SubtitleEvents.PERMISSION)
                }
                if (outputMode != OutputMode.SUBTITLES) {
                    if (ttsReady) {
                        // Bound pending audio: do not read minutes behind a live video.
                        tts?.speak(translated, TextToSpeech.QUEUE_FLUSH, null, "dub-${SystemClock.elapsedRealtime()}")
                    } else showStatus("Переведено, но русский голос TTS недоступен")
                }
            }.onFailure { error -> showStatus("Ошибка перевода: ${error.message}") }
            translateNext()
        } }
    }

    private fun startLocalFallback(text: String, cause: String) {
        yandexActive = false
        translatorReady = false
        pendingTexts.addFirst(text)
        translator?.close()
        val local = LocalTranslationEngine(sourceLanguage)
        translator = local
        showStatus("Yandex недоступен ($cause). Переключаюсь на локальный ML Kit…")
        local.prepare { result -> main.post {
            if (!recording.get()) return@post
            result.onSuccess {
                translatorReady = true
                showStatus("Локальный перевод активирован")
                translateNext()
            }.onFailure { showStatus("Не удалось включить резервный ML Kit: ${it.message}") }
        } }
    }

    private fun showStatus(text: String) = main.post {
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
            .putString("last", text).putLong("at", System.currentTimeMillis()).apply()
        if (recording.get() && outputMode != OutputMode.VOICE) overlay?.update(text)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Перевод VoiceDub", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        recording.set(false)
        try { unregisterReceiver(browserPlaybackReceiver) } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        captureThread?.interrupt()
        projectionCallback?.let { callback ->
            try { projection?.unregisterCallback(callback) } catch (_: Exception) {}
        }
        try { projection?.stop() } catch (_: Exception) {}
        translator?.close()
        tts?.stop()
        tts?.shutdown()
        overlay?.hide()
        super.onDestroy()
    }
}
