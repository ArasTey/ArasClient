package com.aras.client.core.aether

import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * How a session core is read and watched: the words the core's own log lines carry, the
 * addresses its listeners are read by, the ports a start refuses before it launches the
 * process, and the question about the greeting.
 *
 * The lines are ones a real core wrote; the formats it logs in are its own (aether lib.rs).
 */
class AetherCoreManagerTest {

    private fun profile(block: ProfileItem.() -> Unit = {}) =
        ProfileItem(configType = EConfigType.AETHER).apply(block)

    private fun List<String>.valueOf(flag: String): String? =
        indices.filter { this[it] == flag }.firstOrNull()?.plus(1)?.let { getOrNull(it) }

    private fun argumentsOf(profile: ProfileItem, port: Int) =
        AetherCoreManager.buildArguments(profile, port)

    // --------------------------------------------------------------- words read

    @Test
    fun `where the app dials psiphon, readiness needs the core's word`() {
        assertTrue(AetherCoreManager.readyNeedsWord(listOf("--wg", "--psiphon", "--psiphon-bind", "127.0.0.1:10819")))
        assertTrue(AetherCoreManager.readyNeedsWord(listOf("--psiphon-only", "--bind", "127.0.0.1:10819")))
        // Around the tunnel the app dials WARP, whose listener comes up only once the tunnel stands.
        assertFalse(AetherCoreManager.readyNeedsWord(listOf("--masque", "--psiphon-reverse")))
        assertFalse(AetherCoreManager.readyNeedsWord(listOf("--wg", "--tor")))
        assertFalse(AetherCoreManager.readyNeedsWord(listOf("--wg")))
        // Tor alone runs no Psiphon, whatever Psiphon is told; Psiphon alone does say it.
        assertFalse(AetherCoreManager.readyNeedsWord(listOf("--tor-only", "--psiphon")))
        assertTrue(AetherCoreManager.readyNeedsWord(listOf("--psiphon-only", "--tor")))
    }

    @Test
    fun `the ready word and the quiet level go by each other`() {
        assertTrue(AetherCoreManager.isReadyWord("[2026-09-24T10:00:00.000Z INFO  aether] [+] psiphon is ready; 127.0.0.1:10819 leaves through psiphon"))
        assertFalse(AetherCoreManager.isReadyWord("[2026-09-24T10:00:00.000Z INFO  aether] [*] starting psiphon through the tunnel at 127.0.0.1:10820"))
        assertFalse(AetherCoreManager.isReadyWord("[2026-09-24T10:00:00.000Z INFO  aether] [+] psiphon reached a server at 203.0.113.9"))

        assertTrue(AetherCoreManager.showsInfo(listOf("--psiphon")))
        assertTrue(AetherCoreManager.showsInfo(listOf("--psiphon", "--log-level", "debug")))
        assertTrue(AetherCoreManager.showsInfo(listOf("--psiphon", "--verbose")))
        assertFalse(AetherCoreManager.showsInfo(listOf("--psiphon", "--log-level", "warn")))
        assertFalse(AetherCoreManager.showsInfo(listOf("--psiphon", "--log-level", "error")))
    }

    @Test
    fun `a core stopped for want of an ECH key is reported so`() {
        // The core's last line as its main prints the error it ends with (aether lib.rs, session_ech_key).
        val stopped = "Error: Ech(\"ECH is on but there is no ECH key to offer (udp://1.1.1.1:53 did not answer for " +
            "cloudflare-ech.com); stopping rather than send the server name in the clear\")"
        assertTrue(AetherCoreManager.isNoEchKeyWord(stopped))
        assertFalse(AetherCoreManager.isNoEchKeyWord("[2026-10-01T10:00:00.000Z INFO  aether] [+] fetched ECHConfigList automatically (71 bytes)"))
        assertFalse(AetherCoreManager.isNoEchKeyWord("[2026-10-01T10:00:00.000Z INFO  aether] [+] ECH off; the server name goes out in cleartext"))
        assertFalse(AetherCoreManager.isNoEchKeyWord("Error: Api(\"too many registrations\")"))

        assertEquals(R.string.aether_core_stopped_no_ech_key, AetherCoreManager.stoppedMessage(noEchKey = true, chainEnded = false))
        assertEquals(R.string.aether_core_stopped, AetherCoreManager.stoppedMessage(noEchKey = false, chainEnded = false))
        assertEquals(R.string.aether_core_stopped_chain_ended, AetherCoreManager.stoppedMessage(noEchKey = true, chainEnded = true))
    }

