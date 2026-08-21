package com.example.kochakdns

import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * موتور NAT عمومی (نه فقط DNS): هر UDP flow و هر اتصال TCP رو بین رابط تون
 * و یک سوکت واقعی (protected شده تا وارد خودِ تون نشه) relay می‌کنه.
 *
 * چرا لازم شد: برای این‌که بشه هم‌زمان بعضی اپ‌ها اینترنت کامل داشته باشن و
 * بعضی دیگه کاملاً مسدود بشن، اندروید فقط یک جدول مسیریابی برای کل VPN قبول
 * می‌کنه — یعنی اگه بخوایم اپ‌های غیرمجاز رو مسدود کنیم، باید کل ترافیک
 * (نه فقط DNS) وارد تون بشه، و اون‌وقت برای اپ‌های مجاز باید واقعاً relay
 * کنیم وگرنه اینترنتشون هم قطع می‌شه.
 *
 * این پیاده‌سازی دست‌نویس و مخصوص همین برنامه‌ست (بدون کتابخونه‌ی خارجی مثل
 * tun2socks/Firestack)، چون اون‌ها API مستندسازی‌نشده و پیچیده‌ای دارن.
 * محدودیت صادقانه: این اولین نسخه‌ست، تست واقعی روی گوشی لازم داره — مسائل
 * ظریف TCP (رقابت، از دست رفتن پکت، شبکه‌های خیلی کند) ممکنه نیاز به تنظیم
 * finer داشته باشه.
 */
