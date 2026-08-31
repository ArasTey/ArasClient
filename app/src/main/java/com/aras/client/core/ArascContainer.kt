package com.aras.client.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ArasClient proprietary ".arasc" container.
 *
 * File layout (all integers big-endian):
 *   MAGIC      5 bytes  "ARASC"
 *   VERSION    1 byte   container version (currently 1)
 *   FLAGS      1 byte   bit 0: password-protected (layer 2 present)
 *   BODY       variable
 *
 * Body (normal):  Base64( Layer1(gzip(json)) )
 * Body (protected): Layer2 envelope around Base64( Layer1(gzip(json)) )
 *
 * Layer 1 envelope — AES-256-GCM with an app-internal key:
 *   SALT 16 | NONCE 12 | LEN 4 | ciphertext+tag
 *   (obfuscates raw URIs so the file is unreadable in text editors)
 *
 * Layer 2 envelope — AES-256-GCM with PBKDF2-HmacSHA256 password key:
 *   ITER 4 | SALT 16 | NONCE 12 | LEN 4 | ciphertext+tag
 *
 * Integrity: both layers are authenticated (GCM tag); any tamper or
 * corruption fails on decrypt. A wrong password also fails the GCM tag,
 * indistinguishable from tampering by design.
 */
object ArascContainer {

    const val MAGIC = "ARASC"
    const val VERSION: Byte = 2
    const val FILE_EXTENSION = "arasc"

    /** Legacy container version still importable. */
    const val VERSION_V1: Byte = 1

    private const val FLAG_PASSWORD: Byte = 0x01
    private const val GCM_TAG_BITS = 128
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val LAYER1_ITERATIONS = 250000
    private const val LAYER2_ITERATIONS = 600000

    /** Random padding range — frustrates payload-size fingerprinting. */
    private const val PADDING_MIN = 1024
    private const val PADDING_MAX = 8192

    private val random = SecureRandom()

    /** Fixed obfuscation key material for layer 1 (not a user secret). */
    private val layer1KeyBytes = byteArrayOf(
        0x41, 0x72, 0x61, 0x73, 0x43, 0x6c, 0x69, 0x65,
        0x6e, 0x74, 0x2d, 0x41, 0x52, 0x41, 0x53, 0x43,
        0x2d, 0x4c, 0x31, 0x2d, 0x4b, 0x65, 0x79, 0x2d,
        0x76, 0x31, 0x2d, 0x6f, 0x62, 0x66, 0x75, 0x73
    )

    class ArascException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------------ export

    fun encode(payloadJson: String, password: CharArray?): ByteArray {
        try {
            val compressed = gzip(payloadJson.toByteArray(Charsets.UTF_8))
            val inner = encryptLayer1(compressed, pad = true)
            val innerB64 = android.util.Base64.encodeToString(inner, android.util.Base64.NO_WRAP)

            val protected = password != null && password.isNotEmpty()
            val body: ByteArray = if (protected && password != null) {
                encryptLayer2(innerB64.toByteArray(Charsets.UTF_8), password)
            } else {
                innerB64.toByteArray(Charsets.UTF_8)
            }

            val header = ByteBuffer.allocate(7)
                .put(MAGIC.toByteArray(Charsets.US_ASCII))
                .put(VERSION)
                .put(if (protected) FLAG_PASSWORD else 0)
            return header.array() + body
        } catch (e: ArascException) {
            throw e
        } catch (e: Exception) {
            throw ArascException("Failed to encode .arasc container", e)
        }
    }

    // ------------------------------------------------------------------ import

    /** Result of parsing the header of a candidate file. */
    sealed class Header {
        data class Ok(val version: Byte, val passwordProtected: Boolean) : Header()
        object NotArasc : Header()
        object UnsupportedVersion : Header()
    }

    fun peekHeader(bytes: ByteArray): Header {
        if (bytes.size < 7) return Header.NotArasc
        val magic = String(bytes, 0, 5, Charsets.US_ASCII)
        if (magic != MAGIC) return Header.NotArasc
        val version = bytes[5]
        if (version != VERSION && version != VERSION_V1) return Header.UnsupportedVersion
        val flags = bytes[6].toInt()
        return Header.Ok(version, (flags and FLAG_PASSWORD.toInt()) != 0)
    }

