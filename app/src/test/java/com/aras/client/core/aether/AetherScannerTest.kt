package com.aras.client.core.aether

import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.EConfigType
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

/**
 * A scan is only worth running because it changes the profile, so what matters here is
 * that the gateway the core names on its own output is the one that ends the scan and
 * the one that lands on the profile.
 */
class AetherScannerTest {

    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        // apply() writes to the log: Log is a stub here, and the level it reads comes
        // from MMKV, which has no JVM implementation.
        mockLog = mockStatic(Log::class.java)
        com.aras.client.core.TestSettings.install()
    }

    @After
    fun tearDown() {
        mockLog.close()
    }

    private fun profile(protocol: String) = ProfileItem(
        configType = EConfigType.AETHER,
        aetherProtocol = protocol,
    )

    @Test
    fun `the endpoint the core names ends a wireguard scan`() {
        val found = AetherScanner.matches(
            AetherProtocol.WIREGUARD,
            "[-] selected WireGuard endpoint 162.159.196.1:2408",
            ruled = false,
        )

        assertEquals("162.159.196.1:2408", found?.endpoint?.toString())
        assertNull(found?.innerHop)
    }

    @Test
    fun `the endpoint the core names ends a masque scan`() {
        val found = AetherScanner.matches(
            AetherProtocol.MASQUE,
            "[-] selected MASQUE gateway 198.51.100.7:443",
            ruled = false,
        )

        assertEquals("198.51.100.7:443", found?.endpoint?.toString())
    }

    @Test
    fun `a two hop scan takes both hops`() {
        val gool = AetherScanner.matches(
            AetherProtocol.GOOL,
            "[-] using cloudflare edge 198.51.100.1:2408 (outer) and 198.51.100.2:2408 (inner)",
            ruled = false,
        )
        assertEquals("198.51.100.1:2408", gool?.endpoint?.toString())
        assertEquals("198.51.100.2:2408", gool?.innerHop?.toString())

        val mim = AetherScanner.matches(
            AetherProtocol.MIM,
            "[-] masque-in-masque ready: 198.51.100.3:443 (outer) and 198.51.100.4:443 (inner)",
            ruled = false,
        )
        assertEquals("198.51.100.3:443", mim?.endpoint?.toString())
        assertEquals("198.51.100.4:443", mim?.innerHop?.toString())
    }

    @Test
    fun `with an exit rule a named endpoint is not the answer until the exit is accepted`() {
        val named = AetherScanner.matches(
            AetherProtocol.WIREGUARD,
            "[-] selected WireGuard endpoint 162.159.196.1:2408",
            ruled = true,
        )
        assertNull("an exit the core has not accepted yet is not the answer", named)

        val accepted = AetherScanner.matches(
            AetherProtocol.WIREGUARD,
            "[-] selected WireGuard endpoint 162.159.196.1:2408 exit location DE accepted",
            ruled = true,
        )
        assertEquals("162.159.196.1:2408", accepted?.endpoint?.toString())
    }

    @Test
    fun `a line that is not a result ends nothing`() {
        assertNull(AetherScanner.matches(AetherProtocol.WIREGUARD, "[-] sweeping 162.159.192.0/24", false))
        assertNull(AetherScanner.matches(AetherProtocol.WIREGUARD, "", false))
        // An endpoint the app cannot read is not an answer either.
        assertNull(AetherScanner.matches(AetherProtocol.WIREGUARD, "[-] selected WireGuard endpoint nonsense", false))
    }

    @Test
    fun `a single hop scan writes the address and port`() {
        val config = profile("wg")
        AetherScanner.apply(config, AetherScanResult(AetherEndpoint("198.51.100.7", 2408)))

        assertEquals("198.51.100.7", config.server)
        assertEquals("2408", config.serverPort)
        assertNull(config.aetherWiwOuter)
    }

    @Test
    fun `a two hop scan writes the hops and clears the single address`() {
        val config = profile("gool")
        // A leftover address would be read as the endpoint the core dials.
        config.server = "198.51.100.1"
        config.serverPort = "2408"

        AetherScanner.apply(
            config,
            AetherScanResult(AetherEndpoint("198.51.100.1", 2408), AetherEndpoint("198.51.100.2", 2408)),
        )

        assertEquals("198.51.100.1:2408", config.aetherWiwOuter)
        assertEquals("198.51.100.2:2408", config.aetherWiwInner)
        assertNull(config.server)
        assertNull(config.serverPort)
    }
}
