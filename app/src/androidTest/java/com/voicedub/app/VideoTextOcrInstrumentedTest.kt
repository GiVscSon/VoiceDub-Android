package com.voicedub.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Smoke-test the bundled OCR recognizer on an Android device/emulator.
 * This does not establish that PixelCopy captures protected or external video.
 */
@RunWith(AndroidJUnit4::class)
class VideoTextOcrInstrumentedTest {
    @Test fun bundledLatinOcrReadsSyntheticFrame() {
        val bitmap = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 105f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("HELLO WORLD", 40f, 150f, paint)
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                35, TimeUnit.SECONDS
            )
            assertTrue("OCR must read a visible, high-contrast phrase, got: ${result.text}",
                result.text.uppercase(Locale.ROOT).contains("HELLO"))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
