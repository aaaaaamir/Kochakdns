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

    fun save(context: android.content.Context, profileName: String, sent: Long, lost: Long, connectStart: Long) {
        try {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, profileName)
                .putLong(KEY_SENT, sent)
                .putLong(KEY_LOST, lost)
                .putLong(KEY_START, connectStart)
                .putLong(KEY_CHECKPOINT, System.currentTimeMillis())
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

    data class Pending(val profileName: String, val sent: Long, val lost: Long, val durationMs: Long)

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
                durationMs = checkpoint - start
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** منطق مشترک ارسال آمار به سرور، هم از MyVpnService (قطع عادی) هم از MainActivity (فلاش کردن رکورد جامونده) استفاده می‌شه. */
object StatsReporter {
    private val client: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient() }

    /** blocking است؛ حتماً از یک ترد پس‌زمینه صدا زده بشه. */
    fun send(profileName: String, sent: Long, lost: Long): Boolean {
        return try {
            val json = JSONObject().apply {
                put("profile_name", profileName)
                put("packets_sent", sent)
                put("packets_lost", lost)
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
