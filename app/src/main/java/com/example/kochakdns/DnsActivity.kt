package com.example.kochakdns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ==================== Main Activity ====================

/**
 * وضعیت صریح دکمه‌ی وصل/قطع. به‌جای یک boolean ساده که چند جای مختلف کد
 * مستقیم دستکاریش می‌کردن (و منبع باگ‌های race condition بود)، حالا فقط
 * از طریق setVpnState() تغییر می‌کنه و هر تغییر حالت دقیقاً یک بار UI رو
 * آپدیت می‌کنه.
 */
enum class VpnUiState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/**
 * آیکون پاور، مستقیم با Canvas کشیده می‌شه — دیگه فایل drawable جدا لازم
 * نیست، همه‌چیز همین‌جا توی خودِ DnsActivity.kt کنار همدیگه‌ست.
 * سبک: خطی (stroke)، سفید، گوشه‌ها و سرِ خط‌ها گرد — دقیقاً شبیه آیکون
 * "power" از ست آیکون Lucide.
 *
 * اگه بعداً خواستی نسخه‌ی رسمی SVG رو جایگزین کنی (مثلاً یک انیمیشنش رو
 * بسازی)، این دستور رو توی ترمینال بزن تا فایل اصلی Lucide دانلود بشه:
 *
 *   curl -o app/src/main/res/drawable/ic_power.svg \
 *     https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/power.svg
 *
 * توجه: فایل svg مستقیم قابل استفاده به‌عنوان drawable اندروید نیست؛ باید
 * تبدیلش کنی به vector XML — ساده‌ترین راه توی Android Studio:
 * راست‌کلیک روی پوشه‌ی drawable → New → Vector Asset → Local file →
 * همین svg رو انتخاب کن. بعدش کافیه توی کد این کلاس رو با یک ImageView که
 * به R.drawable.ic_power اشاره می‌کنه عوض کنی.
 */
class PowerIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    fun setIconColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0) return
        paint.strokeWidth = size * 0.09f
        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.32f

        // خط عمودی بالا (دسته‌ی کلید)
        canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.15f, paint)

        // کمان دایره با یک شکاف بالا (نماد استاندارد پاور)
        val rectF = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rectF, -55f, 290f, false, paint)
    }
}

/**
 * یک پیکان باز (شبیه یک مثلث که ضلع پایینش رو نداره — فقط دو خط که به هم
 * می‌رسن)، با گوشه‌های نرم (round join/cap). چون شکل همیشه بازه، «پر» بودن
 * با ضخیم‌تر و روشن‌تر شدن خط نشون داده می‌شه (نه واقعاً fill)، «توخالی»
 * با نازک‌تر و کم‌رنگ‌تر شدنش. چرخش برای باز/بسته شدن با `rotation`
 * استاندارد View از بیرون کنترل می‌شه.
 */
class ExpandArrowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = android.graphics.Path()
    private var blend = 0f // 0 = انتخاب‌نشده (نازک‌تر، تیره‌تر)، 1 = انتخاب‌شده (ضخیم‌تر، روشن‌تر)
    private var blendAnimator: android.animation.ValueAnimator? = null

    var filled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            blendAnimator?.cancel()
            blendAnimator = android.animation.ValueAnimator.ofFloat(blend, if (value) 1f else 0f).apply {
                duration = 260
                addUpdateListener {
                    blend = it.animatedValue as Float
                    applyPaint()
                    invalidate()
                }
                start()
            }
        }

    private fun applyPaint() {
        if (width <= 0 || height <= 0) return
        val glyphSize = minOf(width, height) * 0.55f
        val strokeMin = glyphSize * 0.24f
        val strokeMax = glyphSize * 0.30f
        paint.strokeWidth = strokeMin + (strokeMax - strokeMin) * blend

        // گرادیانت روشن-به-تیره برای حس نرم و برجسته (soft UI)، بین حالت
        // انتخاب‌نشده (خاکستری کم‌رنگ) و انتخاب‌شده (تقریباً سفید) نرم بلند می‌شه.
        val evaluator = android.animation.ArgbEvaluator()
        val top = evaluator.evaluate(blend, Color.parseColor("#8A8A96"), Color.parseColor("#FFFFFF")) as Int
        val bottom = evaluator.evaluate(blend, Color.parseColor("#4A4A56"), Color.parseColor("#D8D8DC")) as Int
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, height.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyPaint()
        path.reset()
        val cx = w / 2f
        val cy = h / 2f
        val glyph = minOf(w, h) * 0.55f
        val left = cx - glyph * 0.36f
        val right = cx + glyph * 0.36f
        val top = cy - glyph * 0.24f
        val bottom = cy + glyph * 0.26f
        // یک «V» نرم و پهن رو به پایین — دو خط باز که پایین به هم می‌رسن
        path.moveTo(left, top)
        path.lineTo(cx, bottom)
        path.lineTo(right, top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }
}

/** آیکون همبرگری: سه خط ساده و نرم، هم‌شکل با بقیه‌ی آیکون‌های دستی این فایل. */
class HamburgerIconView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.strokeWidth = minOf(w, h) * 0.09f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = width * 0.24f
        val right = width * 0.76f
        listOf(height * 0.32f, height * 0.5f, height * 0.68f).forEach { y ->
            canvas.drawLine(left, y, right, y, paint)
        }
    }
}

class DnsActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var hamburgerButton: HamburgerIconView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private var isDrawerOpen = false
    private var drawerWidthPx = 0
    private var mainContainerRef: View? = null
    private lateinit var powerButton: LinearLayout
    private lateinit var powerIcon: PowerIconView
    private lateinit var powerButtonShape: android.graphics.drawable.GradientDrawable
    private lateinit var jitterText: TextView
    private lateinit var lastPingText: TextView
    private lateinit var statsLayout: LinearLayout
    private lateinit var packetsSentText: LinearLayout
    private lateinit var packetsLostText: LinearLayout
    private lateinit var bytesSentText: LinearLayout
    private lateinit var bytesReceivedText: LinearLayout
    private lateinit var dnsListContainer: LinearLayout
    private lateinit var statusIndicator: FrameLayout
    private lateinit var retryButton: Button
    private lateinit var loadingSpinner: ProgressBar

    private var pingJob: Job? = null
    private var statsJob: Job? = null
    private var confirmJob: Job? = null
    private var isSyncing = false
    private var vpnState: VpnUiState = VpnUiState.DISCONNECTED
    private var selectedDnsName: String? = null
    private var selectedDnsServers: List<DnsServer> = emptyList()
    private val dnsItems = mutableListOf<DnsItem>()
    private val dnsItemViews = mutableMapOf<String, DnsItemView>()
    private var previousPings = mutableMapOf<String, Long>()
    // جیتر مستقیم: مستقل از آیتم‌های لیست DNS محاسبه می‌شه، فقط از روی
    // پینگ‌های واقعی و متوالی خودِ DNS انتخاب‌شده (فرمول هموارسازی RFC 3550).
    private var directJitterMs: Double = 0.0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "VPN_STARTED" -> setVpnState(VpnUiState.CONNECTED)
                "VPN_STOPPED" -> setVpnState(VpnUiState.DISCONNECTED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUI()
        setupVpnReceiver()
        val prefs = getSharedPreferences("dns_prefs", MODE_PRIVATE)
        selectedDnsName = prefs.getString("selected_dns", null)
        setVpnState(if (VpnStats.isVpnActive) VpnUiState.CONNECTED else VpnUiState.DISCONNECTED)
        lifecycleScope.launch {
            syncDnsData()
            startPingLoop()
            startStatsUpdateLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        // هر بار که برمی‌گردی به این صفحه، دکمه رو با وضعیت واقعی VPN هماهنگ کن
        // (نه صرفاً چیزی که آخرین broadcast گفته)، تا هیچ‌وقت رنگش دروغ نگه.
        // فقط وقتی معتبره که وسط یک انتقال (CONNECTING/DISCONNECTING) نباشیم،
        // چون اون حالت‌ها خودشون به‌زودی نتیجه رو ست می‌کنن.
        val actuallyConnected = VpnStats.isVpnActive
        if (vpnState == VpnUiState.CONNECTED && !actuallyConnected) {
            setVpnState(VpnUiState.DISCONNECTED)
        } else if (vpnState == VpnUiState.DISCONNECTED && actuallyConnected) {
            setVpnState(VpnUiState.CONNECTED)
        }
    }

    private fun setupVpnReceiver() {
        val filter = IntentFilter().apply {
            addAction("VPN_STARTED")
            addAction("VPN_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnReceiver, filter)
        }
    }

    private fun buildUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 64)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        statusIndicator = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        loadingSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        statusIndicator.addView(loadingSpinner)
        retryButton = Button(this).apply {
            text = "↻"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            visibility = View.GONE
            setOnClickListener { lifecycleScope.launch { syncDnsData(force = true) } }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        statusIndicator.addView(retryButton)
        header.addView(statusIndicator)
        mainContainer.addView(header)
        powerButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleVpn() }
            layoutParams = LinearLayout.LayoutParams(480, 480).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 80
                bottomMargin = 32
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                powerButtonShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#1E1E2E"))
                    setStroke(8, Color.parseColor("#2A2A3E"))
                }
                background = powerButtonShape
                // ریپل لمسی گرد، هم‌شکل با خود دکمه، تا کاربر همیشه ببینه تپش ثبت شده
                val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                    this.shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
                    null,
                    rippleMask
                )
            }
        }
        powerIcon = PowerIconView(this).apply {
            setIconColor(Color.parseColor("#666680"))
            layoutParams = LinearLayout.LayoutParams(160, 160)
        }
        powerButton.addView(powerIcon)
        mainContainer.addView(powerButton)
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        val jitterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val jitterLabel = TextView(this).apply {
            text = "JITTER"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        jitterText = TextView(this).apply {
            text = "-- ms"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        jitterContainer.addView(jitterLabel)
        jitterContainer.addView(jitterText)
        val pingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pingLabel = TextView(this).apply {
            text = "LAST PING"
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        lastPingText = TextView(this).apply {
            text = "-- ms"
            setTextColor(Color.parseColor("#4CAF50"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        pingContainer.addView(pingLabel)
        pingContainer.addView(lastPingText)
        statsRow.addView(jitterContainer)
        statsRow.addView(pingContainer)
        mainContainer.addView(statsRow)
        statsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }
        packetsSentText = createStatItem("ارسالی", "0")
        packetsLostText = createStatItem("گم‌شده", "0")
        bytesSentText = createStatItem("↑ ارسال", "0 B")
        bytesReceivedText = createStatItem("↓ دریافت", "0 B")
        statsLayout.addView(packetsSentText)
        statsLayout.addView(packetsLostText)
        statsLayout.addView(bytesSentText)
        statsLayout.addView(bytesReceivedText)
        mainContainer.addView(statsLayout)
        val listHeader = TextView(this).apply {
            text = "لیست DNS"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        mainContainer.addView(listHeader)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        dnsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // اصلاح خطا: استفاده از FrameLayout.LayoutParams به جای ScrollView.LayoutParams
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(dnsListContainer)
        mainContainer.addView(scrollView)
        rootLayout.addView(mainContainer)
        buildDrawerMenu(mainContainer)
        setContentView(rootLayout)
        setVpnState(vpnState) // اعمال ظاهر اولیه‌ی دکمه
    }

    /**
     * منوی کشویی: دکمه‌ی همبرگری بالا-چپ، یک دراور که از گوشه باز می‌شه، و
     * پشتش به‌جای رنگ ساده از بلور واقعی محتوای اصلی استفاده می‌شه (فقط
     * روی اندروید ۱۲/API 31 به بالا ممکنه؛ روی نسخه‌های قدیمی‌تر یک پرده‌ی
     * نیمه‌شفاف تیره جایگزینش می‌شه چون RenderEffect قبل از آن نسخه وجود نداره).
     */
    private fun buildDrawerMenu(mainContainer: View) {
        mainContainerRef = mainContainer
        val screenWidth = resources.displayMetrics.widthPixels
        drawerWidthPx = (screenWidth * 0.76f).toInt()

        drawerScrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            alpha = 0f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { closeDrawer() }
        }
        rootLayout.addView(drawerScrim)

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 220, 32, 32)
            // نیمه‌شفاف، تا وقتی محتوای پشتش بلور شده، خودِ بلور از زیر این
            // رنگ هم دیده بشه (حس شیشه‌ی مات، نه یک صفحه‌ی رنگی توپر).
            setBackgroundColor(Color.parseColor("#CC17171F"))
            layoutParams = FrameLayout.LayoutParams(drawerWidthPx, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
            translationX = -drawerWidthPx.toFloat()
            elevation = 24f
        }
        val menuContent = MenuActivity(this).buildView(onItemClick = { closeDrawer() })
        drawerPanel.addView(menuContent)
        rootLayout.addView(drawerPanel)

        hamburgerButton = HamburgerIconView(this).apply {
            layoutParams = FrameLayout.LayoutParams(80, 80).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = 40
                topMargin = 64
            }
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                    null,
                    rippleMask
                )
            }
            setOnClickListener { toggleDrawer() }
        }
        rootLayout.addView(hamburgerButton)
    }

    private fun toggleDrawer() {
        if (isDrawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(280).start()
        drawerPanel.animate()
            .translationX(0f)
            .setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // بلور واقعی محتوای پشت دراور (فقط API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mainContainerRef?.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    22f, 22f, android.graphics.Shader.TileMode.CLAMP
                )
            )
        }

        // دکمه‌ی همبرگری، هم‌زمان با باز شدن دراور، انگار داخلش «می‌افته»
        hamburgerButton.animate().cancel()
        hamburgerButton.translationY = -30f
        hamburgerButton.animate()
            .translationY(0f)
            .setStartDelay(90)
            .setDuration(340)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
            .start()
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        drawerScrim.animate().alpha(0f).setDuration(240)
            .withEndAction { drawerScrim.visibility = View.GONE }.start()
        drawerPanel.animate()
            .translationX(-drawerWidthPx.toFloat())
            .setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mainContainerRef?.setRenderEffect(null)
        }

        // دکمه‌ی همبرگری انگار از داخل دراور «بیرون می‌پره» و برمی‌گرده سرجاش
        hamburgerButton.animate().cancel()
        hamburgerButton.animate()
            .translationY(-20f)
            .setDuration(120)
            .withEndAction {
                hamburgerButton.animate()
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun createStatItem(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val labelView = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#888888"))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            val valueView = TextView(context).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                tag = "value"
            }
            addView(labelView)
            addView(valueView)
        }
    }

    private fun toggleVpn() {
        when (vpnState) {
            VpnUiState.CONNECTED -> disconnectVpn()
            VpnUiState.DISCONNECTED -> connectVpn()
            VpnUiState.CONNECTING, VpnUiState.DISCONNECTING -> {
                // وسط یک انتقال هستیم؛ تپ رو نادیده می‌گیریم تا race condition نسازه
                // (دکمه هم موقع این حالت‌ها غیرفعاله، این صرفاً یک محافظ اضافیه)
            }
        }
    }

    private fun connectVpn() {
        if (selectedDnsServers.isEmpty()) {
            Toast.makeText(this, "لطفاً ابتدا یک DNS انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(vpnIntent, 1001)
            } else {
                startVpn()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در اتصال: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectVpn() {
        setVpnState(VpnUiState.DISCONNECTING)
        try {
            val intent = Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_STOP
            }
            startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در قطع اتصال: ${e.message}", Toast.LENGTH_LONG).show()
            setVpnState(VpnUiState.CONNECTED) // برگردون چون واقعاً تلاش انجام نشد
            return
        }
        confirmJob?.cancel()
        confirmJob = lifecycleScope.launch {
            val settled = waitForActualState(expectActive = false, timeoutMs = 4000)
            if (settled) {
                setVpnState(VpnUiState.DISCONNECTED)
            } else {
                // بعد از ۴ ثانیه هنوز واقعاً وصله -> قطع واقعاً انجام نشد
                setVpnState(VpnUiState.CONNECTED)
                Toast.makeText(this@DnsActivity, "قطع اتصال ناموفق بود، دوباره امتحان کنید", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            startVpn()
        } else if (requestCode == 1001) {
            // کاربر مجوز VPN رو رد کرد
            setVpnState(VpnUiState.DISCONNECTED)
        }
    }

    private fun startVpn() {
        setVpnState(VpnUiState.CONNECTING)
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_START
            putStringArrayListExtra(
                MyVpnService.EXTRA_DNS_SERVERS,
                ArrayList(selectedDnsServers.map { it.address })
            )
            putExtra(MyVpnService.EXTRA_DNS_NAME, selectedDnsName ?: "DNS")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در اتصال: ${e.message}", Toast.LENGTH_LONG).show()
            setVpnState(VpnUiState.DISCONNECTED)
            return
        }
        // به‌جای صرفاً منتظر broadcast موندن (که ممکنه گم بشه)، خودمون وضعیت
        // واقعی VPN رو چک می‌کنیم تا مطمئن بشیم دکمه هیچ‌وقت زرد گیر نمی‌کنه.
        confirmJob?.cancel()
        confirmJob = lifecycleScope.launch {
            val settled = waitForActualState(expectActive = true, timeoutMs = 8000)
            if (settled) {
                setVpnState(VpnUiState.CONNECTED)
            } else {
                setVpnState(VpnUiState.DISCONNECTED)
                Toast.makeText(this@DnsActivity, "اتصال ناموفق بود. دوباره امتحان کنید.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** هر ۲۵۰ میلی‌ثانیه وضعیت واقعی VPN رو چک می‌کنه تا expectActive بشه یا timeout بخوره. */
    private suspend fun waitForActualState(expectActive: Boolean, timeoutMs: Int): Boolean {
        var waited = 0
        while (waited < timeoutMs) {
            if (VpnStats.isVpnActive == expectActive) return true
            delay(250)
            waited += 250
        }
        return VpnStats.isVpnActive == expectActive
    }

    /**
     * تنها نقطه‌ی تغییر وضعیت VPN توی UI. هر تغییری باید از اینجا رد بشه؛
     * جای دیگه‌ای مستقیم vpnState رو ست نمی‌کنه. همین‌جا هم دکمه رو موقع
     * انتقال (CONNECTING/DISCONNECTING) غیرفعال می‌کنه تا دوبار-تپ زدن
     * race condition نسازه.
     */
    private var powerBgColor = Color.parseColor("#1E1E2E")
    private var powerStrokeColor = Color.parseColor("#2A2A3E")
    private var powerIconColor = Color.parseColor("#666680")
    private var breathingAnimator: android.animation.ValueAnimator? = null
    private var powerColorAnimator: android.animation.ValueAnimator? = null

    private fun setVpnState(newState: VpnUiState) {
        vpnState = newState
        runOnUiThread {
            val (bgColor, strokeColor, iconColor, clickable) = when (newState) {
                VpnUiState.CONNECTED -> Quad("#1B3A22", "#4CAF50", "#4CAF50", true)
                VpnUiState.DISCONNECTED -> Quad("#1E1E2E", "#2A2A3E", "#666680", true)
                VpnUiState.CONNECTING -> Quad("#3A2E00", "#FFD700", "#FFD700", false)
                VpnUiState.DISCONNECTING -> Quad("#3A2E00", "#FFD700", "#FFD700", false)
            }
            val strokeWidth = if (newState == VpnUiState.CONNECTED) 10 else 8
            animatePowerButtonColors(Color.parseColor(bgColor), Color.parseColor(strokeColor), Color.parseColor(iconColor), strokeWidth)

            powerButton.isClickable = clickable
            powerButton.alpha = if (clickable) 1f else 0.75f

            // نفس‌کشیدن ملایم فقط وقتی خاموش و آماده‌ی اتصاله معنا داره
            if (newState == VpnUiState.DISCONNECTED) startPowerIconBreathing() else stopPowerIconBreathing()

            // یک بانس کوچیک روی هر تغییر حالت، تا کاربر همیشه حس کنه چیزی عوض شد
            powerButton.animate().cancel()
            powerButton.scaleX = 0.88f
            powerButton.scaleY = 0.88f
            powerButton.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
                .start()
        }
    }

    /** رنگ پس‌زمینه، حاشیه، و آیکون رو به‌جای پرش ناگهانی، نرم به رنگ جدید محو می‌کنه. */
    private fun animatePowerButtonColors(targetBg: Int, targetStroke: Int, targetIcon: Int, strokeWidthPx: Int) {
        val fromBg = powerBgColor
        val fromStroke = powerStrokeColor
        val fromIcon = powerIconColor
        val evaluator = android.animation.ArgbEvaluator()

        powerColorAnimator?.cancel()
        powerColorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                powerButtonShape.setColor(evaluator.evaluate(f, fromBg, targetBg) as Int)
                powerButtonShape.setStroke(strokeWidthPx, evaluator.evaluate(f, fromStroke, targetStroke) as Int)
                powerIcon.setIconColor(evaluator.evaluate(f, fromIcon, targetIcon) as Int)
            }
            start()
        }
        powerBgColor = targetBg
        powerStrokeColor = targetStroke
        powerIconColor = targetIcon
    }

    /** یک نفس‌کشیدن خیلی ملایم روی خودِ آیکون، فقط وقتی آماده‌ی اتصاله (حالت خاموش). */
    private fun startPowerIconBreathing() {
        if (breathingAnimator?.isRunning == true) return
        breathingAnimator = android.animation.ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 1600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                powerIcon.scaleX = scale
                powerIcon.scaleY = scale
            }
            start()
        }
    }

    private fun stopPowerIconBreathing() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        powerIcon.scaleX = 1f
        powerIcon.scaleY = 1f
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Boolean)


    private suspend fun syncDnsData(force: Boolean = false) {
        isSyncing = true
        showLoading()

        // اول واقعاً تلاش می‌کنیم دیتای تازه از سرور بگیریم. فقط اگه سرور
        // خطا داد (نه هر بار)، می‌ریم سراغ آخرین کش سالمی که قبلاً ذخیره شده.
        // force فقط از دکمه‌ی رفرش دستی true می‌شه؛ در حالت عادی (باز شدن
        // صفحه) به همون sync ای که MainActivity از قبل زده join می‌شیم و
        // یک درخواست تکراری به سرور نمی‌زنیم.
        withContext(Dispatchers.IO) {
            DnsSyncCoordinator.startSync(applicationContext, force).await()
        }

        // نکته: DataStore فقط وقتی sync موفق باشه بازنویسی می‌شه (توی
        // DnsSyncManager.sync)، پس این یک خط همیشه چیز درستی برمی‌گردونه:
        // موفق بود -> دیتای تازه‌ای که همین الان ذخیره شد؛ خطا داد -> همون
        // آخرین کش سالمی که از قبل روی دیسک مونده، بدون هیچ تغییری.
        val profiles = withContext(Dispatchers.IO) { readCachedProfiles() }

        if (profiles != null && profiles.isNotEmpty()) {
            loadDnsFromProfiles(profiles)
            isSyncing = false
            hideLoading()
            fetchAndApplyStats()
        } else {
            isSyncing = false
            if (dnsItems.isEmpty()) showError() else hideLoading()
        }
    }

    /** آمار پکت‌های ارسالی/گم‌شده رو از سرور می‌گیره و روی کارت‌های همین لیست اعمال می‌کنه. */
    private fun fetchAndApplyStats() {
        // اگه کاربر از تنظیمات این گزینه رو خاموش کرده، اصلاً درخواستی به API
        // زده نمی‌شه — نه فقط مخفی کردن نمایش، بلکه کلاً fetch نمی‌شه.
        if (!AppSettings.isShowPacketPercentEnabled(applicationContext)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val stats = DnsSyncManager(applicationContext).fetchStats()
            if (stats.isEmpty()) return@launch
            mainHandler.post {
                stats.forEach { (name, pair) ->
                    val (sent, lost) = pair
                    val index = dnsItems.indexOfFirst { it.name == name }
                    if (index >= 0) {
                        val updated = dnsItems[index].copy(statsPacketsSent = sent, statsPacketsLost = lost)
                        dnsItems[index] = updated
                        dnsItemViews[name]?.update(updated, name == selectedDnsName)
                    }
                }
            }
        }
    }

    private suspend fun readCachedProfiles(): List<DnsProfile>? {
        return try {
            val prefsSnapshot = applicationContext.dnsDataStore.data.first()
            val json = prefsSnapshot[stringPreferencesKey("dns_profiles_list")] ?: return null
            DnsProfile.listFromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadDnsFromProfiles(profiles: List<DnsProfile>) {
        dnsItems.clear()
        previousPings.clear()
        profiles.forEach { profile ->
            dnsItems.add(
                DnsItem(
                    name = profile.name,
                    servers = profile.servers
                )
            )
        }

        // اگه کاربر قبلاً یک DNS رو انتخاب کرده و هنوز توی لیست جدید هست، همونو نگه دار.
        // وگرنه، پروفایلی که سرور به‌عنوان فعال علامت زده (یا در نبودش، اولین آیتم) انتخاب بشه.
        val stillExists = selectedDnsName != null && profiles.any { it.name == selectedDnsName }
        if (!stillExists) {
            val defaultProfile = profiles.firstOrNull { it.isActive } ?: profiles.first()
            selectedDnsName = defaultProfile.name
            selectedDnsServers = defaultProfile.servers
            directJitterMs = 0.0
            getSharedPreferences("dns_prefs", MODE_PRIVATE).edit()
                .putString("selected_dns", defaultProfile.name)
                .apply()
        } else {
            selectedDnsServers = profiles.first { it.name == selectedDnsName }.servers
        }

        rebuildDnsList()
        if (pingJob == null || pingJob?.isActive == false) {
            startPingLoop()
        }
    }

    private fun rebuildDnsList() {
        runOnUiThread {
            dnsListContainer.removeAllViews()
            dnsItemViews.clear()
            val sorted = dnsItems.sortedWith(compareBy<DnsItem> {
                if (it.ping < 0) Long.MAX_VALUE else it.ping
            })
            sorted.forEach { item ->
                val itemView = DnsItemView(this, item)
                dnsItemViews[item.name] = itemView
                dnsListContainer.addView(itemView.view)
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // وقتی متصلیم، پینگ‌گیری کاملاً pause می‌شه (نه فقط پایین‌تر
                // می‌ره) — آخرین مقداری که قبل از وصل شدن نشون داده بود همون‌جا می‌مونه.
                if (vpnState != VpnUiState.CONNECTED) {
                    // یکی‌یکی و پشت‌سرهم بدون مکث پینگ می‌گیریم؛ فقط وقتی کل
                    // صف تموم شد، ۵ ثانیه صبر می‌کنیم و از اول شروع می‌کنیم.
                    val itemsToPing = dnsItems.toList()
                    for (item in itemsToPing) {
                        if (!isActive) break
                        if (vpnState == VpnUiState.CONNECTED) break // اگه وسط صف وصل شد، صف رو نگه دار

                        val newPing = pingDnsWithFallback(item.servers)

                        mainHandler.post {
                            val index = dnsItems.indexOfFirst { it.name == item.name }
                            if (index >= 0) {
                                val oldItem = dnsItems[index]
                                val oldPing = previousPings[item.name] ?: -1
                                previousPings[item.name] = newPing
                                val newItem = oldItem.copy(
                                    ping = newPing,
                                    previousPing = oldPing
                                )
                                dnsItems[index] = newItem
                                dnsItemViews[item.name]?.update(newItem, item.name == selectedDnsName)

                                // جیتر مستقیم فقط از روی نمونه‌های واقعی و متوالی خودِ
                                // DNS انتخاب‌شده محاسبه می‌شه، نه از روی دیتای لیست.
                                if (item.name == selectedDnsName && oldPing > 0 && newPing > 0) {
                                    val diff = kotlin.math.abs(newPing - oldPing).toDouble()
                                    directJitterMs += (diff - directJitterMs) / 16.0
                                }

                                updateSelectedDnsStats()
                                sortDnsList()
                            }
                        }
                    }
                    delay(5000) // بعد از این‌که کل صف پینگ گرفته شد
                } else {
                    delay(2000)
                }
            }
        }
    }

    private fun sortDnsList() {
        val sorted = dnsItems.sortedWith(compareBy<DnsItem> {
            if (it.ping < 0) Long.MAX_VALUE else it.ping
        })

        // تکنیک FLIP: قبل از جابه‌جایی، موقعیت فعلی (top) هر کارت رو ثبت می‌کنیم
        val oldTops = mutableMapOf<String, Int>()
        dnsItemViews.forEach { (name, itemView) ->
            if (itemView.view.parent != null) oldTops[name] = itemView.view.top
        }

        dnsListContainer.removeAllViews()
        sorted.forEach { item ->
            dnsItemViews[item.name]?.let { view ->
                dnsListContainer.addView(view.view)
            }
        }

        // بعد از این‌که layout جدید محاسبه شد، هر کارت رو از موقعیت قبلی‌اش
        // با یک انیمیشن نرم به موقعیت جدیدش می‌بریم (به‌جای پرش ناگهانی)
        dnsListContainer.doOnPreDraw {
            sorted.forEach { item ->
                val itemView = dnsItemViews[item.name] ?: return@forEach
                val oldTop = oldTops[item.name] ?: return@forEach
                val newTop = itemView.view.top
                val delta = (oldTop - newTop).toFloat()
                if (delta != 0f) {
                    itemView.view.translationY = delta
                    itemView.view.animate()
                        .translationY(0f)
                        .setDuration(350)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }
    }

    private fun updateSelectedDnsStats() {
        val selectedItem = dnsItems.find { it.name == selectedDnsName }
        if (selectedItem != null) {
            lastPingText.text = if (selectedItem.ping > 0) {
                "${selectedItem.ping} ms"
            } else {
                "-- ms"
            }
            jitterText.text = if (directJitterMs > 0) {
                "${directJitterMs.toLong()} ms"
            } else {
                "-- ms"
            }
            when {
                selectedItem.ping < 0 -> lastPingText.setTextColor(Color.parseColor("#666666"))
                selectedItem.ping < 50 -> lastPingText.setTextColor(Color.parseColor("#4CAF50"))
                selectedItem.ping < 100 -> lastPingText.setTextColor(Color.parseColor("#FFC107"))
                else -> lastPingText.setTextColor(Color.parseColor("#F44336"))
            }
        }
    }

    /**
     * وقتی VPN خودمون وصله، مسیر همون IP سرور DNS از قبل داخل تون گرفته شده،
     * پس اگه این سوکت پینگ رو protect/bind نکنیم، پکتش هم از تون خودمون رد
     * می‌شه (برنامه -> تون -> relay ما -> DNS واقعی -> برگشت) و RTT دوبرابر
     * و کندتر از پینگ واقعی نشون داده می‌شه. با bindSocket به شبکه‌ی زیرین
     * (وای‌فای/دیتا)، این سوکت کاملاً از تون خودمون عبور می‌کنه و RTT واقعیه.
     */
    // کش می‌کنیم تا هم هر پینگ مجبور به یک IPC جدید به ConnectivityManager نباشه،
    // هم مهم‌تر: انتخاب شبکه هر بار عوض نشه (که باعث پینگ‌های ناپایدار می‌شد).
    private var cachedUnderlyingNetwork: android.net.Network? = null
    private var cachedNetworkTimestamp = 0L

    private fun getUnderlyingNetwork(): android.net.Network? {
        val now = System.currentTimeMillis()
        if (cachedUnderlyingNetwork != null && now - cachedNetworkTimestamp < 15000) {
            return cachedUnderlyingNetwork
        }
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val candidates = cm.allNetworks.mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) return@mapNotNull null
                net to caps
            }
            // ترتیب ترجیح ثابت: وای‌فای > اترنت > موبایل دیتا > هر چیز دیگه.
            // قبلاً firstOrNull بدون اولویت بود و ترتیب allNetworks تضمین‌شده
            // نیست، پس بین وای‌فای و دیتا به‌طور نامنظم جابه‌جا می‌شد و باعث
            // می‌شد پینگ بعضی DNSها هر دور تصادفی بالاتر دیده بشه.
            val chosen = candidates.firstOrNull { (_, c) -> c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) }
                ?: candidates.firstOrNull { (_, c) -> c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) }
                ?: candidates.firstOrNull { (_, c) -> c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) }
                ?: candidates.firstOrNull()
            cachedUnderlyingNetwork = chosen?.first
            cachedNetworkTimestamp = now
            cachedUnderlyingNetwork
        } catch (e: Exception) {
            null
        }
    }

    /**
     * دقیقاً همون ترتیبی که یک resolver واقعی امتحان می‌کنه: اول primary
     * IPv4، اگه جواب نداد/تایم‌اوت شد primary IPv6، بعد secondary IPv4،
     * بعد secondary IPv6. به محض اولین جواب موفق، همون رو برمی‌گردونه —
     * یعنی عددی که نشون داده می‌شه دقیقاً همون چیزیه که کاربر در عمل
     * تجربه می‌کنه، نه یک میانگین تئوریک از چند سرور که واقعاً همزمان
     * استفاده نمی‌شن.
     */
    private suspend fun pingDnsWithFallback(servers: List<DnsServer>): Long {
        val order = listOf(
            "ipv4" to "primary",
            "ipv6" to "primary",
            "ipv4" to "secondary",
            "ipv6" to "secondary"
        )
        for ((family, role) in order) {
            val address = servers.firstOrNull { it.family == family && it.role == role }?.address
                ?: continue
            val ping = pingDns(address)
            if (ping > 0) return ping // این یکی جواب داد، دیگه لازم نیست بقیه رو امتحان کنیم
        }
        return -1L
    }

    private suspend fun pingDns(address: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    getUnderlyingNetwork()?.let { net ->
                        try {
                            net.bindSocket(socket)
                        } catch (_: Exception) {
                            cachedUnderlyingNetwork = null // شبکه‌ی کش‌شده احتمالاً مرده، دفعه‌ی بعد دوباره پیدا کن
                        }
                    }
                }
                socket.soTimeout = 500
                val data = byteArrayOf(
                    0x00, 0x01,
                    0x00, 0x00,
                    0x00, 0x01,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00, 0x00,
                    0x00,
                    0x00, 0x02,
                    0x00, 0x01
                )
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(address),
                    53
                )
                // تایمر رو دقیقاً همین‌جا شروع می‌کنیم، نه قبل از ساخت سوکت —
                // اون overhead ساخت/bind سوکت جزو تاخیر شبکه نیست و نباید
                // توی عدد پینگ حساب بشه (باعث بالاتر دیده شدن از پینگ واقعی می‌شد).
                val start = System.nanoTime()
                socket.send(packet)
                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                socket.close()
                elapsedMs
            } catch (e: Exception) {
                -1L
            }
        }
    }

    private fun startStatsUpdateLoop() {
        statsJob = lifecycleScope.launch {
            while (isActive) {
                updateStatsDisplay()
                delay(1000)
            }
        }
    }

    private fun updateStatsDisplay() {
        runOnUiThread {
            val sent = VpnStats.totalPacketsSent.get()
            val lost = VpnStats.totalPacketsLost.get()
            val bytesSent = VpnStats.totalBytesSent.get()
            val bytesRecv = VpnStats.totalBytesReceived.get()
            (packetsSentText.findViewWithTag<TextView>("value"))?.text = "$sent"
            (packetsLostText.findViewWithTag<TextView>("value"))?.text = "$lost"
            (bytesSentText.findViewWithTag<TextView>("value"))?.text = formatBytes(bytesSent)
            (bytesReceivedText.findViewWithTag<TextView>("value"))?.text = formatBytes(bytesRecv)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun showLoading() {
        runOnUiThread {
            retryButton.visibility = View.GONE
            loadingSpinner.visibility = View.VISIBLE
            val rotation = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1000
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            }
            loadingSpinner.startAnimation(rotation)
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            loadingSpinner.clearAnimation()
            loadingSpinner.visibility = View.GONE
            retryButton.visibility = View.GONE
        }
    }

    private fun showError() {
        runOnUiThread {
            loadingSpinner.clearAnimation()
            loadingSpinner.visibility = View.GONE
            retryButton.visibility = View.VISIBLE
            Toast.makeText(this, "خطا در دریافت DNS. دکمه ↻ را بزنید.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectDns(name: String) {
        val item = dnsItems.find { it.name == name } ?: return
        selectedDnsName = name
        selectedDnsServers = item.servers
        directJitterMs = 0.0 // جیتر مال سرور قبلی بود، برای این یکی از صفر شروع می‌شه
        getSharedPreferences("dns_prefs", MODE_PRIVATE).edit()
            .putString("selected_dns", name)
            .apply()
        dnsItemViews.forEach { (n, view) ->
            view.update(dnsItems.find { it.name == n }!!, n == selectedDnsName)
        }
        updateSelectedDnsStats()
        if (vpnState == VpnUiState.CONNECTED) {
            // با DNS جدید دوباره وصل شو: اول قطع، بعد از تایید قطع واقعی، وصل با سرور جدید
            setVpnState(VpnUiState.DISCONNECTING)
            val stopIntent = Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_STOP
            }
            startService(stopIntent)
            confirmJob?.cancel()
            confirmJob = lifecycleScope.launch {
                waitForActualState(expectActive = false, timeoutMs = 4000)
                startVpn()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isDrawerOpen) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pingJob?.cancel()
        statsJob?.cancel()
        confirmJob?.cancel()
        try {
            unregisterReceiver(vpnReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    inner class DnsItemView(context: Context, initial: DnsItem) {
        val view: LinearLayout
        private val nameText: TextView
        private val pingText: TextView
        private val loadingDots: LinearLayout
        private val percentBadge: TextView
        private val expandArrow: ExpandArrowView
        private val detailsPanel: LinearLayout
        private val detailSentText: TextView
        private val detailLostText: TextView
        private val detailTotalText: TextView
        private val detailAvgText: TextView
        private var isSelected = false
        private var isExpanded = false
        private var cardBgColor = Color.parseColor("#1E1E2E")
        private var cardStrokeColor = Color.parseColor("#2A2A3E")
        private var cardColorAnimator: android.animation.ValueAnimator? = null
        private val cardShape: android.graphics.drawable.GradientDrawable

        init {
            cardShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = 24f
                setStroke(2, cardStrokeColor)
            }
            view = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
                background = cardShape
                isClickable = true
                isFocusable = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 24f
                        setColor(Color.WHITE)
                    }
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                        null,
                        rippleMask
                    )
                }
                setOnClickListener {
                    selectDns(initial.name)
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val infoContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameText = TextView(context).apply {
                text = initial.name
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }
            val pingRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pingText = TextView(context).apply {
                text = if (initial.ping > 0) "${initial.ping} ms" else ""
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
                visibility = if (initial.ping > 0) View.VISIBLE else View.GONE
            }
            loadingDots = buildLoadingDots(context).apply {
                visibility = if (initial.ping > 0) View.GONE else View.VISIBLE
            }
            // درصد موفقیت ارسال پکت‌ها (از آماری که سرور برگردونده)، همون
            // کنار پینگ — یک جای مناسب و کم‌حجم که همیشه در دید باشه.
            percentBadge = TextView(context).apply {
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding(14, 4, 14, 4)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 12 }
            }
            pingRow.addView(pingText)
            pingRow.addView(loadingDots)
            pingRow.addView(percentBadge)
            infoContainer.addView(nameText)
            infoContainer.addView(pingRow)

            // به‌جای دایره‌ی انتخاب، فقط نوک یک پیکان: پر برای کارت انتخاب‌شده،
            // خالی برای بقیه. کلیک روش کاملاً جدا از انتخاب کارت عمل می‌کنه —
            // فقط باز/بسته کردن جزئیات آماره، نه انتخاب DNS.
            expandArrow = ExpandArrowView(context).apply {
                layoutParams = LinearLayout.LayoutParams(88, 88).apply { marginStart = 4 }
                isClickable = true
                isFocusable = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    }
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                        null,
                        rippleMask
                    )
                }
                setOnClickListener { toggleExpanded() }
            }
            topRow.addView(infoContainer)
            topRow.addView(expandArrow)

            // پنل جزئیات، پیش‌فرض بسته (ارتفاع صفر)
            detailsPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                )
            }
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    topMargin = 20
                    bottomMargin = 16
                }
                setBackgroundColor(Color.parseColor("#2A2A3E"))
            }
            val statsGrid = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun statCell(label: String): TextView {
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val labelView = TextView(context).apply {
                    text = label
                    setTextColor(Color.parseColor("#888888"))
                    textSize = 11f
                    gravity = Gravity.CENTER
                }
                val valueView = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 0)
                }
                cell.addView(labelView)
                cell.addView(valueView)
                statsGrid.addView(cell)
                return valueView
            }
            detailSentText = statCell("ارسالی")
            detailLostText = statCell("گم‌شده")
            detailTotalText = statCell("کل")
            detailAvgText = statCell("میانگین")
            detailsPanel.addView(divider)
            detailsPanel.addView(statsGrid)

            view.addView(topRow)
            view.addView(detailsPanel)
        }

        private fun buildLoadingDots(context: Context): LinearLayout {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (i in 0 until 3) {
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(10, 10).apply {
                        if (i < 2) marginEnd = 8
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.parseColor("#666680"))
                        }
                    }
                }
                container.addView(dot)
                android.animation.ValueAnimator.ofFloat(0.5f, 1f).apply {
                    duration = 500
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    startDelay = i * 150L
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val scale = it.animatedValue as Float
                        dot.scaleX = scale
                        dot.scaleY = scale
                        dot.alpha = 0.4f + scale * 0.6f
                    }
                    start()
                }
            }
            return container
        }

        /** باز/بسته کردن پنل جزئیات با انیمیشن ارتفاع نرم، و چرخش ۱۸۰ درجه‌ی پیکان. */
        private fun toggleExpanded() {
            isExpanded = !isExpanded
            expandArrow.animate().rotationBy(180f).setDuration(280)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()

            if (isExpanded) {
                detailsPanel.visibility = View.VISIBLE
                detailsPanel.measure(
                    View.MeasureSpec.makeMeasureSpec((view.width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = detailsPanel.measuredHeight
                animateHeight(detailsPanel, 0, targetHeight) {}
            } else {
                val startHeight = detailsPanel.height
                animateHeight(detailsPanel, startHeight, 0) {
                    detailsPanel.visibility = View.GONE
                }
            }
        }

        private fun animateHeight(target: View, from: Int, to: Int, onEnd: () -> Unit) {
            val animator = android.animation.ValueAnimator.ofInt(from, to)
            animator.duration = 300
            animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            animator.addUpdateListener {
                val params = target.layoutParams
                params.height = it.animatedValue as Int
                target.layoutParams = params
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            animator.start()
        }

        private var lastKnownSelected: Boolean? = null
        private var lastKnownPing: Long? = null
        private var lastKnownPercent: Double? = null
        private var lastKnownStatsSent: Long? = null
        private var lastKnownStatsLost: Long? = null

        fun update(item: DnsItem, isSelected: Boolean) {
            this.isSelected = isSelected
            if (nameText.text != item.name) nameText.text = item.name

            if (lastKnownPing != item.ping) {
                lastKnownPing = item.ping
                if (item.ping > 0) {
                    pingText.text = "${item.ping} ms"
                    pingText.visibility = View.VISIBLE
                    loadingDots.visibility = View.GONE
                } else {
                    pingText.visibility = View.GONE
                    loadingDots.visibility = View.VISIBLE
                }
                when {
                    item.ping < 0 -> pingText.setTextColor(Color.parseColor("#666666"))
                    item.ping < 50 -> pingText.setTextColor(Color.parseColor("#4CAF50"))
                    item.ping < 100 -> pingText.setTextColor(Color.parseColor("#FFC107"))
                    else -> pingText.setTextColor(Color.parseColor("#F44336"))
                }
            }

            val statsEnabled = AppSettings.isShowPacketPercentEnabled(applicationContext)
            val percent = if (statsEnabled) item.successPercent else null
            if (lastKnownPercent != percent) {
                lastKnownPercent = percent
                if (percent != null) {
                    percentBadge.visibility = View.VISIBLE
                    percentBadge.text = "%${"%.0f".format(percent)}"
                    val badgeColor = when {
                        percent >= 95 -> Color.parseColor("#4CAF50")
                        percent >= 85 -> Color.parseColor("#FFC107")
                        else -> Color.parseColor("#F44336")
                    }
                    percentBadge.setTextColor(badgeColor)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        percentBadge.background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 20f
                            setColor(Color.parseColor("#22FFFFFF"))
                            setStroke(1, badgeColor)
                        }
                    }
                } else {
                    percentBadge.visibility = View.GONE
                }
            }

            if (!statsEnabled) {
                // این تنظیم خاموشه؛ حتی اگه از قبل دیتای آماری روی دیسک/آبجکت
                // مونده باشه، اصلاً نشونش نمی‌دیم.
                detailSentText.text = "--"
                detailLostText.text = "--"
                detailTotalText.text = "--"
                detailAvgText.text = "--"
            } else if (lastKnownStatsSent != item.statsPacketsSent || lastKnownStatsLost != item.statsPacketsLost) {
                lastKnownStatsSent = item.statsPacketsSent
                lastKnownStatsLost = item.statsPacketsLost
                detailSentText.text = item.statsPacketsSent.toString()
                detailLostText.text = item.statsPacketsLost.toString()
                detailTotalText.text = item.statsTotal.toString()
                detailAvgText.text = if (percent != null) "%${"%.1f".format(percent)}" else "--"
            }

            // فقط وقتی خودِ وضعیت انتخاب واقعاً عوض شده انیمیشن رنگ اجرا بشه؛
            // وگرنه با هر تپ روی هر کارتی (که همه‌ی کارت‌ها رو update می‌کنه)
            // کل لیست دوباره انیمیشن می‌گرفت و حس «از نو ساخته شدن» می‌داد.
            if (lastKnownSelected == isSelected) return
            lastKnownSelected = isSelected

            // به‌جای سبز شدن، کارت انتخاب‌شده فقط کمی از بقیه سفیدتر می‌شه؛
            // تغییر رنگ هم نرمه، نه پرش ناگهانی.
            val targetBg = if (isSelected) Color.parseColor("#2C2C3E") else Color.parseColor("#1E1E2E")
            val targetStroke = if (isSelected) Color.parseColor("#4A4A60") else Color.parseColor("#2A2A3E")
            animateCardColors(targetBg, targetStroke)

            // پر بودن پیکان = انتخاب‌شده؛ خالی = انتخاب‌نشده. خودِ View داخلی
            // این تغییر رو نرم انیمیت می‌کنه (ضخامت + گرادیانت)، این کاملاً
            // مستقل از حالت باز/بسته‌ی جزئیاته (که با چرخش کنترل می‌شه).
            expandArrow.filled = isSelected
        }

        private fun animateCardColors(targetBg: Int, targetStroke: Int) {
            val fromBg = cardBgColor
            val fromStroke = cardStrokeColor
            val evaluator = android.animation.ArgbEvaluator()
            cardColorAnimator?.cancel()
            cardColorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    cardShape.setColor(evaluator.evaluate(f, fromBg, targetBg) as Int)
                    cardShape.setStroke(2, evaluator.evaluate(f, fromStroke, targetStroke) as Int)
                }
                start()
            }
            cardBgColor = targetBg
            cardStrokeColor = targetStroke
        }
    }
}
