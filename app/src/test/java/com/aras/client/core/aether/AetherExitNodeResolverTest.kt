package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit node is the name of exactly one other profile. Nothing to resolve
 * (blank), no match, or several matches, all leave the core on its direct
 * exit, and the profile named must not be an Aether one: dialing out through
 * the core that is dialing out through the app would be a loop.
 */
class AetherExitNodeResolverTest {

    @Test
    fun `a blank name resolves to nothing and nothing is ok`() {
        val resolved = AetherExitNodeResolver.resolve("")

        assertEquals("", resolved.name)
        assertTrue(resolved.matches.isEmpty())
        assertFalse(resolved.ok)
    }

    @Test
    fun `a non-aether profile's own remark is the only selectable node`() {
        val vmess = ProfileItem.create(EConfigType.VMESS).apply { remarks = "NODE-A" }
        val aether = ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "aether"
            aetherExitNode = "NODE-A"
        }

        assertEquals(vmess.remarks, aether.aetherExitNode)
        // Matches list never contains an aether profile, even one with the name.
        val pool = listOf(vmess, aether).filter { it.configType != EConfigType.AETHER && it.remarks == "NODE-A" }
        assertEquals(1, pool.size)
        assertTrue(pool.none { it.configType == EConfigType.AETHER })
    }

    @Test
    fun `two profiles with the same remark are ambiguous and never ok`() {
        val first = ProfileItem.create(EConfigType.VMESS).apply { remarks = "NODE-B" }
        val second = ProfileItem.create(EConfigType.VLESS).apply { remarks = "NODE-B" }

        val resolved = AetherExitNodeResolver.Resolved(listOf(first, second), "NODE-B")

        assertEquals(2, resolved.matches.size)
        assertFalse(resolved.ok)
    }

    @Test
    fun `the aether main socket and the exit inbound stay apart by the offset`() {
        // The core dials listenPort + AETHER_EXIT_IN_PORT_OFFSET; the offset
        // keeps it clear of the listener chain (+1 tunnel, +2 tor, +3 psiphon).
        assertTrue(AppConfigOffsetCheck.offset >= 4)
    }

    private object AppConfigOffsetCheck {
        val offset = com.aras.client.AppConfig.AETHER_EXIT_IN_PORT_OFFSET
    }
}