    fun decode(bytes: ByteArray, password: CharArray?): String {
        try {
            when (val h = peekHeader(bytes)) {
                Header.NotArasc -> throw ArascException("Not an .arasc file")
                Header.UnsupportedVersion -> throw ArascException("Unsupported .arasc version")
                is Header.Ok -> {
                    val body = bytes.copyOfRange(7, bytes.size)
                    val innerB64: ByteArray = if (h.passwordProtected) {
                        if (password == null || password.isEmpty()) {
                            throw ArascException("Password required")
                        }
                        decryptLayer2(body, password)
                    } else {
                        body
                    }
                    val inner = android.util.Base64.decode(
                        String(innerB64, Charsets.UTF_8).trim(), android.util.Base64.NO_WRAP
                    )
                    val compressed = decryptLayer1(inner, h.version)
                    return String(gunzip(compressed), Charsets.UTF_8)
                }
            }
        } catch (e: ArascException) {
            throw e
        } catch (e: Exception) {
            // Wrong password, tampering and corruption all surface as GCM tag failures.
            throw ArascException("Integrity check failed or wrong password", e)
        }
    }

    // ------------------------------------------------------------------ layers

    private fun encryptLayer1(data: ByteArray, pad: Boolean): ByteArray {
        // Random padding inside the envelope: same plaintext never produces a
        // similar-size ciphertext, blocking size-based traffic analysis.
        val padded = if (pad) {
            val padLen = PADDING_MIN + random.nextInt(PADDING_MAX - PADDING_MIN)
            val marker = ByteArray(4)
            random.nextBytes(marker)
            data + ByteBuffer.allocate(4 + padLen)
                .put(marker).put(ByteArray(padLen)).array()
        } else data
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val key = deriveKey(layer1KeyBytes, salt, LAYER1_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(padded)
        return ByteBuffer.allocate(SALT_LEN + NONCE_LEN + 4 + ct.size)
            .put(salt).put(nonce).putInt(ct.size).put(ct).array()
    }

    /** Strips the random padding marker added by encryptLayer1 (v2 files). */
    private fun unpadLayer1(data: ByteArray, hasPadding: Boolean): ByteArray {
        if (!hasPadding || data.size < 4 + PADDING_MIN + 4) return data
        val buf = ByteBuffer.wrap(data)
        buf.position(data.size - 4)
        val markerLen = buf.int
        if (markerLen < PADDING_MIN || markerLen > data.size - 8) return data
        return data.copyOfRange(0, data.size - 4 - markerLen)
    }

    private fun decryptLayer1(data: ByteArray, version: Byte): ByteArray {
        val buf = ByteBuffer.wrap(data)
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val nonce = ByteArray(NONCE_LEN).also { buf.get(it) }
        val len = buf.int
        val ct = ByteArray(len).also { buf.get(it) }
        val key = deriveKey(layer1KeyBytes, salt, LAYER1_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return unpadLayer1(cipher.doFinal(ct), version >= VERSION)
    }

    private fun encryptLayer2(data: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val key = deriveKey(password, salt, LAYER2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(data)
        return ByteBuffer.allocate(4 + SALT_LEN + NONCE_LEN + 4 + ct.size)
            .putInt(LAYER2_ITERATIONS).put(salt).put(nonce).putInt(ct.size).put(ct).array()
    }

    private fun decryptLayer2(data: ByteArray, password: CharArray): ByteArray {
        val buf = ByteBuffer.wrap(data)
        val iterations = buf.int
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val nonce = ByteArray(NONCE_LEN).also { buf.get(it) }
        val len = buf.int
        val ct = ByteArray(len).also { buf.get(it) }
        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ct)
    }

    private fun deriveKey(secret: ByteArray, salt: ByteArray, iterations: Int): SecretKeySpec =
        deriveKeySpec(secret, salt, iterations)

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec =
        deriveKeySpec(password, salt, iterations)

    private fun deriveKeySpec(
        secret: Any, salt: ByteArray, iterations: Int
    ): SecretKeySpec {
        val spec = when (secret) {
            is ByteArray -> PBEKeySpec(secret.toString(Charsets.UTF_8).toCharArray(), salt, iterations, 256)
            is CharArray -> PBEKeySpec(secret, salt, iterations, 256)
            else -> throw IllegalArgumentException("Unsupported key material")
        }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    // -------------------------------------------------------------- compression

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        java.util.zip.GZIPInputStream(data.inputStream()).use { input ->
            return input.readBytes()
        }
    }
}
