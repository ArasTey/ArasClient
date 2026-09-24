package com.aras.client.handler

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.entities.SubscriptionCache
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Locked Free group whose remote source is committed through the normal replacement path. */
object FreeSubManager {

    const val FREE_SUB_ID = "freesub-protected"
    private const val FREE_SUB_REMARKS = "Free"

    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/ArasTey/freesub/main/config.txt"

    private val syncMutex = Mutex()

    fun isFreeSubId(subscriptionId: String?): Boolean =
        subscriptionId == FREE_SUB_ID

    /** Fetches and transactionally replaces the complete Free profile list. */
    suspend fun sync(context: Context) = syncMutex.withLock {
        try {
            val subscription = ensureSubscription(context)
            val result = AngConfigManager.updateConfigViaSub(
                SubscriptionCache(FREE_SUB_ID, subscription)
            )
            if (result.successCount > 0) {
                protectAll()
                SubscriptionUpdater.syncOne(context, FREE_SUB_ID)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub sync failed", e)
        }
    }

    fun currentUrl(context: Context): String {
        return readAsset(context, "sub.txt")?.trim()
            ?.takeIf { it.startsWith("http") } ?: DEFAULT_URL
    }

    private fun ensureSubscription(context: Context): SubscriptionItem {
        val subscription = MmkvManager.decodeSubscription(FREE_SUB_ID) ?: SubscriptionItem()
        val url = currentUrl(context)
        if (subscription.remarks.isNullOrBlank()) subscription.remarks = FREE_SUB_REMARKS
        if (subscription.url != url) subscription.url = url
        subscription.enabled = true
        subscription.autoUpdate = true
        MmkvManager.encodeSubscription(FREE_SUB_ID, subscription)
        return subscription
    }

    private fun readAsset(context: Context, name: String): String? = runCatching {
        context.assets.open("freesub/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    /** Sets the Free group's URL from sub.txt / default without fetching. */
    fun applyUrl(context: Context) {
        try {
            ensureSubscription(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub applyUrl failed", e)
        }
    }

    /** Re-marks every profile in the Free group as protected. */
    fun protectAll() {
        try {
            ArasExportImportManager.markProtected(MmkvManager.decodeServerList(FREE_SUB_ID))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub protectAll failed", e)
        }
    }
}
