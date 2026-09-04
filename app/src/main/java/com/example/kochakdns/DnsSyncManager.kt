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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

val Context.dnsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dns_sync_data")

/**
 * اجازه می‌ده sync از MainActivity (همون لحظه‌ی splash) شروع بشه تا زودتر
 * دیتا برسه، و DnsActivity به‌جای شروع یک sync جدید از صفر، به همون درخواستِ
 * در حال اجرا join بشه (یا اگه چیزی در جریان نبود، خودش شروع کنه). این‌طوری
 * هم دیتا زودتر آماده می‌شه هم race condition قبلی (خالی موندن لیست) برنمی‌گرده.
 */
object DnsSyncCoordinator {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
    @Volatile
    private var syncDeferred: kotlinx.coroutines.Deferred<Boolean>? = null

    /**
     * true اگه دیتای تازه واقعاً از سرور دریافت و ذخیره شد، false اگه سرور خطا داد.
     *
     * پیش‌فرض (force=false): اگه یک sync قبلاً شروع شده — چه هنوز در حال
     * اجرا باشه چه از قبل *تموم* شده باشه — همون نتیجه رو دوباره برمی‌گردونه،
     * بدون این‌که درخواست جدیدی به سرور بزنه (چون await روی یک Deferred
     * تموم‌شده فقط نتیجه‌ی کش‌شده رو برمی‌گردونه، نه اجرای دوباره).
     * این دقیقاً همون چیزیه که جلوی «دو بار sync زدن» (یکی از MainActivity،
     * یکی از DnsActivity) رو می‌گیره.
     *
     * force=true: صرف‌نظر از هرچی، یک درخواست کاملاً تازه می‌زنه — فقط برای
     * دکمه‌ی رفرش دستی کاربر استفاده می‌شه.
     */
    fun startSync(context: Context, force: Boolean = false): kotlinx.coroutines.Deferred<Boolean> {
        val existing = syncDeferred
        if (!force && existing != null) return existing
        val appContext = context.applicationContext
        val job = scope.async {
            DnsSyncManager(appContext).sync()
        }
        syncDeferred = job
        return job
    }
}

class DnsSyncManager(private val context: Context) {

    companion object {
        private var accessToken: String? = null

        fun setAccessToken(token: String?) {
            accessToken = token
        }
    }

    private val dataStore = context.dnsDataStore
    private val DNS_LIST_KEY = stringPreferencesKey("dns_profiles_list")
    private val LAST_SYNC_KEY = longPreferencesKey("last_sync_time")

    // با DoH و resolve سفارشی دیگه کاری نداریم؛ از DNS سیستم عادی (پیش‌فرض
    // OkHttp) استفاده می‌کنیم که هم ساده‌تره هم سریع‌تر (یک round-trip
    // کمتر نسبت به قبل که اول باید IP رو از طریق DoH می‌گرفتیم).
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** @return true اگه از سرور با موفقیت گرفته و ذخیره شد، false اگه خطا خورد (و کش قبلی دست‌نخورده می‌مونه). */
    suspend fun sync(
        onSuccess: ((List<DnsProfile>) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        return try {
            val profiles = withContext(Dispatchers.IO) {
                fetchListFromServer()
            }
            saveProfiles(profiles)

            // Toast سبز «موفقیت» حذف شد؛ موفقیت بی‌صدا اعمال می‌شود و فقط
            // خطاها به کاربر نمایش داده می‌شوند.
            onSuccess?.invoke(profiles)
            true
        } catch (e: Exception) {
            val errorMessage = parseErrorMessage(e)
            withContext(Dispatchers.Main) {
                showToast(errorMessage, true)
            }
            onError?.invoke(errorMessage)
            false
        }
    }

    /** آمار کامل یک پروفایل: مجموع پکت‌ها + تفکیک اوپراتورها. */
    data class DnsStatsData(
        val sent: Long,
        val lost: Long,
        val operators: List<OperatorStat>
    )

    /**
     * آمار پکت‌های ارسالی/گم‌شده‌ی هر پروفایل را از سرور می‌گیرد (GET
     * /api/dns/stats) همراه با تفکیک اوپراتورها.
     *
     * @return map از profile_name به DnsStatsData
     */
    suspend fun fetchStats(): Map<String, DnsStatsData> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${AppConfig.BASE_URL}/api/dns/stats")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyMap()
                    val body = response.body?.string() ?: return@withContext emptyMap()
                    val root = JSONObject(body)
                    val dataArray = root.optJSONArray("data") ?: return@withContext emptyMap()
                    val map = mutableMapOf<String, DnsStatsData>()
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val name = item.optString("profile_name")
                        if (name.isBlank()) continue
                        val sent = item.optLong("packets_sent", 0)
                        val lost = item.optLong("packets_lost", 0)

                        val ops = mutableListOf<OperatorStat>()
                        val opArray = item.optJSONArray("operators")
                        if (opArray != null) {
                            for (j in 0 until opArray.length()) {
                                val op = opArray.getJSONObject(j)
                                val opName = op.optString("operator")
                                if (opName.isBlank()) continue
                                ops.add(
                                    OperatorStat(
                                        opName,
                                        op.optLong("packets_sent", 0),
                                        op.optLong("packets_lost", 0)
                                    )
                                )
                            }
                        }
                        // مرتب‌سازی از بهترین به بدترین
                        ops.sortByDescending { it.successPercent ?: -1.0 }
                        map[name] = DnsStatsData(sent, lost, ops)
                    }
                    map
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    private fun fetchListFromServer(): List<DnsProfile> {
        val requestBuilder = Request.Builder()
            .url("${AppConfig.BASE_URL}/api/dns/list")
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
            is UnknownHostException -> "خطای شبکه: امکان resolve دامنه‌ی سرور وجود نداشت. لطفاً اتصال اینترنت خود را بررسی کنید."
            is SocketTimeoutException -> "خطای تایم‌آوت: سرور پاسخگویی به موقع نداشت."
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
                text = if (isError) "!" else "✓"
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
