package com.example.kochakdns

/**
 * ⚠️ فایل محلی — این فایل عمداً در .gitignore گذاشته شده و در مخزن گیت
 * commit نمی‌شود. اگر کلون تازه‌ای داری و این فایل نیست، از روی
 * ApiConfig.example.kt بسازش و مقادیر واقعی را بگذار، وگرنه build خطای
 * «unresolved reference: ApiConfig» می‌دهد (این خطای عمدی است تا نبودش لو نرود).
 *
 * صادقانه: این مقادیر داخل APK قابل استخراج‌اند (هر ابزار داسمبلری رشته‌ها را
 * می‌بیند؛ R8 فقط اسم‌ها را مبهم می‌کند). هدف از این فایل این است که کلیدها
 * در سورس/گیت‌هیست قابل‌خواندن نباشند، نه «رازش مطلق». راز واقعیِ سرور
 * (اگر لازم شد) باید سمت Worker بماند و اپ فقط توکن کوتاه‌عمر بگیرد.
 */
object ApiConfig {

    // ---- بک‌اند (Cloudflare Worker) ----
    const val BASE_URL = "https://kodns.ir"

    // مسیرهای API
    const val API_DNS_LIST = "/api/dns/list"
    const val API_DNS_PROFILE = "/api/dns/profile/"          // + name
    const val API_DNS_STATS = "/api/dns/stats"
    const val API_APP_INFO = "/api/app/info"                  // بررسی بروزرسانی (ورژن/حجم)
    const val API_APP_DOWNLOAD = "/api/app/download"          // دانلود APK
    const val API_ANNOUNCEMENT = "/api/app/announcement"      // اطلاعیه داخل برنامه
    const val USER_AGENT = "KochakDNS-Android-Client/1.0"

    // ---- آدرس‌های ظاهری (صفحه درباره‌ما) — اینجا نگه داشته می‌شوند تا دامنه
    //      فقط یک‌جا تعریف شود؛ راز نیستند ولی یک‌دستی تمیزتر است ----
    const val SITE_URL = "https://kodns.ir"
    const val SUPPORT_URL = "https://idothis.ir"

    // ---- پارامترهای رمزنگاری پاسخ API — باید دقیقاً با worker.js یکسان باشند ----
    // (چرخش کلید: اینجا + worker.js + انتشار APK جدید؛ تا برنامه‌های قدیمی
    //  آپدیت نشده‌اند رمزنگاری ربات را خاموش/روشن هماهنگ کن)
    const val ENC_PASSPHRASE = "kochak-dns-shared-secret-v1"
    const val ENC_SALT = "kochakdns::salt::2026"
    const val ENC_ITERATIONS = 10000
}
