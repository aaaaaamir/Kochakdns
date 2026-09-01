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
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        // آیکون بازگشت (فلش Material)
        private const val ICON_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

        // آیکون‌های وکتور (سبک Material، رنگ خاکستری)
        private const val ICON_TUNNEL = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z"
        private const val ICON_PERCENT = "M7.5,11C9.43,11 11,9.43 11,7.5S9.43,4 7.5,4 4,5.57 4,7.5 5.57,11 7.5,11zM7.5,6C8.33,6 9,6.67 9,7.5S8.33,9 7.5,9 6,8.33 6,7.5 6.67,6 7.5,6zM16.5,20c1.93,0 3.5,-1.57 3.5,-3.5s-1.57,-3.5 -3.5,-3.5 -3.5,1.57 -3.5,3.5 1.57,3.5 3.5,3.5zM16.5,17c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5 -1.5,-0.67 -1.5,-1.5 0.67,-1.5 1.5,-1.5zM19,5L5,19"
        private const val ICON_NOTIFICATION = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
        private const val ICON_ABOUT = "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm0,18c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
        private const val ICON_CHEVRON = "M9,6l6,6 -6,6"
    }

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

        list.addView(
            glassSwitch(
                title = "تونل کامل",
                subtitle = "کل ترافیک برنامه‌های انتخاب‌شده (نه فقط DNS) از تونل رد بشه؛ برنامه‌های انتخاب‌نشده اینترنت معمولی دارن",
                iconPath = ICON_TUNNEL,
                initial = AppSettings.isFullTunnelEnabled(this)
            ) { checked ->
                AppSettings.setFullTunnelEnabled(this, checked)
                restartVpnIfActive()
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
                if (VpnStats.isVpnActive) {
                    startService(
                        Intent(this, MyVpnService::class.java).apply {
                            action = MyVpnService.ACTION_RESTART
                        }
                    )
                }
            }
        )

        // ===== درباره ما (منتقل‌شده از منوی کشویی) =====
        list.addView(aboutCard())

        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        setContentView(root)
    }

    /** کارت «درباره ما» — باز کردن AboutActivity. */
    private fun aboutCard(): LinearLayout {
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
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AboutActivity::class.java)) }

            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(ICON_ABOUT, Color.parseColor("#A0A0AC"), 40))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "درباره ما"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                }
            })
            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(ICON_CHEVRON, Color.parseColor("#555560"), 36))
            })
        }
    }

    /** اگر VPN الان وصله، تغییر تنظیمات فقط با ساخت دوباره‌ی تونل اعمال می‌شود. */
    private fun restartVpnIfActive() {
        if (!VpnStats.isVpnActive) return
        try {
            startService(
                Intent(this, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_RESTART
                }
            )
        } catch (_: Exception) {
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

            val switchView = Switch(this@SettingsActivity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }

            addView(textColumn)
            addView(switchView)
        }
    }
}
