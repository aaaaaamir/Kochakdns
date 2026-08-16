package com.example.kochakdns

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

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
                subtitle = "برنامه‌هایی که از لیست «برنامه‌های تونل شده» انتخاب نکردی، اصلاً به اینترنت دسترسی نداشته باشن",
                initial = AppSettings.isBlockNonTunneledEnabled(this)
            ) { checked ->
                AppSettings.setBlockNonTunneledEnabled(this, checked)
            }
        )

        list.addView(
            settingSwitch(
                title = "نمایش درصد پکت‌ها",
                subtitle = "درصد موفقیت ارسال پکت‌ها روی کارت هر DNS نشون داده بشه",
                initial = AppSettings.isShowPacketPercentEnabled(this)
            ) { checked ->
                AppSettings.setShowPacketPercentEnabled(this, checked)
            }
        )

        list.addView(
            settingSwitch(
                title = "نمایش نوتیفیکیشن",
                subtitle = "توجه: اندروید موقع اتصال VPN همیشه یک نوتیفیکیشن مقیم نشون می‌ده؛ این گزینه فقط اهمیت/برجستگیش رو کم می‌کنه، کاملاً مخفیش نمی‌کنه",
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

    private fun settingSwitch(
        title: String,
        subtitle: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 28, 28, 28)
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

            val textColumn = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
