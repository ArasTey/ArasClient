package com.aras.client.core

import android.util.Base64
import com.aras.client.dto.entities.ProfileItem

/**
 * Builds the amneziawg-go UAPI set string for a standalone AmneziaWG tunnel,
 * mirroring the AmneziaVPN Android client (WireguardConfig.toWgUserspaceString):
 * device lines (private key + protocol extension jc..h4) first, then
 * replace_peers + peer lines.
 */
object AwgConfigBuilder {

    private fun hex(key: String): String =
        java.util.Base64.getDecoder().decode(key).joinToString("") { "%02x".format(it) }

    private fun intOrDefault(v: String?, default: Int): Int = v?.trim()?.toIntOrNull() ?: default

    fun buildUapi(profile: ProfileItem): String {
        val sb = StringBuilder()

        sb.append("private_key=").append(hex(profile.secretKey.orEmpty())).append("\n")

        // Protocol extension — always applied for AWG profiles. When the
        // config carries no values, AmneziaWG's standard defaults are used.
        val junkDefaults = profile.junkPacketCount.isNullOrBlank() &&
            profile.initPacketJunkHeader.isNullOrBlank()
        sb.append("jc=").append(intOrDefault(profile.junkPacketCount, if (junkDefaults) 4 else 0)).append("\n")
        sb.append("jmin=").append(intOrDefault(profile.junkPacketMinSize, if (junkDefaults) 40 else 0)).append("\n")
        sb.append("jmax=").append(intOrDefault(profile.junkPacketMaxSize, if (junkDefaults) 70 else 0)).append("\n")
        sb.append("s1=").append(intOrDefault(profile.initPacketJunkSize, if (junkDefaults) 15 else 0)).append("\n")
        sb.append("s2=").append(intOrDefault(profile.responsePacketJunkSize, if (junkDefaults) 20 else 0)).append("\n")
        sb.append("h1=").append(headerOrDefault(profile.initPacketJunkHeader, junkDefaults, 1)).append("\n")
        sb.append("h2=").append(headerOrDefault(profile.responsePacketJunkHeader, junkDefaults, 2)).append("\n")
        sb.append("h3=").append(headerOrDefault(profile.cookiePacketJunkHeader, junkDefaults, 3)).append("\n")
        sb.append("h4=").append(headerOrDefault(profile.transportPacketJunkHeader, junkDefaults, 4)).append("\n")

        sb.append("replace_peers=true\n")

        sb.append("public_key=").append(hex(profile.publicKey.orEmpty())).append("\n")
        sb.append("allowed_ip=0.0.0.0/0\n")
        sb.append("allowed_ip=::/0\n")
        sb.append("endpoint=").append(profile.server.orEmpty()).append(":").append(profile.serverPort.orEmpty()).append("\n")
        profile.preSharedKey?.takeIf { it.isNotBlank() }?.let {
            sb.append("preshared_key=").append(hex(it)).append("\n")
        }
        return sb.toString()
    }

    private fun headerOrDefault(v: String?, defaults: Boolean, default: Int): String {
        val trimmed = v?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
        return if (defaults) default.toString() else default.toString()
    }
}
