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

/**
 * دکمه پاور با انیمیشن چهارفازی (طرح PowerButtonView):
 *  1) MORPH  — آیکون پاور (حلقه + خط) آب می‌شود و به یک توپ سفید سه‌بعدی تبدیل می‌شود
 *  2) JUMP   — توپ می‌پرد و تا رسیدن نتیجه‌ی واقعی اتصال، پریدن را تکرار می‌کند (زرد = در حال اتصال)
 *  3) EMERGE — توپ دوباره به آیکون پاور تبدیل می‌شود و رنگ سبز می‌گیرد
 *  4) SPIN   — چرخش ۳۶۰ درجه با motion-blur برای قطع شدن (سبز می‌چرخد، بعد خاکستری می‌شود)
 *
 * تفاوت مهم با نسخه‌ی دمو: وضعیت‌ها را DnsActivity از نتیجه‌ی واقعی سرویس VPN
 * (VpnUiState) با startConnecting / finishConnecting / startDisconnecting /
 * notifyDisconnected کنترل می‌کند؛ دکمه خودش «وانمود» نمی‌کند که وصل شده.
 * توپ تا آمدن پاسخ سرور/سرویس درجا می‌زند و دقیقاً در لحظه فرود (نه وسط هوا)
 * به فاز ظاهر شدن می‌رود تا پرش نداشته باشیم.
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
    private var iconAlpha = 1f
    private var shadowAlpha = 0f
    private var shadowScaleX = 1f
    private var shadowScaleY = 1f
    private var iconBlur = 0f
    private var breath = 1f

    // ---- رنگ‌ها (تم تیره‌ی خودِ برنامه؛ همان‌هایی که قبلاً ست شده بود) ----
    private var bgColor = Color.parseColor("#1E1E2E")
    private var borderColor = Color.parseColor("#2A2A3E")
    private var iconColor = Color.parseColor("#666680")

    // ---- قلم‌موها ----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- اندازه‌ها ----
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var iconSize = 0f
    private var ringRadius = 0f
    private var strokeW = 0f
    private val density = resources.displayMetrics.density

    private val animators = mutableListOf<ValueAnimator>()
    private var breathAnimator: ValueAnimator? = null

    /** وقتی حین پریدنِ توپ، اتصال واقعی برقرار شود؛ در فرود بعدی به فاز EMERGE می‌رویم. */
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

        // BlurMaskFilter روی بوم سخت‌افزاری (قبل از API 28) نادیده گرفته می‌شود؛
        // لایه نرم‌افزاری تضمین می‌کند بلورِ فاز خاموش‌شدن در همه دستگاه‌ها دیده شود.
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
        strokeW = iconSize * (2.5f / 24f)
        iconPaint.strokeWidth = strokeW
    }

    // ==================== API بیرونی (از سمت DnsActivity صدا زده می‌شود) ====================

    /** شروع اتصال (CONNECTING): مورف + پرش توپ تا اعلام نتیجه. */
    fun startConnecting() {
        if (currentState == STATE_TURNING_ON || currentState == STATE_EMERGING) return
        cancelAll()
        resetAll()
        setState(STATE_TURNING_ON)
        startMorphPhase()
    }

    /** اتصال برقرار شد (CONNECTED). */
    fun finishConnecting() {
        when (currentState) {
            STATE_TURNING_ON -> pendingFinish = true          // در اولین فرود، EMERGE شروع می‌شود
            STATE_EMERGING -> { /* دارد ظاهر می‌شود؛ کاری لازم نیست */ }
            else -> { cancelAll(); resetAll(); setState(STATE_ON) } // مثلاً build مجدد صفحه هنگام VPN فعال
        }
    }

    /** شروع قطع (DISCONNECTING): چرخش + بلور. */
    fun startDisconnecting() {
        if (currentState == STATE_TURNING_OFF) return
        cancelAll()
        resetAll()
        setState(STATE_TURNING_OFF)
        applyStateColors(STATE_ON) // سبز بماند تا آخر چرخش
        startSpinPhase()
    }

    /** قطع کامل شد (DISCONNECTED): اگر وسط چرخش هستیم بگذاریم اسپین تمام شود، وگرنه فوری خاموش. */
    fun notifyDisconnected() {
        if (currentState == STATE_TURNING_OFF) return
        cancelAll()
        resetAll()
        setState(STATE_OFF)
    }

    /** ست‌کردن بی‌انیمیشنِ وضعیت (ساخت اولیه/ری‌کریت اکتیویتی). */
    fun snapTo(state: Int) {
        cancelAll()
        resetAll()
        setState(state)
    }

    /** نفس‌کشیدن ملایم آیکون، فقط وقتی خاموش و آماده‌ی اتصال است. */
    fun startIdleBreathing() {
        if (currentState != STATE_OFF) return
        if (breathAnimator?.isRunning == true) return
        breathAnimator = ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
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
        iconAlpha = 1f
        shadowAlpha = 0f
        shadowScaleX = 1f; shadowScaleY = 1f
        iconBlur = 0f
        applyStateColors(currentState)
        invalidate()
    }

    private fun setState(s: Int) {
        currentState = s
        applyStateColors(s)
        invalidate()
    }

    /**
     * پالت رنگی هر وضعیت — هماهنگ با بقیه‌ی UI برنامه:
     * اگر بخواهی دقیقاً مثل دمو زمینه‌ی زرد/سبزِ یکدست و آیکون سفید داشته باشی،
     * فقط همین بدنه را عوض کن؛ بقیه‌ی انیمیشن مستقل از رنگ‌هاست.
     */
    private fun applyStateColors(s: Int) {
        when (s) {
            STATE_OFF -> {
                bgColor = Color.parseColor("#1E1E2E")
                borderColor = Color.parseColor("#2A2A3E")
                iconColor = Color.parseColor("#666680")
            }
            STATE_TURNING_ON -> {
                bgColor = Color.parseColor("#3A2E00")
                borderColor = Color.parseColor("#FFD700")
                iconColor = Color.parseColor("#FFD700")
            }
            STATE_TURNING_OFF -> {
                bgColor = Color.parseColor("#3A2E00")
                borderColor = Color.parseColor("#FFD700")
                iconColor = Color.parseColor("#FFD700")
            }
            STATE_EMERGING, STATE_ON -> {
                bgColor = Color.parseColor("#1B3A22")
                borderColor = Color.parseColor("#4CAF50")
                iconColor = Color.parseColor("#4CAF50")
            }
        }
    }

    private fun cancelAll() {
        pendingFinish = false
        for (a in animators) {
            a.removeAllListeners()
            a.cancel()
        }
        animators.clear()
        breathAnimator?.cancel()
        breathAnimator = null
        breath = 1f
    }

    /**
     * interpolate بین keyframeها.
     * آرگومان‌ها به صورت زوج‌های (زمان 0..1، مقدار) هستند:
     * keyframe(p, 0f, 0f,  0.5f, 1f,  1f, 0f)
     * یعنی: p=0 ← 0، p=0.5 ← 1، p=1 ← 0
     */
    private fun keyframe(p: Float, vararg tv: Float): Float {
        val n = tv.size / 2
        if (n == 0) return 0f
        if (p <= tv[0]) return tv[1]
        if (p >= tv[2 * (n - 1)]) return tv[2 * (n - 1) + 1]
        for (i in 0 until n - 1) {
            val t1 = tv[2 * i]
            val v1 = tv[2 * i + 1]
            val t2 = tv[2 * i + 2]
            val v2 = tv[2 * i + 3]
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
            ringAlpha   = keyframe(p, 0f, 1f, 0.25f, 1f, 0.55f, 0.60f, 0.80f, 0.25f, 1f, 0f)

            stemSquashX = keyframe(p, 0f, 1f, 0.25f, 0.94f, 0.55f, 1.05f, 0.80f, 1.08f, 1f, 1.10f)
            stemSquashY = keyframe(p, 0f, 1f, 0.25f, 1.08f, 0.55f, 0.85f, 0.80f, 0.50f, 1f, 0f)
            stemAlpha   = keyframe(p, 0f, 1f, 0.25f, 1f, 0.55f, 0.80f, 0.80f, 0.35f, 1f, 0f)

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

    // ==================== فاز ۲: JUMP (حلقه‌ای تا رسیدن نتیجه) ====================
    private fun startJumpPhase() {
        val jump = ValueAnimator.ofFloat(0f, 1f).apply { duration = 700 }
        jump.repeatCount = ValueAnimator.INFINITE
        jump.addUpdateListener { a ->
            val p = a.animatedValue as Float

            // اوج پرش به اندازه‌ی آیکون مقیاس می‌شود (در دمو dp ثابت بود و
            // روی دکمه‌های کوچک‌تر/بزرگ‌تر از تعادل خارج می‌شد)
            iconOffsetY = keyframe(p, 0f, 0f, 0.12f, 0f, 0.45f, -iconSize * 0.62f, 0.80f, 0f, 1f, 0f)
            iconSquashX = keyframe(p, 0f, 1f, 0.12f, 1.05f, 0.45f, 0.97f, 0.80f, 1.06f, 1f, 1f)
            iconSquashY = keyframe(p, 0f, 1f, 0.12f, 0.96f, 0.45f, 1.03f, 0.80f, 0.95f, 1f, 1f)

            shadowAlpha  = keyframe(p, 0f, 0.65f, 0.12f, 0.85f, 0.45f, 0.12f, 0.80f, 0.90f, 0.95f, 0.60f, 1f, 0f)
            shadowScaleX = keyframe(p, 0f, 1.05f, 0.12f, 1.15f, 0.45f, 0.35f, 0.80f, 1.20f, 1f, 1f)
            shadowScaleY = keyframe(p, 0f, 0.95f, 0.12f, 1.05f, 0.45f, 0.50f, 0.80f, 1.12f, 1f, 1f)

            // نتیجه‌ی اتصال رسید؟ فقط در انتهای سیکل (توپ روی زمین) تحویل بده تا پرش نبینیم
            if (pendingFinish && p >= 0.92f) {
                a.removeAllListeners()
                a.cancel()
                startEmerging()
                return@addUpdateListener
            }
            invalidate()
        }
        animators.add(jump)
        jump.start()
    }

    // ==================== فاز ۳: EMERGING ====================
    private fun startEmerging() {
        pendingFinish = false
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

    // ==================== فاز ۴: TURNING OFF (چرخش + بلور) ====================
    private fun startSpinPhase() {
        val spin = ValueAnimator.ofFloat(0f, 1f).apply { duration = 800 }
        spin.addUpdateListener { a ->
            val p = a.animatedValue as Float
            iconRotation = 360f * p
            // بلور: شروع ۲۵٪، اوج ۴۰٪، پایان ۶۰٪
            iconBlur = keyframe(p, 0f, 0f, 0.25f, 0f, 0.40f, dp(5f), 0.60f, 0f, 1f, 0f)
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

        // خودِ دکمه
        bgPaint.color = bgColor
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // حاشیه
        borderPaint.color = borderColor
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // سایه زیر توپ
        if (shadowAlpha > 0.001f) {
            canvas.save()
            canvas.translate(cx, cy + iconSize * 0.55f)
            canvas.scale(shadowScaleX, shadowScaleY)
            shadowPaint.alpha = (170 * shadowAlpha).toInt()
            val sw = iconSize * 0.40f
            val sh = iconSize * 0.08f
            canvas.drawOval(RectF(-sw, -sh, sw, sh), shadowPaint)
            canvas.restore()
        }

        // آیکون (حلقه + خط + توپ) — نفس‌کشیدن بی‌کار روی هر دو محوری اعمال می‌شود
        canvas.save()
        canvas.translate(cx, cy + iconOffsetY)
        canvas.rotate(iconRotation)
        canvas.scale(iconSquashX * breath, iconSquashY * breath)

        // افکت بلور حین چرخش (روی بوم نرم‌افزاری)
        if (iconBlur > 0.5f) {
            iconPaint.maskFilter = BlurMaskFilter(iconBlur, BlurMaskFilter.Blur.NORMAL)
            ballPaint.maskFilter = BlurMaskFilter(iconBlur, BlurMaskFilter.Blur.NORMAL)
        } else {
            iconPaint.maskFilter = null
            ballPaint.maskFilter = null
        }

        // ۱) توپ پرکننده (سه‌بعدی)
        if (fillScale > 0.001f) {
            val r = ringRadius * (10.8f / 9f) * fillScale
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
            iconPaint.alpha = (255 * ringAlpha * iconAlpha).toInt()
            val ringRect = RectF(-ringRadius, -ringRadius, ringRadius, ringRadius)
            canvas.drawArc(ringRect, -60f, 300f, false, iconPaint)
            canvas.restore()
        }

        // ۳) خط وسط — pivot پایین خط
        if (stemAlpha > 0.001f) {
            val stemTop = -iconSize * (10f / 24f)
            canvas.save()
            canvas.scale(stemSquashX, stemSquashY, 0f, 0f)
            iconPaint.color = iconColor
            iconPaint.alpha = (255 * stemAlpha * iconAlpha).toInt()
            canvas.drawLine(0f, stemTop, 0f, 0f, iconPaint)
            canvas.restore()
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        // هرگز انیمیشن بی‌نهایت را روی View رها نکن (جلوگیری از leak)
        cancelAll()
        super.onDetachedFromWindow()
    }
}
