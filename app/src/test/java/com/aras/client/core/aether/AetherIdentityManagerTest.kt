package com.aras.client.core.aether

import com.aras.client.enums.AetherProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The identity is read out of files the core writes, and the app later deletes or
 * replaces them. What matters is that it reads the device's own keys and not a
 * lookalike nested in a block, and that a reset really leaves nothing behind.
 */
class AetherIdentityManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(name: String, text: String) =
        folder.newFile(name).writeText(text)

    @Test
    fun `the device is read from the top level keys`() {
        write(
            AetherIdentityManager.WIREGUARD_FILE,
            """
            device_id = "e7c31c55-0000-4000-8000-000000000001"
            ipv4 = "172.16.0.2"
            ipv6 = "2606:4700:110:89d7:35a5:be9c:80f5:12a4"
            private_key = "something-secret"
            """.trimIndent(),
        )

        val identity = AetherIdentityManager
            .status(folder.root, AetherProtocol.WIREGUARD).primary

        assertEquals("e7c31c55-0000-4000-8000-000000000001", identity?.deviceId)
        assertEquals("172.16.0.2", identity?.ipv4)
        assertEquals("2606:4700:110:89d7:35a5:be9c:80f5:12a4", identity?.ipv6)
    }

    @Test
    fun `a key of the same name inside a block is not this device's`() {
        // The files nest blocks, and a nested key of the same name belongs to
        // something else. Matching on the trimmed line would take this one.
        write(
            AetherIdentityManager.MASQUE_FILE,
            """
            device_id = "the-real-one"
            ipv4 = "172.16.0.2"

            [some_section]
              device_id = "the-wrong-one"
              ipv4 = "10.0.0.9"
            """.trimIndent(),
        )

        val identity = AetherIdentityManager
            .status(folder.root, AetherProtocol.MASQUE).primary

        assertEquals("the-real-one", identity?.deviceId)
        assertEquals("172.16.0.2", identity?.ipv4)
    }

    @Test
    fun `a file with no device id is no identity`() {
        write(AetherIdentityManager.BASE_FILE, "ipv4 = \"172.16.0.2\"\n")

        assertNull(AetherIdentityManager.status(folder.root, AetherProtocol.WIREGUARD).primary)
    }

    @Test
    fun `no file at all is no identity, and not a failure`() {
        assertNull(AetherIdentityManager.status(folder.root, AetherProtocol.MASQUE).primary)
    }

    @Test
    fun `a two hop profile reports both identities`() {
        // The outer hop is the base tunnel and uses the base identity file; the inner
        // one is the nested masque and has its own.
        write(AetherIdentityManager.BASE_FILE, "device_id = \"outer-one\"\nipv4 = \"172.16.0.2\"\n")
        write(AetherIdentityManager.MASQUE_INNER_FILE, "device_id = \"inner-one\"\nipv4 = \"172.16.0.3\"\n")

        val status = AetherIdentityManager.status(folder.root, AetherProtocol.MIM)

        assertEquals("outer-one", status.primary?.deviceId)
        assertEquals("inner-one", status.secondary?.deviceId)
    }

    @Test
    fun `a masque profile reads the masque identity`() {
        write(AetherIdentityManager.MASQUE_FILE, "device_id = \"masque-one\"\nipv4 = \"172.16.0.4\"\n")
        // The base file is the WireGuard identity and must not be taken for this one.
        write(AetherIdentityManager.BASE_FILE, "device_id = \"wg-one\"\nipv4 = \"172.16.0.5\"\n")

        assertEquals(
            "masque-one",
            AetherIdentityManager.status(folder.root, AetherProtocol.MASQUE).primary?.deviceId,
        )
        assertEquals(
            "wg-one",
            AetherIdentityManager.status(folder.root, AetherProtocol.WIREGUARD).primary?.deviceId,
        )
    }

    @Test
    fun `a hand written command with no arguments is not one we can run`() {
        // It would start a core on the default port, which is the collision the
        // profile's own port exists to avoid.
        assertNull(AetherCore.ofCommand("aether"))
        assertNull(AetherCore.ofCommand(""))
        assertNull(AetherCore.ofCommand("not-a-command"))
        assertEquals(10825, AetherCore.ofCommand("aether --bind 127.0.0.1:10825 --protocol wg")?.port)
    }
}
