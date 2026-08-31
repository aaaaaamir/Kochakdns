package com.example.kochakdns

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        // آیکون بازگشت (فلش Material)
        private const val ICON_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"

        // آیکون‌های وکتور (سبک Material، رنگ خاکستری هماهنگ با DnsActivity)
        private const val ICON_BLOCK = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z"
        private const val ICON_TUNNEL = "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z"
        private const val ICON_PERCENT = "M7.5,11C9.43,11 11,9.43 11,7.5S9.43,4 7.5,4 4,5.57 4,7.5 5.57,11 7.5,11zM7.5,6C8.33,6 9,6.67 9,7.5S8.33,9 7.5,9 6,8.33 6,7.5 6.67,6 7.5,6zM16.5,20c1.93,0 3.5,-1.57 3.5,-3.5s-1.57,-3.5 -3.5,-3.5 -3.5,1.57 -3.5,3.5 1.57,3.5 3.5,3.5zM16.5,17c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5 -1.5,-0.67 -1.5,-1.5 0.67,-1.5 1.5,-1.5zM19,5L5,19"
        private const val ICON_NOTIFICATION = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
    }

    // سوییچ مسدودسازی و وضعیت «در انتظار فعال‌سازی در تنظیمات سیستم»
    private var blockSwitch: Switch? = null
    private var pendingEnableBlock = false
    private var updatingBlockSwitch = false

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

        // ===== مسدودسازی برنامه‌های تونل‌نشده (از طریق Always-on VPN سیستم) =====
        list.addView(
            settingSwitch(
                title = "مسدود کردن اینترنت برنامه‌های تونل‌نشده",
                subtitle = "برنامه‌هایی که از لیست «برنامه‌های تونل شده» انتخاب نکردی، به اینترنت دسترسی نداشته باشن (از طریق Always-on VPN سیستم)",
                iconPath = ICON_BLOCK,
                initial = AppSettings.isBlockNonTunneledEnabled(this),
                onSwitchCreated = { blockSwitch = it },
                onChange = { checked ->
                    if (updatingBlockSwitch) return@settingSwitch
                    if (checked) {
                        onBlockEnableRequested()
                    } else {
                        onBlockDisableRequested()
                    }
                }
            )
        )

        list.addView(
            settingSwitch(
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

    override fun onResume() {
        super.onResume()
        if (pendingEnableBlock) {
            pendingEnableBlock = false
            if (isFirewallReady()) {
                AppSettings.setBlockNonTunneledEnabled(this, true)
                Toast.makeText(this, "مسدودسازی فعال شد ✓", Toast.LENGTH_SHORT).show()
                restartVpnIfActive()
            } else {
                Toast.makeText(
                    this,
                    "فعال نشد؛ Always-on VPN و «مسدود کردن اتصال بدون VPN» را فعال کن",
                    Toast.LENGTH_LONG
                ).show()
            }
            setBlockSwitchChecked(AppSettings.isBlockNonTunneledEnabled(this))
        } else {
            // اگر برگشتیم ولی وضعیت واقعی فرق دارد، سوییچ را هماهنگ کن
            setBlockSwitchChecked(AppSettings.isBlockNonTunneledEnabled(this))
        }
    }

    /** وقتی کاربر سوییچ مسدودسازی را روشن می‌کند: اول چک می‌کند؛ اگر آماده بود مستقیم فعال می‌شود، وگرنه راهنمایی + هدایت به تنظیمات. */
    private fun onBlockEnableRequested() {
        if (isFirewallReady()) {
            // از قبل توی تنظیمات سیستم فعاله؛ بدون دیالوگ مستقیم روشنش کن
            AppSettings.setBlockNonTunneledEnabled(this, true)
            Toast.makeText(this, "مسدودسازی فعال شد ✓", Toast.LENGTH_SHORT).show()
            restartVpnIfActive()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("مسدودسازی برنامه‌های انتخاب‌نشده")
            .setMessage(
                "برای مسدودسازیِ واقعیِ اینترنتِ برنامه‌هایی که انتخاب نکردی، باید " +
                    "در تنظیمات اندروید این گزینه‌ها را برای Kochak DNS فعال کنی:\n\n" +
                    "• Always-on VPN\n" +
                    "• مسدود کردن اتصال بدون VPN\n\n" +
                    "توجه مهم: وقتی این حالت روشن باشه و VPN وصل نباشه، هیچ برنامه‌ای " +
                    "(حتی خودِ اپ) به اینترنت دسترسی نداره — این همان «کشته‌ی اتصال» (kill switch) است.\n\n" +
                    "الان می‌برمت به همون صفحه؛ بعد از فعال‌کردن برگرد."
            )
            .setCancelable(false)
            .setPositiveButton("برو به تنظیمات") { _, _ ->
                pendingEnableBlock = true
                openVpnSettings()
            }
            .setNegativeButton("انصراف") { _, _ ->
                setBlockSwitchChecked(false)
            }
            .show()
    }

    /** وقتی کاربر سوییچ مسدودسازی را خاموش می‌کند: یادآوری وضعیت سیستم. */
    private fun onBlockDisableRequested() {
        AppSettings.setBlockNonTunneledEnabled(this, false)
        restartVpnIfActive()
        AlertDialog.Builder(this)
            .setTitle("مسدودسازی خاموش شد")
            .setMessage(
                "توجه: گزینه‌ی «مسدود کردن اتصال بدون VPN» در تنظیمات سیستم احتمالاً هنوز روشنه؛ " +
                    "تا وقتی VPN وصل نباشه، اینترنت همه‌ی برنامه‌ها قطع می‌مونه.\n\n" +
                    "برای برگردوندن حالت عادی، اون گزینه رو هم در تنظیمات VPN خاموش کن. " +
                    "می‌خوای الان بری به تنظیمات VPN؟"
            )
            .setPositiveButton("برو به تنظیمات") { _, _ -> openVpnSettings() }
            .setNegativeButton("بعداً", null)
            .show()
    }

    /** تنظیم سوییچ بدون تحریک listener (برای هماهنگ‌سازی برنامه‌ای). */
    private fun setBlockSwitchChecked(value: Boolean) {
        updatingBlockSwitch = true
        blockSwitch?.isChecked = value
        updatingBlockSwitch = false
    }

    /** آیا «Block connections without VPN» (قفل اتصال) روشن است؟ */
    private fun isLockdownEnabled(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, "always_on_vpn_lockdown", 0) != 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * آیا فایروال آماده است؟ برای مسدودسازی، «قفل اتصال» (Block connections
     * without VPN) کافی است؛ این گزینه فقط وقتی فعال می‌شود که Always-on VPN
     * هم روشن باشد، پس چک کردن آن به‌تنهایی مطمئن‌تر است (روی بعضی گوشی‌ها
     * مقدار always_on_vpn_app فرمت متفاوتی دارد و باعث تشخیص اشتباه می‌شد).
     */
    private fun isFirewallReady(): Boolean = isLockdownEnabled()

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

    /** باز کردن صفحه‌ی تنظیمات VPN سیستم (برای فعال‌سازی Always-on VPN). */
    private fun openVpnSettings() {
        val first = try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            true
        } catch (_: Exception) {
            false
        }
        if (!first) {
            try {
                startActivity(Intent("android.settings.VPN_SETTINGS"))
            } catch (_: Exception) {
            }
        }
    }

    private fun settingSwitch(
        title: String,
        subtitle: String,
        iconPath: String,
        initial: Boolean,
        onSwitchCreated: ((Switch) -> Unit)? = null,
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
            onSwitchCreated?.invoke(switchView)

            addView(textColumn)
            addView(switchView)
        }
    }
}
