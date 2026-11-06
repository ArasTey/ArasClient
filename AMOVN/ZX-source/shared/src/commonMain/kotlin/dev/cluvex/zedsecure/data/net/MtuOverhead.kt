package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.domain.config.Protocol

data class MtuOverhead(val total: Int, val parts: List<Part>) {
    data class Part(val name: String, val bytes: Int)

    fun explain(): String =
        parts.joinToString(" + ") { "${it.name} ${it.bytes}" } + " = $total bytes"
}

object MtuOverheads {
    private const val IPV4 = 20
    private const val IPV6 = 40
    private const val UDP = 8

    private const val TCP = 32

    private const val TLS = 21

    private const val WEBSOCKET = 8

    private const val GRPC = 14

    private const val XHTTP = 9

    private const val QUIC = 29

    private const val KCP = 24

    private const val WIREGUARD = 32

    private const val SHADOWSOCKS = 34

    private const val VMESS_CHUNK = 18

    fun of(
        protocol: Protocol,
        network: String = "tcp",
        tls: Boolean = true,
        ipv6: Boolean = false,
    ): MtuOverhead {
        val parts = mutableListOf<MtuOverhead.Part>()
        parts += MtuOverhead.Part(if (ipv6) "IPv6" else "IPv4", if (ipv6) IPV6 else IPV4)

        when (protocol) {
            Protocol.WIREGUARD, Protocol.AMNEZIAWG -> {
                parts += MtuOverhead.Part("UDP", UDP)
                parts += MtuOverhead.Part("WireGuard", WIREGUARD)
            }

            Protocol.HYSTERIA -> {
                parts += MtuOverhead.Part("UDP", UDP)
                parts += MtuOverhead.Part("QUIC", QUIC)
            }

            else -> {
                val net = network.lowercase()
                if (net == "quic") {
                    parts += MtuOverhead.Part("UDP", UDP)
                    parts += MtuOverhead.Part("QUIC", QUIC)
                } else if (net == "kcp" || net == "mkcp") {
                    parts += MtuOverhead.Part("UDP", UDP)
                    parts += MtuOverhead.Part("mKCP", KCP)
                } else {
                    parts += MtuOverhead.Part("TCP", TCP)
                    if (tls) parts += MtuOverhead.Part("TLS", TLS)
                    when (net) {
                        "ws", "websocket", "httpupgrade" -> parts += MtuOverhead.Part("WebSocket", WEBSOCKET)
                        "grpc", "gun" -> parts += MtuOverhead.Part("gRPC", GRPC)
                        "xhttp", "splithttp" -> parts += MtuOverhead.Part("XHTTP", XHTTP)
                    }
                }
                when (protocol) {
                    Protocol.SHADOWSOCKS -> parts += MtuOverhead.Part("Shadowsocks", SHADOWSOCKS)
                    Protocol.VMESS -> parts += MtuOverhead.Part("VMess", VMESS_CHUNK)
                    else -> Unit
                }
            }
        }
        return MtuOverhead(parts.sumOf { it.bytes }, parts)
    }

    fun worstCase(ipv6: Boolean = false): MtuOverhead =
        of(Protocol.VMESS, network = "ws", tls = true, ipv6 = ipv6)

    fun ofServer(
        config: dev.cluvex.zedsecure.domain.config.ServerConfig,
        ipv6: Boolean = false,
    ): MtuOverhead = of(
        protocol = config.protocol,
        network = config.transport.network,

        tls = config.tls.security.isNotBlank() && !config.tls.security.equals("none", ignoreCase = true),

        ipv6 = ipv6 || config.address.count { it == ':' } > 1,
    )
}

data class MtuServerHint(val host: String, val overhead: MtuOverhead)
