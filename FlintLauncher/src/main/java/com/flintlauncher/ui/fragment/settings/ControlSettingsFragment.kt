package com.flintlauncher.ui.fragment.settings

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.flintlauncher.R
import com.flintlauncher.databinding.SettingsFragmentControlBinding
import com.flintlauncher.event.value.SettingsPageSwapEvent
import com.flintlauncher.setting.AllSettings
import com.flintlauncher.ui.fragment.CustomMouseFragment
import com.flintlauncher.ui.fragment.FragmentWithAnim
import com.flintlauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.flintlauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.flintlauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.flintlauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.flintlauncher.utils.ZHTools
import fr.spse.gamepad_remapper.Remapper
import net.kdt.pojavlaunch.fragments.GamepadMapperFragment
import org.greenrobot.eventbus.Subscribe

class ControlSettingsFragment() : AbstractSettingsFragment(R.layout.settings_fragment_control, SettingCategory.CONTROL) {
    private lateinit var binding: SettingsFragmentControlBinding
    private var parentFragment: FragmentWithAnim? = null
    private var settingsInitialized = false

    constructor(parentFragment: FragmentWithAnim?) : this() {
        this.parentFragment = parentFragment
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentControlBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    @Subscribe
    fun initializeWhenSelected(event: SettingsPageSwapEvent) {
        if (event.index == SettingCategory.CONTROL.ordinal) binding.root.post { initializeSettings() }
    }

    private fun initializeSettings() {
        if (settingsInitialized || !isAdded) return
        settingsInitialized = true
        val context = requireContext()
        ListSettingsWrapper(
            context,
            AllSettings.controlLayoutStyle,
            binding.controlLayoutStyleLayout,
            binding.controlLayoutStyleTitle,
            binding.controlLayoutStyleValue,
            R.array.control_layout_style_names,
            R.array.control_layout_style_values
        )

        ListSettingsWrapper(
            context,
            AllSettings.androidBackAction,
            binding.androidBackActionLayout,
            binding.androidBackActionTitle,
            binding.androidBackActionValue,
            R.array.android_back_action_names,
            R.array.android_back_action_values
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.disableGestures,
            binding.disableGesturesLayout,
            binding.disableGestures
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.disableDoubleTap,
            binding.disableDoubleTapLayout,
            binding.disableDoubleTap
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.timeLongPressTrigger,
            binding.timeLongPressTriggerLayout,
            binding.timeLongPressTriggerTitle,
            binding.timeLongPressTriggerSummary,
            binding.timeLongPressTriggerValue,
            binding.timeLongPressTrigger,
            "ms"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.buttonScale,
            binding.buttonscaleLayout,
            binding.buttonscaleTitle,
            binding.buttonscaleSummary,
            binding.buttonscaleValue,
            binding.buttonscale,
            "%"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.buttonAllCaps,
            binding.buttonAllCapsLayout,
            binding.buttonAllCaps
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.mouseScale,
            binding.mousescaleLayout,
            binding.mousescaleTitle,
            binding.mousescaleSummary,
            binding.mousescaleValue,
            binding.mousescale,
            "%"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.mouseSpeed,
            binding.mousespeedLayout,
            binding.mousespeedTitle,
            binding.mousespeedSummary,
            binding.mousespeedValue,
            binding.mousespeed,
            "%"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.virtualMouseStart,
            binding.mouseStartLayout,
            binding.mouseStart
        )

        BaseSettingsWrapper(
            context,
            binding.customMouseLayout
        ) {
            parentFragment?.apply {
                ZHTools.swapFragmentWithAnim(
                    this,
                    CustomMouseFragment::class.java,
                    CustomMouseFragment.TAG,
                    null
                )
            }
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.enableGyro,
            binding.enableGyroLayout,
            binding.enableGyro
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gyroSensitivity,
            binding.gyroSensitivityLayout,
            binding.gyroSensitivityTitle,
            binding.gyroSensitivitySummary,
            binding.gyroSensitivityValue,
            binding.gyroSensitivity,
            "%"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.gyroSampleRate,
            binding.gyroSampleRateLayout,
            binding.gyroSampleRateTitle,
            binding.gyroSampleRateSummary,
            binding.gyroSampleRateValue,
            binding.gyroSampleRate,
            "ms"
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroSmoothing,
            binding.gyroSmoothingLayout,
            binding.gyroSmoothing
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroInvertX,
            binding.gyroInvertXLayout,
            binding.gyroInvertX
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.gyroInvertY,
            binding.gyroInvertYLayout,
            binding.gyroInvertY
        )

        BaseSettingsWrapper(
            context,
            binding.changeControllerBindingsLayout
        ) {
            parentFragment?.apply {
                ZHTools.swapFragmentWithAnim(
                    this,
                    GamepadMapperFragment::class.java,
                    GamepadMapperFragment.TAG,
                    null
                )
            }
        }

        BaseSettingsWrapper(
            context,
            binding.resetControllerBindingsLayout
        ) {
            Remapper.wipePreferences(context)
            Toast.makeText(context, R.string.setting_controller_map_wiped, Toast.LENGTH_SHORT)
                .show()
        }

        SeekBarSettingsWrapper(
            context,
            AllSettings.deadZoneScale,
            binding.gamepadDeadzoneScaleLayout,
            binding.gamepadDeadzoneScaleTitle,
            binding.gamepadDeadzoneScaleSummary,
            binding.gamepadDeadzoneScaleValue,
            binding.gamepadDeadzoneScale,
            "%"
        )

        val mGyroAvailable =
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            ) != null
        binding.enableGyroCategory.visibility = if (mGyroAvailable) View.VISIBLE else View.GONE

        computeVisibility()
    }

    override fun onChange() {
        super.onChange()
        if (settingsInitialized) computeVisibility()
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.duration(240).apply(AnimPlayer.Entry(binding.root, Animations.FadeIn))
    }

    private fun computeVisibility() {
        binding.apply {
            setViewVisibility(
                timeLongPressTriggerLayout,
                !AllSettings.disableGestures.getValue()
            )
            setViewVisibility(gyroSensitivityLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroSampleRateLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroInvertXLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroInvertYLayout, AllSettings.enableGyro.getValue())
            setViewVisibility(gyroSmoothingLayout, AllSettings.enableGyro.getValue())
        }
    }

    private fun setViewVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}