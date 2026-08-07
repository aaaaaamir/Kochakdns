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
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class MainActivity : AppCompatActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // مجوز تایید شد
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

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

        val cardView = CardView(this).apply {
            radius = 64f
            cardElevation = 16f
            setCardBackgroundColor(Color.parseColor("#1E1E2E"))
            layoutParams = LinearLayout.LayoutParams(340, 340).apply {
                setMargins(0, 0, 0, 32)
            }
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        cardView.addView(logoIcon)

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
            ).apply {
                setMargins(0, 24, 0, 0)
            }
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
            ).apply {
                setMargins(0, 12, 0, 0)
            }
        }

        container.addView(cardView)
        container.addView(titleText)
        container.addView(descText)
        rootLayout.addView(container)

        setContentView(rootLayout)

        cardView.scaleX = 0.3f
        cardView.scaleY = 0.3f
        cardView.alpha = 0f
        cardView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(1200)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(30f, 0f).apply {
                duration = 1200
                addUpdateListener { anim ->
                    val radius = anim.animatedValue as Float
                    if (radius > 0.5f) {
                        logoIcon.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                        )
                    } else {
                        logoIcon.setRenderEffect(null)
                    }
                }
                start()
            }
        }

        titleText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(900)
            .setStartDelay(300)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        descText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(900)
            .setStartDelay(600)
            .setInterpolator(OvershootInterpolator(1.1f))
            .withEndAction {
                startLuxuryGlowEffect(descText)
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

    private fun checkVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }
}
