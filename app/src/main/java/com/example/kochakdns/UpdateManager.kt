package com.example.kochakdns

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * بررسی بروزرسانی + دانلود + نصب APK (بدون وابستگی به فروشگاه).
 *
 * - ورژن جدید را از `/api/app/info` می‌خواند و با ورژن فعلی مقایسه می‌کند.
 * - APK را از `/api/app/download` دانلود و در cache کش می‌کند.
 * - بعد از دانلود، فایل را با FileProvider برای نصب باز می‌کند.
 * - بعد از نصب موفق (ورژن فعلی == ورژن دانلودشده)، کش به‌طور خودکار پاک می‌شود.
 */
object UpdateManager {

    private const val PREFS = "update_manager"
    private const val KEY_DOWNLOADED_PATH = "downloaded_apk_path"
    private const val KEY_DOWNLOADED_VERSION = "downloaded_apk_version"

    data class UpdateInfo(
        val version: String,
        val size: Long?,
        val sizeFormatted: String?
    )

    private val client by lazy { OkHttpClient() }

    /** ورژن فعلی برنامه (از PackageManager). */
    fun currentVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /** مقایسه‌ی ورژن‌ها: آیا remote جدیدتر از current است؟ */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.split(".").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(0) }

    /** اطلاعات بروزرسانی را می‌گیرد؛ null یعنی بروزرسانی موجود نیست (یا خاموش/خطا). */
    suspend fun fetchInfo(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(AppConfig.BASE_URL + AppConfig.API_APP_INFO)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                if (!json.optBoolean("available", false)) return@withContext null
                val version = json.optString("version", null)
                if (version.isNullOrEmpty()) return@withContext null
                if (!isNewer(version, currentVersion(context))) return@withContext null
                val size = if (json.has("size")) json.optLong("size") else null
                val sizeFormatted = json.optString("size_formatted", null).takeIf { it.isNotBlank() && it != "null" }
                UpdateInfo(version, size, sizeFormatted)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** APK را دانلود و در cache کش می‌کند؛ فایل را برمی‌گرداند (null یعنی شکست).
     *  @param onProgress درصد پیشرفت (0..100) — برای نمایش به کاربر. */
    suspend fun download(context: Context, onProgress: ((Int) -> Unit)? = null): File? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(AppConfig.BASE_URL + AppConfig.API_APP_DOWNLOAD)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                val file = File(context.cacheDir, "update.apk")
                FileOutputStream(file).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress?.invoke(pct)
                                }
                            }
                        }
                    }
                }
                if (file.length() <= 0) {
                    file.delete()
                    return@withContext null
                }
                file
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveDownloaded(context: Context, file: File, version: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DOWNLOADED_PATH, file.absolutePath)
                .putString(KEY_DOWNLOADED_VERSION, version)
                .apply()
        } catch (_: Exception) {}
    }

    /** فایل APK دانلودشده‌ی قبلی (در صورت وجود). */
    fun downloadedFile(context: Context): File? {
        return try {
            val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DOWNLOADED_PATH, null) ?: return null
            val file = File(path)
            if (file.exists() && file.length() > 0) file else null
        } catch (_: Exception) {
            null
        }
    }

    fun downloadedVersion(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DOWNLOADED_VERSION, null)
        } catch (_: Exception) {
            null
        }
    }

    /** اگر APK دانلودشده همان ورژن فعلی است (یعنی نصب شده)، کش را پاک می‌کند. */
    fun clearCacheIfInstalled(context: Context): Boolean {
        val downloadedVer = downloadedVersion(context) ?: return false
        return if (downloadedVer == currentVersion(context)) {
            clearCache(context)
            true
        } else {
            false
        }
    }

    /** حذف فایل کش + پاک کردن رکوردها. */
    fun clearCache(context: Context) {
        try {
            downloadedFile(context)?.delete()
        } catch (_: Exception) {}
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    /** باز کردن فایل APK برای نصب. */
    fun install(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
