package com.example.kochakdns

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

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
        // آیکون همزمانی (ردیف/صف — Material «layers»)
        private const val ICON_CONC = "M5,17h14v2H5zM5,11h14v2H5zM5,5h14v2H5z"
        // آیکون تایمر (ساعت/زمان‌سنج — Material «timer»)
        private const val ICON_TIMER = "M15,1H9v2h6V1zM11,14h2V8h-2V14zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"
        // آیکون زبان (ترجمه — Material «translate»)
        private const val ICON_LANG = "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM16.88,17l1.62,-4.33L20.12,17h-3.24z"
        // آیکون روش پینگ (کره/شبکه — Material «public»)
        private const val ICON_GLOBE = "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2L4.26,14zM5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56zM8.03,8H5.08c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96zM14.34,14H9.66c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2zM14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z"

        // مقادیر پیش‌فرض (برای نمایش برچسب «پیش‌فرض»)
        private const val DEFAULT_UDP = 8
        private const val DEFAULT_TCP = 4
        private const val DEFAULT_TIMEOUT_START = 8000
        private const val DEFAULT_TIMEOUT_FLOOR = 2000
        private const val DEFAULT_FIXED_TIMEOUT = 5000
    }

    // کارت سوییچ‌دار (برای تغییر وضعیت فعال/غیرفعال درجا)
    private class SwitchCard(val card: LinearLayout, val switch: AnimatedSwitchView)

    // کارت مقدار (برای به‌روزرسانی برچسب مقدار درجا)
    private class ValueCard(val card: LinearLayout, val valueView: TextView)

    // خط زنده‌ی «نرخ پاسخ از کش» داخل کارت کش DNS (هر چند ثانیه به‌روز می‌شود)
    private lateinit var cacheHitRateText: TextView
    private lateinit var root: FrameLayout

    // ارجاع‌ها برای به‌روزرسانی درجا (بدون rebuild صفحه)
    private lateinit var tcpFallbackCard: SwitchCard
    private lateinit var udpCard: ValueCard
    private lateinit var tcpConcCard: ValueCard
    private lateinit var timeoutStartCard: ValueCard
    private lateinit var timeoutFloorCard: ValueCard
    private lateinit var fixedTimeoutCard: ValueCard
    private lateinit var pingModeCard: ValueCard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        root = FrameLayout(this).apply {
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
            text = str("settings_title")
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
        })
        column.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 32)
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

        // ===================== اتصال و سرعت =====================
        list.addView(sectionHeader(str("sec_network")))

        // کش DNS
        list.addView(
            glassSwitch(
                title = str("set_dns_cache"),
                subtitle = str("set_dns_cache_sub"),
                iconPath = ICON_CACHE,
                initial = AppSettings.isDnsCacheEnabled(this),
                liveLine = cacheHitRateText
            ) { checked ->
                AppSettings.setDnsCacheEnabled(this, checked)
            }.card
        )

        // پشتیبانی TCP
        tcpFallbackCard = glassSwitch(
            title = str("set_tcp_fallback"),
            subtitle = str("set_tcp_fallback_sub"),
            iconPath = ICON_TCP,
            initial = AppSettings.isTcpFallbackEnabled(this)
        ) { checked ->
            AppSettings.setTcpFallbackEnabled(this, checked)
            refreshDependents()
        }
        list.addView(tcpFallbackCard.card)

        // حالت فقط TCP (با هشدار موقع روشن کردن)
        list.addView(
            glassSwitch(
                title = str("set_tcp_only"),
                subtitle = str("set_tcp_only_sub"),
                iconPath = ICON_TCP,
                initial = AppSettings.isTcpOnlyEnabled(this)
            ) { checked ->
                if (checked) {
                    confirmDialog(
                        title = str("tcp_only_warn_title"),
                        message = str("tcp_only_warn_msg"),
                        positiveText = str("activate"),
                        onConfirm = {
                            AppSettings.setTcpOnlyEnabled(this, true)
                            refreshDependents()
                        },
                        onCancel = { refreshDependents() }
                    )
                } else {
                    AppSettings.setTcpOnlyEnabled(this, false)
                    refreshDependents()
                }
            }.card
        )

        // همزمانی UDP
        udpCard = glassValueCard(
            title = str("set_udp_conc"),
            subtitle = str("set_udp_conc_sub"),
            iconPath = ICON_CONC,
            valueLabel = ""
        ) {
            numberPickDialog(
                title = str("set_udp_conc"),
                options = listOf(2, 4, 8, 16, 32),
                current = AppSettings.getUdpConcurrent(this),
                default = DEFAULT_UDP,
                unit = str("val_requests"),
            ) { v ->
                AppSettings.setUdpConcurrent(this, v)
                refreshValueLabels()
            }
        }
        list.addView(udpCard.card)

        // همزمانی TCP
        tcpConcCard = glassValueCard(
            title = str("set_tcp_conc"),
            subtitle = str("set_tcp_conc_sub"),
            iconPath = ICON_CONC,
            valueLabel = ""
        ) {
            numberPickDialog(
                title = str("set_tcp_conc"),
                options = listOf(1, 2, 4, 8, 16),
                current = AppSettings.getTcpConcurrent(this),
                default = DEFAULT_TCP,
                unit = str("val_requests"),
            ) { v ->
                AppSettings.setTcpConcurrent(this, v)
                refreshValueLabels()
            }
        }
        list.addView(tcpConcCard.card)

        // تایم‌اوت تطبیقی
        list.addView(
            glassSwitch(
                title = str("set_adaptive_timeout"),
                subtitle = str("set_adaptive_timeout_sub"),
                iconPath = ICON_TIMER,
                initial = AppSettings.isAdaptiveTimeoutEnabled(this)
            ) { checked ->
                AppSettings.setAdaptiveTimeoutEnabled(this, checked)
                refreshDependents()
            }.card
        )

        // نقطه شروع تایم‌اوت
        timeoutStartCard = glassValueCard(
            title = str("set_timeout_start"),
            subtitle = str("set_timeout_start_sub"),
            iconPath = ICON_TIMER,
            valueLabel = ""
        ) {
            numberPickDialog(
                title = str("set_timeout_start"),
                options = listOf(4000, 6000, 8000, 10000),
                current = AppSettings.getTimeoutStartMs(this),
                default = DEFAULT_TIMEOUT_START,
                unit = str("val_ms"),
            ) { v ->
                AppSettings.setTimeoutStartMs(this, v)
                refreshValueLabels()
            }
        }
        list.addView(timeoutStartCard.card)

        // کف تایم‌اوت
        timeoutFloorCard = glassValueCard(
            title = str("set_timeout_floor"),
            subtitle = str("set_timeout_floor_sub"),
            iconPath = ICON_TIMER,
            valueLabel = ""
        ) {
            val start = AppSettings.getTimeoutStartMs(this)
            numberPickDialog(
                title = str("set_timeout_floor"),
                options = listOf(1000, 1500, 2000, 3000, 5000).filter { it <= start },
                current = AppSettings.getTimeoutFloorMs(this),
                default = DEFAULT_TIMEOUT_FLOOR,
                unit = str("val_ms"),
            ) { v ->
                AppSettings.setTimeoutFloorMs(this, v)
                refreshValueLabels()
            }
        }
        list.addView(timeoutFloorCard.card)

        // تایم‌اوت ثابت
        fixedTimeoutCard = glassValueCard(
            title = str("set_fixed_timeout"),
            subtitle = str("set_fixed_timeout_sub"),
            iconPath = ICON_TIMER,
            valueLabel = ""
        ) {
            numberPickDialog(
                title = str("set_fixed_timeout"),
                options = listOf(2000, 3000, 4000, 5000, 7000),
                current = AppSettings.getFixedTimeoutMs(this),
                default = DEFAULT_FIXED_TIMEOUT,
                unit = str("val_ms"),
            ) { v ->
                AppSettings.setFixedTimeoutMs(this, v)
                refreshValueLabels()
            }
        }
        list.addView(fixedTimeoutCard.card)

        // روش پینگ‌گیری — پیش‌فرض = همان رفتار فعلی (پرس‌وجوی DNS)؛ حالت‌های
        // https و دستی، تأخیر واقعی‌ترِ TCP+TLS تا یک آدرس مشخص را می‌سنجند.
        pingModeCard = glassValueCard(
            title = str("ping_mode_title"),
            subtitle = str("ping_mode_sub"),
            iconPath = ICON_GLOBE,
            valueLabel = ""
        ) {
            showPingModeDialog()
        }
        list.addView(pingModeCard.card)

        // ===================== نمایش =====================
        list.addView(sectionHeader(str("sec_display")))

        list.addView(
            glassSwitch(
                title = str("set_show_percent"),
                subtitle = str("set_show_percent_sub"),
                iconPath = ICON_PERCENT,
                initial = AppSettings.isShowPacketPercentEnabled(this)
            ) { checked ->
                AppSettings.setShowPacketPercentEnabled(this, checked)
            }.card
        )

        list.addView(
            glassSwitch(
                title = str("set_show_notif"),
                subtitle = str("set_show_notif_sub"),
                iconPath = ICON_NOTIFICATION,
                initial = AppSettings.isShowNotificationInfoEnabled(this)
            ) { checked ->
                AppSettings.setShowNotificationInfoEnabled(this, checked)
            }.card
        )

        // ===================== عمومی =====================
        list.addView(sectionHeader(str("sec_general")))

        // زبان برنامه
        list.addView(
            glassValueCard(
                title = str("set_language"),
                subtitle = "",
                iconPath = ICON_LANG,
                valueLabel = currentLanguageLabel()
            ) {
                pickValueDialog(
                    title = str("set_language"),
                    options = listOf(
                        AppLang.DEVICE to "Device language",
                        AppLang.FA to "فارسی",
                        AppLang.EN to "English"
                    ),
                    current = AppSettings.getLanguage(this),
                    onCustom = null
                ) { v ->
                    AppSettings.setLanguage(this, v)
                    recreate() // فقط تغییر زبان به rebuild صفحه نیاز دارد
                }
            }.card
        )

        // دسترسی سریع
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            list.addView(
                glassCard(
                    title = str("set_quick_settings"),
                    iconPath = ICON_TUNE,
                    onClick = {
                        AppSettings.setQsTileEnabled(this, true)
                        if (!QuickSettingsTileService.requestAddTile(this)) {
                            Toast.makeText(this, str("tile_hint"), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            )
        }

        // درباره ما
        list.addView(
            glassCard(
                title = str("set_about"),
                iconPath = ICON_ABOUT,
                onClick = { startActivity(Intent(this, AboutActivity::class.java)) }
            )
        )

        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)
        setContentView(root)

        // اعمال وضعیت فعال/غیرفعال و برچسب‌های مقدار از روی تنظیمات فعلی
        refreshDependents()
        refreshValueLabels()
    }

    /** به‌روزرسانی وضعیت فعال/غیرفعال کارت‌های وابسته (بدون بازسازی صفحه). */
    private fun refreshDependents() {
        val tcpOnly = AppSettings.isTcpOnlyEnabled(this)
        val tcpFallbackOn = AppSettings.isTcpFallbackEnabled(this)
        val adaptive = AppSettings.isAdaptiveTimeoutEnabled(this)

        setCardEnabled(tcpFallbackCard.card, !tcpOnly)
        tcpFallbackCard.switch.isClickable = !tcpOnly
        setCardEnabled(udpCard.card, !tcpOnly)
        setCardEnabled(tcpConcCard.card, tcpFallbackOn || tcpOnly)
        setCardEnabled(timeoutStartCard.card, adaptive)
        setCardEnabled(timeoutFloorCard.card, adaptive)
        setCardEnabled(fixedTimeoutCard.card, !adaptive)
    }

    /** به‌روزرسانی برچسب مقدارها درجا (بدون بازسازی صفحه). */
    private fun refreshValueLabels() {
        udpCard.valueView.text = valueLabel(AppSettings.getUdpConcurrent(this), DEFAULT_UDP, str("val_requests"))
        tcpConcCard.valueView.text = valueLabel(AppSettings.getTcpConcurrent(this), DEFAULT_TCP, str("val_requests"))
        timeoutStartCard.valueView.text = valueLabel(AppSettings.getTimeoutStartMs(this), DEFAULT_TIMEOUT_START, str("val_ms"))
        timeoutFloorCard.valueView.text = valueLabel(AppSettings.getTimeoutFloorMs(this), DEFAULT_TIMEOUT_FLOOR, str("val_ms"))
        fixedTimeoutCard.valueView.text = valueLabel(AppSettings.getFixedTimeoutMs(this), DEFAULT_FIXED_TIMEOUT, str("val_ms"))
        pingModeCard.valueView.text = pingModeLabel()
    }

    /** برچسب کارت «روش پینگ» — در حالت دستی خودِ آدرس هم نشان داده می‌شود. */
    private fun pingModeLabel(): String = when (AppSettings.getPingMode(this)) {
        AppSettings.PING_MODE_PUBG -> "Pubgmobile.com"
        AppSettings.PING_MODE_GOOGLE -> "Google 204"
        AppSettings.PING_MODE_MANUAL -> {
            val url = AppSettings.getPingManualUrl(this)
            if (url.isBlank()) str("ping_mode_need_url") else url.substringAfter("://")
        }
        else -> str("ping_mode_dns")
    }

    /** دیالوگ انتخاب روش پینگ؛ گزینه «دستی» اول آدرس معتبر می‌خواهد. */
    private fun showPingModeDialog() {
        pickValueDialog(
            title = str("ping_mode_title"),
            options = listOf(
                AppSettings.PING_MODE_DNS to str("ping_mode_dns"),
                AppSettings.PING_MODE_PUBG to "https://pubgmobile.com",
                AppSettings.PING_MODE_GOOGLE to "https://www.google.com/generate_204",
                AppSettings.PING_MODE_MANUAL to str("ping_mode_manual")
            ),
            current = AppSettings.getPingMode(this),
            onCustom = null
        ) { v ->
            when (v) {
                AppSettings.PING_MODE_MANUAL -> urlPingInputDialog()
                else -> {
                    AppSettings.setPingMode(this, v)
                    refreshValueLabels()
                }
            }
        }
    }

    /**
     * ورود آدرس دستی پینگ — فقط لینک کامل https:// معتبر پذیرفته می‌شود
     * (طرح/سبک دقیقاً مثل numberInputDialog؛ با «تأیید» هم روش روی دستی ست می‌شود).
     */
    private fun urlPingInputDialog() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 44
                rightMargin = 44
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#2A2A3E"))
            }
            isClickable = true
            isFocusable = true
        }
        card.addView(TextView(this).apply {
            text = str("ping_mode_url_title")
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        })
        card.addView(TextView(this).apply {
            text = str("ping_mode_url_hint")
            setTextColor(Color.parseColor("#8A8A9A"))
            textSize = 12f
            setPadding(0, 0, 0, 14)
        })

        val input = EditText(this).apply {
            setText(AppSettings.getPingManualUrl(this@SettingsActivity))
            hint = "https://example.com"
            setHintTextColor(Color.parseColor("#5A5A6E"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#2A2A3E"))
                setStroke(2, Color.parseColor("#3A3A4E"))
            }
            setPadding(20, 14, 20, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        card.addView(input)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(makeDialogButton(str("cancel"), false) {
            dismissOverlay(overlay)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 1)
        })
        row.addView(makeDialogButton(str("ok"), true) {
            val v = input.text.toString().trim()
            if (!isValidHttpsUrl(v)) {
                Toast.makeText(this, str("ping_mode_invalid_url"), Toast.LENGTH_SHORT).show()
            } else {
                AppSettings.setPingManualUrl(this, v)
                AppSettings.setPingMode(this, AppSettings.PING_MODE_MANUAL)
                dismissOverlay(overlay)
                refreshValueLabels()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        overlay.addView(card)
        root.addView(overlay)
        animateDialogIn(overlay, card)
        input.requestFocus()
    }

    /** اعتبار لینک پینگ دستی: باید با https:// شروع شود و یک URL کامل با هاست معتبر باشد. */
    private fun isValidHttpsUrl(raw: String): Boolean {
        val s = raw.trim()
        if (s.length < 12 || s.length > 2048) return false
        if (s.any { it <= ' ' }) return false
        if (!s.startsWith("https://", ignoreCase = true)) return false
        return try {
            val host = java.net.URI(s).host
            host != null && host.contains('.') && !host.endsWith(".")
        } catch (_: Exception) {
            false
        }
    }

    /** محو کردن (و غیرقابل لمس کردن) یک کارت بدون حذفش از صفحه. */
    private fun setCardEnabled(card: LinearLayout, enabled: Boolean) {
        card.alpha = if (enabled) 1f else 0.4f
        card.isClickable = enabled
        card.isEnabled = enabled
    }

    /** برچسب مقدار + (پیش‌فرض) وقتی مقدار روی پیش‌فرض است. */
    private fun valueLabel(value: Int, default: Int, unit: String): String =
        if (value == default) "$value $unit • ${str("default_label")}" else "$value $unit"

    /** تیتر دسته‌بندی تنظیمات. */
    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#4C8DFF"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(4, 26, 4, 10)
        }
    }

    /** نام زبان فعلی، همیشه به زبان خودِ زبان (Device language / فارسی / English). */
    private fun currentLanguageLabel(): String = when (AppSettings.getLanguage(this)) {
        AppLang.FA -> "فارسی"
        AppLang.EN -> "English"
        else -> "Device language"
    }

    /** به‌روزرسانی خط «نرخ پاسخ از کش» از آمار زنده‌ی سرویس VPN. */
    private fun updateCacheHitRate() {
        if (!::cacheHitRateText.isInitialized) return
        val rate = VpnStats.dnsCacheHitRate()
        val cacheOn = AppSettings.isDnsCacheEnabled(this)
        cacheHitRateText.text = when {
            !cacheOn -> str("cache_rate_off")
            rate == null -> str("cache_rate_na")
            else -> String.format(str("cache_rate"), Math.round(rate))
        }
        cacheHitRateText.setTextColor(
            when {
                rate != null && rate >= 50 -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#8A8A9A")
            }
        )
    }

    /** دیالوگ انتخاب مقدار عددی: گزینه‌های آماده + ورود دستی (بدون محدودیت). */
    private fun numberPickDialog(
        title: String,
        options: List<Int>,
        current: Int,
        default: Int,
        unit: String,
        onPick: (Int) -> Unit
    ) {
        val strOptions = options.map {
            "$it" to (if (it == default) "$it $unit • ${str("default_label")}" else "$it $unit")
        }
        pickValueDialog(
            title = title,
            options = strOptions,
            current = current.toString(),
            onCustom = {
                numberInputDialog(title, current, unit, onPick)
            }
        ) { v ->
            v.toIntOrNull()?.let { onPick(it) }
        }
    }

    /** دیالوگ انتخاب از لیست (متن) + گزینه‌ی اختیاری «ورود دستی». */
    private fun pickValueDialog(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onCustom: (() -> Unit)?,
        onPick: (String) -> Unit
    ) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dismissOverlay(this) }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 18)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 44
                rightMargin = 44
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#2A2A3E"))
            }
            isClickable = true
            isFocusable = true
        }

        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        // اگر گزینه‌ها زیاد شوند، داخل اسکرول می‌گذاریم تا از صفحه بیرون نزنند
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val optionsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for ((value, label) in options) {
            val isCurrent = value == current
            val item = TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isCurrent) Color.parseColor("#4C8DFF") else Color.parseColor("#B0B0BA"))
                gravity = Gravity.CENTER
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(if (isCurrent) Color.parseColor("#224C8DFF") else Color.parseColor("#2A2A3E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissOverlay(overlay)
                    onPick(value)
                }
            }
            optionsColumn.addView(item)
        }
        if (onCustom != null) {
            optionsColumn.addView(TextView(this).apply {
                text = str("custom_label")
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#A0A0AC"))
                gravity = Gravity.CENTER
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(Color.parseColor("#22222E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissOverlay(overlay)
                    onCustom()
                }
            })
        }
        scroll.addView(optionsColumn)
        card.addView(scroll)

        card.addView(TextView(this).apply {
            text = str("cancel")
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8A8A9A"))
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissOverlay(overlay) }
        })

        overlay.addView(card)
        root.addView(overlay)
        animateDialogIn(overlay, card)
    }

    /** ورودی دستی عدد — بدون محدودیت بالا (هر عدد مثبت دلخواه). */
    private fun numberInputDialog(
        title: String,
        current: Int,
        unit: String,
        onDone: (Int) -> Unit
    ) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 44
                rightMargin = 44
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#2A2A3E"))
            }
            isClickable = true
            isFocusable = true
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        })
        card.addView(TextView(this).apply {
            text = str("enter_any") + " ($unit)"
            setTextColor(Color.parseColor("#8A8A9A"))
            textSize = 12f
            setPadding(0, 0, 0, 14)
        })

        val input = EditText(this).apply {
            setText(current.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#2A2A3E"))
                setStroke(2, Color.parseColor("#3A3A4E"))
            }
            setPadding(20, 14, 20, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        card.addView(input)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(makeDialogButton(str("cancel"), false) {
            dismissOverlay(overlay)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 1)
        })
        row.addView(makeDialogButton(str("ok"), true) {
            val v = input.text.toString().trim().toIntOrNull()
            if (v == null || v <= 0) {
                Toast.makeText(this, str("invalid_number"), Toast.LENGTH_SHORT).show()
            } else {
                dismissOverlay(overlay)
                onDone(v)
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        overlay.addView(card)
        root.addView(overlay)
        animateDialogIn(overlay, card)
        input.requestFocus()
    }

    private fun makeDialogButton(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(16, 14, 16, 14)
            setTextColor(if (primary) Color.WHITE else Color.parseColor("#B0B0BA"))
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(if (primary) Color.parseColor("#4C8DFF") else Color.parseColor("#2A2A3E"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** انیمیشن ورود نرم دیالوگ: فید اوورلی + بانس فنری کارت. */
    private fun animateDialogIn(overlay: FrameLayout, card: LinearLayout) {
        overlay.alpha = 0f
        card.alpha = 0f
        card.scaleX = 0.88f
        card.scaleY = 0.88f
        overlay.animate().alpha(1f).setDuration(180).start()
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
    }

    /** بستن دیالوگ با محو شدن نرم. */
    private fun dismissOverlay(overlay: FrameLayout) {
        overlay.animate().alpha(0f).setDuration(150).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }.start()
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

    /** کارت مقدار (عنوان + توضیح + مقدار فعلی + فلش) برای گزینه‌های عددی/انتخابی. */
    private fun glassValueCard(
        title: String,
        subtitle: String,
        iconPath: String,
        valueLabel: String,
        onClick: () -> Unit
    ): ValueCard {
        val valueView = TextView(this@SettingsActivity)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 20, 18)
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
            if (subtitle.isNotBlank()) {
                textColumn.addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#8A8A9A"))
                    textSize = 11f
                    setPadding(0, 6, 0, 0)
                })
            }
            addView(textColumn)

            valueView.apply {
                text = valueLabel
                setTextColor(Color.parseColor("#4C8DFF"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 14, 0)
            }
            addView(valueView)

            addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(buildVectorDrawable(ICON_CHEVRON, Color.parseColor("#666680"), 36))
            })

            setOnClickListener { onClick() }
        }
        return ValueCard(card, valueView)
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
    ): SwitchCard {
        val switchView = AnimatedSwitchView(this@SettingsActivity)
        val card = LinearLayout(this).apply {
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

            switchView.apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(26))
                setChecked(initial)
                onCheckedChangeListener = { checked -> onChange(checked) }
            }

            addView(textColumn)
            addView(switchView)

            // لمس هرجای کارت = روشن/خاموش
            setOnClickListener { switchView.performClick() }
        }
        return SwitchCard(card, switchView)
    }

    /** دیالوگ تأیید ساده (برای هشدارها) با دو دکمه. */
    private fun confirmDialog(
        title: String,
        message: String,
        positiveText: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = 44
                rightMargin = 44
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E2E"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#2A2A3E"))
            }
            isClickable = true
            isFocusable = true
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#FFC107"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        })
        val msgScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        msgScroll.addView(TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#B0B0BA"))
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 2, 0, 4)
        })
        card.addView(msgScroll)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        row.addView(makeDialogButton(str("cancel"), false) {
            dismissOverlay(overlay)
            onCancel()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 1)
        })
        row.addView(makeDialogButton(positiveText, true) {
            dismissOverlay(overlay)
            onConfirm()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        overlay.addView(card)
        root.addView(overlay)
        animateDialogIn(overlay, card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
