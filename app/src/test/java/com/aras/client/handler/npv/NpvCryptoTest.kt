package com.aras.client.handler.npv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class NpvCryptoTest {

    @Test
    fun gen2WhiteBoxA16MatchesPublishedNpvForgeTestVector() {
        val encoded = javaClass.classLoader!!
            .getResourceAsStream("npv/gen2_tables.bin.z.b64")!!
            .bufferedReader(Charsets.US_ASCII)
            .use { it.readText() }
        val compressed = Base64.getDecoder().decode(encoded)
        val salt = "b3e16e38809576cbf6aaafd638b6b070".chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()

        val a16 = NpvCrypto.npvGen2A16(salt, compressed)
        assertNotNull(a16)
        assertEquals("ffd6b86349231c14b37f6c929e24cd42", a16!!.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun hkdfSha256MatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = "000102030405060708090a0b0c".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val info = "f0f1f2f3f4f5f6f7f8f9".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actual = NpvCrypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            actual.joinToString("") { "%02x".format(it) }
        )
    }
}
