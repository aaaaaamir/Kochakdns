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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/**
 * سرویس VPN — بدون هیچ وابستگی به تشخیص UID (که روی برخی دستگاه‌ها برای UDP
 * غیرقابل اعتماد بود و باعث می‌شد برنامه‌های مجاز هم مسدود شوند).
 *
 * همه‌ی منطق «کدام برنامه داخل تونل باشد» به کرنل اندروید (addAllowedApplication /
 * addDisallowedApplication) سپرده شده است — یعنی دقیق و بدون حدس:
 *
 * حالت‌ها:
 *  ۱) DEFAULT: هیچ انتخاب سفارشی نیست → همه‌ی برنامه‌ها DNS سفارشی می‌گیرند.
 *  ۲) SPLIT (انتخاب سفارشی، بدون مسدودسازی واقعی): برنامه‌های انتخاب‌نشده با
 *     addDisallowedApplication از تونل مستثنی می‌شوند → DNS سیستم می‌گیرند و
 *     اینترنت‌شان دست‌نخورده می‌ماند. (هرگز برنامه‌ی مجاز مسدود نمی‌شود.)
 *  ۳) LOCKDOWN (Always-on VPN): فقط برنامه‌های انتخاب‌شده (و خودِ برنامه) با
 *     addAllowedApplication اجازه‌ی VPN دارند؛ مسدودسازی بقیه توسط سیستم
 *     (Block connections without VPN) انجام می‌شود — قابل‌اعتمادترین روش.
 *  ۴) FULL TUNNEL: کل ترافیک (0.0.0.0/0) وارد تون می‌شود؛ برنامه‌های
 *     انتخاب‌نشده با addDisallowedApplication مستثنا می‌شوند، پس هر پکتی که
 *     وارد تون شود قطعاً متعلق به برنامه‌های انتخاب‌شده است و بدون هیچ
 *     بررسی UID توسط TunnelEngine relay می‌شود.
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

        // کلیدهای ذخیره‌ی آخرین DNS (برای اتصال مجدد خودکار)
        private const val PREFS_DNS = "dns_prefs"
        private const val KEY_LAST_DNS_SERVERS = "last_dns_servers"
        private const val KEY_LAST_DNS_NAME = "last_dns_name"

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
    private var tunnelEngine: TunnelEngine? = null

    // حالت‌های فعال
    private var lockdownMode = false       // Always-on VPN (مسدودسازی توسط سیستم)
    private var fullTunnelMode = false     // تونل کامل (کل ترافیک انتخاب‌شده‌ها)

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
        tunnelEngine?.shutdown()
        tunnelEngine = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        startVpn(lastDnsServers, lastDnsName)
    }

    private fun startVpn(dnsServers: List<String>, dnsName: String) {
        val ipv6Enabled = getSharedPreferences(PREFS_DNS, MODE_PRIVATE).getBoolean("ipv6_enabled", true)
        val validV4 = dnsServers.filter { it.isNotBlank() && isIpv4(it) }
        val validV6 = if (ipv6Enabled) dnsServers.filter { it.isNotBlank() && isIpv6(it) } else emptyList()
        if (validV4.isEmpty() && validV6.isEmpty()) { stopSelf(); return }

        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال به $dnsName..."))

        // null یعنی کاربر هیچ انتخاب سفارشی‌ای نکرده → «همه برنامه‌ها».
        val selectedPackages = TunnelAppsStore.getSelectedPackages(this)
        val hasSelection = selectedPackages != null
        val blockEnabled = AppSettings.isBlockNonTunneledEnabled(this)

        // اولویت حالت‌ها: lockdown > full tunnel > split
        // «مسدودسازی برنامه‌های تونل‌نشده» حالا یعنی مسدودسازی از طریق
        // Always-on VPN سیستم (قابل‌اعتمادترین روش). «تونل کامل» مستقل از آن
        // است و فقط وقتی فعال می‌شود که مسدودسازی (lockdown) روشن نباشد.
        lockdownMode = blockEnabled && hasSelection
        fullTunnelMode = !lockdownMode && hasSelection && AppSettings.isFullTunnelEnabled(this)

        val builder = Builder().apply {
            addAddress(TUN_ADDRESS, 32)
            if (validV6.isNotEmpty() || fullTunnelMode) {
                try { addAddress(TUN_ADDRESS_V6, 128) } catch (_: Exception) {}
            }

            if (fullTunnelMode) {
                // ===== تونل کامل =====
                // کل ترافیک وارد تون می‌شود؛ برنامه‌های انتخاب‌نشده پایین‌تر
                // با addDisallowedApplication مستثنا می‌شوند.
                try { addRoute("0.0.0.0", 0) } catch (_: Exception) {}
                try { addRoute("::", 0) } catch (_: Exception) {}
                validV4.take(4).forEach { dns -> try { addDnsServer(dns) } catch (_: Exception) {} }
                validV6.take(4).forEach { dns -> try { addDnsServer(dns) } catch (_: Exception) {} }
            } else {
                // فقط مسیر خودِ سرورهای DNS را می‌گیریم (فقط DNS وارد تون می‌شود).
                validV4.take(4).forEach { dns ->
                    try { addRoute(dns, 32); addDnsServer(dns) } catch (_: Exception) {}
                }
                validV6.take(4).forEach { dns ->
                    try { addRoute(dns, 128); addDnsServer(dns) } catch (_: Exception) {}
                }
            }

            // ===== تعیین اینکه کدام برنامه‌ها داخل/خارج تونل باشند =====
            when {
                lockdownMode -> {
                    // فقط برنامه‌های انتخاب‌شده (و خودِ برنامه) از VPN استفاده می‌کنند؛
                    // بقیه توسط قفل سیستم (Block connections without VPN) مسدود می‌شوند.
                    (selectedPackages ?: emptySet()).forEach { pkg ->
                        try { addAllowedApplication(pkg) } catch (_: Exception) {}
                    }
                    try { addAllowedApplication(packageName) } catch (_: Exception) {}
                }
                fullTunnelMode -> {
                    // تونل کامل: برنامه‌های انتخاب‌نشده از تونل مستثنی می‌شوند
                    // (اینترنت معمولی دارند) و ترافیک برنامه‌های انتخاب‌شده
                    // به‌طور کامل و بدون هیچ بررسی UID (که روی بعضی دستگاه‌ها
                    // اشتباه جواب می‌داد و اینترنت همه را قطع می‌کرد) forward
                    // می‌شود. یعنی برنامه‌های انتخاب‌شده هرگز قطع نمی‌شوند.
                    try {
                        val selected = selectedPackages ?: emptySet()
                        val allPackages = packageManager.getInstalledApplications(0).map { it.packageName }
                        allPackages.filterNot { selected.contains(it) }.forEach { pkg ->
                            try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                hasSelection -> {
                    // انتخاب سفارشی (split): برنامه‌های انتخاب‌نشده از تونل مستثنی
                    // می‌شوند → اینترنت معمولی دارند (مسدود نمی‌شوند، فقط DNS سیستم می‌گیرند).
                    try {
                        val selected = selectedPackages ?: emptySet()
                        val allPackages = packageManager.getInstalledApplications(0).map { it.packageName }
                        allPackages.filterNot { selected.contains(it) }.forEach { pkg ->
                            try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                else -> {
                    // بدون انتخاب سفارشی → همه‌ی برنامه‌ها داخل تونل (DNS سفارشی برای همه)
                }
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
            connectStartTime = System.currentTimeMillis()
            readerJob = serviceScope.launch { processPackets() }
            statsUpdateHandler?.removeCallbacksAndMessages(null)
            statsUpdateHandler = Handler(Looper.getMainLooper())
            statsUpdateHandler?.post(object : Runnable {
                override fun run() {
                    if (VpnStats.isVpnActive) {
                        updateNotification()
                        tunnelEngine?.cleanupIdleSessions()
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
    // فقط پکت‌های UDP روی پورت 53 (DNS) را relay می‌کند؛ در حالت تونل کامل،
    // بقیه‌ی TCP/UDP هم به TunnelEngine سپرده می‌شود. چون برنامه‌های
    // انتخاب‌نشده در سطح کرنل مستثنا شده‌اند، هر پکتی که به اینجا برسد
    // متعلق به برنامه‌های مجاز است — بدون هیچ بررسی UID.
    // ------------------------------------------------------------
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnIface = vpnInterface ?: return@withContext
        val input = FileInputStream(vpnIface.fileDescriptor)
        val output = FileOutputStream(vpnIface.fileDescriptor)
        val packet = ByteArray(32767)

        // در حالت تونل کامل، هر پکتی که وارد تون می‌شود متعلق به برنامه‌های
        // انتخاب‌شده است (چون انتخاب‌نشده‌ها با addDisallowedApplication مستثنا
        // شده‌اند)؛ پس همه را forward می‌کنیم — بدون هیچ بررسی UID که روی
        // بعضی دستگاه‌ها اشتباه جواب می‌داد و باعث قطع اینترنتِ همه می‌شد.
        val localEngine: TunnelEngine? = if (fullTunnelMode) {
            TunnelEngine(
                vpnService = this@MyVpnService,
                output = output,
                scope = serviceScope,
                outputMutex = outputMutex,
                shouldForward = { true }
            )
        } else {
            null
        }
        tunnelEngine = localEngine

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
                    fullTunnelMode && isIpv4(data) -> {
                        handleGeneralPacketV4(data, localEngine)
                    }
                    fullTunnelMode && isIpv6(data) -> {
                        handleGeneralPacketV6(data, localEngine)
                    }
                    else -> {
                        // پروتکل‌های غیر DNS وارد تون نمی‌شوند (به‌جز حالت تونل کامل)
                        VpnStats.totalPacketsLost.incrementAndGet()
                    }
                }
            }
        } finally {
            localEngine?.shutdown()
            if (tunnelEngine === localEngine) tunnelEngine = null
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    private fun isIpv4(data: ByteArray): Boolean =
        data.isNotEmpty() && ((data[0].toInt() and 0xF0) ushr 4) == 4

    private fun isIpv6(data: ByteArray): Boolean =
        data.isNotEmpty() && ((data[0].toInt() and 0xF0) ushr 4) == 6

    /** هر پکت غیر-DNS در حالت تونل کامل، بسته به پروتکلش (TCP/UDP) به TunnelEngine سپرده می‌شود. */
    private fun handleGeneralPacketV4(data: ByteArray, engine: TunnelEngine?) {
        if (data.size < 20) return
        val ihl = (data[0].toInt() and 0x0F) * 4
        when (data[9].toInt() and 0xFF) {
            6 -> engine?.handleTcpV4(data, ihl)
            17 -> engine?.handleUdpV4(data, ihl)
        }
    }

    private fun handleGeneralPacketV6(data: ByteArray, engine: TunnelEngine?) {
        if (data.size < 40) return
        when (data[6].toInt() and 0xFF) {
            6 -> engine?.handleTcpV6(data)
            17 -> engine?.handleUdpV6(data)
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

            val socket = acquireDnsSocket()
            val responsePacket = try {
                val request = DatagramPacket(dnsPayload, dnsPayload.size, dstIp, 53)
                socket.send(request)
                val responseBuf = ByteArray(1500)
                val resp = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(resp)
                resp
            } finally {
                releaseDnsSocket(socket)
            }

            val replyIpPacket = buildIpv4UdpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = 53,
                dstPort = srcPort,
                payload = responsePacket.data.copyOfRange(0, responsePacket.length)
            )

            outputMutex.withLock {
                output.write(replyIpPacket)
                output.flush()
            }
            VpnStats.totalBytesReceived.addAndGet(replyIpPacket.size.toLong())
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

            val socket = acquireDnsSocket()
            val responsePacket = try {
                val request = DatagramPacket(dnsPayload, dnsPayload.size, dstIp, 53)
                socket.send(request)
                val responseBuf = ByteArray(1500)
                val resp = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(resp)
                resp
            } finally {
                releaseDnsSocket(socket)
            }

            val replyIpPacket = buildIpv6UdpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = 53,
                dstPort = srcPort,
                payload = responsePacket.data.copyOfRange(0, responsePacket.length)
            )

            outputMutex.withLock {
                output.write(replyIpPacket)
                output.flush()
            }
            VpnStats.totalBytesReceived.addAndGet(replyIpPacket.size.toLong())
        } catch (_: Exception) {
            VpnStats.totalPacketsLost.incrementAndGet()
        }
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
        if (!address.contains(":")) return false
        return try {
            InetAddress.getByName(address) is Inet6Address
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
        readerJob?.cancel()
        readerJob = null
        tunnelEngine?.shutdown()
        tunnelEngine = null
        lockdownMode = false
        fullTunnelMode = false
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
        if (AppSettings.isShowNotificationEnabled(this)) CHANNEL_ID else CHANNEL_ID_MIN

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
        val bytesSent = VpnStats.totalBytesSent.get()
        val bytesReceived = VpnStats.totalBytesReceived.get()
        val packetsSent = VpnStats.totalPacketsSent.get()
        val packetsLost = VpnStats.totalPacketsLost.get()
        val contentText = "↑ ${formatBytes(bytesSent)} | ↓ ${formatBytes(bytesReceived)}\n📦 $packetsSent | ❌ $packetsLost"
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
