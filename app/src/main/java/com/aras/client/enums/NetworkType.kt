package com.aras.client.enums

enum class NetworkType(val type: String) {
    TCP("tcp"),
    KCP("kcp"),
    WS("ws"),
    HTTP_UPGRADE("httpupgrade"),
    XHTTP("xhttp"),
    HTTP("http"),
    H2("h2"),

    //QUIC("quic"),
    GRPC("grpc"),
    HYSTERIA("hysteria"),
    ANYTLS("anytls"),
    /** MASQUE transport (IETF CONNECT-IP). See also EConfigType.MASQUE, the protocol. */
    MASQUE("masque"),
    /** XDRIVE transport: tunnels through remote storage, ignoring IP allowlists. */
    XDRIVE("xdrive");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: TCP
    }
}
