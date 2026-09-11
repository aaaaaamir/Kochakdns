package com.example.kochakdns

import android.content.Context
import android.os.Build

/**
 * مدیریت زبان برنامه:
 *  - "device" → از زبان خودِ گوشی پیروی می‌کند (فارسی گوشی = فارسی، بقیه = انگلیسی)
 *  - "fa"     → همیشه فارسی
 *  - "en"     → همیشه انگلیسی
 */
object AppLang {
    const val DEVICE = "device"
    const val FA = "fa"
    const val EN = "en"

    /** آیا رابط کاربری باید فارسی باشد؟ */
    fun isFa(context: Context): Boolean = when (AppSettings.getLanguage(context)) {
        FA -> true
        EN -> false
        else -> {
            val cfg = context.resources.configuration
            val loc = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cfg.locales[0]
                else @Suppress("DEPRECATION") cfg.locale
            } catch (_: Exception) {
                null
            }
            loc?.language == "fa"
        }
    }
}

/**
 * رشته‌های رابط کاربری به دو زبان.
 * هر رشته با کلید متنی از نقشه خوانده می‌شود؛ اگر کلیدی نبود، fallback به فارسی.
 */
object I18n {

    private val FA = mapOf(
        // منوی کشویی
        "menu_settings" to "تنظیمات",
        "menu_ipv6" to "پشتیبانی از IPv6",
        "menu_tunnel_apps" to "برنامه‌های تونل شده",

        // تنظیمات
        "settings_title" to "تنظیمات",
        "set_dns_cache" to "کش DNS",
        "set_dns_cache_sub" to "پاسخ‌های تکراری و نامعتبر DNS از حافظه خوانده می‌شوند و پاسخ‌های قدیمی تا رسیدن پاسخ تازه سرو می‌شوند",
        "set_tcp_fallback" to "پشتیبانی TCP (Fallback)",
        "set_tcp_fallback_sub" to "اگر UDP جواب ندهد همان درخواست با TCP امتحان می‌شود — خاموش کردنش وقتی UDP مختل باشد پکت گم‌شده را زیاد می‌کند",
        "set_show_percent" to "نمایش درصد پکت‌ها",
        "set_show_percent_sub" to "درصد موفقیت ارسال پکت‌ها روی کارت هر DNS",
        "set_show_notif" to "نمایش اطلاعات در نوتیفیکیشن",
        "set_show_notif_sub" to "وقتی خاموش باشد، نوتیفیکیشن فقط وضعیت اتصال را نشان می‌دهد",
        "set_quick_settings" to "دسترسی سریع (Quick Settings)",
        "set_udp_conc" to "درخواست‌های همزمان UDP",
        "set_udp_conc_sub" to "کم بودنش صف و تأخیر می‌سازد؛ زیاد بودنش فشار روی شبکه",
        "set_tcp_conc" to "درخواست‌های همزمان TCP",
        "set_tcp_conc_sub" to "فقط وقتی UDP جواب ندهد استفاده می‌شود",
        "set_timeout_start" to "نقطه شروع تایم‌اوت",
        "set_timeout_start_sub" to "تایم‌اوت اولیه و سقف هر درخواست؛ کمترش = سریع‌تر ولی ریسک گم‌شدن پاسخ‌های دیر",
        "set_timeout_floor" to "کف تایم‌اوت",
        "set_timeout_floor_sub" to "زیر ۲ ثانیه ممکن است پاسخ‌های سالمِ دیررسیده را قطع کند و پکت گم‌شده زیاد شود",
        "set_language" to "زبان برنامه",
        "set_about" to "درباره ما",

        // گزینه‌های زبان
        "lang_device" to "زبان دستگاه",
        "lang_fa" to "فارسی",
        "lang_en" to "English",

        // دیالوگ‌ها
        "cancel" to "لغو",
        "close" to "بستن",

        // راهنمای کاشی
        "tile_hint" to "نوار اعلان را بکش پایین ← ویرایش (مداد) ← کاشی «Kochak» را اضافه کن",

        // واحدها
        "val_requests" to "درخواست",
        "val_ms" to "میلی‌ثانیه",

        // نرخ کش
        "cache_rate_off" to "نرخ پاسخ از کش: خاموش",
        "cache_rate_na" to "نرخ پاسخ از کش: --",
        "cache_rate" to "نرخ پاسخ از کش: %d٪",

        // صفحه اصلی (DnsActivity)
        "stat_sent" to "ارسالی",
        "stat_lost" to "گم‌شده",
        "stat_bsent" to "ارسال",
        "stat_brecv" to "دریافت",
        "stat_total" to "کل",
        "stat_avg" to "میانگین",
        "update_open" to "باز کردن",
        "list_dns" to "لیست DNS",
        "dlg_connect_first" to "لطفاً ابتدا یک DNS انتخاب کنید",
        "dlg_connect_err" to "خطا در اتصال:",
        "dlg_conn_failed" to "اتصال ناموفق بود. دوباره امتحان کنید.",
        "dlg_disc_failed" to "قطع اتصال ناموفق بود، دوباره امتحان کنید",
        "update_new" to "بروزرسانی جدید",
        "update_ready" to "آماده نصب",
        "update_download" to "دانلود",
        "update_install" to "نصب بروزرسانی",
        "update_later" to "بعداً",
        "qs_title" to "دسترسی سریع",
        "qs_msg" to "می‌خوای یک کاشی وصل/قطع سریع به نوار اعلان اضافه کنی؟ با یک تپ، بدون باز کردن برنامه، وصل یا قطع می‌شی.",
        "qs_enable" to "فعال کن",

        // نوتیفیکیشن
        "notif_connecting" to "در حال اتصال به",
        "notif_disconnect" to "قطع اتصال",
        "notif_active" to "Kochak DNS فعال است",
        "notif_connected_to" to "متصل به"
    )

