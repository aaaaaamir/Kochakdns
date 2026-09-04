package com.example.kochakdns

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // نکته: درخواست مجوز VPN (VpnService.prepare) عمداً از اینجا حذف شد.
    // این مجوز فقط باید وقتی کاربر واقعاً دکمه‌ی اتصال رو توی DnsActivity می‌زنه
    // درخواست بشه (که همین الان هم درست پیاده‌سازی شده). گرفتنش اینجا، هنگام
    // باز شدن اپ، باعث می‌شد اندروید هر VPN/فیلترشکن فعال دیگه‌ای رو قطع کنه.

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onPermissionsGranted() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setupSplashScreen()

        // دریافت لیست DNS از همین لحظه (همزمان با انیمیشن اسپلش) شروع می‌شه،
        // نه بعد از ۳ ثانیه تاخیر. DnsActivity بعداً به همین درخواست در حال
        // اجرا join می‌شه، پس دیتا معمولاً قبل یا همزمان با رسیدن به اون
        // صفحه آماده‌ست و دیگه منتظرش نمی‌مونیم.
        DnsSyncCoordinator.startSync(applicationContext)

        // API های بروزرسانی و اطلاعیه هم «به محض شروع برنامه» صدا زده می‌شوند
        // تا وقتی کاربر به صفحه‌ی اصلی رسید، نتیجه از قبل آماده باشد و هیچ
        // کندی حس نکند. DnsActivity بعداً به همین job ها ملحق می‌شود.
        if (AppSettings.isUpdateCheckEnabled(applicationContext)) {
            StartupTasks.startUpdateCheck(applicationContext)
        }
        if (AppSettings.isAnnouncementEnabled(applicationContext)) {
            StartupTasks.startAnnouncement(applicationContext)
        }

        // اگه دفعه‌ی قبل برنامه force-stop شده باشه، MyVpnService فرصت نکرده
        // آمار آخرین اتصال رو بفرسته. اینجا چک می‌کنیم آیا از چک‌پوینت پیوسته‌ی
        // روی دیسک چیزی جامونده، و اگه بله (و طبق همون قانون ۳۰ ثانیه واجد
        // شرایط بود) همین الان می‌فرستیمش.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pending = PendingStatsStore.read(applicationContext)
            if (pending != null) {
                if (pending.durationMs >= 30_000) {
                    StatsReporter.send(pending.profileName, pending.sent, pending.lost, pending.operator)
                }
                PendingStatsStore.clear(applicationContext)
            }
        }

        lifecycleScope.launch {
            delay(3000)
            checkNotificationPermission()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onPermissionsGranted()
            }
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        // sync دیگه اینجا زده نمی‌شه (از onCreate شروع شده)؛ DnsActivity
        // خودش موقع باز شدن به همون درخواست join می‌شه و منتظرش می‌مونه.
        navigateToDnsActivity()
    }

    private fun navigateToDnsActivity() {
        startActivity(Intent(this, DnsActivity::class.java))
        finish()
    }

    // ===================================================================
    // آیکون‌های شناور صفحه اسپلش
    // هر آیکون یک «عمق» دارد: اندازه، شفافیت (پررنگ/کم‌رنگ)، و تارشدگی (بلور)
    // با هم فرق می‌کنند تا حس عمق و پخش شدن در فضا ایجاد شود.
    // ===================================================================
    private data class FloatingIcon(
        val path: String,
        val xFraction: Float,   // موقعیت افقی (۰..۱)
        val yFraction: Float,   // موقعیت عمودی (۰..۱)
        val sizeDp: Int,        // اندازه (نزدیک‌تر = بزرگ‌تر)
        val alpha: Float,       // پررنگی (۱ = خیلی پررنگ، کمتر = محو)
        val blurRadius: Float,  // تارشدگی (۰ = واضح، بیشتر = تارتر)
        val driftX: Float,      // دامنه‌ی حرکت افقی (px)
        val driftY: Float,      // دامنه‌ی حرکت عمودی (px)
        val duration: Long,     // طول یک سیکل شناوری
        val startDelay: Long
    )

    private fun floatingIcons(): List<FloatingIcon> {
        // آیکون‌های قابلیت‌های برنامه — مسیرهای پرجزئیات‌تر (سبک Material)
        val paths = listOf(
            "M13,2 3,14h7l-1,8 10,-12h-7l1,-8z",                                                          // رعد
            "M12,22s8,-4 8,-10V5l-8,-3 -8,3v7c0,6 8,10 8,10z",                                           // سپر
            "M6,9h4v2H8v2H6zM15,11h2v2h-2zM18,9h2v2h-2zM2,7h20v10H2z",                                  // دسته بازی
            "M2,20h.01M7,20v-4M12,20v-8M17,20V8M22,4v16",                                               // سیگنال
            "M18,20V10M12,20V4M6,20v-6",                                                                // نمودار
            "M12,2a10,10 0 1,0 0,20 10,10 0 1,0 0,-20M2,12h20M12,2c3,3 3,17 0,20c-3,-3 -3,-17 0,-20", // کره
            "M6,9H4.5a2.5,2.5 0 0 1 0,-5H6M18,9h1.5a2.5,2.5 0 0 0 0,-5H18M4,22h16M10,14.7V17c0,.6 -.5,1 -1,1.2c-1.2,.5 -2,2 -2,3.8M14,14.7V17c0,.6 .5,1 1,1.2c1.2,.5 2,2 2,3.8M18,2H6v7a6,6 0 0 0 12,0V2z", // جام
            "M12,12m-10,0a10,10 0 1,1 20,0a10,10 0 1,1 -20,0M12,12m-6,0a6,6 0 1,1 12,0a6,6 0 1,1 -12,0M12,12m-2,0a2,2 0 1,1 4,0a2,2 0 1,1 -4,0", // هدف
            "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z", // قفل
            "M12,3a6,6 0 0 0,6 6c0.4,0 0.8,-0.05 1.2,-0.15L20,10.2A8,8 0 0 1 12,2a8,8 0 0 1 -7.9,6.9L5,8.8A6,6 0 0 0,12,3zM18.5,13.5L21,11a8,8 0 0 1 -2.4,7.4l1.4,1.4a10,10 0 0 0,1.5 -6.3zM7.9,5.1A6,6 0 0 0,6 9a6,6 0 0 0,2.2 4.6l-1.5 1.5A8,8 0 0 1,4 11a8,8 0 0 1,2.4 -5.7z", // وای‌فای
            "M5,17h14v2H5zM5,11h14v2H5zM5,5h14v2H5z",                                                    // لایه‌ها
            "M12,2l2.4,4.9 5.4,0.8 -3.9,3.8 0.9,5.4 -4.8,-2.5 -4.8,2.5 0.9,-5.4 -3.9,-3.8 5.4,-0.8z", // ستاره
            "M20.8,4.6a5.5,5.5 0 0 0 -7.8,0L12,5.6l-1,-1a5.5,5.5 0 0 0 -7.8,7.8l1,1L12,21.2l7.8,-7.8 1,-1a5.5,5.5 0 0 0,0 -7.8z", // قلب
            "M12,4.5C7,4.5 2.7,7.6 1,12c1.7,4.4 6,7.5 11,7.5s9.3,-3.1 11,-7.5C21.3,7.6 17,4.5 12,4.5zM12,17a5,5 0 1 1 0,-10 5,5 0 0 1,0 10zM12,9.5a2.5,2.5 0 1 0,0 5 2.5,2.5 0 0 0,0 -5z", // چشم
            "M12,2l2.5,5.5L20,8l-4,4 1,5.5 -5,-3 -5,3L8,12 4,8l5.5,-0.5z",                                 // الماس
            "M13.5,5.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM9.9,19.1c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18.5,20c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM10.5,13c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,11c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM16.5,16c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM7,7.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM7,16c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z", // نقاط شبکه
            "M12,2a10,10 0 1,0 0,20 10,10 0 0,0 0,-20zM10,17l-5,-5 1.4,-1.4L10,14.2l7.6,-7.6L19,8z",        // تیک دایره
            "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6z"                                                          // بعلاوه
        )

        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val icons = mutableListOf<FloatingIcon>()

        // پخش شبکه‌ای تا کل صفحه پر شود + جابجایی تصادفی در هر خانه
        val cols = 4
        val rows = 5
        val shuffled = paths.shuffled(kotlin.random.Random(rnd.nextLong()))

        shuffled.take(cols * rows).forEachIndexed { i, path ->
            val col = i % cols
            val row = i / cols
            val cellW = 1f / cols
            val cellH = 1f / rows
            val jx = (rnd.nextFloat() - 0.5f) * cellW * 0.7f
            val jy = (rnd.nextFloat() - 0.5f) * cellH * 0.7f
            val x = ((col + 0.5f) * cellW + jx).coerceIn(0.04f, 0.96f)
            val y = ((row + 0.5f) * cellH + jy).coerceIn(0.06f, 0.94f)

            // عمق تصادفی: دور = کوچک + محو + تار | نزدیک = بزرگ‌تر + پررنگ + واضح
            val depth = rnd.nextFloat()
            val sizeDp = lerp(11f, 20f, depth).toInt()
            val alpha = lerp(0.08f, 0.30f, depth)
            val blurRadius = lerp(3f, 0f, depth)

            val driftX = lerp(8f, 20f, rnd.nextFloat())
            val driftY = lerp(8f, 20f, rnd.nextFloat())
            val duration = rnd.nextLong(4200L, 6800L)
            val startDelay = (i * 60L) + rnd.nextLong(0L, 200L)

            icons.add(
                FloatingIcon(path, x, y, sizeDp, alpha, blurRadius, driftX, driftY, duration, startDelay)
            )
        }
        return icons
    }

    /** درون‌یابی خطی ساده برای اعداد float. */
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    @Suppress("NewApi")
    private fun setupSplashScreen() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
            alpha = 0f
        }

        // ===== لوگوی برنامه: بزرگ، blur شده، در پس‌زمینه =====
        val logoSize = dp(260)
        val bgLogo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER)
            // بلور شدید برای حس «پشت صحنه»
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP))
            } else {
                alpha = 0.18f // روی نسخه‌های قدیمی فقط محو
            }
        }
        rootLayout.addView(bgLogo)

        // ===== آیکون‌های شناور مینیمال (روی لوگوی blur) =====
        floatingIcons().forEach { icon ->
            val sizePx = dp(icon.sizeDp)
            val iconView = ImageView(this).apply {
                // گرادیان خاکستری: گوشه‌ی پایین/راست کمی تیره‌تر (حس عمق)
                setImageDrawable(
                    buildVectorDrawableGradient(
                        icon.path,
                        Color.parseColor("#A6A6B2"),
                        Color.parseColor("#55555F"),
                        icon.sizeDp
                    )
                )
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    leftMargin = ((resources.displayMetrics.widthPixels - sizePx) * icon.xFraction).toInt()
                    topMargin = ((resources.displayMetrics.heightPixels - sizePx) * icon.yFraction).toInt()
                }
                // تارشدگی برای آیکون‌های دور (فقط اندروید ۱۲+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && icon.blurRadius > 0f) {
                    setRenderEffect(RenderEffect.createBlurEffect(icon.blurRadius, icon.blurRadius, Shader.TileMode.CLAMP))
                }
            }
            rootLayout.addView(iconView)

            // ورود نرم + شناوری پیوسته
            iconView.animate()
                .alpha(icon.alpha)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(icon.startDelay)
                .setDuration(900)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
            startFloating(iconView, icon)
        }

        setContentView(rootLayout)

        // کل صفحه از سیاه محو به رنگ اصلی می‌رسه، به‌جای این‌که یهو ظاهر بشه
        rootLayout.animate().alpha(1f).setDuration(500).setInterpolator(LinearInterpolator()).start()

        // لوگوی پس‌زمینه به‌آرامی محو و نمایان می‌شود
        bgLogo.animate().alpha(0.5f).setDuration(1400)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    /** شناوری پیوسته‌ی هر آیکون: حرکت نرم در دو محور + چرخش ملایم. */
    private fun startFloating(view: View, icon: FloatingIcon) {
        // ValueAnimator بعد از start توسط AnimationHandler نگه داشته می‌شود،
        // پس نیازی به نگه‌داشتن مرجع نیست.
        ValueAnimator.ofFloat(0f, icon.driftX, 0f, -icon.driftX, 0f).apply {
            duration = icon.duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = icon.startDelay
            addUpdateListener { view.translationX = it.animatedValue as Float }
            start()
        }
        ValueAnimator.ofFloat(0f, -icon.driftY, 0f, icon.driftY, 0f).apply {
            duration = icon.duration * 5 / 4
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = icon.startDelay + 150
            addUpdateListener { view.translationY = it.animatedValue as Float }
            start()
        }
        // چرخش ملایم (نوسان ±۶ درجه) برای حس زنده‌بودن آیکون‌ها
        ValueAnimator.ofFloat(-6f, 6f, -6f).apply {
            duration = icon.duration * 3 / 2
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = icon.startDelay + 300
            addUpdateListener { view.rotation = it.animatedValue as Float }
            start()
        }
    }
}
