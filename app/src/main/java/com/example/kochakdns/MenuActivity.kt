package com.example.kochakdns

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView

/**
 * محتوای منوی کشویی، توی یک فایل جدا از DnsActivity.
 *
 * نکته‌ی فنی مهم: این کلاس یک Activity واقعی اندروید نیست — اندروید مدرن
 * اجازه نمی‌ده یک Activity واقعی رو مستقیم داخل درخت View یک Activity
 * دیگه امبد کنی (اون روش‌های قدیمی مثل ActivityGroup سال‌هاست منسوخ شدن).
 * برای همین این کلاس فقط وظیفه‌ش ساختن View آیتم‌های منوئه؛ DnsActivity
 * همون View رو می‌گیره و داخل پنجره‌ی کشویی خودش (drawerPanel) نشون می‌ده.
 * اگه بعداً آیتم‌های بیشتری خواستی، همه‌شون همین‌جا اضافه می‌شن، نه توی
 * DnsActivity.
 */
class MenuActivity(private val host: DnsActivity) {

    /**
     * @param onItemClick بعد از تپ روی هر آیتم صدا زده می‌شه (معمولاً برای
     * بستن دراور قبل از رفتن به صفحه‌ی بعدی)
     */
    fun buildView(onItemClick: () -> Unit): LinearLayout {
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(
            menuItem("درباره ما") {
                onItemClick()
                host.startActivity(Intent(host, AboutActivity::class.java))
            }
        )

        // آیتم‌های بعدی منو رو همین‌جا اضافه کن، مثلاً:
        // container.addView(menuItem("تنظیمات") { onItemClick(); host.startActivity(...) })

        return container
    }

    private fun menuItem(title: String, onClick: () -> Unit): TextView {
        return TextView(host).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(28, 28, 28, 28)
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor(Color.TRANSPARENT)
                }
                background = shape
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                    shape,
                    null
                )
            }
            setOnClickListener { onClick() }
        }
    }
}
