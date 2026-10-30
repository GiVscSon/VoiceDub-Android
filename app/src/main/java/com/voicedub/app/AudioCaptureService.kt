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
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Android 10+ user-approved internal PCM -> Vosk ASR -> selected text provider -> matching TTS. */
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
    private data class Segment(val text: String, val reference: ByteArray?)
    private val pendingTexts = ArrayDeque<Segment>()
    private val referenceBuffer = VoiceReference()
    private var cloneClient: CloneSpeechClient? = null
    private var clonedPlayer: MediaPlayer? = null
    private var clonedFile: File? = null
    private val captureEpoch = AtomicLong(0)
    @Volatile private var captureAllowed = true
    private var translating = false
    private var translatorReady = false
    private var translator: TranslationEngine? = null
    private var sourceLanguage = "en"
    private var targetLanguage = "ru"
    private var ttsInitialized = false
    private var yandexActive = false
    private var translationSession: Long = 0L
    private var translationSequence: Long = 0L
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val speechQueue = ArrayDeque<Segment>()
    private var activeUtterance: String? = null
    private var utteranceCounter = 0L
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
            captureAllowed = decision.allowNewSpeech
            if (decision.cancelQueuedSpeech) {
                translationEpoch++
                captureEpoch.incrementAndGet()
                referenceBuffer.clear()
                pendingTexts.clear()
                clearSpeechQueue()
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
                ttsInitialized = true
                configureTtsLanguage()
                // Accessibility audio is excluded from third-party playback capture:
                // avoid feeding dubbed speech back into the input.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { main.post { finishUtterance(utteranceId) } }
                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) { main.post { finishUtterance(utteranceId) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { finishUtterance(utteranceId) } }
                })
                speakNext()
            }
        }
    }

    private fun configureTtsLanguage() {
        if (!ttsInitialized) return
        val result = tts?.setLanguage(Locale.forLanguageTag(targetLanguage))
        ttsReady = result != null && result >= 0
        val warning = if (ttsReady) "" else "Нет голоса TTS для языка ${TranslationLanguages.resolve(targetLanguage)?.label ?: targetLanguage}. Установите голосовой пакет; субтитры доступны."
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("ttsWarning", warning).apply()
        if (warning.isNotEmpty()) showStatus(warning)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || !recording.compareAndSet(false, true)) return START_NOT_STICKY
        outputMode = OutputMode.parse(intent.getStringExtra("outputMode"))
        val target = TranslationLanguages.resolve(intent.getStringExtra("targetLanguage") ?: "ru")
        if (target == null) { showStatus("Язык перевода не поддерживается"); stopSelf(); return START_NOT_STICKY }
        targetLanguage = target.code
        configureTtsLanguage()
        translationSession = SystemClock.elapsedRealtimeNanos()
        val endpoint = intent.getStringExtra("voiceEndpoint").orEmpty()
        try {
            if (endpoint.isNotBlank() && outputMode != OutputMode.SUBTITLES) {
                require(TranslationLanguages.supportsMoss(targetLanguage)) { "MOSS пока поддерживает только русскую озвучку" }
                cloneClient = CloneSpeechClient(endpoint)
            }
        }
        catch (_: Exception) { recording.set(false); stopSelf(); return START_NOT_STICKY }

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
            val model = ModelCatalog.findByLanguageTag(intent.getStringExtra("lang"))
                ?: throw IllegalArgumentException("Неизвестный язык")
            overlay?.update("VoiceDub запущен · подготовка модели…")
            val autoLanguage = intent.getBooleanExtra("autoLocalLanguage", false) && LocalSpeechDetector.available
            if (!autoLanguage) getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("languageDetection", "Исходный язык выбран вручную: ${model.label}").apply()
            val backend = intent.getStringExtra("translationEngine") ?: "local"
            val key = intent.getStringExtra("yandexApiKey").orEmpty()
            val folder = intent.getStringExtra("yandexFolderId").orEmpty()
            captureThread = Thread({ captureLoop(grantedProjection, model, autoLanguage, backend, key, folder) }, "voicedub-asr").also { it.start() }
        } catch (e: Exception) {
            showStatus("Ошибка запуска: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun initTranslator(source: String, backend: String, apiKey: String, folderId: String) {
        sourceLanguage = source
        yandexActive = backend == "yandex-cloud" && source != targetLanguage
        translator = if (yandexActive) {
            YandexCloudTranslationEngine(source, apiKey, folderId, targetLanguage)
        } else LocalTranslationEngine(source, targetLanguage)
        translatorReady = false
        showStatus("Подготовка движка: ${if (source == targetLanguage) "без перевода" else if (yandexActive) "Yandex Cloud" else "ML Kit"}")
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

    private fun captureLoop(grantedProjection: MediaProjection, speechModel: SpeechModel, autoLanguage: Boolean, backend: String, key: String, folder: String) {
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
                .setBufferSizeInBytes(maxOf(minBytes, if (autoLanguage) SAMPLE_RATE * 2 * 20 else 32768))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord = capture
            check(capture.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован" }
            capture.startRecording()
            val probe = if (autoLanguage) detectSourceLanguage(capture, speechModel) else SourceProbe(speechModel, ByteArray(0), captureEpoch.get())
            if (!recording.get()) return
            main.post { if (recording.get()) initTranslator(probe.model.code, backend, key, folder) }
            Model(probe.model.directory(filesDir).absolutePath).use { model ->
                Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
                    val buffer = ByteArray(8192)
                    var seenEpoch = captureEpoch.get()
                    if (probe.epoch == captureEpoch.get() && probe.pcm.isNotEmpty() && recognizer.acceptWaveForm(probe.pcm, probe.pcm.size)) {
                        val original = JSONObject(recognizer.result).optString("text").trim()
                        main.post { if (probe.epoch == captureEpoch.get() && original.isNotEmpty()) enqueueText(original, null) }
                    }
                    showStatus("Захват PCM активен · ${probe.model.label}")
                    while (recording.get()) {
                        val n = capture.read(buffer, 0, buffer.size)
                        if (n <= 0) {
                            if (n < 0) showStatus("Ошибка чтения AudioRecord: $n")
                            break
                        }
                        val epoch = captureEpoch.get()
                        if (epoch != seenEpoch) { recognizer.reset(); referenceBuffer.clear(); seenEpoch = epoch }
                        if (!captureAllowed) continue
                        if (cloneClient != null) referenceBuffer.append(buffer, n - n % 2)
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
                            val reference = if (cloneClient != null) referenceBuffer.wav() else null
                            if (original.isNotEmpty()) main.post { if (epoch == captureEpoch.get()) enqueueText(original, reference) }
                        }
                    }
                    if (recording.get()) {
                        val rest = JSONObject(recognizer.finalResult).optString("text").trim()
                        val epoch = captureEpoch.get()
                        val reference = if (cloneClient != null) referenceBuffer.wav() else null
                        if (rest.isNotEmpty()) main.post { if (epoch == captureEpoch.get()) enqueueText(rest, reference) }
                    }
                }
            }
        } catch (e: Exception) {
            showStatus("Ошибка захвата / Vosk: ${e.message}")
        } finally {
            main.post { stopSelf() }
        }
    }

    private data class SourceProbe(val model: SpeechModel, val pcm: ByteArray, val epoch: Long)

    private fun detectSourceLanguage(capture: AudioRecord, fallback: SpeechModel): SourceProbe {
        val window = LanguageSampleWindow()
        val replay = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var epoch = captureEpoch.get()
        var sampled = 0
        showStatus("Определяю язык локально: нужно около 6 секунд речи…")
        while (recording.get() && !window.ready && sampled < 20 * SAMPLE_RATE * 2) {
            val n = capture.read(buffer, 0, buffer.size)
            if (n <= 0) break
            sampled += n
            if (epoch != captureEpoch.get()) { epoch = captureEpoch.get(); window.clear(); replay.reset() }
            if (!captureAllowed) continue
            val size = n - n % 2
            replay.write(buffer, 0, size); window.append(buffer, size)
        }
        var model = fallback
        var message = "Недостаточно речи; резервный язык: ${fallback.label}"
        if (recording.get() && window.ready) {
            try {
                val detector = LocalSpeechDetector.open(this)
                val detected = try { detector.detect(window.samples()) } finally { detector.close() }
                val candidate = ModelCatalog.findByLanguageTag(detected)
                if (candidate != null && candidate.directory(filesDir).resolve("am/final.mdl").isFile) {
                    model = candidate; message = "Локально определён язык: ${candidate.label}"
                } else message = if (detected == null) "Язык не определён уверенно; резерв: ${fallback.label}" else "Определён $detected, но модель распознавания не установлена; резерв: ${fallback.label}"
            } catch (_: Exception) { message = "Локальная модель недоступна; резерв: ${fallback.label}" }
            catch (_: LinkageError) { message = "Движок Tiny несовместим с устройством; резерв: ${fallback.label}" }
        }
        if (epoch != captureEpoch.get()) {
            model = fallback; replay.reset(); message = "Видео изменилось при определении языка; резерв: ${fallback.label}"
        }
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().putString("languageDetection", message).apply()
        showStatus(message)
        return SourceProbe(model, replay.toByteArray(), captureEpoch.get())
    }

    private fun enqueueText(original: String, reference: ByteArray?) {
        if (!recording.get() || original.isBlank() || !playbackGate.allowsSpeech()) return
        if (pendingTexts.size >= 3) pendingTexts.removeFirst()
        pendingTexts.addLast(Segment(original, reference))
        translateNext()
    }

    private fun translateNext() {
        if (!recording.get() || translating || !translatorReady || pendingTexts.isEmpty() || !playbackGate.allowsSpeech()) return
        val segment = pendingTexts.removeFirst()
        val text = segment.text
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
                startLocalFallback(segment, result.exceptionOrNull()?.message.orEmpty())
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
                    enqueueSpeech(Segment(translated, segment.reference))
                }
            }.onFailure { error -> showStatus("Ошибка перевода: ${error.message}") }
            translateNext()
        } }
    }

    /** Keep the current utterance intact; bound waiting speech to limit live lag. */
    private fun enqueueSpeech(segment: Segment) {
        if (segment.text.isBlank()) return
        if (speechQueue.size >= 2) speechQueue.removeFirst()
        speechQueue.addLast(segment)
        speakNext()
    }

    private fun speakNext() {
        if (!recording.get() || activeUtterance != null || speechQueue.isEmpty() || !playbackGate.allowsSpeech()) return
        if (cloneClient == null && !ttsReady) return
        val segment = speechQueue.removeFirst()
        val id = "dub-${++utteranceCounter}"
        activeUtterance = id
        val client = cloneClient
        if (client != null && segment.reference != null) {
            showStatus("MOSS: готовлю русскую речь с исходным голосом…")
            client.synthesize(segment.text, segment.reference) { result -> main.post {
                if (!recording.get() || activeUtterance != id) return@post
                result.onSuccess { data ->
                    try {
                        val file = File.createTempFile("dub-", ".wav", cacheDir)
                        clonedFile = file
                        file.writeBytes(data)
                        val player = MediaPlayer()
                        clonedPlayer = player
                        player.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                        player.setDataSource(file.absolutePath)
                        player.setOnCompletionListener { finishUtterance(id) }
                        player.setOnErrorListener { _, _, _ ->
                            if (activeUtterance == id) {
                                releaseClonedAudio(); showStatus("Ошибка аудио MOSS · обычный русский голос")
                                speakSystem(segment.text, id)
                            }
                            true
                        }
                        player.setOnPreparedListener {
                            if (activeUtterance == id && recording.get()) {
                                showStatus("MOSS: воспроизвожу полученную озвучку")
                                it.start()
                            }
                        }
                        player.prepareAsync()
                    } catch (_: Exception) {
                        releaseClonedAudio(); showStatus("Не удалось воспроизвести MOSS · обычный русский голос")
                        speakSystem(segment.text, id)
                    }
                }.onFailure {
                    showStatus("Сервер MOSS недоступен или занят · обычный русский голос")
                    speakSystem(segment.text, id)
                }
            } }
        } else {
            if (client != null) showStatus("Ещё нет 3 секунд образца · обычный русский голос")
            speakSystem(segment.text, id)
        }
    }

    private fun speakSystem(text: String, id: String) {
        if (activeUtterance != id || !recording.get()) return
        if (!ttsReady || tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
            showStatus("Русский голос TTS недоступен")
            finishUtterance(id)
        }
    }

    private fun finishUtterance(id: String?) {
        if (id == null || activeUtterance != id) return
        releaseClonedAudio()
        activeUtterance = null
        speakNext()
    }

    private fun releaseClonedAudio() {
        try { clonedPlayer?.release() } catch (_: Exception) { }
        clonedPlayer = null
        clonedFile?.delete(); clonedFile = null
    }

    private fun clearSpeechQueue() {
        speechQueue.clear()
        activeUtterance = null
        cloneClient?.cancel()
        releaseClonedAudio()
        tts?.stop()
    }

    private fun startLocalFallback(segment: Segment, cause: String) {
        yandexActive = false
        translatorReady = false
        pendingTexts.addFirst(segment)
        translator?.close()
        val local = LocalTranslationEngine(sourceLanguage, targetLanguage)
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
        referenceBuffer.clear()
        captureEpoch.incrementAndGet()
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
        clearSpeechQueue()
        cloneClient?.close()
        tts?.shutdown()
        overlay?.hide()
        super.onDestroy()
    }
}
