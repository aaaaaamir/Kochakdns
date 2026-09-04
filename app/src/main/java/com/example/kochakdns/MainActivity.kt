package com.example.kochakdns

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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
        // آیکون‌های قابلیت‌های برنامه (فقط مسیر SVG؛ رنگ از تم خاکستری گرفته می‌شود)
        val paths = listOf(
            "M13,2 3,14h7l-1,8 10,-12h-7l1,-8z",                                                    // رعد (سرعت)
            "M12,22s8,-4 8,-10V5l-8,-3 -8,3v7c0,6 8,10 8,10z",                                       // سپر (ضد تحریم)
            "M6,9h4v2H8v2H6zM15,11h2v2h-2zM18,9h2v2h-2zM2,7h20v10H2z",                              // دسته بازی
            "M2,20h.01M7,20v-4M12,20v-8M17,20V8M22,4v16",                                           // سیگنال (پینگ)
            "M18,20V10M12,20V4M6,20v-6",                                                            // نمودار (آمار)
            "M12,2a10,10 0 1,0 0,20 10,10 0 1,0 0,-20M2,12h20M12,2c3,3 3,17 0,20c-3,-3 -3,-17 0,-20", // کره (DNS)
            "M6,9H4.5a2.5,2.5 0 0 1 0,-5H6M18,9h1.5a2.5,2.5 0 0 0 0,-5H18M4,22h16M10,14.7V17c0,.6 -.5,1 -1,1.2c-1.2,.5 -2,2 -2,3.8M14,14.7V17c0,.6 .5,1 1,1.2c1.2,.5 2,2 2,3.8M18,2H6v7a6,6 0 0 0 12,0V2z", // جام (بهترین)
            "M12,12m-10,0a10,10 0 1,1 20,0a10,10 0 1,1 -20,0M12,12m-6,0a6,6 0 1,1 12,0a6,6 0 1,1 -12,0M12,12m-2,0a2,2 0 1,1 4,0a2,2 0 1,1 -4,0" // هدف (دقت)
        )

        val rnd = kotlin.random.Random(System.currentTimeMillis())
        val icons = mutableListOf<FloatingIcon>()

        paths.forEachIndexed { i, path ->
            // ===== عمق تصادفی: 0 = دور، 1 = نزدیک =====
            // دور: کوچک + محو + تار | نزدیک: بزرگ + پررنگ + واضح
            val depth = rnd.nextFloat()
            val sizeDp = lerp(34f, 68f, depth).roundToInt()
            val alpha = lerp(0.22f, 0.95f, depth)
            val blurRadius = lerp(9f, 0f, depth)

            // موقعیت تصادفی، با پرهیز از مرکز (جایی که لوگو است)
            var x = rnd.nextFloat() * 0.88f + 0.04f
            var y = rnd.nextFloat() * 0.82f + 0.08f
            if (x in 0.34f..0.66f && y in 0.32f..0.62f) {
                // خیلی به مرکز نزدیک شد؛ به یکی از گوشه‌ها هدایتش کن
                if (x < 0.5f) {
                    x = rnd.nextFloat() * 0.28f + 0.04f
                } else {
                    x = rnd.nextFloat() * 0.28f + 0.68f
                }
            }

            val driftX = lerp(14f, 34f, rnd.nextFloat())
            val driftY = lerp(14f, 34f, rnd.nextFloat())
            val duration = rnd.nextLong(4200L, 6800L)
            val startDelay = (i * 90L) + rnd.nextLong(0L, 220L)

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
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
            gravity = Gravity.CENTER
            alpha = 0f
        }

        // ===== آیکون‌های شناور (پشت لوگو) =====
        val density = resources.displayMetrics.density
        floatingIcons().forEach { icon ->
            val sizePx = (icon.sizeDp * density).toInt()
            val iconView = ImageView(this).apply {
                // رنگ خاکستری هماهنگ با آیکون‌های تم برنامه (ICON_GRAY)
                setImageDrawable(buildVectorDrawable(icon.path, Color.parseColor("#A0A0AC"), icon.sizeDp))
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                layoutParams = RelativeLayout.LayoutParams(sizePx, sizePx).apply {
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

        // ===== کارت لوگو (وسط) =====
        val cardStack = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(420, 420).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        val glowView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(420, 420).apply { gravity = Gravity.CENTER }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 210f
                    setColors(intArrayOf(Color.parseColor("#55FFD700"), Color.parseColor("#00FFD700")))
                }
            }
            scaleX = 0.75f
            scaleY = 0.75f
            alpha = 0f
        }
        val cardView = CardView(this).apply {
            radius = 64f
            cardElevation = 16f
            setCardBackgroundColor(Color.parseColor("#1E1E2E"))
            layoutParams = FrameLayout.LayoutParams(340, 340).apply { gravity = Gravity.CENTER }
        }
        val logoIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        cardView.addView(logoIcon)

        // یک نوار نور مورب که یک‌بار روی کارت سر می‌خوره (افکت shimmer ملایم)
        val shimmerView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(70, FrameLayout.LayoutParams.MATCH_PARENT)
            rotation = 20f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#33FFFFFF"), Color.TRANSPARENT)
                )
            }
            translationX = -420f
            alpha = 0f
        }
        cardView.addView(shimmerView)

        cardStack.addView(glowView)
        cardStack.addView(cardView)
        rootLayout.addView(cardStack)

        setContentView(rootLayout)

        // کل صفحه از سیاه محو به رنگ اصلی می‌رسه، به‌جای این‌که یهو ظاهر بشه
        rootLayout.animate().alpha(1f).setDuration(500).setInterpolator(LinearInterpolator()).start()

        cardView.scaleX = 0.3f
        cardView.scaleY = 0.3f
        cardView.alpha = 0f
        cardView.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(1200).setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction {
                startGlowPulse(glowView)
                runShimmerSweep(shimmerView)
            }.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(30f, 0f).apply {
                duration = 1200
                addUpdateListener { anim ->
                    val radius = anim.animatedValue as Float
                    if (radius > 0.5f) logoIcon.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                    else logoIcon.setRenderEffect(null)
                }
                start()
            }
        }
    }

    /** شناوری پیوسته‌ی هر آیکون: حرکت نرم در دو محور با سرعت‌های متفاوت. */
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
    }

    /** هاله‌ی پشت کارت به‌آرومی و پیوسته بزرگ/کوچک و کم/زیاد می‌شه، مثل نفس کشیدن. */
    private fun startGlowPulse(glowView: View) {
        glowView.animate().alpha(1f).setDuration(600).start()
        ValueAnimator.ofFloat(0.85f, 1.1f).apply {
            duration = 2400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val scale = it.animatedValue as Float
                glowView.scaleX = scale
                glowView.scaleY = scale
            }
            start()
        }
    }

    /** یک خط نور مورب هر چند ثانیه یک‌بار به‌آرومی روی کارت سر می‌خوره. */
    private fun runShimmerSweep(shimmerView: View) {
        shimmerView.translationX = -420f
        shimmerView.alpha = 1f
        shimmerView.animate()
            .translationX(420f)
            .setDuration(1300)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                shimmerView.postDelayed({ runShimmerSweep(shimmerView) }, 2600)
            }
            .start()
    }
}
