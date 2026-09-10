package com.example.kochakdns

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        // آیکون بازگشت (فلش Material)
        private const val ICON_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

        // آیکون‌های وکتور (سبک Material، رنگ خاکستری)
        private const val ICON_CACHE = "M12,4V1L7,5l5,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C20.42,14.78 21,13.47 21,12 21,7.03 16.97,3 12,3zM6,12c0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.58,9.22 4,10.53 4,12c0,4.97 4.03,9 9,9v3l5,-4 -5,-4v3c-3.31,0 -6,-2.69 -6,-6z"
        private const val ICON_PERCENT = "M7.5,11C9.43,11 11,9.43 11,7.5S9.43,4 7.5,4 4,5.57 4,7.5 5.57,11 7.5,11zM7.5,6C8.33,6 9,6.67 9,7.5S8.33,9 7.5,9 6,8.33 6,7.5 6.67,6 7.5,6zM16.5,20c1.93,0 3.5,-1.57 3.5,-3.5s-1.57,-3.5 -3.5,-3.5 -3.5,1.57 -3.5,3.5 1.57,3.5 3.5,3.5zM16.5,17c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5 -1.5,-0.67 -1.5,-1.5 0.67,-1.5 1.5,-1.5zM19,5L5,19"
        private const val ICON_NOTIFICATION = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
        private const val ICON_ABOUT = "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm0,18c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
        private const val ICON_CHEVRON = "M9,18l6,-6 -6,-6"
        // آیکون دسترسی سریع (اسلایدر تنظیم — Material «tune»)
        private const val ICON_TUNE = "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"
        // آیکون پشتیبانی TCP (رفرش/اتصال مجدد — Material «refresh»)
        private const val ICON_TCP = "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
    }

    // خط زنده‌ی «نرخ پاسخ از کش» داخل کارت کش DNS (هر چند ثانیه به‌روز می‌شود)
    private lateinit var cacheHitRateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 56, 24, 24)
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(buildVectorDrawable(ICON_BACK, Color.WHITE, 56))
            setPadding(24, 16, 24, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "تنظیمات"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
        })
        column.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }

        // خط زنده‌ی «نرخ پاسخ از کش» که داخل کارت کش DNS قرار می‌گیرد
        cacheHitRateText = TextView(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        updateCacheHitRate()
        lifecycleScope.launch {
            while (isActive) {
                delay(1500)
                updateCacheHitRate()
            }
        }

        list.addView(
            glassSwitch(
                title = "کش DNS",
                subtitle = "پاسخ‌های تکراری و نامعتبر DNS از حافظه خوانده می‌شوند و پاسخ‌های قدیمی تا رسیدن پاسخ تازه سرو می‌شوند",
                iconPath = ICON_CACHE,
                initial = AppSettings.isDnsCacheEnabled(this),
                liveLine = cacheHitRateText
            ) { checked ->
                AppSettings.setDnsCacheEnabled(this, checked)
                // اعمال واقعی کش موقع بازگشت به صفحه‌ی اصلی انجام می‌شود (وصل مجدد)
            }
        )

        list.addView(
            glassSwitch(
                title = "پشتیبانی TCP (Fallback)",
                subtitle = "اگه پرس‌وجوی UDP جواب نده، همون پرس‌وجو با TCP (پورت ۵۳) دوباره امتحان می‌شه — دور زدن اختلال ISP روی UDP",
                iconPath = ICON_TCP,
                initial = AppSettings.isTcpFallbackEnabled(this)
            ) { checked ->
                AppSettings.setTcpFallbackEnabled(this, checked)
                // اعمال واقعی موقع بازگشت به صفحه‌ی اصلی انجام می‌شود (وصل مجدد)
            }
        )

        list.addView(
            glassSwitch(
                title = "نمایش درصد پکت‌ها",
                subtitle = "درصد موفقیت ارسال پکت‌ها روی کارت هر DNS نشون داده بشه",
                iconPath = ICON_PERCENT,
                initial = AppSettings.isShowPacketPercentEnabled(this)
            ) { checked ->
                AppSettings.setShowPacketPercentEnabled(this, checked)
            }
        )

        list.addView(
            glassSwitch(
                title = "نمایش اطلاعات در نوتیفیکیشن",
                subtitle = "وقتی خاموش باشه، نوتیفیکیشن VPN دیگه اطلاعات پکت‌ها و حجم دیتای منتقل‌شده رو نشون نمی‌ده",
                iconPath = ICON_NOTIFICATION,
                initial = AppSettings.isShowNotificationInfoEnabled(this)
            ) { checked ->
                AppSettings.setShowNotificationInfoEnabled(this, checked)
                // نوتیفیکیشن هر ۲ ثانیه خودش به‌روز می‌شود؛ نیازی به وصل مجدد نیست
            }
        )

        // کاشی‌های Quick Settings فقط از اندروید ۷ (API 24) به بعد وجود دارند
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            list.addView(
                glassCard(
                    title = "دسترسی سریع (Quick Settings)",
                    iconPath = ICON_TUNE,
                    onClick = {
                        AppSettings.setQsTileEnabled(this, true)
                        if (!QuickSettingsTileService.requestAddTile(this)) {
                            Toast.makeText(
                                this,
                                "نوار اعلان را بکش پایین ← ویرایش (مداد) ← کاشی «Kochak» را اضافه کن",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            )
        }

        // ===== درباره ما =====
        list.addView(
            glassCard(
                title = "درباره ما",
                iconPath = ICON_ABOUT,
                onClick = { startActivity(Intent(this, AboutActivity::class.java)) }
            )
        )

        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        setContentView(root)
    }

    /** به‌روزرسانی خط «نرخ پاسخ از کش» از آمار زنده‌ی سرویس VPN. */
    private fun updateCacheHitRate() {
        if (!::cacheHitRateText.isInitialized) return
        val rate = VpnStats.dnsCacheHitRate()
        val cacheOn = AppSettings.isDnsCacheEnabled(this)
        cacheHitRateText.text = when {
            !cacheOn -> "نرخ پاسخ از کش: خاموش"
            rate == null -> "نرخ پاسخ از کش: --"
            else -> "نرخ پاسخ از کش: ${Math.round(rate)}٪"
        }
        cacheHitRateText.setTextColor(
            when {
                rate != null && rate >= 50 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#8A8A9A")
            }
        )
    }

    /** کارت کلیک‌پذیر شیشه‌ای (مثل «درباره ما») با فلش سمت چپ. */
    private fun glassCard(
        title: String,
        iconPath: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 22, 20, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
            applyGlassBackground(this)
            isClickable = true
            isFocusable = true

            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(iconPath, Color.parseColor("#A0A0AC"), 40))
            })

            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                }
            })

            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(ICON_CHEVRON, Color.parseColor("#666680"), 36))
            })

            setOnClickListener { onClick() }
        }
    }

    /** پس‌زمینه شیشه‌ای (نیمه‌شفاف + حاشیه روشن). */
    private fun applyGlassBackground(view: android.view.View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(1, Color.parseColor("#22FFFFFF"))
            }
        }
    }

    private fun glassSwitch(
        title: String,
        subtitle: String,
        iconPath: String,
        initial: Boolean,
        liveLine: TextView? = null,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 22, 20, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
            applyGlassBackground(this)
            isClickable = true
            isFocusable = true

            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(iconPath, Color.parseColor("#A0A0AC"), 40))
            })

            val textColumn = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                    marginEnd = 10
                }
            }
            textColumn.addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textColumn.addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#8A8A9A"))
                textSize = 11f
                setPadding(0, 6, 0, 0)
            })

            // خط زنده (مثلاً نرخ پاسخ از کش) — در صورت نیاز زیر توضیح اضافه می‌شود
            if (liveLine != null) {
                textColumn.addView(liveLine)
            }

            val switchView = AnimatedSwitchView(this@SettingsActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(26))
                setChecked(initial)
                onCheckedChangeListener = { checked -> onChange(checked) }
            }

            addView(textColumn)
            addView(switchView)

            // لمس هرجای کارت = روشن/خاموش
            setOnClickListener { switchView.performClick() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
