package com.aras.client.handler

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.util.LogUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Bundled "Free" group — locked, updated via a subscription link.
 *
 * The Free group's URL defaults to the raw GitHub config.txt of this repo
 * (ArasTey edits that file; users fetch it with the update button).
 * A sub.txt override in assets/freesub/ can point anywhere else.
 *
 * Every imported profile is marked protected at the data layer:
 * users cannot view/edit/share them — only ping and connect.
 * Profiles CAN be deleted by the user.
 */
object FreeSubManager {

    const val FREE_SUB_ID = "freesub-protected"
    private const val FREE_SUB_REMARKS = "Free"

    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/ArasTey/ArasClient/main/app/src/main/assets/freesub/config.txt"

    private val syncMutex = Mutex()

    fun isFreeSubId(subscriptionId: String?): Boolean =
        subscriptionId == FREE_SUB_ID

    /**
     * Ensures the Free group exists with the current URL, fetches the config
     * list, and imports every link as a protected profile.
     * Safe to call on every app start and from the update button.
     */
    suspend fun sync(context: Context) = syncMutex.withLock {
        try {
            ensureGroup()

            val url = currentUrl(context)
            val sub = MmkvManager.decodeSubscription(FREE_SUB_ID)
            if (sub == null || sub.url != url) {
                MmkvManager.encodeSubscription(
                    FREE_SUB_ID,
                    SubscriptionItem(remarks = FREE_SUB_REMARKS, url = url, autoUpdate = true)
                )
            }

            // Fetch + import (append=false via the standard update path
            // replaces old configs with the new list)
            val fetched = fetchUrl(context, java.net.URL(url))
            if (fetched != null) {
                importLinks(fetched)
            }

            protectAll()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub sync failed", e)
        }
    }

    fun currentUrl(context: Context): String {
        // sub.txt overrides the default raw GitHub config.txt URL
        return readAsset(context, "sub.txt")?.trim()
            ?.takeIf { it.startsWith("http") } ?: DEFAULT_URL
    }

    private fun ensureGroup() {
        if (MmkvManager.decodeSubscription(FREE_SUB_ID) == null) {
            MmkvManager.encodeSubscription(
                FREE_SUB_ID,
                SubscriptionItem(remarks = FREE_SUB_REMARKS, url = "", autoUpdate = false)
            )
        }
    }

    private fun readAsset(context: Context, name: String): String? = runCatching {
        context.assets.open("freesub/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    fun fetchUrl(context: Context, url: java.net.URL): String? = runCatching {
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ArasClient/1.6")
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }.getOrNull()

    /** Parses link lines of ANY supported scheme and commits each as protected. */
    private fun importLinks(text: String) {
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val profile = AngConfigManager.parseAnyLink(line) ?: return@forEach
            profile.subscriptionId = FREE_SUB_ID
            if (profile.remarks.isBlank() || profile.remarks.toLongOrNull() != null) {
                profile.remarks = "Free " + protectCount()
            }
            val guid = MmkvManager.encodeServerConfig("", profile)
            ArasExportImportManager.markProtected(guid)
        }
    }

    private fun protectCount(): Int = MmkvManager.decodeServerList(FREE_SUB_ID).size + 1

    /** Sets the Free group's URL from sub.txt / default without fetching. */
    fun applyUrl(context: Context) {
        try {
            ensureGroup()
            val url = currentUrl(context)
            val sub = MmkvManager.decodeSubscription(FREE_SUB_ID)
            if (sub == null || sub.url != url) {
                MmkvManager.encodeSubscription(
                    FREE_SUB_ID,
                    SubscriptionItem(remarks = FREE_SUB_REMARKS, url = url, autoUpdate = true)
                )
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub applyUrl failed", e)
        }
    }

    /** Re-marks every profile in the Free group as protected. */
    fun protectAll() {
        try {
            MmkvManager.decodeServerList(FREE_SUB_ID).forEach { guid ->
                ArasExportImportManager.markProtected(guid)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub protectAll failed", e)
        }
    }
}
