package com.aras.client.ui.main

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolDescriptionTest {

    private fun description(profile: ProfileItem) = protocolDescription(profile, "only")

    @Test
    fun aetherCardNamesProtocolAndExit() {
        val profile = ProfileItem(
            configType = EConfigType.AETHER,
            aetherProtocol = "masque",
            aetherExitNode = "PSIPHON"
        )
        assertEquals("AETHER / MASQUE → PSIPHON", description(profile))
    }

    @Test
    fun aetherProtocolFallsBackToWireGuardWhenUnknown() {
        val profile = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "quic")
        assertEquals("AETHER / WIREGUARD", description(profile))
    }

    @Test
    fun aetherWithoutAProtocolOrExitStaysShort() {
        val noProtocol = ProfileItem(configType = EConfigType.AETHER, aetherExitNode = " TOR ")
        assertEquals("AETHER → TOR", description(noProtocol))
        val noExit = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "gool", aetherExitNode = "  ")
        assertEquals("AETHER / GOOL", description(noExit))
    }

    @Test
    fun aetherNamesTheCarrierInItsPlaceInThePath() {
        val around = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "masque", aetherPsiphon = "reverse")
        assertEquals("PSIPHON > AETHER / MASQUE", description(around))
        val inside = ProfileItem(configType = EConfigType.AETHER, aetherProtocol = "masque", aetherTor = "chain")
        assertEquals("AETHER / MASQUE > TOR", description(inside))
        val alone = ProfileItem(configType = EConfigType.AETHER, aetherPsiphon = "only")
        assertEquals("PSIPHON > only", description(alone))
    }

    @Test
    fun plainProtocolsSkipTheDefaultNetwork() {
        val vless = ProfileItem(
            configType = EConfigType.VLESS,
            network = "ws",
            security = "tls"
        )
        assertEquals("VLESS / ws / tls", description(vless))
        val defaultNetwork = vless.copy(network = "tcp")
        assertEquals("VLESS / tls", description(defaultNetwork))
        val insecure = vless.copy(insecure = true)
        assertEquals("VLESS / ws / tls insecure", description(insecure))
    }

    @Test
    fun complexTypesShowOnlyTheirName() {
        val custom = ProfileItem(configType = EConfigType.CUSTOM, network = "ws", security = "tls")
        assertEquals("CUSTOM", description(custom))
        val chain = ProfileItem(configType = EConfigType.PROXYCHAIN)
        assertEquals("PROXYCHAIN", description(chain))
    }
}
