package com.flintlauncher.feature.unpack

import android.content.Context
import com.flintlauncher.feature.log.Logging.e
import com.flintlauncher.utils.CopyDefaultFromAssets.Companion.copyFromAssets
import com.flintlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools

class UnpackSingleFilesTask(val context: Context) : AbstractUnpackTask() {
    override fun isNeedUnpack(): Boolean = true

    override fun run() {
        runCatching {
            copyFromAssets(context)
            Tools.copyAssetFile(context, "resolv.conf", PathManager.DIR_DATA, false)
        }.getOrElse { e("AsyncAssetManager", "Failed to unpack critical components !") }
    }
}