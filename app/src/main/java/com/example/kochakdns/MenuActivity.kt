package com.example.kochakdns

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.PathParser

/**
 * مدیریت محتوای منوی کشویی به همراه آیکون‌های وکتور نرم
 */
class MenuActivity(private val host: DnsActivity) {

    companion object {
        // مسیرهای SVG وکتور با گوشه‌های نرم
        private const val PATH_SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.33 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94l-0.36,-2.54c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41l-0.36,2.54c-0.59,0.24 -1.13,0.57 -1.62,0.94l-2.39,-0.96c-0.22,-0.08 -0.47,0 -0.59,0.22l-1.92,3.32c-0.12,0.2 -0.07,0.47 0.12,0.61l2.03,1.58c-0.05,0.3 -0.09,0.63 -0.09,0.94s0.04,0.64 0.09,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.2 0.07,-0.47 -0.12,-0.61l-2.01,-1.58zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6s-1.62,3.6 -3.6,3.6z"
        private const val PATH_IPV6 = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-1,17.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zm6.9,-2.54c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"
        private const val PATH_TUNNEL_APPS = "M4,11h6a1,1 0,0,0 1,-1V4a1,1 0,0,0 -1,-1H4a1,1 0,0,0 -1,1v6a1,1 0,0,0 1,1zm10,0h6a1,1 0,0,0 1,-1V4a1,1 0,0,0 -1,-1h-6a1,1 0,0,0 -1,1v6a1,1 0,0,0 1,1zM4,21h6a1,1 0,0,0 1,-1v-6a1,1 0,0,0 -1,-1H4a1,1 0,0,0 -1,1v6a1,1 0,0,0 1,1zm10,0h6a1,1 0,0,0 1,-1v-6a1,1 0,0,0 -1,-1h-6a1,1 0,0,0 -1,1v6a1,1 0,0,0 1,1z"
        private const val PATH_ABOUT = "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm0,18c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
    }

    fun buildView(onItemClick: () -> Unit): LinearLayout {
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ۱. گزینه‌ی تنظیمات
        container.addView(
            menuItem("تنظیمات", PATH_SETTINGS) {
                onItemClick()
                host.startActivity(Intent(host, SettingsActivity::class.java))
            }
        )

        // ۲. گزینه‌ی سوئیچ خاموش/روشن IPv6
        val prefs = host.getSharedPreferences("dns_prefs", Context.MODE_PRIVATE)
        val isIpv6Enabled = prefs.getBoolean("ipv6_enabled", true)

        container.addView(
            menuToggleItem("پشتیبانی از IPv6", PATH_IPV6, isIpv6Enabled) { isChecked ->
                prefs.edit().putBoolean("ipv6_enabled", isChecked).apply()
                // MyVpnService خودش موقع اتصال بعدی این مقدار رو می‌خونه؛ نیازی
                // به هیچ کار اضافه‌ای اینجا نیست.
            }
        )

        // ۳. گزینه‌ی برنامه‌های تونل شده
        container.addView(
            menuItem("برنامه‌های تونل شده", PATH_TUNNEL_APPS) {
                onItemClick()
                host.startActivity(Intent(host, TunnelAppsActivity::class.java))
            }
        )

        // ۴. گزینه‌ی درباره ما (در انتهای لیست)
        container.addView(
            menuItem("درباره ما", PATH_ABOUT) {
                onItemClick()
                host.startActivity(Intent(host, AboutActivity::class.java))
            }
        )

        return container
    }

    /**
     * ساخت آیتم استاندارد کلیک‌پذیر منو همراه با آیکون
     */
    private fun menuItem(title: String, pathData: String, onClick: () -> Unit): TextView {
        return TextView(host).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 28, 32, 28)

            val iconDrawable = createVectorDrawable(pathData, Color.parseColor("#4C8DFF"), 52)
            setCompoundDrawablesWithIntrinsicBounds(iconDrawable, null, null, null)
            compoundDrawablePadding = 24

            applyRippleBackground(this)

            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /**
     * ساخت آیتم سوئیچ‌دار (خاموش/روشن) برای گزینه‌هایی مثل IPv6
     */
    private fun menuToggleItem(
        title: String,
        pathData: String,
        initialState: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 16, 32, 16)
            applyRippleBackground(this)

            // آیکون
            val iconView = ImageView(host).apply {
                setImageDrawable(createVectorDrawable(pathData, Color.parseColor("#4C8DFF"), 52))
            }

            // متن
            val textView = TextView(host).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = 24
                }
            }

            // دکمه سوئیچ خاموش/روشن
            val switchView = Switch(host).apply {
                isChecked = initialState
                setOnCheckedChangeListener { _, isChecked ->
                    onToggle(isChecked)
                }
            }

            addView(iconView)
            addView(textView)
            addView(switchView)

            isClickable = true
            isFocusable = true
            setOnClickListener {
                switchView.toggle()
            }
        }
    }

    /**
     * افکت انیمیشن کلیک (Ripple) با گوشه‌های انحنادار
     */
    private fun applyRippleBackground(view: android.view.View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val shape = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.TRANSPARENT)
            }
            view.background = shape
            view.foreground = RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
                shape,
                null
            )
        }
    }

    /**
     * مبدل کد وکتور (SVG Path) به Drawable بدون نیاز به XML
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
