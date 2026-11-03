package com.aras.client.enums

/**
 * The tunnel an Aether profile dials, named the way the aether core names it.
 *
 * The wire values are the ones aether share links and the Aether app itself use —
 * `wg`, not `wireguard` — so links move between ArasClient and PattNG unchanged.
 */
enum class AetherProtocol(val type: String) {
    MASQUE("masque"),
    WIREGUARD("wg"),
    GOOL("gool"),
    MIM("mim");

    /** Whether MASQUE carries the tunnel, which then uses the MASQUE transport, fragmentation and key. */
    val overMasque: Boolean get() = this == MASQUE || this == MIM

    /** The tunnel as a generated profile name spells it: the core's word, but short. */
    val shortName: String get() = if (this == MASQUE) "masq" else type

    /** Whether the tunnel is two hops, an outer and an inner one, in place of one endpoint. */
    val twoHops: Boolean get() = this == GOOL || this == MIM

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: WIREGUARD
    }
}

/** The HTTP version MASQUE is carried over. */
enum class AetherTransport(val type: String) {
    HTTP3("h3"),
    HTTP2("h2");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: HTTP2
    }
}

internal const val CHROME_EIGHT_SIGALGS = "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:" +
    "ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha512"

/**
 * The trust anchors Chrome's ClientHello of October 2026 asks for, the 28 IDs of its trust_anchors (0xca34) in dotted
 * decimal, in its order.
 */
internal const val CHROME_TRUST_ANCHORS = "44947.2.1:44947.2.6:44947.2.13:44947.2.14:44947.2.15:44947.2.18:44947.2.19:" +
    "44947.2.20:52580.200109.1.7:52580.200109.1.8:52580.200109.1.9:52580.200109.1.10:52580.200109.1.11:" +
    "52580.200109.1.12:52580.200109.1.13:52580.200109.1.18:52580.200109.1.19:11129.9.1:11129.9.4:11129.9.5:" +
    "11129.9.6:11129.9.7:11129.9.8:11129.9.10:11129.9.11:11129.9.12:11129.9.13:11129.9.15"

/**
 * The ClientHello of the TLS handshakes of the core, after a client whose ClientHello was captured: its TLS 1.2 suites
 * as BoringSSL names them, in its order, or Chrome's own rule; its TLS 1.3 suites in its order, where that order is not
 * BoringSSL's; its groups in its order, those BoringSSL has, and its key shares, where they are not BoringSSL's; its
 * signature algorithms, those BoringSSL has; the extensions it sends that BoringSSL can send, and only those: GREASE
 * values, GREASE among the signature algorithms, certificate compression, ALPS, GREASE ECH, trust anchors, server
 * padding, OCSP, signed certificate timestamps and a session ticket; and whether its extensions go in a new order on
 * each handshake. BoringSSL writes the TLS 1.3 suites before the TLS 1.2 ones, whatever the client does. Unless they
 * are named, it orders the TLS 1.3 suites by the phone's AES instructions, as Chrome does, AES-GCM first with them and
 * ChaCha20 first without, and it sends key shares for the first group and for the first one of the other kind,
 * post-quantum or not. It cannot send delegated_credentials, record_size_limit, signature_algorithms_cert,
 * encrypt_then_mac or more than one point format, always sends psk_key_exchange_modes, and keeps an order of the
 * extensions of its own.
 *
 * Each names its suites, its groups and its signature algorithms, so that it stays the client's whatever the core's
 * defaults become; what the core sends unless told otherwise (GREASE, OCSP, signed certificate timestamps, the session
 * ticket, the new order on each handshake) a fingerprint without it turns off, and what the core leaves out unless
 * told otherwise a fingerprint with it turns on. Aether 2.4.0 takes every option; --tls13-ciphers, --tls-key-shares
 * and --enable-grease-sigalgs from 2.3.8 on, the rest from 2.3.10 on.
 */
