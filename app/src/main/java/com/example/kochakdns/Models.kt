package com.example.kochakdns

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * لایه‌ی دسترسی ثابت به بک‌اند — خودِ مقدارها (دامنه، مسیرها، کلید رمزنگاری)
 * در `ApiConfig.kt` هستند که از گیت بیرون نگه داشته می‌شود.
 * بقیه‌ی کد فقط با همین نام‌ها کار می‌کند تا اگر روزی ساختار عوض شد، یک فایل عوض شود.
 */
object AppConfig {
    const val BASE_URL = ApiConfig.BASE_URL

    // مسیرهای API (بک‌اند Cloudflare Worker)
    const val API_DNS_LIST = ApiConfig.API_DNS_LIST
    const val API_DNS_PROFILE = ApiConfig.API_DNS_PROFILE
    const val API_DNS_STATS = ApiConfig.API_DNS_STATS
    const val API_APP_INFO = ApiConfig.API_APP_INFO
    const val API_APP_DOWNLOAD = ApiConfig.API_APP_DOWNLOAD
    const val API_ANNOUNCEMENT = ApiConfig.API_ANNOUNCEMENT
    const val USER_AGENT = ApiConfig.USER_AGENT
    const val SITE_URL = ApiConfig.SITE_URL
    const val SUPPORT_URL = ApiConfig.SUPPORT_URL
}

data class DnsServer(
    val role: String,
    val priority: Int,
    val family: String,
    val address: String
)

