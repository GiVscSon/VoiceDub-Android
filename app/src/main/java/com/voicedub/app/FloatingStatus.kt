package com.voicedub.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Minimal translated captions: displays short text above any app when allowed. */
class FloatingStatus(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null

    fun update(text: String) {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(context)) return
        val existing = view
        if (existing != null) {
            existing.text = text
            return
        }
        val textView = TextView(context).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(225, 21, 28, 41))
            setPadding(22, 16, 22, 16)
            gravity = Gravity.CENTER
            maxLines = 5
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        try {
            wm.addView(textView, params)
            view = textView
        } catch (_: Exception) { /* Overlay denied or OEM policy */ }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }
}
