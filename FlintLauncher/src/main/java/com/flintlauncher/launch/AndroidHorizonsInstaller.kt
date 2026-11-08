package com.flintlauncher.launch

import android.content.Context
import com.flintlauncher.feature.log.Logging
import com.flintlauncher.feature.version.Version
import net.kdt.pojavlaunch.Architecture
import java.io.File
import java.io.IOException

/** Installs the bundled Android native compatibility mod into eligible DH profiles. */
internal object AndroidHorizonsInstaller {
    private const val ASSET_PATH = "androidhorizons/AndroidHorizons-1.0.0.jar"
    private const val MOD_FILENAME = "AndroidHorizons-1.0.0.jar"
    private const val MOD_ID = "androidhorizons"

    @Throws(IOException::class)
    fun installIfNeeded(context: Context, version: Version) {
        if (Architecture.getDeviceArchitecture() != Architecture.ARCH_ARM64) return
        val info = version.getVersionInfo() ?: return
        val mcVersion = info.minecraftVersion.orEmpty()
        if (!mcVersion.startsWith("1.21.")) return

        val fabricVersion = info.loaderInfo.orEmpty()
            .firstOrNull { it.name.equals("Fabric", ignoreCase = true) }
            ?.version ?: return
        if (!isAtLeastFabric016(fabricVersion)) return

        val modsDir = File(version.getGameDir(), "mods")
        if (!modsDir.isDirectory()) return
        val hasDistantHorizons = modsDir.listFiles()?.any { file ->
            file.isFile && file.name.endsWith(".jar", ignoreCase = true) &&
                file.name.contains("distanthorizons", ignoreCase = true)
        } == true
        if (!hasDistantHorizons) return

        val destination = File(modsDir, MOD_FILENAME)
        if (destination.isFile && destination.length() > 0L) return
        context.assets.open(ASSET_PATH).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        Logging.i("AndroidHorizons", "Installed $MOD_ID for Minecraft $mcVersion / Fabric $fabricVersion")
    }

    private fun isAtLeastFabric016(version: String): Boolean {
        val numbers = Regex("\\d+").findAll(version).take(3).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return false
        val major = numbers[0]
        val minor = numbers.getOrElse(1) { 0 }
        return major > 0 || minor >= 16
    }
}
