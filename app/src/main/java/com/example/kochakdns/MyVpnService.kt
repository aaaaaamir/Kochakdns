package com.example.kochakdns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * سرویس VPN — فقط DNS را از تونل عبور می‌دهد (پایدار و بدون NAT).
 *
 * حالت‌ها:
 *  ۱) DEFAULT: هیچ انتخاب سفارشی نیست → همه‌ی برنامه‌ها DNS سفارشی می‌گیرند.
 *  ۲) SPLIT (انتخاب سفارشی): برنامه‌های انتخاب‌نشده با addDisallowedApplication
 *     از تونل مستثنی می‌شوند → DNS سیستم می‌گیرند و اینترنت‌شان دست‌نخورده است.
 *
 * فقط مسیر IP سرورهای DNS وارد تون می‌شود؛ بقیه‌ی ترافیک از مسیر عادی شبکه
 * رد می‌شود. برای همین اینترنت همه‌ی برنامه‌ها همیشه برقرار است.
 */
class MyVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_channel"
        const val CHANNEL_ID_MIN = "vpn_channel_min"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val ACTION_RESTART = "action_restart"
        const val EXTRA_DNS_SERVERS = "dns_servers"
        const val EXTRA_DNS_NAME = "dns_name"

        private const val TUN_ADDRESS = "10.8.0.1"
        // آدرس محلی ULA برای رابط تون در حالت IPv6؛ فقط برای خود دستگاه معتبره
        private const val TUN_ADDRESS_V6 = "fd12:3456:789a::1"
        // حداکثر تعداد پرس‌وجوی DNS هم‌زمان در حال relay؛ محافظت در برابر flood
        private const val MAX_CONCURRENT_RELAYS = 16

        // حداکثر حجم payload که یک پاسخ UDP می‌تواند حمل کند (MTU 1500 − هدرها)
        private const val MAX_UDP_PAYLOAD = 1472

        // کلیدهای ذخیره‌ی آخرین DNS (برای اتصال مجدد خودکار و کاشی دسترسی سریع)
        const val PREFS_DNS = "dns_prefs"
        const val KEY_LAST_DNS_SERVERS = "last_dns_servers"
        const val KEY_LAST_DNS_NAME = "last_dns_name"

        // یک scope و کلاینت مستقل و جدا از serviceJob
        private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val relayPermits = Semaphore(MAX_CONCURRENT_RELAYS)
    private val outputMutex = Mutex()

    // ===== بهینه‌سازی: مخزن سوکت‌های DNS =====
    // سوکت‌های protected بازیافت می‌شوند؛ اکثر کوئری‌ها دیگر نه ساخت سوکت
    // دارند نه IPC اضافه (protect).
    private val dnsSocketPool = ConcurrentLinkedQueue<DatagramSocket>()
    private val dnsSocketPoolLimit = 20

    private var readerJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectStartTime: Long = 0L
    private var statsUpdateHandler: Handler? = null

    // کش DNS (با TTL واقعی + کش منفی + stale-while-revalidate).
    private val dnsCache = DnsCache()
    private var dnsCacheEnabled = true
    // پشتیبانی TCP fallback (قابل خاموش شدن از تنظیمات؛ پیش‌فرض فعال)
    private var tcpFallbackEnabled = true

    // یادگیری RTT هر سرور (برای تایم‌اوت تطبیقی) و مجموعه‌ی کلیدهای در حال
    // تازه‌سازی پیشدستانه‌ی کش — تا برای یک کلید دو تازه‌سازی موازی نشود.
    private val rttEma = ConcurrentHashMap<String, Double>()
    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()
    // شمارش شکست‌های متوالی UDP برای هر سرور: وقتی UDP چند بار پشت‌سرهم جواب
    // ندهد، سریع‌تر به TCP سوییچ می‌کنیم (به‌جای ۵ ثانیه انتظار برای UDP).
    private val udpFailStreak = ConcurrentHashMap<String, Int>()

    // آخرین DNSهایی که تونل با آن‌ها ساخته شده
    private var lastDnsServers: List<String> = emptyList()
    private var lastDnsName: String = "DNS"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> {
                lastDnsServers = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS) ?: emptyList()
                lastDnsName = intent.getStringExtra(EXTRA_DNS_NAME) ?: "DNS"
                persistLastDns(lastDnsServers, lastDnsName)
                startVpn(lastDnsServers, lastDnsName)
            }
            ACTION_RESTART -> {
                restartVpn()
            }
            else -> {
                val (servers, name) = readLastDns()
                if (servers.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                lastDnsServers = servers
                lastDnsName = name
                startVpn(servers, name)
            }
        }
        return START_STICKY
    }

    /** تونل فعلی را می‌بندد و با همان DNSهای قبلی دوباره می‌سازد. */
    private fun restartVpn() {
        readerJob?.cancel()
        readerJob = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        startVpn(lastDnsServers, lastDnsName)
    }

    private fun startVpn(dnsServers: List<String>, dnsName: String) {
        val ipv6Enabled = getSharedPreferences(PREFS_DNS, MODE_PRIVATE).getBoolean("ipv6_enabled", true)
        // آدرس‌ها نرمال‌سازی می‌شوند (مثلاً حذف براکت‌های [ ] از IPv6) تا
        // addRoute/addDnsServer هرگز با آدرسِ بد خطا ندهند.
        val validV4 = dnsServers.filter { it.isNotBlank() && isIpv4(it) }.map { it.trim() }
        val validV6 = if (ipv6Enabled) {
            dnsServers.filter { it.isNotBlank() && isIpv6(it) }
                .map { it.trim().removePrefix("[").removeSuffix("]") }
        } else {
            emptyList()
        }
        if (validV4.isEmpty() && validV6.isEmpty()) { stopSelf(); return }

        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال به $dnsName..."))

        // null یعنی کاربر هیچ انتخاب سفارشی‌ای نکرده → «همه برنامه‌ها».
        val selectedPackages = TunnelAppsStore.getSelectedPackages(this)
        val hasSelection = selectedPackages != null

        // خواندن وضعیت کش DNS و TCP fallback از تنظیمات (هر دو پیش‌فرض فعال)
        dnsCacheEnabled = AppSettings.isDnsCacheEnabled(this)
        tcpFallbackEnabled = AppSettings.isTcpFallbackEnabled(this)

        val builder = Builder().apply {
            addAddress(TUN_ADDRESS, 32)
            try { addAddress(TUN_ADDRESS_V6, 128) } catch (_: Exception) {}

            // ===== فقط DNS (حالت امن و پایدار) =====
            // فقط مسیر خودِ سرورهای DNS وارد تون می‌شود؛ بقیه‌ی ترافیک هر اپی
            // از مسیر عادی شبکه رد می‌شود. برای همین به هیچ NAT/موتور تونل
            // نیاز نیست و اینترنت همه‌ی برنامه‌ها دست‌نخورده می‌ماند.
            // (قبلاً اینجا addRoute("0.0.0.0",0) بود که کل ترافیک را وارد تون
            // می‌کرد و چون موتور NAT دست‌نویس خراب بود، هیچ صفحه‌ای باز نمی‌شد.)
            validV4.take(4).forEach { dns ->
                try { addRoute(dns, 32); addDnsServer(dns) } catch (_: Exception) {}
            }
            validV6.take(4).forEach { dns ->
                try { addRoute(dns, 128); addDnsServer(dns) } catch (_: Exception) {}
            }

            // اگر انتخاب سفارشی وجود دارد، برنامه‌های انتخاب‌نشده از تونل مستثنی
            // می‌شوند (DNS سیستم می‌گیرند و اینترنت‌شان دست‌نخورده می‌ماند).
            if (hasSelection) {
                try {
                    val selected = selectedPackages ?: emptySet()
                    val allPackages = packageManager.getInstalledApplications(0).map { it.packageName }
                    allPackages.filterNot { selected.contains(it) }.forEach { pkg ->
                        try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            setSession("Kochak DNS - $dnsName")
            setBlocking(true)
            setMtu(1500)
        }

        try {
            vpnInterface = builder.establish() ?: run { stopSelf(); return }
            VpnStats.isVpnActive = true
            VpnStats.activeDnsName = dnsName
            VpnStats.totalBytesSent.set(0)
            VpnStats.totalBytesReceived.set(0)
            VpnStats.totalPacketsSent.set(0)
            VpnStats.totalPacketsLost.set(0)
            VpnStats.totalPacketsBlocked.set(0)
            VpnStats.dnsCacheHits.set(0)
            VpnStats.dnsCacheMisses.set(0)
            connectStartTime = System.currentTimeMillis()
            readerJob = serviceScope.launch { processPackets() }
            statsUpdateHandler?.removeCallbacksAndMessages(null)
            statsUpdateHandler = Handler(Looper.getMainLooper())
            statsUpdateHandler?.post(object : Runnable {
                override fun run() {
                    if (VpnStats.isVpnActive) {
                        updateNotification()
                        statsUpdateHandler?.postDelayed(this, 2000)
                    }
                }
            })
            statsUpdateHandler?.postDelayed(object : Runnable {
                override fun run() {
                    if (VpnStats.isVpnActive) {
                        VpnStats.activeDnsName?.let { name ->
                            PendingStatsStore.save(
                                this@MyVpnService,
                                name,
                                VpnStats.totalPacketsSent.get(),
                                VpnStats.totalPacketsLost.get(),
                                connectStartTime,
                                getOperatorInfo(this@MyVpnService)
                            )
                        }
                        statsUpdateHandler?.postDelayed(this, 5000)
                    }
                }
            }, 5000)
            sendBroadcast(Intent("VPN_STARTED"))
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    // ------------------------------------------------------------
    // فقط پکت‌های UDP روی پورت 53 (DNS) را تشخیص می‌دهد و relay می‌کند.
    // چون فقط مسیر IP سرورهای DNS وارد تون می‌شود، ترافیک دیگری به اینجا
    // نمی‌رسد و اینترنت برنامه‌ها همیشه برقرار است.
    // ------------------------------------------------------------
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnIface = vpnInterface ?: return@withContext
        val input = FileInputStream(vpnIface.fileDescriptor)
        val output = FileOutputStream(vpnIface.fileDescriptor)
        val packet = ByteArray(32767)

        try {
            while (VpnStats.isVpnActive) {
                val length = try {
                    input.read(packet)
                } catch (_: Exception) {
                    break
                }
                if (length <= 0) break

                val data = packet.copyOf(length)
                when {
                    isIpv4Udp53(data) -> {
                        VpnStats.totalPacketsSent.incrementAndGet()
                        VpnStats.totalBytesSent.addAndGet(length.toLong())
                        launchRelay { relayDnsQueryV4(data, output) }
                    }
                    isIpv6Udp53(data) -> {
                        VpnStats.totalPacketsSent.incrementAndGet()
                        VpnStats.totalBytesSent.addAndGet(length.toLong())
                        launchRelay { relayDnsQueryV6(data, output) }
                    }
                    else -> {
                        // فقط DNS وارد تون می‌شود؛ هر چیز دیگر غیرمنتظره است
                        VpnStats.totalPacketsLost.incrementAndGet()
                    }
                }
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    /** هر relay را به‌عنوان یک کوروتین مستقل اجرا می‌کند، با سقف تعداد هم‌زمان. */
    private fun launchRelay(block: suspend () -> Unit) {
        serviceScope.launch {
            if (!relayPermits.tryAcquire()) {
                VpnStats.totalPacketsLost.incrementAndGet()
                return@launch
            }
            try {
                block()
            } finally {
                relayPermits.release()
            }
        }
    }

    /** گرفتن یک سوکت protected از مخزن (یا ساخت جدید در صورت خالی بودن). */
    private fun acquireDnsSocket(): DatagramSocket {
        val s = dnsSocketPool.poll()
        return if (s != null) {
            s
        } else {
            DatagramSocket().apply {
                try { protect(this) } catch (_: Exception) {}
                soTimeout = 5000
            }
        }
    }

    /** بازگرداندن سوکت به مخزن (در صورت پر بودن، بسته می‌شود). */
    private fun releaseDnsSocket(socket: DatagramSocket) {
        if (dnsSocketPool.size < dnsSocketPoolLimit) {
            dnsSocketPool.offer(socket)
        } else {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun relayDnsQueryV4(ipPacket: ByteArray, output: FileOutputStream) {
        try {
            val ihl = (ipPacket[0].toInt() and 0x0F) * 4
            val dstIp = InetAddress.getByAddress(ipPacket.copyOfRange(16, 20))
            val srcIp = InetAddress.getByAddress(ipPacket.copyOfRange(12, 16))
            val srcPort = ((ipPacket[ihl].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 1].toInt() and 0xFF)
            val udpLength = ((ipPacket[ihl + 4].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 5].toInt() and 0xFF)
            val dnsPayload = ipPacket.copyOfRange(ihl + 8, ihl + udpLength)

            // ===== کش DNS (با stale-while-revalidate) =====
            if (dnsCacheEnabled && dnsPayload.size >= 12) {
                val queryId = ((dnsPayload[0].toInt() and 0xFF) shl 8) or (dnsPayload[1].toInt() and 0xFF)
                val cached = dnsCache.get(dnsPayload, queryId)
                if (cached != null) {
                    VpnStats.dnsCacheHits.incrementAndGet()
                    writeReplyV4(output, dstIp, srcIp, srcPort, cached.bytes)
                    // پاسخ نرم (منقضی ولی قابل سرو): در پس‌زمینه تازه‌سازی کن
                    if (cached.stale) refreshCacheEntry(dnsPayload, dstIp)
                    return
                }
                VpnStats.dnsCacheMisses.incrementAndGet()
            }

            // پرس‌وجوی واقعی: اول UDP با تایم‌اوت تطبیقی؛ در صورت بی‌پاسخی TCP
            val responsePayload = queryUpstream(dnsPayload, dstIp)
            if (responsePayload == null) {
                VpnStats.totalPacketsLost.incrementAndGet()
                return
            }
            if (dnsCacheEnabled) dnsCache.put(dnsPayload, responsePayload)
            writeReplyV4(output, dstIp, srcIp, srcPort, responsePayload)
        } catch (_: Exception) {
            VpnStats.totalPacketsLost.incrementAndGet()
        }
    }

    private suspend fun relayDnsQueryV6(ipPacket: ByteArray, output: FileOutputStream) {
        try {
            val dstIp = InetAddress.getByAddress(ipPacket.copyOfRange(24, 40)) as Inet6Address
            val srcIp = InetAddress.getByAddress(ipPacket.copyOfRange(8, 24)) as Inet6Address
            val srcPort = ((ipPacket[40].toInt() and 0xFF) shl 8) or (ipPacket[41].toInt() and 0xFF)
            val udpLength = ((ipPacket[44].toInt() and 0xFF) shl 8) or (ipPacket[45].toInt() and 0xFF)
            val dnsPayload = ipPacket.copyOfRange(48, 40 + udpLength)

            // ===== کش DNS (با stale-while-revalidate) =====
            if (dnsCacheEnabled && dnsPayload.size >= 12) {
                val queryId = ((dnsPayload[0].toInt() and 0xFF) shl 8) or (dnsPayload[1].toInt() and 0xFF)
                val cached = dnsCache.get(dnsPayload, queryId)
                if (cached != null) {
                    VpnStats.dnsCacheHits.incrementAndGet()
                    writeReplyV6(output, dstIp, srcIp, srcPort, cached.bytes)
                    if (cached.stale) refreshCacheEntry(dnsPayload, dstIp)
                    return
                }
                VpnStats.dnsCacheMisses.incrementAndGet()
            }

            // پرس‌وجوی واقعی: اول UDP با تایم‌اوت تطبیقی؛ در صورت بی‌پاسخی TCP
            val responsePayload = queryUpstream(dnsPayload, dstIp)
            if (responsePayload == null) {
                VpnStats.totalPacketsLost.incrementAndGet()
                return
            }
            if (dnsCacheEnabled) dnsCache.put(dnsPayload, responsePayload)
            writeReplyV6(output, dstIp, srcIp, srcPort, responsePayload)
        } catch (_: Exception) {
            VpnStats.totalPacketsLost.incrementAndGet()
        }
    }

    // ------------------------------------------------------------
    // پرس‌وجوی هوشمند upstream: UDP با تایم‌اوت تطبیقی + fallback به TCP
    // ------------------------------------------------------------

    /** پرس‌وجوی DNS از سرور بالادستی: اول UDP، در صورت بی‌پاسخی TCP (اگه فعال باشد). null یعنی شکست کامل. */
    private fun queryUpstream(dnsPayload: ByteArray, server: InetAddress): ByteArray? {
        udpQuery(dnsPayload, server)?.let { return it }
        if (!tcpFallbackEnabled) return null
        val tcpResp = tcpQuery(dnsPayload, server) ?: return null
        return if (tcpResp.size <= MAX_UDP_PAYLOAD) tcpResp else truncateWithTc(tcpResp)
    }

    /** ارسال پرس‌وجوی UDP با تایم‌اوت تطبیقی؛ null یعنی تایم‌اوت/خطا. */
    private fun udpQuery(dnsPayload: ByteArray, server: InetAddress): ByteArray? {
        val socket = acquireDnsSocket()
        return try {
            val streak = udpFailStreak[server.hostAddress] ?: 0
            // بعد از چند شکست متوالی UDP، حدس می‌زنیم UDP این سرور دچار مشکل
            // شده؛ تایم‌اوت را کوتاه می‌کنیم تا زودتر به TCP سوییچ شود.
            val timeout = if (streak >= 2) {
                minOf(adaptiveTimeoutMs(server.hostAddress), 1200)
            } else {
                adaptiveTimeoutMs(server.hostAddress)
            }
            socket.soTimeout = timeout
            val start = System.nanoTime()
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, server, 53))
            val buffer = ByteArray(1500)
            val resp = DatagramPacket(buffer, buffer.size)
            socket.receive(resp)
            val rttMs = (System.nanoTime() - start) / 1_000_000
            udpFailStreak[server.hostAddress] = 0
            recordRtt(server.hostAddress, rttMs)
            resp.data.copyOfRange(0, resp.length)
        } catch (_: Exception) {
            udpFailStreak.merge(server.hostAddress, 1) { a, b -> a + b }
            recordTimeout(server.hostAddress)
            null
        } finally {
            releaseDnsSocket(socket)
        }
    }

    /** پرس‌وجوی TCP (با پیشوند ۲ بایتی طول، RFC 1035) برای وقتی UDP جواب نداد. */
    private fun tcpQuery(dnsPayload: ByteArray, server: InetAddress): ByteArray? {
        return try {
            Socket().use { s ->
                // سوکت نباید از تونل خودمان رد شود وگرنه لوپ می‌شود
                try { protect(s) } catch (_: Exception) {}
                s.connect(InetSocketAddress(server, 53), 3000)
                s.soTimeout = 4000
                val out = DataOutputStream(s.getOutputStream())
                out.write((dnsPayload.size shr 8) and 0xFF)
                out.write(dnsPayload.size and 0xFF)
                out.write(dnsPayload)
                out.flush()
                val input = DataInputStream(s.getInputStream())
                val len = input.readUnsignedShort()
                if (len <= 0) return null
                val buf = ByteArray(len)
                input.readFully(buf)
                buf
            }
        } catch (_: Exception) {
            null
        }
    }

    /** اگر پاسخ از ظرفیت یک UDP جا نشد، با تنظیم بیت TC کوتاهش می‌کنیم. */
    private fun truncateWithTc(response: ByteArray): ByteArray {
        if (response.size <= MAX_UDP_PAYLOAD) return response
        val out = response.copyOf(MAX_UDP_PAYLOAD)
        if (out.size >= 4) {
            out[2] = ((out[2].toInt() and 0xFF) or 0x02).toByte()
        }
        return out
    }

    // ---- تایم‌اوت تطبیقی بر اساس میانگین زمان پاسخ هر سرور ----

    /** تایم‌اوت دینامیک: حدود ۴ برابر میانگین پاسخ، محدود بین ۶۰۰ms تا ۵ ثانیه. */
    private fun adaptiveTimeoutMs(host: String): Int =
        ((rttEma[host] ?: 900.0) * 4.0).toInt().coerceIn(600, 5000)

    private fun recordRtt(host: String, ms: Long) {
        val prev = rttEma[host]
        rttEma[host] = if (prev == null) ms.toDouble() else prev * 0.7 + ms * 0.3
    }

    /** بعد از تایم‌اوت، تخمین را بالا می‌بریم تا دفعه‌ی بعد صبورتر باشیم. */
    private fun recordTimeout(host: String) {
        val prev = rttEma[host] ?: 900.0
        rttEma[host] = (prev * 1.4).coerceAtMost(5000.0)
    }

    // ---- تازه‌سازی پیشدستانه‌ی ورودی‌های کش منقضی‌شده (stale-while-revalidate) ----

    private fun refreshCacheEntry(query: ByteArray, server: InetAddress) {
        val k = dnsCache.keyOf(query)
        if (!refreshingKeys.add(k)) return // هم‌اکنون در حال تازه‌سازی است
        serviceScope.launch {
            if (!relayPermits.tryAcquire()) {
                refreshingKeys.remove(k)
                return@launch
            }
            try {
                val resp = queryUpstream(query, server)
                if (resp != null && dnsCacheEnabled) dnsCache.put(query, resp)
            } finally {
                refreshingKeys.remove(k)
                relayPermits.release()
            }
        }
    }

    // ---- ارسال پاسخ UDP به سمت دستگاه ----

    private suspend fun writeReplyV4(
        output: FileOutputStream,
        serverIp: InetAddress,
        clientIp: InetAddress,
        clientPort: Int,
        payload: ByteArray
    ) {
        val packet = buildIpv4UdpPacket(
            srcIp = serverIp, dstIp = clientIp, srcPort = 53, dstPort = clientPort, payload = payload
        )
        outputMutex.withLock {
            output.write(packet)
            output.flush()
        }
        VpnStats.totalBytesReceived.addAndGet(packet.size.toLong())
    }

    private suspend fun writeReplyV6(
        output: FileOutputStream,
        serverIp: Inet6Address,
        clientIp: Inet6Address,
        clientPort: Int,
        payload: ByteArray
    ) {
        val packet = buildIpv6UdpPacket(
            srcIp = serverIp, dstIp = clientIp, srcPort = 53, dstPort = clientPort, payload = payload
        )
        outputMutex.withLock {
            output.write(packet)
            output.flush()
        }
        VpnStats.totalBytesReceived.addAndGet(packet.size.toLong())
    }

    private fun isIpv4Udp53(data: ByteArray): Boolean {
        if (data.size < 20) return false
        val version = (data[0].toInt() and 0xF0) ushr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return false
        val ihl = (data[0].toInt() and 0x0F) * 4
        if (data.size < ihl + 8) return false
        val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        return dstPort == 53
    }

    private fun isIpv6Udp53(data: ByteArray): Boolean {
        if (data.size < 48) return false
        val version = (data[0].toInt() and 0xF0) ushr 4
        if (version != 6) return false
        val nextHeader = data[6].toInt() and 0xFF
        if (nextHeader != 17) return false
        val dstPort = ((data[42].toInt() and 0xFF) shl 8) or (data[43].toInt() and 0xFF)
        return dstPort == 53
    }

    private fun isIpv6(address: String): Boolean {
        val clean = address.trim().removePrefix("[").removeSuffix("]")
        if (!clean.contains(":")) return false
        return try {
            InetAddress.getByName(clean) is Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    private fun isIpv4(address: String): Boolean {
        val parts = address.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    private fun buildIpv4UdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0
        packet[6] = 0x40.toByte(); packet[7] = 0
        packet[8] = 64
        packet[9] = 17
        packet[10] = 0; packet[11] = 0
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLength shr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0; packet[27] = 0

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun buildIpv6UdpPacket(
        srcIp: Inet6Address,
        dstIp: Inet6Address,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 40 + udpLength
        val packet = ByteArray(totalLength)

        packet[0] = 0x60
        packet[1] = 0; packet[2] = 0; packet[3] = 0
        packet[4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[5] = (udpLength and 0xFF).toByte()
        packet[6] = 17
        packet[7] = 64
        System.arraycopy(srcIp.address, 0, packet, 8, 16)
        System.arraycopy(dstIp.address, 0, packet, 24, 16)

        val udpOffset = 40
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLength and 0xFF).toByte()
        packet[udpOffset + 6] = 0; packet[udpOffset + 7] = 0
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)

        val pseudo = ByteArray(40 + udpLength)
        System.arraycopy(srcIp.address, 0, pseudo, 0, 16)
        System.arraycopy(dstIp.address, 0, pseudo, 16, 16)
        pseudo[32] = 0; pseudo[33] = 0
        pseudo[34] = ((udpLength shr 8) and 0xFF).toByte()
        pseudo[35] = (udpLength and 0xFF).toByte()
        pseudo[36] = 0; pseudo[37] = 0; pseudo[38] = 0
        pseudo[39] = 17
        System.arraycopy(packet, udpOffset, pseudo, 40, udpLength)

        var udpChecksum = checksum(pseudo, 0, pseudo.size)
        if (udpChecksum == 0) udpChecksum = 0xFFFF
        packet[udpOffset + 6] = ((udpChecksum shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun persistLastDns(servers: List<String>, name: String) {
        try {
            getSharedPreferences(PREFS_DNS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_DNS_NAME, name)
                .putString(KEY_LAST_DNS_SERVERS, servers.joinToString("\u0000"))
                .apply()
        } catch (_: Exception) {}
    }

    private fun readLastDns(): Pair<List<String>, String> {
        return try {
            val prefs = getSharedPreferences(PREFS_DNS, MODE_PRIVATE)
            val servers = prefs.getString(KEY_LAST_DNS_SERVERS, null)
                ?.split("\u0000")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val name = prefs.getString(KEY_LAST_DNS_NAME, null) ?: "DNS"
            servers to name
        } catch (_: Exception) {
            emptyList<String>() to "DNS"
        }
    }

    private fun stopVpn() {
        val profileName = VpnStats.activeDnsName
        val sent = VpnStats.totalPacketsSent.get()
        val lost = VpnStats.totalPacketsLost.get()
        val durationMs = if (connectStartTime > 0) System.currentTimeMillis() - connectStartTime else 0L
        if (!profileName.isNullOrBlank() && (sent > 0 || lost > 0) && durationMs >= 30_000) {
            sendStatsToServer(profileName, sent, lost, getOperatorInfo(this))
        }
        PendingStatsStore.clear(this)
        connectStartTime = 0L

        VpnStats.isVpnActive = false
        VpnStats.dnsCacheHits.set(0)
        VpnStats.dnsCacheMisses.set(0)
        readerJob?.cancel()
        readerJob = null
        refreshingKeys.clear()
        udpFailStreak.clear()
        // تخلیه‌ی مخزن سوکت‌ها
        while (true) {
            val s = dnsSocketPool.poll() ?: break
            try { s.close() } catch (_: Exception) {}
        }
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        statsUpdateHandler?.removeCallbacksAndMessages(null)
        sendBroadcast(Intent("VPN_STOPPED"))
        stopForeground(true)
        stopSelf()
    }

    private fun sendStatsToServer(profileName: String, sent: Long, lost: Long, operator: String) {
        statsScope.launch {
            StatsReporter.send(profileName, sent, lost, operator)
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Kochak VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "VPN Service"; setShowBadge(false)
                }
            )
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_MIN, "Kochak VPN (کم‌اهمیت)", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "VPN Service"; setShowBadge(false)
                }
            )
        }
    }

    private fun activeChannelId(): String =
        if (AppSettings.isShowNotificationInfoEnabled(this)) CHANNEL_ID else CHANNEL_ID_MIN

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0,
            Intent(this, DnsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, activeChannelId())
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Kochak DNS")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع اتصال", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        // وقتی «نمایش اطلاعات در نوتیفیکیشن» خاموش باشد، فقط یک متن ساده
        // نشان می‌دهیم؛ اطلاعات پکت‌ها و حجم دیتای منتقل‌شده مخفی می‌شود.
        val contentText = if (AppSettings.isShowNotificationInfoEnabled(this)) {
            val bytesSent = VpnStats.totalBytesSent.get()
            val bytesReceived = VpnStats.totalBytesReceived.get()
            val packetsSent = VpnStats.totalPacketsSent.get()
            val packetsLost = VpnStats.totalPacketsLost.get()
            "↑ ${formatBytes(bytesSent)} | ↓ ${formatBytes(bytesReceived)}\n📦 $packetsSent | ❌ $packetsLost"
        } else {
            VpnStats.activeDnsName?.let { "متصل به $it" } ?: "Kochak DNS فعال است"
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
