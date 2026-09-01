package com.example.kochakdns

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * صفحه «درباره ما» — با تم کارتی خاکستری هماهنگ با بقیه برنامه.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        // آیکون‌های وکتور (سبک Material)
        private const val PATH_ARROW_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
        private const val PATH_LOGO = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-1,17.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zm6.9,-2.54c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"
        private const val PATH_GLOBE = "M12,2A10,10 0,0 0,2 12A10,10 0,0 0,12 22A10,10 0,0 0,22 12A10,10 0,0 0,12 2M11,4.07C7.38,4.53 4.53,7.38 4.07,11H11V4.07M13,4.07V11H19.93C19.47,7.38 16.62,4.53 13,4.07M4.07,13C4.53,16.62 7.38,19.47 11,19.93V13H4.07M13,19.93C16.62,19.47 19.47,16.62 19.93,13H13V19.93Z"
        private const val ICON_CHEVRON = "M9,18l6,-6 -6,-6"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
        }

        // نوار بالا: دکمه بازگشت + عنوان
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 56, 24, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(buildVectorDrawable(PATH_ARROW_BACK, Color.WHITE, 56))
            setPadding(20, 16, 20, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "درباره ما"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
        })
        root.addView(header)

        // محتوای اصلی
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 120
            }
            setPadding(40, 16, 40, 40)
        }

        // لوگوی برنامه
        content.addView(ImageView(this).apply {
            setImageDrawable(buildVectorDrawable(PATH_LOGO, Color.parseColor("#4C8DFF"), 130))
            layoutParams = LinearLayout.LayoutParams(130, 130).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 20
            }
        })

        // عنوان برنامه
        content.addView(TextView(this).apply {
            text = "کُچک دی ان اس"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        // نسخه برنامه (واقعی، از اطلاعات بسته)
        content.addView(TextView(this).apply {
            text = "نسخه ${getAppVersion()}"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 36)
        })

        // لینک‌ها در کارت‌های خاکستری
        content.addView(linkCard("وب‌سایت kodns.ir", "https://kodns.ir"))
        content.addView(linkCard("وب‌سایت idothis.ir", "https://idothis.ir"))

        root.addView(content)
        setContentView(root)
    }

    /** خواندن ورژن واقعی برنامه از PackageManager (versionName در build.gradle). */
    private fun getAppVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /** کارت لینک با آیکون خاکستری و فلش، هماهنگ با تم کارتی برنامه. */
    private fun linkCard(title: String, url: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 22, 20, 22)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E1E2E"))
                    cornerRadius = 24f
                    setStroke(2, Color.parseColor("#2A2A3E"))
                }
                val mask = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.WHITE)
                }
                foreground = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                    null,
                    mask
                )
            }

            addView(ImageView(this@AboutActivity).apply {
                setImageDrawable(buildVectorDrawable(PATH_GLOBE, Color.parseColor("#A0A0AC"), 40))
            })

            addView(TextView(this@AboutActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                }
            })

            addView(ImageView(this@AboutActivity).apply {
                setImageDrawable(buildVectorDrawable(ICON_CHEVRON, Color.parseColor("#666680"), 36))
            })

            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
        }
    }
}
