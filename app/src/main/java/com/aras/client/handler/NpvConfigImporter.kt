package com.aras.client.handler

import com.aras.client.handler.npv.NpvCrypto
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
 * - Open NPVO1 JSON exports are traversed and normalized into share links.
 * - Sealed NPVS Gen-2 (v5) passphrase envelopes: user enters creator password.
 * - Encrypted AppKey/legacy NPVT containers are reported accurately when their
 *   required White-Box engine is not present; they are never guessed as plaintext.
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

    fun hasNpvMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val head = String(bytes, 0, minOf(5, bytes.size), StandardCharsets.US_ASCII)
        return head.startsWith("NPVS") || head.startsWith("NPVT") || head.startsWith("NPVO")
    }

    fun inspect(bytes: ByteArray, fileName: String? = null): Result {
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
                if (isNpvGen2Passphrase(bytes)) {
                    return Result.NeedPassword(
                        "فایل NPVS قفل‌شده با رمز سازنده است. رمز را وارد کنید (مثل اپ NPV Tunnel)."
                    )
                }
                return if (isNpvGen2AppKey(bytes)) {
                    Result.Failed(
                        "این NPVS v5 با AppKey رمزگذاری شده است؛ برای بازکردن آن به موتور White-Box نسل ۵ نیاز است. رمز عبور معمولی این فایل را باز نمی‌کند."
                    )
                } else {
                    Result.NeedPassword(
                        "فایل NPVS قفل‌شده با رمز سازنده است. رمز را از سازنده‌ی فایل دریافت و وارد کنید."
                    )
                }
            }
            magic.startsWith("NPVT") ||
                (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII).startsWith("NPVT")) -> {
                val recovered = extractFromBinaryish(bytes)
                if (recovered.isNotEmpty()) {
                    return Result.Open(recovered.joinToString("\n"), recovered.size, "npvt-recovered")
                }
                return Result.Failed(
                    "ظرف رمزگذاری‌شده‌ی NPVT1 شناسایی شد، اما این نسخه هنوز به موتور رمزگشایی White-Box نسخه‌ی NPVT1 نیاز دارد؛ رمز عبور معمولی برای این فرمت کاربرد ندارد."
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

    fun inspectWithPassphrase(bytes: ByteArray, passphrase: String, fileName: String? = null): Result {
        val base = inspect(bytes, fileName)
        if (base is Result.Open) return base
        if (passphrase.isBlank()) return base

        val gen2 = tryOpenGen2Passphrase(bytes, passphrase)
        if (gen2 != null && gen2.isNotEmpty()) {
            return Result.Open(gen2.joinToString("\n"), gen2.size, "npvs-gen2-pass")
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
                "رمز اشتباه است یا فایل فقط با AppKey اپ NPV (whitebox) ساخته شده که در این بیلد پشتیبانی نمی‌شود."
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
        when (network) {
            "ws", "httpupgrade", "xhttp" -> {
                value("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
                value("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
            }
            "grpc" -> value("serviceName").takeIf(String::isNotBlank)?.let { query["serviceName"] = it }
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
            1 -> {
                val vmess = linkedMapOf(
                    "v" to "2", "ps" to name, "add" to server, "port" to port.toString(),
                    "id" to user, "aid" to value("alterId", "0"), "scy" to value("method", "auto"),
                    "net" to network, "type" to "none", "host" to value("host"), "path" to value("path"),
                    "tls" to if (security == "tls") "tls" else "", "sni" to value("sni")
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
            val hdrLen = NpvCrypto.be32(b, 5)
            val h = b.copyOfRange(9, 9 + hdrLen)
            val recipients = NpvCrypto.be16(h, 51)
            var off = GEN2_KEY_BLOCK_OFFSET + recipients * (GEN2_RECIPIENT_PK + GEN2_RECIPIENT_WRAP)
            if (off + GEN2_PASS_BLOCK_SIZE > h.size) return null
            val iters = NpvCrypto.be32(h, off)
            if (iters < 1000 || iters > 5_000_000) return null
            val salt = h.copyOfRange(off + 4, off + 20)
            val wrap = h.copyOfRange(off + 20, off + GEN2_PASS_BLOCK_SIZE)
            val kdk = NpvCrypto.pbkdf2HmacSha256(
                passphrase.toByteArray(StandardCharsets.UTF_8),
                salt,
                iters,
                32
            )
            if (wrap.size < 28) return null
            val wrapNonce = wrap.copyOfRange(0, 12)
            val wrapCt = wrap.copyOfRange(12, wrap.size)
            val dek = NpvCrypto.chacha20Poly1305Open(kdk, wrapNonce, wrapCt, salt) ?: return null

            var bodyOff = 9 + hdrLen
            if (bodyOff + 16 > b.size) return null
            val bodyLen = NpvCrypto.be32(b, bodyOff + 12)
            bodyOff += 16
            if (bodyLen < 32 || bodyOff + bodyLen > b.size) return null
            val body = b.copyOfRange(bodyOff, bodyOff + bodyLen)

            val links = LinkedHashSet<String>()
            extractFromBinaryish(body).forEach { links += it }
            extractFromBinaryish(dek).forEach { links += it }
            extractFromBinaryish(b).forEach { links += it }
            if (links.isEmpty()) return null
            links.take(200).toList()
        } catch (_: Exception) {
            null
        }
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
