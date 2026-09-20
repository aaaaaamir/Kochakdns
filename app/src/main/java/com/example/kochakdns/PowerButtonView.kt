package com.example.kochakdns

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * دکمه پاور با ۴ فاز انیمیشن + HOLD.
 * کنترل وضعیت از بیرون توسط DnsActivity انجام می‌شود.
 */
class PowerButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val STATE_OFF = 0
        const val STATE_TURNING_ON = 1
        const val STATE_EMERGING = 2
        const val STATE_ON = 3
        const val STATE_TURNING_OFF = 4

        // ===== پالت رنگ =====
        private val COL_OFF_BG      = 0xFF181829.toInt()
        private val COL_OFF_BORDER  = 0xFF2A2A40.toInt()
        private val COL_OFF_ICON    = 0xFF4A4A6A.toInt()

        private val COL_ON_BG       = 0xFF181829.toInt()   // موقع morph: داخل تیره می‌مونه
        private val COL_ON_BORDER   = 0xFFFACC15.toInt()   // فقط حاشیه زرد
        private val COL_ON_ICON     = 0xFFFFFFFF.toInt()

        private val COL_FINAL_BG    = 0xFF22C55E.toInt()
        private val COL_FINAL_BORDER= 0xFF16A34A.toInt()
        private val COL_FINAL_ICON  = 0xFFFFFFFF.toInt()
    }

    private var currentState = STATE_OFF

    // ---- مقادیر انیمیشنی ----
    private var fillScale = 0f
    private var ringSquashX = 1f
    private var ringSquashY = 1f
    private var ringAlpha = 1f
    private var stemSquashX = 1f
    private var stemSquashY = 1f
    private var stemAlpha = 1f
    private var iconOffsetY = 0f
    private var iconSquashX = 1f
    private var iconSquashY = 1f
    private var iconRotation = 0f
    private var shadowAlpha = 0f
    private var shadowScaleX = 1f
    private var shadowScaleY = 1f
    private var iconBlur = 0f

    private var holdPulse = 1f
    private var inHold = false

    private var breath = 1f
    private var breathAnimator: ValueAnimator? = null

    private var bgColor = COL_OFF_BG
    private var borderColor = COL_OFF_BORDER
    private var iconColor = COL_OFF_ICON
    private var colorAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var iconSize = 0f
    private var ringRadius = 0f
    private val density = resources.displayMetrics.density

    private val animators = mutableListOf<ValueAnimator>()
    private var pendingFinish = false

    init {
        bgPaint.style = Paint.Style.FILL
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = dp(2.6f)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeCap = Paint.Cap.ROUND
        ballPaint.style = Paint.Style.FILL
        shadowPaint.style = Paint.Style.FILL
        shadowPaint.color = 0xAA000000.toInt()

        // بلور روی بوم نرم‌افزاری (قبل از API 28 در بوم سخت‌افزاری نادیده گرفته می‌شود)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(v: Float) = v * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = minOf(w, h) / 2f - dp(1.5f)
        iconSize = minOf(w, h) * (68f / 220f)
        ringRadius = iconSize * (9f / 24f)
        iconPaint.strokeWidth = iconSize * (2.5f / 24f)
    }

    // ==================== API عمومی ====================

    fun startConnecting() {
        if (currentState == STATE_TURNING_ON || currentState == STATE_EMERGING) return
        cancelAll()
        resetAll()
        animateColorsTo(STATE_TURNING_ON, 500)
        setState(STATE_TURNING_ON)
        startMorphPhase()
    }

    fun finishConnecting() {
        when (currentState) {
            STATE_TURNING_ON -> {
                if (inHold) {
                    cancelAll()
                    startEmerging()
                } else {
                    pendingFinish = true
                }
            }
            STATE_EMERGING -> { /* در حال ظاهر شدن */ }
            else -> {
                cancelAll()
                resetAll()
                snapColors(STATE_ON)
                setState(STATE_ON)
            }
        }
    }

    /**
     * شروع قطع: چرخش + بلور + FADE فوری رنگ سبز به تیره.
     * رنگ دیگه روی سبز قفل نمی‌مونه؛ همون لحظه‌ی کلیک، محو شدن شروع می‌شه.
     */
    fun startDisconnecting() {
        if (currentState == STATE_TURNING_OFF) return
        cancelAll()
        resetAll()
        // ⭐ رنگ فوراً شروع می‌کنه به محو شدن به سمت تیره (همزمان با چرخش)
        animateColorsTo(STATE_OFF, 500)
        setState(STATE_TURNING_OFF)
        startSpinPhase()
    }

    fun notifyDisconnected() {
        if (currentState == STATE_TURNING_OFF) return
        cancelAll()
        resetAll()
        animateColorsTo(STATE_OFF, 500)
        setState(STATE_OFF)
    }

    fun snapTo(state: Int) {
        cancelAll()
        resetAll()
        snapColors(state)
        setState(state)
    }

    fun startIdleBreathing() {
        if (currentState != STATE_OFF) return
        if (breathAnimator?.isRunning == true) return
        breathAnimator = ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopIdleBreathing() {
        breathAnimator?.cancel()
        breathAnimator = null
        breath = 1f
        invalidate()
    }

    // ==================== کمکی‌ها ====================

    private fun resetAll() {
        fillScale = 0f
        ringSquashX = 1f; ringSquashY = 1f; ringAlpha = 1f
        stemSquashX = 1f; stemSquashY = 1f; stemAlpha = 1f
        iconOffsetY = 0f
        iconSquashX = 1f; iconSquashY = 1f
        iconRotation = 0f
        shadowAlpha = 0f
        shadowScaleX = 1f; shadowScaleY = 1f
        iconBlur = 0f
        holdPulse = 1f
        inHold = false
        invalidate()
    }

    private fun setState(s: Int) {
        currentState = s
        invalidate()
    }

    private fun cancelAll() {
        pendingFinish = false
        inHold = false
        for (a in animators) {
            a.removeAllListeners()
            a.cancel()
        }
        animators.clear()
        colorAnimator?.cancel()
        colorAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        breath = 1f
    }

    // ==================== انیمیشن رنگ ====================

    private fun animateColorsTo(state: Int, durationMs: Long) {
        val (tb, tBo, ti) = colorsFor(state)
        val fb = bgColor
        val fBo = borderColor
        val fi = iconColor

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                bgColor = lerpColor(fb, tb, p)
                borderColor = lerpColor(fBo, tBo, p)
                iconColor = lerpColor(fi, ti, p)
                invalidate()
            }
            start()
        }
    }

    private fun snapColors(state: Int) {
        val (b, bo, i) = colorsFor(state)
        bgColor = b
        borderColor = bo
        iconColor = i
    }

    private fun colorsFor(state: Int): Triple<Int, Int, Int> {
        return when (state) {
            STATE_OFF, STATE_TURNING_OFF ->
                Triple(COL_OFF_BG, COL_OFF_BORDER, COL_OFF_ICON)
            STATE_TURNING_ON ->
                Triple(COL_ON_BG, COL_ON_BORDER, COL_ON_ICON)
            STATE_EMERGING, STATE_ON ->
                Triple(COL_FINAL_BG, COL_FINAL_BORDER, COL_FINAL_ICON)
            else ->
                Triple(COL_OFF_BG, COL_OFF_BORDER, COL_OFF_ICON)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    // ==================== Interpolation ====================
    private fun keyframe(p: Float, vararg tv: Float): Float {
        val n = tv.size / 2
        if (n == 0) return 0f
        if (p <= tv[0]) return tv[1]
        if (p >= tv[2 * (n - 1)]) return tv[2 * (n - 1) + 1]
        for (i in 0 until n - 1) {
            val t1 = tv[2 * i]; val v1 = tv[2 * i + 1]
            val t2 = tv[2 * i + 2]; val v2 = tv[2 * i + 3]
            if (p <= t2) {
                val local = (p - t1) / (t2 - t1)
                return v1 + (v2 - v1) * local
            }
        }
        return tv[2 * (n - 1) + 1]
    }

    // ==================== فاز ۱: MORPH ====================
    private fun startMorphPhase() {
        val morph = ValueAnimator.ofFloat(0f, 1f).apply { duration = 550 }
        morph.addUpdateListener { a ->
            val p = a.animatedValue as Float

            fillScale = keyframe(p, 0f, 0f, 0.70f, 1.08f, 1f, 1f)

            ringSquashX = keyframe(p, 0f, 1f, 0.25f, 1.08f, 0.55f, 0.95f, 0.80f, 0.97f, 1f, 0.80f)
            ringSquashY = keyframe(p, 0f, 1f, 0.25f, 0.94f, 0.55f, 1.05f, 0.80f, 0.94f, 1f, 0.80f)
            ringAlpha = keyframe(p, 0f, 1f, 0.25f, 1f, 0.55f, 0.60f, 0.80f, 0.25f, 1f, 0f)

            stemSquashX = keyframe(p, 0f, 1f, 0.25f, 0.94f, 0.55f, 1.05f, 0.80f, 1.08f, 1f, 1.10f)
            stemSquashY = keyframe(p, 0f, 1f, 0.25f, 1.08f, 0.55f, 0.85f, 0.80f, 0.50f, 1f, 0f)
            stemAlpha = keyframe(p, 0f, 1f, 0.25f, 1f, 0.55f, 0.80f, 0.80f, 0.35f, 1f, 0f)

            shadowAlpha = 0f
            invalidate()
        }
        morph.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (currentState == STATE_TURNING_ON) startJumpPhase()
            }
        })
        animators.add(morph)
        morph.start()
    }

    // ==================== فاز ۲: JUMP ====================
    private fun startJumpPhase() {
        val jump = ValueAnimator.ofFloat(0f, 1f).apply { duration = 700 }
        jump.addUpdateListener { a ->
            val p = a.animatedValue as Float

            iconOffsetY = keyframe(p, 0f, 0f, 0.12f, 0f, 0.45f, -iconSize * 0.62f, 0.80f, 0f, 1f, 0f)
            iconSquashX = keyframe(p, 0f, 1f, 0.12f, 1.05f, 0.45f, 0.97f, 0.80f, 1.06f, 1f, 1f)
            iconSquashY = keyframe(p, 0f, 1f, 0.12f, 0.96f, 0.45f, 1.03f, 0.80f, 0.95f, 1f, 1f)

            shadowAlpha = keyframe(p, 0f, 0.65f, 0.12f, 0.85f, 0.45f, 0.12f, 0.80f, 0.90f, 0.95f, 0.60f, 1f, 0f)
            shadowScaleX = keyframe(p, 0f, 1.05f, 0.12f, 1.15f, 0.45f, 0.35f, 0.80f, 1.20f, 1f, 1f)
            shadowScaleY = keyframe(p, 0f, 0.95f, 0.12f, 1.05f, 0.45f, 0.50f, 0.80f, 1.12f, 1f, 1f)

            invalidate()
        }
        jump.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (currentState != STATE_TURNING_ON) return
                if (pendingFinish) startEmerging() else startHoldPhase()
            }
        })
        animators.add(jump)
        jump.start()
    }

    // ==================== فاز ۲.۵: HOLD ====================
    private fun startHoldPhase() {
        inHold = true
        val hold = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1100 }
        hold.repeatCount = ValueAnimator.INFINITE
        hold.addUpdateListener { a ->
            val p = a.animatedValue as Float
            iconOffsetY = 0f
            shadowAlpha = keyframe(p, 0f, 0f, 0.15f, 0.45f, 0.50f, 0.70f, 0.85f, 0.45f, 1f, 0f)
            shadowScaleX = keyframe(p, 0f, 0.90f, 0.50f, 1.00f, 1f, 0.90f)
            shadowScaleY = keyframe(p, 0f, 0.80f, 0.50f, 1.00f, 1f, 0.80f)
            holdPulse = keyframe(p, 0f, 1f, 0.50f, 1.07f, 1f, 1f)
            invalidate()
        }
        animators.add(hold)
        hold.start()
    }

    // ==================== فاز ۳: EMERGING ====================
    private fun startEmerging() {
        pendingFinish = false
        inHold = false
        holdPulse = 1f
        animateColorsTo(STATE_EMERGING, 500)
        setState(STATE_EMERGING)

        val emerge = ValueAnimator.ofFloat(0f, 1f).apply { duration = 650 }
        emerge.addUpdateListener { a ->
            val p = a.animatedValue as Float

            fillScale = keyframe(p, 0f, 1f, 1f, 0f)

            ringSquashX = keyframe(p, 0f, 0.01f, 0.55f, 1.06f, 0.75f, 0.98f, 1f, 1f)
            ringSquashY = keyframe(p, 0f, 0.01f, 0.55f, 0.95f, 0.75f, 1.02f, 1f, 1f)
            ringAlpha = 1f

            stemSquashX = keyframe(p, 0f, 0.01f, 0.55f, 0.96f, 0.75f, 1.02f, 1f, 1f)
            stemSquashY = keyframe(p, 0f, 0.01f, 0.55f, 1.08f, 0.75f, 0.98f, 1f, 1f)
            stemAlpha = 1f

            iconOffsetY = 0f
            iconSquashX = 1f; iconSquashY = 1f
            shadowAlpha = 0f
            invalidate()
        }
        emerge.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (currentState == STATE_EMERGING) {
                    resetAll()
                    setState(STATE_ON)
                }
            }
        })
        animators.add(emerge)
        emerge.start()
    }

    // ==================== فاز ۴: TURNING OFF (چرخش + بلور نرم) ====================
    private fun startSpinPhase() {
        val spin = ValueAnimator.ofFloat(0f, 1f).apply { duration = 800 }
        spin.addUpdateListener { a ->
            val p = a.animatedValue as Float
            iconRotation = 360f * p

            // ⭐ منحنی نرم sine-squared:
            //   از 20% شروع، اوج در 50%، پایان در 80%
            //   15 کیفریم برای رفت و برگشت کاملاً نرم
            iconBlur = keyframe(p,
                0.00f, 0.00f,
                0.20f, 0.00f,
                0.25f, 0.10f,
                0.30f, 0.30f,
                0.35f, 0.55f,
                0.40f, 0.78f,
                0.45f, 0.94f,
                0.50f, 1.00f,
                0.55f, 0.94f,
                0.60f, 0.78f,
                0.65f, 0.55f,
                0.70f, 0.30f,
                0.75f, 0.10f,
                0.80f, 0.00f,
                1.00f, 0.00f) * dp(5f)

            invalidate()
        }
        spin.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                iconRotation = 0f
                iconBlur = 0f
                if (currentState == STATE_TURNING_OFF) {
                    resetAll()
                    setState(STATE_OFF)
                }
            }
        })
        animators.add(spin)
        spin.start()
    }

    // ==================== رسم ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // خود دکمه
        bgPaint.color = bgColor
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // حاشیه
        borderPaint.color = borderColor
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // سایه‌ی زیر توپ
        if (shadowAlpha > 0.001f) {
            canvas.save()
            canvas.translate(cx, cy + iconSize * 0.55f)
            canvas.scale(shadowScaleX, shadowScaleY)

            val sw = iconSize * 0.42f
            val aCenter = (242 * shadowAlpha).toInt().coerceIn(0, 255)
            val aMid = (178 * shadowAlpha).toInt().coerceIn(0, 255)
            val aOuter = (77 * shadowAlpha).toInt().coerceIn(0, 255)

            shadowPaint.shader = RadialGradient(
                0f, 0f, sw,
                intArrayOf(
                    Color.argb(aCenter, 0, 0, 0),
                    Color.argb(aMid, 0, 0, 0),
                    Color.argb(aOuter, 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.scale(1f, 0.20f)
            canvas.drawCircle(0f, 0f, sw, shadowPaint)
            canvas.restore()

            canvas.restore()
        }

        // آیکون
        canvas.save()
        canvas.translate(cx, cy + iconOffsetY)
        canvas.rotate(iconRotation)
        canvas.scale(iconSquashX * breath, iconSquashY * breath)

        // افکت بلور
        if (iconBlur > 0.05f) {
            try {
                iconPaint.maskFilter = BlurMaskFilter(iconBlur, BlurMaskFilter.Blur.NORMAL)
                ballPaint.maskFilter = BlurMaskFilter(iconBlur, BlurMaskFilter.Blur.NORMAL)
            } catch (_: Exception) { }
        } else {
            iconPaint.maskFilter = null
            ballPaint.maskFilter = null
        }

        // ۱) توپ پرکننده
        if (fillScale > 0.001f) {
            val r = ringRadius * (10.8f / 9f) * fillScale * holdPulse
            ballPaint.shader = RadialGradient(
                0f, r * 0.3f, r * 1.05f,
                intArrayOf(
                    Color.WHITE,
                    0xFFF8F8FC.toInt(),
                    0xFFD8D8E2.toInt(),
                    0xFF8A8A9C.toInt()
                ),
                floatArrayOf(0f, 0.45f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(0f, 0f, r, ballPaint)
        }

        // ۲) حلقه
        if (ringAlpha > 0.001f) {
            canvas.save()
            canvas.scale(ringSquashX, ringSquashY)
            iconPaint.color = iconColor
            iconPaint.alpha = (255 * ringAlpha).toInt()
            val ringRect = RectF(-ringRadius, -ringRadius, ringRadius, ringRadius)
            canvas.drawArc(ringRect, -60f, 300f, false, iconPaint)
            canvas.restore()
        }

        // ۳) خط وسط
        if (stemAlpha > 0.001f) {
            val stemTop = -iconSize * (10f / 24f)
            canvas.save()
            canvas.scale(stemSquashX, stemSquashY, 0f, 0f)
            iconPaint.color = iconColor
            iconPaint.alpha = (255 * stemAlpha).toInt()
            canvas.drawLine(0f, stemTop, 0f, 0f, iconPaint)
            canvas.restore()
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }
}
