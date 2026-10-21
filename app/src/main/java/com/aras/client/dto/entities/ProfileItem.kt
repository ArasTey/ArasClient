package com.aras.client.dto.entities

import com.aras.client.AppConfig
import com.aras.client.enums.EConfigType
import com.aras.client.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var cipherSuites: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var echOutbound: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    var keepAlive: String? = null,

    // AmneziaWG obfuscation parameters.
    var junkPacketCount: String? = null,
    var junkPacketMinSize: String? = null,
    var junkPacketMaxSize: String? = null,
    var initPacketJunkSize: String? = null,
    var responsePacketJunkSize: String? = null,
    var initPacketJunkHeader: String? = null,
    var responsePacketJunkHeader: String? = null,
    var cookiePacketJunkHeader: String? = null,
    var transportPacketJunkHeader: String? = null,
    // Mimic packets I1..I5, one obfuscation spec per line. Blank means none.
    var mimicPackets: String? = null,

    // DNS tunnel: the engine, the tunnel zone and its key, the resolvers the
    // engine asks, and the ports it serves its SOCKS5 and its own DNS on.
    var dnsTunnelEngine: String? = null,
    var dnsTunnelDomain: String? = null,
    var dnsTunnelKey: String? = null,
    var dnsTunnelMethod: Int? = null,
    var dnsTunnelResolvers: String? = null,
    var dnsTunnelPort: String? = null,
    var dnsTunnelDnsPort: String? = null,
    // The profile whose traffic the tunnel carries, by name.
    var dnsTunnelTarget: String? = null,
    // Extra engine settings, as the engine's own TOML keys.
    var dnsTunnelExtra: String? = null,
    // The country the tunnel is used from, for the engines that discover their
    // own resolvers by it.
    var dnsTunnelCountry: String? = null,
    // The engine's own preset, for the engines that have presets.
    var dnsTunnelPreset: String? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,

    // MASQUE: the server-side resolver list the proxy should use, comma separated.
    var remoteDNS: String? = null,

    // XDRIVE transport.
    var xdriveService: String? = null,
    var xdriveRemoteFolder: String? = null,
    var xdriveSecrets: String? = null,
    /** Raw JSON for any further xdriveSettings keys not modelled individually. */
    var xdriveExtra: String? = null,

    // Aether / gateway profile. Field names match PattNG's so a .arasc file moves
    // between the two clients unchanged; values are the ones aether itself uses
    // (protocol "wg" not "wireguard", ip "v6" not "ipv6", obfuscation under "noize").
    var aetherProtocol: String? = null,
    var aetherTransport: String? = null,
    var aetherFingerprint: String? = null,
    var aetherScanMode: String? = null,
    var aetherObfuscation: String? = null,
    var aetherIpVersion: String? = null,
    /** Two-hop tunnels (gool, mim) name their outer and inner gateway instead of one endpoint. */
    var aetherWiwOuter: String? = null,
    var aetherWiwInner: String? = null,
    var aetherFragment: Boolean? = null,
    var aetherFragmentSize: String? = null,
    var aetherFragmentDelay: String? = null,
    var aetherEch: Boolean? = null,
    /** The name of the one other profile the aether core dials out through; blank = direct exit. */
    var aetherExitNode: String? = null,
    /** The resolver --ech auto asks for the ECH key (aether 2.3). */
    var aetherEchDns: String? = null,
    /** The domain whose key --ech auto takes (aether 2.3, default cloudflare-ech.com). */
    var aetherEchDomain: String? = null,
    /** The server name the MASQUE handshakes put in their ClientHello (aether 2.3). */
    var aetherMasqueSni: String? = null,
    var aetherDns: String? = null,
    var aetherExitLoc: String? = null,
    var aetherListenPort: String? = null,
    var aetherPsiphon: String? = null,
    var aetherPsiphonMode: String? = null,
    var aetherPsiphonCdnIps: String? = null,
    var aetherPsiphonCdnSni: String? = null,
    var aetherPsiphonCdnSets: String? = null,
    var aetherPsiphonRegion: String? = null,
    var aetherPsiphonBundledList: Boolean? = null,
    var aetherTor: String? = null,
    var aetherTorBridges: String? = null,
    /** The profile's own bridge lines, one per line as torrc writes them. */
    var aetherTorBridgeLines: String? = null,
    var aetherTorRelays: String? = null,

    // Mieru: the loopback port its client listens on, when not the default.
    var mieruListenPort: String? = null,
    /** A command line used in place of the settings. */
    var aetherCommand: String? = null,
    @Deprecated("Use pinnedCA256")
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var policyGroupTestOutbounds: Boolean? = null,
    var policyGroupFallbackTag: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,
) {

    companion object {
        fun create(configType: EConfigType): ProfileItem =
            ProfileItem(configType = configType)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "${AppConfig.LOOPBACK}:${AppConfig.PORT_SOCKS}"
        }
        return "${Utils.getIpv6Address(server)}:$serverPort"
    }

    /**
     * Dedicated identity for "remove duplicate configurations".
     *
     * Ignores metadata that does not affect connection:
     * - configVersion
     * - subscriptionId
     * - addedTime
     * - remarks
     * - description
     *
     * All other fields, including configType, are included in the comparison.
     *
     * Returns a copy; the caller must not modify it further.
     */
    fun duplicateIdentity(): ProfileItem =
        copy(
            configVersion = 0,
            subscriptionId = "",
            addedTime = 0L,
            remarks = "",
            description = null
        )
}