data class DnsProfile(
    val name: String,
    val enabled: Boolean,
    val ipv4Primary: String?,
    val ipv6Primary: String?,
    val ipv4Secondary: String?,
    val ipv6Secondary: String?,
    val servers: List<DnsServer>,
    val updatedAt: String?,
    val isActive: Boolean = false
) {
    fun toJson(): JSONObject {
        val serversArray = JSONArray()
        servers.forEach { s ->
            serversArray.put(JSONObject().apply {
                put("role", s.role)
                put("priority", s.priority)
                put("family", s.family)
                put("address", s.address)
            })
        }
        return JSONObject().apply {
            put("name", name)
            put("enabled", enabled)
            put("ipv4Primary", ipv4Primary ?: JSONObject.NULL)
            put("ipv6Primary", ipv6Primary ?: JSONObject.NULL)
            put("ipv4Secondary", ipv4Secondary ?: JSONObject.NULL)
            put("ipv6Secondary", ipv6Secondary ?: JSONObject.NULL)
            put("updatedAt", updatedAt ?: JSONObject.NULL)
            put("isActive", isActive)
            put("servers", serversArray)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): DnsProfile {
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
            return DnsProfile(
                name = obj.optString("name"),
                enabled = obj.optBoolean("enabled"),
                ipv4Primary = obj.optString("ipv4Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Primary = obj.optString("ipv6Primary").takeIf { it.isNotEmpty() && it != "null" },
                ipv4Secondary = obj.optString("ipv4Secondary").takeIf { it.isNotEmpty() && it != "null" },
                ipv6Secondary = obj.optString("ipv6Secondary").takeIf { it.isNotEmpty() && it != "null" },
                servers = servers,
                updatedAt = obj.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" },
                isActive = obj.optBoolean("isActive", false)
            )
        }

        fun fromJson(json: String): DnsProfile = fromJson(JSONObject(json))

        // --- کمکی برای سریالایز/دیسریالایز لیست کامل پروفایل‌ها ---
        fun listToJson(profiles: List<DnsProfile>): String {
            val arr = JSONArray()
            profiles.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String): List<DnsProfile> {
            val arr = JSONArray(json)
            val result = mutableListOf<DnsProfile>()
            for (i in 0 until arr.length()) {
                result.add(fromJson(arr.getJSONObject(i)))
            }
            return result
        }
    }
}

/** آمار یک اوپراتور خاص برای یک DNS (از endpoint آمار). */
data class OperatorStat(
    val operator: String,
    val packetsSent: Long,
    val packetsLost: Long
) {
    val total: Long get() = packetsSent + packetsLost

    /** درصد موفقیت این اوپراتور؛ null یعنی هنوز آماری نیست. */
    val successPercent: Double?
        get() = if (total > 0) (packetsSent * 100.0 / total) else null
}

data class DnsItem(
    val name: String,
    val servers: List<DnsServer>,
    val ping: Long = -1,
    val previousPing: Long = -1,
    // آخرین نمونه‌ی پینگ تایم‌اوت رفته بود؟ (برای نمایش «تایم‌اوت» در لیست)
    val timedOut: Boolean = false,
    // آماری که از سرور (endpoint آمار) خونده می‌شه؛ مجموع پکت‌های
    // ارسالی/گم‌شده‌ای که قبلاً وقتی این پروفایل وصل بوده، ثبت شده.
    val statsPacketsSent: Long = 0,
    val statsPacketsLost: Long = 0,
    // آمار به تفکیک اوپراتور (از بهترین به بدترین مرتب می‌شود)
    val operatorStats: List<OperatorStat> = emptyList()
) {
    val jitter: Long
        get() = if (ping > 0 && previousPing > 0) abs(ping - previousPing) else 0

    val statsTotal: Long
        get() = statsPacketsSent + statsPacketsLost

    /** درصد موفقیت (چند درصد از کل پکت‌ها واقعاً ارسال شدن، نه گم شدن). null یعنی هنوز آماری نیست. */
    val successPercent: Double?
        get() = if (statsTotal > 0) (statsPacketsSent * 100.0 / statsTotal) else null

    /** بهترین اوپراتور (بیشترین درصد موفقیت). null یعنی هنوز آماری نیست. */
    val bestOperator: OperatorStat?
        get() = operatorStats.filter { it.successPercent != null }
            .maxByOrNull { it.successPercent!! }
}

object VpnStats {
    val totalBytesSent = AtomicLong(0)
    val totalBytesReceived = AtomicLong(0)
    val totalPacketsSent = AtomicLong(0)
    val totalPacketsLost = AtomicLong(0)
    // پکت‌هایی که عمداً به‌دلیل «مسدودسازی» دور ریخته می‌شوند؛ این‌ها گم‌شده
    // نیستند و نه در UI به‌عنوان گم‌شده نمایش داده می‌شوند نه به سرور ارسال می‌شوند.
    val totalPacketsBlocked = AtomicLong(0)
    /**
     * کوئری‌هایی که به‌دلیل محدودساز سرعت دور ریخته شدند (منتظر نوبت بیش از
     * تایم‌اوت ماندن). عمداً در sent/lost حساب نمی‌شوند — ارسال نشدند، پس «گم‌شده»
     * نیستند؛ ریزالور اندروید خودش بعد از مهلتش ریترای می‌زند.
     */
    val totalQueriesThrottled = AtomicLong(0)

    // آمار کش DNS: تعداد پاسخ‌هایی که از کش سرو شده‌اند و تعداد پرس‌وجوهایی که
    // در کش نبوده‌اند. با هم نرخ «پاسخ از کش» را می‌سازند (نمایش در تنظیمات).
    val dnsCacheHits = AtomicLong(0)
    val dnsCacheMisses = AtomicLong(0)

    // تخمین زمان صرفه‌جویی‌شده از پاسخ‌های کش (مجموع RTTهایی که دور زده شده‌اند).
    val dnsCacheSavedMs = AtomicLong(0)

    /** یک رکورد از لاگ پاسخ‌های سرو‌شده از کش — فقط نام دامنه (بدون آدرس/کد سرور). */
    data class CacheLogEntry(val domain: String, val servedAt: Long)

    /** آخرین پاسخ‌های سرو‌شده از کش (جدیدترین در انتهای صف). */
    val dnsCacheLog = ConcurrentLinkedQueue<CacheLogEntry>()

    @Volatile
    var isVpnActive = false

    @Volatile
    var activeDnsName: String? = null

    /** درصد پاسخ‌هایی که از کش سرو شده‌اند؛ null یعنی هنوز هیچ پرس‌وجویی نشده. */
    fun dnsCacheHitRate(): Double? {
        val hits = dnsCacheHits.get()
        val total = hits + dnsCacheMisses.get()
        return if (total > 0) hits * 100.0 / total else null
    }
}

/**
 * چک‌پوینت پیوسته‌ی آمار روی دیسک، برای وقتی که برنامه force-stop می‌شه و
 * اصلاً فرصت اجرای هیچ کدی (نه onDestroy نه هیچ callback دیگه) پیش نمیاد.
 * MyVpnService هر چند ثانیه یک‌بار همین‌جا ذخیره می‌کنه؛ دفعه‌ی بعد که اپ
 * باز می‌شه (از MainActivity)، اگه رکورد ارسال‌نشده‌ای مونده باشه، همون‌جا
 * (با همون قانون حداقل ۳۰ ثانیه) فرستاده و پاک می‌شه.
 */
object PendingStatsStore {
    private const val PREFS = "vpn_pending_stats"
    private const val KEY_PROFILE = "profile_name"
    private const val KEY_SENT = "packets_sent"
    private const val KEY_LOST = "packets_lost"
    private const val KEY_START = "connect_start"
    private const val KEY_CHECKPOINT = "last_checkpoint"
    private const val KEY_OPERATOR = "operator"

    fun save(context: android.content.Context, profileName: String, sent: Long, lost: Long, connectStart: Long, operator: String) {
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, profileName)
                .putLong(KEY_SENT, sent)
                .putLong(KEY_LOST, lost)
                .putLong(KEY_START, connectStart)
                .putLong(KEY_CHECKPOINT, System.currentTimeMillis())
                .putString(KEY_OPERATOR, operator)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clear(context: android.content.Context) {
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {
        }
    }

    data class Pending(val profileName: String, val sent: Long, val lost: Long, val durationMs: Long, val operator: String)

    fun read(context: android.content.Context): Pending? {
        return try {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val profile = prefs.getString(KEY_PROFILE, null) ?: return null
            val start = prefs.getLong(KEY_START, 0)
            val checkpoint = prefs.getLong(KEY_CHECKPOINT, 0)
            if (start <= 0 || checkpoint <= 0) return null
            Pending(
                profileName = profile,
                sent = prefs.getLong(KEY_SENT, 0),
                lost = prefs.getLong(KEY_LOST, 0),
                durationMs = checkpoint - start,
                operator = prefs.getString(KEY_OPERATOR, null) ?: "unknown"
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** اسم اپراتور فعلی (یا "wifi" اگه روی وای‌فای باشه)؛ نیاز به هیچ مجوز خطرناکی نداره. */
fun getOperatorInfo(context: android.content.Context): String {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        when {
            caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> {
                val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                val name = tm.networkOperatorName
                if (name.isNullOrBlank()) "mobile" else name
            }
        }
    } catch (e: Exception) {
        "unknown"
    }
}

/** منطق مشترک ارسال آمار به سرور، هم از MyVpnService (قطع عادی) هم از MainActivity (فلاش کردن رکورد جامونده) استفاده می‌شه. */
object StatsReporter {
    private val client: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

    /** blocking است؛ حتماً از یک ترد پس‌زمینه صدا زده بشه. */
    fun send(profileName: String, sent: Long, lost: Long, operator: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("profile_name", profileName)
                put("packets_sent", sent)
                put("packets_lost", lost)
                put("operator", operator)
            }
            val body = json.toString()
                .toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder()
                .url(AppConfig.BASE_URL + AppConfig.API_DNS_STATS)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}

/** تنظیمات سراسری برنامه (صفحه‌ی تنظیمات). پیش‌فرض‌ها دقیقاً همون رفتار فعلی برنامه‌ست. */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_DNS_CACHE = "dns_cache"
    private const val KEY_SHOW_PACKET_PERCENT = "show_packet_percentage"
    private const val KEY_SHOW_NOTIFICATION = "show_notification"
    private const val KEY_UPDATE_CHECK = "update_check_enabled"
    private const val KEY_ANNOUNCEMENT = "announcement_enabled"
    private const val KEY_QS_TILE = "qs_tile_enabled"
    private const val KEY_QS_SUGGESTION = "qs_tile_suggestion_shown"
    // ---- محدودساز سرعت ارسال کوئری (رله‌ی DNS) ----
    private const val KEY_RATE_LIMIT = "query_rate_limit_enabled"
    private const val KEY_RATE_COUNT = "query_rate_limit_count"
    private const val KEY_RATE_WINDOW = "query_rate_limit_window_sec"
    private const val KEY_RATE_TIMEOUT = "query_rate_limit_timeout_sec"
    // پذیرش موافقت‌نامه حریم خصوصی/شرایط — بار اول روی اسپلش پرسیده می‌شود.
    private const val KEY_CONSENT = "terms_consent_v1"
    private const val KEY_TCP_FALLBACK = "tcp_fallback"
    private const val KEY_TCP_ONLY = "tcp_only"
    private const val KEY_UDP_CONCURRENT = "udp_concurrent"
    private const val KEY_TCP_CONCURRENT = "tcp_concurrent"
    private const val KEY_TIMEOUT_START = "timeout_start_ms"
    private const val KEY_TIMEOUT_FLOOR = "timeout_floor_ms"
    private const val KEY_ADAPTIVE_TIMEOUT = "adaptive_timeout"
    private const val KEY_FIXED_TIMEOUT = "fixed_timeout_ms"
    // روش پینگ‌گیری + آدرس دستی
    private const val KEY_PING_MODE = "ping_mode"
    private const val KEY_PING_MANUAL_URL = "ping_manual_url"
    private const val KEY_LANGUAGE = "app_language"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    // پیش‌فرض true: کش DNS فعال است. پاسخ‌های تکراری DNS از حافظه خوانده می‌شوند
    // (با TTL واقعی رکورد) که باعث بهبود پینگ و سرعت می‌شود.
    fun isDnsCacheEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_DNS_CACHE, true)

    fun setDnsCacheEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DNS_CACHE, value).apply()
    }

    // پیش‌فرض true: بررسی خودکار بروزرسانی هنگام ورود به برنامه انجام می‌شود.
    fun isUpdateCheckEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_UPDATE_CHECK, true)

    fun setUpdateCheckEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_UPDATE_CHECK, value).apply()
    }

    // پیش‌فرض true: اطلاعیه‌های داخل برنامه (از ربات) نمایش داده می‌شوند.
    fun isAnnouncementEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_ANNOUNCEMENT, true)

    fun setAnnouncementEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCEMENT, value).apply()
    }

    // پیش‌فرض true: الان درصد پکت‌ها نمایش داده می‌شه.
    fun isShowPacketPercentEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_PACKET_PERCENT, true)

    fun setShowPacketPercentEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_PACKET_PERCENT, value).apply()
    }

    // پیش‌فرض true: اطلاعات پکت‌ها و حجم دیتای منتقل‌شده در نوتیفیکیشن VPN
    // نمایش داده می‌شود. وقتی خاموش باشد، نوتیفیکیشن همچنان (طبق الزام اندروید)
    // وجود دارد ولی فقط یک متن ساده (بدون آمار) نشان می‌دهد.
    fun isShowNotificationInfoEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_NOTIFICATION, true)

    fun setShowNotificationInfoEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_NOTIFICATION, value).apply()
    }

    // پیش‌فرض false: کاشی دسترسی سریع (Quick Settings) خاموش است؛ کاربر از
    // تنظیمات فعالش می‌کند یا از پیشنهاد یک‌باره‌ی داخل برنامه.
    fun isQsTileEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_QS_TILE, false)

    fun setQsTileEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_QS_TILE, value).apply()
    }

    // پیشنهاد یک‌باره‌ی افزودن کاشی: بعد از اولین اتصال فقط یک بار نمایش داده می‌شود.
    fun isQsTileSuggestionShown(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_QS_SUGGESTION, false)

    fun markQsTileSuggestionShown(context: android.content.Context) {
        prefs(context).edit().putBoolean(KEY_QS_SUGGESTION, true).apply()
    }

    // بدون پذیرش موافقت‌نامه: نه وارد برنامه می‌شویم، نه آماری ارسال می‌کنیم.
    fun isConsentAgreed(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun markConsentAgreed(context: android.content.Context) {
        prefs(context).edit().putBoolean(KEY_CONSENT, true).apply()
    }

    // پیش‌فرض true: اگر پرس‌وجوی UDP بی‌پاسخ بماند، همان پرس‌وجو با TCP (پورت ۵۳)
    // دوباره امتحان می‌شود — دور زدن ISPهایی که UDP/53 را مختل می‌کنند.
    fun isTcpFallbackEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_TCP_FALLBACK, true)

    fun setTcpFallbackEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TCP_FALLBACK, value).apply()
    }

    // حالت فقط TCP: همه‌ی درخواست‌های DNS مستقیماً با TCP ارسال می‌شوند (بدون UDP).
    // برای شبکه‌هایی که UDP/53 کاملاً مسدود است. پیش‌فرض خاموش.
    fun isTcpOnlyEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_TCP_ONLY, false)

    fun setTcpOnlyEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TCP_ONLY, value).apply()
    }

    // حداکثر پرس‌وجوی هم‌زمان UDP به سمت سرور (پیش‌فرض ۸؛ بدون سقف از تنظیمات).
    fun getUdpConcurrent(context: android.content.Context): Int =
        prefs(context).getInt(KEY_UDP_CONCURRENT, 8).coerceAtLeast(1)

    fun setUdpConcurrent(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_UDP_CONCURRENT, value.coerceAtLeast(1)).apply()
    }

    // حداکثر پرس‌وجوی هم‌زمان TCP به سمت سرور (پیش‌فرض ۴؛ بدون سقف از تنظیمات).
    fun getTcpConcurrent(context: android.content.Context): Int =
        prefs(context).getInt(KEY_TCP_CONCURRENT, 4).coerceAtLeast(1)

    fun setTcpConcurrent(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_TCP_CONCURRENT, value.coerceAtLeast(1)).apply()
    }

    // نقطه‌ی شروع (و سقف) تایم‌اوت تطبیقی هر درخواست، به میلی‌ثانیه.
    // پیش‌فرض ۸۰۰۰ms؛ بدون سقف (فقط باید مثبت باشد).
    fun getTimeoutStartMs(context: android.content.Context): Int =
        prefs(context).getInt(KEY_TIMEOUT_START, 8000).coerceAtLeast(1)

    fun setTimeoutStartMs(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_TIMEOUT_START, value.coerceAtLeast(1)).apply()
    }

    // کفِ تایم‌اوت تطبیقی: تایم‌اوت سرورهای سریع هرگز از این کمتر نمی‌شود.
    // همیشه بین ۱ و «نقطه شروع» نگه داشته می‌شود تا رابطه‌ی کف ≤ سقف برقرار بماند.
    fun getTimeoutFloorMs(context: android.content.Context): Int {
        val start = getTimeoutStartMs(context)
        return prefs(context).getInt(KEY_TIMEOUT_FLOOR, 2000).coerceIn(1, start)
    }

    fun setTimeoutFloorMs(context: android.content.Context, value: Int) {
        val start = getTimeoutStartMs(context)
        prefs(context).edit().putInt(KEY_TIMEOUT_FLOOR, value.coerceIn(1, start)).apply()
    }

    // تایم‌اوت تطبیقی: روشن = تایم‌اوت با سرعت سرور تنظیم می‌شود (پیش‌فرض).
    // خاموش = از تایم‌اوت ثابت (getFixedTimeoutMs) استفاده می‌شود.
    fun isAdaptiveTimeoutEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_ADAPTIVE_TIMEOUT, true)

    fun setAdaptiveTimeoutEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADAPTIVE_TIMEOUT, value).apply()
    }

    // تایم‌اوت ثابت (وقتی تایم‌اوت تطبیقی خاموش باشد). پیش‌فرض ۵۰۰۰ms؛ بدون سقف.
    fun getFixedTimeoutMs(context: android.content.Context): Int =
        prefs(context).getInt(KEY_FIXED_TIMEOUT, 5000).coerceAtLeast(1)

    fun setFixedTimeoutMs(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_FIXED_TIMEOUT, value.coerceAtLeast(1)).apply()
    }

    // ---- روش پینگ‌گیری (تنظیمات → نحوه پینگ) ----
    const val PING_MODE_DNS = "dns"          // پیش‌فرض: پرس‌وجوی DNS به سرور خودِ پروفایل (UDP/TCP:53)
    const val PING_MODE_PUBG = "pubg"        // https://pubgmobile.com
    const val PING_MODE_GOOGLE = "google"    // https://www.google.com/generate_204
    const val PING_MODE_MANUAL = "manual"    // آدرس دلخواه کاربر (حتماً https:// معتبر)

    fun getPingMode(context: android.content.Context): String =
        prefs(context).getString(KEY_PING_MODE, PING_MODE_DNS) ?: PING_MODE_DNS

    fun setPingMode(context: android.content.Context, mode: String) {
        prefs(context).edit().putString(KEY_PING_MODE, mode).apply()
    }

    fun getPingManualUrl(context: android.content.Context): String =
        prefs(context).getString(KEY_PING_MANUAL_URL, "") ?: ""

    fun setPingManualUrl(context: android.content.Context, url: String) {
        prefs(context).edit().putString(KEY_PING_MANUAL_URL, url.trim()).apply()
    }

    // ---- محدودساز سرعت ارسال کوئری ----
    // پیش‌فرض روشن: حداکثر ۶ کوئری در هر ۱ ثانیه به سرور DNS.
    // کوئری‌هایی که نوبتشان بیشتر از «تایم‌اوت صف» عقب بیفتد، حذف می‌شوند
    // و در sent/lost حساب نمی‌شوند (ریزالور خودش ریترای می‌کند).
    fun isQueryRateLimitEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_RATE_LIMIT, true)

    fun setQueryRateLimitEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RATE_LIMIT, value).apply()
    }

    fun getQueryRateCount(context: android.content.Context): Int =
        prefs(context).getInt(KEY_RATE_COUNT, 6).coerceAtLeast(1)

    fun setQueryRateCount(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_RATE_COUNT, value.coerceAtLeast(1)).apply()
    }

    fun getQueryRateWindowSec(context: android.content.Context): Int =
        prefs(context).getInt(KEY_RATE_WINDOW, 1).coerceAtLeast(1)

    fun setQueryRateWindowSec(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_RATE_WINDOW, value.coerceAtLeast(1)).apply()
    }

    fun getQueryRateTimeoutSec(context: android.content.Context): Int =
        prefs(context).getInt(KEY_RATE_TIMEOUT, 5).coerceAtLeast(1)

    fun setQueryRateTimeoutSec(context: android.content.Context, value: Int) {
        prefs(context).edit().putInt(KEY_RATE_TIMEOUT, value.coerceAtLeast(1)).apply()
    }

    // زبان برنامه: "device" (پیش‌فرض) | "fa" | "en"
    fun getLanguage(context: android.content.Context): String =
        prefs(context).getString(KEY_LANGUAGE, "device") ?: "device"

    fun setLanguage(context: android.content.Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }
}

/** انتخاب برنامه‌هایی که باید تونل بشن (DNS سفارشی بگیرن). */
object TunnelAppsStore {
    private const val PREFS = "tunnel_apps_prefs"
    private const val KEY_PACKAGES = "tunneled_packages"
    private const val KEY_HAS_SELECTION = "has_custom_selection"

    /** null یعنی کاربر هنوز انتخاب سفارشی نکرده -> یعنی «همه‌ی برنامه‌ها» (رفتار پیش‌فرض فعلی، بدون محدودیت). */
    fun getSelectedPackages(context: android.content.Context): Set<String>? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SELECTION, false)) return null
        return prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun saveSelectedPackages(context: android.content.Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_SELECTION, true)
            .putStringSet(KEY_PACKAGES, packages)
            .apply()
    }

    fun clearSelection(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HAS_SELECTION, false)
            .remove(KEY_PACKAGES)
            .apply()
    }
}
