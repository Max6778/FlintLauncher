package com.flintlauncher.ui.activity

import com.flintlauncher.feature.unpack.AbstractUnpackTask

class InstallableItem(
    val name: String,
    val summary: String?,
    val task: AbstractUnpackTask,
    var isRunning: Boolean = false,
    var isFinished: Boolean = false
) : Comparable<InstallableItem> {

    override fun compareTo(other: InstallableItem): Int {
        return name.compareTo(other.name, ignoreCase = true)
    }
}