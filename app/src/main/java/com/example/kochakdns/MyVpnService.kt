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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MyVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_channel"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_DNS_SERVERS = "dns_servers"
        const val EXTRA_DNS_NAME = "dns_name"
        private const val TUN_ADDRESS = "10.8.0.1"
    }

    private var vpnThread: Thread? = null
    private var isCancelled = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var statsUpdateHandler: Handler? = null
    // هر پرس‌وجوی DNS روی یک ترد کوتاه‌عمر جدا relay می‌شه تا خواندن پکت‌های
    // بعدی از تون بلاک نشه؛ تعداد ترد همزمان رو محدود می‌کنیم که کنترل‌شده بمونه.
    private val relayExecutor = Executors.newFixedThreadPool(8)

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
        val validDns = dnsServers.filter { it.isNotBlank() && isIpv4(it) }
        if (validDns.isEmpty()) { stopSelf(); return }

        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال به $dnsName..."))

        val builder = Builder().apply {
            addAddress(TUN_ADDRESS, 32)
            // فقط مسیر خودِ سرورهای DNS رو می‌گیریم، نه کل اینترنت (0.0.0.0/0).
            // این یعنی فقط پرس‌وجوهای DNS وارد تون می‌شن، بقیه‌ی ترافیک هر اپی
            // از مسیر عادی شبکه رد می‌شه و دست‌نخورده باقی می‌مونه.
            validDns.take(4).forEach { dns ->
                try {
                    addRoute(dns, 32)
                    addDnsServer(dns)
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
            isCancelled = false
            vpnThread = Thread { processPackets() }.apply { name = "VpnThread"; start() }
            statsUpdateHandler = Handler(Looper.getMainLooper())
            statsUpdateHandler?.post(object : Runnable {
                override fun run() {
                    if (!isCancelled) { updateNotification(); statsUpdateHandler?.postDelayed(this, 2000) }
                }
            })
            sendBroadcast(Intent("VPN_STARTED"))
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    // ------------------------------------------------------------
    // می‌خونه از tun، فقط پکت‌های UDP روی پورت 53 (DNS) رو تشخیص می‌ده
    // و برای relay واقعی می‌فرسته. بقیه‌ی پروتکل‌ها اصلاً به این تون
    // نمی‌رسن چون route فقط برای IP سرورهای DNS تعریف شده.
    // ------------------------------------------------------------
    private fun processPackets() {
        val vpnInterface = this.vpnInterface ?: return
        val input = FileInputStream(vpnInterface.fileDescriptor)
        val output = FileOutputStream(vpnInterface.fileDescriptor)
        val packet = ByteArray(32767)

        while (!isCancelled) {
            try {
                val length = input.read(packet)
                if (length <= 0) continue

                val data = packet.copyOf(length)
                if (isIpv4Udp53(data)) {
                    VpnStats.totalPacketsSent.incrementAndGet()
                    VpnStats.totalBytesSent.addAndGet(length.toLong())
                    relayExecutor.execute { relayDnsQuery(data, output) }
                } else {
                    // پروتکل‌های دیگه (که عملاً نباید زیاد پیش بیان چون route
                    // محدود به IP سرورهای DNS هست) رو نادیده می‌گیریم.
                    VpnStats.totalPacketsLost.incrementAndGet()
                }
            } catch (_: Exception) {
                if (!isCancelled) break
            }
        }
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    private fun relayDnsQuery(ipPacket: ByteArray, output: FileOutputStream) {
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

            val request = DatagramPacket(dnsPayload, dnsPayload.size, dstIp, 53)
            socket.send(request)

            val responseBuf = ByteArray(1500)
            val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(responsePacket)
            socket.close()

            val replyIpPacket = buildIpv4UdpPacket(
                srcIp = dstIp,           // پاسخ از طرف خودِ سرور DNS واقعی میاد
                dstIp = srcIp,           // به آدرس مجازی تون (10.8.0.1)
                srcPort = 53,
                dstPort = srcPort,
                payload = responsePacket.data.copyOfRange(0, responsePacket.length)
            )

            synchronized(output) {
                output.write(replyIpPacket)
                output.flush()
            }
            VpnStats.totalBytesReceived.addAndGet(replyIpPacket.size.toLong())
        } catch (_: Exception) {
            // تایم‌اوت یا خطای شبکه روی یک کوئری؛ صرفاً همون یک کوئری از دست می‌ره
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
        isCancelled = true
        VpnStats.isVpnActive = false
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        statsUpdateHandler?.removeCallbacksAndMessages(null)
        sendBroadcast(Intent("VPN_STOPPED"))
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        relayExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Kochak VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN Service"; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0,
            Intent(this, DnsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