enum class AetherFingerprint(
    val type: String,
    val ciphers: String,
    val grease: Boolean,
    val groups: String,
    /** The signature algorithms, in order, by the names BoringSSL gives them, for --tls-sigalgs. */
    val sigalgs: String,
    val tls13Ciphers: String? = null,
    val keyShares: String? = null,
    val greaseSigalgs: Boolean = false,
    /** The certificate compression offered, of zlib, brotli and zstd, in order, for --tls-cert-compression. */
    val certCompression: String? = null,
    /** The ALPS codepoint, new (0x44cd) or old (0x4469), for --tls-alps; ALPS goes for HTTP/2 and HTTP/3. */
    val alps: String? = null,
    /** The trust anchor IDs asked for, in dotted decimal, for --tls-trust-anchors. */
    val trustAnchors: String? = null,
    /** The bytes of padding asked of the server, for --tls-server-padding. */
    val serverPadding: Int? = null,
    /** A GREASE ECH when no ECH key is offered, --enable-ech-grease. */
    val echGrease: Boolean = false,
    /** OCSP asked for; without it, --disable-ocsp-stapling. */
    val ocspStapling: Boolean = true,
    /** Signed certificate timestamps asked for; without them, --disable-sct. */
    val sct: Boolean = true,
    /** A session_ticket extension; without it, --disable-session-ticket. */
    val sessionTicket: Boolean = true,
    /** The extensions in a new order on each handshake; without it, --disable-extension-permutation. */
    val permuteExtensions: Boolean = true,
) {
    /**
     * Chrome's own rule rather than a list, which BoringSSL orders as it does for Chrome, by the phone's AES
     * instructions as the TLS 1.3 suites: c02b c02f c02c c030 cca9 cca8 c013 c014 009c 009d 002f 0035 with them,
     * ChaCha20 first without. A fixed list would keep AES-GCM first there while the TLS 1.3 suites go ChaCha20 first, a
     * ClientHello no Chrome sends. Chrome's BoringSSL of October 2026 still orders both lists so. After a GREASE value.
     *
     * Chrome's groups, as its ClientHello of October 2026 lists them after a GREASE value: X25519MLKEM768, X25519,
     * P-256, P-384. BoringSSL gives the first a key share, and X25519, the first that is not post-quantum, another, as
     * Chrome does.
     *
     * Its signature algorithms after a GREASE value, which BoringSSL sends only when told to: ML-DSA-44, -65 and -87,
     * then its eight. Its extensions, all of which BoringSSL sends as Chrome does, Chrome's BoringSSL being the same:
     * brotli certificate compression; ALPS for HTTP/2 under the new codepoint; a GREASE ECH, where its capture went
     * with Cloudflare's ECH key; its 28 trust anchors; 6000 bytes of padding asked of the server; OCSP, signed
     * certificate timestamps and a session ticket; and a new order on each handshake, as Chrome's since its
     * version 110.
     */
    CHROME(
        "chrome",
        "ALL:!aPSK:!ECDSA+SHA1:!3DES",
        true,
        "X25519MLKEM768:X25519:P-256:P-384",
        sigalgs = "mldsa44:mldsa65:mldsa87:$CHROME_EIGHT_SIGALGS",
        greaseSigalgs = true,
        certCompression = "brotli",
        alps = "new",
        trustAnchors = CHROME_TRUST_ANCHORS,
        serverPadding = 6000,
        echGrease = true,
    ),

    /**
     * Chrome 120's, as its ClientHello was captured: Chrome's own rule, which gives the TLS 1.2 suites of Chrome's of
     * today in the same order, after a GREASE value and BoringSSL's TLS 1.3 order. Its groups, after a GREASE value,
     * are X25519, P-256 and P-384, none of them post-quantum, with a key share for X25519, which BoringSSL sends of
     * itself. GREASE as Chrome's, but none among its signature algorithms, which are Chrome's eight without ML-DSA.
     * Brotli certificate compression, ALPS for HTTP/2 under the old codepoint, a GREASE ECH, OCSP, signed certificate
     * timestamps, a session ticket and a new order on each handshake; neither trust anchors nor server padding.
     */
    CHROME_120(
        "chrome-120",
        "ALL:!aPSK:!ECDSA+SHA1:!3DES",
        true,
        "X25519:P-256:P-384",
        sigalgs = CHROME_EIGHT_SIGALGS,
        certCompression = "brotli",
        alps = "old",
        echGrease = true,
    ),

    /**
     * c02b c02f cca9 cca8 c02c c030 c013 c014 009c 009d 002f 0035, without GREASE, after Firefox's TLS 1.3 suites in
     * its own order: 1301 1303 1302.
     *
     * Firefox's groups, as its ClientHello of October 2026 lists them: X25519MLKEM768, X25519, P-256, P-384, P-521,
     * with key shares for the first three, where BoringSSL would send two.
     *
     * Its signature algorithms; zlib, brotli and zstd certificate compression; a GREASE ECH, where its capture went
     * with Cloudflare's ECH key, as Firefox sends one otherwise; OCSP and signed certificate timestamps; no session
     * ticket; and one order of the extensions, BoringSSL's, as Firefox keeps one of its own. Its delegated_credentials
     * and record_size_limit BoringSSL cannot send.
     */
    FIREFOX(
        "firefox",
        "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:" +
            "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:" +
            "AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA:AES256-SHA",
        false,
        "X25519MLKEM768:X25519:P-256:P-384:P-521",
        sigalgs = "ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:rsa_pss_rsae_sha256:" +
            "rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:ecdsa_sha1:" +
            "rsa_pkcs1_sha1",
        tls13Ciphers = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
        keyShares = "X25519MLKEM768:X25519:P-256",
        certCompression = "zlib:brotli:zstd",
        echGrease = true,
        sessionTicket = false,
        permuteExtensions = false,
    ),

    /**
     * c02c c030 c02b c02f cca9 cca8 c00a c014 c023 c027: Python's first ten TLS 1.2 suites, with c00a and c014
     * (AES-256-CBC with SHA-1) in place of c024 and c028 (with SHA-384), the nearest ones BoringSSL has; Python's
     * four DHE suites after them are left out, as BoringSSL has none. Hence semi-python: not Python's list as it
     * is, but every suite of it a core on boring 5.2 or newer takes; an older one refuses c023. Without GREASE,
     * after Python's TLS 1.3 suites in its own order: 1302 1303 1301.
     *
     * Python's groups, as its ClientHello of October 2026 lists them, without X448, ffdhe2048 and ffdhe3072, which
     * BoringSSL does not have: X25519MLKEM768, X25519, P-256, P-384, P-521. Key shares for the first two, as Python
     * sends them.
     *
     * Its signature algorithms that BoringSSL has, in its order: Ed448, the brainpool curves, RSA-PSS with PSS keys,
     * SHA-224 and DSA left out. Neither OCSP nor signed certificate timestamps; a session ticket; one order of the
     * extensions. Its encrypt_then_mac and its three point formats BoringSSL cannot send.
     */
    SEMI_PYTHON(
        "semi-python",
        "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:" +
            "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:" +
            "ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256",
        false,
        "X25519MLKEM768:X25519:P-256:P-384:P-521",
        sigalgs = "mldsa65:mldsa87:mldsa44:ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:" +
            "ed25519:rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:" +
            "rsa_pkcs1_sha512",
        tls13Ciphers = "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256",
        ocspStapling = false,
        sct = false,
        permuteExtensions = false,
    ),

    /**
     * c02b c02f c02c c030 cca9 cca8 c009 c013 c00a c014; Go lists its TLS 1.3 suites after them, which BoringSSL
     * cannot do, in BoringSSL's order among themselves. Without GREASE.
     *
     * Go's groups, as its ClientHello of October 2026 lists them, without SecP256r1MLKEM768 and SecP384r1MLKEM1024,
     * which BoringSSL does not have: X25519MLKEM768, X25519, P-256, P-384, P-521. Key shares for the first two, as
     * Go sends them.
     *
     * Its signature algorithms; OCSP and signed certificate timestamps; no session ticket; one order of the
     * extensions. Its signature_algorithms_cert BoringSSL cannot send.
     */
    GO(
        "go",
        "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:" +
            "ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:" +
            "ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA",
        false,
        "X25519MLKEM768:X25519:P-256:P-384:P-521",
        sigalgs = "mldsa44:mldsa65:mldsa87:rsa_pss_rsae_sha256:ecdsa_secp256r1_sha256:ed25519:rsa_pss_rsae_sha384:" +
            "rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:ecdsa_secp384r1_sha384:" +
            "ecdsa_secp521r1_sha512",
        sessionTicket = false,
        permuteExtensions = false,
    );

    /**
     * The core's options for this fingerprint: its TLS 1.2 suites, its TLS 1.3 order where it has one, its groups, its
     * key shares where it names them, its signature algorithms, the certificate compression, ALPS, trust anchors and
     * server padding it has, and a flag for each of the rest that is not the core's own: GREASE left out, GREASE among
     * the signature algorithms, GREASE ECH, and OCSP, signed certificate timestamps, the session ticket and the new
     * order on each handshake left out.
     */
    val arguments: List<String>
        get() = buildList {
            addAll(listOf("--tls-ciphers", ciphers))
            tls13Ciphers?.let { addAll(listOf("--tls13-ciphers", it)) }
            addAll(listOf("--tls-groups", groups))
            keyShares?.let { addAll(listOf("--tls-key-shares", it)) }
            addAll(listOf("--tls-sigalgs", sigalgs))
            certCompression?.let { addAll(listOf("--tls-cert-compression", it)) }
            alps?.let { addAll(listOf("--tls-alps", it)) }
            trustAnchors?.let { addAll(listOf("--tls-trust-anchors", it)) }
            serverPadding?.let { addAll(listOf("--tls-server-padding", it.toString())) }
            if (!grease) add("--disable-grease")
            if (greaseSigalgs) add("--enable-grease-sigalgs")
            if (echGrease) add("--enable-ech-grease")
            if (!ocspStapling) add("--disable-ocsp-stapling")
            if (!sct) add("--disable-sct")
            if (!sessionTicket) add("--disable-session-ticket")
            if (!permuteExtensions) add("--disable-extension-permutation")
        }

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: CHROME
    }
}