class TunnelEngine(
    private val vpnService: VpnService,
    private val output: FileOutputStream,
    private val scope: CoroutineScope,
    private val shouldForward: (uid: Int) -> Boolean
) {
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val ownershipDecisions = ConcurrentHashMap<String, Boolean>()
    private val outputMutex = Mutex()

    private suspend fun writePacket(packet: ByteArray) {
        outputMutex.withLock {
            try {
                output.write(packet)
                output.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        udpSessions.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        udpSessions.clear()
        tcpSessions.values.forEach { try { it.socket?.close() } catch (_: Exception) {} }
        tcpSessions.clear()
        ownershipDecisions.clear()
    }

    // ============================== UDP ==============================

    private class UdpSession(val socket: DatagramSocket, var lastActive: Long)

    fun handleUdpV4(data: ByteArray, ihl: Int) {
        try {
            val srcIp = InetAddress.getByAddress(data.copyOfRange(12, 16))
            val dstIp = InetAddress.getByAddress(data.copyOfRange(16, 20))
            val srcPort = ((data[ihl].toInt() and 0xFF) shl 8) or (data[ihl + 1].toInt() and 0xFF)
            val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
            val udpLen = ((data[ihl + 4].toInt() and 0xFF) shl 8) or (data[ihl + 5].toInt() and 0xFF)
            val payload = data.copyOfRange(ihl + 8, ihl + udpLen)

            val key = "v4:$srcIp:$srcPort:$dstIp:$dstPort"
            if (!isAllowedUdp(key, srcIp, srcPort, dstIp, dstPort)) return

            val session = udpSessions.getOrPut(key) {
                val socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 60_000
                val s = UdpSession(socket, System.currentTimeMillis())
                scope.launch { pumpUdpReplies(key, s, srcIp, srcPort, dstIp, dstPort, isV6 = false) }
                s
            }
            session.lastActive = System.currentTimeMillis()
            session.socket.send(DatagramPacket(payload, payload.size, dstIp, dstPort))
        } catch (_: Exception) {
        }
    }

    fun handleUdpV6(data: ByteArray) {
        try {
            val srcIp = InetAddress.getByAddress(data.copyOfRange(8, 24)) as Inet6Address
            val dstIp = InetAddress.getByAddress(data.copyOfRange(24, 40)) as Inet6Address
            val srcPort = ((data[40].toInt() and 0xFF) shl 8) or (data[41].toInt() and 0xFF)
            val dstPort = ((data[42].toInt() and 0xFF) shl 8) or (data[43].toInt() and 0xFF)
            val udpLen = ((data[44].toInt() and 0xFF) shl 8) or (data[45].toInt() and 0xFF)
            val payload = data.copyOfRange(48, 40 + udpLen)

            val key = "v6:$srcIp:$srcPort:$dstIp:$dstPort"
            if (!isAllowedUdp(key, srcIp, srcPort, dstIp, dstPort)) return

            val session = udpSessions.getOrPut(key) {
                val socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 60_000
                val s = UdpSession(socket, System.currentTimeMillis())
                scope.launch { pumpUdpReplies(key, s, srcIp, srcPort, dstIp, dstPort, isV6 = true) }
                s
            }
            session.lastActive = System.currentTimeMillis()
            session.socket.send(DatagramPacket(payload, payload.size, dstIp, dstPort))
        } catch (_: Exception) {
        }
    }

    private suspend fun pumpUdpReplies(
        key: String,
        session: UdpSession,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        isV6: Boolean
    ) {
        val buf = ByteArray(32767)
        while (true) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                session.socket.receive(packet)
                session.lastActive = System.currentTimeMillis()
                val payload = packet.data.copyOfRange(0, packet.length)
                val reply = if (isV6) {
                    buildUdpV6(dstIp as Inet6Address, srcIp as Inet6Address, dstPort, srcPort, payload)
                } else {
                    buildUdpV4(dstIp, srcIp, dstPort, srcPort, payload)
                }
                writePacket(reply)
            } catch (e: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - session.lastActive > 55_000) break
            } catch (_: Exception) {
                break
            }
        }
        udpSessions.remove(key)
        ownershipDecisions.remove(key)
        try { session.socket.close() } catch (_: Exception) {}
    }

    // ============================== TCP ==============================

    private enum class TcpState { SYN_RCVD, ESTABLISHED, CLOSING, CLOSED }

    private class TcpSession(
        val key: String,
        val srcIp: InetAddress, val srcPort: Int,
        val dstIp: InetAddress, val dstPort: Int,
        var socket: Socket? = null,
        var state: TcpState = TcpState.SYN_RCVD,
        var clientSeq: Long = 0,   // آخرین seq که از اپ دیدیم (برای ack کردن)
        var ourSeq: Long = 0,      // seq خودمون برای دیتایی که به اپ می‌فرستیم
        var job: Job? = null,
        var ownerPending: Boolean = false // مالکیت موقع SYN مشخص نشد؛ روی پکت بعدی دوباره چک می‌شود
    )

    fun handleTcpV4(data: ByteArray, ihl: Int) {
        try {
            val srcIp = InetAddress.getByAddress(data.copyOfRange(12, 16))
            val dstIp = InetAddress.getByAddress(data.copyOfRange(16, 20))
            handleTcp(data, ihl, isV6 = false, srcIp = srcIp, dstIp = dstIp)
        } catch (_: Exception) {
        }
    }

    fun handleTcpV6(data: ByteArray) {
        try {
            val srcIp = InetAddress.getByAddress(data.copyOfRange(8, 24))
            val dstIp = InetAddress.getByAddress(data.copyOfRange(24, 40))
            handleTcp(data, 40, isV6 = true, srcIp = srcIp, dstIp = dstIp)
        } catch (_: Exception) {
        }
    }

    private fun handleTcp(data: ByteArray, headerOffset: Int, isV6: Boolean, srcIp: InetAddress, dstIp: InetAddress) {
        val srcPort = ((data[headerOffset].toInt() and 0xFF) shl 8) or (data[headerOffset + 1].toInt() and 0xFF)
        val dstPort = ((data[headerOffset + 2].toInt() and 0xFF) shl 8) or (data[headerOffset + 3].toInt() and 0xFF)
        val seq = readUInt32(data, headerOffset + 4)
        val ack = readUInt32(data, headerOffset + 8)
        val dataOffsetBytes = ((data[headerOffset + 12].toInt() and 0xFF) ushr 4) * 4
        val flags = data[headerOffset + 13].toInt() and 0xFF
        val isSyn = flags and 0x02 != 0
        val isAck = flags and 0x10 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0
        val payloadStart = headerOffset + dataOffsetBytes
        val payload = if (payloadStart < data.size) data.copyOfRange(payloadStart, data.size) else ByteArray(0)

        val key = "${if (isV6) "v6" else "v4"}:$srcIp:$srcPort:$dstIp:$dstPort"

        if (isRst) {
            tcpSessions.remove(key)?.let { try { it.socket?.close() } catch (_: Exception) {} ; it.job?.cancel() }
            return
        }

        if (isSyn && !isAck) {
            // SYN تکراری (retransmit): سشن همین اتصال از قبل ساخته شده؛ نباید
            // سشن دومی بسازیم (که باعث ارسال دو SYN-ACK با شماره‌های دنباله‌ی
            // متفاوت و به هم ریختن اتصال می‌شد).
            if (tcpSessions.containsKey(key)) return

            // مالکیت فقط یک‌بار اینجا چک می‌شود؛ اگر هنوز نامشخص باشد،
            // fail-open عمل می‌کنیم و روی پکت بعدی (ACK/دیتا) دوباره چک می‌کنیم.
            val decision = ownerAllowed(srcIp, srcPort, dstIp, dstPort, isUdp = false)
            if (decision == false) return // مالک شناسایی شد و مجاز نیست → مسدود

            // شروع یک اتصال جدید
            val session = TcpSession(key, srcIp, srcPort, dstIp, dstPort, clientSeq = seq + 1, ourSeq = (100000..900000).random().toLong())
            session.ownerPending = decision == null
            tcpSessions[key] = session
            session.job = scope.launch {
                try {
                    val socket = Socket()
                    vpnService.protect(socket)
                    socket.connect(InetSocketAddress(dstIp, dstPort), 8000)
                    session.socket = socket
                    session.state = TcpState.ESTABLISHED
                    // SYN-ACK بفرست
                    writePacket(buildTcpPacket(session, flags = 0x12 /*SYN+ACK*/, payload = ByteArray(0)))
                    session.ourSeq += 1
                    pumpTcpFromSocket(session, isV6)
                } catch (_: Exception) {
                    writePacket(buildTcpPacket(session, flags = 0x14 /*RST+ACK*/, payload = ByteArray(0)))
                    tcpSessions.remove(key)
                }
            }
            return
        }

        // اگه اینجا رسیدیم و هیچ سشنی برای این key نیست، یعنی یا مسدود بوده
        // (SYNـش رد شده) یا اتصالی در جریان نبوده؛ در هر دو حالت نادیده می‌گیریم.
        val session = tcpSessions[key] ?: return

        // اگر مالکیت موقع SYN مشخص نشده بود، اینجا (اولین پکت بعدی) دوباره
        // چک می‌کنیم؛ جدول conntrack معمولاً تا این لحظه آماده است.
        if (session.ownerPending) {
            session.ownerPending = false
            val decision = ownerAllowed(srcIp, srcPort, dstIp, dstPort, isUdp = false)
            if (decision == false) {
                tcpSessions.remove(key)
                try { session.socket?.close() } catch (_: Exception) {}
                session.job?.cancel()
                return
            }
        }

        if (isFin) {
            session.clientSeq = seq + 1
            try { session.socket?.shutdownOutput() } catch (_: Exception) {}
            scope.launch { writePacket(buildTcpPacket(session, flags = 0x10 /*ACK*/, payload = ByteArray(0))) }
            return
        }

        if (payload.isNotEmpty()) {
            try {
                session.socket?.getOutputStream()?.write(payload)
                session.socket?.getOutputStream()?.flush()
            } catch (_: Exception) {
            }
            session.clientSeq = seq + payload.size
            scope.launch { writePacket(buildTcpPacket(session, flags = 0x10 /*ACK*/, payload = ByteArray(0))) }
        }
    }

    private suspend fun pumpTcpFromSocket(session: TcpSession, isV6: Boolean) {
        val socket = session.socket ?: return
        val buf = ByteArray(16384)
        try {
            val input = socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                writePacket(buildTcpPacket(session, flags = 0x18 /*PSH+ACK*/, payload = chunk))
                session.ourSeq += n
            }
        } catch (_: Exception) {
        } finally {
            // اتصال واقعی تموم شد؛ به اپ FIN بفرست
            try { writePacket(buildTcpPacket(session, flags = 0x11 /*FIN+ACK*/, payload = ByteArray(0))) } catch (_: Exception) {}
            session.ourSeq += 1
            tcpSessions.remove(session.key)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun buildTcpPacket(session: TcpSession, flags: Int, payload: ByteArray): ByteArray {
        return if (session.dstIp is Inet6Address) {
            buildTcpV6(
                session.dstIp, session.srcIp as Inet6Address,
                session.dstPort, session.srcPort,
                session.ourSeq, session.clientSeq, flags, payload
            )
        } else {
            buildTcpV4(
                session.dstIp, session.srcIp,
                session.dstPort, session.srcPort,
                session.ourSeq, session.clientSeq, flags, payload
            )
        }
    }

    /** پاکسازی نشست‌های TCP بی‌کار (وقتی هیچ داده‌ای رد و بدل نمی‌شه). */
    fun cleanupIdleSessions() {
        // نشست‌های TCP خودشون با بسته شدن سوکت واقعی پاک می‌شن (pumpTcpFromSocket)؛
        // اینجا فقط برای اطمینان یک sweep سبک روی UDP انجام می‌دیم.
        val now = System.currentTimeMillis()
        udpSessions.entries.removeAll { (_, s) -> now - s.lastActive > 65_000 }
    }

    // ============================== ownership check ==============================

    /**
     * تعیین می‌کند آیا مالک این flow (بر اساس UID) اجازه‌ی عبور دارد یا نه.
     *
     * نکته‌ی مهم (رفع باگ «مسدود شدن اینترنت همه»): قبلاً fail-closed بود —
     * یعنی هر جا getConnectionOwnerUid برمی‌گشت INVALID_UID (-1) یا استثنا
     * پرتاب می‌کرد، پکت «مسدود» حساب می‌شد. اما طبق مستندات اندروید، این API
     * روی بعضی دستگاه‌ها/پروتکل‌ها (مخصوصاً UDP و حتی لحظه‌ی اولین SYN) موقتاً
     * نامشخص برمی‌گرداند؛ نتیجه این بود که با فعال شدن حالت مسدودسازی، اینترنتِ
     * «همه»ی برنامه‌ها (حتی انتخاب‌شده‌ها) قطع می‌شد. حالا fail-open است:
     * وقتی مالکیت مشخص نشود، پکت رد نمی‌شود (اجازه داده می‌شود) و روی پکت بعدی
     * دوباره چک می‌کنیم تا به محض مشخص شدن، مسدودسازی واقعی اعمال شود.
     */

    /** تصمیم قطعی مالکیت هر flow؛ فقط وقتی جواب قطعی داریم کش می‌شود. */
    private fun ownerAllowed(srcIp: InetAddress, srcPort: Int, dstIp: InetAddress, dstPort: Int, isUdp: Boolean): Boolean? {
        if (android.os.Build.VERSION.SDK_INT < 29) return true // بدون API تشخیص؛ fail-open تا اینترنت کسی بی‌دلیل قطع نشود
        return try {
            val cm = vpnService.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val proto = if (isUdp) android.system.OsConstants.IPPROTO_UDP else android.system.OsConstants.IPPROTO_TCP
            val uid = cm.getConnectionOwnerUid(proto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(dstIp, dstPort))
            if (uid <= 0) null else shouldForward(uid) // null یعنی هنوز مالکیت مشخص نیست
        } catch (_: Exception) {
            null
        }
    }

    /** نسخه‌ی UDP: تا وقتی تصمیم قطعی نگرفته‌ایم، روی هر پکت دوباره می‌پرسیم. */
    private fun isAllowedUdp(key: String, srcIp: InetAddress, srcPort: Int, dstIp: InetAddress, dstPort: Int): Boolean {
        ownershipDecisions[key]?.let { return it }
        return when (val decision = ownerAllowed(srcIp, srcPort, dstIp, dstPort, isUdp = true)) {
            null -> true // نامشخص → fail-open؛ پکت بعدی دوباره چک می‌شود
            else -> {
                ownershipDecisions[key] = decision
                decision
            }
        }
    }

    // ============================== packet builders ==============================

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt32(packet: ByteArray, offset: Int, value: Long) {
        packet[offset] = ((value shr 24) and 0xFF).toByte()
        packet[offset + 1] = ((value shr 16) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 3] = (value and 0xFF).toByte()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun buildUdpV4(srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45; packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte(); packet[3] = (totalLength and 0xFF).toByte()
        packet[6] = 0x40.toByte(); packet[8] = 64; packet[9] = 17
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)
        val ipChecksum = checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte(); packet[11] = (ipChecksum and 0xFF).toByte()
        packet[20] = ((srcPort shr 8) and 0xFF).toByte(); packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte(); packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLength shr 8) and 0xFF).toByte(); packet[25] = (udpLength and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun buildUdpV6(srcIp: Inet6Address, dstIp: Inet6Address, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60; packet[4] = ((udpLength shr 8) and 0xFF).toByte(); packet[5] = (udpLength and 0xFF).toByte()
        packet[6] = 17; packet[7] = 64
        System.arraycopy(srcIp.address, 0, packet, 8, 16)
        System.arraycopy(dstIp.address, 0, packet, 24, 16)
        val udpOffset = 40
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte(); packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte(); packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLength shr 8) and 0xFF).toByte(); packet[udpOffset + 5] = (udpLength and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)
        val pseudo = ByteArray(40 + udpLength)
        System.arraycopy(srcIp.address, 0, pseudo, 0, 16)
        System.arraycopy(dstIp.address, 0, pseudo, 16, 16)
        pseudo[34] = ((udpLength shr 8) and 0xFF).toByte(); pseudo[35] = (udpLength and 0xFF).toByte()
        pseudo[39] = 17
        System.arraycopy(packet, udpOffset, pseudo, 40, udpLength)
        var cs = checksum(pseudo, 0, pseudo.size)
        if (cs == 0) cs = 0xFFFF
        packet[udpOffset + 6] = ((cs shr 8) and 0xFF).toByte(); packet[udpOffset + 7] = (cs and 0xFF).toByte()
        return packet
    }

    private fun buildTcpV4(srcIp: InetAddress, dstIp: InetAddress, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, payload: ByteArray): ByteArray {
        val tcpLength = 20 + payload.size
        val totalLength = 20 + tcpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45; packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte(); packet[3] = (totalLength and 0xFF).toByte()
        packet[6] = 0x40.toByte(); packet[8] = 64; packet[9] = 6
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)
        val ipChecksum = checksum(packet, 0, 20)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte(); packet[11] = (ipChecksum and 0xFF).toByte()

        val t = 20
        packet[t] = ((srcPort shr 8) and 0xFF).toByte(); packet[t + 1] = (srcPort and 0xFF).toByte()
        packet[t + 2] = ((dstPort shr 8) and 0xFF).toByte(); packet[t + 3] = (dstPort and 0xFF).toByte()
        writeUInt32(packet, t + 4, seq)
        writeUInt32(packet, t + 8, ack)
        packet[t + 12] = (5 shl 4).toByte() // data offset = 5 (20 بایت، بدون options)
        packet[t + 13] = flags.toByte()
        packet[t + 14] = 0xFF.toByte(); packet[t + 15] = 0xFF.toByte() // window size
        System.arraycopy(payload, 0, packet, t + 20, payload.size)

        val pseudo = ByteArray(12 + tcpLength)
        System.arraycopy(srcIp.address, 0, pseudo, 0, 4)
        System.arraycopy(dstIp.address, 0, pseudo, 4, 4)
        pseudo[9] = 6
        pseudo[10] = ((tcpLength shr 8) and 0xFF).toByte(); pseudo[11] = (tcpLength and 0xFF).toByte()
        System.arraycopy(packet, t, pseudo, 12, tcpLength)
        val tcpChecksum = checksum(pseudo, 0, pseudo.size)
        packet[t + 16] = ((tcpChecksum shr 8) and 0xFF).toByte(); packet[t + 17] = (tcpChecksum and 0xFF).toByte()
        return packet
    }

    private fun buildTcpV6(srcIp: Inet6Address, dstIp: Inet6Address, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, payload: ByteArray): ByteArray {
        val tcpLength = 20 + payload.size
        val packet = ByteArray(40 + tcpLength)
        packet[0] = 0x60
        packet[4] = ((tcpLength shr 8) and 0xFF).toByte(); packet[5] = (tcpLength and 0xFF).toByte()
        packet[6] = 6; packet[7] = 64
        System.arraycopy(srcIp.address, 0, packet, 8, 16)
        System.arraycopy(dstIp.address, 0, packet, 24, 16)

        val t = 40
        packet[t] = ((srcPort shr 8) and 0xFF).toByte(); packet[t + 1] = (srcPort and 0xFF).toByte()
        packet[t + 2] = ((dstPort shr 8) and 0xFF).toByte(); packet[t + 3] = (dstPort and 0xFF).toByte()
        writeUInt32(packet, t + 4, seq)
        writeUInt32(packet, t + 8, ack)
        packet[t + 12] = (5 shl 4).toByte()
        packet[t + 13] = flags.toByte()
        packet[t + 14] = 0xFF.toByte(); packet[t + 15] = 0xFF.toByte()
        System.arraycopy(payload, 0, packet, t + 20, payload.size)

        val pseudo = ByteArray(40 + tcpLength)
        System.arraycopy(srcIp.address, 0, pseudo, 0, 16)
        System.arraycopy(dstIp.address, 0, pseudo, 16, 16)
        pseudo[34] = ((tcpLength shr 8) and 0xFF).toByte(); pseudo[35] = (tcpLength and 0xFF).toByte()
        pseudo[39] = 6
        System.arraycopy(packet, t, pseudo, 40, tcpLength)
        var cs = checksum(pseudo, 0, pseudo.size)
        if (cs == 0) cs = 0xFFFF
        packet[t + 16] = ((cs shr 8) and 0xFF).toByte(); packet[t + 17] = (cs and 0xFF).toByte()
        return packet
    }
}
