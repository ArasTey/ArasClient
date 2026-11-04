package com.aras.client.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NpvConfigImporterTest {

    @Test
    fun importsOpenNpvo1VlessProfileIntoTheBatchLinkFormat() {
        val payload = """
            NPVO1
            {
              "configs": [{
                "name": "NPV Germany",
                "v2rayProfile": {
                  "configType": 5,
                  "server": "edge.example.net",
                  "serverPort": "443",
                  "password": "123e4567-e89b-12d3-a456-426614174000",
                  "network": "ws",
                  "security": "tls",
                  "sni": "cdn.example.net",
                  "host": "front.example.net",
                  "path": "/tunnel"
                }
              }]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val result = NpvConfigImporter.inspect(payload, "profile.npvs")
        assertTrue(result is NpvConfigImporter.Result.Open)
        val opened = result as NpvConfigImporter.Result.Open
        assertEquals(1, opened.count)
        assertTrue(opened.shareLinksText.startsWith("vless://"))
        assertTrue(opened.shareLinksText.contains("edge.example.net:443"))
        assertTrue(opened.shareLinksText.contains("sni=cdn.example.net"))
        assertTrue(opened.shareLinksText.contains("host=front.example.net"))
        assertTrue(opened.shareLinksText.contains("path=%2Ftunnel"))
    }

    @Test
    fun decryptsNpvt1WhiteBoxChunksAndImportsEmbeddedVlessProfiles() {
        val tables = javaClass.classLoader!!
            .getResourceAsStream("npv/legacy_whitebox_tables.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val profile = """[{"name":"NPVT VLESS","v2rayProfile":{"configType":5,"server":"edge.example.net","serverPort":"443","password":"123e4567-e89b-12d3-a456-426614174000","network":"ws","security":"tls","sni":"cdn.example.net","host":"front.example.net","path":"/ws"}}]"""
        val encrypted = com.aras.client.handler.npv.NpvCrypto.encryptLegacyChunkForTest(
            profile.toByteArray(StandardCharsets.UTF_8),
            ByteArray(16) { (it * 7 + 3).toByte() },
            tables
        )
        val container = "NPVT1\\n" + java.util.Base64.getEncoder().encodeToString(encrypted)
        val result = NpvConfigImporter.inspect(
            container.toByteArray(StandardCharsets.UTF_8),
            "profile.npvt",
            legacyTablesJson = tables
        )
        assertTrue(result is NpvConfigImporter.Result.Open)
        val opened = result as NpvConfigImporter.Result.Open
        assertEquals(1, opened.count)
        assertTrue(opened.shareLinksText.startsWith("vless://"))
        assertTrue(opened.shareLinksText.contains("edge.example.net:443"))
    }

    @Test
    fun appKeyProtectedGen2IsNotMistakenForAPassphraseFile() {
        val bytes = ByteArray(9 + 135 + 16 + 32 + 64)
        bytes[0] = 'N'.code.toByte()
        bytes[1] = 'P'.code.toByte()
        bytes[2] = 'V'.code.toByte()
        bytes[3] = 'S'.code.toByte()
        bytes[4] = 5
        bytes[8] = 135
        bytes[9 + 50] = 2

        val result = NpvConfigImporter.inspect(bytes, "locked.npvs")
        assertTrue(result is NpvConfigImporter.Result.Failed)
        assertFalse((result as NpvConfigImporter.Result.Failed).message.contains("رمز سازنده را وارد کنید"))
    }

    @Test
    fun legacyNpvtDoesNotAskForAnUnrelatedPassphrase() {
        val result = NpvConfigImporter.inspect("NPVT1\nnot-a-valid-container".toByteArray(), "legacy.npvt")
        assertTrue(result is NpvConfigImporter.Result.Failed)
        assertFalse((result as NpvConfigImporter.Result.Failed).message.contains("رمز"))
    }
}
