package com.example.kochakdns  // ← پکیج خودت رو بذار

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ==================== DataStore ====================
private val Context.dnsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dns_sync_data")

// ==================== Data Classes ====================
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
    val updatedAt: String?
) {
    fun toJson(): String {
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
            put("servers", serversArray)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): DnsProfile {
            val obj = JSONObject(json)
            val serversArray = obj.optJSONArray("servers")
            val servers = mutableListOf<DnsServer>()

            if (serversArray != null) {
                for (i in 0 until serversArray.length()) {
                    val s = serversArray.getJSONObject(i)
                    servers.add(DnsServer(
                        role = s.optString("role"),
                        priority = s.optInt("priority"),
                        family = s.optString("family"),
                        address = s.optString("address")
                    ))
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
                updatedAt = obj.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" }
            )
        }
    }
}

// ==================== Main Manager ====================
class DnsSyncManager(private val context: Context) {

    companion object {
        // ⬇️⬇️⬇️ آدرس بک‌اند خودت رو اینجا بذار (خط 101) ⬇️⬇️⬇️
        private const val BASE_URL = "https://kochakdns-backend.amir26076.workers.dev"
        // ⬆️⬆️⬆️ فقط همین یک خط رو عوض کن ⬆️⬆️⬆️

        private var accessToken: String? = null

        /**
         * بعداً اگر API_ACCESS_TOKEN گذاشتی، فقط این یک خط رو صدا کن:
         * DnsSyncManager.setAccessToken("your-token")
         */
        fun setAccessToken(token: String?) {
            accessToken = token
        }
    }

    private val dataStore = context.dnsDataStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private val DNS_DATA_KEY = stringPreferencesKey("dns_profile_data")
    private val LAST_SYNC_KEY = longPreferencesKey("last_sync_time")

    /**
     * دریافت از سرور و ذخیره
     * در صورت موفقیت: دیتای قبلی پاک و با جدید جایگزین می‌شود
     * در صورت خطا: دیتای قبلی دست نمی‌خورد
     */
    suspend fun sync(
        onSuccess: ((DnsProfile) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val profile = withContext(Dispatchers.IO) {
                fetchFromServer()
            }

            // ✅ موفقیت → پاک کردن قبلی و ذخیره جدید
            saveProfile(profile)

            mainHandler.post {
                showToast("✓ سرورهای DNS با موفقیت بروزرسانی شدند", false)
            }

            onSuccess?.invoke(profile)

        } catch (e: Exception) {
            val errorMessage = when (e) {
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException,
                is java.io.IOException -> {
                    "خطا در بروزرسانی سرور های dns لطفا اگر دفعه اول است که وارد میشوید از فیلترشکن استفاده کنید."
                }
                else -> e.message ?: "خطای ناشناخته"
            }

            mainHandler.post {
                showToast(errorMessage, true)
            }

            onError?.invoke(errorMessage)
        }
    }

    /**
     * خواندن کانفیگ ذخیره شده (بدون درخواست شبکه)
     */
    suspend fun getSavedProfile(): DnsProfile? {
        return withContext(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val json = prefs[DNS_DATA_KEY] ?: return@withContext null
            try {
                DnsProfile.fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * پاک کردن دیتای ذخیره شده
     */
    suspend fun clearSavedData() {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(DNS_DATA_KEY)
                prefs.remove(LAST_SYNC_KEY)
            }
        }
    }

    /**
     * آخرین زمان sync (میلی‌ثانیه)
     */
    suspend fun getLastSyncTime(): Long? {
        return withContext(Dispatchers.IO) {
            dataStore.data.first()[LAST_SYNC_KEY]
        }
    }

    // ==================== Private ====================

    private fun fetchFromServer(): DnsProfile {
        val url = URL("$BASE_URL/api/dns/active")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            // اگر token تنظیم شده باشه، اضافه می‌شود
            accessToken?.let { token ->
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = conn.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("خطای سرور: $responseCode")
            }

            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            return parseResponse(responseBody)

        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(jsonString: String): DnsProfile {
        val root = JSONObject(jsonString)

        if (!root.optBoolean("ok", false)) {
            throw Exception("پاسخ سرور نامعتبر است")
        }

        if (!root.optBoolean("active", false)) {
            throw Exception("هیچ پروفایل فعالی تنظیم نشده است")
        }

        val data = root.optJSONObject("data")
            ?: throw Exception("داده DNS در پاسخ سرور وجود ندارد")

        val dns = data.optJSONObject("dns")

        val servers = mutableListOf<DnsServer>()
        val serversArray = data.optJSONArray("servers")
        if (serversArray != null) {
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.getJSONObject(i)
                servers.add(DnsServer(
                    role = serverObj.optString("role"),
                    priority = serverObj.optInt("priority"),
                    family = serverObj.optString("family"),
                    address = serverObj.optString("address")
                ))
            }
        }

        return DnsProfile(
            name = data.optString("name"),
            enabled = data.optBoolean("enabled"),
            ipv4Primary = dns?.optString("ipv4_primary")?.takeIf { it.isNotEmpty() && it != "null" },
            ipv6Primary = dns?.optString("ipv6_primary")?.takeIf { it.isNotEmpty() && it != "null" },
            ipv4Secondary = dns?.optString("ipv4_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
            ipv6Secondary = dns?.optString("ipv6_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
            servers = servers,
            updatedAt = data.optString("updated_at").takeIf { it.isNotEmpty() && it != "null" }
        )
    }

    private suspend fun saveProfile(profile: DnsProfile) {
        dataStore.edit { prefs ->
            // پاک کردن دیتای قبلی و جایگزینی با جدید
            prefs.remove(DNS_DATA_KEY)
            prefs[DNS_DATA_KEY] = profile.toJson()
            prefs[LAST_SYNC_KEY] = System.currentTimeMillis()
        }
    }

    private fun showToast(message: String, isError: Boolean) {
        try {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 28, 40, 28)

                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (isError) {
                        intArrayOf(Color.parseColor("#D32F2F"), Color.parseColor("#B71C1C"))
                    } else {
                        intArrayOf(Color.parseColor("#388E3C"), Color.parseColor("#2E7D32"))
                    }
                ).apply {
                    cornerRadius = 28f
                }

                gravity = Gravity.CENTER_VERTICAL
            }

            val icon = TextView(context).apply {
                text = if (isError) "⚠️" else "✓"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 20, 0)
            }

            val textView = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    weight = 1f
                }
            }

            layout.addView(icon)
            layout.addView(textView)

            Toast(context).apply {
                duration = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                view = layout
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 120)
                show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
