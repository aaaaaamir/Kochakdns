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

class MyVpnService : VpnService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_channel"
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_DNS_SERVERS = "dns_servers"
        const val EXTRA_DNS_NAME = "dns_name"
    }

    private var vpnThread: Thread? = null
    private var isCancelled = false
    private var vpnInterface: ParcelFileDescriptor? = null
    private var statsUpdateHandler: Handler? = null

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
        if (dnsServers.isEmpty()) { stopSelf(); return }
        startForeground(NOTIFICATION_ID, buildNotification("در حال اتصال به $dnsName..."))
        val builder = Builder().apply {
            addAddress("10.8.0.1", 32)
            addRoute("0.0.0.0", 0)
            dnsServers.take(4).forEach { dns -> try { addDnsServer(dns) } catch (_: Exception) {} }
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
        } catch (e: Exception) { e.printStackTrace(); stopSelf() }
    }

    private fun processPackets() {
        val vpnInterface = this.vpnInterface ?: return
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val packet = ByteArray(32767)
        while (!isCancelled) {
            try {
                val length = inputStream.read(packet)
                if (length > 0) {
                    VpnStats.totalPacketsSent.incrementAndGet()
                    VpnStats.totalBytesSent.addAndGet(length.toLong())
                    VpnStats.totalBytesReceived.addAndGet(length.toLong())
                }
            } catch (_: Exception) { if (!isCancelled) break }
        }
        try { inputStream.close() } catch (_: Exception) {}
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

    override fun onDestroy() { stopVpn(); super.onDestroy() }
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
