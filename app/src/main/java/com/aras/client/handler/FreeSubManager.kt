package com.aras.client.handler

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.fmt.AmneziawgFmt
import com.aras.client.fmt.AnytlsFmt
import com.aras.client.fmt.VlessFmt
import com.aras.client.util.LogUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Bundled "Free" group. Two files in assets/freesub/ (ArasTey edits, users cannot):
 *
 *  - config.txt : one link per line (vless:// anytls:// awg:// ...) → imported directly
 *  - sub.txt    : one https:// subscription URL → fetched at every app start, its
 *                 links imported the same way (auto-updating free sub)
 *
 * Every imported profile is marked protected at the data layer:
 * users cannot view/edit/share/delete them — only ping and connect.
 */
object FreeSubManager {

    const val FREE_SUB_ID = "freesub-protected"
    private const val FREE_SUB_REMARKS = "Free"
    private const val MAP_KEY = "FREESUB_MAP"

    private val syncMutex = Mutex()

    private data class Entry(val hash: String, val guids: List<String>)

    private fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isFreeSubId(subscriptionId: String?): Boolean =
        subscriptionId == FREE_SUB_ID

    /**
     * Re-reads sub.txt and updates the Free group's URL (if changed) so the
     * normal update flow fetches the current link. Returns true when a URL
     * is set.
     */
    fun refreshUrl(context: Context): Boolean {
        val url = runCatching {
            context.assets.open("freesub/sub.txt").bufferedReader().use { it.readText().trim() }
        }.getOrNull().orEmpty()
        if (!url.startsWith("http")) return false
        val sub = MmkvManager.decodeSubscription(FREE_SUB_ID) ?: return false
        if (sub.url != url) {
            sub.url = url
            MmkvManager.encodeSubscription(FREE_SUB_ID, sub)
        }
        return true
    }

    fun protectAll() {
        try {
            MmkvManager.decodeServerList(FREE_SUB_ID).forEach { guid ->
                ArasExportImportManager.markProtected(guid)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub protectAll failed", e)
        }
    }

    /** Mirrors assets/freesub/ into the locked group. Safe on every app start. */
    suspend fun sync(context: Context) = syncMutex.withLock {
        try {
            ensureGroup()

            val stored = decodeMap()
            val seen = mutableSetOf<String>()

            // ---- config.txt : direct links ----
            val directText = readAsset(context, "config.txt")
            if (directText != null) {
                syncSource("config.txt", hashOf(directText), stored, seen) {
                    importLinks(directText)
                }
            }

            // ---- sub.txt : one https:// subscription URL, fetched at start ----
            val subUrl = readAsset(context, "sub.txt")?.trim()?.takeIf { it.startsWith("http") }
            if (subUrl != null) {
                val sub = MmkvManager.decodeSubscription(FREE_SUB_ID)
                if (sub == null || sub.url != subUrl) {
                    MmkvManager.encodeSubscription(
                        FREE_SUB_ID,
                        SubscriptionItem(remarks = FREE_SUB_REMARKS, url = subUrl, autoUpdate = true)
                    )
                }
                val fetched = runCatching {
                    val conn = java.net.URL(subUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "ArasClient/1.6")
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else null
                }.getOrNull()
                if (fetched != null) {
                    syncSource("sub.txt", hashOf(subUrl + "\n" + fetched), stored, seen) {
                        importLinks(fetched)
                    }
                }
            }

            // ---- remove profiles whose source disappeared ----
            stored.keys.filter { it !in seen }.forEach { name ->
                stored.remove(name)?.guids?.forEach { guid ->
                    MmkvManager.removeServer(guid)
                    ArasExportImportManager.forgetProtected(listOf(guid))
                }
            }

            persistMap(stored)
            protectAll()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub sync failed", e)
        }
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

    /**
     * Generic hash-gated import: if the source hash changed, removes the old
     * profiles of this source and imports the new ones via [import].
     */
    private inline fun syncSource(
        name: String,
        hash: String,
        stored: MutableMap<String, Entry>,
        seen: MutableSet<String>,
        import: () -> List<String>
    ) {
        val entry = stored[name]
        if (entry != null && entry.hash == hash) {
            seen.add(name)
            return
        }
        entry?.guids?.forEach { guid ->
            MmkvManager.removeServer(guid)
            ArasExportImportManager.forgetProtected(listOf(guid))
        }
        val guids = import()
        if (guids.isNotEmpty()) {
            stored[name] = Entry(hash, guids)
            seen.add(name)
        }
    }

    /** Parses link lines (vless:// anytls:// awg://) and commits each. */
    private fun importLinks(text: String): List<String> {
        val guids = mutableListOf<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val profile = when {
                line.startsWith(AppConfig.VLESS, true) -> VlessFmt.parse(line)
                line.startsWith(AppConfig.ANYTLS, true) -> AnytlsFmt.parse(line)
                line.startsWith(AppConfig.AMNEZIAWG, true) -> AmneziawgFmt.parse(line)
                line.startsWith(AppConfig.WIREGUARD, true) -> AmneziawgFmt.parse(line)
                else -> null
            } ?: return@forEach
            profile.subscriptionId = FREE_SUB_ID
            if (profile.remarks.isBlank() || profile.remarks.toLongOrNull() != null) {
                profile.remarks = "Free " + (guids.size + 1)
            }
            val guid = MmkvManager.encodeServerConfig("", profile)
            ArasExportImportManager.markProtected(guid)
            guids.add(guid)
        }
        return guids
    }

    private fun decodeMap(): MutableMap<String, Entry> {
        val raw = MmkvManager.decodeSettingsString(MAP_KEY) ?: return mutableMapOf()
        val result = mutableMapOf<String, Entry>()
        raw.split(";").filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                result[parts[0]] = Entry(parts[1], parts[2].split(",").filter { it.isNotBlank() })
            }
        }
        return result
    }

    private fun persistMap(map: Map<String, Entry>) {
        val raw = map.entries.joinToString(";") { (name, e) ->
            "$name|${e.hash}|${e.guids.joinToString(",")}"
        }
        MmkvManager.encodeSettings(MAP_KEY, raw)
    }
}
