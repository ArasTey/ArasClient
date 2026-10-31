package com.aras.client.ui.main

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolDescriptionTest {
    @Test
    fun aetherCardNamesProtocolAndExit() {
        val profile = ProfileItem(
            configType = EConfigType.AETHER,
            aetherProtocol = "masque",
            aetherExitNode = "PSIPHON"
        )
        assertEquals("AETHER / MASQUE → PSIPHON", getProtocolDescription(profile))
    }

    @Test
    fun aetherProtocolFallsBackToWireGuardWhenUnknown() {
        val profile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "quic")
        assertEquals("AETHER / WIREGUARD", getProtocolDescription(profile))
    }

    @Test
    fun aetherWithoutAProtocolOrExitStaysShort() {
        val noProtocol = ProfileItem(configType = EConfigType.AETHER, aetherExitNode = " TOR ")
        assertEquals("AETHER → TOR", getProtocolDescription(noProtocol))
        val noExit = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "gool", aetherExitNode = "  ")
        assertEquals("AETHER / GOOL", getProtocolDescription(noExit))
    }

    @Test
    fun plainProtocolsSkipTheDefaultNetwork() {
        val vless = ProfileItem(
            configType = EConfigType.VLESS,
            network = "ws",
            security = "tls"
        )
        assertEquals("VLESS / ws / tls", getProtocolDescription(vless))
        val defaultNetwork = vless.copy(network = "tcp")
        assertEquals("VLESS / tls", getProtocolDescription(defaultNetwork))
        val insecure = vless.copy(insecure = true)
        assertEquals("VLESS / ws / tls insecure", getProtocolDescription(insecure))
    }

    @Test
    fun complexTypesShowOnlyTheirName() {
        val custom = ProfileItem(configType = EConfigType.CUSTOM, network = "ws", security = "tls")
        assertEquals("CUSTOM", getProtocolDescription(custom))
        val chain = ProfileItem(configType = EConfigType.PROXYCHAIN)
        assertEquals("PROXYCHAIN", getProtocolDescription(chain))
    }
}
