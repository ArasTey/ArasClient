@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.cluvex.zedsecure.data.config

import dev.cluvex.zedsecure.platform.AppInfo
import dev.cluvex.zedsecure.platform.KeyValueStore
import dev.cluvex.zedsecure.platform.currentTimeMillis
import dev.cluvex.zedsecure.platform.HttpTextResponse
import dev.cluvex.zedsecure.platform.httpGetResponse
import dev.cluvex.zedsecure.platform.newId
import dev.cluvex.zedsecure.crypto.ZsxCrypto
import dev.cluvex.zedsecure.crypto.ZsxExpiredException
import dev.cluvex.zedsecure.crypto.ZsxMetadata
import dev.cluvex.zedsecure.crypto.ZsxSealRequest
import dev.cluvex.zedsecure.domain.config.AmneziaLink
import dev.cluvex.zedsecure.domain.config.AutoMember
import dev.cluvex.zedsecure.domain.config.AutoSelectBuild
import dev.cluvex.zedsecure.domain.config.AutoSelectIds
import dev.cluvex.zedsecure.domain.config.AutoSelectTuning
import dev.cluvex.zedsecure.domain.config.RuleGroup
import dev.cluvex.zedsecure.domain.config.SniSpoofLink
import dev.cluvex.zedsecure.domain.config.ZedLink
import dev.cluvex.zedsecure.domain.config.ConfigParser
import dev.cluvex.zedsecure.domain.config.NpvTunnelParser
import dev.cluvex.zedsecure.domain.config.AwgConfig
import dev.cluvex.zedsecure.domain.config.ShareLink
import dev.cluvex.zedsecure.domain.config.CustomConfig
import dev.cluvex.zedsecure.domain.config.ProfileSource
import dev.cluvex.zedsecure.domain.config.OvpnConfig
import dev.cluvex.zedsecure.domain.config.Protocol
import dev.cluvex.zedsecure.domain.config.ServerConfig
import dev.cluvex.zedsecure.domain.config.SsdLink
import dev.cluvex.zedsecure.domain.config.SshLink
import dev.cluvex.zedsecure.domain.config.SingBoxJson
import dev.cluvex.zedsecure.domain.config.SingBoxLinks
import dev.cluvex.zedsecure.domain.config.SingBoxOnlyServers
import dev.cluvex.zedsecure.domain.config.Subscription
import dev.cluvex.zedsecure.domain.config.SubscriptionHeaders
import dev.cluvex.zedsecure.domain.config.SubscriptionMeta
import dev.cluvex.zedsecure.domain.config.SubscriptionParser
import dev.cluvex.zedsecure.domain.config.SubscriptionSchedule
import dev.cluvex.zedsecure.domain.config.VpnProfile
import dev.cluvex.zedsecure.domain.config.XrayJsonBuilder
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class ProxyChainMemberMissingException(val missing: List<String>) :
    Exception("Proxy chain is missing ${missing.size} member(s): ${missing.joinToString(", ")}")

class CrossChainUnsupportedException(val reason: String, val engine: String) :
    Exception("Cross chain unsupported ($reason): $engine") {
    companion object {
        const val CARRIER = "carrier"
        const val DIALER = "dialer"
    }
}

class CrossChainUdpUnsupportedException(val protocol: String, val carrier: String) :
    Exception("$protocol needs UDP, which $carrier cannot relay")

