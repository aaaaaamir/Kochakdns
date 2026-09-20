package com.example.powerbutton

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
import android.view.MotionEvent
import android.view.View

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
    }

    interface OnStateChangeListener {
        fun onStateChanged(newState: Int)
    }

    private var listener: OnStateChangeListener? = null
    private var currentState = STATE_OFF

    // ---- Animated values ----
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

    // ---- Colors ----
    private var bgColor = 0xFF181829.toInt()
    private var borderColor = 0xFF2A2A40.toInt()
    private var iconColor = 0xFF4A4A6A.toInt()

    // ---- Paints ----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- Sizes ----
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var iconSize = 0f
    private var ringRadius = 0f
    private val density = resources.displayMetrics.density

    private val animators = mutableListOf<ValueAnimator>()
    private var colorAnimator: ValueAnimator? = null

    init {
        bgPaint.style = Paint.Style.FILL
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = dp(2f)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeCap = Paint.Cap.ROUND
        shadowPaint.style = Paint.Style.FILL
        ballPaint.style = Paint.Style.FILL

        isClickable = true
        isFocusable = true
    }

    fun setOnStateChangeListener(l: OnStateChangeListener) {
        listener = l
    }

    private fun dp(v: Float) = v * density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = minOf(w, h) / 2f - dp(2f)
        iconSize = minOf(w, h) * (68f / 220f)
        ringRadius = iconSize * (9f / 24f)
        iconPaint.strokeWidth = iconSize * (2.5f / 24f)
    }

    // ==================== Touch ====================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        handleClick()
        return super.performClick()
    }

    // ==================== Click (interruptible) ====================
    private fun handleClick() {
        when (currentState) {
            STATE_OFF -> startTurningOn()

            STATE_TURNING_ON, STATE_EMERGING -> {
                cancelAll()
                resetAll()
                animateColorsTo(STATE_OFF, 500)
                setState(STATE_OFF)
            }

            STATE_ON -> startTurningOff()

            STATE_TURNING_OFF -> {
                cancelAll()
                setLayerType(LAYER_TYPE_NONE, null)
                resetAll()
                animateColorsTo(STATE_ON, 500)
                setState(STATE_ON)
            }
        }
    }

    // ==================== Helpers ====================
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
        invalidate()
    }

    private fun setState(s: Int) {
        currentState = s
        listener?.onStateChanged(s)
    }

    private fun cancelAll() {
        for (a in animators) {
            a.removeAllListeners()
            a.cancel()
        }
        animators.clear()
        colorAnimator?.cancel()
    }

    // ==================== Color Animation ====================
    private fun animateColorsTo(state: Int, durationMs: Long) {
        val target = colorsFor(state)
        val fromBg = bgColor
        val fromBorder = borderColor
        val fromIcon = iconColor

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                bgColor     = lerpColor(fromBg,     target[0], p)
                borderColor = lerpColor(fromBorder, target[1], p)
                iconColor   = lerpColor(fromIcon,   target[2], p)
                invalidate()
            }
            start()
        }
    }

    private fun colorsFor(state: Int): IntArray {
        return when (state) {
            STATE_OFF, STATE_TURNING_OFF ->
                intArrayOf(0xFF181829.toInt(), 0xFF2A2A40.toInt(), 0xFF4A4A6A.toInt())
            STATE_TURNING_ON ->
                intArrayOf(0xFF181829.toInt(), 0xFFFACC15.toInt(), 0xFFFFFFFF.toInt())
            STATE_EMERGING, STATE_ON ->
                intArrayOf(0xFF22C55E.toInt(), 0xFF16A34A.toInt(), 0xFFFFFFFF.toInt())
            else ->
                intArrayOf(0xFF181829.toInt(), 0xFF2A2A40.toInt(), 0xFF4A4A6A.toInt())
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from)   + (Color.red(to)   - Color.red(from))   * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from)  + (Color.blue(to)  - Color.blue(from))  * t).toInt()
        return Color.argb(a, r, g, b)
    }

    // ==================== Keyframe interpolation ====================
    private fun keyframe(p: Float, vararg tv: Float): Float {
        val n = tv.size / 2
        if (n == 0) return 0f
        if (p <= tv[0]) return tv[1]
        if (p >= tv[2 * (n - 1)]) return tv[2 * (n - 1) + 1]
        for (i in 0 until n - 1) {
            val t1 = tv[2 * i];     val v1 = tv[2 * i + 1]
            val t2 = tv[2 * i + 2]; val v2 = tv[2 * i + 3]
            if (p <= t2) {
                val local = (p - t1) / (t2 - t1)
                return v1 + (v2 - v1) * local
            }
        }
        return tv[2 * (n - 1) + 1]
    }

    // ==================== Phase 1: MORPH ====================
    private fun startTurningOn() {
        cancelAll()
        resetAll()
        animateColorsTo(STATE_TURNING_ON, 500)
        setState(STATE_TURNING_ON)

        val morph = ValueAnimator.ofFloat(0f, 1f).apply { duration = 550 }
        morph.addUpdateListener { a ->
            val p = a.animatedValue as Float

            fillScale = keyframe(p, 0f, 0f, 0.70f, 1.08f, 1f, 1f)

            ringSquashX = keyframe(p, 0f, 1f, 0.25f, 1.08f, 0.55f, 0.95f, 0.80f, 0.97f, 1f, 0.80f)
            ringSquashY = keyframe(p, 0f, 1f, 0.25f, 0.94f, 0.55f, 1.05f, 0.80f, 0.94f, 1f, 0.80f)
            ringAlpha   = keyframe(p, 0f, 1f, 0.25f, 1f,    0.55f, 0.60f, 0.80f, 0.25f, 1f, 0f)

            stemSquashX = keyframe(p, 0f, 1f, 0.25f, 0.94f, 0.55f, 1.05f, 0.80f, 1.08f, 1f, 1.10f)
            stemSquashY = keyframe(p, 0f, 1f, 0.25f, 1.08f, 0.55f, 0.85f, 0.80f, 0.50f, 1f, 0f)
            stemAlpha   = keyframe(p, 0f, 1f, 0.25f, 1f,    0.55f, 0.80f, 0.80f, 0.35f, 1f, 0f)

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

    // ==================== Phase 2: JUMP ====================
    private fun startJumpPhase() {
        val jump = ValueAnimator.ofFloat(0f, 1f).apply { duration = 700 }
        jump.addUpdateListener { a ->
            val p = a.animatedValue as Float

            iconOffsetY = keyframe(p, 0f, 0f, 0.12f, 0f, 0.45f, -dp(44f), 0.80f, 0f, 1f, 0f)
            iconSquashX = keyframe(p, 0f, 1f, 0.12f, 1.05f, 0.45f, 0.97f, 0.80f, 1.06f, 1f, 1f)
            iconSquashY = keyframe(p, 0f, 1f, 0.12f, 0.96f, 0.45f, 1.03f, 0.80f, 0.95f, 1f, 1f)

            // shadow keyframes دقیقاً مطابق CSS
            shadowAlpha  = keyframe(p,
                0f,    0f,
                0.05f, 0.70f,
                0.12f, 0.95f,
                0.45f, 0.15f,
                0.80f, 1.0f,
                0.95f, 0.70f,
                1f,    0f)
            shadowScaleX = keyframe(p,
                0f, 1.05f, 0.12f, 1.15f, 0.45f, 0.35f, 0.80f, 1.20f, 1f, 1f)
            shadowScaleY = keyframe(p,
                0f, 0.95f, 0.12f, 1.05f, 0.45f, 0.50f, 0.80f, 1.12f, 1f, 1f)

            invalidate()
        }
        jump.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (currentState == STATE_TURNING_ON) startEmerging()
            }
        })
        animators.add(jump)
        jump.start()
    }

    // ==================== Phase 3: EMERGING ====================
    private fun startEmerging() {
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
                if (currentState == STATE_EMERGING) setState(STATE_ON)
            }
        })
        animators.add(emerge)
        emerge.start()
    }

    // ==================== Phase 4: TURNING OFF (Spin + Blur) ====================
    private fun startTurningOff() {
        cancelAll()
        resetAll()

        // فعال کردن رندر نرم‌افزاری برای BlurMaskFilter
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // رنگ‌ها بلافاصله به حالت خاموش می‌رن (با انیمیشن ۵۰۰ms)
        animateColorsTo(STATE_OFF, 500)
        setState(STATE_TURNING_OFF)

        val spin = ValueAnimator.ofFloat(0f, 1f).apply { duration = 800 }
        spin.addUpdateListener { a ->
            val p = a.animatedValue as Float
            iconRotation = 360f * p
            // بلور: شروع ۲۵٪، اوج ۴۰٪، پایان ۶۰٪ (خطی مثل CSS)
            iconBlur = keyframe(p,
                0f,    0f,
                0.25f, 0f,
                0.40f, dp(5f),
                0.60f, 0f,
                1f,    0f)
            invalidate()
        }
        spin.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (currentState == STATE_TURNING_OFF) {
                    setLayerType(LAYER_TYPE_NONE, null)
                    resetAll()
                    setState(STATE_OFF)
                }
            }
        })
        animators.add(spin)
        spin.start()
    }

    // ==================== Draw ====================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // پس‌زمینه دکمه
        bgPaint.color = bgColor
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // حاشیه
        borderPaint.color = borderColor
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // ============ سایه‌ی زیر دایره‌ی پرنده ============
        if (shadowAlpha > 0.001f) {
            val sw = iconSize * 0.48f
            val sh = iconSize * 0.105f

            canvas.save()
            canvas.translate(cx, cy + iconSize * 0.55f)
            canvas.scale(shadowScaleX, shadowScaleY)

            // برای اینکه گرادیان به شکل بیضی دربیاد، canvas رو عمودی فشرده می‌کنیم
            canvas.save()
            canvas.scale(1f, sh / sw)

            val aCenter = (242 * shadowAlpha).toInt().coerceIn(0, 255)
            val aMid    = (178 * shadowAlpha).toInt().coerceIn(0, 255)
            val aOuter  = (77  * shadowAlpha).toInt().coerceIn(0, 255)

            shadowPaint.shader = RadialGradient(
                0f, 0f, sw,
                intArrayOf(
                    Color.argb(aCenter, 0, 0, 0),
                    Color.argb(aMid,    0, 0, 0),
                    Color.argb(aOuter,  0, 0, 0),
                    Color.argb(0,       0, 0, 0)
                ),
                floatArrayOf(0f, 0.35f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(0f, 0f, sw, shadowPaint)

            canvas.restore()
            canvas.restore()
        }

        // ============ آیکون ============
        canvas.save()
        canvas.translate(cx, cy + iconOffsetY)
        canvas.rotate(iconRotation)
        canvas.scale(iconSquashX, iconSquashY)

        // افکت blur (فقط در رندر نرم‌افزاری کار می‌کنه)
        if (iconBlur > 0.5f) {
            try {
                iconPaint.maskFilter = BlurMaskFilter(iconBlur, BlurMaskFilter.Blur.NORMAL)
            } catch (_: Exception) { }
        } else {
            iconPaint.maskFilter = null
        }

        // ۱) دایره‌ی پرکننده‌ی سه‌بعدی
        if (fillScale > 0.001f) {
            val r = ringRadius * (10.8f / 9f) * fillScale
            ballPaint.shader = RadialGradient(
                0f, r * 0.3f, r * 1.05f,
                intArrayOf(
                    0xFFFFFFFF.toInt(),
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

        // ۳) خط وسط (pivot در پایین)
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
}
