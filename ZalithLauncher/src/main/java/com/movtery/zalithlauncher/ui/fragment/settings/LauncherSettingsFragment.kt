package com.movtery.zalithlauncher.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.movtery.anim.AnimPlayer
import com.movtery.anim.animations.Animations
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.context.LocaleHelper
import com.movtery.zalithlauncher.databinding.SettingsFragmentLauncherBinding
import com.movtery.zalithlauncher.event.single.PageOpacityChangeEvent
import com.movtery.zalithlauncher.feature.update.UpdateUtils
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.fragment.CustomBackgroundFragment
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim
import com.movtery.zalithlauncher.ui.fragment.settings.wrapper.BaseSettingsWrapper
import com.movtery.zalithlauncher.ui.fragment.settings.wrapper.ListSettingsWrapper
import com.movtery.zalithlauncher.ui.fragment.settings.wrapper.SeekBarSettingsWrapper
import com.movtery.zalithlauncher.ui.fragment.settings.wrapper.SwitchSettingsWrapper
import com.movtery.zalithlauncher.utils.CleanUpCache.Companion.start
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.LauncherActivity
import org.greenrobot.eventbus.EventBus

class LauncherSettingsFragment() :
    AbstractSettingsFragment(
        R.layout.settings_fragment_launcher,
        SettingCategory.LAUNCHER
    ) {

    private lateinit var binding: SettingsFragmentLauncherBinding
    private var parentFragment: FragmentWithAnim? = null

    constructor(parentFragment: FragmentWithAnim?) : this() {
        this.parentFragment = parentFragment
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentLauncherBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()

        SwitchSettingsWrapper(
            context,
            AllSettings.checkLibraries,
            binding.checkLibrariesLayout,
            binding.checkLibraries
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.verifyManifest,
            binding.verifyManifestLayout,
            binding.verifyManifest
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.resourceImageCache,
            binding.resourceImageCacheLayout,
            binding.resourceImageCache
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.addFullResourceName,
            binding.addFullResourceNameLayout,
            binding.addFullResourceName
        )

        ListSettingsWrapper(
            context,
            AllSettings.downloadSource,
            binding.downloadSourceLayout,
            binding.downloadSourceTitle,
            binding.downloadSourceValue,
            R.array.download_source_names,
            R.array.download_source_values
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.maxDownloadThreads,
            binding.maxDownloadThreadsLayout,
            binding.maxDownloadThreadsTitle,
            binding.maxDownloadThreadsSummary,
            binding.maxDownloadThreadsValue,
            binding.maxDownloadThreads,
            ""
        )

        ListSettingsWrapper(
            context,
            AllSettings.launcherTheme,
            binding.launcherThemeLayout,
            binding.launcherThemeTitle,
            binding.launcherThemeValue,
            R.array.launcher_theme_names,
            R.array.launcher_theme_values
        ).setRequiresReboot()

        // Launcher language picker
        val savedLang = LocaleHelper.getSavedLanguage(requireContext())

        binding.launcherLanguageValue.text =
            LocaleHelper.SUPPORTED_LANGUAGES[savedLang] ?: "Follow System"

        binding.launcherLanguageLayout.setOnClickListener {
            val keys = LocaleHelper.SUPPORTED_LANGUAGES.keys.toTypedArray()
            val values = LocaleHelper.SUPPORTED_LANGUAGES.values.toTypedArray()

            val current = keys.indexOf(
                LocaleHelper.getSavedLanguage(requireContext())
            )

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.setting_launcher_language))
                .setSingleChoiceItems(values, current) { dialog, which ->
                    val chosen = keys[which]

                    LocaleHelper.saveLanguage(requireContext(), chosen)

                    binding.launcherLanguageValue.text = values[which]

                    dialog.dismiss()

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.setting_launcher_language_restart),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .show()
        }

        BaseSettingsWrapper(
            context,
            binding.customBackgroundLayout
        ) {
            parentFragment?.apply {
                ZHTools.swapFragmentWithAnim(
                    this,
                    CustomBackgroundFragment::class.java,
                    CustomBackgroundFragment.TAG,
                    null
                )
            }
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.animation,
            binding.animationLayout,
            binding.animation
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.animationSpeed,
            binding.animationSpeedLayout,
            binding.animationSpeedTitle,
            binding.animationSpeedSummary,
            binding.animationSpeedValue,
            binding.animationSpeed,
            "ms"
        )

        SeekBarSettingsWrapper(
            context,
            AllSettings.pageOpacity,
            binding.pageOpacityLayout,
            binding.pageOpacityTitle,
            binding.pageOpacitySummary,
            binding.pageOpacityValue,
            binding.pageOpacity,
            "%"
        ).setOnSeekBarProgressChangeListener {
            EventBus.getDefault().post(PageOpacityChangeEvent(it))
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.enableLogOutput,
            binding.enableLogOutputLayout,
            binding.enableLogOutput
        )

        SwitchSettingsWrapper(
            context,
            AllSettings.quitLauncher,
            binding.quitLauncherLayout,
            binding.quitLauncher
        )

        BaseSettingsWrapper(
            context,
            binding.cleanUpCacheLayout
        ) {
            start(context)
        }

        BaseSettingsWrapper(
            context,
            binding.checkUpdateLayout
        ) {
            UpdateUtils.checkDownloadedPackage(
                context,
                force = true,
                ignore = false
            )
        }

        SwitchSettingsWrapper(
            context,
            AllSettings.acceptPreReleaseUpdates,
            binding.acceptPreReleaseUpdatesLayout,
            binding.acceptPreReleaseUpdates
        )

        val notificationPermissionRequest = SwitchSettingsWrapper(
            context,
            AllSettings.notificationPermissionRequest,
            binding.notificationPermissionRequestLayout,
            binding.notificationPermissionRequest
        )

        setupNotificationRequestPreference(notificationPermissionRequest)
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        animPlayer.apply(
            AnimPlayer.Entry(
                binding.root,
                Animations.BounceInDown
            )
        )
    }

    private fun setupNotificationRequestPreference(
        notificationPermissionRequest: SwitchSettingsWrapper
    ) {
        val activity = requireActivity()

        if (activity is LauncherActivity) {
            if (ZHTools.checkForNotificationPermission()) {
                notificationPermissionRequest.setGone()
            }

            notificationPermissionRequest.switchView
                .setOnCheckedChangeListener { _, _ ->
                    activity.askForNotificationPermission {
                        notificationPermissionRequest.mainView.visibility =
                            View.GONE
                    }
                }
        } else {
            notificationPermissionRequest.mainView.visibility = View.GONE
        }
    }
    }