    @Test
    fun `the end of Tor or Psiphon inside the tunnel is told from their other lines`() {
        val tor = listOf("--masque", "--tor", "--tor-bind", "127.0.0.1:10819", "--bind", "127.0.0.1:10820")
        val psiphon = listOf("--masque", "--psiphon", "--psiphon-bind", "127.0.0.1:10819", "--bind", "127.0.0.1:10820")
        // The core's lines as it writes them (aether lib.rs, watch_chain): an error's words start with the label of its
        // kind, as AetherError::Other shows it.
        val torEnded = "[2026-10-23T10:00:00.000Z ERROR aether] [-] tor: other: the tor socks5 listener cannot use 127.0.0.1:10819: " +
            "Address already in use (os error 98); another program already listens there, so stop it or choose another port with --tor-bind"
        assertEquals(
            "tor: the tor socks5 listener cannot use 127.0.0.1:10819: Address already in use (os error 98); " +
                "another program already listens there, so stop it or choose another port with --tor-bind",
            AetherCoreManager.chainEndWords(torEnded, tor),
        )
        assertEquals(
            "tor: Connection reset by peer (os error 104)",
            AetherCoreManager.chainEndWords("[2026-10-23T10:00:00.000Z ERROR aether] [-] tor: io: Connection reset by peer (os error 104)", tor),
        )
        // As a device logged it.
        assertEquals(
            "psiphon: psiphon did not come up in time; it may be blocked on this network",
            AetherCoreManager.chainEndWords(
                "[2026-09-24T16:12:36.142Z ERROR aether] [-] psiphon: other: psiphon did not come up in time; it may be blocked on this network",
                psiphon,
            ),
        )
        assertEquals(
            "psiphon: psiphon stopped: exit status: 1",
            AetherCoreManager.chainEndWords("[2026-10-23T10:00:00.000Z ERROR aether] [-] psiphon: other: psiphon stopped: exit status: 1", psiphon),
        )
        // The notices of the Psiphon client come from the core's psiphon module, under the same prefix, at any level.
        assertNull(
            AetherCoreManager.chainEndWords(
                "[2026-10-23T10:00:00.000Z ERROR aether::psiphon] [-] psiphon: error initializing local SOCKS proxy: address already in use",
                psiphon,
            ),
        )
        assertNull(
            AetherCoreManager.chainEndWords(
                "[2026-10-23T10:00:00.000Z ERROR aether::psiphon] [-] psiphon: psiphon.(*Controller).Run#412: tunnel failed",
                psiphon,
            ),
        )
        assertNull(
            AetherCoreManager.chainEndWords(
                "[2026-10-23T10:00:00.000Z WARN  aether::psiphon] [-] psiphon lost its tunnel; it is reconnecting",
                psiphon,
            ),
        )
        // Not at the error level, Tor's word for a session on Psiphon's listener, a session on the core's own listener,
        // and the error the core itself stops on, which ends it anyway.
        assertNull(AetherCoreManager.chainEndWords(torEnded.replace("ERROR", "INFO "), tor))
        assertNull(AetherCoreManager.chainEndWords(torEnded, psiphon))
        assertNull(AetherCoreManager.chainEndWords(torEnded, listOf("--masque", "--bind", "127.0.0.1:10819")))
        assertNull(AetherCoreManager.chainEndWords("Error: Other(\"[-] tor: the tor socks5 listener cannot use 127.0.0.1:10819\")", tor))
    }

    // --------------------------------------------------------------- read the command