enum class AetherScanMode(val type: String) {
    TURBO("turbo"),
    BALANCED("balanced"),
    THOROUGH("thorough"),
    VERIFIED("verified"),
    IRONCLAD("ironclad");

    companion object {
        /** The name the verified mode had before aether 2.1; links from then still carry it. */
        const val STEALTH = "stealth"

        fun fromString(type: String?) =
            entries.find { it.type == type } ?: if (type == STEALTH) VERIFIED else BALANCED
    }
}

/**
 * The obfuscation profile, named the way the core names it. [AUTO] leaves the choice to
 * the core, which takes firewall for MASQUE and balanced for WireGuard and gool.
 */
enum class AetherObfuscation(val type: String) {
    AUTO("auto"),
    OFF("off"),
    LIGHT("light"),
    FIREWALL("firewall"),
    BALANCED("balanced"),
    GFW("gfw"),
    AGGRESSIVE("aggressive");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/**
 * The IP versions the core scans and connects over. IPv4 unless a profile says
 * otherwise, so a profile connects on an IPv4-only network and a dual-stack one alike.
 */
enum class AetherIpVersion(val type: String) {
    V4("v4"),
    V6("v6"),
    DUAL("both");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: V4
    }
}

/** Where Psiphon stands in the tunnel of a profile, named the way the core names it. */
enum class AetherPsiphon(val type: String) {
    OFF("off"),

