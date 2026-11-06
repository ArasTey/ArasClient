package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object OvpnConfig {
    enum class Reason {
        NoRemote,

        Tap,

        ExternalFiles,

        UnsupportedCredentials,
    }

    class UnsupportedException(val reason: Reason, val detail: String = "") :
        Exception("OpenVPN profile unsupported ($reason)${if (detail.isBlank()) "" else ": $detail"}")

    data class Info(
        val host: String,
        val port: Int,

        val network: String,

        val needsCredentials: Boolean,

        val hasInlineCredentials: Boolean,

        val name: String,
    )

    fun looksLikeOvpn(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        if (trimmed.startsWith("[Interface]", ignoreCase = true)) return false
        val directives = directives(trimmed)
        if (directives.none { it.name == "remote" }) return false
        val marks = directives.count { it.name in OVPN_MARKERS } + blocks(trimmed).keys.count { it in INLINE_BLOCKS }
        return marks > 0
    }

    fun inspect(text: String): Info {
        val directives = directives(text)
        val blocks = blocks(text)
        refuseWhatCannotRun(directives, blocks)
        val remote = remotes(directives).firstOrNull() ?: throw UnsupportedException(Reason.NoRemote)
        val credentials = inlineCredentials(blocks)
        return Info(
            host = remote.host,
            port = remote.port,
            network = remote.network,
            needsCredentials = directives.any { it.name == "auth-user-pass" } || credentials != null,
            hasInlineCredentials = credentials != null,
            name = comment(text) ?: remote.host,
        )
    }

    fun toSingBoxFragment(
        text: String,
        name: String? = null,
        username: String? = null,
        password: String? = null,
    ): String {
        val directives = directives(text)
        val blocks = blocks(text)
        refuseWhatCannotRun(directives, blocks)
        val remotes = remotes(directives)
        val first = remotes.firstOrNull() ?: throw UnsupportedException(Reason.NoRemote)
        val inline = inlineCredentials(blocks)

        val staticKey = blocks["secret"] ?: blocks["static-key"]

        val endpoint = buildJsonObject {
            put("type", SingBoxJson.OPENVPN_TYPE)
            put("tag", name?.takeIf { it.isNotBlank() } ?: comment(text) ?: first.host)

            if (remotes.size > 1) {
                putJsonArray("servers") {
                    remotes.forEach { r ->
                        add(
                            buildJsonObject {
                                put("server", r.host)
                                put("server_port", r.port)
                                put("network", r.network)
                            },
                        )
                    }
                }
            } else {
                put("server", first.host)
                put("server_port", first.port)
            }
            put("network", first.network)
            if (directives.flag("remote-random")) put("remote_random", true)

            if (staticKey != null) {
                put("mode", "static_key")
                putJsonArray("static_key") { staticKey.forEach { add(it) } }
                keyDirection(directives, "secret")?.let { put("key_direction", it) }

                ifconfig(directives)?.let { (local, peer) ->
                    putJsonArray("address") { add(local) }
                    put("peer_address", peer)
                }

                directives.first("cipher")?.firstOrNull()?.let { put("cipher", it) }
            } else {
                put("tls", tls(directives, blocks) ?: buildJsonObject {})
                (username ?: inline?.first)?.takeIf { it.isNotBlank() }?.let { put("username", it) }
                (password ?: inline?.second)?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                directives.first("static-challenge")?.let { args ->
                    args.getOrNull(0)?.let { put("static_challenge", it) }
                    if (args.getOrNull(1) == "1") put("static_challenge_echo", true)
                }

                val ciphers = directives.first("data-ciphers")?.firstOrNull()
                    ?.split(':')?.filter { it.isNotBlank() }
                    .orEmpty()
                if (ciphers.isNotEmpty()) {
                    putJsonArray("data_ciphers") { ciphers.forEach { add(it) } }
                }
                val fallback = directives.first("data-ciphers-fallback")?.firstOrNull()
                    ?: directives.first("cipher")?.firstOrNull()
                fallback?.let { put("data_ciphers_fallback", it) }

                directives.number("reneg-sec")?.let {
                    if (it == 0) put("renegotiate_disabled", true) else put("renegotiate_interval", "${it}s")
                }
            }

            directives.first("auth")?.firstOrNull()?.let { put("auth", it) }

            directives.first("compress")?.let { put("compression", it.firstOrNull() ?: "stub") }
            directives.first("comp-lzo")?.let { put("compression_lzo", it.firstOrNull() ?: "yes") }
            directives.first("allow-compression")?.firstOrNull()?.let { put("allow_compression", it) }

            directives.number("mssfix")?.let {
                if (it == 0) put("mss_fix_disabled", true) else put("mss_fix", it)
            }
            directives.first("mssfix")?.getOrNull(1)?.let { put("mss_fix_mode", it) }
            directives.number("fragment")?.let { put("fragment", it) }
            directives.number("tun-mtu")?.let { put("mtu", it) }
            directives.number("replay-window")?.let { put("replay_window", it) }

            directives.first("replay-window")?.getOrNull(1)?.toIntOrNull()
                ?.let { put("replay_window_time", "${it}s") }

            if (directives.flag("route-nopull")) put("route_no_pull", true)

            directives.first("auth-retry")?.firstOrNull()
                ?.takeIf { it == "none" || it == "nointeract" || it == "interact" }
                ?.let { put("auth_retry", it) }

            val keepalive = directives.first("keepalive")
            val pingInterval = keepalive?.getOrNull(0)?.toIntOrNull() ?: directives.number("ping")
            val pingRestart = keepalive?.getOrNull(1)?.toIntOrNull() ?: directives.number("ping-restart")
            pingInterval?.let { put("ping_interval", "${it}s") }
            pingRestart?.let { put("ping_restart", "${it}s") }
            directives.first("explicit-exit-notify")?.let {
                put("explicit_exit_notify", it.firstOrNull()?.toIntOrNull() ?: 1)
            }
        }
        return prettyJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { putJsonArray("endpoints") { add(endpoint) } },
        )
    }

    private fun tls(directives: List<Directive>, blocks: Map<String, List<String>>): JsonObject? {
        val ca = blocks["ca"]
        val cert = blocks["cert"]
        val key = blocks["key"]
        val wrapType = when {
            blocks.containsKey("tls-crypt-v2") -> "tls_crypt_v2"
            blocks.containsKey("tls-crypt") -> "tls_crypt"
            blocks.containsKey("tls-auth") -> "tls_auth"
            else -> null
        }
        val verify = directives.first("verify-x509-name")
        val remoteCert = directives.first("remote-cert-tls")?.firstOrNull()
        val versionMin = directives.first("tls-version-min")?.firstOrNull()
        val versionMax = directives.first("tls-version-max")?.firstOrNull()
        val tlsCipher = tlsCipherList(directives.first("tls-cipher")?.firstOrNull())
        val groups = tlsGroupList(directives.first("tls-groups")?.firstOrNull())

        val fingerprints = (blocks["peer-fingerprint"] ?: directives.first("peer-fingerprint"))
            ?.mapNotNull(::certFingerprint)?.takeIf { it.isNotEmpty() }
        val remoteKu = directives.first("remote-cert-ku")?.takeIf { it.isNotEmpty() }
        val remoteEku = directives.first("remote-cert-eku")?.joinToString(" ")?.takeIf { it.isNotBlank() }
        val nsCertType = directives.first("ns-cert-type")?.firstOrNull()
        if (ca == null && cert == null && key == null && wrapType == null && verify == null &&
            remoteCert == null && versionMin == null && versionMax == null && tlsCipher == null &&
            groups == null && fingerprints == null && remoteKu == null && remoteEku == null &&
            nsCertType == null
        ) {
            return null
        }
        return buildJsonObject {
            ca?.let { putJsonArray("certificate") { it.forEach { line -> add(line) } } }
            cert?.let { putJsonArray("client_certificate") { it.forEach { line -> add(line) } } }
            key?.let { putJsonArray("client_key") { it.forEach { line -> add(line) } } }
            verify?.let { args ->
                args.getOrNull(0)?.let { put("server_name", it) }

                put("server_name_type", args.getOrNull(1) ?: "subject")
            }
            remoteCert?.let { put("remote_certificate_tls", it) }
            versionMin?.let { put("version_min", it) }
            versionMax?.let { put("version_max", it) }
            tlsCipher?.let { put("cipher", it) }
            groups?.let { put("groups", it) }

            fingerprints?.let { list ->
                putJsonArray("peer_fingerprint") { list.forEach { add(it) } }
            }
            remoteKu?.let { list -> putJsonArray("remote_certificate_ku") { list.forEach { add(it) } } }
            remoteEku?.let { put("remote_certificate_eku", it) }
            nsCertType?.let { put("ns_certificate_type", it) }
            if (wrapType != null) {
                val material = blocks["tls-crypt-v2"] ?: blocks["tls-crypt"] ?: blocks.getValue("tls-auth")
                putJsonObject("control_wrap") {
                    put("type", wrapType)
                    putJsonArray("key") { material.forEach { line -> add(line) } }
                    if (wrapType == "tls_auth") {
                        keyDirection(directives, "tls-auth")?.let { put("direction", it) }
                    }
                }
            }
        }
    }

    private fun tlsGroupList(raw: String?): String? {
        val tokens = raw?.split(':')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?: return null
        return tokens.filter { it in TLS_GROUP_NAMES }.distinct()
            .joinToString(":").takeIf { it.isNotEmpty() }
    }

    private fun certFingerprint(raw: String): String? = raw
        .filterNot { it == ':' || it == ' ' || it == '-' }
        .lowercase()
        .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun tlsCipherList(raw: String?): String? {
        val tokens = raw?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        val usable = tokens

            .map { if (it.startsWith("TLS-", ignoreCase = true)) it.replace('-', '_') else it }
            .filter { it in TLS_CIPHER_NAMES }
            .distinct()
        return usable.joinToString(":").takeIf { it.isNotEmpty() }
    }

    private fun keyDirection(directives: List<Directive>, directive: String): String? {
        val value = directives.first("key-direction")?.firstOrNull()
            ?: directives.first(directive)?.lastOrNull()?.takeIf { it == "0" || it == "1" }
            ?: return null
        return when (value) {
            "1" -> "client"
            "0" -> "server"
            else -> null
        }
    }

    private val TLS_GROUP_NAMES: Set<String> = setOf(
        "X25519", "CURVE25519",
        "SECP256R1", "PRIME256V1", "P-256", "NISTP256",
        "SECP384R1", "P-384", "NISTP384",
        "SECP521R1", "P-521", "NISTP521",
    )

    private val TLS_CIPHER_NAMES: Set<String> = setOf(
        "RC4-SHA", "TLS_RSA_WITH_RC4_128_SHA",
        "DES-CBC3-SHA", "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
        "AES128-SHA", "TLS_RSA_WITH_AES_128_CBC_SHA",
        "AES256-SHA", "TLS_RSA_WITH_AES_256_CBC_SHA",
        "AES128-SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "AES128-GCM-SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "AES256-GCM-SHA384", "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-ECDSA-AES128-SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "ECDHE-ECDSA-AES256-SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "ECDHE-ECDSA-RC4-SHA", "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
        "ECDHE-RSA-RC4-SHA", "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
        "ECDHE-RSA-DES-CBC3-SHA", "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
        "ECDHE-RSA-AES128-SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "ECDHE-RSA-AES256-SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "ECDHE-ECDSA-AES128-SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "ECDHE-RSA-AES128-SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "ECDHE-ECDSA-AES128-GCM-SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "ECDHE-RSA-AES128-GCM-SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "ECDHE-ECDSA-AES256-GCM-SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-RSA-AES256-GCM-SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "ECDHE-RSA-CHACHA20-POLY1305", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    private data class Directive(val name: String, val args: List<String>)

    private data class Remote(val host: String, val port: Int, val network: String)

    private fun refuseWhatCannotRun(directives: List<Directive>, blocks: Map<String, List<String>>) {
        directives.first("dev")?.firstOrNull()?.let {
            if (it.startsWith("tap")) throw UnsupportedException(Reason.Tap, it)
        }
        directives.first("dev-type")?.firstOrNull()?.let {
            if (it.startsWith("tap")) throw UnsupportedException(Reason.Tap, it)
        }
        listOf("pkcs12", "cryptoapicert", "pkcs11-id").forEach { name ->
            if (directives.any { it.name == name }) throw UnsupportedException(Reason.UnsupportedCredentials, name)
        }

        listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret").forEach { name ->
            val named = directives.first(name)?.firstOrNull()
            if (named != null && !blocks.containsKey(name)) {
                throw UnsupportedException(Reason.ExternalFiles, "$name $named")
            }
        }
    }

    private fun ifconfig(directives: List<Directive>): Pair<String, String>? {
        val args = directives.first("ifconfig") ?: return null
        val local = args.getOrNull(0)?.takeIf { it.count { c -> c == '.' } == 3 } ?: return null
        val peer = args.getOrNull(1)?.takeIf { it.count { c -> c == '.' } == 3 } ?: return null
        return "$local/32" to peer
    }

    private fun remotes(directives: List<Directive>): List<Remote> {
        val defaultPort = directives.number("port") ?: 1194
        val defaultNetwork = network(directives.first("proto")?.firstOrNull()) ?: "udp"
        return directives.filter { it.name == "remote" }.mapNotNull { d ->
            val host = d.args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Remote(
                host = host,
                port = d.args.getOrNull(1)?.toIntOrNull() ?: defaultPort,
                network = network(d.args.getOrNull(2)) ?: defaultNetwork,
            )
        }
    }

    private fun network(value: String?): String? = when {
        value == null -> null
        value.startsWith("tcp") -> "tcp"
        value.startsWith("udp") -> "udp"
        else -> null
    }

    private fun inlineCredentials(blocks: Map<String, List<String>>): Pair<String, String>? {
        val lines = blocks["auth-user-pass"]?.filter { it.isNotBlank() } ?: return null
        if (lines.size < 2) return null
        return lines[0] to lines[1]
    }

    private fun comment(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { (it.startsWith("#") || it.startsWith(";")) && it.length > 2 }
        ?.drop(1)?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 }

    private fun List<Directive>.first(name: String): List<String>? = firstOrNull { it.name == name }?.args

    private fun List<Directive>.flag(name: String): Boolean = any { it.name == name }

    private fun List<Directive>.number(name: String): Int? = first(name)?.firstOrNull()?.toIntOrNull()

    private fun directives(text: String): List<Directive> {
        val out = mutableListOf<Directive>()
        var skipUntil: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.equals("<connection>", ignoreCase = true) || line.equals("</connection>", ignoreCase = true)) {
                return@forEach
            }
            if (skipUntil != null) {
                if (line.equals(skipUntil, ignoreCase = true)) skipUntil = null
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                skipUntil = "</" + line.trim('<', '>') + ">"
                return@forEach
            }
            val tokens = tokenize(line)
            if (tokens.isNotEmpty()) out += Directive(tokens.first().lowercase(), tokens.drop(1))
        }
        return out
    }

    private fun blocks(text: String): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        var name: String? = null
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                name == null && line.startsWith("<") && line.endsWith(">") && !line.startsWith("</") -> {
                    name = line.trim('<', '>').lowercase()
                    lines.clear()
                }
                name != null && line.equals("</$name>", ignoreCase = true) -> {
                    out[name!!] = lines.toList()
                    name = null
                }
                name != null -> lines += line
            }
        }
        return out
    }

    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        line.forEach { c ->
            when {
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> {
                    if (token.isNotEmpty()) {
                        out += token.toString()
                        token.clear()
                    }
                }
                else -> token.append(c)
            }
        }
        if (token.isNotEmpty()) out += token.toString()
        return out
    }

    private val prettyJson = Json { prettyPrint = true }

    private val OVPN_MARKERS = setOf(
        "client", "dev", "proto", "ca", "cert", "key", "auth-user-pass", "tls-client", "remote-cert-tls",
        "cipher", "comp-lzo", "compress", "tls-auth", "tls-crypt", "resolv-retry", "persist-tun", "nobind",
    )

    private val INLINE_BLOCKS = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret")
}
