package com.example.kochakdns

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

val Context.dnsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dns_sync_data")

class DnsSyncManager(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://kochakdns-backend.amir26076.workers.dev"
        private var accessToken: String? = null

        fun setAccessToken(token: String?) {
            accessToken = token
        }

        private val DOH_ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/resolve"
        )

        private val dohCache = mutableMapOf<String, Pair<String, Long>>()
        private const val DOH_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val dataStore = context.dnsDataStore
    private val DNS_LIST_KEY = stringPreferencesKey("dns_profiles_list")
    private val SELECTED_PROFILE_KEY = stringPreferencesKey("selected_profile_name")
    private val LAST_SYNC_KEY = longPreferencesKey("last_sync_time")

    private inner class DohDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ip = resolveViaDoH(hostname)
                ?: throw UnknownHostException("امکان resolve امن دامنه $hostname وجود نداشت (DoH ناموفق بود).")
            return listOf(InetAddress.getByName(ip))
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * سینک و دریافت تمام پروفایل‌های DNS از سرور
     */
    suspend fun syncProfiles(
        onSuccess: ((List<DnsProfile>) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val profiles = withContext(Dispatchers.IO) {
                fetchProfilesFromServer()
            }
            saveProfiles(profiles)

            withContext(Dispatchers.Main) {
                showToast("✓ لیست سرورهای DNS با موفقیت بروزرسانی شد", false)
            }
            onSuccess?.invoke(profiles)
        } catch (e: Exception) {
            val errorMessage = parseErrorMessage(e)
            withContext(Dispatchers.Main) {
                showToast(errorMessage, true)
            }
            onError?.invoke(errorMessage)
        }
    }

    // ------------------------------------------------------------
    // DNS-over-HTTPS resolution
    // ------------------------------------------------------------
    private fun resolveViaDoH(hostname: String): String? {
        dohCache[hostname]?.let { (ip, ts) ->
            if (System.currentTimeMillis() - ts < DOH_CACHE_TTL_MS) return ip
        }

        for (endpoint in DOH_ENDPOINTS) {
            try {
                val ip = queryDoh(endpoint, hostname)
                if (ip != null) {
                    dohCache[hostname] = ip to System.currentTimeMillis()
                    return ip
                }
            } catch (_: Exception) {
                // try next endpoint
            }
        }
        return null
    }

    private fun queryDoh(endpoint: String, hostname: String): String? {
        val url = URL("$endpoint?name=$hostname&type=A")
        val conn = url.openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/dns-json")

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null

            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                if (a.optInt("type") == 1) { // A record
                    val data = a.optString("data")
                    if (data.isNotBlank()) return data
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------
    // API Request and Parsing
    // ------------------------------------------------------------
    private fun fetchProfilesFromServer(): List<DnsProfile> {
        val requestBuilder = Request.Builder()
            .url("$BASE_URL/api/dns/list")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "KochakDNS-Android-Client/1.0")

        accessToken?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = try {
                    response.body?.string() ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw when (response.code) {
                    401 -> Exception("خطای احراز هویت: توکن دسترسی نامعتبر یا منقضی شده است.")
                    403, 503 -> Exception("دسترسی رد شد. لطفاً بررسی کنید که آیا به فیلترشکن نیاز است یا خیر.")
                    else -> Exception("خطای سرور (کد ${response.code}): ${if (errorBody.isNotBlank()) errorBody else "پاسخ نامعتبر"}")
                }
            }

            val responseBody = response.body?.string()
                ?: throw IOException("پاسخ خالی از سرور دریافت شد.")

            val trimmedResponse = responseBody.trim()
            if (trimmedResponse.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedResponse.startsWith("<html", ignoreCase = true)
            ) {
                throw IOException("سرور پاسخ نامعتبر (HTML) برگرداند. احتمالاً دسترسی مسدود شده است.")
            }

            return parseProfilesList(responseBody)
        }
    }

    private fun parseProfilesList(jsonString: String): List<DnsProfile> {
        try {
            val root = JSONObject(jsonString)

            if (!root.optBoolean("ok", false)) {
                throw Exception("پاسخ سرور نامعتبر است (وضعیت ok ناموفق بود).")
            }

            val dataArray = root.optJSONArray("data")
                ?: throw Exception("لیست داده‌های DNS در پاسخ سرور یافت نشد.")

            val profilesList = mutableListOf<DnsProfile>()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val dns = item.optJSONObject("dns")
                val servers = mutableListOf<DnsServer>()
                val serversArray = item.optJSONArray("servers")

                if (serversArray != null) {
                    for (j in 0 until serversArray.length()) {
                        val s = serversArray.getJSONObject(j)
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

                val profile = DnsProfile(
                    name = item.optString("name"),
                    enabled = item.optBoolean("enabled"),
                    ipv4Primary = dns?.optString("ipv4_primary")?.takeIf { it.isNotEmpty() && it != "null" },
                    ipv6Primary = dns?.optString("ipv6_primary")?.takeIf { it.isNotEmpty() && it != "null" },
                    ipv4Secondary = dns?.optString("ipv4_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
                    ipv6Secondary = dns?.optString("ipv6_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
                    servers = servers,
                    updatedAt = item.optString("updated_at").takeIf { it.isNotEmpty() && it != "null" },
                    isActive = item.optBoolean("is_active", false)
                )

                profilesList.add(profile)
            }

            return profilesList
        } catch (e: JSONException) {
            throw Exception("خطا در تحلیل ساختار JSON دریافتی از سرور.")
        }
    }

    private fun parseErrorMessage(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "خطای شبکه: امکان resolve امن آدرس سرور وجود نداشت. لطفاً اتصال اینترنت یا فیلترشکن خود را بررسی کنید."
            is SocketTimeoutException -> "خطای تایم‌آوت: سرور پاسخگویی به موقع نداشت."
            is IOException -> e.message ?: "خطای ارتباط با شبکه رخ داد."
            else -> e.message ?: "خطای ناشناخته رخ داد."
        }
    }

    // ------------------------------------------------------------
    // Local DataStore Management
    // ------------------------------------------------------------
    private suspend fun saveProfiles(profiles: List<DnsProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { profile ->
            jsonArray.put(JSONObject(profile.toJson().toString()))
        }

        dataStore.edit { prefs ->
            prefs[DNS_LIST_KEY] = jsonArray.toString()
            prefs[LAST_SYNC_KEY] = System.currentTimeMillis()
        }
    }

    /**
     * دریافت لیست تمام پروفایل‌های ذخیره‌شده
     */
    suspend fun getSavedProfiles(): List<DnsProfile> {
        val prefs = dataStore.data.first()
        val jsonString = prefs[DNS_LIST_KEY] ?: return emptyList()

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<DnsProfile>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                list.add(DnsProfile.fromJson(item))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ذخیره کردن پروفایل انتخابی کاربر
     */
    suspend fun selectProfile(profileName: String) {
        dataStore.edit { prefs ->
            prefs[SELECTED_PROFILE_KEY] = profileName
        }
    }

    /**
     * دریافت پروفایل فعلی که کاربر انتخاب کرده است
     */
    suspend fun getSelectedProfile(): DnsProfile? {
        val prefs = dataStore.data.first()
        val selectedName = prefs[SELECTED_PROFILE_KEY]
        val allProfiles = getSavedProfiles()

        return if (selectedName != null) {
            allProfiles.find { it.name == selectedName } ?: allProfiles.firstOrNull()
        } else {
            // اگر کاربر چیزی انتخاب نکرده بود، پروفایلی که روی سرور active است یا اولی را برمی‌گرداند
            allProfiles.find { it.isActive } ?: allProfiles.firstOrNull()
        }
    }

    private fun showToast(message: String, isError: Boolean) {
        try {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 28, 40, 28)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (isError) intArrayOf(Color.parseColor("#D32F2F"), Color.parseColor("#B71C1C"))
                    else intArrayOf(Color.parseColor("#388E3C"), Color.parseColor("#2E7D32"))
                ).apply { cornerRadius = 28f }
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
                ).apply { weight = 1f }
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
