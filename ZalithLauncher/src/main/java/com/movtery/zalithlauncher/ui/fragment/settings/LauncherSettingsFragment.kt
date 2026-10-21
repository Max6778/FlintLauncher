package com.movtery.zalithlauncher.ui.fragment.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
    private var catImagePicker: ActivityResultLauncher<Array<String>>? = null

    private val catImagePreferences
        get() = requireContext().getSharedPreferences("flint_launcher_ui", android.content.Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                val resolver = requireContext().contentResolver
                try {
                    resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    Toast.makeText(
                        requireContext(),
                        R.string.flint_cat_image_pick_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    return@let
                }
                releaseCatImagePermission(except = it)
                catImagePreferences.edit()
                    .putString("cat_image_uri", it.toString())
                    .putBoolean("cat_image_hidden", false)
                    .apply()
                refreshCatImageSummary()
            }
        }
    }

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

    private fun releaseCatImagePermission(except: Uri? = null) {
        val oldUriString = catImagePreferences.getString("cat_image_uri", null) ?: return
        val oldUri = Uri.parse(oldUriString)
        if (oldUri == except) return
        try {
            requireContext().contentResolver.releasePersistableUriPermission(
                oldUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // A provider may have revoked the grant already.
        }
    }

    private fun refreshCatImageSummary() {
        if (!::binding.isInitialized || view == null) return
        val preferences = catImagePreferences
        binding.catImageSummary.text = when {
            preferences.getBoolean("cat_image_hidden", false) -> getString(R.string.flint_cat_image_removed)
            preferences.contains("cat_image_uri") -> getString(R.string.flint_cat_image_custom)
            else -> getString(R.string.flint_cat_image_default)
        }
    }

    private fun showCatImageOptions() {
        val options = arrayOf(
            getString(R.string.flint_cat_image_choose),
            getString(R.string.flint_cat_image_default_action),
            getString(R.string.flint_cat_image_remove)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.flint_cat_image_title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> catImagePicker?.launch(arrayOf("image/*"))
                    1 -> {
                        releaseCatImagePermission()
                        catImagePreferences.edit()
                            .remove("cat_image_uri")
                            .putBoolean("cat_image_hidden", false)
                            .apply()
                        refreshCatImageSummary()
                    }
                    2 -> {
                        releaseCatImagePermission()
                        catImagePreferences.edit()
                            .remove("cat_image_uri")
                            .putBoolean("cat_image_hidden", true)
                            .apply()
                        refreshCatImageSummary()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()

        binding.catImageLayout.setOnClickListener { showCatImageOptions() }
        refreshCatImageSummary()

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
        animPlayer.duration(240).apply(
            AnimPlayer.Entry(
                binding.root,
                Animations.FadeIn
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
