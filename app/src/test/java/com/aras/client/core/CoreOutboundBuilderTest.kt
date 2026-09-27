package com.aras.client.core

import android.util.Log
import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.enums.NetworkType
import com.aras.client.handler.MmkvManager
import com.tencent.mmkv.MMKV
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

/**
 * Config-generation tests for the protocols that depend on patches carried by the
 * bundled core rather than on stock Xray-core.
 *
 * The core is a prebuilt native AAR that CI rebuilds from source, so nothing in the
 * unit-test suite can catch a patch silently dropping out of that build. These tests
 * pin the *Kotlin* half of the contract — the JSON handed to the core — so a
 * regression there is caught by `testDebugUnitTest` rather than on a user's device.
 */
class CoreOutboundBuilderTest {

    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java)
        injectSettingsStorage()
    }

    @After
    fun tearDown() {
        mockLog.close()
    }

    /**
     * MMKV is a native library with no JVM implementation, so [MmkvManager] reads null
     * in unit tests and every settings lookup NPEs. MmkvManager is a Kotlin `object`,
     * so `mockStatic` cannot intercept it either — instead, force the `by lazy`
     * delegate to hand out a mocked MMKV. Unstubbed reads return the type default,
     * which is what the production code already assumes for "unset".
     */
    private fun injectSettingsStorage() {
        val delegateField = MmkvManager::class.java.getDeclaredField("settingsStorage\$delegate")
        delegateField.isAccessible = true
        val lazy = delegateField.get(MmkvManager)

        // SynchronizedLazyImpl reads _value on every getValue() and only calls the
        // initializer while it holds the uninitialized sentinel, so assigning here
        // is enough — no separate initialized flag to set.
        lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }
            .set(lazy, mock(MMKV::class.java))
    }

    // An IP address keeps getServerAddress() off HttpUtil's resolver, so these stay
    // pure JVM tests with no Android or network dependency.
    private fun profile(type: EConfigType) = ProfileItem(
        configType = type,
        remarks = "test",
        server = "1.2.3.4",
        serverPort = "443",
    )

    @Test
    fun `anytls outbound carries its password on both settings and transport`() {
        val item = profile(EConfigType.ANYTLS).apply { password = "s3cret" }

        val outbound = CoreOutboundBuilder.convert(item)

        assertNotNull(outbound)
        assertEquals("anytls", outbound!!.protocol)
        assertEquals("s3cret", outbound.settings?.password)
        assertEquals(NetworkType.ANYTLS.type, outbound.streamSettings?.network)
        // The anytls transport authenticates with the password carried in anytlsSettings.
        assertEquals("s3cret", outbound.streamSettings?.anytlsSettings?.password)
        // AnyTLS is always TLS-wrapped; the builder defaults it when unset.
        assertEquals(AppConfig.TLS, outbound.streamSettings?.security)
    }

    @Test
    fun `anytls forces multiplex off because the transport multiplexes internally`() {
        val outbound = CoreOutboundBuilder.convert(
            profile(EConfigType.ANYTLS).apply { password = "s3cret" }
        )

        assertEquals(false, outbound?.mux?.enabled)
    }

    @Test
    fun `amneziawg reuses the wireguard protocol with the userspace tunnel`() {
        val item = profile(EConfigType.AMNEZIAWG).apply {
            publicKey = "pubkey"
            secretKey = "secretkey"
            localAddress = "10.0.0.2/32"
        }

        val outbound = CoreOutboundBuilder.convert(item)

        assertNotNull(outbound)
        // AmneziaWG shares the plain WireGuard outbound; only the peer differs.
        assertEquals("wireguard", outbound!!.protocol)
        // Kernel TUN cannot apply junk packets, so the userspace device is mandatory.
        assertEquals(true, outbound.settings?.noKernelTun)
        // Cloudflare WARP endpoints drop IPv6 handshakes; AWG binds v4 only.
        assertEquals("forceIPv4", outbound.settings?.domainStrategy)
    }

    @Test
    fun `amneziawg applies obfuscation defaults to profiles saved before the params existed`() {
        val item = profile(EConfigType.AMNEZIAWG).apply {
            publicKey = "pubkey"
            secretKey = "secretkey"
        }

        val peer = CoreOutboundBuilder.convert(item)?.settings?.peers?.firstOrNull()

        assertNotNull(peer)
        assertEquals(4, peer!!.junkPacketCount)
        assertEquals(40, peer.junkPacketMinSize)
        assertEquals(70, peer.junkPacketMaxSize)
        assertEquals(15, peer.initPacketJunkSize)
        assertEquals(20, peer.responsePacketJunkSize)
        assertEquals(listOf(1), peer.initPacketJunkHeader)
        assertEquals(listOf(2), peer.responsePacketJunkHeader)
        assertEquals(listOf(3), peer.cookiePacketJunkHeader)
        assertEquals(listOf(4), peer.transportPacketJunkHeader)
    }

    @Test
    fun `amneziawg keeps explicitly configured obfuscation params`() {
        val item = profile(EConfigType.AMNEZIAWG).apply {
            publicKey = "pubkey"
            secretKey = "secretkey"
            junkPacketCount = "9"
            junkPacketMinSize = "11"
            initPacketJunkHeader = "7,8,9"
        }

        val peer = CoreOutboundBuilder.convert(item)?.settings?.peers?.firstOrNull()

        assertEquals(9, peer?.junkPacketCount)
        assertEquals(11, peer?.junkPacketMinSize)
        assertEquals(listOf(7, 8, 9), peer?.initPacketJunkHeader)
    }

    @Test
    fun `amneziawg drops ipv6 local addresses so the tunnel binds v4 only`() {
        val item = profile(EConfigType.AMNEZIAWG).apply {
            publicKey = "pubkey"
            secretKey = "secretkey"
            localAddress = "10.0.0.2/32,fd00::2/128"
        }

        val address = CoreOutboundBuilder.convert(item)?.settings?.address as? List<*>

        assertNotNull("expected a list of local addresses", address)
        assertTrue("expected only IPv4 addresses, got $address",
            requireNotNull(address).all { entry -> (entry as? String).orEmpty().none { it == ':' } })
    }

    @Test
    fun `reality populates realitySettings and leaves tlsSettings unset`() {
        val item = profile(EConfigType.VLESS).apply {
            security = AppConfig.REALITY
            publicKey = "pbk"
            shortId = "abcd"
            spiderX = "/"
            network = NetworkType.TCP.type
        }

        val stream = CoreOutboundBuilder.convert(item)?.streamSettings

        assertEquals(AppConfig.REALITY, stream?.security)
        assertNotNull("realitySettings must be set", stream?.realitySettings)
        assertNull("tlsSettings must be unset for reality", stream?.tlsSettings)
    }

    @Test
    fun `tls populates tlsSettings and leaves realitySettings unset`() {
        val item = profile(EConfigType.TROJAN).apply {
            password = "pw"
            security = AppConfig.TLS
            network = NetworkType.TCP.type
        }

        val stream = CoreOutboundBuilder.convert(item)?.streamSettings

        assertEquals(AppConfig.TLS, stream?.security)
        assertNotNull(stream?.tlsSettings)
        assertNull(stream?.realitySettings)
    }

    @Test
    fun `masque outbound uses the masque transport over tls`() {
        val item = profile(EConfigType.MASQUE).apply {
            host = "example.org"
            path = "/connect/{target}"
        }

        val outbound = CoreOutboundBuilder.convert(item)

        assertNotNull(outbound)
        assertEquals("masque", outbound!!.protocol)
        assertEquals(NetworkType.MASQUE.type, outbound.streamSettings?.network)
        // MASQUE is CONNECT-IP inside an HTTP/3 request — there is no plaintext mode.
        assertEquals(AppConfig.TLS, outbound.streamSettings?.security)
        assertNotNull(outbound.streamSettings?.tlsSettings)
        assertEquals("example.org", outbound.streamSettings?.masqueSettings?.host)
        assertEquals("/connect/{target}", outbound.streamSettings?.masqueSettings?.path)
    }

    @Test
    fun `masque forces multiplex off`() {
        val outbound = CoreOutboundBuilder.convert(profile(EConfigType.MASQUE))

        assertEquals(false, outbound?.mux?.enabled)
    }

    @Test
    fun `masque carries the resolver list from the profile`() {
        val item = profile(EConfigType.MASQUE).apply { remoteDNS = "1.1.1.1, 8.8.8.8" }

        val remoteDNS = CoreOutboundBuilder.convert(item)?.settings?.remoteDNS

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), remoteDNS)
    }

    @Test
    fun `xdrive transport carries its service and folder onto any protocol`() {
        val item = profile(EConfigType.VLESS).apply {
            network = NetworkType.XDRIVE.type
            security = AppConfig.TLS
            xdriveService = "Google Drive"
            xdriveRemoteFolder = "tunnels"
            xdriveSecrets = "secret-a,secret-b"
        }

        val xdrive = CoreOutboundBuilder.convert(item)?.streamSettings?.xdriveSettings

        assertNotNull(xdrive)
        assertEquals("Google Drive", xdrive!!.service)
        assertEquals("tunnels", xdrive.remoteFolder)
        assertEquals(listOf("secret-a", "secret-b"), xdrive.secrets)
    }

    @Test
    fun `a profile with a non-numeric port is skipped instead of throwing`() {
        // convertProfile2Outbound only guards a null return, so a thrown
        // NumberFormatException here would abort the whole config build.
        val item = profile(EConfigType.VLESS).apply {
            serverPort = "not-a-port"
            security = AppConfig.TLS
            network = NetworkType.TCP.type
        }

        assertNull(CoreOutboundBuilder.convert(item))
    }

    @Test
    fun `a profile with an out of range port is skipped`() {
        val item = profile(EConfigType.TROJAN).apply {
            serverPort = "70000"
            password = "pw"
            security = AppConfig.TLS
        }

        assertNull(CoreOutboundBuilder.convert(item))
    }

    @Test
    fun `a protocol with no builder yields no outbound instead of a broken one`() {
        // Policies and proxy chains are resolved elsewhere; the builder must decline
        // them rather than emit a half-populated outbound the core would reject.
        assertNull(CoreOutboundBuilder.convert(profile(EConfigType.POLICYGROUP)))
    }
}
