package com.aras.client.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.aras.client.AppConfig
import com.aras.client.core.CoreConfigManager
import com.aras.client.dto.SubscriptionUpdateResult
import com.aras.client.dto.UrlContentRequest
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.SubscriptionCache
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isNotNullEmpty
import com.aras.client.fmt.CustomFmt
import com.aras.client.fmt.Hysteria2Fmt
import com.aras.client.fmt.ShadowsocksFmt
import com.aras.client.fmt.SocksFmt
import com.aras.client.fmt.TrojanFmt
import com.aras.client.fmt.ArasFmt
import com.aras.client.fmt.AmneziawgFmt
import com.aras.client.fmt.AnytlsFmt
import com.aras.client.fmt.VlessFmt
import com.aras.client.fmt.VmessFmt
import com.aras.client.fmt.WireguardFmt
import com.aras.client.util.HttpUtil
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import com.aras.client.util.QRCodeDecoder
import com.aras.client.util.Utils
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object AngConfigManager {

    private data class ParsedProfile(
        val profile: ProfileItem,
        val rawConfig: String? = null,
    )

    // Parser mapping for different config types (lazy initialized)
    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
            EConfigType.ANYTLS.protocolScheme to AnytlsFmt::parse,
            EConfigType.AMNEZIAWG.protocolScheme to AmneziawgFmt::parse,
            AppConfig.ARASFMTS to ArasFmt::parse
        )
    }

    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    /**
     * Returns the share URI text for a config (for TXT file export).
     */
    fun getShareText(guid: String): String {
        return try {
            shareConfig(guid) ?: ""
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to build share text for GUID: $guid", e)
            ""
        }
    }

    /**
     * Builds a TXT block with the share links of the given configs.
     */
    fun getShareTextForGuids(guids: List<String>): String {
        return guids.mapNotNull { guid ->
            try {
                shareConfig(guid)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to build share text for GUID: $guid", e)
                null
            }
        }.joinToString("\n")
    }

    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            // Protected configs must not leak their raw Xray JSON either.
            if (ArasExportImportManager.isProtected(guid)) return -1
            val result = CoreConfigManager.getXrayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private fun shareConfig(guid: String): String {
        try {
            // Protected configs (imported from a Protected .arasc file) must never
            // leave the app as raw URIs — enforced at the data layer, not just UI.
            if (ArasExportImportManager.isProtected(guid)) return ""

            val config = MmkvManager.decodeServerConfig(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                EConfigType.ANYTLS -> AnytlsFmt.toUri(config)
                EConfigType.AMNEZIAWG -> AmneziawgFmt.toUri(config)
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        return try {
            var count = parseBatchConfig(Utils.decode(server), subid, append)
            if (count <= 0) {
                count = parseBatchConfig(server, subid, append)
            }
            if (count <= 0) {
                count = parseCustomConfigServer(server, subid, append)
            }

            var newSubIds = parseBatchSubscription(server)
            if (newSubIds.isEmpty()) {
                newSubIds = parseBatchSubscription(Utils.decode(server))
            }
            // Only fetch the subscriptions that were just added; existing ones
            // keep their current content until the user updates them manually.
            newSubIds.forEach { guid ->
                MmkvManager.decodeSubscription(guid)?.let {
                    updateConfigViaSub(SubscriptionCache(guid, it))
                }
            }

            count to newSubIds.size
        } catch (e: ProfileStorageException) {
            LogUtil.e(AppConfig.TAG, "Failed to store imported profiles", e)
            0 to 0
        }
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The number of subscriptions parsed.
     */
    private fun parseBatchSubscription(servers: String?): List<String> {
        try {
            if (servers == null) {
                return emptyList()
            }

            val guids = mutableListOf<String>()
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        importUrlAsSubscription(str)?.let { guids.add(it) }
                    }
                }
            return guids
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return emptyList()
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            val subItem = MmkvManager.decodeSubscription(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            servers.lines()
                .distinct()
                .reversed()
                .forEach {
                    val config = parseConfig(it, subid, subItem)
                    if (config != null) {
                        configs.add(config)
                    }
                }

            if (configs.isNotEmpty()) {
                commitProfiles(
                    configs = configs.map(::ParsedProfile),
                    subid = subid,
                    append = append,
                )
            }

            return configs.size
        } catch (e: ProfileStorageException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    /**
     * Commits parsed profiles before removing the profiles they replace.
     *
     * @param configs The parsed profiles to save.
     * @param subid The subscription ID.
     * @param append Whether to append to the existing server list.
     */
    private fun commitProfiles(
        configs: List<ParsedProfile>,
        subid: String,
        append: Boolean,
    ) {
        val keyToProfile = linkedMapOf<String, ProfileItem>()
        val rawConfigs = mutableMapOf<String, String>()

        configs.forEach { parsed ->
            val key = Utils.getUuid()
            keyToProfile[key] = parsed.profile
            parsed.rawConfig?.let { raw -> rawConfigs[key] = raw }
        }

        MmkvManager.saveServerProfiles(
            profiles = keyToProfile,
            rawConfigs = rawConfigs,
            subscriptionId = subid,
            append = append,
        )
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    val configs = serverList.reversed().map { srv ->
                        val config = CustomFmt.parse(JsonUtil.toJson(srv))
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        ParsedProfile(
                            profile = config,
                            rawConfig = JsonUtil.toJsonPretty(srv) ?: "",
                        )
                    }
                    commitProfiles(configs, subid, append)
                    return configs.size
                }
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                )
                return 1
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val isAmneziaConf = server.contains("Jc", ignoreCase = false) &&
                    server.split('\n').any {
                        val key = it.substringBefore('=').trim().lowercase()
                        key in setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")
                    }
                val config = if (isAmneziaConf) {
                    AmneziawgFmt.parseAmneziaConfFile(server)
                } else {
                    WireguardFmt.parseWireguardConfFile(server)
                }
                config.subscriptionId = subid
                config.description = generateDescription(config)
                commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                )
                return 1
            } catch (e: ProfileStorageException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
                if (str.startsWith(scheme)) parser(str) else null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            if (str.startsWith(AppConfig.ARASFMTS, ignoreCase = true)
                && config.policyGroupSubscriptionId == "self"
            ) {
                config.policyGroupSubscriptionId = subid
            }

            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            if (subscriptions.isEmpty()) return SubscriptionUpdateResult()

            // Fetch subscriptions concurrently; profile writes stay serialized
            // by the storage lock inside updateConfigViaSub.
            runBlocking {
                val semaphore = Semaphore(8)
                subscriptions.map { subscription ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit { updateConfigViaSub(subscription) }
                    }
                }.awaitAll().fold(SubscriptionUpdateResult()) { acc, result ->
                    acc + result
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            LogUtil.i(AppConfig.TAG, url)
            // Cache-buster: provider/CDN caches were returning stale copies,
            // freezing the traffic/expiry numbers between updates.
            val fetchUrl = if (url.contains("?")) "$url&_t=${System.currentTimeMillis()}"
                           else "$url?_t=${System.currentTimeMillis()}"
            val userAgent = it.subscription.userAgent
            val requestHeaders = it.subscription.requestHeaders
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()

            var fetched: HttpUtil.FetchedSubscription? = try {
                val httpPort = SettingsManager.getHttpPort()
                HttpUtil.getSubscriptionWithHeaders(
                    UrlContentRequest(
                        url = fetchUrl,
                        userAgent = userAgent,
                        requestHeaders = requestHeaders,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
                null
            }
            if (fetched == null || fetched.body.isEmpty()) {
                fetched = try {
                    HttpUtil.getSubscriptionWithHeaders(
                        UrlContentRequest(
                            url = fetchUrl,
                            userAgent = userAgent,
                            requestHeaders = requestHeaders
                        )
                    )
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e)
                    null
                }
            }
            if (fetched == null || fetched.body.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            val configText = fetched.body

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                applySubscriptionMetadata(it.subscription, fetched.headers)
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                com.aras.client.util.SubscriptionUpdateNotifier.notify(it.guid)
                LogUtil.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Removes invalid server configurations for a subscription.
     *
     * @param subId The subscription ID.
     */
    fun removeInvalidServer(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        val invalidServers = serverList.filter {
            val aff = MmkvManager.decodeServerAffiliationInfo(it)
            aff != null && aff.testDelayMillis < 0L
        }
        MmkvManager.removeServers(invalidServers, subId)
    }

    /**
     * Sorts servers by test results for a subscription.
     *
     * @param subId The subscription ID.
     */
    /**
     * Returns the guid of the server with the lowest successful test delay
     * across all groups, or null when no server has been tested successfully.
     */
    fun bestTestedServerGuid(): String? {
        var bestGuid: String? = null
        var bestDelay = Long.MAX_VALUE
        val groupIds = listOf("") + MmkvManager.decodeSubsList()
        groupIds.forEach { subId ->
            MmkvManager.decodeServerList(subId).forEach { guid ->
                val delay =
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                if (delay > 0L && delay < bestDelay) {
                    bestDelay = delay
                    bestGuid = guid
                }
            }
        }
        return bestGuid
    }

    /**
     * Parses subscription metadata response headers into the SubscriptionItem:
     * traffic used/total + expiry (subscription-userinfo), announcement and
     * creator message (profile-title / announce-*). Header values only
     * overwrite when present, so manual values are never cleared.
     */
    fun applySubscriptionMetadata(sub: SubscriptionItem, headers: Map<String, String>) {
        headers["subscription-userinfo"]?.let { raw ->
            var used = -1L
            var total = -1L
            var expire = -1L
            raw.split(";").forEach { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) {
                    val v = kv[1].trim().toLongOrNull() ?: return@forEach
                    when (kv[0].trim().lowercase()) {
                        "upload", "download" -> used = if (used < 0) v else used + v
                        "total" -> total = v
                        "expire" -> expire = v
                    }
                }
            }
            if (used >= 0) sub.trafficUsed = used
            if (total >= 0) sub.trafficTotal = total
            if (expire >= 0) sub.expireAt = expire
        }
        headers["announce"]?.let { v -> decodeHeaderValue(v)?.let { sub.announcement = it } }
        headers["announcement"]?.let { v -> decodeHeaderValue(v)?.let { sub.announcement = it } }
        headers["announce-url"]?.let {
            if (it.isNotBlank() && sub.announcement.isNullOrBlank()) {
                sub.announcement = decodeHeaderValue(it)
            }
        }
        headers["profile-title"]?.let { v -> decodeHeaderValue(v)?.let { sub.creatorMessage = it } }
    }

    /** Decodes "base64:..."-prefixed header values used by many panels. */
    private fun decodeHeaderValue(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("base64:", ignoreCase = true)) {
            try {
                val decoded = Utils.decode(trimmed.substringAfter(':'))
                decoded.ifBlank { null }
            } catch (_: Exception) {
                trimmed
            }
        } else {
            trimmed
        }
    }

    fun sortByTestResultsForSub(subId: String) {
        val serverList = MmkvManager.decodeServerList(subId)
        if (serverList.isEmpty()) return

        val sorted = serverList
            .map { guid ->
                val delay =
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                guid to if (delay <= 0L) Long.MAX_VALUE else delay
            }
            .sortedBy { it.second }
            .map { it.first }
            .toMutableList()
        MmkvManager.encodeServerList(sorted, subId)
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }
        return count
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The number of subscriptions imported.
     */
    private fun importUrlAsSubscription(url: String): String? {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return null
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        // Plain-http links stay fetchable without extra confirmation.
        if (url.startsWith("http://")) {
            subItem.allowInsecureUrl = true
        }
        return MmkvManager.encodeSubscription("", subItem)
    }

    /** Generates a description for the profile.
     *
     * @param profile The profile item.
     * @return The generated description.
     */
    fun generateDescription(profile: ProfileItem): String {
        // Hide xxx:xxx:***/xxx.xxx.xxx.***
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""

        return "$addrPart : ${port ?: ""}"
    }
}
