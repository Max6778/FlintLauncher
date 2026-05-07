package com.movtery.zalithlauncher.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.utils.ZHTools

@SuppressLint("CustomSplashScreen")
class FlintSplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flint_splash)

        val logo = findViewById<ImageView>(R.id.splash_logo)
        val title = findViewById<TextView>(R.id.splash_title)
        val tagline = findViewById<TextView>(R.id.splash_tagline)
        val progress = findViewById<ProgressBar>(R.id.splash_progress)
        val version = findViewById<TextView>(R.id.splash_version)

        // Set version text
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

        // Text fade in — slightly delayed
        val textAnim = AlphaAnimation(0f, 1f).apply {
            duration = 600
            startOffset = 500
            fillAfter = true
        }

        // Progress + version fade in — last
        val bottomAnim = AlphaAnimation(0f, 1f).apply {
            duration = 500
            startOffset = 800
            fillAfter = true
        }

        // Start animations
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

        // Move to SplashActivity after 2.8 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, SplashActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2800)
    }
}
