package com.example.kochakdns

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
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

    @Suppress("NewApi")
    private fun setupSplashScreen() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // یک هاله‌ی نرم و درخشان پشت کارت لوگو، برای حس پرمیوم‌تر
        val cardStack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(420, 420).apply { setMargins(0, 0, 0, 32) }
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

        val titleText = TextView(this).apply {
            text = "کُچک دی ان اس"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = -80f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }
        val descText = TextView(this).apply {
            text = "VIP GAMING DNS"
            textSize = 16f
            setTextColor(Color.parseColor("#FFD700"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = -80f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 0) }
        }
        container.addView(cardStack)
        container.addView(titleText)
        container.addView(descText)
        rootLayout.addView(container)
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
        titleText.animate().alpha(1f).translationY(0f).setDuration(900)
            .setStartDelay(300).setInterpolator(OvershootInterpolator(1.1f)).start()
        descText.animate().alpha(1f).translationY(0f).setDuration(900)
            .setStartDelay(600).setInterpolator(OvershootInterpolator(1.1f))
            .withEndAction { startLuxuryGlowEffect(descText) }.start()
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

    private fun startLuxuryGlowEffect(textView: TextView) {
        ObjectAnimator.ofFloat(textView, "alpha", 1f, 0.6f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }
}
