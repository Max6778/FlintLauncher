package com.flintlauncher.feature.download

import androidx.lifecycle.ViewModel
import com.flintlauncher.feature.download.item.InfoItem
import com.flintlauncher.feature.download.platform.AbstractPlatformHelper

class InfoViewModel : ViewModel() {
    var platformHelper: AbstractPlatformHelper? = null
    var infoItem: InfoItem? = null
}