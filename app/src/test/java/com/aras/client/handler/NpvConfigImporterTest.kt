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
