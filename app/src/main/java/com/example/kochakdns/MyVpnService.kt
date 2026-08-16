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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Semaphore

class MyVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_channel"
        const val CHANNEL_ID_MIN = "vpn_channel_min"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_DNS_SERVERS = "dns_servers"
        const val EXTRA_DNS_NAME = "dns_name"
        private const val TUN_ADDRESS = "10.8.0.1"
        // آدرس محلی ULA برای رابط تون در حالت IPv6؛ فقط برای خود دستگاه معتبره، مسیریابی نمی‌شه
        private const val TUN_ADDRESS_V6 = "fd12:3456:789a::1"
        // حداکثر تعداد پرس‌وجوی DNS هم‌زمان در حال relay؛ محافظت در برابر flood
        private const val MAX_CONCURRENT_RELAYS = 12

        // یک scope و کلاینت مستقل و جدا از serviceJob؛ چون stopVpn() دقیقاً
        // همزمان با متوقف شدن سرویس صدا زده می‌شه، اگه از serviceScope
        // استفاده می‌کردیم، درخواست ارسال آمار قبل از تموم شدن لغو می‌شد.
        private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // یک CoroutineScope مستقل با SupervisorJob: خطای یک relay بقیه رو نمی‌کشه،
    // و با cancel() کل کار به‌طور تمیز و قطعی متوقف می‌شه (به‌جای Thread.interrupt
    // که تضمینی نیست فوراً اثر کنه).
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val relayPermits = Semaphore(MAX_CONCURRENT_RELAYS)
    private val outputMutex = Mutex()

    private var readerJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectStartTime: Long = 0L
    private var statsUpdateHandler: Handler? = null
    private var tunnelEngine: TunnelEngine? = null
    private var fullCaptureMode = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> {
                val dnsServers = intent.getStringArrayListExtra(EXTRA_DNS_SERVERS) ?: emptyList()
                val dnsName = intent.getStringExtra(EXTRA_DNS_NAME) ?: "DNS"
                startVpn(dnsServers, dnsName)
            }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startVpn(dnsServers: List<String>, dnsName: String) {
        val ipv6Enabled = getSharedPreferences("dns_prefs", MODE_PRIVATE).getBoolean("ipv6_enabled", true)
        val validV4 = dnsServers.filter { it.isNotBlank() && isIpv4(it) }
        val validV6 = if (ipv6Enabled) dnsServers.filter { it.isNotBlank() && isIpv6(it) } else emptyList()
        if (validV4.isEmpty() && validV6.isEmpty()) { stopSelf(); return }

        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال به $dnsName..."))

        // حالت «تونل کامل»: فقط وقتی هم کاربر یک زیرمجموعه‌ی خاص از برنامه‌ها
        // رو انتخاب کرده باشه، هم گزینه‌ی «مسدود کردن اینترنت تونل‌نشده‌ها»
        // روشن باشه. در این حالت کل ترافیک (نه فقط DNS) وارد تون می‌شه؛
        // برنامه‌های انتخاب‌شده با TunnelEngine واقعاً relay می‌شن (اینترنت
        // کامل دارن)، بقیه هیچ‌جا relay نمی‌شن یعنی عملاً مسدودن.
        val selectedPackages = TunnelAppsStore.getSelectedPackages(this)
        fullCaptureMode = AppSettings.isBlockNonTunneledEnabled(this) && selectedPackages != null

        val builder = Builder().apply {
            addAddress(TUN_ADDRESS, 32)
            if (validV6.isNotEmpty() || fullCaptureMode) {
                try { addAddress(TUN_ADDRESS_V6, 128) } catch (_: Exception) {}
            }
            if (fullCaptureMode) {
                // کل ترافیک IPv4/IPv6 وارد تون می‌شه؛ فقط برنامه‌های انتخاب‌شده
                // واقعاً relay می‌شن (پایین‌تر توی processPackets/TunnelEngine).
                try { addRoute("0.0.0.0", 0) } catch (_: Exception) {}
                try { addRoute("::", 0) } catch (_: Exception) {}
                validV4.take(4).forEach { dns -> try { addDnsServer(dns) } catch (_: Exception) {} }
                validV6.take(4).forEach { dns -> try { addDnsServer(dns) } catch (_: Exception) {} }
            } else {
                // فقط مسیر خودِ سرورهای DNS رو می‌گیریم (چه v4 چه v6)، نه کل
                // اینترنت. یعنی فقط پرس‌وجوهای DNS وارد تون می‌شن، بقیه‌ی
                // ترافیک هر اپی از مسیر عادی شبکه رد می‌شه و دست‌نخورده باقی می‌مونه.
                validV4.take(4).forEach { dns ->
                    try {
                        addRoute(dns, 32)
                        addDnsServer(dns)
                    } catch (_: Exception) {}
                }
                validV6.take(4).forEach { dns ->
                    try {
                        addRoute(dns, 128)
                        addDnsServer(dns)
                    } catch (_: Exception) {}
                }
                // اگه کاربر یک زیرمجموعه‌ی خاص انتخاب کرده (بدون حالت مسدودسازی)،
                // بقیه‌ی برنامه‌ها رو از تون خودمون مستثنی می‌کنیم (DNS سیستم
                // عادی می‌گیرن، دست‌نخورده). null یعنی چیزی سفارشی انتخاب نشده.
                if (selectedPackages != null) {
                    try {
                        val allPackages = packageManager.getInstalledApplications(0).map { it.packageName }
                        allPackages.filterNot { selectedPackages.contains(it) }.forEach { pkg ->
                            try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
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
            connectStartTime = System.currentTimeMillis()
            readerJob = serviceScope.launch { processPackets() }
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
            // چک‌پوینت پیوسته روی دیسک، جدا از نوتیفیکیشن و با فاصله‌ی خودش
            // (۵ ثانیه)؛ اگه برنامه force-stop بشه، دفعه‌ی بعد از همین آخرین
            // نقطه می‌تونیم ارسال کنیم.
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
    // حالت عادی: فقط پکت‌های UDP روی پورت 53 (DNS) رو تشخیص می‌ده و relay
    // می‌کنه. حالت «تونل کامل» (fullCaptureMode): DNS همچنان با همون منطق
    // خودش relay می‌شه، ولی بقیه‌ی TCP/UDP هم به TunnelEngine سپرده می‌شه
    // (که بر اساس UID مالکِ هر flow تصمیم می‌گیره relay کنه یا نادیده بگیره).
    // خواندن fd مسدودکننده‌ست، برای همین توی Dispatchers.IO اجرا می‌شه.
    // ------------------------------------------------------------
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnIface = vpnInterface ?: return@withContext
        val input = FileInputStream(vpnIface.fileDescriptor)
        val output = FileOutputStream(vpnIface.fileDescriptor)
        val packet = ByteArray(32767)

        if (fullCaptureMode) {
            val selected = TunnelAppsStore.getSelectedPackages(this@MyVpnService) ?: emptySet()
            tunnelEngine = TunnelEngine(
                vpnService = this@MyVpnService,
                output = output,
                scope = serviceScope,
                shouldForward = { uid -> isUidTunneled(uid, selected) }
            )
        }

        try {
            while (VpnStats.isVpnActive) {
                val length = try {
                    input.read(packet)
                } catch (_: Exception) {
                    break // fd بسته شده یا سرویس داره متوقف می‌شه
                }
                if (length <= 0) continue

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
                    fullCaptureMode && isIpv4(data) -> {
                        // نکته: VpnStats.totalPacketsSent/totalBytesSent مخصوص
                        // آمار DNSه (که برای سرور و UI گزارش می‌شه). قبلاً اینجا
                        // هر پکتی که وارد تون می‌شد (حتی پکت‌های اپ‌های مسدودشده
                        // که اصلاً relay نمی‌شن) به‌عنوان "ارسالی" شمرده می‌شد —
                        // که در حالت تونل کامل باعث اعداد کاذب و خیلی بزرگ می‌شد.
                        handleGeneralPacketV4(data)
                    }
                    fullCaptureMode && isIpv6(data) -> {
                        handleGeneralPacketV6(data)
                    }
                    else -> {
                        // حالت عادی: پروتکل‌های غیر DNS رو نادیده می‌گیریم چون
                        // route محدود به IP سرورهای DNS هست، عملاً زیاد پیش نمیاد.
                        VpnStats.totalPacketsLost.incrementAndGet()
                    }
                }
            }
        } finally {
            tunnelEngine?.shutdown()
            tunnelEngine = null
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    private fun isUidTunneled(uid: Int, selectedPackages: Set<String>): Boolean {
        return try {
            val names = packageManager.getPackagesForUid(uid) ?: return false
            names.any { selectedPackages.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun isIpv4(data: ByteArray): Boolean =
        data.isNotEmpty() && ((data[0].toInt() and 0xF0) ushr 4) == 4

    private fun isIpv6(data: ByteArray): Boolean =
        data.isNotEmpty() && ((data[0].toInt() and 0xF0) ushr 4) == 6

    /** هر پکت غیر-DNS در حالت تونل کامل، بسته به پروتکلش (TCP/UDP) به TunnelEngine سپرده می‌شه. */
    private fun handleGeneralPacketV4(data: ByteArray) {
        if (data.size < 20) return
        val ihl = (data[0].toInt() and 0x0F) * 4
        when (data[9].toInt() and 0xFF) {
            6 -> tunnelEngine?.handleTcpV4(data, ihl)   // TCP
            17 -> tunnelEngine?.handleUdpV4(data, ihl)  // UDP
            // ICMP و بقیه‌ی پروتکل‌ها فعلاً پشتیبانی نمی‌شن، بی‌صدا نادیده گرفته می‌شن
        }
    }

    private fun handleGeneralPacketV6(data: ByteArray) {
        if (data.size < 40) return
        when (data[6].toInt() and 0xFF) {
            6 -> tunnelEngine?.handleTcpV6(data)   // TCP
            17 -> tunnelEngine?.handleUdpV6(data)  // UDP
        }
    }

    /** هر relay رو به‌عنوان یک کوروتین مستقل اجرا می‌کنه، با سقف تعداد هم‌زمان. */
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

    private suspend fun relayDnsQueryV4(ipPacket: ByteArray, output: FileOutputStream) {

        try {
            val ihl = (ipPacket[0].toInt() and 0x0F) * 4
            val dstIp = InetAddress.getByAddress(ipPacket.copyOfRange(16, 20))
            val srcIp = InetAddress.getByAddress(ipPacket.copyOfRange(12, 16))
            val srcPort = ((ipPacket[ihl].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 1].toInt() and 0xFF)
            val udpLength = ((ipPacket[ihl + 4].toInt() and 0xFF) shl 8) or (ipPacket[ihl + 5].toInt() and 0xFF)
            val dnsPayload = ipPacket.copyOfRange(ihl + 8, ihl + udpLength)

            val socket = DatagramSocket()
            protect(socket) // خیلی مهم: جلوگیری از این‌که این سوکت خودش دوباره وارد تون بشه (حلقه‌ی بی‌نهایت)
            socket.soTimeout = 5000

            val responsePacket = try {
                val request = DatagramPacket(dnsPayload, dnsPayload.size, dstIp, 53)
                socket.send(request)
                val responseBuf = ByteArray(1500)
                val resp = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(resp)
                resp
            } finally {
                socket.close()
            }

            val replyIpPacket = buildIpv4UdpPacket(
                srcIp = dstIp,           // پاسخ از طرف خودِ سرور DNS واقعی میاد
                dstIp = srcIp,           // به آدرس مجازی تون (10.8.0.1)
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
            // تایم‌اوت یا خطای شبکه روی یک کوئری؛ همون یک کوئری از دست می‌ره، ولی شمارش می‌شه
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

            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000

            val responsePacket = try {
                val request = DatagramPacket(dnsPayload, dnsPayload.size, dstIp, 53)
                socket.send(request)
                val responseBuf = ByteArray(1500)
                val resp = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(resp)
                resp
            } finally {
                socket.close()
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
        if (protocol != 17) return false // UDP
        val ihl = (data[0].toInt() and 0x0F) * 4
        if (data.size < ihl + 8) return false
        val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        return dstPort == 53
    }

    // فرض ساده: بدون extension header (رایج‌ترین حالت برای کوئری DNS)
    // یعنی next header مستقیم پروتکل لایه‌ی بالاتره.
    private fun isIpv6Udp53(data: ByteArray): Boolean {
        if (data.size < 48) return false // 40 هدر ثابت + 8 هدر UDP حداقل
        val version = (data[0].toInt() and 0xF0) ushr 4
        if (version != 6) return false
        val nextHeader = data[6].toInt() and 0xFF
        if (nextHeader != 17) return false // UDP
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

    // می‌سازه یک پکت کامل IPv4 + UDP با هدرهای معتبر (checksum صحیح روی IP هدر)
    // تا کرنل به‌عنوان یک پاسخ واقعی از سمت سرور DNS قبولش کنه.
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

        // --- IPv4 header ---
        packet[0] = 0x45 // version=4, IHL=5 (20 bytes)
        packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0 // identification
        packet[6] = 0x40.toByte(); packet[7] = 0 // flags: don't fragment
        packet[8] = 64 // TTL
        packet[9] = 17 // protocol = UDP
        packet[10] = 0; packet[11] = 0 // checksum (محاسبه می‌شه پایین‌تر)
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // --- UDP header (checksum=0 مجازه برای IPv4، صرف‌نظر می‌کنیم) ---
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLength shr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0; packet[27] = 0 // checksum

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    // برخلاف IPv4، توی IPv6 چک‌سام UDP اجباریه (نمی‌شه صفر گذاشت)، پس باید
    // با pseudo-header (طبق RFC 2460) محاسبه بشه.
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

        // --- IPv6 fixed header (40 بایت) ---
        packet[0] = 0x60 // version=6, traffic class/flow label = 0
        packet[1] = 0; packet[2] = 0; packet[3] = 0
        packet[4] = ((udpLength shr 8) and 0xFF).toByte() // payload length (بدون احتساب هدر ثابت)
        packet[5] = (udpLength and 0xFF).toByte()
        packet[6] = 17 // next header = UDP
        packet[7] = 64 // hop limit
        System.arraycopy(srcIp.address, 0, packet, 8, 16)
        System.arraycopy(dstIp.address, 0, packet, 24, 16)

        // --- UDP header ---
        val udpOffset = 40
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLength shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLength and 0xFF).toByte()
        packet[udpOffset + 6] = 0; packet[udpOffset + 7] = 0 // checksum placeholder
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)

        // --- pseudo-header + UDP segment برای محاسبه‌ی checksum ---
        val pseudo = ByteArray(40 + udpLength)
        System.arraycopy(srcIp.address, 0, pseudo, 0, 16)
        System.arraycopy(dstIp.address, 0, pseudo, 16, 16)
        pseudo[32] = 0; pseudo[33] = 0
        pseudo[34] = ((udpLength shr 8) and 0xFF).toByte()
        pseudo[35] = (udpLength and 0xFF).toByte()
        pseudo[36] = 0; pseudo[37] = 0; pseudo[38] = 0
        pseudo[39] = 17 // next header = UDP
        System.arraycopy(packet, udpOffset, pseudo, 40, udpLength)

        var udpChecksum = checksum(pseudo, 0, pseudo.size)
        if (udpChecksum == 0) udpChecksum = 0xFFFF // طبق RFC، صفر مجاز نیست
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

    private fun stopVpn() {
        // قبل از صفر کردن هر چیزی، آمار همین دورِ اتصال رو برای سرور می‌فرستیم —
        // فقط اگه واقعاً حداقل ۳۰ ثانیه وصل بوده (اتصال‌های خیلی کوتاه آماری
        // معنادار نیستن و ارزش ارسال ندارن).
        val profileName = VpnStats.activeDnsName
        val sent = VpnStats.totalPacketsSent.get()
        val lost = VpnStats.totalPacketsLost.get()
        val durationMs = if (connectStartTime > 0) System.currentTimeMillis() - connectStartTime else 0L
        if (!profileName.isNullOrBlank() && (sent > 0 || lost > 0) && durationMs >= 30_000) {
            sendStatsToServer(profileName, sent, lost, getOperatorInfo(this))
        }
        // این یک قطعِ عادیه (نه force-stop)، پس دیگه چک‌پوینت روی دیسک لازم
        // نیست — یا الان فرستاده شد، یا طبق قانون ۳۰ ثانیه اصلاً نباید بفرستیم.
        PendingStatsStore.clear(this)
        connectStartTime = 0L

        VpnStats.isVpnActive = false
        readerJob?.cancel()
        readerJob = null
        fullCaptureMode = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        statsUpdateHandler?.removeCallbacksAndMessages(null)
        sendBroadcast(Intent("VPN_STOPPED"))
        stopForeground(true)
        stopSelf()
    }

    /** ارسال best-effort آمار پکت‌ها به سرور؛ اگه شکست بخوره اهمیتی نداره، فقط نادیده گرفته می‌شه. */
    private fun sendStatsToServer(profileName: String, sent: Long, lost: Long, operator: String) {
        statsScope.launch {
            StatsReporter.send(profileName, sent, lost, operator)
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel() // هر relay در حال اجرا رو هم قطعی متوقف می‌کنه
        super.onDestroy()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // توجه: اندروید (از نسخه ۸ به بعد) اجازه نمی‌ده یک foreground
            // service (که VPN هم جزوشه) کاملاً بدون نوتیفیکیشن اجرا بشه —
            // این یک محدودیت سیستم‌عامله، نه چیزی که با کد این اپ بشه دورش زد.
            //
            // نکته‌ی مهم: اندروید بعد از اولین ساخت یک NotificationChannel،
            // دیگه اجازه نمی‌ده اهمیتش (importance) با کد عوض بشه — هر بار که
            // createNotificationChannel با همون ID صدا زده بشه ولی importance
            // فرق کنه، اندروید بی‌صدا نادیده‌ش می‌گیره. برای همین قبلاً روشن/
            // خاموش کردن این تنظیم هیچ اثری نداشت. فیکس: به‌جای عوض کردن
            // importance یک channel، از دو تا channel جداگانه (با ID متفاوت
            // و importance ثابت از همون ابتدا) استفاده می‌کنیم و موقع ساختن
            // نوتیفیکیشن، بر اساس تنظیم فعلی یکی‌شون رو انتخاب می‌کنیم.
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
