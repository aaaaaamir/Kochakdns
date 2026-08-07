package com.example.kochakdns

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // مجوز VPN تایید شد
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupSplashScreen()
        checkVpnPermission()
    }

    @Suppress("NewApi")
    private fun setupSplashScreen() {
        val rootLayout = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F14"))
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(260, 260)
        }

        val titleText = TextView(this).apply {
            text = "کُچک دی ان اس"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = -60f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 48, 0, 0)
            }
        }

        val descText = TextView(this).apply {
            text = "vip gaming dns"
            textSize = 16f
            setTextColor(Color.parseColor("#FFD700"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = -60f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }

        container.addView(logoIcon)
        container.addView(titleText)
        container.addView(descText)
        rootLayout.addView(container)

        setContentView(rootLayout)

        // ۱. انیمیشن بلر آیکون
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(25f, 0.1f).apply {
                duration = 1400
                addUpdateListener { anim ->
                    val radius = anim.animatedValue as Float
                    logoIcon.setRenderEffect(
                        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    )
                }
                start()
            }
        } else {
            logoIcon.alpha = 0.2f
            logoIcon.scaleX = 1.3f
            logoIcon.scaleY = 1.3f
            logoIcon.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(1400)
                .start()
        }

        // ۲. انیمیشن عنوان اصلی
        titleText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(400)
            .start()

        // ۳. انیمیشن توضیحات طلایی
        descText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(800)
            .withEndAction {
                startGoldShimmerEffect(descText)
            }
            .start()
    }

    private fun startGoldShimmerEffect(textView: TextView) {
        ObjectAnimator.ofFloat(textView, "alpha", 1f, 0.35f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun checkVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }
}
