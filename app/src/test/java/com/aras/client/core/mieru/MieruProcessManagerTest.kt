package com.aras.client.core.mieru

import com.aras.client.core.TestSettings
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration handed to the mieru client.
 *
 * The shape was taken by feeding a configuration to the real client and reading back
 * what it stored, so a field the client does not know is a config it will refuse rather
 * than one that is quietly ignored.
 */
class MieruProcessManagerTest {

    private fun profile(block: ProfileItem.() -> Unit = {}) =
        ProfileItem(
            configType = EConfigType.MIERU,
            server = "198.51.100.9",
            serverPort = "1080",
        ).apply(block)

    @Test
    fun `a configuration names the profile, the endpoint and the socks port`() {
        TestSettings.install()

        val json = MieruProcessManager.buildConfig(
            profile {
                username = "user1"
                password = "pass1"
                mtu = 1400
            },
            socksPort = 10820,
        )

        assertTrue(json.contains(""""profileName": "aras""""))
        assertTrue(json.contains(""""ipAddress": "198.51.100.9""""))
        assertTrue(!json.contains("domainName"))
        assertTrue(json.contains(""""portBindings": [{"port": 1080, "protocol": "TCP"}]"""))
        assertTrue(json.contains(""""socks5Port": 10820"""))
        assertTrue(json.contains(""""activeProfile": "aras""""))
        assertTrue(json.contains(""""mtu": 1400"""))
        assertTrue(json.contains(""""name": "user1""""))
        assertTrue(json.contains(""""password": "pass1""""))
    }

    @Test
    fun `a name is sent as a domain rather than an address`() {
        TestSettings.install()

        val json = MieruProcessManager.buildConfig(
            profile { server = "mi.example.org" },
            socksPort = 10820,
        )

        assertTrue(json.contains(""""domainName": "mi.example.org""""))
        assertTrue(!json.contains("ipAddress"))
    }

    @Test
    fun `a mtu of zero or less falls back to the default`() {
        TestSettings.install()

        val json = MieruProcessManager.buildConfig(profile { mtu = 0 }, socksPort = 10820)

        assertTrue(json.contains(""""mtu": 1360"""))
    }

    @Test
    fun `a quote or newline in a password survives the configuration intact`() {
        TestSettings.install()

        val awkward = "a\"b\\c\nd"
        val json = MieruProcessManager.buildConfig(
            profile { password = awkward },
            socksPort = 10820,
        )

        // The configuration has to parse, and the credential has to come back exactly
        // as it went in - a raw newline would make it invalid and a lost quote would
        // silently sign in as nobody.
        val parsed = com.google.gson.JsonParser.parseString(json).asJsonObject
        val user = parsed.getAsJsonArray("profiles")[0].asJsonObject.getAsJsonObject("user")
        assertEquals(awkward, user.get("password").asString)
        assertEquals(10820, parsed.get("socks5Port").asInt)
    }

    @Test
    fun `the escaping helper leaves ordinary credentials alone`() {
        assertEquals("hunter2", MieruProcessManager.JsonEscape("hunter2"))
        assertEquals("", MieruProcessManager.JsonEscape(""))
    }
}
