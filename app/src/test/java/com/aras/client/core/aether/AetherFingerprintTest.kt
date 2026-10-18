package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherFingerprint
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.AetherFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TLS handshake fingerprint (PattNG's four choices): its TLS 1.2 suites reach
 * the core as --tls-ciphers, GREASE is left out where the captured client sends
 * none, and the value survives the aether:// link round trip the way PattNG's
 * links write it.
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
            listOf("chrome", "firefox", "semi-python", "go"),
            AetherFingerprint.entries.map { it.type },
        )
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
