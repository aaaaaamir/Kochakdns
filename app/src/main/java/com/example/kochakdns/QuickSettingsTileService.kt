package com.example.kochakdns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * کاشی دسترسی سریع (Quick Settings Tile):
 *
 * - وقتی فعال باشد و VPN روشن است → سبز با نام DNS فعلی؛ تپ = قطع.
 * - وقتی فعال باشد و VPN خاموش است → تپ = اتصال با آخرین DNS انتخاب‌شده.
 * - وقتی از تنظیمات غیرفعال باشد → کاشی غیرفعال نشان داده می‌شود.
 *
 * نکته‌ی اصولی: اگر مجوز VPN هنوز گرفته نشده باشد (یا هنوز DNSی انتخاب نشده)،
 * از داخل کاشی نمی‌توان دیالوگ مجوز را نشان داد (نیاز به Activity دارد)، پس در
 * آن حالت کاربر به صفحه‌ی اصلی برنامه هدایت می‌شود.
 */
class QuickSettingsTileService : TileService() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        // ثبت دریافت‌کننده برای به‌روزرسانی فوری کاشی موقع وصل/قطع
        val filter = IntentFilter().apply {
            addAction("VPN_STARTED")
            addAction("VPN_STOPPED")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onClick() {
        super.onClick()
        if (!AppSettings.isQsTileEnabled(applicationContext)) {
            // غیرفعال است؛ کاربر را به تنظیمات ببر تا فعالش کند
            startActivityAndCollapse(Intent(this, SettingsActivity::class.java))
            return
        }

        if (VpnStats.isVpnActive) {
            startService(Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_STOP
            })
        } else {
            val (servers, name) = readLastDns()
            if (servers.isEmpty()) {
                // هنوز DNS انتخاب نشده؛ اپ را باز کن
                startActivityAndCollapse(Intent(this, DnsActivity::class.java))
                return
            }
            // مجوز VPN هنوز گرفته نشده → باید از داخل اپ گرفته شود
            if (VpnService.prepare(this) != null) {
                startActivityAndCollapse(Intent(this, DnsActivity::class.java))
                return
            }
            val startIntent = Intent(this, MyVpnService::class.java).apply {
                action = MyVpnService.ACTION_START
                putStringArrayListExtra(MyVpnService.EXTRA_DNS_SERVERS, ArrayList(servers))
                putExtra(MyVpnService.EXTRA_DNS_NAME, name)
            }
            // کاشی در حالت background کلیک می‌شود؛ سرویس foreground باید با
            // startForegroundService شروع شود (اندروید ۸ به بعد)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = AppSettings.isQsTileEnabled(applicationContext)
        val active = VpnStats.isVpnActive
        when {
            !enabled -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Kochak DNS"
                tile.subtitle = "از تنظیمات فعال کن"
            }
            active -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = VpnStats.activeDnsName ?: "Kochak DNS"
                tile.subtitle = "متصل — تپ برای قطع"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Kochak DNS"
                tile.subtitle = "تپ برای اتصال"
            }
        }
        tile.updateTile()
    }

    private fun readLastDns(): Pair<List<String>, String> {
        return try {
            val prefs = getSharedPreferences(MyVpnService.PREFS_DNS, Context.MODE_PRIVATE)
            val servers = prefs.getString(MyVpnService.KEY_LAST_DNS_SERVERS, null)
                ?.split("\u0000")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val name = prefs.getString(MyVpnService.KEY_LAST_DNS_NAME, null) ?: "DNS"
            servers to name
        } catch (_: Exception) {
            emptyList<String>() to "DNS"
        }
    }
}
