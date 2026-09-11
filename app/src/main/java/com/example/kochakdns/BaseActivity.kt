package com.example.kochakdns

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * اعمال زبان برنامه روی context در همه‌ی نسخه‌های اندروید (minSdk 21).
 * وقتی زبان روی «دستگاه» باشد هیچ کاری نمی‌کند؛ وگرنه context را با locale
 * انتخابی wrap می‌کند تا همه‌ی رشته‌ها/چیدمان در همان زبان رندر شوند.
 */
object LocaleHelper {
    fun wrap(context: Context): Context {
        val lang = AppSettings.getLanguage(context)
        if (lang == AppLang.DEVICE) return context
        val locale = Locale(if (lang == AppLang.FA) "fa" else "en")
        return try {
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(android.os.LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            context.createConfigurationContext(config)
        } catch (_: Exception) {
            context
        }
    }
}

/** کلاس پایه‌ی همه‌ی اکتیویتی‌ها تا زبان برنامه قبل از ساخت UI اعمال شود. */
abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
