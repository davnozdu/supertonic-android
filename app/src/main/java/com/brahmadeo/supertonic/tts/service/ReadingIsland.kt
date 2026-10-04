package com.brahmadeo.supertonic.tts.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.brahmadeo.supertonic.tts.QuickReadActivity

/** Optional experimental overlay; it does not access the camera or own audio/media focus. */
object ReadingIsland {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var view: LinearLayout? = null
    private var button: TextView? = null
    private var caption: TextView? = null
    private var playing = false
    private var visible = false
    private var cutoutX: Int? = null
    private var cutoutBottom = 0
    private val tick = object : Runnable {
        override fun run() { refresh(); if (view != null) main.postDelayed(this, 1000) }
    }
    fun enabled(ctx: Context) = ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).getBoolean("camera_island", false)
    fun offset(ctx: Context) = ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).getInt("island_y", 0)
    fun configure(ctx: Context, enabled: Boolean, offset: Int = offset(ctx)) {
        ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit().putBoolean("camera_island", enabled)
            .putInt("island_y", offset.coerceIn(0, 80)).apply()
        main.post {
            context = ctx.applicationContext; refresh()
            main.removeCallbacks(tick); if (view != null) main.postDelayed(tick, 1000)
        }
    }
    fun state(ctx: Context, active: Boolean, isPlaying: Boolean) { main.post {
        context = ctx.applicationContext; visible = active; playing = isPlaying
        refresh()
        main.removeCallbacks(tick)
        if (view != null) main.postDelayed(tick, 1000)
    } }
    private fun refresh() {
        val ctx = context ?: return
        if (!visible || !enabled(ctx) || !Settings.canDrawOverlays(ctx)) { remove(); return }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        if (view == null) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(28).toFloat() }
                elevation = dp(4).toFloat()
            }
            button = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
                contentDescription = "Плей или пауза чтения"
                setOnClickListener { if (playing) ReadingControls.pause(ctx) else ReadingControls.play(ctx) }
            }
            caption = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER; maxLines = 1
                setOnClickListener { ctx.startActivity(Intent(ctx, QuickReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                contentDescription = "Открыть панель MyTTS"
            }
            row.addView(button, LinearLayout.LayoutParams(dp(78), dp(42)))
            row.addView(View(ctx), LinearLayout.LayoutParams(dp(48), dp(42)))
            row.addView(caption, LinearLayout.LayoutParams(dp(78), dp(42)))
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(dp(204), dp(42), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
            fun position() {
                val width = maxOf(
                    if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds.width() else 0,
                    ctx.resources.displayMetrics.widthPixels, (cutoutX ?: 0) * 2, dp(204))
                params.x = ((cutoutX ?: width / 2) - dp(102)).coerceIn(0, (width - dp(204)).coerceAtLeast(0))
                params.y = dp(offset(ctx))
                params.height = maxOf(dp(42), cutoutBottom - params.y + dp(2))
            }
            position()
            if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                row.setOnApplyWindowInsetsListener { _, insets ->
                    val rect = insets.displayCutout?.boundingRects?.firstOrNull { it.top == 0 && it.width() < dp(100) }
                    if (rect != null && (cutoutX != rect.centerX() || cutoutBottom != rect.bottom)) {
                        cutoutX = rect.centerX(); cutoutBottom = rect.bottom
                        position(); runCatching { manager.updateViewLayout(row, params) }
                    }
                    insets
                }
            }
            runCatching { manager.addView(row, params); view = row
                android.util.Log.i("ReadingIsland", "Overlay x=${params.x} y=${params.y} width=${params.width} height=${params.height}")
            }.onFailure { view = null }
        }
        button?.text = if (playing) "Ⅱ" else "▶"
        caption?.text = SleepTimer.remainingLabel().ifBlank { "MyTTS" }
        view?.let { row ->
            val params = row.layoutParams as WindowManager.LayoutParams
            val y = dp(offset(ctx))
            if (params.y != y) { params.y = y; runCatching { manager.updateViewLayout(row, params) } }
        }
    }
    private fun remove() {
        main.removeCallbacks(tick)
        view?.let { row -> context?.let { ctx -> runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(row) } } }
        view = null; button = null; caption = null
    }
}