    @Test
    fun `the last value named is the one the core keeps`() {
        assertEquals("masque", AetherCoreManager.valueAfter(listOf("--protocol", "wg", "--protocol", "masque"), "--protocol"))
        assertEquals("wg", AetherCoreManager.valueAfter(listOf("--protocol", "wg"), "--protocol"))
        assertNull(AetherCoreManager.valueAfter(listOf("--peers-only"), "--protocol"))

        assertEquals(10820, AetherCoreManager.portAfter(listOf("--bind", "127.0.0.1:10819", "--bind", "127.0.0.1:10820"), "--bind"))
        assertEquals(10819, AetherCoreManager.listenerPortOf(listOf("--bind", "127.0.0.1:10819")))
        assertNull(AetherCoreManager.listenerPortOf(listOf("--wg")))
    }

    @Test
    fun `the carriers' listener addresses are read the way the core reads them`() {
        // Psiphon inside, Tor around: the app dials Psiphon, the tunnel takes the next port, Tor the one after.
        val psiphonInside = argumentsOf(profile { aetherPsiphon = "chain"; aetherTor = "reverse" }, 10819)
        assertEquals("127.0.0.1:10819", psiphonInside.valueOf("--psiphon-bind"))
        assertEquals("127.0.0.1:10820", psiphonInside.valueOf("--bind"))
        assertEquals("127.0.0.1:10821", psiphonInside.valueOf("--tor-bind"))
        assertEquals(10819, AetherCoreManager.listenerPortOf(psiphonInside))
        assertTrue("--psiphon" in psiphonInside)
        assertTrue("--tor-reverse" in psiphonInside)

        // Tor inside, Psiphon around: the app dials Tor, and Psiphon picks its own port as the core reads it.
        val torInside = argumentsOf(profile { aetherPsiphon = "reverse"; aetherTor = "chain" }, 10819)
        assertEquals("127.0.0.1:10819", torInside.valueOf("--tor-bind"))
        assertEquals("127.0.0.1:10820", torInside.valueOf("--bind"))
        assertEquals("127.0.0.1:0", torInside.valueOf("--psiphon-bind"))
        assertEquals(10819, AetherCoreManager.listenerPortOf(torInside))

        // Where the app dials, by the modes the command names.
        assertEquals(AetherCoreManager.PSIPHON_BIND, AetherCoreManager.listenerFlagOf(listOf("--wg", "--psiphon", "--bind", "127.0.0.1:1819")))
        assertEquals("--bind", AetherCoreManager.listenerFlagOf(listOf("--wg")))
        assertEquals("--bind", AetherCoreManager.listenerFlagOf(listOf("--tor-only", "--psiphon")))
        assertEquals("--bind", AetherCoreManager.listenerFlagOf(listOf("--psiphon-only", "--tor")))
        assertEquals("TOR", AetherCoreManager.aloneOf(listOf("--tor-only", "--psiphon-only")))
        assertEquals("PSIPHON", AetherCoreManager.aloneOf(listOf("--psiphon-only", "--tor")))
        assertNull(AetherCoreManager.aloneOf(listOf("--tor", "--psiphon-reverse")))
    }

    @Test
    fun `an address is read as Rust reads a socket address`() {
        // An address is read as Rust reads a socket address: a host name is none, leading zeros and
        // ports beyond 65_535 are refused.
        assertTrue(AetherCoreManager.isSocketAddress("127.0.0.1:10819"))
        assertTrue(AetherCoreManager.isSocketAddress("0.0.0.0:0"))
        assertTrue(AetherCoreManager.isSocketAddress("[::1]:10819"))
        assertTrue(AetherCoreManager.isSocketAddress("[::ffff:0.0.0.0]:0"))
        assertFalse(AetherCoreManager.isSocketAddress("example.com:10819"))
        assertFalse(AetherCoreManager.isSocketAddress("127.0.0.1:-1"))
        assertFalse(AetherCoreManager.isSocketAddress("127.0.0.1:99999"))
        assertFalse(AetherCoreManager.isSocketAddress("[::1]10819"))
        assertFalse(AetherCoreManager.isSocketAddress("::1:10819"))
        assertFalse(AetherCoreManager.isSocketAddress("01.2.3.4:0"))
        assertFalse(AetherCoreManager.isSocketAddress("00.1.2.3:0"))

        // Whitespace as the core trims it: Rust's, not Kotlin's.
        assertTrue(AetherCoreManager.isRustWhitespace('\u0085'))
        assertFalse(AetherCoreManager.isRustWhitespace('\u001F'))
        assertTrue(AetherCoreManager.isRustWhitespace('\u3000'))
        assertEquals(
            "127.0.0.1:1820",
            AetherCoreManager.listenerAddressAfter(listOf("--tor-bind", "127.0.0.1:1820\u3000"), "--tor-bind"),
        )
        // --bind is read as written; the others are trimmed.
        assertEquals("127.0.0.1:1819 ", AetherCoreManager.listenerAddressAfter(listOf("--bind", "127.0.0.1:1819 "), "--bind"))
    }

