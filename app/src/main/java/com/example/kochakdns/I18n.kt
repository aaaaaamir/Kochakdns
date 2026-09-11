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

        // عنوان‌ها
        "settings_title" to "تنظیمات",

        // دسته‌بندی تنظیمات
        "sec_network" to "اتصال و سرعت",
        "sec_display" to "نمایش",
        "sec_general" to "عمومی",

        // کش DNS
        "set_dns_cache" to "کش DNS",
        "set_dns_cache_sub" to "پاسخ‌های تکراری و نامعتبر DNS از حافظه خوانده می‌شوند و پاسخ‌های قدیمی تا رسیدن پاسخ تازه سرو می‌شوند. روشن بودنش یعنی جواب سریع‌تر و رفت‌وآمد کمتر به سرور.",

        // TCP fallback
        "set_tcp_fallback" to "پشتیبانی TCP (Fallback)",
        "set_tcp_fallback_sub" to "اگر UDP جواب ندهد، همان درخواست با TCP (پورت ۵۳) دوباره امتحان می‌شود. در شبکه‌هایی که UDP مختل است، خاموش کردنش یعنی پکت گم‌شده بیشتر و سایت‌ها باز نمی‌شوند. فقط وقتی خاموشش کن که مطمئنی UDP سالم است.",

        // همزمانی UDP
        "set_udp_conc" to "درخواست‌های همزمان UDP",
        "set_udp_conc_sub" to "چند درخواست DNS همزمان با UDP به سرور ارسال شود. بیشتر = در مواقع شلوغ سریع‌تر، ولی فشار بیشتر روی شبکه و در شبکه‌های ضعیف احتمال گم شدن پاکت بالا می‌رود. کمتر = پایدارتر، ولی ممکن است صف و تأخیر ایجاد شود.",

        // همزمانی TCP
        "set_tcp_conc" to "درخواست‌های همزمان TCP",
        "set_tcp_conc_sub" to "فقط وقتی UDP جواب ندهد از TCP استفاده می‌شود. بیشتر = اتصال بیشتری اشغال می‌شود و ممکن است کندی ایجاد کند. کمتر = صف و تأخیر در مواقع خرابی UDP.",

        // تایم‌اوت تطبیقی
        "set_adaptive_timeout" to "تایم‌اوت تطبیقی",
        "set_adaptive_timeout_sub" to "مدت انتظار هر درخواست به‌صورت خودکار با سرعت سرور تنظیم می‌شود: سرور سریع = انتظار کوتاه، سرور کند = انتظار بلند. در شبکه‌های معمولی عالی است؛ اما در شبکه‌های ناپایدار (اختلال/فیلترینگ) ممکن است پاسخ‌های دیررسیده را زود قطع کند و پکت گم‌شده را زیاد کند. در آن حالت خاموشش کن و یک تایم‌اوت ثابت بگذار.",

        // نقطه شروع تایم‌اوت
        "set_timeout_start" to "نقطه شروع تایم‌اوت",
        "set_timeout_start_sub" to "تایم‌اوت اولیه و سقف هر درخواست. کمترش = سریع‌تر متوجه شکست می‌شوی ولی پاسخ‌های سالمِ دیررسیده هم قطع می‌شوند (پکت گم‌شده بیشتر). بیشترش = صبورتر و پکت گم‌شده کمتر، ولی درخواست‌های واقعاً مرده بیشتر معطل می‌مانند.",

        // کف تایم‌اوت
        "set_timeout_floor" to "کف تایم‌اوت",
        "set_timeout_floor_sub" to "کمترین تایم‌اوتی که به سرورهای سریع داده می‌شود. هرچه کمتر باشد، سرور سریع زودتر رها می‌شود؛ ولی زیر ۲ ثانیه در شبکه‌های ناپایدار ممکن است پاسخِ سالمِ کمی دیر را قطع کند و پکت گم‌شده را زیاد کند.",

        // تایم‌اوت ثابت
        "set_fixed_timeout" to "تایم‌اوت ثابت",
        "set_fixed_timeout_sub" to "وقتی تایم‌اوت تطبیقی خاموش باشد، همه‌ی درخواست‌ها با همین مدت منتظر جواب می‌مانند. زیاد = پکت گم‌شده کمتر، ولی درخواست‌های مرده معطل می‌مانند. کم = سریع‌تر، ولی پاسخ‌های دیر ممکن است گم شوند.",

        // نمایش
        "set_show_percent" to "نمایش درصد پکت‌ها",
        "set_show_percent_sub" to "درصد موفقیت ارسال پکت‌ها روی کارت هر DNS. فقط نمایشی است و روی کارکرد VPN اثری ندارد.",
        "set_show_notif" to "نمایش اطلاعات در نوتیفیکیشن",
        "set_show_notif_sub" to "وقتی خاموش باشد، نوتیفیکیشن فقط وضعیت اتصال را نشان می‌دهد. فقط نمایشی است و روی کارکرد VPN اثری ندارد.",

        // عمومی
        "set_quick_settings" to "دسترسی سریع (Quick Settings)",
        "set_language" to "زبان برنامه",
        "set_about" to "درباره ما",

        // گزینه‌های زبان
        "lang_device" to "زبان دستگاه",
        "lang_fa" to "فارسی",
        "lang_en" to "English",

        // دیالوگ‌ها
        "cancel" to "لغو",
        "close" to "بستن",
        "ok" to "تأیید",
        "default_label" to "پیش‌فرض",
        "custom_label" to "دستی وارد کن...",
        "enter_value" to "مقدار را وارد کن",
        "invalid_number" to "یک عدد در بازه مجاز وارد کن",
        "range_between" to "بین %d تا %d",

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
        "notif_connected_to" to "متصل به",

        // درباره ما
        "about_title" to "درباره ما",
        "app_brand" to "کُچک دی ان اس",
        "about_version" to "نسخه",
        "about_website" to "وب‌سایت",

        // صفحه تونل برنامه‌ها
        "apps_back" to "بازگشت",
        "apps_search" to "جستجو",
        "apps_search_placeholder" to "جستجو در برنامه‌ها...",
        "apps_clear" to "پاک کردن",
        "apps_close_search" to "بستن جستجو",
        "apps_loading" to "در حال دریافت لیست برنامه‌ها...",
        "apps_none" to "برنامه‌ای یافت نشد",
        "apps_selected_count" to "%d از %d برنامه انتخاب شده",
        "apps_select_all" to "انتخاب همه",
        "apps_clear_all" to "لغو همه",
        "apps_system" to "سیستمی",
        "apps_load_error" to "خطا در دریافت لیست برنامه‌ها"
    )

    private val EN = mapOf(
        "menu_settings" to "Settings",
        "menu_ipv6" to "IPv6 support",
        "menu_tunnel_apps" to "Tunneled apps",

        "settings_title" to "Settings",

        "sec_network" to "Connection & speed",
        "sec_display" to "Display",
        "sec_general" to "General",

        "set_dns_cache" to "DNS cache",
        "set_dns_cache_sub" to "Repeated and invalid DNS answers are served from memory, and stale answers are served until a fresh one arrives. Keeping it on means faster replies and fewer round-trips to the server.",

        "set_tcp_fallback" to "TCP fallback",
        "set_tcp_fallback_sub" to "If UDP gets no answer, the same request is retried over TCP (port 53). On networks where UDP is blocked, turning it off means more packet loss and pages failing to load. Only disable it if you are sure UDP is healthy.",

        "set_udp_conc" to "Concurrent UDP requests",
        "set_udp_conc_sub" to "How many DNS requests are sent over UDP at the same time. More = faster under load, but more pressure on the network and higher packet loss on weak links. Fewer = more stable, but may cause queueing and latency.",

        "set_tcp_conc" to "Concurrent TCP requests",
        "set_tcp_conc_sub" to "TCP is only used when UDP fails. More = uses more connections and may feel slower. Fewer = queueing and delay when UDP is down.",

        "set_adaptive_timeout" to "Adaptive timeout",
        "set_adaptive_timeout_sub" to "The wait time per request adjusts to each server's speed: fast server = short wait, slow server = long wait. Great on normal networks, but on unstable links (throttling/filtering) it may cut off healthy late replies and increase packet loss — in that case turn it off and set a fixed timeout.",

        "set_timeout_start" to "Timeout start",
        "set_timeout_start_sub" to "The initial and maximum timeout per request. Lower = you notice failures faster, but healthy late replies also get cut (more packet loss). Higher = more patient and less loss, but truly dead requests wait longer.",

        "set_timeout_floor" to "Timeout floor",
        "set_timeout_floor_sub" to "The lowest timeout given to fast servers. Lower means fast servers are abandoned sooner, but below 2 seconds on unstable networks may cut off a healthy late reply and raise packet loss.",

        "set_fixed_timeout" to "Fixed timeout",
        "set_fixed_timeout_sub" to "When adaptive timeout is off, every request waits this long for an answer. Higher = less packet loss but dead requests linger. Lower = faster, but late replies may be lost.",

        "set_show_percent" to "Show packet percentage",
        "set_show_percent_sub" to "Success percentage shown on each DNS card. Display only — it does not affect the VPN.",

        "set_show_notif" to "Show info in notification",
        "set_show_notif_sub" to "When off, the notification only shows connection state. Display only — it does not affect the VPN.",

        "set_quick_settings" to "Quick Settings tile",
        "set_language" to "App language",
        "set_about" to "About",

        "lang_device" to "Device language",
        "lang_fa" to "Persian",
        "lang_en" to "English",

        "cancel" to "Cancel",
        "close" to "Close",
        "ok" to "OK",
        "default_label" to "default",
        "custom_label" to "Enter manually...",
        "enter_value" to "Enter a value",
        "invalid_number" to "Enter a number within the allowed range",
        "range_between" to "between %d and %d",

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
        "notif_connected_to" to "Connected to",

        "about_title" to "About",
        "app_brand" to "Kochak DNS",
        "about_version" to "Version",
        "about_website" to "Website",

        "apps_back" to "Back",
        "apps_search" to "Search",
        "apps_search_placeholder" to "Search apps...",
        "apps_clear" to "Clear",
        "apps_close_search" to "Close search",
        "apps_loading" to "Loading app list...",
        "apps_none" to "No apps found",
        "apps_selected_count" to "%d of %d apps selected",
        "apps_select_all" to "Select all",
        "apps_clear_all" to "Clear all",
        "apps_system" to "System",
        "apps_load_error" to "Error loading app list"
    )

    /** ترجمه‌ی کلید به زبان فعلی (fallback به فارسی). */
    fun t(isFa: Boolean, key: String): String =
        (if (isFa) FA else EN)[key] ?: FA[key] ?: key
}

/** میان‌بر راحت: context.str("key") رشته‌ی محلی‌شده را برمی‌گرداند. */
fun Context.str(key: String): String = I18n.t(AppLang.isFa(this), key)
