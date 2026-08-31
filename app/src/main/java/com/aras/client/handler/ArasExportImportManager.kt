package com.aras.client.handler

import com.aras.client.AppConfig
import com.aras.client.core.ArascContainer
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil

/**
 * Business logic for the proprietary ".arasc" export/import format.
 *
 * Protected config policy is enforced here at the data layer: a protected
 * profile can never be serialized back out through the raw share/export
 * paths, regardless of what the UI requests.
 */
object ArasExportImportManager {

    // ------------------------------------------------------------- payload DTO

    data class ArascProfilePayload(
        val profile: ProfileItem,
        val isProtected: Boolean = false,
    )

    data class ArascSubscriptionPayload(
        val remarks: String,
        val configs: List<ArascProfilePayload>,
        /** Present in v2: the subscription group's own identity (no secrets). */
        val subscriptionId: String? = null,
    )

    /** v2: a real subscription (URL, update settings) carried in the file. */
    data class ArascSubLinkPayload(
        val guid: String,
        val item: SubscriptionItem,
        val profileGuids: List<String>,
    )

    data class ArascPayload(
        val formatVersion: Int = 2,
        val exportedAt: Long = System.currentTimeMillis(),
        val subscriptions: List<ArascSubscriptionPayload> = emptyList(),
        /** v2: real subscriptions to recreate on import. */
        val subLinks: List<ArascSubLinkPayload> = emptyList(),
        /** v2: exporter's note / explanation, shown on import. */
        val note: String? = null,
    )

    // ------------------------------------------------------------- protected set

    /** GUIDs of profiles imported from a Protected container. */
    private val protectedGuids: MutableSet<String> by lazy {
        val raw = MmkvManager.decodeSettingsString(AppConfig.PREF_ARASC_PROTECTED_GUIDS) ?: ""
        raw.split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    private fun persistProtected() {
        MmkvManager.encodeSettings(
            AppConfig.PREF_ARASC_PROTECTED_GUIDS, protectedGuids.joinToString(",")
        )
    }

    fun isProtected(guid: String): Boolean = protectedGuids.contains(guid)

    fun forgetProtected(guids: List<String>) {
        if (guids.any { protectedGuids.remove(it) }) persistProtected()
    }

    // ------------------------------------------------------------- export

    /**
     * Builds a .arasc file for the selected profile guids.
     * Protected configs are always excluded from raw export at this layer.
     *
     * @param items list of (group title, profile guids); a blank title means
     *   standalone configs.
     */
    /**
     * Builds a .arasc v2 file.
     *
     * @param items group title → selected profile guids
     * @param subLinks real subscriptions to carry (guid → (item, profile guids));
     *   only include when the user opted into sharing subscription links
     * @param note exporter's note shown to the receiver on import
     */
    fun buildExport(
        items: List<Triple<String, String, List<String>>>,
        password: CharArray?,
        subLinks: Map<String, Pair<SubscriptionItem, List<String>>> = emptyMap(),
        note: String? = null,
    ): ByteArray {
        val subsPayload = items.mapNotNull { (subGuid, title, guids) ->
            val configs = guids.mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                if (isProtected(guid)) return@mapNotNull null // never re-export raw protected configs
                ArascProfilePayload(profile, isProtected = false)
            }
            if (configs.isEmpty()) return@mapNotNull null
            ArascSubscriptionPayload(
                remarks = title,
                configs = configs,
                subscriptionId = subGuid.ifBlank { null },
            )
        }
        val linksPayload = subLinks.mapNotNull { (guid, pair) ->
            val (item, guids) = pair
            val profileGuids = guids.filter { !isProtected(it) }
            if (item.url.isBlank() || profileGuids.isEmpty()) return@mapNotNull null
            ArascSubLinkPayload(guid, item, profileGuids)
        }
        val payload = ArascPayload(
            subscriptions = subsPayload,
            subLinks = linksPayload,
            note = note?.takeIf { it.isNotBlank() },
        )
        return ArascContainer.encode(JsonUtil.toJson(payload), password)
    }

