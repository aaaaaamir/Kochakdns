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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// ==================== Main Activity ====================
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
    private var isSyncing = false
    private var isVpnConnected = false
    private var selectedDnsName: String? = null
    private var selectedDnsServers: List<DnsServer> = emptyList()
    private val dnsItems = mutableListOf<DnsItem>()
    private val dnsItemViews = mutableMapOf<String, DnsItemView>()
    private var previousPings = mutableMapOf<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "VPN_STARTED" -> {
                    isVpnConnected = true
                    updatePowerButton()
                }
                "VPN_STOPPED" -> {
                    isVpnConnected = false
                    updatePowerButton()
                }
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
        lifecycleScope.launch {
            syncDnsData()
            startPingLoop()
            startStatsUpdateLoop()
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
        updatePowerButton()
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
        if (isVpnConnected) {
            try {
                val intent = Intent(this, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_STOP
                }
                startService(intent)
                // آپدیت فوری UI؛ منتظر broadcast نمی‌مونیم چون ممکنه دیر برسه یا نرسه
                isVpnConnected = false
                updatePowerButton()
                Toast.makeText(this, "قطع شد", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "خطا در قطع اتصال: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
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
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        // فیدبک فوری: تا وقتی broadcast تایید وصل شدن برسه، حالت «در حال اتصال» نشون بده
        powerIcon.setTextColor(Color.parseColor("#FFD700"))
        Toast.makeText(this, "در حال اتصال...", Toast.LENGTH_SHORT).show()
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
            updatePowerButton()
        }
    }

    private fun updatePowerButton() {
        runOnUiThread {
            if (isVpnConnected) {
                powerIcon.setTextColor(Color.parseColor("#4CAF50"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#1B3A22"))
                        setStroke(10, Color.parseColor("#4CAF50"))
                    }
                    powerButton.background = shape
                }
            } else {
                powerIcon.setTextColor(Color.parseColor("#666680"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#1E1E2E"))
                        setStroke(8, Color.parseColor("#2A2A3E"))
                    }
                    powerButton.background = shape
                }
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

    private suspend fun syncDnsData() {
        isSyncing = true
        showLoading()

        // ابتدا یک sync زنده انجام می‌دیم و منتظرش می‌مونیم تا کامل بشه.
        // قبلاً این صفحه فقط از DataStore می‌خوند و امیدوار بود که sync
        // پس‌زمینه‌ی MainActivity زودتر تمام شده باشه؛ چون درخواست DoH چند
        // مرحله‌ای شده، این فرض دیگه برقرار نبود و لیست خالی می‌موند.
        withContext(Dispatchers.IO) {
            val dnsSyncManager = DnsSyncManager(applicationContext)
            dnsSyncManager.sync()
        }

        withContext(Dispatchers.IO) {
            try {
                val dataStore = applicationContext.dnsDataStore
                val prefs = dataStore.data.first()
                val json = prefs[stringPreferencesKey("dns_profile_data")]
                if (json != null) {
                    val profile = parseProfileFromJson(json)
                    if (profile != null) {
                        mainHandler.post {
                            loadDnsFromProfile(profile)
                            isSyncing = false
                            hideLoading()
                        }
                        return@withContext
                    }
                }
                mainHandler.post {
                    isSyncing = false
                    showError()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isSyncing = false
                    showError()
                }
            }
        }
    }

    private fun parseProfileFromJson(json: String): DnsProfile? {
        return try {
            val obj = org.json.JSONObject(json)
            val serversArray = obj.optJSONArray("servers")
            val servers = mutableListOf<DnsServer>()
            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    val s = serversArray.getJSONObject(i)
                    servers.add(
                        DnsServer(
                            role = s.optString("role"),
                            priority = s.optInt("priority"),
                            family = s.optString("family"),
                            address = s.optString("address")
                        )
                    )
                }
            }
            DnsProfile(
                name = obj.optString("name"),
                enabled = obj.optBoolean("enabled"),
                ipv4Primary = obj.optString("ipv4Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Primary = obj.optString("ipv6Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv4Secondary = obj.optString("ipv4Secondary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Secondary = obj.optString("ipv6Secondary").takeIf { it.isNotEmpty() && it != "null" },
                servers = servers,
                updatedAt = obj.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadDnsFromProfile(profile: DnsProfile) {
        dnsItems.clear()
        previousPings.clear()
        dnsItems.add(
            DnsItem(
                name = profile.name,
                servers = profile.servers
            )
        )
        activeProfileNameText.text = profile.name
        if (selectedDnsName == null) {
            selectedDnsName = profile.name
            selectedDnsServers = profile.servers
            getSharedPreferences("dns_prefs", MODE_PRIVATE).edit()
                .putString("selected_dns", profile.name)
                .apply()
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
                val itemsToPing = dnsItems.toList()
                val pingResults = itemsToPing.map { item ->
                    async {
                        val primaryIpv4 = item.servers.firstOrNull {
                            it.family == "ipv4" && it.role == "primary"
                        }?.address
                        if (primaryIpv4 != null) {
                            val ping = pingDns(primaryIpv4)
                            Pair(item.name, ping)
                        } else {
                            Pair(item.name, -1L)
                        }
                    }
                }.awaitAll()
                mainHandler.post {
                    pingResults.forEach { (name, newPing) ->
                        val index = dnsItems.indexOfFirst { it.name == name }
                        if (index >= 0) {
                            val oldItem = dnsItems[index]
                            val oldPing = previousPings[name] ?: -1
                            previousPings[name] = newPing
                            val newItem = oldItem.copy(
                                ping = newPing,
                                previousPing = oldPing
                            )
                            dnsItems[index] = newItem
                            dnsItemViews[name]?.update(newItem, name == selectedDnsName)
                        }
                    }
                    updateSelectedDnsStats()
                    sortDnsList()
                }
                delay(2000)
            }
        }
    }

    private fun sortDnsList() {
        val sorted = dnsItems.sortedWith(compareBy<DnsItem> {
            if (it.ping < 0) Long.MAX_VALUE else it.ping
        })
        dnsListContainer.removeAllViews()
        sorted.forEach { item ->
            dnsItemViews[item.name]?.let { view ->
                dnsListContainer.addView(view.view)
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
            jitterText.text = if (selectedItem.jitter > 0) {
                "${selectedItem.jitter} ms"
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

    private suspend fun pingDns(address: String): Long {
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 3000
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
                socket.send(packet)
                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                socket.close()
                System.currentTimeMillis() - start
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
        getSharedPreferences("dns_prefs", MODE_PRIVATE).edit()
            .putString("selected_dns", name)
            .apply()
        dnsItemViews.forEach { (n, view) ->
            view.update(dnsItems.find { it.name == n }!!, n == selectedDnsName)
        }
        updateSelectedDnsStats()
        if (isVpnConnected) {
            val stopIntent = Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_STOP
            }
            startService(stopIntent)
            mainHandler.postDelayed({
                startVpn()
            }, 500)
        }
    }

    override fun onDestroy() {
        pingJob?.cancel()
        statsJob?.cancel()
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
