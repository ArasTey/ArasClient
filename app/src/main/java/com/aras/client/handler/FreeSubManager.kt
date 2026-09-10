package com.aras.client.handler

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.AmneziawgFmt
import com.aras.client.fmt.AnytlsFmt
import com.aras.client.fmt.VlessFmt
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.security.MessageDigest

/**
 * Bundled "Free" subscription: configs ship inside the APK assets
 * (assets/freesub/) and are synced into a locked group at app start.
 *
 * - Profiles are marked protected at the data layer (share/edit/delete blocked)
 * - The group itself has no URL / auto-update / traffic info
 * - Only ping (test) and connect are exposed to the user
 *
 * ArasTey adds/updates configs by editing files in assets/freesub/ and
 * rebuilding; the app mirrors the folder on every launch.
 */
object FreeSubManager {

    private val syncMutex = Mutex()
    private val inFlight = AtomicBoolean(false)

    const val FREE_SUB_ID = "freesub-protected"
    private const val FREE_SUB_REMARKS = "Free"
    private const val MAP_KEY = "FREESUB_FILE_MAP"

    private fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isFreeSubId(subscriptionId: String?): Boolean =
        subscriptionId == FREE_SUB_ID

    /**
     * Mirrors assets/freesub/ into the locked group.
     * Safe to call on every app start: only changed files are re-imported.
     */
    suspend fun sync(context: Context) = syncMutex.withLock {
        // Overlapping syncs (restart race) would double-import the same files.
        if (!inFlight.compareAndSet(false, true)) return@withLock
        try {
            val names = context.assets.list("freesub")?.toList() ?: emptyList()

            // Ensure the locked group exists
            if (MmkvManager.decodeSubscription(FREE_SUB_ID) == null) {
                MmkvManager.encodeSubscription(
                    FREE_SUB_ID,
                    SubscriptionItem(
                        remarks = FREE_SUB_REMARKS,
                        url = "",
                        enabled = true,
                        autoUpdate = false,
                    )
                )
            }

            val stored = decodeMap()
            val seen = mutableSetOf<String>()

            names.filter { it.endsWith(".conf") || it.endsWith(".txt") }.forEach { name ->
                val text = runCatching {
                    context.assets.open("freesub/$name").bufferedReader().use { it.readText() }
                }.getOrNull() ?: return@forEach

                val hash = hashOf(text)
                val entry = stored[name]
                if (entry != null && entry.first == hash) {
                    seen.add(name)
                    return@forEach
                }

                // Remove previously imported profiles of this file
                entry?.second?.forEach { guid ->
                    MmkvManager.removeServer(guid)
                    ArasExportImportManager.forgetProtected(listOf(guid))
                }

                val guids = importFile(name, text)
                if (guids.isNotEmpty()) {
                    stored[name] = hash to guids
                    seen.add(name)
                }
            }

            // Remove profiles whose source file was deleted
            stored.keys.filter { it !in seen }.forEach { name ->
                stored.remove(name)?.second?.forEach { guid ->
                    MmkvManager.removeServer(guid)
                    ArasExportImportManager.forgetProtected(listOf(guid))
                }
            }

            persistMap(stored)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeSub sync failed", e)
        } finally {
            inFlight.set(false)
        }
    }

    /** Returns imported profile guids. */
    private fun importFile(name: String, text: String): List<String> {
        val guids = mutableListOf<String>()

        fun commit(profile: ProfileItem) {
            profile.subscriptionId = FREE_SUB_ID
            if (profile.remarks.isBlank() || profile.remarks.toLongOrNull() != null) {
                profile.remarks = name.substringBeforeLast('.')
            }
            val guid = MmkvManager.encodeServerConfig("", profile)
            ArasExportImportManager.markProtected(guid)
            guids.add(guid)
        }

        val trimmed = text.trim()
        if (trimmed.contains("[Interface]")) {
            val profile = AmneziawgFmt.parseAmneziaConfFile(text)
            commit(profile)
            return guids
        }

        // Link file: one link per non-empty line
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val profile = when {
                line.startsWith(AppConfig.AMNEZIAWG, true) -> AmneziawgFmt.parse(line)
                line.startsWith(AppConfig.ANYTLS, true) -> AnytlsFmt.parse(line)
                line.startsWith(AppConfig.VLESS, true) -> VlessFmt.parse(line)
                else -> null
            } ?: return@forEach
            commit(profile)
        }
        return guids
    }

    private fun decodeMap(): MutableMap<String, Pair<String, List<String>>> {
        val raw = MmkvManager.decodeSettingsString(MAP_KEY) ?: return mutableMapOf()
        val result = mutableMapOf<String, Pair<String, List<String>>>()
        raw.split(";").filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                result[parts[0]] = parts[1] to parts[2].split(",").filter { it.isNotBlank() }
            }
        }
        return result
    }

    private fun persistMap(map: Map<String, Pair<String, List<String>>>) {
        val raw = map.entries.joinToString(";") { (name, pair) ->
            "$name|${pair.first}|${pair.second.joinToString(",")}"
        }
        MmkvManager.encodeSettings(MAP_KEY, raw)
    }
}
