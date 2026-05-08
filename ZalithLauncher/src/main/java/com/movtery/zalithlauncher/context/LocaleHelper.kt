package com.movtery.zalithlauncher.context

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import com.movtery.zalithlauncher.setting.Settings
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import java.util.Locale

class LocaleHelper(context: Context) : ContextWrapper(context) {
    companion object {

        // Supported launcher languages
        // "system" = follow device locale
        val SUPPORTED_LANGUAGES = mapOf(
            "system" to "Follow System",
            "en"     to "English",
            "fr"     to "Français",
            "de"     to "Deutsch",
            "es"     to "Español",
            "pt"     to "Português",
            "ru"     to "Русский",
            "zh"     to "中文",
            "ja"     to "日本語",
            "ko"     to "한국어",
            "ar"     to "العربية",
            "tr"     to "Türkçe",
            "pl"     to "Polski",
            "it"     to "Italiano",
            "nl"     to "Nederlands"
        )

        fun setLocale(context: Context): ContextWrapper {
            // Initialize paths
            PathManager.initContextConstants(context)
            // Refresh launcher settings
            Settings.refreshSettings()
            LauncherPreferences.loadPreferences()

            // Read saved launcher language preference
            val prefs = context.getSharedPreferences("flint_locale", Context.MODE_PRIVATE)
            val languageCode = prefs.getString("launcher_language", "system") ?: "system"

            // If system, don't override locale
            if (languageCode == "system" || languageCode.isBlank()) {
                return LocaleHelper(context)
            }

            // Apply chosen locale
            val locale = Locale(languageCode)
            Locale.setDefault(locale)

            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)

            return LocaleHelper(context.createConfigurationContext(config))
        }

        /**
         * Save the chosen launcher language and return true.
         * The app must be restarted for the change to take effect.
         */
        fun saveLanguage(context: Context, languageCode: String) {
            context.getSharedPreferences("flint_locale", Context.MODE_PRIVATE)
                .edit()
                .putString("launcher_language", languageCode)
                .apply()
        }

        /**
         * Get the currently saved language code.
         */
        fun getSavedLanguage(context: Context): String {
            return context.getSharedPreferences("flint_locale", Context.MODE_PRIVATE)
                .getString("launcher_language", "system") ?: "system"
        }
    }
}
