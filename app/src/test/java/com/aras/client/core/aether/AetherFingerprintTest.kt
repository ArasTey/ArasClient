package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherFingerprint
import com.aras.client.enums.CHROME_EIGHT_SIGALGS
import com.aras.client.enums.CHROME_TRUST_ANCHORS
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.AetherFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TLS handshake fingerprints, the clients PattNG captures: the whole ClientHello
 * each names — suites, groups, key shares and signature algorithms in their order —
 * reaches the core as aether 2.4's TLS options, the extensions each client sends or
 * does not as --enable/--disable flags, and the value survives the aether:// link
 * round trip the way PattNG's links write it.
 */
class AetherFingerprintTest {

    private fun profile(block: ProfileItem.() -> Unit = {}) =
        ProfileItem(configType = EConfigType.AETHER).apply(block)

    private fun List<String>.valueOf(flag: String): String? {
        val index = indexOf(flag)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }

    @Test
    fun `chrome is the core's default rule and keeps grease`() {
        val args = AetherFingerprint.CHROME.arguments

        assertEquals("ALL:!aPSK:!ECDSA+SHA1:!3DES", args.valueOf("--tls-ciphers"))
        assertTrue("--disable-grease" !in args)
    }

    @Test
    fun `the captured clients turn grease off and carry their own suites`() {
        listOf(AetherFingerprint.FIREFOX, AetherFingerprint.SEMI_PYTHON, AetherFingerprint.GO)
            .forEach { fingerprint ->
                assertTrue(fingerprint.arguments.contains("--disable-grease"))
                assertTrue(fingerprint.arguments.valueOf("--tls-ciphers")!!.isNotBlank())
            }
        // The wire names PattNG writes into links.
        assertEquals(
            listOf("chrome", "chrome-120", "firefox", "semi-python", "go"),
            AetherFingerprint.entries.map { it.type },
        )
    }

    @Test
    fun `chrome names its whole ClientHello for the core to send`() {
        val args = AetherFingerprint.CHROME.arguments

        assertEquals("X25519MLKEM768:X25519:P-256:P-384", args.valueOf("--tls-groups"))
        assertTrue(args.valueOf("--tls-sigalgs")!!.startsWith("mldsa44:"))
        assertTrue("--enable-grease-sigalgs" in args)
        assertEquals("brotli", args.valueOf("--tls-cert-compression"))
        assertEquals("new", args.valueOf("--tls-alps"))
        assertEquals(CHROME_TRUST_ANCHORS, args.valueOf("--tls-trust-anchors"))
        assertEquals("6000", args.valueOf("--tls-server-padding"))
        assertEquals("--enable-ech-grease" in args, AetherFingerprint.CHROME.echGrease)
        assertTrue("--disable-ocsp-stapling" !in args)
    }

    @Test
    fun `chrome 120 is the older captured hello`() {
        val args = AetherFingerprint.CHROME_120.arguments

        assertEquals("X25519:P-256:P-384", args.valueOf("--tls-groups"))
        assertEquals(CHROME_EIGHT_SIGALGS, args.valueOf("--tls-sigalgs"))
        assertEquals("old", args.valueOf("--tls-alps"))
        assertNull(args.valueOf("--tls-trust-anchors"))
        assertNull(args.valueOf("--tls13-ciphers"))
    }

    @Test
    fun `firefox orders tls thirteen as its own client does`() {
        val args = AetherFingerprint.FIREFOX.arguments

        assertEquals(
            "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
            args.valueOf("--tls13-ciphers"),
        )
        assertEquals("X25519MLKEM768:X25519:P-256", args.valueOf("--tls-key-shares"))
        assertEquals("zlib:brotli:zstd", args.valueOf("--tls-cert-compression"))
        assertTrue("--disable-session-ticket" in args)
        assertTrue("--disable-extension-permutation" in args)
    }

    @Test
    fun `semi python asks neither ocsp nor sct, and go neither more of tls 13`() {
        val python = AetherFingerprint.SEMI_PYTHON.arguments
        assertTrue("--disable-ocsp-stapling" in python)
        assertTrue("--disable-sct" in python)

        val go = AetherFingerprint.GO.arguments
        assertTrue("--disable-session-ticket" in go)
        assertTrue("--disable-extension-permutation" in go)
        assertTrue("--disable-ocsp-stapling" !in go)
    }

    @Test
    fun `a masque session carries the chosen fingerprint's arguments`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "masque"
                aetherFingerprint = "firefox"
            },
            port = 10819,
        )

        val expected = AetherFingerprint.FIREFOX.arguments
        assertTrue("--disable-grease" in args)
        assertTrue(expected.valueOf("--tls-ciphers") in args)
    }

    @Test
    fun `an unset fingerprint is chrome, and a wg session carries none`() {
        val masque = AetherCoreManager.buildArguments(
            profile { aetherProtocol = "masque" },
            port = 10819,
        )
        assertTrue("--tls-ciphers" in masque)
        assertTrue("--disable-grease" !in masque)

        val wg = AetherCoreManager.buildArguments(
            profile { aetherProtocol = "wg" },
            port = 10819,
        )
        assertTrue("--tls-ciphers" !in wg)
    }

    @Test
    fun `the fingerprint survives an aether link round trip`() {
        val original = AetherFmt.parse(
            "aether://?protocol=masque&fingerprint=semi-python#ff"
        )!!

        val shared = EConfigType.AETHER.protocolScheme + AetherFmt.toUri(original)
        val reparsed = AetherFmt.parse(shared)!!

        assertEquals("semi-python", reparsed.aetherFingerprint)
        assertTrue(shared.contains("fingerprint=semi-python"))
    }

    @Test
    fun `an unknown fingerprint reads as chrome and is not written back`() {
        val parsed = AetherFmt.parse("aether://?protocol=masque&fingerprint=opera#x")!!
        assertEquals("chrome", parsed.aetherFingerprint)

        val shared = EConfigType.AETHER.protocolScheme + AetherFmt.toUri(parsed)
        assertTrue(!shared.contains("fingerprint="))
        // And the default never rides a link either.
        val chrome = AetherFmt.parse("aether://?protocol=masque#y")!!
        assertNull(AetherFmt.toUri(chrome).let { uri ->
            Regex("fingerprint=").find(uri)?.value
        })
    }
}
