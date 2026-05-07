package com.movtery.zalithlauncher.feature.profile

import android.content.Context
import android.content.SharedPreferences
import com.movtery.zalithlauncher.setting.AllSettings

/**
 * FlintLauncher - Auto Performance Manager
 * Runs once on first launch to apply device-optimized settings.
 * Never overwrites settings the user has manually changed.
 */
object AutoPerformanceManager {

    private const val PREFS_NAME = "flint_auto_perf"
    private const val KEY_OPTIMIZED = "auto_optimized_v1"

    /**
     * Call this from FlintSplashActivity.
     * Profiles the device and applies optimal defaults — only on first launch.
     */
    fun applyIfFirstLaunch(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OPTIMIZED, false)) return

        val profile = DeviceProfiler.profile(context)
        applySettings(profile)

        prefs.edit().putBoolean(KEY_OPTIMIZED, true).apply()
    }

    /**
     * Force re-apply optimization (e.g. from a "Re-optimize" button in settings).
     */
    fun forceApply(context: Context) {
        val profile = DeviceProfiler.profile(context)
        applySettings(profile)
    }

    private fun applySettings(profile: DeviceProfiler.DeviceProfile) {
        // RAM allocation
        AllSettings.ramAllocation.value.save(profile.recommendedRamMb)

        // Renderer
        AllSettings.renderer.save(profile.recommendedRenderer)

        // JVM args — only set if user hasn't written anything custom
        val currentArgs = AllSettings.javaArgs.getValue()
        if (currentArgs.isBlank()) {
            AllSettings.javaArgs.save(profile.recommendedJvmArgs)
        }

        // Low-end specific tweaks
        if (profile.isLowEnd) {
            // Scale down resolution slightly to gain FPS
            AllSettings.resolutionRatio.save(85)
            // Disable animations to reduce overhead
            AllSettings.animation.save(false)
            // Enable sustained performance mode
            AllSettings.sustainedPerformance.save(true)
        }
    }
}