    // ------------------------------------------------------------- import

    /**
     * One parsing attempt. Returns the stage so the UI can prompt or
     * re-prompt for the password.
     */
    fun tryImport(bytes: ByteArray, password: CharArray?): ImportAttempt {
        return try {
            when (val header = ArascContainer.peekHeader(bytes)) {
                ArascContainer.Header.NotArasc -> ImportAttempt.Error(ImportError.NOT_ARASC)
                ArascContainer.Header.UnsupportedVersion ->
                    ImportAttempt.Error(ImportError.UNSUPPORTED_VERSION)
                is ArascContainer.Header.Ok -> {
                    if (header.passwordProtected && password == null) {
                        return ImportAttempt.NeedPassword
                    }
                    val json = try {
                        ArascContainer.decode(bytes, password)
                    } catch (e: ArascContainer.ArascException) {
                        // Protected files: any decrypt failure with a supplied
                        // password means wrong password.
                        if (header.passwordProtected) {
                            return ImportAttempt.WrongPassword
                        }
                        throw e
                    }
                    val payload = JsonUtil.fromJsonSafe(json, ArascPayload::class.java)
                        ?: return ImportAttempt.Error(ImportError.CORRUPTED)
                    if (payload.subscriptions.isEmpty()) {
                        return ImportAttempt.Error(ImportError.EMPTY)
                    }
                    val imported = importPayload(payload, header.passwordProtected)
                    ImportAttempt.Done(imported, payload.note)
                }
            }
        } catch (e: ArascContainer.ArascException) {
            LogUtil.w(AppConfig.TAG, "arasc import rejected: ${e.message}")
            ImportAttempt.Error(ImportError.CORRUPTED)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "arasc import failed", e)
            ImportAttempt.Error(ImportError.CORRUPTED)
        }
    }

    sealed class ImportAttempt {
        object NeedPassword : ImportAttempt()
        object WrongPassword : ImportAttempt()
        data class Done(val imported: Int, val note: String? = null) : ImportAttempt()
        data class Error(val error: ImportError) : ImportAttempt()
    }

    enum class ImportError {
        NOT_ARASC,
        UNSUPPORTED_VERSION,
        CORRUPTED,
        EMPTY,
    }

    /**
     * Imports payload groups. Every group becomes (or merges into) a local
     * subscription so configs stay grouped; profiles from a protected file
     * are registered in the protected set immediately.
     */
    private fun importPayload(payload: ArascPayload, markProtected: Boolean): Int {
        var imported = 0

        // v2: recreate real subscriptions (URL etc) first; configs land in them.
        val linkMap = mutableMapOf<String, String>() // original guid -> new guid
        payload.subLinks.forEach { link ->
            val newGuid = MmkvManager.encodeSubscription("", link.item)
            linkMap[link.guid] = newGuid
        }

        payload.subscriptions.forEach { group ->
            val subId = if (group.subscriptionId != null && linkMap.containsKey(group.subscriptionId)) {
                linkMap[group.subscriptionId]!!
            } else if (group.remarks.isNotBlank()) {
                // v2 groups carry the original sub guid in remarks fallback:
                // reuse an already-created sub with the same remarks if any.
                linkMap.entries
                    .firstOrNull {
                        MmkvManager.decodeSubscription(it.value)?.remarks == group.remarks
                    }?.value
                    ?: MmkvManager.encodeSubscription(
                        "", SubscriptionItem(remarks = group.remarks)
                    )
            } else {
                MmkvManager.encodeSubscription(
                    "", SubscriptionItem(remarks = "ArasClient")
                )
            }
            group.configs.forEach { cfg ->
                val profile = cfg.profile
                profile.subscriptionId = subId
                val guid = MmkvManager.encodeServerConfig("", profile)
                if (markProtected) {
                    protectedGuids.add(guid)
                }
                imported++
            }
        }
        if (protectedGuids.isNotEmpty()) persistProtected()
        return imported
    }
}