    private val EN = mapOf(
        "menu_settings" to "Settings",
        "menu_ipv6" to "IPv6 support",
        "menu_tunnel_apps" to "Tunneled apps",

        "settings_title" to "Settings",
        "set_dns_cache" to "DNS cache",
        "set_dns_cache_sub" to "Repeated and invalid DNS answers are served from memory; stale answers are served until a fresh one arrives",
        "set_tcp_fallback" to "TCP fallback",
        "set_tcp_fallback_sub" to "Retries over TCP when UDP fails — disabling it while UDP is blocked increases packet loss",
        "set_show_percent" to "Show packet percentage",
        "set_show_percent_sub" to "Success percentage shown on each DNS card",
        "set_show_notif" to "Show info in notification",
        "set_show_notif_sub" to "When off, the notification only shows connection state",
        "set_quick_settings" to "Quick Settings tile",
        "set_udp_conc" to "Concurrent UDP requests",
        "set_udp_conc_sub" to "Too low = queueing and latency; too high = network pressure",
        "set_tcp_conc" to "Concurrent TCP requests",
        "set_tcp_conc_sub" to "Used only when UDP fails",
        "set_timeout_start" to "Timeout start",
        "set_timeout_start_sub" to "Initial and maximum timeout per request; lower = faster but riskier for late replies",
        "set_timeout_floor" to "Timeout floor",
        "set_timeout_floor_sub" to "Below 2s may cut off healthy late replies and increase packet loss",
        "set_language" to "App language",
        "set_about" to "About",

        "lang_device" to "Device language",
        "lang_fa" to "Persian",
        "lang_en" to "English",

        "cancel" to "Cancel",
        "close" to "Close",

        "tile_hint" to "Pull down the notification shade → Edit → add the \"Kochak\" tile",

        "val_requests" to "requests",
        "val_ms" to "ms",

        "cache_rate_off" to "Cache hit rate: off",
        "cache_rate_na" to "Cache hit rate: --",
        "cache_rate" to "Cache hit rate: %d%%",

        "stat_sent" to "Sent",
        "stat_lost" to "Lost",
        "stat_bsent" to "Sent",
        "stat_brecv" to "Received",
        "stat_total" to "Total",
        "stat_avg" to "Avg",
        "update_open" to "Open",
        "list_dns" to "DNS list",
        "dlg_connect_first" to "Please select a DNS first",
        "dlg_connect_err" to "Connection error:",
        "dlg_conn_failed" to "Connection failed. Try again.",
        "dlg_disc_failed" to "Disconnect failed, try again",
        "update_new" to "Update available",
        "update_ready" to "Ready to install",
        "update_download" to "Download",
        "update_install" to "Install update",
        "update_later" to "Later",
        "qs_title" to "Quick access",
        "qs_msg" to "Add a quick connect/disconnect tile to the notification shade? One tap, without opening the app.",
        "qs_enable" to "Enable",

        "notif_connecting" to "Connecting to",
        "notif_disconnect" to "Disconnect",
        "notif_active" to "Kochak DNS is active",
        "notif_connected_to" to "Connected to"
    )

    /** ترجمه‌ی کلید به زبان فعلی (fallback به فارسی). */
    fun t(isFa: Boolean, key: String): String =
        (if (isFa) FA else EN)[key] ?: FA[key] ?: key
}

/** میان‌بر راحت: context.str("key") رشته‌ی محلی‌شده را برمی‌گرداند. */
fun Context.str(key: String): String = I18n.t(AppLang.isFa(this), key)
