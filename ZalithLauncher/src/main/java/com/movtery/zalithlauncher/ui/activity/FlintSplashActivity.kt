package com.movtery.zalithlauncher.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.profile.AutoPerformanceManager
import com.movtery.zalithlauncher.utils.ZHTools

@SuppressLint("CustomSplashScreen")
class FlintSplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flint_splash)

        // BUG 1 FIX: Hide the 3 bottom navigation buttons (back/home/recents)
        hideNavigationBar()

        // Apply device-optimized settings on first launch
        AutoPerformanceManager.applyIfFirstLaunch(this)

        val logo = findViewById<ImageView>(R.id.splash_logo)
        val title = findViewById<TextView>(R.id.splash_title)
        val tagline = findViewById<TextView>(R.id.splash_tagline)
        val progress = findViewById<ProgressBar>(R.id.splash_progress)
        val version = findViewById<TextView>(R.id.splash_version)

        version.text = "v${ZHTools.getVersionName()}"

        // Logo animation — scale + fade in
        val logoAnim = AnimationSet(true).apply {
            interpolator = AccelerateDecelerateInterpolator()
            addAnimation(AlphaAnimation(0f, 1f).apply {
                duration = 700
                fillAfter = true
            })
            addAnimation(ScaleAnimation(
                0.7f, 1f,
                0.7f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 700
                fillAfter = true
            })
        }

        val textAnim = AlphaAnimation(0f, 1f).apply {
            duration = 600
            startOffset = 500
            fillAfter = true
        }

        val bottomAnim = AlphaAnimation(0f, 1f).apply {
            duration = 500
            startOffset = 800
            fillAfter = true
        }

        logo.startAnimation(logoAnim)
        logo.alpha = 1f

        Handler(Looper.getMainLooper()).postDelayed({
            title.startAnimation(textAnim)
            tagline.startAnimation(textAnim)
            title.alpha = 1f
            tagline.alpha = 1f
        }, 500)

        Handler(Looper.getMainLooper()).postDelayed({
            progress.startAnimation(bottomAnim)
            version.startAnimation(bottomAnim)
            progress.alpha = 1f
            version.alpha = 1f
        }, 800)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, SplashActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2800)
    }

    // BUG 1 FIX: Hides back/home/recents nav buttons
    @Suppress("DEPRECATION")
    private fun hideNavigationBar() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    // Keep nav hidden if user swipes to reveal it
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }
}
