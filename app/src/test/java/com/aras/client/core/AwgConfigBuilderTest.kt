package com.aras.client.core

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgConfigBuilderTest {

    private fun userConfig(): ProfileItem {
        val config = ProfileItem.create(EConfigType.AMNEZIAWG)
        config.remarks = "1788707473291"
        config.secretKey = "cHfvEYAOf+ZFEizLKhyJEDGhY9egzPxwIwlcb2ThTEo="
        config.publicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        config.server = "8.6.112.148"
        config.serverPort = "891"
        config.mtu = 1280
        config.junkPacketCount = "5"
        config.junkPacketMinSize = "10"
        config.junkPacketMaxSize = "40"
        config.initPacketJunkSize = "0"
        config.responsePacketJunkSize = "0"
        config.initPacketJunkHeader = "1"
        config.responsePacketJunkHeader = "2"
        config.cookiePacketJunkHeader = "3"
        config.transportPacketJunkHeader = "4"
        return config
    }

    @Test
    fun `user config produces device-then-peer UAPI with exact junk values`() {
        val uapi = AwgConfigBuilder.buildUapi(userConfig())

        val deviceLines = uapi.substringBefore("replace_peers")
        assertTrue(deviceLines.contains("jc=5\n"))
        assertTrue(deviceLines.contains("jmin=10\n"))
        assertTrue(deviceLines.contains("jmax=40\n"))
        assertTrue(deviceLines.contains("s1=0\n"))
        assertTrue(deviceLines.contains("s2=0\n"))
        assertTrue(deviceLines.contains("h1=1\n"))
        assertTrue(deviceLines.contains("h2=2\n"))
        assertTrue(deviceLines.contains("h3=3\n"))
        assertTrue(deviceLines.contains("h4=4\n"))
        // device junk lines must all appear BEFORE the peer section
        assertTrue(uapi.indexOf("jc=5") < uapi.indexOf("public_key="))
    }

    @Test
    fun `keys are hex encoded`() {
        val uapi = AwgConfigBuilder.buildUapi(userConfig())
        val privHex = "7077ef11800e7f9191 2cab724242 4c4189c477a1b532 613f8c2171a6dd34f7994a" // shape only
        // hex strings contain only hex chars and are 64 chars long
        val privLine = uapi.lineSequence().first { it.startsWith("private_key=") }
        val pubLine = uapi.lineSequence().first { it.startsWith("public_key=") }
        assertEquals(64, privLine.removePrefix("private_key=").length)
        assertEquals(64, pubLine.removePrefix("public_key=").length)
        assertTrue(privLine.removePrefix("private_key=").all { it.isDigit() || it in 'a'..'f' })
        assertTrue(pubLine.removePrefix("public_key=").all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `endpoint and peer lines present`() {
        val uapi = AwgConfigBuilder.buildUapi(userConfig())
        assertTrue(uapi.contains("endpoint=8.6.112.148:891\n"))
        assertTrue(uapi.contains("allowed_ip=0.0.0.0/0\n"))
        assertTrue(uapi.contains("replace_peers=true\n"))
        assertTrue(uapi.indexOf("replace_peers=true") < uapi.indexOf("public_key="))
    }
}
