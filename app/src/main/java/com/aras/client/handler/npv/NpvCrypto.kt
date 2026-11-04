package com.aras.client.handler.npv

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto helpers for NPV Tunnel passphrase-protected envelopes (Gen-2 / NPVS v5).
 * Based on public structures used by open-source NPV readers (PBKDF2-HMAC-SHA256 +
 * ChaCha20-Poly1305). AppKey whitebox tables are intentionally not bundled.
 */
object NpvCrypto {

    private const val KEY_LEN = 32

    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int = KEY_LEN): ByteArray {
        return try {
            val spec = PBEKeySpec(
                String(password, StandardCharsets.UTF_8).toCharArray(),
                salt,
                iterations.coerceIn(1, 5_000_000),
                keyLen * 8
            )
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            skf.generateSecret(spec).encoded.copyOf(keyLen)
        } catch (_: Exception) {
            purePbkdf2HmacSha256(password, salt, iterations, keyLen)
        }
    }

    private fun purePbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLen = 32
        val blocks = (keyLen + hashLen - 1) / hashLen
        val out = ByteArray(blocks * hashLen)
        val t = ByteArray(hashLen)
        for (block in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    ((block ushr 24) and 0xff).toByte(),
                    ((block ushr 16) and 0xff).toByte(),
                    ((block ushr 8) and 0xff).toByte(),
                    (block and 0xff).toByte()
                )
            )
            var ui = mac.doFinal()
            System.arraycopy(ui, 0, t, 0, hashLen)
            repeat(iterations - 1) {
                mac.reset()
                ui = mac.doFinal(ui)
                for (i in 0 until hashLen) t[i] = (t[i].toInt() xor ui[i].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hashLen, hashLen)
        }
        return out.copyOf(keyLen)
    }

    fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray? {
        if (key.size != 32 || nonce.size != 12 || ciphertextAndTag.size < 16) return null
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val spec = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), spec)
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertextAndTag)
        } catch (_: Exception) {
            null
        }
    }

    fun be32(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val actualSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(actualSalt, "HmacSHA256"))
        }
        val prk = extract.doFinal(ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(prk, "HmacSHA256"))
            }
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val count = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, output, offset, count)
            offset += count
            counter++
        }
        return output
    }

    fun be32(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return -1
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    fun putBe16(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte()
    )

    fun putBe32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte()
    )

    fun be16(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return ByteBuffer.wrap(b, off, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
