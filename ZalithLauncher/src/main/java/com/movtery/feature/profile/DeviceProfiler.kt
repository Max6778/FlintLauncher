package com.movtery.zalithlauncher.feature.profile

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * FlintLauncher - Device Profiler
 * Detects device hardware and returns a profile used
 * to automatically tune performance settings.
 */
object DeviceProfiler {

    enum class DeviceTier {
        LOW,   // Under 3GB RAM
        MID,   // 3–6GB RAM
        HIGH   // 6GB+ RAM
    }

    data class DeviceProfile(
        val totalRamMb: Int,
        val availableRamMb: Int,
        val cpuCores: Int,
        val androidVersion: Int,
        val tier: DeviceTier,
        val recommendedRamMb: Int,
        val recommendedJvmArgs: String,
        val recommendedRenderer: String,
        val isLowEnd: Boolean
    )

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamMb = (memInfo.totalMem / 1024 / 1024).toInt()
        val availableRamMb = (memInfo.availMem / 1024 / 1024).toInt()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val androidVersion = Build.VERSION.SDK_INT

        val tier = when {
            totalRamMb < 3072 -> DeviceTier.LOW
            totalRamMb < 6144 -> DeviceTier.MID
            else -> DeviceTier.HIGH
        }

        val recommendedRamMb = when (tier) {
            DeviceTier.LOW -> minOf(512, (totalRamMb * 0.35).toInt())
            DeviceTier.MID -> minOf(1024, (totalRamMb * 0.30).toInt())
            DeviceTier.HIGH -> minOf(2048, (totalRamMb * 0.30).toInt())
        }

        val recommendedJvmArgs = buildJvmArgs(tier, recommendedRamMb, cpuCores)

        val recommendedRenderer = when (tier) {
            DeviceTier.LOW -> "opengles2"
            DeviceTier.MID -> "opengles3"
            DeviceTier.HIGH -> "opengles3"
        }

        return DeviceProfile(
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            cpuCores = cpuCores,
            androidVersion = androidVersion,
            tier = tier,
            recommendedRamMb = recommendedRamMb,
            recommendedJvmArgs = recommendedJvmArgs,
            recommendedRenderer = recommendedRenderer,
            isLowEnd = tier == DeviceTier.LOW
        )
    }

    private fun buildJvmArgs(tier: DeviceTier, ramMb: Int, cores: Int): String {
        val base = "-XX:+UseG1GC " +
                "-XX:MaxGCPauseMillis=20 " +
                "-XX:+UnlockExperimentalVMOptions " +
                "-XX:+DisableExplicitGC"

        return when (tier) {
            DeviceTier.LOW ->
                "$base " +
                "-Xms${ramMb / 2}m " +
                "-XX:G1HeapRegionSize=8M " +
                "-XX:+AlwaysPreTouch"

            DeviceTier.MID ->
                "$base " +
                "-Xms${ramMb / 2}m " +
                "-XX:G1HeapRegionSize=16M"

            DeviceTier.HIGH ->
                "$base " +
                "-Xms${ramMb / 2}m " +
                "-XX:G1HeapRegionSize=32M"
        }
    }

    /**
     * Returns a human-readable summary of the device tier.
     * Used in UI to show users what was detected.
     */
    fun getTierLabel(tier: DeviceTier): String {
        return when (tier) {
            DeviceTier.LOW -> "Low-end device"
            DeviceTier.MID -> "Mid-range device"
            DeviceTier.HIGH -> "High-end device"
        }
    }
}

