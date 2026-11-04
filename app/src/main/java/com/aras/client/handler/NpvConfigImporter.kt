package com.aras.client.handler

import com.aras.client.handler.npv.NpvCrypto
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Reader for NPV Tunnel / NapsternetV config files
 * (.npv / .npvt / .npvs / .npv4 / .npv3 / .npv2).
 *
 * - Open text, share-links, base64 subs, and recoverable embedded links
 *   are imported via [AngConfigManager.importBatchConfig].
 * - NPVT1 legacy White-Box AES-CTR containers are decrypted locally, including
 *   all profile chunks; NPVO1 open JSON exports are normalized into share links.
 * - NPVS v1-v4 AppKey/passphrase and NPVS v5 Gen-2 AppKey/passphrase envelopes
 *   are opened locally using the matching White-Box tables and authenticated AEAD.
 * - Recipient/ECDH-wrapped containers require the authorized recipient private key
 *   and are reported as such rather than treated as password-protected files.
 */
object NpvConfigImporter {

    private val SHARE_PREFIXES = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "tuic://", "socks://", "socks5://",
        "wireguard://", "hysteria://", "anytls://", "mieru://", "ssh://"
    )

    private val LINK_REGEX = Regex(
        """(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic|socks5?|wireguard|hysteria|anytls|mieru|ssh)://[^\s\"'<>]+"""
    )

    sealed class Result {
        data class Open(val shareLinksText: String, val count: Int, val label: String) : Result()
        data class NeedPassword(val message: String) : Result()
        data class Failed(val message: String) : Result()
    }

    fun looksLikeNpvName(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".npv") || lower.endsWith(".npvt") ||
            lower.endsWith(".npvs") || lower.endsWith(".npv4") ||
            lower.endsWith(".npv3") || lower.endsWith(".npv2")
    }

    fun needsGen2Tables(bytes: ByteArray): Boolean = isNpvGen2AppKey(bytes)

    fun needsLegacyTables(bytes: ByteArray): Boolean =
        (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII) == "NPVT1") ||
            isNpvV1AppKey(bytes)

    fun hasNpvMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val head = String(bytes, 0, minOf(5, bytes.size), StandardCharsets.US_ASCII)
        return head.startsWith("NPVS") || head.startsWith("NPVT") || head.startsWith("NPVO")
    }

    fun inspect(
        bytes: ByteArray,
        fileName: String? = null,
        gen2Tables: ByteArray? = null,
        legacyTablesJson: String? = null,
    ): Result {
        if (bytes.isEmpty()) return Result.Failed("فایل خالی است.")

        val asText = runCatching {
            String(bytes, StandardCharsets.UTF_8).trim()
        }.getOrNull()

        if (!asText.isNullOrEmpty() && isMostlyPrintable(asText)) {
            val links = extractLinks(asText)
            if (links.isNotEmpty()) {
                return Result.Open(links.joinToString("\n"), links.size, "open-text")
            }
            if (looksLikeBase64(asText) && asText.length in 40..900_000) {
                val decoded = decodeBase64(asText)
                if (decoded != null) {
                    val fromB64 = extractLinks(decoded)
                    if (fromB64.isNotEmpty()) {
                        return Result.Open(fromB64.joinToString("\n"), fromB64.size, "base64-sub")
                    }
                }
            }
        }

        val magic = if (bytes.size >= 4) String(bytes, 0, 4, StandardCharsets.US_ASCII) else ""
        when {
            magic == "NPVS" -> {
                val recovered = extractFromBinaryish(bytes)
                if (recovered.isNotEmpty()) {
                    return Result.Open(recovered.joinToString("\n"), recovered.size, "npvs-recovered")
                }
                if (isNpvGen2Passphrase(bytes) || isNpvV1Passphrase(bytes)) {
                    return Result.NeedPassword(
                        "فایل NPVS با رمز سازنده محافظت شده است. رمز را از سازنده‌ی فایل دریافت و وارد کنید."
                    )
                }
                if (isNpvV1AppKey(bytes)) {
                    val opened = legacyTablesJson?.let { tryOpenNpvV1AppKey(bytes, it) }
                    if (!opened.isNullOrEmpty()) {
                        return Result.Open(opened.joinToString("\n"), opened.size, "npvs-v1-appkey")
                    }
                    return Result.Failed(
                        if (legacyTablesJson == null) {
                            "جداول آفلاین White-Box نسل قدیمی NPVS در دسترس نیستند."
                        } else {
                            "رمزگشایی NPVS قدیمی با AppKey انجام نشد؛ فایل ممکن است ناقص یا ناسازگار باشد."
                        }
                    )
                }
                if (isNpvGen2AppKey(bytes)) {
                    val opened = gen2Tables?.let { tryOpenGen2AppKey(bytes, it) }
                    if (!opened.isNullOrEmpty()) {
                        return Result.Open(opened.joinToString("\n"), opened.size, "npvs-gen2-appkey")
                    }
                    return Result.Failed(
                        if (gen2Tables == null) {
                            "جداول رمزگشایی White-Box نسل ۵ در دسترس نیستند؛ فایل دست‌نخورده باقی مانده است."
                        } else {
                            "رمزگشایی NPVS v5 با AppKey انجام نشد؛ فایل ممکن است ناقص یا ناسازگار باشد."
                        }
                    )
                } else if (isNpvGen2Recipient(bytes) || isNpvV1Recipient(bytes)) {
                    Result.Failed(
                        "این کانفیگ با کلید خصوصی گیرنده (Recipient/ECDH) قفل شده است؛ برای بازکردن آن کلید خصوصی مجاز همان گیرنده لازم است."
                    )
                } else {
                    Result.NeedPassword(
                        "فایل NPVS قفل‌شده با رمز سازنده است. رمز را از سازنده‌ی فایل دریافت و وارد کنید."
                    )
                }
            }
            magic.startsWith("NPVT") -> {
                val decrypted = legacyTablesJson?.let { NpvCrypto.decryptNpvt1Chunks(bytes, it) }
                if (!decrypted.isNullOrEmpty()) {
                    val links = LinkedHashSet<String>()
                    decrypted.forEach { chunk ->
                        val plain = String(chunk, StandardCharsets.UTF_8)
                        links += extractLinks(plain)
                        links += extractLinksFromNpvJson(plain)
                    }
                    if (links.isNotEmpty()) {
                        return Result.Open(links.take(200).joinToString("\n"), minOf(links.size, 200), "npvt1-decrypted")
                    }
                    return Result.Failed("NPVT1 بازگشایی شد، اما هیچ پروفایل V2Ray قابل‌واردکردنی در آن پیدا نشد.")
                }
                return Result.Failed(
                    if (legacyTablesJson == null) {
                        "جداول آفلاین رمزگشایی NPVT1 در دسترس نیستند؛ فایل اصلی تغییری نکرده است."
                    } else {
                        "رمزگشایی NPVT1 انجام نشد؛ ساختار فایل ناقص یا ناسازگار است."
                    }
                )
            }
            magic.startsWith("NPVO") -> {
                val opened = extractOpenNpvContainer(bytes)
                if (opened.isNotEmpty()) {
                    return Result.Open(opened.joinToString("\n"), opened.size, "npvo-open")
                }
            }
        }

        if (looksLikeNpvName(fileName) || hasNpvMagic(bytes)) {
            return Result.NeedPassword(
                "فایل NPV قابل‌استفاده به‌صورت باز پیدا نشد. اگر رمز دارد وارد کنید."
            )
        }
        return Result.Failed("فرمت NPV شناسایی نشد.")
    }

    fun inspectWithPassphrase(
        bytes: ByteArray,
        passphrase: String,
        fileName: String? = null,
        gen2Tables: ByteArray? = null,
        legacyTablesJson: String? = null,
    ): Result {
        val base = inspect(bytes, fileName, gen2Tables, legacyTablesJson)
        if (base is Result.Open) return base
        if (passphrase.isBlank()) return base

        val gen2 = tryOpenGen2Passphrase(bytes, passphrase)
        if (gen2 != null && gen2.isNotEmpty()) {
            return Result.Open(gen2.joinToString("\n"), gen2.size, "npvs-gen2-pass")
        }
        val legacy = tryOpenNpvV1Passphrase(bytes, passphrase)
        if (legacy != null && legacy.isNotEmpty()) {
            return Result.Open(legacy.joinToString("\n"), legacy.size, "npvs-v1-pass")
        }

        val recovered = extractFromBinaryish(bytes)
        if (recovered.isNotEmpty()) {
            return Result.Open(recovered.joinToString("\n"), recovered.size, "pass-recovered")
        }
        val asText = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
        if (asText != null) {
            val links = extractLinks(asText)
            if (links.isNotEmpty()) {
                return Result.Open(links.joinToString("\n"), links.size, "pass-text")
            }
        }
        return Result.Failed(
            "رمز وارد شد ولی پروفایلی استخراج نشد. " +
                "رمز اشتباه است یا فایل از نسل/روش قفل‌گذاری پشتیبانی‌نشده‌ای استفاده می‌کند."
        )
    }

    /**
     * NPVO1 is an open JSON container, not an encrypted NPVS file. Parse its
     * config array and normalize the common NapsternetV v2rayProfile fields.
     * Data model compatibility is cross-checked against NPV//FORGE and Pantegnos.
     */
    private fun extractOpenNpvContainer(bytes: ByteArray): List<String> {
        val text = String(bytes, StandardCharsets.UTF_8).trim()
        val payload = text.removePrefix("NPVO1").trimStart()
        if (payload.isBlank()) return emptyList()
        return runCatching {
            val root = JsonParser.parseString(payload)
            val configs = root.asJsonObject.getAsJsonArray("configs") ?: return@runCatching emptyList()
            val links = LinkedHashSet<String>()
            configs.forEach { config ->
                links += extractLinks(config.toString())
                if (config.isJsonObject) {
                    val obj = config.asJsonObject
                    obj.get("v2rayProfile")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let { profile -> profileToShareLink(obj, profile)?.let(links::add) }
                    obj.get("v2rayJson")?.let { links += extractLinks(it.toString()) }
                }
            }
            links.take(200).toList()
        }.getOrElse { emptyList() }
    }

    private fun profileToShareLink(config: JsonObject, profile: JsonObject): String? {
        fun value(key: String, fallback: String = ""): String {
            val v = profile.get(key) ?: return fallback
            return if (v.isJsonNull) fallback else runCatching { v.asString }.getOrDefault(v.toString())
        }
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val type = value("configType").toIntOrNull() ?: return null
        val server = value("server", value("address")).trim()
        val port = value("serverPort", value("port", "443")).toIntOrNull() ?: return null
        if (server.isBlank() || port !in 1..65535) return null
        val name = value("remarks", config.get("name")?.asString ?: "NPV Node")
        val network = value("network", "tcp").lowercase(Locale.ROOT)
        val security = value("security", "none").lowercase(Locale.ROOT)
        val user = value("password")
        if (user.isBlank()) return null
        val query = linkedMapOf("type" to network, "security" to security)
        value("flow").takeIf(String::isNotBlank)?.let { query["flow"] = it }
        when (network) {
            "ws" -> {
                value("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
                value("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
            }
            "grpc" -> {
                value("serviceName").takeIf(String::isNotBlank)?.let { query["serviceName"] = it }
                value("mode").takeIf(String::isNotBlank)?.let { query["mode"] = it }
                value("authority").takeIf(String::isNotBlank)?.let { query["authority"] = it }
            }
            "kcp" -> {
                value("headerType").takeIf(String::isNotBlank)?.let { query["headerType"] = it }
                value("seed").takeIf(String::isNotBlank)?.let { query["seed"] = it }
            }
            "quic" -> {
                value("key").takeIf(String::isNotBlank)?.let { query["key"] = it }
                value("headerType").takeIf(String::isNotBlank)?.let { query["headerType"] = it }
            }
            "xhttp", "httpupgrade" -> {
                value("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
                value("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
                value("xhttpMode", value("mode")).takeIf(String::isNotBlank)?.let { query["mode"] = it }
                value("xhttpExtra").takeIf(String::isNotBlank)?.let { query["extra"] = it }
            }
            "tcp", "raw" -> {
                val header = value("headerType")
                if (header.isNotBlank() && header != "none") {
                    query["headerType"] = header
                    value("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
                    value("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
                }
            }
        }
        if (security == "tls") {
            value("sni").takeIf(String::isNotBlank)?.let { query["sni"] = it }
            value("fingerPrint").takeIf(String::isNotBlank)?.let { query["fp"] = it }
            value("alpn").takeIf(String::isNotBlank)?.let { query["alpn"] = it }
            if (value("tlsAllowInsecure", value("insecure")).lowercase() in setOf("true", "1", "yes")) {
                query["allowInsecure"] = "1"
            }
        } else if (security == "reality") {
            value("sni").takeIf(String::isNotBlank)?.let { query["sni"] = it }
            value("fingerPrint").takeIf(String::isNotBlank)?.let { query["fp"] = it }
            value("publicKey").takeIf(String::isNotBlank)?.let { query["pbk"] = it }
            value("shortId").takeIf(String::isNotBlank)?.let { query["sid"] = it }
            value("spiderX").takeIf(String::isNotBlank)?.let { query["spx"] = it }
        }
        val q = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val host = if (server.contains(':') && !server.startsWith("[")) "[$server]" else server
        val fragment = enc(name)
        return when (type) {
            5 -> "vless://$user@$host:$port?$q#${fragment}"
            6 -> "trojan://$user@$host:$port?$q#${fragment}"
            3 -> {
                val method = value("method", "aes-128-gcm")
                val credentials = Base64.getEncoder().encodeToString(
                    "$method:$user".toByteArray(StandardCharsets.UTF_8)
                )
                "ss://$credentials@$host:$port#${fragment}"
            }
            7 -> {
                val username = value("username")
                val auth = if (username.isBlank()) user else "$username:$user"
                "socks5://${enc(auth)}@$host:$port#${fragment}"
            }
            1 -> {
                val vmess = linkedMapOf(
                    "v" to "2", "ps" to name, "add" to server, "port" to port.toString(),
                    "id" to user, "aid" to value("alterId", "0"), "scy" to value("method", "auto"),
                    "net" to network, "type" to "none", "host" to value("host"), "path" to value("path"),
                    "tls" to if (security == "tls") "tls" else "", "sni" to value("sni"),
                    "fp" to value("fingerPrint"), "alpn" to value("alpn"), "flow" to value("flow")
                )
                val json = com.google.gson.Gson().toJson(vmess)
                "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
            }
            else -> null
        }
    }

    private fun isNpvGen2AppKey(b: ByteArray): Boolean {
        if (b.size < 9 || String(b, 0, 4, StandardCharsets.US_ASCII) != "NPVS" || b[4] != GEN2_VERSION) return false
        val hdrLen = NpvCrypto.be32(b, 5)
        if (hdrLen < 53 || 9L + hdrLen > b.size) return false
        return (b[9 + 50].toInt() and 0xff) == 2
    }

    private fun npvV1Header(bytes: ByteArray): JsonObject? {
        if (bytes.size < 89 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "NPVS") return null
        if ((bytes[4].toInt() and 0xff) !in 0..4) return null
        val headerLength = NpvCrypto.be32(bytes, 5)
        if (headerLength <= 0 || 9L + headerLength > bytes.size) return null
        return runCatching {
            JsonParser.parseString(String(bytes, 9, headerLength, StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    private fun isNpvV1Passphrase(bytes: ByteArray): Boolean =
        npvV1Header(bytes)?.has("passphrase") == true && npvV1Header(bytes)?.has("appKey") != true

    private fun isNpvV1AppKey(bytes: ByteArray): Boolean =
        npvV1Header(bytes)?.has("appKey") == true

    private fun isNpvV1Recipient(bytes: ByteArray): Boolean {
        val header = npvV1Header(bytes) ?: return false
        return !header.has("appKey") && !header.has("passphrase") &&
            (header.getAsJsonArray("recipients")?.size() ?: 0) > 0
    }

    private fun isNpvGen2Recipient(bytes: ByteArray): Boolean {
        if (bytes.size < 62 || String(bytes, 0, 4, StandardCharsets.US_ASCII) != "NPVS" || bytes[4] != GEN2_VERSION) return false
        val headerLength = NpvCrypto.be32(bytes, 5)
        if (headerLength < 53 || 9L + headerLength > bytes.size) return false
        val header = bytes.copyOfRange(9, 9 + headerLength)
        return (header[50].toInt() and 0xff) == 0 && NpvCrypto.be16(header, 51) > 0
    }

    private fun tryOpenNpvV1AppKey(bytes: ByteArray, tablesJson: String): List<String>? {
        val header = npvV1Header(bytes) ?: return null
        val appKey = header.getAsJsonObject("appKey") ?: return null
        return try {
            val salt = decodeBase64Bytes(appKey.get("salt")?.asString ?: "") ?: return null
            val wrapped = decodeBase64Bytes(appKey.get("wrap")?.asString ?: "") ?: return null
            if (salt.size != 16 || wrapped.size != 60) return null
            val headerLength = NpvCrypto.be32(bytes, 5)
            val headerRaw = bytes.copyOfRange(9, 9 + headerLength)
            var dek: ByteArray? = null
            for (kdk in NpvCrypto.npvV1CustodianKdks(salt, tablesJson)) {
                dek = NpvCrypto.chacha20Poly1305Open(
                    kdk, wrapped.copyOfRange(0, 12), wrapped.copyOfRange(12, 60), salt
                )
                if (dek != null) break
            }
            val dataKey = dek ?: return null
            val bodyOffset = 9 + headerLength
            if (bodyOffset + 16 > bytes.size) return null
            val nonce = bytes.copyOfRange(bodyOffset, bodyOffset + 12)
            val bodyLength = NpvCrypto.be32(bytes, bodyOffset + 12)
            val encryptedOffset = bodyOffset + 16
            if (bodyLength < 16 || encryptedOffset.toLong() + bodyLength > bytes.size) return null
            val encrypted = bytes.copyOfRange(encryptedOffset, encryptedOffset + bodyLength)
            val plain = NpvCrypto.chacha20Poly1305Open(dataKey, nonce, encrypted, headerRaw) ?: return null
            val text = decodeNpvSentinels(String(plain, StandardCharsets.UTF_8))
            val links = LinkedHashSet<String>()
            links += extractLinks(text)
            links += extractLinksFromNpvJson(text)
            links.take(200).toList().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOpenNpvV1Passphrase(bytes: ByteArray, passphrase: String): List<String>? {
        val header = npvV1Header(bytes) ?: return null
        if (header.has("appKey")) return null
        val wrapInfo = header.getAsJsonObject("passphrase") ?: return null
        return try {
            val kdf = wrapInfo.get("kdf")?.asString ?: return null
            if (kdf != "pbkdf2-hmac-sha256") return null
            val iterations = wrapInfo.get("iters")?.asInt ?: return null
            if (iterations !in 1..2_000_000) return null
            val salt = decodeBase64Bytes(wrapInfo.get("salt")?.asString ?: "") ?: return null
            val wrapped = decodeBase64Bytes(wrapInfo.get("wrap")?.asString ?: "") ?: return null
            if (salt.size != 16 || wrapped.size != 60) return null
            val headerLength = NpvCrypto.be32(bytes, 5)
            val headerRaw = bytes.copyOfRange(9, 9 + headerLength)
            val derived = NpvCrypto.pbkdf2HmacSha256(
                passphrase.trim().toByteArray(StandardCharsets.UTF_8), salt, iterations, 32
            )
            val dek = NpvCrypto.chacha20Poly1305Open(
                derived, wrapped.copyOfRange(0, 12), wrapped.copyOfRange(12, 60), salt
            ) ?: return null

            val bodyOffset = 9 + headerLength
            if (bodyOffset + 16 > bytes.size) return null
            val nonce = bytes.copyOfRange(bodyOffset, bodyOffset + 12)
            val bodyLength = NpvCrypto.be32(bytes, bodyOffset + 12)
            val encryptedOffset = bodyOffset + 16
            if (bodyLength < 16 || encryptedOffset.toLong() + bodyLength > bytes.size) return null
            val encrypted = bytes.copyOfRange(encryptedOffset, encryptedOffset + bodyLength)
            val plain = NpvCrypto.chacha20Poly1305Open(dek, nonce, encrypted, headerRaw) ?: return null
            val text = decodeNpvSentinels(String(plain, StandardCharsets.UTF_8))
            val links = LinkedHashSet<String>()
            links += extractLinks(text)
            links += extractLinksFromNpvJson(text)
            links.take(200).toList().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLinksFromNpvJson(text: String): List<String> = runCatching {
        val root = JsonParser.parseString(text)
        val links = LinkedHashSet<String>()
        fun visit(element: JsonElement) {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                    links += extractLinks(element.asString)
                element.isJsonArray -> element.asJsonArray.forEach(::visit)
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    obj.get("v2rayProfile")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let { profile -> profileToShareLink(obj, profile)?.let(links::add) }
                    obj.get("v2rayJson")?.let(::visit)
                    obj.entrySet().forEach { (key, child) -> if (key != "v2rayJson") visit(child) }
                }
            }
        }
        visit(root)
        links.take(200).toList()
    }.getOrElse { emptyList() }

    private const val GEN2_VERSION: Byte = 5
    private const val GEN2_METHOD_PASS = 1
    private const val GEN2_KEY_BLOCK_OFFSET = 53
    private const val GEN2_PASS_BLOCK_SIZE = 80
    private const val GEN2_RECIPIENT_PK = 32
    private const val GEN2_RECIPIENT_WRAP = 0x5d

    private fun isNpvGen2Passphrase(b: ByteArray): Boolean {
        if (b.size < 9 || String(b, 0, 4, StandardCharsets.US_ASCII) != "NPVS") return false
        if (b[4] != GEN2_VERSION) return false
        val hdrLen = NpvCrypto.be32(b, 5)
        if (hdrLen < 53 || 9 + hdrLen > b.size) return false
        val h = b.copyOfRange(9, 9 + hdrLen)
        if (h.isEmpty() || h[0].toInt() != 1) return false
        val method = h[50].toInt() and 0xff
        return method == GEN2_METHOD_PASS
    }

    private fun tryOpenGen2Passphrase(b: ByteArray, passphrase: String): List<String>? {
        if (!isNpvGen2Passphrase(b)) return null
        return try {
            val headerLength = NpvCrypto.be32(b, 5)
            if (headerLength < 135 || 9L + headerLength > b.size) return null
            val header = b.copyOfRange(9, 9 + headerLength)
            val recipients = NpvCrypto.be16(header, 51)
            val keyBlockOffset = GEN2_KEY_BLOCK_OFFSET + recipients * (GEN2_RECIPIENT_PK + GEN2_RECIPIENT_WRAP)
            if (keyBlockOffset + GEN2_PASS_BLOCK_SIZE + 4 > header.size) return null
            val iterations = NpvCrypto.be32(header, keyBlockOffset)
            if (iterations !in 1..2_000_000) return null
            val salt = header.copyOfRange(keyBlockOffset + 4, keyBlockOffset + 20)
            val wrappedKey = header.copyOfRange(keyBlockOffset + 20, keyBlockOffset + GEN2_PASS_BLOCK_SIZE)
            if (salt.size != 16 || wrappedKey.size != 60) return null
            val kdk = NpvCrypto.pbkdf2HmacSha256(
                passphrase.trim().toByteArray(StandardCharsets.UTF_8), salt, iterations, 32
            )
            openGen2EnvelopeWithKdk(b, header, headerLength, keyBlockOffset + GEN2_PASS_BLOCK_SIZE, salt, wrappedKey, kdk)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOpenGen2AppKey(b: ByteArray, compressedTables: ByteArray): List<String>? {
        if (!isNpvGen2AppKey(b)) return null
        return try {
            val headerLength = NpvCrypto.be32(b, 5)
            if (headerLength < 135 || 9L + headerLength > b.size) return null
            val header = b.copyOfRange(9, 9 + headerLength)
            val recipients = NpvCrypto.be16(header, 51)
            val keyBlockOffset = GEN2_KEY_BLOCK_OFFSET + recipients * (GEN2_RECIPIENT_PK + GEN2_RECIPIENT_WRAP)
            if (keyBlockOffset + 78 + 4 > header.size) return null
            if (NpvCrypto.be16(header, keyBlockOffset) != 2) return null
            val salt = header.copyOfRange(keyBlockOffset + 2, keyBlockOffset + 18)
            val wrappedKey = header.copyOfRange(keyBlockOffset + 18, keyBlockOffset + 78)
            val a16 = NpvCrypto.npvGen2A16(salt, compressedTables) ?: return null
            val configId = header.copyOfRange(1, 17)
            val kdk = NpvCrypto.sha256(
                "npvtunnel/appkey/v2 ".toByteArray(StandardCharsets.UTF_8) + a16 + configId
            )
            openGen2EnvelopeWithKdk(b, header, headerLength, keyBlockOffset + 78, salt, wrappedKey, kdk)
        } catch (_: Exception) {
            null
        }
    }

    private fun openGen2EnvelopeWithKdk(
        bytes: ByteArray,
        header: ByteArray,
        headerLength: Int,
        metadataLengthOffset: Int,
        salt: ByteArray,
        wrappedKey: ByteArray,
        kdk: ByteArray,
    ): List<String>? {
        if (wrappedKey.size != 60 || salt.size != 16) return null
        val dek = NpvCrypto.chacha20Poly1305Open(
            kdk, wrappedKey.copyOfRange(0, 12), wrappedKey.copyOfRange(12, wrappedKey.size), salt
        ) ?: return null
        val metadataLength = NpvCrypto.be32(header, metadataLengthOffset)
        if (metadataLength < 16 || metadataLengthOffset + 4L + metadataLength > header.size) return null
        val headerPrefix = header.copyOfRange(0, metadataLengthOffset)
        val sealedMetadata = header.copyOfRange(metadataLengthOffset + 4, metadataLengthOffset + 4 + metadataLength)

        val bodyOffset = 9 + headerLength
        if (bodyOffset + 16 > bytes.size) return null
        val nonce = bytes.copyOfRange(bodyOffset, bodyOffset + 12)
        val bodyLength = NpvCrypto.be32(bytes, bodyOffset + 12)
        val encryptedBodyOffset = bodyOffset + 16
        if (bodyLength < 38 || encryptedBodyOffset.toLong() + bodyLength + 64 > bytes.size) return null
        val encryptedBody = bytes.copyOfRange(encryptedBodyOffset, encryptedBodyOffset + bodyLength)

        val metadataKey = NpvCrypto.hkdfSha256(
            dek, nonce, "NPVS-v5/metadata".toByteArray(StandardCharsets.UTF_8)
        )
        if (NpvCrypto.chacha20Poly1305Open(metadataKey, nonce, sealedMetadata, headerPrefix) == null) return null

        if (
            encryptedBody[0] != 0x4e.toByte() || encryptedBody[1] != 0x50.toByte() ||
            encryptedBody[2] != 0x46.toByte() || encryptedBody[3] != 0x01.toByte()
        ) return null
        val contentId = encryptedBody.copyOfRange(4, 36)
        val rowCount = NpvCrypto.be16(encryptedBody, 36)
        if (rowCount !in 1..4096) return null
        var offset = 38
        val fields = LinkedHashMap<Int, ByteArray>()
        val zeroNonce = ByteArray(12)
        val fieldPrefix = "NPV-fields-v1/field/".toByteArray(StandardCharsets.UTF_8)
        val recordPrefix = "NPV-fields-v1/record/".toByteArray(StandardCharsets.UTF_8)

        repeat(rowCount) {
            if (offset + 6 > encryptedBody.size) return null
            val sequence = NpvCrypto.be16(encryptedBody, offset)
            val blobLength = NpvCrypto.be16(encryptedBody, offset + 4)
            offset += 6
            if (blobLength < 16 || offset + blobLength > encryptedBody.size) return null
            val blob = encryptedBody.copyOfRange(offset, offset + blobLength)
            offset += blobLength
            val plainLength = blobLength - 16
            val sequenceBytes = NpvCrypto.putBe16(sequence)
            val fieldKey = NpvCrypto.hkdfSha256(dek, contentId, fieldPrefix + sequenceBytes)
            val aad = recordPrefix + contentId + sequenceBytes + NpvCrypto.putBe32(plainLength)
            NpvCrypto.chacha20Poly1305Open(fieldKey, zeroNonce, blob, aad)?.let { fields[sequence] = it }
        }

        val sentinelBytes = fields[0xffff] ?: return null
        val sentinelJson = runCatching {
            JsonParser.parseString(String(sentinelBytes, StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (!sentinelJson.isJsonObject) return null
        val configs = sentinelJson.asJsonObject.getAsJsonArray("configs") ?: return null
        val links = LinkedHashSet<String>()
        configs.forEach { rawConfig ->
            val config = substituteNpvFields(rawConfig, fields)
            links += extractLinks(config.toString())
            if (config.isJsonObject) {
                val obj = config.asJsonObject
                obj.get("v2rayProfile")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { profile -> profileToShareLink(obj, profile)?.let(links::add) }
                obj.get("v2rayJson")?.let { links += extractLinks(it.toString()) }
            }
        }
        return links.take(200).toList().takeIf { it.isNotEmpty() }
    }

    private fun substituteNpvFields(value: JsonElement, fields: Map<Int, ByteArray>): JsonElement {
        if (value.isJsonPrimitive) {
            val primitive = value.asJsonPrimitive
            if (primitive.isNumber) {
                val sequence = runCatching { primitive.asInt }.getOrNull()
                val field = sequence?.let(fields::get)
                if (field != null) {
                    return com.google.gson.JsonPrimitive(decodeNpvSentinels(String(field, StandardCharsets.UTF_8)))
                }
            }
            if (primitive.isString) {
                return com.google.gson.JsonPrimitive(decodeNpvSentinels(primitive.asString))
            }
            return value.deepCopy()
        }
        if (value.isJsonArray) {
            val result = com.google.gson.JsonArray()
            value.asJsonArray.forEach { result.add(substituteNpvFields(it, fields)) }
            return result
        }
        if (value.isJsonObject) {
            val result = JsonObject()
            value.asJsonObject.entrySet().forEach { entry ->
                result.add(entry.key, substituteNpvFields(entry.value, fields))
            }
            return result
        }
        return value
    }

    private fun decodeNpvSentinels(input: String): String {
        if (!input.contains("npvs1:")) return input
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=_-"
        val output = StringBuilder()
        var rest = input
        while (true) {
            val index = rest.indexOf("npvs1:")
            if (index < 0) {
                output.append(rest)
                break
            }
            output.append(rest.substring(0, index))
            rest = rest.substring(index + 6)
            var end = 0
            while (end < rest.length && alphabet.contains(rest[end])) end++
            val token = rest.substring(0, end)
            rest = rest.substring(end)
            val decoded = decodeBase64(token)
            output.append(decoded ?: "npvs1:$token")
        }
        return output.toString()
    }

    private fun extractLinks(text: String): List<String> {
        val out = LinkedHashSet<String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (isShareLink(line)) {
                out += cleanLink(line)
            } else if (looksLikeBase64(line) && line.length in 40..500_000) {
                decodeBase64(line)?.lineSequence()?.forEach { inner ->
                    if (isShareLink(inner.trim())) out += cleanLink(inner.trim())
                }
            }
        }
        LINK_REGEX.findAll(text).forEach { out += cleanLink(it.value) }
        return out.take(200).toList()
    }

    private fun extractFromBinaryish(bytes: ByteArray): List<String> {
        val latin = runCatching {
            String(bytes, StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return emptyList()
        return extractLinks(latin)
    }

    private fun isShareLink(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return SHARE_PREFIXES.any { lower.startsWith(it) }
    }

    private fun cleanLink(raw: String): String =
        raw.trim().trimEnd(',', ';', '.', ')', ']', '}')

    private fun looksLikeBase64(s: String): Boolean {
        if (s.length < 40) return false
        return s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
    }

    private fun decodeBase64Bytes(value: String): ByteArray? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        return runCatching {
            Base64.getDecoder().decode(normalized + "=".repeat(pad))
        }.getOrNull()
    }

    private fun decodeBase64(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(pad)
        return runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun isMostlyPrintable(s: String): Boolean {
        if (s.isEmpty()) return false
        val printable = s.count { it.code in 9..13 || it.code in 32..126 || it.code > 127 }
        return printable * 100 / s.length >= 85
    }
}