    @Test
    fun `the loopback addresses a listener answers the app on`() {
        assertTrue(AetherCoreManager.reachesLoopback("127.0.0.1"))
        assertTrue(AetherCoreManager.reachesLoopback("0.0.0.0"))
        assertTrue(AetherCoreManager.reachesLoopback("[::]"))
        assertTrue(AetherCoreManager.reachesLoopback("[::ffff:127.0.0.1]"))
        assertTrue(AetherCoreManager.reachesLoopback("[::ffff:0.0.0.0]"))
        // Another loopback address, IPv6's own one, or a network's: the session would stay connecting.
        assertFalse(AetherCoreManager.reachesLoopback("[::1]"))
        assertFalse(AetherCoreManager.reachesLoopback("127.0.0.2"))
        assertFalse(AetherCoreManager.reachesLoopback("[::2]"))
        assertFalse(AetherCoreManager.reachesLoopback("10.0.0.5"))
        assertFalse(AetherCoreManager.reachesLoopback("[fe80::1]"))
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), AetherCoreManager.ipv6Groups("::1")?.toList())
        assertEquals(listOf(0, 0, 0, 0, 0, 0xffff, 0x7f00, 1), AetherCoreManager.ipv6Groups("::ffff:127.0.0.1")?.toList())
        assertNull(AetherCoreManager.ipv6Groups("fe80::1%eth0"))
    }

    // --------------------------------------------------------------- listener checks

    @Test
    fun `the ports the core's listeners take`() {
        // Its default port stands for a listener the command names nothing about.
        assertEquals(listOf(1819, 1820), AetherCore(listOf("--tor", "--tor-bind", "127.0.0.1:1820")).ports)
        assertEquals(listOf(10819, 10820), AetherCore(listOf("--bind", "127.0.0.1:10819", "--psiphon", "--psiphon-bind", "127.0.0.1:10820")).ports)
        assertEquals(listOf(10819), AetherCore(listOf("--bind", "127.0.0.1:10819")).ports)
        // An HTTP proxy off asks for no port; an ephemeral port, the Psiphon around the tunnel's, is left out.
        assertEquals(listOf(1819), AetherCore(listOf("--bind", "127.0.0.1:1819", "--http-proxy", "off")).ports)
        assertEquals(listOf(1819), AetherCore(listOf("--bind", "127.0.0.1:1819", "--psiphon-reverse", "--psiphon-bind", "127.0.0.1:0")).ports)
        // A listener no option names and no HTTP proxy names takes its port of the core's own defaults.
        assertEquals(listOf(1819), AetherCore(listOf("--http-proxy")).ports)
    }

    @Test
    fun `a start refuses what a hand written command could make the core do`() {
        // An address the core does not take: it would listen on its default instead, and the app would dial it nowhere.
        assertEquals(
            "--bind",
            AetherCore(listOf("--bind", "example.org:1819")).invalidListener,
        )
        // An HTTP proxy that is off takes no address; one whose address is another flag is refused.
        assertNull(AetherCore(listOf("--bind", "127.0.0.1:1819", "--http-proxy", "off")).invalidListener)
        assertEquals("--http-proxy", AetherCore(listOf("--bind", "127.0.0.1:1819", "--http-proxy", "--wg")).invalidListener)

        // An address the app does not dial leaves the session connecting forever.
        assertEquals(
            "--bind",
            AetherCore(listOf("--bind", "127.0.0.2:1819")).unreachableListener,
        )
        assertNull(AetherCore(listOf("--bind", "0.0.0.0:1819")).unreachableListener)
        assertNull(AetherCore(listOf("--bind", "127.0.0.1:1819")).unreachableListener)

        // One port for two listeners: the core would bind one and fail on the other.
        assertEquals(1819, AetherCore(listOf("--bind", "127.0.0.1:1819", "--tor", "--tor-bind", "127.0.0.1:1819")).sharedPort)
        assertNull(AetherCore(listOf("--bind", "127.0.0.1:1819", "--tor-only")).sharedPort)
        // Tor or Psiphon alone holds its own HTTP proxy as it starts: those two may not share a port,
        // and their own listener's HTTP proxy pair it lets go may.
        assertEquals(1819, AetherCore(listOf("--tor-only", "--bind", "127.0.0.1:1819", "--http-proxy", "127.0.0.1:1819")).sharedPort)
        assertNull(AetherCore(listOf("--tor-only", "--bind", "127.0.0.1:1819", "--http-proxy", "127.0.0.1:8118", "--tor-http", "127.0.0.1:8118")).sharedPort)

        // A message for each, in the order the start refuses them.
        assertTrue(AetherCoreManager.problemsOf(AetherCore(listOf("--bind", "example.org:1819"))).orEmpty().contains("address the core does not take"))
        assertTrue(AetherCoreManager.problemsOf(AetherCore(listOf("--bind", "127.0.0.2:1819"))).orEmpty().contains("does not connect to"))
        assertTrue(AetherCoreManager.problemsOf(AetherCore(listOf("--bind", "127.0.0.1:1819", "--tor", "--tor-bind", "127.0.0.1:1819"))).orEmpty().contains("two of the core's listeners"))
    }

    @Test
    fun `a start refuses a port another process holds`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { held ->
            val taken = AetherCore(listOf("--bind", "127.0.0.1:${held.localPort}"))
            assertTrue(AetherCoreManager.problemsOf(taken).orEmpty().contains("in use by another app"))
            assertFalse(AetherCoreManager.isFree(AetherCoreManager.SessionPort(held.localPort)))
            assertEquals(held.localPort, AetherCoreManager.firstTaken(listOf(AetherCoreManager.SessionPort(held.localPort)))?.port)
        }
        // And one bound by a socket that has just been closed is free again.
        val freed = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertTrue(AetherCoreManager.isFree(AetherCoreManager.SessionPort(freed)))
    }

    @Test
    fun `a setting whose value reads as an option never reaches the core`() {
        // A link carrying dns=--upstream would leave the core without the upstream the app gives it.
        assertEquals("1.1.1.1", argumentsOf(profile { aetherDns = " 1.1.1.1 " }, 10819).valueOf("--dns"))
        assertNull(argumentsOf(profile { aetherDns = "--upstream" }, 10819).valueOf("--dns"))
        assertNull(argumentsOf(profile { aetherDns = "--" }, 10819).valueOf("--dns"))
        assertNull(argumentsOf(profile { aetherExitLoc = "-" }, 10819).valueOf("--exit-loc"))
        assertNull(argumentsOf(profile { aetherMasqueSni = "--peer"; aetherProtocol = "masque" }, 10819).valueOf("--masque-sni"))
        assertEquals(
            "proper.example",
            argumentsOf(profile { aetherMasqueSni = " proper.example "; aetherProtocol = "masque" }, 10819).valueOf("--masque-sni"),
        )
    }

    @Test
    fun `the WARP API fields go trimmed and blank leaves the core's own`() {
        // Nothing set: the core's own, api.devices.cloudflare.com, then
        // api.cloudflareclient.com when that one does not serve the call.
        val blank = argumentsOf(profile { aetherProtocol = "masque" }, 10819)
        assertFalse("--api-address" in blank)
        assertFalse("--api-sni" in blank)
        assertFalse("--api-host" in blank)

        // Each one apart, trimmed, on every carrier.
        val given = argumentsOf(
            profile {
                aetherApiAddress = "162.159.137.105"
                aetherApiSni = " api.cloudflareclient.com "
                aetherApiHost = "api.devices.cloudflare.com "
            },
            10819,
        )
        assertEquals("162.159.137.105", given.valueOf("--api-address"))
        assertEquals("api.cloudflareclient.com", given.valueOf("--api-sni"))
        assertEquals("api.devices.cloudflare.com", given.valueOf("--api-host"))

        // A value that reads as an option takes nothing.
        val guarded = argumentsOf(profile { aetherApiHost = "--register" }, 10819)
        assertNull(guarded.valueOf("--api-host"))
        assertFalse("--api-address" in guarded)
    }

    // --------------------------------------------------------------- the greeting

    /** Answers the SOCKS5 greeting and nothing more, as a served listener does. */
    private class SocksGreeter : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) {
                        runCatching {
                            client.use {
                                val input = DataInputStream(it.getInputStream())
                                input.readByte()
                                repeat(input.readUnsignedByte()) { input.readByte() }
                                it.getOutputStream().write(byteArrayOf(5, 0))
                            }
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    @Test
    fun `a listener counts once it answers the socks greeting`() {
        // Bound but not served yet, as Psiphon's listener is before it has a tunnel: the connection is taken, nothing is said.
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { silent ->
            assertFalse(AetherCoreManager.answersSocks(silent.localPort))
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFalse(AetherCoreManager.answersSocks(closedPort))
        SocksGreeter().use { assertTrue(AetherCoreManager.answersSocks(it.port)) }
    }

    @Test
    fun `questions about a port asked together share one answer`() {
        // A listener that says nothing: the question that asks first holds its connection open
        // while it waits, and whoever asks meanwhile takes its answer, so the connections a long
        // warm-up queues are not one per question. The question waits long here, and the others
        // are let go only once the port has accepted the first connection, so "meanwhile" does
        // not rest on three threads being scheduled together on a loaded machine.
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { silent ->
            var accepts = 0
            val lock = Any()
            val accepted = mutableListOf<Socket>()
            val firstConnected = CountDownLatch(1)
            val accepter = thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        silent.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    synchronized(lock) { accepts++ }
                    synchronized(lock) { accepted += client }
                    firstConnected.countDown()
                }
            }
            val port = silent.localPort
            val probe = SocksAnswers(waitMs = 1_500)
            val first = FutureTask { probe.answers(port) }
            thread(isDaemon = true) { first.run() }
            assertTrue("the first question never reached the port", firstConnected.await(5, TimeUnit.SECONDS))
            // Asked while the first question still waits on its connection: the same answer.
            val together = List(2) { FutureTask<Boolean> { probe.answers(port) } }
            together.forEach { task -> thread(isDaemon = true) { task.run() } }
            together.forEach { assertEquals(false, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(false, first.get(10, TimeUnit.SECONDS))
            runCatching { silent.close() }
            accepter.interrupt()
            synchronized(lock) { accepted.forEach { runCatching { it.close() } } }
            assertEquals("questions asked while one waited opened their own connection", 1, accepts)
        }
    }

    @Test
    fun `answers through a connection a question severed`() {
        // A server that takes the greeting and closes it: no answer, and the kept connection is let go.
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { sealer ->
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        sealer.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) {
                        runCatching {
                            client.use { client.getInputStream().read() }
                        }
                    }
                }
            }
            assertFalse(AetherCoreManager.answersSocks(sealer.localPort))
            assertFalse(AetherCoreManager.answersSocks(sealer.localPort))
        }
    }

    @Test
    fun `a connection a probe made to itself is none`() {
        // Where nothing listens on a port of the range the system picks its own ports from, a
        // connect can be given that very port to dial from; the greeting it sends would come
        // back as its answer, so a connection whose two ends are one is read as none.
        val island = java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 10819)
        val street = java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 10820)
        assertTrue(SocksAnswers.isSelfConnect(island, island))
        assertFalse(SocksAnswers.isSelfConnect(island, street))
        assertFalse(SocksAnswers.isSelfConnect(null, street))
    }
}
