package com.aras.client.handler.npv

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream
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

    /**
     * NPV Tunnel Gen-2 AppKey White-Box derivation.
     * Table layout and test vector are compatible with the MIT-licensed
     * NPV//FORGE implementation; the arithmetic below is an independent Kotlin port.
     */
    fun npvGen2A16(salt: ByteArray, compressedTables: ByteArray): ByteArray? = runCatching {
        if (salt.size != 16 || compressedTables.isEmpty()) return null
        val tables = InflaterInputStream(ByteArrayInputStream(compressedTables)).use { it.readBytes() }
        val blockSize = 57_344
        val blockCount = 13
        if (tables.size != blockSize * blockCount + 4_096) return null
        val shift = intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)
        val state = salt.copyOf()
        repeat(blockCount) { blockIndex ->
            val block = tables.copyOfRange(blockIndex * blockSize, (blockIndex + 1) * blockSize)
            val shifted = ByteArray(16) { state[shift[it]] }
            System.arraycopy(shifted, 0, state, 0, 16)
            repeat(4) { group ->
                var args = npvGen2GroupArgs(block, 0x6000, group, state, group * 4)
                var result = npvGen2Sub7DC0(block, group, args[0], args[1], args[2], args[3])
                System.arraycopy(result, 0, state, group * 4, 4)
                args = npvGen2GroupArgs(block, 0xa000, group, state, group * 4)
                result = npvGen2Sub7DC0(block, group, args[0], args[1], args[2], args[3])
                System.arraycopy(result, 0, state, group * 4, 4)
            }
        }
        val pageOffset = blockSize * blockCount
        val page = tables.copyOfRange(pageOffset, tables.size)
        ByteArray(16) { i -> page[0x100 * i + (state[shift[i]].toInt() and 0xff)] }
    }.getOrNull()

    private fun npvGen2GroupArgs(block: ByteArray, half: Int, group: Int, state: ByteArray, stateOffset: Int): LongArray {
        val base = half + 0x1000 * group
        fun be32(offset: Int): Long =
            ((block[offset].toLong() and 0xff) shl 24) or
                ((block[offset + 1].toLong() and 0xff) shl 16) or
                ((block[offset + 2].toLong() and 0xff) shl 8) or
                (block[offset + 3].toLong() and 0xff)
        return longArrayOf(
            be32(base + 4 * (state[stateOffset].toInt() and 0xff)),
            be32(base + 0x400 + 4 * (state[stateOffset + 1].toInt() and 0xff)),
            be32(base + 0x800 + 4 * (state[stateOffset + 2].toInt() and 0xff)),
            be32(base + 0xc00 + 4 * (state[stateOffset + 3].toInt() and 0xff))
        )
    }

    private fun npvGen2Sub7DC0(block: ByteArray, index: Int, in3: Long, in4: Long, in5: Long, in6: Long): ByteArray {
        fun u32(v: Long) = v and 0xffffffffL
        fun at(address: Long): Long = (block[u32(address).toInt()].toInt() and 0xff).toLong()
        val x2 = index.toLong()
        val x3 = u32(in3); val x4 = u32(in4); val x5 = u32(in5); val x6 = u32(in6)
        var x0 = 0L; var x8 = 0L; var x9 = 0L; var x10 = 0L; var x11 = 0L
        var x12 = 0L; var x13 = 0L; var x14 = 0L; var x15 = 0L; var x16 = 0L; var x17 = 0L
        val out = ByteArray(4)

        x8 = u32(x2 + u32(x2 shl 1))
        x12 = u32(x3 ushr 0x14)
        x11 = u32(x3 ushr 0x18)
        x13 = (x4 ushr 0x18) and 0xf
        x14 = u32(x5 ushr 0x18)
        x10 = x6
        x9 = u32(x8 shl 3)
        x8 = x4
        x12 = x12 and 0xf0
        x12 = u32(x12 + x13)
        x13 = u32(x5 ushr 0x14)
        x16 = x8 ushr 0x1c
        x9 = u32(x9)
        x11 = x11 and 0xf0
        x14 = x14 and 0xf0
        x15 = u32(x6 ushr 0x18) and 0xf
        x13 = x13 and 0xf0
        x17 = u32(x3 ushr 0x10)
        x9 = u32(x9 shl 8)
        x8 = x8 and 0xf
        x13 = u32(x13 + x15)
        x17 = x17 and 0xf0
        x11 = u32(x9 + x11)
        x14 = u32(x9 + x14)
        x12 = u32(x9 + x12)
        x16 = at(x11 + x16)
        x14 = u32(x14 + (x10 ushr 28))
        x12 = at(x12 + 0x200)
        x13 = u32(x9 + x13)
        x0 = u32(x6 ushr 0x14) and 0xf
        x14 = at(x14 + 0x100)
        x15 = u32(x9 + (x16 shl 4))
        x13 = at(x13 + 0x300)
        x12 = u32(x9 + (x12 shl 4))
        x16 = u32(x4 ushr 0x14) and 0xf
        x10 = x10 and 0xf
        x14 = u32(x15 + x14)
        x15 = u32(x5 ushr 0x10)
        x12 = u32(x12 + x13)
        x13 = u32(x3 ushr 0xc)
        x14 = at(x14 + 0x400)
        x12 = at(x12 + 0x500)
        x15 = x15 and 0xf0
        x16 = u32(x17 + x16)
        x15 = u32(x15 + x0)
        x0 = u32(x4 ushr 0x10) and 0xf
        x17 = u32(x5 ushr 0xc)
        x12 = u32(x12 or (x14 shl 4))
        x13 = x13 and 0xf0
        x14 = u32(x6 ushr 0x10) and 0xf
        x13 = u32(x13 + x0)
        x17 = x17 and 0xf0
        x16 = u32(x9 + x16)
        out[0] = x12.toByte()
        x13 = u32(x9 + x13)
        x12 = u32(x17 + x14)
        x14 = at(x16 + 0x600)
        x13 = at(x13 + 0x800)
        x15 = u32(x9 + x15)
        x12 = u32(x9 + x12)
        x15 = at(x15 + 0x700)
        x16 = u32(x5 ushr 8)
        x12 = at(x12 + 0x900)
        x14 = u32(x9 + (x14 shl 4))
        x13 = u32(x9 + (x13 shl 4))
        x0 = u32(x6 ushr 0xc) and 0xf
        x17 = u32(x3 ushr 8)
        x14 = u32(x14 + x15)
        x12 = u32(x13 + x12)
        x13 = x16 and 0xf0
        x14 = at(x14 + 0xa00)
        x12 = at(x12 + 0xb00)
        x15 = u32(x3 ushr 4)
        x13 = u32(x13 + x0)
        x0 = u32(x4 ushr 8) and 0xf
        x16 = u32(x4 ushr 0xc) and 0xf
        x12 = u32(x12 or (x14 shl 4))
        x14 = x15 and 0xf0
        x17 = x17 and 0xf0
        x14 = u32(x14 + x0)
        x15 = u32(x9 + 0xc00)
        x0 = u32(x5 ushr 4)
        x17 = u32(x15 + x17)
        out[1] = x12.toByte()
        x12 = u32(x6 ushr 8) and 0xf
        x16 = at(x17 + x16)
        x14 = u32(x9 + x14)
        x17 = x0 and 0xf0
        x14 = at(x14 + 0xe00)
        x12 = u32(x17 + x12)
        x13 = u32(x15 + x13)
        x12 = u32(x9 + x12)
        x13 = at(x13 + 0x100)
        x15 = u32(x9 + (x16 shl 4))
        x12 = at(x12 + 0xf00)
        x14 = u32(x9 + (x14 shl 4))
        x16 = 0x1000
        x13 = u32(x15 + x13)
        x17 = u32(x6 ushr 4) and 0xf
        x0 = x5 and 0xf0
        x12 = u32(x14 + x12)
        x14 = 0x1100
        x13 = at(x13 + x16)
        x12 = at(x12 + x14)
        x14 = 0x1200
        x15 = u32(x4 ushr 4) and 0xf
        x16 = x3 and 0xf0
        x17 = u32(x0 + x17)
        x14 = u32(x9 + x14)
        x12 = u32(x12 or (x13 shl 4))
        x13 = (x3 and 0xf) shl 4
        x16 = u32(x14 + x16)
        out[2] = x12.toByte()
        x8 = u32(x13 + x8)
        x12 = u32(x14 + x17)
        x14 = (x5 and 0xf) shl 4
        x13 = at(x16 + x15)
        x8 = u32(x9 + x8)
        x15 = 0x1400
        x12 = at(x12 + 0x100)
        x8 = at(x8 + x15)
        x10 = u32(x14 + x10)
        x14 = 0x1500
        x10 = u32(x9 + x10)
        x13 = u32(x9 + (x13 shl 4))
        x10 = at(x10 + x14)
        x8 = u32(x9 + (x8 shl 4))
        x9 = u32(x13 + x12)
        x12 = 0x1600
        x8 = u32(x8 + x10)
        x10 = 0x1700
        x9 = at(x9 + x12)
        x8 = at(x8 + x10)
        x8 = u32(x8 or (x9 shl 4))
        out[3] = x8.toByte()
        return out
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
