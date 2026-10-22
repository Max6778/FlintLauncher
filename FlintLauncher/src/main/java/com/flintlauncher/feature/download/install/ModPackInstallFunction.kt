package com.flintlauncher.feature.download.install

import com.flintlauncher.feature.download.item.ModLoaderWrapper
import java.io.File
import java.io.IOException

fun interface ModPackInstallFunction {
    @Throws(IOException::class)
    fun install(modpackFile: File, targetPath: File): ModLoaderWrapper?
}