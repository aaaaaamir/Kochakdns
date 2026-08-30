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
        // آیکون‌های وکتور (سبک Material، رنگ خاکستری هماهنگ با DnsActivity)
        private const val ICON_BLOCK = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z"
        private const val ICON_TUNNEL = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z"
        private const val ICON_LOCK = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
        private const val ICON_PERCENT = "M7.5,11C9.43,11 11,9.43 11,7.5S9.43,4 7.5,4 4,5.57 4,7.5 5.57,11 7.5,11zM7.5,6C8.33,6 9,6.67 9,7.5S8.33,9 7.5,9 6,8.33 6,7.5 6.67,6 7.5,6zM16.5,20c1.93,0 3.5,-1.57 3.5,-3.5s-1.57,-3.5 -3.5,-3.5 -3.5,1.57 -3.5,3.5 1.57,3.5 3.5,3.5zM16.5,17c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5 -1.5,-0.67 -1.5,-1.5 0.67,-1.5 1.5,-1.5zM19,5L5,19"
        private const val ICON_NOTIFICATION = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
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
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.WHITE)
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
            settingSwitch(
                title = "مسدود کردن اینترنت برنامه‌های تونل‌نشده",
                subtitle = "برنامه‌هایی که از لیست «برنامه‌های تونل شده» انتخاب نکردی، به اینترنت دسترسی نداشته باشن",
                iconPath = ICON_BLOCK,
                initial = AppSettings.isBlockNonTunneledEnabled(this)
            ) { checked ->
                AppSettings.setBlockNonTunneledEnabled(this, checked)
                restartVpnIfActive()
            }
        )

        list.addView(
            settingSwitch(
                title = "تونل کامل",
                subtitle = "به‌جای فقط DNS، کل ترافیک برنامه‌های انتخاب‌شده از تونل رد بشه و برنامه‌های انتخاب‌نشده در حین اتصال مسدود بشن",
                iconPath = ICON_TUNNEL,
                initial = AppSettings.isFullTunnelEnabled(this)
            ) { checked ->
                AppSettings.setFullTunnelEnabled(this, checked)
                restartVpnIfActive()
            }
        )

        list.addView(
            settingSwitch(
                title = "مسدودسازی از طریق Always-on VPN",
                subtitle = "قابل‌اعتمادترین روش: مسدودسازی برنامه‌های انتخاب‌نشده توسط سیستم اندروید. نیاز به فعال‌سازی Always-on VPN و «Block connections without VPN» دارد",
                iconPath = ICON_LOCK,
                initial = AppSettings.isLockdownBlockEnabled(this)
            ) { checked ->
                AppSettings.setLockdownBlockEnabled(this, checked)
                restartVpnIfActive()
            }
        )

        list.addView(
            settingSwitch(
                title = "نمایش درصد پکت‌ها",
                subtitle = "درصد موفقیت ارسال پکت‌ها روی کارت هر DNS نشون داده بشه",
                iconPath = ICON_PERCENT,
                initial = AppSettings.isShowPacketPercentEnabled(this)
            ) { checked ->
                AppSettings.setShowPacketPercentEnabled(this, checked)
            }
        )

        list.addView(
            settingSwitch(
                title = "نمایش نوتیفیکیشن",
                subtitle = "اندروید موقع اتصال VPN همیشه یک نوتیفیکیشن مقیم نشان می‌دهد؛ این گزینه فقط اهمیتش را کم می‌کند",
                iconPath = ICON_NOTIFICATION,
                initial = AppSettings.isShowNotificationEnabled(this)
            ) { checked ->
                AppSettings.setShowNotificationEnabled(this, checked)
            }
        )

        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        setContentView(root)
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

    private fun settingSwitch(
        title: String,
        subtitle: String,
        iconPath: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 24, 28, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#2A2A3E"))
                }
            }

            // آیکون خاکستری هماهنگ با DnsActivity
            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(iconPath, Color.parseColor(ICON_GRAY), 44))
            })

            val textColumn = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 20
                    marginEnd = 12
                }
            }
            textColumn.addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })
            textColumn.addView(TextView(this@SettingsActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#888888"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
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
