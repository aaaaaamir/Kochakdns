package com.example.kochakdns

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.PathParser

/**
 * سوییچ انیمیشنی سفارشی (الهام از Uiverse "Galahhad"):
 * - مسیر قرصی‌شکل که با جابه‌جایی دایره، رنگش نرم تغییر می‌کند.
 * - آیکون «ضربدر» در حالت خاموش و «تیک» در حالت روشن، با انیمیشن scale.
 * - رنگ روشن با تم برنامه (آبی #4C8DFF) هماهنگ است.
 */
class AnimatedSwitchView(context: Context) : View(context) {

    companion object {
        private const val ANIM_DURATION = 260L
        // cubic-bezier(0.27, 0.2, 0.25, 1.51) — همان حس فنریِ طرح اصلی
        private val SPRING = PathInterpolator(0.27f, 0.2f, 0.25f, 1.51f)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val trackOn = Color.parseColor("#4C8DFF")
    private val trackOff = Color.parseColor("#4A4A56")
    private val circleColor = Color.WHITE
    private val crossColor = Color.parseColor("#4A4A56")
    private val checkColor = Color.parseColor("#4C8DFF")

    private var progress = 0f   // 0 = خاموش، 1 = روشن
    private var checked = false
    private var animator: ValueAnimator? = null
    private val evaluator = android.animation.ArgbEvaluator()

    // مسیر آیکون ضربدر (viewBox=365.696)
    private val crossPath: Path = PathParser.createPathFromPathData(
        "M243.188 182.86 356.32 69.726c12.5-12.5 12.5-32.766 0-45.247L341.238 9.398c-12.504-12.503-32.77-12.503-45.25 0L182.86 122.528 69.727 9.374c-12.5-12.5-32.766-12.5-45.247 0L9.375 24.457c-12.5 12.504-12.5 32.77 0 45.25l113.152 113.152L9.398 295.99c-12.503 12.503-12.503 32.769 0 45.25L24.48 356.32c12.5 12.5 32.766 12.5 45.247 0l113.132-113.132L295.99 356.32c12.503 12.5 32.769 12.5 45.25 0l15.081-15.082c12.5-12.504 12.5-32.77 0-45.25z"
    )
    // مسیر آیکون تیک (viewBox=24)
    private val checkPath: Path = PathParser.createPathFromPathData(
        "M9.707 19.121a.997.997 0 0 1-1.414 0l-5.646-5.647a1.5 1.5 0 0 1 0-2.121l.707-.707a1.5 1.5 0 0 1 2.121 0L9 14.171l9.525-9.525a1.5 1.5 0 0 1 2.121 0l.707.707a1.5 1.5 0 0 1 0 2.121z"
    )

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    val isChecked: Boolean
        get() = checked

    /** تنظیم حالت بدون انیمیشن (برای مقداردهی اولیه). */
    fun setChecked(value: Boolean) {
        if (checked == value) {
            progress = if (value) 1f else 0f
            invalidate()
            return
        }
        checked = value
        animateTo(if (value) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ANIM_DURATION
            interpolator = SPRING
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val newValue = !checked
            checked = newValue
            animateTo(if (newValue) 1f else 0f)
            onCheckedChangeListener?.invoke(newValue)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ===== مسیر (ترک) قرصی =====
        trackPaint.color = evaluator.evaluate(progress, trackOff, trackOn) as Int
        val trackRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, h / 2f, h / 2f, trackPaint)

        // ===== دایره =====
        val pad = h * 0.10f
        val circleD = h - 2 * pad
        val travel = w - circleD - 2 * pad
        val circleX = pad + travel * progress
        val circleY = pad
        val centerX = circleX + circleD / 2f
        val centerY = circleY + circleD / 2f

        circlePaint.color = circleColor
        circlePaint.setShadowLayer(3f, 0f, 1f, Color.parseColor("#55888888"))
        canvas.drawCircle(centerX, centerY, circleD / 2f, circlePaint)
        circlePaint.clearShadowLayer()

        // ===== آیکون ضربدر (محو شدن هنگام روشن) =====
        val crossScale = 1f - progress
        if (crossScale > 0.02f) {
            iconPaint.color = crossColor
            drawIcon(canvas, crossPath, centerX, centerY, circleD * 0.5f * crossScale, 365.696f)
        }
        // ===== آیکون تیک (ظاهر شدن هنگام روشن) =====
        val checkScale = progress
        if (checkScale > 0.02f) {
            iconPaint.color = checkColor
            drawIcon(canvas, checkPath, centerX, centerY, circleD * 0.5f * checkScale, 24f)
        }
    }

    private fun drawIcon(canvas: Canvas, path: Path, cx: Float, cy: Float, size: Float, viewbox: Float) {
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        val s = size / viewbox
        canvas.scale(s, s)
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
