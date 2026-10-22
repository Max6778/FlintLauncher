package com.flintlauncher.feature.download.enums

import com.flintlauncher.feature.download.platform.AbstractPlatformHelper
import com.flintlauncher.feature.download.platform.curseforge.CurseForgeHelper
import com.flintlauncher.feature.download.platform.modrinth.ModrinthHelper

enum class Platform(val pName: String, val helper: AbstractPlatformHelper) {
    MODRINTH("Modrinth", ModrinthHelper()),
    CURSEFORGE("CurseForge", CurseForgeHelper())
}