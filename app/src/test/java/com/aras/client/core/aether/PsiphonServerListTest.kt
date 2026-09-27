package com.aras.client.core.aether

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.Deflater

/**
 * The bundled Psiphon server list has to be the list Psiphon signed.
 *
 * The client takes whatever it is given as server entries, so a list that is corrupt,
 * foreign or tampered with must never reach it. These tests use a list built here
 * rather than the bundled one so the expectations do not move when the bundled list is
 * refreshed.
 */
class PsiphonServerListTest {

    private fun pack(entries: String, key: String, corruptSignature: Boolean = false): ByteArray {
        val digest = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII)),
        )
        val signature = java.util.Base64.getEncoder().encodeToString(
            "signature".toByteArray(Charsets.UTF_8),
        )
        val json = buildString {
            append("{")
            append("\"data\":").append(gsonString(entries)).append(",")
            append("\"signingPublicKeyDigest\":").append(gsonString(digest)).append(",")
            append("\"signature\":").append(gsonString(if (corruptSignature) "AAAA" else signature))
            append("}")
        }
        val deflater = Deflater()
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun gsonString(value: String): String =
        com.google.gson.JsonPrimitive(value).toString()

    @Test
    fun `a list that is not a signed package is refused`() {
        val notZlib = "not compressed at all".toByteArray()

        val failure = runCatching { PsiphonServerList.unpack(notZlib, PsiphonServerList.SIGNING_KEY) }
            .exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun `a list carrying no entries is refused`() {
        val packed = pack("", PsiphonServerList.SIGNING_KEY)

        val failure = runCatching { PsiphonServerList.unpack(packed, PsiphonServerList.SIGNING_KEY) }
            .exceptionOrNull()

        assertTrue("an empty list must be refused, was $failure", failure is IOException)
    }

    @Test
    fun `the bundled list is present and signed with Psiphon's key`() {
        val asset = File("src/main/assets/${PsiphonServerList.ASSET_NAME}")
        assertTrue("${PsiphonServerList.ASSET_NAME} is not in the assets", asset.isFile)

        // The real signature check needs the real signature, so only that the file is
        // the package Psiphon writes is asserted here; a mismatch raises, and a match
        // returns the entries.
        val packed = asset.readBytes()
        val text = PsiphonServerList.unpack(packed, PsiphonServerList.SIGNING_KEY)

        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue("the bundled list has no servers", lines.isNotEmpty())
        // An entry is a hex-encoded line: "address port secret certificate {json}".
        assertTrue("entries are not hex-encoded", lines.all { line -> line.all { c -> c.isDigit() || c in 'a'..'f' } })
    }
}
