package com.aras.client.handler

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import com.aras.client.util.Utils
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Soft-delete store for Free-group configs: a manual delete stashes the
 * profile so an accidental tap is recoverable, while a recorded identity
 * hash keeps the next Free subscription update from silently restoring
 * configs the user deliberately removed.
 */
object FreeTrashManager {

    private const val MAX_TRASH = 10

    private val trashMutex = Mutex()

    data class Entry(val item: ProfileItem)

    fun hasTrash(): Boolean = decodeItems().isNotEmpty()

    fun count(): Int = decodeItems().size

    /** UI-facing hook: a config was removed; the newest entry is at index 0. */
    fun newestTrashedItem(): ProfileItem? = decodeItems().firstOrNull()

    /** Stashes the config before removal. Returns false for non-Free configs. */
    suspend fun trashFreeConfig(guid: String): Boolean = trashMutex.withLock {
        val item = MmkvManager.decodeServerConfig(guid) ?: return@withLock false
        val subId = MmkvManager.getSubscriptionId(item.subscriptionId)
        if (subId != FreeSubManager.FREE_SUB_ID) return@withLock false
        val identity = identityOf(item) ?: return@withLock false
        val items = decodeItems()
        val ids = decodeIds()
        items.add(0, item)
        ids.add(0, identity)
        persistTrimmed(items, ids)
        LogUtil.i(AppConfig.TAG, "FreeTrash: trashed $guid (items=${items.size}, ids=${ids.size})")
        true
    }

    /** Puts the newest trashed config back into the Free group. */
    suspend fun undoTrash(): Boolean = trashMutex.withLock {
        val items = decodeItems()
        val ids = decodeIds()
        LogUtil.i(AppConfig.TAG, "FreeTrash: undo requested (items=${items.size}, ids=${ids.size})")
        if (items.isEmpty()) return@withLock false
        val item = items.removeAt(0)
        if (ids.isNotEmpty()) ids.removeAt(0)
        persistTrimmed(items, ids)
        reAdd(item)
    }

    /** Restores every trashed config (⋮ menu). Returns the count restored. */
    suspend fun restoreAll(): Int = trashMutex.withLock {
        val items = decodeItems()
        var restored = 0
        items.forEach { if (reAdd(it)) restored++ }
        persistTrimmed(mutableListOf(), mutableListOf())
        restored
    }

    /**
     * Deletes Free-group profiles that a subscription update re-imported even
     * though the user had deleted them earlier. Call after every Free re-import.
     */
    fun purgeResurrected() {
        try {
            val deleted = decodeIds().toHashSet()
            if (deleted.isEmpty()) return
            MmkvManager.decodeServerList(FreeSubManager.FREE_SUB_ID).forEach { guid ->
                val item = MmkvManager.decodeServerConfig(guid) ?: return@forEach
                if (identityOf(item) in deleted) {
                    MmkvManager.removeServer(guid)
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeTrash purgeResurrected failed", e)
        }
    }

    private fun reAdd(item: ProfileItem): Boolean {
        return try {
            val guid = Utils.getUuid()
            item.subscriptionId = FreeSubManager.FREE_SUB_ID
            item.addedTime = System.currentTimeMillis()
            MmkvManager.encodeServerConfig(guid, item)
            val list = MmkvManager.decodeServerList(FreeSubManager.FREE_SUB_ID)
            list.remove(guid)
            list.add(guid)
            MmkvManager.encodeServerList(list, FreeSubManager.FREE_SUB_ID)
            ArasExportImportManager.markProtected(guid)
            LogUtil.i(AppConfig.TAG, "FreeTrash: restored as $guid (group=${list.size})")
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeTrash restore failed", e)
            false
        }
    }

    private fun decodeItems(): MutableList<ProfileItem> {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_FREE_TRASH_ITEMS) ?: "[]"
        val parsed = runCatching {
            JsonUtil.fromJsonSafe(raw, Array<ProfileItem>::class.java)
        }.getOrNull()
        if (parsed == null) {
            LogUtil.w(AppConfig.TAG, "FreeTrash: items payload unreadable (${raw.length} chars)")
        }
        return parsed?.toMutableList().orEmpty().toMutableList()
    }

    private fun decodeIds(): MutableList<String> =
        (MmkvManager.decodeSettingsString(AppConfig.PREF_FREE_TRASH_IDS) ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .toMutableList()

    private fun persistTrimmed(items: MutableList<ProfileItem>, ids: MutableList<String>) {
        while (items.size > MAX_TRASH) {
            items.removeAt(items.lastIndex)
        }
        while (ids.size > MAX_TRASH) {
            ids.removeAt(ids.lastIndex)
        }
        MmkvManager.encodeSettings(
            AppConfig.PREF_FREE_TRASH_ITEMS,
            JsonUtil.toJson(items.toList())
        )
        MmkvManager.encodeSettings(
            AppConfig.PREF_FREE_TRASH_IDS,
            ids.joinToString(",")
        )
    }

    /**
     * Content identity used to recognize the same config when the Free
     * subscription re-imports it under a new guid. Deliberately excludes
     * remarks/subscription metadata, which the importer rewrites.
     */
    private fun identityOf(item: ProfileItem): String? {
        return try {
            val raw = listOfNotNull(
                item.configType?.name,
                item.server?.takeIf { it.isNotBlank() },
                item.serverPort?.takeIf { it.isNotBlank() },
                item.password?.takeIf { it.isNotBlank() },
                item.username?.takeIf { it.isNotBlank() },
                item.publicKey?.takeIf { it.isNotBlank() },
            ).joinToString("|")
            val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "FreeTrash identity failed", e)
            null
        }
    }
}
