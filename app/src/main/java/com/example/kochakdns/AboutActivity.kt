package com.example.kochakdns

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.PathParser

/**
 * صفحه «درباره ما» برنامه‌ی کُچک دی ان اس
 * تمام آیکون‌های وکتور به صورت درون‌برنامه‌ای (Programmatic SVG) در همین کد قرار دارند.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        // SVG Path Data برای آیکون‌ها
        private const val PATH_ARROW_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"
        private const val PATH_LOGO = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-1,17.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zm6.9,-2.54c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"
        private const val PATH_GLOBE = "M12,2A10,10 0,0 0,2 12A10,10 0,0 0,12 22A10,10 0,0 0,22 12A10,10 0,0 0,12 2M11,4.07C7.38,4.53 4.53,7.38 4.07,11H11V4.07M13,4.07V11H19.93C19.47,7.38 16.62,4.53 13,4.07M4.07,13C4.53,16.62 7.38,19.47 11,19.93V13H4.07M13,19.93C16.62,19.47 19.47,16.62 19.93,13H13V19.93Z"
        private const val PATH_TELEGRAM = "M9.78,18.65L10.06,14.42L17.74,7.5C18.08,7.19 17.67,7.02 17.22,7.31L7.74,13.3L3.64,12.03C2.75,11.75 2.74,11.14 3.84,10.71L19.86,4.54C20.6,4.27 21.25,4.71 21,5.83L18.28,18.64C18.08,19.55 17.54,19.77 16.78,19.34L12.64,16.29L10.64,18.22C10.42,18.44 10.23,18.65 9.78,18.65Z"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
        }

        // دکمه بازگشت با آیکون وکتور
        val backButton = ImageView(this).apply {
            setImageDrawable(createVectorDrawable(PATH_ARROW_BACK, Color.WHITE, 64))
            setPadding(32, 32, 32, 32)
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = 32
                leftMargin = 16
            }
            setOnClickListener { finish() }
        }

        // لایوت اصلی محتوا
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(48, 48, 48, 48)
        }

        // آیکون اصلی / لوگوی برنامه
        content.addView(ImageView(this).apply {
            setImageDrawable(createVectorDrawable(PATH_LOGO, Color.parseColor("#4C8DFF"), 140))
            layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                bottomMargin = 24
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

        // نسخه برنامه
        content.addView(TextView(this).apply {
            text = "نسخه ۱.۰"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 40)
        })

        // تابع ساخت دکمه‌های لینک‌دار همراه با آیکون SVG
        fun createLinkItem(title: String, url: String, pathData: String): TextView {
            return TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)

                // ساخت آیکون و ست کردن آن در سمت چپ
                val iconDrawable = createVectorDrawable(pathData, Color.parseColor("#4C8DFF"), 56)
                setCompoundDrawablesWithIntrinsicBounds(iconDrawable, null, null, null)
                compoundDrawablePadding = 24

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                }
            }
        }

        // افزودن لینک‌ها همراه با آیکون‌های وکتور
        content.addView(createLinkItem("وب‌سایت kodns.ir", "https://kodns.ir", PATH_GLOBE))
        content.addView(createLinkItem("وب‌سایت idothis.ir", "https://idothis.ir", PATH_GLOBE))
        content.addView(createLinkItem("ارتباط در تلگرام: @u_amir_d", "https://t.me/u_amir_d", PATH_TELEGRAM))

        root.addView(content)
        root.addView(backButton)
        setContentView(root)
    }

    /**
     * مبدل رشته SVG Path به Drawable در محیط برنامه
     */
    private fun createVectorDrawable(pathData: String, color: Int, sizePx: Int): Drawable {
        val path = PathParser.createPathFromPathData(pathData)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        return object : Drawable() {
            override fun draw(canvas: Canvas) {
                val saveCount = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                val scaleX = bounds.width().toFloat() / 24f
                val scaleY = bounds.height().toFloat() / 24f
                canvas.scale(scaleX, scaleY)
                canvas.drawPath(path, paint)
                canvas.restoreToCount(saveCount)
            }

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Suppress("DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
}