class ConfigRepository(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prettyJson = Json { prettyPrint = true }

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow(loadSubscriptions())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _activeId = MutableStateFlow(store.getString(KEY_ACTIVE))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun activeProfile(): VpnProfile? = _activeId.value?.let { profile(it) }

    fun profile(id: String): VpnProfile? =
        _profiles.value.firstOrNull { it.id == id } ?: autoSelectProfile(id)

    private fun activeExists(): Boolean = _activeId.value?.let { profile(it) } != null

    fun importText(text: String, subscriptionId: String = ""): Result<Int> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty" }

        val npvProfiles = NpvTunnelParser.parse(trimmed)
        if (npvProfiles != null) {
            var added = 0
            var lastError: Throwable? = null
            npvProfiles.forEach { item ->
                runCatching {
                    if (!item.rawJson.isNullOrBlank()) {
                        addRawJson(item.rawJson, name = item.name, subscriptionId = subscriptionId).getOrThrow()
                    } else {
                        importText(item.link ?: throw IllegalArgumentException("NPV profile has no importable payload"), subscriptionId).getOrThrow()
                    }
                }.onSuccess { added++ }.onFailure { lastError = it }
            }
            if (added == 0) {
                throw IllegalArgumentException(lastError?.message ?: "NPV Tunnel file contains no importable profiles")
            }
            return@runCatching added
        }

        val sshLines = trimmed.lines().map { it.trim() }.filter { SshLink.isSshLink(it) }
        if (sshLines.isNotEmpty()) {
            var added = 0
            sshLines.forEach { line ->
                SshLink.parse(line)?.let { (nm, prof) -> addSsh(prof, nm); added++ }
            }
            if (added > 0) return@runCatching added
            throw IllegalArgumentException("no usable ssh:// config found")
        }

        if (ZedLink.isZedLink(trimmed)) return@runCatching importZedLink(trimmed)

        if (SniSpoofLink.isSniSpoofLink(trimmed)) {
            val (nm, prof) = SniSpoofLink.parse(trimmed)
                ?: throw IllegalArgumentException("invalid snispoof:// link")
            addSniSpoof(prof, nm)
            return@runCatching 1
        }

        if (SsdLink.isSsdLink(trimmed)) {
            val links = SsdLink.parse(trimmed)
            require(links.isNotEmpty()) { "no servers in this ssd:// subscription" }
            return@runCatching importText(links.joinToString("\n"), subscriptionId).getOrThrow()
        }

        if (AmneziaLink.isAmneziaLink(trimmed) || AmneziaLink.isAmneziaJson(trimmed) ||
            AmneziaLink.isAmneziaBlob(trimmed)
        ) {
            when (val payload = AmneziaLink.parse(trimmed)) {
                is AmneziaLink.Payload.Configs -> {
                    var added = 0
                    var lastError: Throwable? = null
                    payload.items.forEach { item ->

                        importText(item.text, subscriptionId)
                            .onSuccess { added += it }
                            .onFailure { lastError = it }
                    }
                    if (added == 0) {
                        throw IllegalArgumentException(
                            lastError?.message?.takeIf { it.isNotBlank() } ?: "unsupported vpn:// config",
                        )
                    }
                    return@runCatching added
                }

                AmneziaLink.Payload.Backup -> throw AmneziaLink.UnsupportedException(
                    AmneziaLink.Reason.Backup,
                    "this is an Amnezia backup - restore it, don't import it",
                )
                AmneziaLink.Payload.Subscription -> throw AmneziaLink.UnsupportedException(
                    AmneziaLink.Reason.Subscription,
                    "Amnezia Free/Premium subscriptions need Amnezia's own servers",
                )
                is AmneziaLink.Payload.Unusable -> throw AmneziaLink.UnsupportedException(
                    AmneziaLink.Reason.NoRunnableContainer,
                    "no runnable protocol in this Amnezia config (${payload.reason})",
                )
                AmneziaLink.Payload.Unsupported ->
                    throw IllegalArgumentException("unsupported vpn:// config")
            }
        }

        if (OvpnConfig.looksLikeOvpn(trimmed)) {
            addOvpn(trimmed, subscriptionId = subscriptionId).getOrThrow()
            return@runCatching 1
        }

        if (ConfigParser.looksLikeWireguardConf(trimmed)) {
            val link = ShareLink.build(ConfigParser.parseWireguardConf(trimmed))
            return@runCatching importText(link, subscriptionId).getOrThrow()
        }

        val bodies = buildList {
            runCatching { ConfigParser.decodeBase64Utf8(trimmed) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(it) }
            add(trimmed)
        }.distinct()

        bodies.forEach { body ->
            if (NpvTunnelParser.isNpvText(body)) {
                val profiles = NpvTunnelParser.parse(body) ?: return@forEach
                var added = 0
                var lastError: Throwable? = null
                profiles.forEach { item ->
                    runCatching {
                        if (!item.rawJson.isNullOrBlank()) {
                            addRawJson(item.rawJson, name = item.name, subscriptionId = subscriptionId).getOrThrow()
                        } else {
                            importText(item.link ?: throw IllegalArgumentException("NPV profile has no importable payload"), subscriptionId).getOrThrow()
                        }
                    }.onSuccess { added++ }.onFailure { lastError = it }
                }
                if (added > 0) return@runCatching added
                throw IllegalArgumentException(lastError?.message ?: "NPV Tunnel file contains no importable profiles")
            }
        }

        bodies.forEach { body ->

            importSingBoxJson(body, subscriptionId)?.let { return@runCatching it }
        }

        bodies.forEach { body ->
            val custom = importCustomJson(body, subscriptionId)
            if (custom > 0) return@runCatching custom
        }

        val links = bodies.map { SubscriptionParser.extractLinks(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no supported links found")

        val current = _profiles.value
        val fresh = mutableListOf<VpnProfile>()
        var duplicates = 0
        links.forEach { link ->
            runCatching {
                if (SingBoxLinks.handles(link)) {
                    val parsed = SingBoxLinks.parse(link)
                    val server = SingBoxJson.servers(parsed.fragment).first()
                    VpnProfile.fromSingBox(
                        server = server,
                        id = newId(),
                        addedAt = currentTimeMillis(),
                        subscriptionId = subscriptionId,
                        name = parsed.name,
                        link = link,
                    )
                } else {
                    VpnProfile.fromLink(
                        link = link,
                        id = newId(),
                        addedAt = currentTimeMillis(),
                        subscriptionId = subscriptionId,
                    )
                }
            }.onSuccess { profile ->
                val duplicate = (current + fresh).any {
                    it.rawPayload() == link && it.subscriptionId == subscriptionId
                }
                if (duplicate) duplicates++ else fresh += profile
            }
        }

        lastImportDuplicates = duplicates

        _profiles.value =
            if (subscriptionId.isBlank()) fresh + current else current + fresh
        persistProfiles()
        if (_activeId.value == null) setActive(_profiles.value.firstOrNull()?.id)
        fresh.size
    }

    private var lastImportDuplicates: Int = 0

    private fun importSingBoxJson(body: String, subscriptionId: String): Int? {
        val shape = SingBoxJson.shapeOf(body)
        if (shape == SingBoxJson.Shape.None) return null
        if (shape == SingBoxJson.Shape.FullConfig && subscriptionId.isBlank()) {
            addSingBoxConfig(body).getOrThrow()
            return 1
        }
        val servers = SingBoxJson.servers(body)
        if (servers.isEmpty()) throw IllegalArgumentException("no sing-box servers found")
        val current = _profiles.value
        val fresh = mutableListOf<VpnProfile>()
        var duplicates = 0
        servers.forEach { server ->
            val profile = VpnProfile.fromSingBox(
                server = server,
                id = newId(),
                addedAt = currentTimeMillis(),
                subscriptionId = subscriptionId,
            )
            val duplicate = (current + fresh).any {
                it.subscriptionId == subscriptionId && (it.source as? ProfileSource.SingBox)?.json == server.fragment
            }
            if (duplicate) duplicates++ else fresh += profile
        }
        lastImportDuplicates = duplicates
        _profiles.value = if (subscriptionId.isBlank()) fresh + current else current + fresh
        persistProfiles()
        if (_activeId.value == null) setActive(_profiles.value.firstOrNull()?.id)
        return fresh.size
    }

    fun addOvpn(
        text: String,
        name: String? = null,
        username: String? = null,
        password: String? = null,
        subscriptionId: String = "",
    ): Result<VpnProfile> = runCatching {
        val fragment = OvpnConfig.toSingBoxFragment(text, name = name, username = username, password = password)
        val server = SingBoxJson.servers(fragment).firstOrNull()
            ?: throw IllegalArgumentException("no OpenVPN server in the profile")
        val profile = VpnProfile.fromSingBox(
            server = server,
            id = newId(),
            addedAt = currentTimeMillis(),
            subscriptionId = subscriptionId,
            name = name,
        )
        profile.toXrayConfigJson()
        _profiles.value =
            if (subscriptionId.isBlank()) listOf(profile) + _profiles.value else _profiles.value + profile
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        profile
    }

    fun addSingBoxConfig(json: String, name: String? = null, subscriptionId: String = ""): Result<VpnProfile> = runCatching {
        require(SingBoxJson.shapeOf(json) != SingBoxJson.Shape.None) { "not a sing-box config" }
        val profile = VpnProfile.fromSingBoxConfig(
            json = json,
            id = newId(),
            addedAt = currentTimeMillis(),
            name = name,
            subscriptionId = subscriptionId,
        )
        _profiles.value = if (subscriptionId.isBlank()) listOf(profile) + _profiles.value else _profiles.value + profile
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        profile
    }

    fun updateSingBox(id: String, json: String): Result<VpnProfile> = runCatching {
        val existing = profile(id) ?: throw IllegalArgumentException("unknown profile")
        val rebuilt = when (existing.source) {
            is ProfileSource.SingBox -> {
                val server = SingBoxJson.servers(json).firstOrNull()
                    ?: throw IllegalArgumentException("no sing-box server in the JSON")
                VpnProfile.fromSingBox(server, existing.id, existing.addedAt, existing.subscriptionId, existing.name)
            }
            is ProfileSource.SingBoxConfig -> {
                require(SingBoxJson.shapeOf(json) != SingBoxJson.Shape.None) { "not a sing-box config" }
                VpnProfile.fromSingBoxConfig(json, existing.id, existing.addedAt, existing.name, existing.subscriptionId)
            }
            else -> throw IllegalArgumentException("not a sing-box profile")
        }.copy(lastPingMs = existing.lastPingMs, countryCode = existing.countryCode)
        _profiles.value = _profiles.value.map { if (it.id == id) rebuilt else it }
        persistProfiles()
        rebuilt
    }

    private fun importCustomJson(body: String, subscriptionId: String): Int {
        if (!CustomConfig.looksLikeCustomJson(body)) return 0

        val clean = dev.cluvex.zedsecure.domain.config.Jsonc.strip(body)
        val element = runCatching { lenientJson.parseToJsonElement(clean) }.getOrNull() ?: return 0

        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        if (objects.isEmpty()) return 0

        var added = 0
        objects.forEach { obj ->
            val raw = prettyJson.encodeToString(JsonObject.serializer(), obj)
            runCatching { addRawJson(raw, subscriptionId = subscriptionId).getOrThrow() }
                .onSuccess { added++ }
        }
        return added
    }

    fun addRawJson(
        rawJson: String,
        name: String? = null,
        subscriptionId: String = "",
    ): Result<VpnProfile> = runCatching {
        val profile = VpnProfile.fromRawJson(
            rawJson = rawJson,
            id = newId(),
            addedAt = currentTimeMillis(),
            fallbackName = name,
            subscriptionId = subscriptionId,
        )

        profile.toXrayConfigJson()

        _profiles.value =
            if (subscriptionId.isBlank()) listOf(profile) + _profiles.value
            else _profiles.value + profile
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        profile
    }

    fun updateRawJson(id: String, rawJson: String): Result<VpnProfile> = runCatching {
        val existing = profile(id) ?: throw IllegalArgumentException("unknown profile")
        val rebuilt = VpnProfile.fromRawJson(
            rawJson = rawJson,
            id = existing.id,
            addedAt = existing.addedAt,
            fallbackName = existing.name,
            subscriptionId = existing.subscriptionId,
        ).copy(lastPingMs = existing.lastPingMs, countryCode = existing.countryCode)
        rebuilt.toXrayConfigJson()
        _profiles.value = _profiles.value.map { if (it.id == id) rebuilt else it }
        persistProfiles()
        rebuilt
    }

    private fun carryOver(existing: VpnProfile?, built: VpnProfile): VpnProfile =
        if (existing == null) built else built.copy(
            subscriptionId = existing.subscriptionId,
            lastPingMs = existing.lastPingMs,
            countryCode = existing.countryCode,
            bytesDown = existing.bytesDown,
            bytesUp = existing.bytesUp,
        )

    fun addPsiphon(
        settings: dev.cluvex.zedsecure.domain.config.PsiphonProfile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromPsiphon(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addDnsTunnel(
        settings: dev.cluvex.zedsecure.domain.config.DnsTunnelProfile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromDnsTunnel(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addMasterDns(
        settings: dev.cluvex.zedsecure.domain.config.MasterDnsProfile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromMasterDns(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addOpenConnect(
        settings: dev.cluvex.zedsecure.domain.config.OpenConnectProfile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromOpenConnect(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun rememberOpenConnectCert(id: String, pin: String): Boolean {
        val target = profile(id) ?: return false
        val settings = target.openConnectSettings() ?: return false
        if (pin.isBlank() || settings.serverCertSha256 == pin) return false
        addOpenConnect(settings.copy(serverCertSha256 = pin), target.name, target.id)
        return true
    }

    fun addIkev2(
        settings: dev.cluvex.zedsecure.domain.config.Ikev2Profile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromIkev2(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addProxyChain(memberIds: List<String>, name: String, id: String? = null): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromProxyChain(
            memberIds = memberIds,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { built ->

            if (existing == null) built else built.copy(
                lastPingMs = existing.lastPingMs,
                countryCode = existing.countryCode,
                bytesDown = existing.bytesDown,
                bytesUp = existing.bytesUp,
                subscriptionId = existing.subscriptionId,
            )
        }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addCrossChain(
        innerId: String,
        outerId: String,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromCrossChain(
            innerId = innerId,
            outerId = outerId,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
            innerName = profile(innerId)?.name.orEmpty(),
            outerName = profile(outerId)?.name.orEmpty(),
        ).let { built ->
            if (existing == null) built else built.copy(
                lastPingMs = existing.lastPingMs,
                countryCode = existing.countryCode,
                bytesDown = existing.bytesDown,
                bytesUp = existing.bytesUp,
                subscriptionId = existing.subscriptionId,
            )
        }
        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun crossChainMembers(profile: VpnProfile): Pair<VpnProfile, VpnProfile> {
        val (innerId, outerId) = profile.crossChainSettings()
            ?: throw IllegalStateException("not a cross-chain profile")
        val missing = mutableListOf<String>()
        val inner = profile(innerId) ?: run { missing += innerId.take(8); null }
        val outer = profile(outerId) ?: run { missing += outerId.take(8); null }
        if (missing.isNotEmpty()) throw ProxyChainMemberMissingException(missing)
        inner!!; outer!!
        if (!outer.canCarryChain) {
            throw CrossChainUnsupportedException(CrossChainUnsupportedException.CARRIER, outer.name)
        }
        if (!inner.canDialThroughProxy) {
            throw CrossChainUnsupportedException(CrossChainUnsupportedException.DIALER, inner.name)
        }
        udpMismatch(inner, outer)?.let { throw it }
        return inner to outer
    }

    fun udpMismatch(inner: VpnProfile, outer: VpnProfile): CrossChainUdpUnsupportedException? {
        if (outer.carrierRelaysUdp) return null
        val offending = udpProtocolsOf(inner) ?: return null
        return CrossChainUdpUnsupportedException(offending, outer.name)
    }

    private val SEALED_UNKNOWN = "locked config"

    private fun udpProtocolsOf(profile: VpnProfile): String? {
        fun offending(c: ServerConfig): String? {
            val udpProtocol = c.protocol.isWireguardFamily || c.protocol == Protocol.HYSTERIA
            val udpTransport = c.transport.network.lowercase() in setOf("kcp", "mkcp")
            return when {
                udpProtocol -> c.protocol.label
                udpTransport -> c.transport.network.uppercase()
                else -> null
            }
        }
        profile.proxyChainSettings()?.let { ids ->
            return ids.asSequence()
                .mapNotNull { profile(it)?.rawPayload() }
                .mapNotNull { runCatching { ConfigParser.parse(it) }.getOrNull() }
                .mapNotNull { offending(it) }
                .firstOrNull()
        }

        if (profile.source is ProfileSource.Sealed) return SEALED_UNKNOWN

        (profile.source as? ProfileSource.SingBox)?.let { src ->
            val server = SingBoxJson.servers(src.json).firstOrNull() ?: return null
            return server.label.takeIf { server.udp }
        }
        val payload = profile.rawPayload() ?: return null
        runCatching { ConfigParser.parse(payload) }.getOrNull()?.let { return offending(it) }

        val lower = payload.lowercase()
        return when {
            "\"protocol\": \"wireguard\"" in lower || "\"protocol\":\"wireguard\"" in lower -> "WireGuard"
            "\"protocol\": \"hysteria\"" in lower || "\"protocol\":\"hysteria\"" in lower -> "Hysteria2"
            "\"network\": \"kcp\"" in lower || "\"network\":\"kcp\"" in lower -> "mKCP"
            else -> null
        }
    }

    fun buildChainConfig(
        profile: VpnProfile,
        options: XrayJsonBuilder.BuildOptions,
        forSpeedtest: Boolean = false,
    ): String {
        val ids = profile.proxyChainSettings()
            ?: throw IllegalStateException("not a proxy-chain profile")
        val missing = mutableListOf<String>()
        val servers = ids.mapNotNull { memberId ->
            val member = profile(memberId)
            if (member == null) {
                missing += memberId.take(8)
                return@mapNotNull null
            }

            (member.source as? ProfileSource.SingBox)?.let { src ->
                val server = SingBoxJson.servers(src.json).firstOrNull()
                    ?: run { missing += member.name; return@mapNotNull null }
                return@mapNotNull XrayJsonBuilder.ChainHop.SingBox(src.json, src.carrier, server)
            }
            (member.source as? ProfileSource.RawJson)?.takeIf { member.isServerless }?.let { src ->
                return@mapNotNull XrayJsonBuilder.ChainHop.Serverless(src.json)
            }
            val payload = member.rawPayload()
            if (payload == null) {
                missing += member.name
                return@mapNotNull null
            }
            if (SingBoxLinks.handles(payload)) {
                val parsed = runCatching { SingBoxLinks.parse(payload) }.getOrNull()
                val server = parsed?.let { SingBoxJson.servers(it.fragment).firstOrNull() }
                    ?: run { missing += member.name; return@mapNotNull null }
                return@mapNotNull XrayJsonBuilder.ChainHop.SingBox(parsed.fragment, parsed.name, server)
            }
            runCatching { ConfigParser.parse(payload) }.getOrNull()?.let { XrayJsonBuilder.ChainHop.Xray(it) }
                ?: run { missing += member.name; null }
        }
        if (missing.isNotEmpty()) throw ProxyChainMemberMissingException(missing)
        require(servers.isNotEmpty()) { "proxy chain has no usable members" }
        return XrayJsonBuilder.buildChainHops(servers, options = options, forSpeedtest = forSpeedtest)
    }

    fun chainProbeConfig(profile: VpnProfile): String? {
        if (!profile.isProxyChain) return null
        return runCatching {
            buildChainConfig(profile, XrayJsonBuilder.BuildOptions(), forSpeedtest = true)
        }.getOrNull()
    }

    fun resolveRuleOutbounds(
        rulesets: List<dev.cluvex.zedsecure.domain.model.RulesetItem>,
    ): Map<String, ServerConfig> = rulesets
        .asSequence()
        .filter { it.enabled }
        .mapNotNull { rule ->
            val id = dev.cluvex.zedsecure.domain.model.RulesetItem.profileIdOf(rule.outboundTag)
                ?: return@mapNotNull null
            val payload = profile(id)?.takeIf { it.source is ProfileSource.Link }?.rawPayload()
                ?: return@mapNotNull null
            runCatching { ConfigParser.parse(payload) }.getOrNull()?.let { rule.outboundTag to it }
        }
        .toMap()

    fun plainWireguardAmong(ids: Collection<String>): List<String> =
        _profiles.value.filter { p ->
            p.id in ids && p.protocol == Protocol.WIREGUARD.name &&
                runCatching { ConfigParser.parse(p.rawPayload().orEmpty()).awg.isEmpty }
                    .getOrDefault(false)
        }.map { it.id }

    fun enableWireguardObfuscation(ids: Collection<String>): Int {
        var changed = 0
        _profiles.value = _profiles.value.map { p ->
            if (p.id !in ids) return@map p
            val link = p.rawPayload() ?: return@map p
            val parsed = runCatching { ConfigParser.parse(link) }.getOrNull() ?: return@map p
            if (parsed.protocol != Protocol.WIREGUARD || !parsed.awg.isEmpty) return@map p
            val obfuscated = parsed.copy(
                protocol = Protocol.AMNEZIAWG,
                awg = AwgConfig.amneziaDefaults(),
            )
            changed++
            p.copy(
                protocol = Protocol.AMNEZIAWG.name,
                transportLabel = obfuscated.transportLabel,
                source = ProfileSource.Link(ShareLink.build(obfuscated)),
            )
        }
        if (changed > 0) persistProfiles()
        return changed
    }

    fun shareLinkOf(profile: VpnProfile): String? {
        val src = profile.source
        val memberIds = when (src) {
            is ProfileSource.ProxyChain -> src.memberIds
            is ProfileSource.CrossChain -> listOf(src.innerId, src.outerId)
            else -> return profile.rawPayload()
        }
        val byId = _profiles.value.associateBy { it.id }
        val members = memberIds.map { id ->
            val member = byId[id] ?: return null

            shareLinkOf(member) ?: return null
        }
        return ZedLink.build(profile.name, src, members)
    }

    private fun importZedLink(text: String): Int {
        val payload = ZedLink.parse(text)
            ?: throw IllegalArgumentException("invalid zedsecure:// link")
        val name = payload.name
        return when (val src = payload.source) {
            is ProfileSource.Psiphon -> { addPsiphon(src.settings, name); 1 }
            is ProfileSource.DnsTunnel -> { addDnsTunnel(src.settings, name); 1 }
            is ProfileSource.Tor -> { addTor(name); 1 }
            is ProfileSource.Ssh -> { addSsh(src.settings, name); 1 }
            is ProfileSource.MasterDns -> { addMasterDns(src.settings, name); 1 }
            is ProfileSource.OpenConnect -> { addOpenConnect(src.settings, name); 1 }
            is ProfileSource.Ikev2 -> { addIkev2(src.settings, name); 1 }
            is ProfileSource.ProxyChain, is ProfileSource.CrossChain -> {
                val ids = payload.members.map { link ->
                    val before = _profiles.value.map { it.id }.toSet()
                    importText(link).getOrThrow()
                    _profiles.value.firstOrNull { it.id !in before }?.id
                        ?: throw IllegalArgumentException("chain member did not import")
                }
                if (src is ProfileSource.ProxyChain) addProxyChain(ids, name)
                else addCrossChain(ids[0], ids[1], name)
                1
            }
            else -> throw IllegalArgumentException("unsupported zedsecure:// payload")
        }
    }

    fun autoSelectProfile(id: String): VpnProfile? {
        if (!AutoSelectIds.isAuto(id)) return null
        val subscriptionId = AutoSelectIds.subscriptionOf(id)
        val groupName = when (subscriptionId) {
            null, "" -> ""
            else -> _subscriptions.value.firstOrNull { it.id == subscriptionId }?.name ?: return null
        }
        return VpnProfile.fromAutoSelect(subscriptionId, groupName, autoSelectMembers(subscriptionId).size)
    }

    fun autoSelectMembers(subscriptionId: String?): List<VpnProfile> = _profiles.value.filter { p ->
        !p.isLocked &&
            (subscriptionId == null || p.subscriptionId == subscriptionId) &&
            (p.source is ProfileSource.Link || p.source is ProfileSource.RawJson || p.source is ProfileSource.SingBox)
    }

    private fun autoMembersOf(members: List<VpnProfile>): List<AutoMember> = members.mapNotNull { p ->
        when (val src = p.source) {
            is ProfileSource.Link -> if (SingBoxLinks.handles(src.link)) {
                runCatching { SingBoxLinks.parse(src.link) }.getOrNull()
                    ?.let { AutoMember.SingBox(p.id, it.fragment, it.name) }
            } else {
                runCatching { ConfigParser.parse(src.link) }.getOrNull()
                    ?.let { AutoMember.Server(p.id, it) }
            }
            is ProfileSource.RawJson -> AutoMember.Custom(p.id, src.json)
            is ProfileSource.SingBox -> AutoMember.SingBox(p.id, src.json, src.carrier)
            else -> null
        }
    }

    private fun fastestOf(members: List<VpnProfile>): String? =
        members.filter { (it.lastPingMs ?: -1) > 0 }.minByOrNull { it.lastPingMs!! }?.id

    fun resolveRuleGroups(
        rulesets: List<dev.cluvex.zedsecure.domain.model.RulesetItem>,
    ): Map<String, RuleGroup> = rulesets
        .asSequence()
        .filter { it.enabled && AutoSelectIds.isAuto(it.outboundTag) }
        .map { it.outboundTag }
        .distinct()
        .mapNotNull { tag ->
            val subscriptionId = AutoSelectIds.subscriptionOf(tag)
            if (!subscriptionId.isNullOrEmpty() && _subscriptions.value.none { it.id == subscriptionId }) return@mapNotNull null
            val members = autoSelectMembers(subscriptionId)
            val built = autoMembersOf(members)
            if (built.isEmpty()) return@mapNotNull null
            tag to RuleGroup(built, fastestOf(members))
        }
        .toMap()

    fun buildAutoSelectConfig(
        profile: VpnProfile,
        options: XrayJsonBuilder.BuildOptions,
        tuning: AutoSelectTuning,
    ): AutoSelectBuild {
        val group = profile.autoSelectSettings() ?: throw IllegalStateException("not an auto-select profile")
        val members = autoSelectMembers(group.subscriptionId)
        val built = autoMembersOf(members)
        val remembered = lastAutoSelectPick(profile.id)?.takeIf { id -> members.any { it.id == id } }
        val fastest = fastestOf(members)
        return XrayJsonBuilder.buildAutoSelect(
            members = built,
            tuning = tuning.copy(initialProfileId = tuning.initialProfileId ?: remembered ?: fastest),
            options = options,
        )
    }

    fun rememberAutoSelectPick(autoId: String, memberProfileId: String) {
        if (lastAutoSelectPick(autoId) != memberProfileId) store.putString(KEY_AUTO_PICK + autoId, memberProfileId)
    }

    fun lastAutoSelectPick(autoId: String): String? = store.getString(KEY_AUTO_PICK + autoId)

    fun addSniSpoof(
        settings: dev.cluvex.zedsecure.domain.config.SniSpoofProfile,
        name: String,
        id: String? = null,
    ): VpnProfile {
        val existing = id?.let { profile(it) }
        val profile = VpnProfile.fromSniSpoof(
            settings = settings,
            id = existing?.id ?: newId(),
            addedAt = existing?.addedAt ?: currentTimeMillis(),
            name = name,
        ).let { carryOver(existing, it) }

        _profiles.value = if (existing != null) {
            _profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + _profiles.value
        }
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addSsh(settings: dev.cluvex.zedsecure.domain.config.SshProfile, name: String): VpnProfile {
        val profile = VpnProfile.fromSsh(
            settings = settings,
            id = newId(),
            addedAt = currentTimeMillis(),
            name = name,
        )
        _profiles.value = listOf(profile) + _profiles.value
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun addTor(name: String): VpnProfile {
        val profile = VpnProfile.fromTor(
            id = newId(),
            addedAt = currentTimeMillis(),
            name = name,
        )
        _profiles.value = listOf(profile) + _profiles.value
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        return profile
    }

    fun update(profile: VpnProfile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        persistProfiles()
    }

    fun reorder(shownIdsInNewOrder: List<String>) {
        val current = _profiles.value
        val shownSet = shownIdsInNewOrder.toSet()
        val byId = current.associateBy { it.id }
        val newShown = shownIdsInNewOrder.mapNotNull { byId[it] }.iterator()
        _profiles.value = current.map { if (it.id in shownSet && newShown.hasNext()) newShown.next() else it }
        persistProfiles()
    }

    fun rename(id: String, newName: String) {
        profile(id)?.let { update(it.copy(name = newName)) }
    }

    fun remove(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }

        scrubChainMember(id)
        if (_activeId.value == id) setActive(_profiles.value.firstOrNull()?.id)
        persistProfiles()
    }

    private fun scrubChainMember(memberId: String) {
        val affected = _profiles.value.filter {
            it.proxyChainSettings()?.contains(memberId) == true
        }
        if (affected.isEmpty()) return
        val doomed = mutableSetOf<String>()
        _profiles.update { list ->
            list.mapNotNull { p ->
                val ids = p.proxyChainSettings() ?: return@mapNotNull p
                if (memberId !in ids) return@mapNotNull p
                val kept = ids.filterNot { it == memberId }
                if (kept.size < 2) {
                    doomed += p.id
                    null
                } else {
                    VpnProfile.fromProxyChain(
                        memberIds = kept,
                        id = p.id,
                        addedAt = p.addedAt,
                        name = p.name,
                    ).copy(
                        lastPingMs = p.lastPingMs,
                        countryCode = p.countryCode,
                        bytesDown = p.bytesDown,
                        bytesUp = p.bytesUp,
                        subscriptionId = p.subscriptionId,
                    )
                }
            }
        }
        if (_activeId.value in doomed) setActive(_profiles.value.firstOrNull()?.id)
    }

    fun removeAll(predicate: (VpnProfile) -> Boolean) {
        val doomed = _profiles.value.filter(predicate).map { it.id }
        if (doomed.isEmpty()) return
        val doomedSet = doomed.toSet()
        _profiles.value = _profiles.value.filterNot { it.id in doomedSet }
        // Keep proxy chains consistent when bulk-removing a member profile.
        doomed.forEach { scrubChainMember(it) }
        if (!activeExists()) {
            setActive(_profiles.value.firstOrNull()?.id)
        }
        persistProfiles()
    }

    fun removeDuplicates(): Int {
        val seen = HashSet<String>()
        val kept = ArrayList<VpnProfile>()
        val removedIds = ArrayList<String>()
        _profiles.value.forEach { profile ->
            val key = profile.rawPayload() ?: profile.id
            if (profile.isLocked || seen.add(key)) {
                kept += profile
            } else {
                removedIds += profile.id
            }
        }
        if (removedIds.isNotEmpty()) {
            _profiles.value = kept
            // A removed duplicate may still be referenced by one or more chains.
            removedIds.forEach { scrubChainMember(it) }
            if (!activeExists()) setActive(_profiles.value.firstOrNull()?.id)
            persistProfiles()
        }
        return removedIds.size
    }

    fun removeInvalid(): Int {
        val removedIds = _profiles.value.filterNot { keepAsValid(it) }.map { it.id }
        if (removedIds.isNotEmpty()) {
            val doomed = removedIds.toSet()
            _profiles.value = _profiles.value.filterNot { it.id in doomed }
            // Invalid profiles must not leave dangling references in proxy chains.
            removedIds.forEach { scrubChainMember(it) }
            if (!activeExists()) {
                setActive(_profiles.value.firstOrNull()?.id)
            }
            persistProfiles()
        }
        return removedIds.size
    }

    fun invalidCount(): Int = _profiles.value.count { !keepAsValid(it) }

    private fun keepAsValid(profile: VpnProfile): Boolean {
        if (profile.isLocked || profile.isManagedTunnel || profile.isProxyChain) return true

        if (!profile.isCustom && !profile.isSingBoxConfig &&
            (profile.address.isBlank() || profile.address == "-" || profile.port <= 0)
        ) {
            return false
        }

        profile.lastPingMs?.let { if (it < 0) return false }
        return runCatching { profile.toXrayConfigJson() }.isSuccess
    }

    fun sortByTestResults() {
        _profiles.value = _profiles.value.sortedWith(
            compareBy(
                { it.isLocked },

                { it.lastPingMs?.takeIf { ms -> ms > 0 } ?: Int.MAX_VALUE },
            ),
        )
        persistProfiles()
    }

    fun exportAllPayloads(): String =
        _profiles.value.mapNotNull { it.rawPayload() }.joinToString("\n")

    fun setActive(id: String?) {
        _activeId.value = id
        store.putString(KEY_ACTIVE, id)
    }

    fun addUsage(id: String, downDelta: Long, upDelta: Long, persist: Boolean) {
        if (downDelta <= 0 && upDelta <= 0 && !persist) return
        _profiles.update { list ->
            list.map {
                if (it.id == id) it.copy(bytesDown = it.bytesDown + downDelta, bytesUp = it.bytesUp + upDelta)
                else it
            }
        }
        if (persist) persistProfiles()
    }

    fun resetUsage(id: String) {
        profile(id)?.let { update(it.copy(bytesDown = 0, bytesUp = 0)) }
    }

    fun setPing(id: String, pingMs: Int?, countryCode: String? = null, persist: Boolean = true) {
        _profiles.update { list ->
            list.map {
                if (it.id == id) it.copy(lastPingMs = pingMs, countryCode = countryCode ?: it.countryCode)
                else it
            }
        }
        if (persist) persistProfiles()
    }

    fun flushProfiles() = persistProfiles()

    fun clearPings(ids: Collection<String>, persist: Boolean = true) {
        if (ids.isEmpty()) return
        val target = ids.toSet()
        _profiles.update { list ->
            list.map { if (it.id in target && it.lastPingMs != null) it.copy(lastPingMs = null) else it }
        }
        if (persist) persistProfiles()
    }

    suspend fun importPasted(text: String): Result<PastedImport> {
        subscriptionUrlIn(text)?.let { return importSubscriptionUrl(it, name = null) }
        val parsed = runCatching {
            lastImportDuplicates = 0
            val added = importText(text).getOrThrow()
            PastedImport(count = added, subscription = false, duplicates = lastImportDuplicates)
        }
        if (parsed.getOrNull()?.let { it.count > 0 || it.duplicates > 0 } == true) return parsed
        val url = soleUrlIn(text) ?: return parsed
        return importSubscriptionUrl(url, name = null, keepIfUnreachable = false)
    }

    suspend fun importSubscriptionUrl(
        url: String,
        name: String?,
        keepIfUnreachable: Boolean = true,
    ): Result<PastedImport> = runCatching {
        val existing = _subscriptions.value.firstOrNull { it.url.equals(url, ignoreCase = true) }
        val sub = existing ?: addSubscription(name = name?.trim().orEmpty(), url = url)
        val added = updateSubscription(sub.id).getOrElse { e ->
            val savedForLater = existing == null && keepIfUnreachable && e is SubscriptionUnreachableException
            if (existing == null && !savedForLater) removeSubscription(sub.id)
            throw SubscriptionNotFetchedException(
                name = _subscriptions.value.firstOrNull { it.id == sub.id }?.name ?: sub.name,
                savedForLater = savedForLater,
                cause = e,
            )
        }

        val finalName = _subscriptions.value.firstOrNull { it.id == sub.id }?.name ?: sub.name
        PastedImport(count = added, subscription = true, subscriptionName = finalName)
    }

    class SubscriptionUnreachableException(cause: Throwable) : Exception(cause.message, cause)

    class SubscriptionNotFetchedException(
        val name: String,
        val savedForLater: Boolean,
        cause: Throwable,
    ) : Exception(cause.message, cause)

    data class PastedImport(
        val count: Int,
        val subscription: Boolean,
        val subscriptionName: String = "",

        val duplicates: Int = 0,
    )

    fun addSubscription(name: String, url: String, userAgent: String? = null): Subscription {
        val sub = Subscription(
            id = newId(),
            name = name.ifBlank { url.take(40) },
            url = url.trim(),
            userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() },
        )
        _subscriptions.value = _subscriptions.value + sub
        persistSubscriptions()
        return sub
    }

    fun addGroup(name: String): Subscription {
        val group = Subscription(id = newId(), name = name.trim().ifBlank { "Group" }, url = "")
        _subscriptions.value = _subscriptions.value + group
        persistSubscriptions()
        return group
    }

    fun moveToGroup(profileId: String, groupId: String) = moveToGroup(listOf(profileId), groupId)

    fun moveToGroup(profileIds: Collection<String>, groupId: String) {
        val ids = profileIds.toSet()
        if (ids.isEmpty()) return
        _profiles.value = _profiles.value.map {
            if (it.id in ids) it.copy(subscriptionId = groupId) else it
        }
        persistProfiles()
    }

    fun removeAll(ids: Collection<String>) {
        val doomed = ids.toSet()
        if (doomed.isEmpty()) return
        _profiles.value = _profiles.value.filterNot { it.id in doomed }
        doomed.forEach { scrubChainMember(it) }
        if (_activeId.value in doomed) setActive(_profiles.value.firstOrNull()?.id)
        persistProfiles()
    }

    fun renameSubscription(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == id) it.copy(name = trimmed) else it
        }
        persistSubscriptions()
    }

    fun editSubscription(id: String, newName: String, userAgent: String?, newUrl: String? = null) {
        val trimmedName = newName.trim()
        val trimmedUa = userAgent?.trim()?.takeIf { it.isNotBlank() }
        val trimmedUrl = newUrl?.trim()?.takeIf { it.isNotBlank() }
        _subscriptions.value = _subscriptions.value.map {
            if (it.id != id) it
            else it.copy(
                name = trimmedName.ifEmpty { it.name },
                url = trimmedUrl ?: it.url,
                userAgent = trimmedUa,
            )
        }
        persistSubscriptions()
    }

    fun looksLikeSubscriptionUrl(text: String): Boolean = subscriptionUrlIn(text) != null

    private fun subscriptionUrlIn(text: String): String? {
        val t = text.withoutFormatChars().trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        return t.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun soleUrlIn(text: String): String? =
        URL_IN_TEXT.findAll(text.withoutFormatChars())
            .map { it.value.trimEnd(*URL_TRAILING_PUNCTUATION) }
            .filter { it.length > "https://".length }
            .distinct()
            .singleOrNull()

    private fun String.withoutFormatChars(): String = filterNot { it.category == CharCategory.FORMAT }

    private fun reuseIdentities(subscriptionId: String, previous: List<VpnProfile>) {
        if (previous.isEmpty()) return
        val taken = mutableSetOf<String>()
        fun keys(p: VpnProfile) = listOf(
            p.rawPayload()?.let { "link:$it" },
            "ep:${p.name}|${p.address}|${p.port}",
            "name:${p.name}",
        )

        val byKey = HashMap<String, VpnProfile>()
        for (tier in 0..2) {
            previous.forEach { old ->
                val k = keys(old).getOrNull(tier) ?: return@forEach
                byKey.putIfAbsent(k, old)
            }
        }
        _profiles.update { list ->
            list.map { p ->
                if (p.subscriptionId != subscriptionId) return@map p
                val old = keys(p).asSequence()
                    .filterNotNull()
                    .mapNotNull { byKey[it] }
                    .firstOrNull { it.id !in taken }
                    ?: return@map p
                taken += old.id
                p.copy(
                    id = old.id,
                    addedAt = old.addedAt,
                    lastPingMs = p.lastPingMs ?: old.lastPingMs,
                    countryCode = p.countryCode ?: old.countryCode,
                    bytesDown = old.bytesDown,
                    bytesUp = old.bytesUp,
                )
            }
        }
        persistProfiles()
    }

    fun removeSubscription(id: String, alsoRemoveServers: Boolean = true) {
        _subscriptions.value = _subscriptions.value.filterNot { it.id == id }
        persistSubscriptions()
        if (alsoRemoveServers) removeAll { it.subscriptionId == id }
    }

    fun moveSubscription(fromId: String, toId: String) {
        val list = _subscriptions.value.toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        list.add(to, list.removeAt(from))
        _subscriptions.value = list
        persistSubscriptions()
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persistSubscriptions()
    }

    suspend fun updateSubscription(id: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val sub = _subscriptions.value.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown subscription")

            if (sub.url.isBlank()) return@runCatching 0
            val primaryUa = resolveUa(sub.userAgent)
            val response = runCatching { fetch(sub.url, primaryUa) }
                .getOrElse { throw SubscriptionUnreachableException(it) }
            val body = response.body
            var meta = SubscriptionHeaders.parse(response.headers)

            val previous = _profiles.value.filter { it.subscriptionId == id }

            val previousActive = _activeId.value

            removeAll { it.subscriptionId == id }
            val first = importText(body, subscriptionId = id)
            var added = first.getOrElse { 0 }
            if (added == 0) {
                val fallbackUas = listOf(
                    if (primaryUa.startsWith(V2RAYNG_UA_PREFIX)) ALT_UA else DEFAULT_UA,
                    SING_BOX_UA,
                ).filter { it != primaryUa }
                val seenBodies = mutableSetOf(body)
                for (fallbackUa in fallbackUas) {
                    val retry = runCatching { fetch(sub.url, fallbackUa) }.getOrNull() ?: continue
                    if (!seenBodies.add(retry.body)) continue
                    added = importText(retry.body, subscriptionId = id).getOrElse { 0 }
                    if (added > 0) {
                        if (!SubscriptionHeaders.parse(retry.headers).isEmpty) {
                            meta = SubscriptionHeaders.parse(retry.headers)
                        }
                        break
                    }
                }
            }

            if (added == 0) throw first.exceptionOrNull()
                ?: IllegalArgumentException("no supported configs found")

            previous.firstOrNull { it.id == previousActive && SingBoxOnlyServers.isPlaceholder(it) }?.let { old ->
                _profiles.value
                    .firstOrNull { it.subscriptionId == id && it.name == old.name && SingBoxOnlyServers.isPlaceholder(it) }
                    ?.let { setActive(it.id) }
            }

            added += replaceSingBoxPlaceholders(id, sub.url)
            reuseIdentities(id, previous)
            if (previousActive != null && _activeId.value != previousActive &&
                profile(previousActive) != null
            ) {
                setActive(previousActive)
            }
            _subscriptions.value = _subscriptions.value.map {
                if (it.id == id) {
                    it.withMeta(meta).copy(lastUpdated = currentTimeMillis(), serverCount = added)
                } else {
                    it
                }
            }
            persistSubscriptions()
            added
        }
    }

    private fun replaceSingBoxPlaceholders(subscriptionId: String, url: String): Int {
        val imported = _profiles.value.filter { it.subscriptionId == subscriptionId }
        val placeholders = imported.filter { SingBoxOnlyServers.isPlaceholder(it) }
        if (placeholders.isEmpty()) return 0
        val body = runCatching { fetch(url, SING_BOX_UA).body }.getOrNull() ?: return 0
        if (SingBoxJson.shapeOf(body) == SingBoxJson.Shape.None) return 0
        val missing = SingBoxOnlyServers.missing(imported, SingBoxJson.servers(body))
        if (missing.isEmpty()) return 0
        val now = currentTimeMillis()
        val fresh = missing.map {
            VpnProfile.fromSingBox(server = it, id = newId(), addedAt = now, subscriptionId = subscriptionId)
        }
        val activeIndex = placeholders.indexOfFirst { it.id == _activeId.value }
        _profiles.value = SingBoxOnlyServers.splice(_profiles.value, placeholders, fresh)
        persistProfiles()
        if (activeIndex >= 0) {
            setActive(fresh.getOrNull(activeIndex)?.takeIf { fresh.size == placeholders.size }?.id ?: fresh.first().id)
        }
        return fresh.size - placeholders.size
    }

    private fun Subscription.withMeta(meta: SubscriptionMeta): Subscription {
        val unnamed = name.isBlank() || name == url.take(40)
        return copy(
            name = if (unnamed && meta.title != null) meta.title else name,
            upload = meta.upload,
            download = meta.download,
            total = meta.total,
            expireAt = meta.expireAt,
            title = meta.title,
            updateIntervalHours = meta.updateIntervalHours,
            supportUrl = meta.supportUrl,
            webPageUrl = meta.webPageUrl,
        )
    }

    fun dueSubscriptions(globalIntervalHours: Int, nowMs: Long = currentTimeMillis()): List<Subscription> =
        _subscriptions.value.filter { SubscriptionSchedule.isDue(it, globalIntervalHours, nowMs) }

    suspend fun updateDueSubscriptions(globalIntervalHours: Int): Int {
        var ok = 0
        dueSubscriptions(globalIntervalHours).forEach { sub ->
            if (updateSubscription(sub.id).isSuccess) ok++
        }
        return ok
    }

    suspend fun updateAllSubscriptions(): Int {
        var total = 0
        _subscriptions.value.filter { it.enabled }.forEach { sub ->
            updateSubscription(sub.id).onSuccess { total += it }
        }
        return total
    }

    private fun fetch(url: String, userAgent: String? = null): HttpTextResponse {
        val ua = resolveUa(userAgent)
        val tunnelPort = dev.cluvex.zedsecure.core.VpnManager.activeSocksPort?.takeIf {
            it > 0 && dev.cluvex.zedsecure.core.VpnManager.status.value.state ==
                dev.cluvex.zedsecure.domain.model.ConnectionState.Connected
        }
        if (tunnelPort != null) {
            runCatching { return httpGetResponse(url, userAgent = ua, socksPort = tunnelPort) }
        }
        return httpGetResponse(url, userAgent = ua)
    }

    private fun resolveUa(userAgent: String?): String =
        userAgent?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_UA

    fun createLockedZsx(request: ZsxSealRequest): ByteArray = ZsxCrypto.seal(request)

    fun peekLocked(bytes: ByteArray): ZsxMetadata = ZsxCrypto.peek(bytes)

    fun importLocked(bytes: ByteArray, password: String?): Result<VpnProfile> = runCatching {
        val meta = ZsxCrypto.peek(bytes)
        if (meta.isExpired) throw ZsxExpiredException()
        val payload = ZsxCrypto.open(bytes, password)
        val resealed = ZsxCrypto.seal(
            ZsxSealRequest(
                configPayload = payload,
                nameEn = meta.nameEn,
                nameFa = meta.nameFa,
                note = meta.note,
                expiresAt = meta.expiresAt,
                password = null,
            ),
        )
        val name = meta.nameEn.ifBlank { meta.nameFa }.ifBlank { "Locked config" }
        val profile = VpnProfile(
            id = newId(),
            name = name,
            protocol = "LOCKED",
            address = "•••",
            port = 0,
            transportLabel = "Locked · .zsx",
            source = ProfileSource.Sealed(Base64.encode(resealed)),
            addedAt = currentTimeMillis(),
            isLocked = true,
            note = meta.note.takeIf { it.isNotBlank() },
        )
        profile.toXrayConfigJson()
        _profiles.value = _profiles.value + profile
        persistProfiles()
        if (_activeId.value == null) setActive(profile.id)
        profile
    }

    private fun persistProfiles() {
        store.putString(KEY_PROFILES, json.encodeToString(_profiles.value))
    }

    private fun persistSubscriptions() {
        store.putString(KEY_SUBS, json.encodeToString(_subscriptions.value))
    }

    private fun loadProfiles(): List<VpnProfile> {
        val raw = store.getString(KEY_PROFILES) ?: return emptyList()
        val loaded = runCatching { json.decodeFromString<List<VpnProfile>>(raw) }.getOrDefault(emptyList())
        return repairOpenVpnType(loaded)
    }

    private fun repairOpenVpnType(profiles: List<VpnProfile>): List<VpnProfile> {
        var changed = false
        val repaired = profiles.map { profile ->
            val json = when (val source = profile.source) {
                is ProfileSource.SingBox -> source.json
                is ProfileSource.SingBoxConfig -> source.json
                else -> return@map profile
            }
            if (!json.contains("\"openvpn\"")) return@map profile
            val fixed = SingBoxJson.retypeOpenVpn(json) ?: return@map profile
            changed = true
            when (profile.source) {
                is ProfileSource.SingBox -> profile.copy(source = ProfileSource.SingBox(fixed))
                else -> profile.copy(source = ProfileSource.SingBoxConfig(fixed))
            }
        }
        if (changed) {
            store.putString(KEY_PROFILES, json.encodeToString(repaired))
        }
        return repaired
    }

    private fun loadSubscriptions(): List<Subscription> {
        val raw = store.getString(KEY_SUBS) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Subscription>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "zed_configs"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SUBS = "subscriptions"
        private const val KEY_ACTIVE = "active_id"
        private const val KEY_AUTO_PICK = "auto_pick:"

        val DEFAULT_UA = "v2rayNG/${AppInfo.versionName}"

        val ALT_UA = "ZedSecure/${AppInfo.versionName}"

        const val SING_BOX_UA = "SFA/1.14.0"

        const val V2RAYNG_UA_PREFIX = "v2rayNG/"

        private val URL_IN_TEXT = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

        private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '،', '؛')
    }
}
