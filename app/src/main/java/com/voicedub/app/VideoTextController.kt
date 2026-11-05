package com.voicedub.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/**
 * Visible-frame OCR for VoiceDub's own browser only.
 *
 * PixelCopy captures the *displayed* browser frame, not a video URL or a WebView
 * canvas, so it can work with normal cross-origin video pixels. DRM / FLAG_SECURE
 * frames may be unavailable. No frames are written to disk or sent to servers.
 */
class VideoTextController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val status: (String) -> Unit
) {
    private data class Line(val original: String, val bounds: Rect)
    private data class Label(val translated: String, val bounds: Rect)

    private val main = Handler(Looper.getMainLooper())
    private val overlay = LabelOverlay(activity).apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }
    private val intervalMs = 1500L
    private var enabled = false
    private var resumed = false
    private var closed = false
    private var busy = false
    private var generation = 0
    private var playerKey = ""
    private var frameBounds: Rect? = null
    private var language = ""
    private var target = ""
    private var recognizer: TextRecognizer? = null
    private var translator: LocalTranslationEngine? = null
    private var translatorReady = false
    private var lines: List<Line> = emptyList()
    private var lastFrameAt = 0L
    private var lastInfo = ""
    private val pending = mutableSetOf<String>()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 64
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || closed) return
            if (enabled && playerKey.isNotBlank() && frameBounds != null && !busy && recognizer != null) capture()
            if (lines.isNotEmpty() && SystemClock.elapsedRealtime() - lastFrameAt > 4500L) clearLabels()
            main.postDelayed(this, intervalMs)
        }
    }

    init { root.addView(overlay, FrameLayout.LayoutParams(-1, -1)) }

    fun bringToFront() { root.bringChildToFront(overlay) }

    fun setEnabled(value: Boolean) {
        if (enabled == value || closed) return
        enabled = value
        if (!value) {
            resetPlayer()
            info("OCR выключен")
        } else info("OCR включён: откройте воспроизводящееся видео")
        overlay.visibility = if (value && resumed) View.VISIBLE else View.GONE
    }

    fun resume() {
        if (closed) return
        resumed = true
        overlay.visibility = if (enabled) View.VISIBLE else View.GONE
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    fun pause() {
        resumed = false
        resetPlayer()
        main.removeCallbacks(ticker)
        overlay.visibility = View.GONE
        recognizer?.close()
        translator?.close()
        recognizer = null
        translator = null
        translatorReady = false
        language = ""
        target = ""
    }

    fun clearPlayer() { resetPlayer() }

    /** Bounds must be in activity-window pixels, never CSS pixels. */
    fun setPlayer(key: String, bounds: Rect?, playing: Boolean, source: String, dest: String) {
        if (!enabled || !resumed || closed) return
        val rect = bounds?.takeIf { it.width() >= 80 && it.height() >= 60 }
        if (!playing || key.isBlank() || rect == null) {
            if (playerKey.isNotBlank()) resetPlayer()
            return
        }
        val script = VideoTextPolicy.scriptFor(source)
        if (script == null) {
            resetPlayer()
            info("OCR: для языка $source пока нет локального распознавателя письма")
            return
        }
        val route = try { TranslationRoute.create(source, dest) } catch (_: IllegalArgumentException) {
            resetPlayer(); info("OCR: выберите поддерживаемое направление перевода"); return
        }
        if (key != playerKey) {
            resetPlayer()
            playerKey = key
        }
        if (language != route.source || target != route.target) {
            resetPlayer()
            playerKey = key
            recognizer?.close()
            translator?.close()
            language = route.source
            target = route.target
            recognizer = createRecognizer(script)
            translatorReady = false
            val engine = LocalTranslationEngine(language, target)
            translator = engine
            engine.prepare { result -> main.post {
                if (closed || engine !== translator || !enabled || !resumed) return@post
                translatorReady = result.isSuccess
                info(if (result.isSuccess) "OCR готов · $language → $target" else
                    "OCR: не удалось загрузить языковую модель перевода")
                if (result.isSuccess) translateVisible()
            } }
        }
        frameBounds = Rect(rect)
    }

    private fun createRecognizer(script: VideoTextScript): TextRecognizer = when (script) {
        VideoTextScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        VideoTextScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        VideoTextScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        VideoTextScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        VideoTextScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private fun capture() {
        val bounds = frameBounds ?: return
        val recognizer = recognizer ?: return
        val windowRect = Rect(bounds)
        val rootPos = IntArray(2)
        root.getLocationInWindow(rootPos)
        val sample = VideoTextPolicy.maxSampleSize(windowRect.width(), windowRect.height())
        val bitmap = try { Bitmap.createBitmap(sample.first, sample.second, Bitmap.Config.ARGB_8888) }
        catch (_: RuntimeException) { info("OCR: недостаточно памяти для кадра"); return }
        val epoch = generation
        busy = true
        // Do not teach OCR to recognize its own translated overlay.
        overlay.visibility = View.INVISIBLE
        try {
            PixelCopy.request(activity.window, windowRect, bitmap, { code ->
                overlay.visibility = if (enabled && resumed) View.VISIBLE else View.GONE
                if (closed || epoch != generation || !enabled || !resumed || code != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    busy = false
                    if (code != PixelCopy.SUCCESS && enabled && resumed) info("OCR: кадр недоступен (возможно, защищённое видео)")
                    return@request
                }
                try {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { found ->
                            if (!closed && epoch == generation && enabled && resumed) {
                                consume(found, windowRect, bitmap.width, bitmap.height, rootPos)
                            }
                        }
                        .addOnFailureListener { if (epoch == generation) info("OCR: не удалось распознать кадр") }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            busy = false
                        }
                } catch (_: RuntimeException) {
                    bitmap.recycle()
                    busy = false
                    info("OCR: ошибка обработки изображения")
                }
            }, main)
        } catch (_: IllegalArgumentException) {
            overlay.visibility = if (enabled && resumed) View.VISIBLE else View.GONE
            bitmap.recycle()
            busy = false
            info("OCR: область видео вышла за границы экрана")
        }
    }

    private fun consume(found: Text, frame: Rect, imageW: Int, imageH: Int, rootPos: IntArray) {
        val sx = frame.width().toFloat() / imageW
        val sy = frame.height().toFloat() / imageH
        lines = found.textBlocks.asSequence().flatMap { it.lines.asSequence() }.mapNotNull { line ->
            val text = VideoTextPolicy.cleanLine(line.text) ?: return@mapNotNull null
            val box = line.boundingBox ?: return@mapNotNull null
            if (box.width() < 12 || box.height() < 8) return@mapNotNull null
            Line(text, Rect(
                frame.left - rootPos[0] + (box.left * sx).roundToInt(),
                frame.top - rootPos[1] + (box.top * sy).roundToInt(),
                frame.left - rootPos[0] + (box.right * sx).roundToInt(),
                frame.top - rootPos[1] + (box.bottom * sy).roundToInt()
            ))
        }.take(6).toList()
        lastFrameAt = SystemClock.elapsedRealtime()
        render()
        translateVisible()
    }

    private fun translateVisible() {
        if (!translatorReady) return
        val engine = translator ?: return
        val epoch = generation
        for (line in lines) {
            val original = line.original
            if (cache.containsKey(original) || pending.contains(original) || pending.size >= 3) continue
            pending.add(original)
            engine.translate(original) { result -> main.post {
                pending.remove(original)
                if (closed || epoch != generation || !enabled || !resumed) return@post
                result.onSuccess { translated ->
                    if (translated.isNotBlank()) cache[original] = translated.take(200)
                    render()
                }.onFailure { info("OCR: модель перевода не смогла обработать текст") }
                translateVisible()
            } }
        }
    }

    private fun render() {
        overlay.show(lines.mapNotNull { line ->
            cache[line.original]?.let { Label(it, line.bounds) }
        })
    }

    private fun clearLabels() { lines = emptyList(); overlay.show(emptyList()) }

    private fun resetPlayer() {
        generation++
        playerKey = ""
        frameBounds = null
        pending.clear()
        cache.clear()
        clearLabels()
    }

    private fun info(message: String) {
        if (lastInfo != message) { lastInfo = message; status(message) }
    }

    fun close() {
        if (closed) return
        pause()
        closed = true
        recognizer?.close()
        translator?.close()
        recognizer = null
        translator = null
        root.removeView(overlay)
    }

    private class LabelOverlay(activity: Activity) : View(activity) {
        private val density = resources.displayMetrics.density
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f * resources.displayMetrics.scaledDensity
            isFakeBoldText = true
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD112235.toInt() }
        private var labels: List<Label> = emptyList()
        fun show(values: List<Label>) { labels = values; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val horizontal = 7f * density
            val vertical = 5f * density
            for (label in labels) {
                val available = (width - horizontal * 4).coerceAtMost(380f * density)
                if (available <= 0f) continue
                val safeText = TextUtils.ellipsize(label.translated.replace('\n', ' '), textPaint,
                    available, TextUtils.TruncateAt.END).toString()
                val textW = textPaint.measureText(safeText)
                val boxW = textW + 2 * horizontal
                val boxH = textPaint.fontSpacing + 2 * vertical
                val left = (label.bounds.left.toFloat()).coerceIn(0f, (width - boxW).coerceAtLeast(0f))
                val proposed = label.bounds.bottom + 3f * density
                val top = if (proposed + boxH < height) proposed else
                    (label.bounds.top - boxH - 3f * density).coerceAtLeast(0f)
                canvas.drawRoundRect(left, top, left + boxW, top + boxH, 5f * density, 5f * density, bgPaint)
                canvas.drawText(safeText, left + horizontal,
                    top + vertical - textPaint.fontMetrics.top, textPaint)
            }
        }
    }
}
