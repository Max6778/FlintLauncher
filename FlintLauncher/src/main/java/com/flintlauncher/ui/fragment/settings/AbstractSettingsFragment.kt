package com.flintlauncher.ui.fragment.settings

import androidx.annotation.CallSuper
import com.movtery.anim.AnimPlayer
import com.flintlauncher.event.single.SettingsChangeEvent
import com.flintlauncher.event.value.SettingsPageSwapEvent
import com.flintlauncher.ui.fragment.FragmentWithAnim
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

abstract class AbstractSettingsFragment(layoutId: Int, private val category: SettingCategory) : FragmentWithAnim(layoutId) {
    @Subscribe
    fun event(event: SettingsChangeEvent) {
        onChange()
    }

    @Subscribe
    fun event(event: SettingsPageSwapEvent) {
        if (event.index == category.ordinal) {
            slideIn()
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @CallSuper
    protected open fun onChange() {
        LauncherPreferences.loadPreferences()
    }

    override fun slideOut(animPlayer: AnimPlayer) {}
}