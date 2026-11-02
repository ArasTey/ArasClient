package com.aras.client.handler

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Best-effort reader for NPV Tunnel / NapsternetV config files
 * (.npv / .npvt / .npvs / .npv4).
 *
 * Open payloads and recoverable share links are extracted into plain text that
 * [AngConfigManager.importBatchConfig] already understands. Sealed envelopes
 * surface [Result.NeedPassword] so the UI can prompt the user (same idea as
 * NPV Tunnel). Full Gen-2 AppKey whitebox decrypt is not shipped here.
 */
object NpvConfigImporter {

    private val SHARE_PREFIXES = listOf(
        "vless://", "vmess://", "trojan://", "ss://",
        "hysteria2://", "hy2://", "tuic://", "socks://", "socks5://",
        "wireguard://", "hysteria://", "anytls://", "mieru://"
    )

    private val LINK_REGEX = Regex(
        """(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2|tuic|socks5?|wireguard|hysteria|anytls|mieru)://[^\s\"'<>]+"""
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
                return Result.NeedPassword(
                    "فایل NPVS قفل‌شده است. رمز سازنده را وارد کنید (مثل اپ NPV Tunnel)."
                )
            }
            magic.startsWith("NPVT") ||
                (bytes.size >= 5 && String(bytes, 0, 5, StandardCharsets.US_ASCII).startsWith("NPVT")) -> {
                val recovered = extractFromBinaryish(bytes)
                if (recovered.isNotEmpty()) {
                    return Result.Open(recovered.joinToString("\n"), recovered.size, "npvt-recovered")
                }
                return Result.NeedPassword(
                    "ظرف NPVT تشخیص داده شد. اگر رمز دارد وارد کنید."
                )
            }
            magic.startsWith("NPVO") -> {
                val recovered = extractFromBinaryish(bytes)
                if (recovered.isNotEmpty()) {
                    return Result.Open(recovered.joinToString("\n"), recovered.size, "npvo")
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
                "فایل‌های فقط-AppKey (whitebox اپ NPV) در این بیلد باز نمی‌شوند."
        )
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
