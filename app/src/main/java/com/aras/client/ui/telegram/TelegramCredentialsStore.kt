package com.aras.client.ui.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Telegram API credentials encrypted with an Android Keystore AES-GCM key.
 * The API hash is never written to preferences as plaintext.
 */
object TelegramCredentialsStore {
    private const val PREFS = "radar_telegram_secure"
    private const val KEY_CREDENTIALS = "api_credentials_v1"
    private const val KEY_ALIAS = "radar.telegram.api.credentials.v1"

    data class Credentials(val apiId: String, val apiHash: String)

    fun isValid(apiId: String, apiHash: String): Boolean =
        apiId.toIntOrNull()?.let { it > 0 } == true &&
            apiHash.matches(Regex("[0-9a-fA-F]{32}"))

    fun save(context: Context, apiId: String, apiHash: String): Boolean {
        if (!isValid(apiId.trim(), apiHash.trim())) return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val plain = "${apiId.trim()}\n${apiHash.trim()}".toByteArray(StandardCharsets.UTF_8)
            val encrypted = cipher.doFinal(plain)
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CREDENTIALS, packed).commit()
        }.getOrDefault(false)
    }

    fun load(context: Context): Credentials? = runCatching {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: error("No Telegram API credentials saved")
        val parts = packed.split(":", limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decoded = String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        val values = decoded.split("\n", limit = 2)
        require(values.size == 2 && isValid(values[0], values[1]))
        Credentials(values[0], values[1])
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_CREDENTIALS).apply()
    }

    private fun getOrCreateKey(): java.security.Key {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? java.security.Key)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
