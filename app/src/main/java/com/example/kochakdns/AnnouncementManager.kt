package com.example.kochakdns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * اطلاعیه‌ی داخل برنامه (که از ربات تلگرام تنظیم می‌شود).
 * اگر «دکمه لغو» در ربات ست نشده باشد، دیالوگ قابل رد شدن نیست.
 */
object AnnouncementManager {

    data class Announcement(
        val title: String,
        val body: String,
        val cancel: Boolean,
        val link: String?,
        val linkText: String?
    )

    private val client by lazy { OkHttpClient() }

    /** اطلاعیه را می‌گیرد؛ null یعنی اطلاعیه‌ای نیست (یا خاموش/خطا). */
    suspend fun fetch(context: Context): Announcement? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(AppConfig.BASE_URL + AppConfig.API_ANNOUNCEMENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                if (!json.optBoolean("available", false)) return@withContext null
                val title = json.optString("title", null)
                if (title.isNullOrBlank()) return@withContext null
                val body = json.optString("body", "")
                val cancel = json.optBoolean("cancel", false)
                val link = json.optString("link", null).takeIf { it.isNotBlank() && it != "null" }
                val linkText = json.optString("link_text", null).takeIf { it.isNotBlank() && it != "null" }
                Announcement(title, body, cancel, link, linkText)
            }
        } catch (_: Exception) {
            null
        }
    }
}
