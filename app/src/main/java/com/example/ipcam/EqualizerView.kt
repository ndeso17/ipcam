package com.example.ipcam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 5
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeCap = Paint.Cap.ROUND
    }
    private val barValues = FloatArray(barCount) { 0.12f }
    private var animator: ValueAnimator? = null
    private var isActive = false

    fun setActive(active: Boolean) {
        if (active == isActive) return
        isActive = active
        if (active) {
            startAnimation()
            visibility = VISIBLE
        } else {
            stopAnimation()
            visibility = INVISIBLE
            invalidate()
        }
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 80
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                val amp = MicManager.amplitude.coerceIn(0f, 1f)
                for (i in barValues.indices) {
                    val target = if (amp > 0.005f) {
                        (amp * 3f * (0.6f + i * 0.15f)).coerceIn(0.08f, 1f)
                    } else {
                        0.08f + Math.random().toFloat() * 0.04f
                    }
                    barValues[i] = barValues[i] * 0.55f + target * 0.45f
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        for (i in barValues.indices) {
            barValues[i] = 0.08f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / (barCount * 2f)
        val barW = gap
        val cx = gap / 2f

        for (i in 0 until barCount) {
            val bh = h * barValues[i].coerceIn(0.05f, 1f)
            val left = cx + i * (barW + gap)
            val top = h - bh
            canvas.drawRoundRect(left, top, left + barW, h, barW / 2, barW / 2, barPaint)
        }
    }
}