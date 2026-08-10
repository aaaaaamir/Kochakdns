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
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private const val BASE_URL = "https://kodns.ir"
        private const val BASE_HOST = "kodns.ir"
        private var accessToken: String? = null

        fun setAccessToken(token: String?) {
            accessToken = token
        }

        // DNS-over-HTTPS resolvers, queried by literal IP so the request itself
        // never needs a (poisonable) DNS lookup. Cloudflare first, Google as fallback.
        private val DOH_ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/resolve"
        )

        // Cache resolved IPs briefly in memory so we don't hit DoH on every request.
        private val dohCache = mutableMapOf<String, Pair<String, Long>>()
        private const val DOH_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val dataStore = context.dnsDataStore
    private val DNS_LIST_KEY = stringPreferencesKey("dns_profiles_list")
    private val LAST_SYNC_KEY = longPreferencesKey("last_sync_time")

    // ------------------------------------------------------------
    // Custom Dns for OkHttp: resolves hostnames via DoH instead of
    // the (potentially poisoned/hijacked) system/ISP DNS resolver.
    // ------------------------------------------------------------
    private inner class DohDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ip = resolveViaDoH(hostname)
                ?: throw UnknownHostException("امکان resolve امن دامنه $hostname وجود نداشت (DoH ناموفق بود).")
            // Parsing a literal IP string does not trigger a network DNS lookup.
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

    suspend fun sync(
        onSuccess: ((List<DnsProfile>) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        try {
            val profiles = withContext(Dispatchers.IO) {
                fetchListFromServer()
            }
            saveProfiles(profiles)

            withContext(Dispatchers.Main) {
                showToast("✓ لیست DNS با موفقیت بروزرسانی شد (${profiles.size} پروفایل)", false)
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
    // DNS-over-HTTPS resolution (bypasses system/ISP DNS entirely)
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
        // endpoint host is a literal IP (1.1.1.1 / 8.8.8.8) -> no DNS lookup needed here.
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
    // Actual API request, routed through the DoH-resolved IP while
    // still using the correct hostname for TLS SNI / cert checks
    // (OkHttp handles this automatically via the custom Dns above).
    // ------------------------------------------------------------
    private fun fetchListFromServer(): List<DnsProfile> {
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
                    403, 503 -> Exception("دسترسی رد شد. لطفاً بررسی کنید که آیا به فیلترشکن نیاز است یا درخواست محدود شده است.")
                    else -> Exception("خطای سرور (کد ${response.code}): ${if (errorBody.isNotBlank()) errorBody else "پاسخ نامعتبر"}")
                }
            }

            val responseBody = response.body?.string()
                ?: throw IOException("پاسخ خالی از سرور دریافت شد.")

            val trimmedResponse = responseBody.trim()
            if (trimmedResponse.startsWith("<!DOCTYPE", ignoreCase = true) ||
                trimmedResponse.startsWith("<html", ignoreCase = true)
            ) {
                throw IOException("سرور پاسخ نامعتبر (HTML) برگرداند. احتمالاً دسترسی توسط شبکه یا فایروال مسدود شده است.")
            }

            return parseListResponse(responseBody)
        }
    }

    private fun parseListResponse(jsonString: String): List<DnsProfile> {
        try {
            val root = JSONObject(jsonString)
            if (!root.optBoolean("ok", false)) {
                throw Exception("پاسخ سرور نامعتبر است (وضعیت ok ناموفق بود).")
            }

            val activeName = root.optString("active_profile", null.toString()).takeIf { it.isNotBlank() && it != "null" }
            val dataArray = root.optJSONArray("data") ?: throw Exception("لیست DNS در پاسخ سرور وجود ندارد.")
            val profiles = mutableListOf<DnsProfile>()

            for (i in 0 until dataArray.length()) {
                val p = dataArray.getJSONObject(i)
                val dns = p.optJSONObject("dns")
                val servers = mutableListOf<DnsServer>()
                val serversArray = p.optJSONArray("servers")
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
                val name = p.optString("name")
                profiles.add(
                    DnsProfile(
                        name = name,
                        enabled = p.optBoolean("enabled"),
                        ipv4Primary = dns?.optString("ipv4_primary")?.takeIf { it.isNotEmpty() && it != "null" },
                        ipv6Primary = dns?.optString("ipv6_primary")?.takeIf { it.isNotEmpty() && it != "null" },
                        ipv4Secondary = dns?.optString("ipv4_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
                        ipv6Secondary = dns?.optString("ipv6_secondary")?.takeIf { it.isNotEmpty() && it != "null" },
                        servers = servers,
                        updatedAt = p.optString("updated_at").takeIf { it.isNotEmpty() && it != "null" },
                        isActive = p.optBoolean("is_active", name == activeName)
                    )
                )
            }

            if (profiles.isEmpty()) {
                throw Exception("هیچ پروفایل فعالی روی سرور یافت نشد.")
            }
            return profiles
        } catch (e: JSONException) {
            throw Exception("خطا در تحلیل ساختار JSON دریافتی از سرور.")
        }
    }

    private fun parseErrorMessage(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "خطای شبکه: امکان resolve امن آدرس سرور وجود نداشت. لطفاً اتصال اینترنت یا فیلترشکن خود را بررسی کنید."
            is SocketTimeoutException -> "خطای تایم‌آوت: سرور پاسخگویی به موقع نداشت. لطفاً فیلترشکن خود را بررسی کنید."
            is IOException -> e.message ?: "خطای ارتباط با شبکه رخ داد."
            else -> e.message ?: "خطای ناشناخته رخ داد."
        }
    }

    private suspend fun saveProfiles(profiles: List<DnsProfile>) {
        dataStore.edit { prefs ->
            prefs[DNS_LIST_KEY] = DnsProfile.listToJson(profiles)
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
