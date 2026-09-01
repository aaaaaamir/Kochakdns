package com.example.kochakdns

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** دامنه‌ی اصلی بک‌اند، یک‌جا تعریف شده تا هم DnsSyncManager هم MyVpnService از همین استفاده کنن. */
object AppConfig {
    const val BASE_URL = "https://kodns.ir"

    // مسیرهای API (بک‌اند Cloudflare Worker)
    const val API_APP_INFO = "/api/app/info"          // بررسی بروزرسانی (ورژن/حجم)
    const val API_APP_DOWNLOAD = "/api/app/download"  // دانلود APK
    const val API_ANNOUNCEMENT = "/api/app/announcement" // اطلاعیه داخل برنامه
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

data class DnsItem(
    val name: String,
    val servers: List<DnsServer>,
    val ping: Long = -1,
    val previousPing: Long = -1,
    // آماری که از سرور (GET /api/dns/stats) خونده می‌شه؛ مجموع پکت‌های
    // ارسالی/گم‌شده‌ای که قبلاً وقتی این پروفایل وصل بوده، ثبت شده.
    val statsPacketsSent: Long = 0,
    val statsPacketsLost: Long = 0
) {
    val jitter: Long
        get() = if (ping > 0 && previousPing > 0) abs(ping - previousPing) else 0

    val statsTotal: Long
        get() = statsPacketsSent + statsPacketsLost

    /** درصد موفقیت (چند درصد از کل پکت‌ها واقعاً ارسال شدن، نه گم شدن). null یعنی هنوز آماری نیست. */
    val successPercent: Double?
        get() = if (statsTotal > 0) (statsPacketsSent * 100.0 / statsTotal) else null
}

object VpnStats {
    val totalBytesSent = AtomicLong(0)
    val totalBytesReceived = AtomicLong(0)
    val totalPacketsSent = AtomicLong(0)
    val totalPacketsLost = AtomicLong(0)
    // پکت‌هایی که عمداً به‌دلیل «مسدودسازی» دور ریخته می‌شوند؛ این‌ها گم‌شده
    // نیستند و نه در UI به‌عنوان گم‌شده نمایش داده می‌شوند نه به سرور ارسال می‌شوند.
    val totalPacketsBlocked = AtomicLong(0)

    @Volatile
    var isVpnActive = false

    @Volatile
    var activeDnsName: String? = null
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
                .url("${AppConfig.BASE_URL}/api/dns/stats")
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
    private const val KEY_FULL_TUNNEL = "full_tunnel"
    private const val KEY_SHOW_PACKET_PERCENT = "show_packet_percentage"
    private const val KEY_SHOW_NOTIFICATION = "show_notification"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    // پیش‌فرض false: تونل کامل خاموش است (فقط DNS relay می‌شود).
    // وقتی روشن باشد، کل ترافیک برنامه‌های انتخاب‌شده (نه فقط DNS) از تونل
    // رد می‌شود؛ برنامه‌های انتخاب‌نشده اینترنت معمولی دارند.
    fun isFullTunnelEnabled(context: android.content.Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_TUNNEL, false)

    fun setFullTunnelEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_TUNNEL, value).apply()
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
