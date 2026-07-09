package com.talkbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A scrolling voice-level meter: a row of vertical bars whose heights follow
 * recent microphone energy, like a graphic equaliser / waveform display.
 * Call push(level) with values 0..1 (~10 times per second) from any thread.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BARS = 28
    }

    private val levels = FloatArray(BARS)

    @Volatile
    private var head = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33888888
    }

    /** Feed one level sample (0..1). Thread-safe. */
    fun push(level: Float) {
        levels[head] = level.coerceIn(0f, 1f)
        head = (head + 1) % BARS
        postInvalidate()
    }

    /** Change the bar colour (e.g. per detected language). Thread-safe. */
    fun setAccent(color: Int) {
        barPaint.color = color
        postInvalidate()
    }

    fun clear() {
        levels.fill(0f)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val slot = w / BARS
        val barW = slot * 0.6f
        val minH = h * 0.08f
        for (i in 0 until BARS) {
            // Oldest on the left, newest on the right
            val level = levels[(head + i) % BARS]
            val x = i * slot + (slot - barW) / 2
            val barH = minH + level * (h - minH)
            val top = (h - barH) / 2
            val paint = if (level > 0.02f) barPaint else idlePaint
            canvas.drawRoundRect(x, top, x + barW, top + barH, barW / 2, barW / 2, paint)
        }
    }
}
