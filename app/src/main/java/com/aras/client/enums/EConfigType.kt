package com.aras.client.enums

import com.aras.client.AppConfig

enum class EConfigType(val value: Int, val protocolScheme: String) {
    VMESS(1, AppConfig.VMESS),
    CUSTOM(2, AppConfig.CUSTOM),
    SHADOWSOCKS(3, AppConfig.SHADOWSOCKS),
    SOCKS(4, AppConfig.SOCKS),
    VLESS(5, AppConfig.VLESS),
    TROJAN(6, AppConfig.TROJAN),
    WIREGUARD(7, AppConfig.WIREGUARD),

    // TUIC stays disabled: the bundled core has no TUIC implementation at all, so
    // the enum entry would produce config the core rejects. Re-enable only together
    // with a core port.
    //    TUIC(8, AppConfig.TUIC),
    HYSTERIA2(9, AppConfig.HYSTERIA2),
    HYSTERIA(900, AppConfig.HYSTERIA),
    HTTP(10, AppConfig.HTTP),
    ANYTLS(11, AppConfig.ANYTLS),
    AMNEZIAWG(12, AppConfig.AMNEZIAWG),
    // MASQUE (IETF CONNECT-IP, RFC 9484). Import-only: no manual-add editor.
    MASQUE(13, AppConfig.MASQUE),
    // Aether/gateway profile. Added from the import menu, directly below the .arasc
    // entry, rather than from the "Add manually" protocol submenu.
    AETHER(14, AppConfig.AETHER),
    // Mieru: a separate client process serving a local SOCKS, like Aether.
    MIERU(15, AppConfig.MIERU),
    POLICYGROUP(101, AppConfig.CUSTOM),
    PROXYCHAIN(102, AppConfig.CUSTOM);

    companion object {
        /**
         * Protocols that can only be created by importing a link or a .arasc file —
         * they deliberately have no "Add manually" entry and no server editor screen.
         *
         * They are hidden from the editor because the generic fallbacks would be
         * wrong for them: the HTTP editor would rewrite the profile as an HTTP proxy,
         * and the raw-config editor would rewrite it as CUSTOM.
         */
        val IMPORT_ONLY = setOf(MASQUE)

        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }

    /** True when a dedicated server editor exists for this protocol. */
    val hasEditor: Boolean get() = this !in IMPORT_ONLY
}