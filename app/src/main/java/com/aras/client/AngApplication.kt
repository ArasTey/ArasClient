package com.aras.client

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aras.client.AppConfig.ANG_PACKAGE
import com.aras.client.handler.AppLocaleManager
import com.aras.client.handler.MmkvManager
import com.aras.client.util.LogUtil
import com.aras.client.handler.SettingsManager
import com.aras.client.ui.compose.ThemeManager
import com.aras.client.ui.calculator.LauncherIconManager

class AngApplication : Application(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName("${ANG_PACKAGE}:bg")
            .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // Restore the default launcher on installs that used the removed Telegram icon.
        LauncherIconManager.migrateRemovedTelegramMode(this)

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // The log level is read once and cached; without this a change to it during a
        // session would only take effect on the next launch.
        LogUtil.refreshLogLevel()

        // Initialize theme state from MMKV
        ThemeManager.refresh()
    }
}
