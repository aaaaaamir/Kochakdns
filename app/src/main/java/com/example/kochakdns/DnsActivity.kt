package com.example.kochakdns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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

class DnsActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var powerButton: LinearLayout
    private lateinit var powerIcon: TextView
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
    private lateinit var activeProfileNameText: TextView

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
        activeProfileNameText = TextView(this).apply {
            text = "پروفایل فعال"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        header.addView(activeProfileNameText)
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
            setOnClickListener { lifecycleScope.launch { syncDnsData() } }
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
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#1E1E2E"))
                    setStroke(8, Color.parseColor("#2A2A3E"))
                }
                background = shape
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
        powerIcon = TextView(this).apply {
            text = "⏻"
            textSize = 120f
            setTextColor(Color.parseColor("#666680"))
            gravity = Gravity.CENTER
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
        packetsSentText = createStatItem("📦 ارسالی", "0")
        packetsLostText = createStatItem("❌ گم‌شده", "0")
        bytesSentText = createStatItem("↑ ارسال", "0 B")
        bytesReceivedText = createStatItem("↓ دریافت", "0 B")
        statsLayout.addView(packetsSentText)
        statsLayout.addView(packetsLostText)
        statsLayout.addView(bytesSentText)
        statsLayout.addView(bytesReceivedText)
        mainContainer.addView(statsLayout)
        val listHeader = TextView(this).apply {
            text = "📡 لیست DNS"
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
        setContentView(rootLayout)
        setVpnState(vpnState) // اعمال ظاهر اولیه‌ی دکمه
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
    private fun setVpnState(newState: VpnUiState) {
        vpnState = newState
        runOnUiThread {
            val (bgColor, strokeColor, iconColor, clickable) = when (newState) {
                VpnUiState.CONNECTED -> Quad("#1B3A22", "#4CAF50", "#4CAF50", true)
                VpnUiState.DISCONNECTED -> Quad("#1E1E2E", "#2A2A3E", "#666680", true)
                VpnUiState.CONNECTING -> Quad("#3A2E00", "#FFD700", "#FFD700", false)
                VpnUiState.DISCONNECTING -> Quad("#3A2E00", "#FFD700", "#FFD700", false)
            }

            powerIcon.setTextColor(Color.parseColor(iconColor))
            powerButton.isClickable = clickable
            powerButton.alpha = if (clickable) 1f else 0.75f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(bgColor))
                    setStroke(if (newState == VpnUiState.CONNECTED) 10 else 8, Color.parseColor(strokeColor))
                }
                powerButton.background = shape
            }

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

    private data class Quad(val a: String, val b: String, val c: String, val d: Boolean)


    private suspend fun syncDnsData() {
        isSyncing = true

        // مرحله‌ی ۱: اگه از دور قبل چیزی کش شده، فوری نشونش بده تا کاربر
        // صفحه‌ی خالی/لودینگ نبینه؛ فقط وقتی هیچ کشی نیست لودینگ کامل نشون بده.
        val cached = withContext(Dispatchers.IO) { readCachedProfiles() }
        if (cached != null && cached.isNotEmpty()) {
            loadDnsFromProfiles(cached)
        } else {
            showLoading()
        }

        // مرحله‌ی ۲: sync زنده در پس‌زمینه
        withContext(Dispatchers.IO) {
            val dnsSyncManager = DnsSyncManager(applicationContext)
            dnsSyncManager.sync()
        }

        // مرحله‌ی ۳: لیست تازه رو بخون و جایگزین همون قبلی کن
        withContext(Dispatchers.IO) {
            try {
                val fresh = readCachedProfiles()
                if (fresh != null && fresh.isNotEmpty()) {
                    mainHandler.post {
                        loadDnsFromProfiles(fresh)
                        isSyncing = false
                        hideLoading()
                    }
                } else {
                    mainHandler.post {
                        isSyncing = false
                        // اگه چیزی از قبل (cache) روی صفحه هست، همونو نگه دار؛ فقط
                        // وقتی واقعاً هیچی نداریم خطای کامل نشون بده.
                        if (dnsItems.isEmpty()) showError() else hideLoading()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isSyncing = false
                    if (dnsItems.isEmpty()) showError() else hideLoading()
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

        activeProfileNameText.text = if (profiles.size == 1) "پروفایل DNS" else "انتخاب DNS (${profiles.size} پروفایل)"
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

                        val primaryIpv4 = item.servers.firstOrNull {
                            it.family == "ipv4" && it.role == "primary"
                        }?.address
                        val newPing = if (primaryIpv4 != null) pingDns(primaryIpv4) else -1L

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
    private fun getUnderlyingNetwork(): android.net.Network? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun pingDns(address: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    getUnderlyingNetwork()?.let { net ->
                        try { net.bindSocket(socket) } catch (_: Exception) {}
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
        private val selectButton: TextView

        init {
            view = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(Color.parseColor("#1E1E2E"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1E1E2E"))
                        cornerRadius = 24f
                        setStroke(2, Color.parseColor("#2A2A3E"))
                    }
                    background = shape
                }
                setOnClickListener {
                    selectDns(initial.name)
                }
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
            pingText = TextView(context).apply {
                text = if (initial.ping > 0) "${initial.ping} ms" else "در حال پینگ..."
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
            }
            infoContainer.addView(nameText)
            infoContainer.addView(pingText)
            selectButton = TextView(context).apply {
                text = "انتخاب"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(24, 12, 24, 12)
                setBackgroundColor(Color.parseColor("#4CAF50"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#4CAF50"))
                        cornerRadius = 16f
                    }
                    background = shape
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    selectDns(initial.name)
                }
            }
            view.addView(infoContainer)
            view.addView(selectButton)
        }

        fun update(item: DnsItem, isSelected: Boolean) {
            nameText.text = item.name
            pingText.text = if (item.ping > 0) "${item.ping} ms" else "در حال پینگ..."
            when {
                item.ping < 0 -> pingText.setTextColor(Color.parseColor("#666666"))
                item.ping < 50 -> pingText.setTextColor(Color.parseColor("#4CAF50"))
                item.ping < 100 -> pingText.setTextColor(Color.parseColor("#FFC107"))
                else -> pingText.setTextColor(Color.parseColor("#F44336"))
            }
            if (isSelected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#2E7D32"))
                        cornerRadius = 24f
                        setStroke(3, Color.parseColor("#4CAF50"))
                    }
                    view.background = shape
                }
                selectButton.text = "✓ فعال"
                selectButton.setBackgroundColor(Color.parseColor("#388E3C"))
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1E1E2E"))
                        cornerRadius = 24f
                        setStroke(2, Color.parseColor("#2A2A3E"))
                    }
                    view.background = shape
                }
                selectButton.text = "انتخاب"
                selectButton.setBackgroundColor(Color.parseColor("#4CAF50"))
            }
        }
    }
}
