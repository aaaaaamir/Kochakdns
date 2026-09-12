package com.example.kochakdns

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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val outputMutex = Mutex()


    // ===== همزمانی جداگانه‌ی UDP و TCP =====
    // تعداد پرس‌وجوهای همزمان هر پروتکل مستقل است و از تنظیمات خوانده می‌شود.
    // با withPermit (نه tryAcquire) صف می‌شوند تا هیچ کوئری‌ای بی‌دلیل «گم‌شده»
    // حساب نشود.
    private var udpPermits = Semaphore(8)
    private var tcpPermits = Semaphore(4)


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
    // حالت فقط TCP: همه‌ی درخواست‌ها مستقیم با TCP ارسال می‌شوند (بدون UDP)
    private var tcpOnlyEnabled = false


    // تایم‌اوت تطبیقیِ قابل تنظیم از تنظیمات: نقطه‌ی شروع (و سقف) + کف.
    // پیش‌فرض‌ها دقیقاً رفتارِ پایدارِ قبلی هستند (شروع ۵ ثانیه، کف ۲ ثانیه).

