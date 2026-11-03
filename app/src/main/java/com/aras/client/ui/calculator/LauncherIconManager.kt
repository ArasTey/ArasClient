package com.aras.client.ui.calculator

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Switches only the declared launcher aliases and stores calculator preferences.
 * VPN services and other app components are intentionally untouched.
 */
object LauncherIconManager {
    private const val DEFAULT_ALIAS = "com.aras.client.ui.main.DefaultLauncherAlias"
    private const val CALCULATOR_ALIAS = "com.aras.client.ui.calculator.CalculatorLauncherAlias"
    private const val TELEGRAM_ALIAS = "com.aras.client.ui.telegram.TelegramLauncherAlias"
    private const val PREFS = "radar_launcher"
    private const val KEY_ICON_MODE = "icon_mode"
    private const val KEY_ACCESS_CODE_HASH = "access_code_sha256"
    private const val DEFAULT_ACCESS_CODE = "2580"

    const val MODE_DEFAULT = "default"
    const val MODE_CALCULATOR = "calculator"
    const val MODE_TELEGRAM = "telegram"

    fun selectedMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ICON_MODE, MODE_DEFAULT) ?: MODE_DEFAULT

    fun apply(context: Context, mode: String) {
        val selected = when (mode) {
            MODE_CALCULATOR -> MODE_CALCULATOR
            MODE_TELEGRAM -> MODE_TELEGRAM
            else -> MODE_DEFAULT
        }
        val pm = context.packageManager
        val aliases = listOf(
            DEFAULT_ALIAS to (selected == MODE_DEFAULT),
            CALCULATOR_ALIAS to (selected == MODE_CALCULATOR),
            TELEGRAM_ALIAS to (selected == MODE_TELEGRAM)
        )
        // Disable the non-selected aliases first, then enable exactly one launcher entry.
        aliases.filterNot { it.second }.forEach { (alias, _) ->
            pm.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        aliases.first { it.second }.let { (alias, _) ->
            pm.setComponentEnabledSetting(
                ComponentName(context, alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ICON_MODE, selected).apply()
    }

    /**
     * A numeric 4–12 digit code may be configured in UI settings.
     * Only a SHA-256 digest is persisted; the code itself is not written to preferences.
     */
    fun setAccessCode(context: Context, code: String): Boolean {
        if (code.length !in 4..12 || code.any { !it.isDigit() }) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCESS_CODE_HASH, digest(code)).apply()
        return true
    }

    fun verifyAccessCode(context: Context, candidate: String): Boolean {
        if (candidate.length !in 4..12 || candidate.any { !it.isDigit() }) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedHash = prefs.getString(KEY_ACCESS_CODE_HASH, null)
        val expectedHash = savedHash ?: digest(DEFAULT_ACCESS_CODE).also {
            prefs.edit().putString(KEY_ACCESS_CODE_HASH, it).apply()
        }
        return MessageDigest.isEqual(
            expectedHash.toByteArray(Charsets.UTF_8),
            digest(candidate).toByteArray(Charsets.UTF_8)
        )
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