    /** The tunnel carries Psiphon: the app dials Psiphon, which leaves through WARP. */
    CHAIN("chain"),

    /** Psiphon carries the tunnel: WARP is reached through Psiphon, and the app dials WARP. */
    REVERSE("reverse"),

    /** No WARP at all: the app dials Psiphon itself. */
    ONLY("only");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: OFF
    }
}

/**
 * The lists of CDN edges built into Psiphon's client, named the way its config names
 * them and in the order the app hands them over, which is the order the scan tries them.
 */
enum class AetherPsiphonCdnSet(val type: String) {
    CLOUDFLARE("cloudflare"),
    FASTLY("fastly"),
    CLOUDFRONT("cloudfront"),
    AKAMAI("psiphon-akamai"),
    BUNNY("psiphon-bunny"),
    VERCEL("vercel"),
    GITHUB("github"),
    CURATED("curated-fronting"),
    LEGACY("legacy-android-overrides");

    companion object {
        /** The sets named in [text], comma or space separated: in the order above, once each. */
        fun parse(text: String?): List<AetherPsiphonCdnSet> {
            val named = text.orEmpty().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.toSet()
            return entries.filter { it.type in named }
        }

        /** [sets] as the core and the profile take them, comma separated; null for none. */
        fun join(sets: Collection<AetherPsiphonCdnSet>): String? =
            entries.filter { it in sets }.joinToString(",") { it.type }.ifEmpty { null }
    }
}

/** How Psiphon reaches its servers. */
enum class AetherPsiphonMode(val type: String) {
    AUTO("auto"),
    CDN("cdn"),
    DIRECT("direct");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/** Where Tor stands in the tunnel of a profile: the same three places as [AetherPsiphon]. */
enum class AetherTor(val type: String) {
    OFF("off"),

    /** The tunnel carries Tor: the app dials Tor, which leaves through WARP. */
    CHAIN("chain"),

    /** Tor carries the tunnel: WARP is reached from a Tor exit, and the app dials WARP. */
    REVERSE("reverse"),

    /** No WARP at all: the app dials Tor itself. */
    ONLY("only");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: OFF
    }
}

/** When Tor turns to bridges. */
enum class AetherTorBridges(val type: String) {
    /** Tor is tried plainly first, and bridges are fetched when that gets nowhere. */
    AUTO("auto"),

    /** Bridges from the start, without trying Tor plainly. */
    FIRST("first"),

    /** Never, however blocked the network looks. */
    NEVER("never"),

    /** The bridge lines of the profile, and no other. */
    OWN("own");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}

/** Where Tor's fetched bridges come from. */
enum class AetherTorRelays(val type: String) {
    /** bridgedb and the relays together, the core's own choice. */
    AUTO("auto"),

    /** The relays alone; bridgedb hands out few bridges, and they are blocked early. */
    ONLY("only"),

    /** bridgedb alone. */
    OFF("off");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: AUTO
    }
}
