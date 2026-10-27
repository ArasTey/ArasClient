package com.aras.client.core.aether

import com.aras.client.AppConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Whether the SOCKS5 server on a loopback port answers its greeting, asked over at
 * most one connection per port at a time.
 *
 * The core binds its listener as it starts and serves it only once its tunnel is up.
 * Until then the kernel queues every connection to it, and nothing answers. A probe
 * that hung up after its timeout and connected anew at its next try left a dead
 * connection in that queue each time: a few a second over a long Psiphon or Tor
 * warm-up, ahead of the clients the tunnel serves first, in a backlog of 128 on older
 * Android kernels. Here a greeting that got no answer yet is kept waiting, and the
 * next question about that port reads on, on the same connection, while it stays
 * unanswered; questions asked at the same time share one answer. Once the kept
 * connection is answered or ended, a new one tells whether the port is served now: an
 * answer that waited there can be older than the server's end.
 *
 * At most one connection per port stays open while nobody asks.
 */
internal class SocksAnswers(
    private val waitMs: Int,
    private val connectTimeoutMs: Int = waitMs,
) {

    /** A question about one port under way; whoever asks about that port meanwhile takes its answer. */
    private class Question {
        val done = CountDownLatch(1)

        @Volatile
        var answered = false
    }

    private enum class Reply { ANSWERED, WAITING, ENDED }

    private val lock = Any()

    /** The questions under way, by port. Guarded by [lock]. */
    private val asking = HashMap<Int, Question>()

    /** The connections whose greeting got no answer yet, by port. Guarded by [lock]. */
    private val waiting = HashMap<Int, Socket>()

    /**
     * True when the SOCKS5 server on the loopback [port] answers the greeting with its version and a method the greeting
     * allows, the version within [waitMs], on the connection an earlier question left waiting where there is one. Blocks
     * the calling thread.
     */
    fun answers(port: Int): Boolean {
        var mine = false
        val question = synchronized(lock) {
            asking[port] ?: Question().also {
                asking[port] = it
                mine = true
            }
        }
        if (!mine) {
            // The question under way answers this one as well, within the longest it can take: two reads, each of which
            // may wait for the version and then for the byte after it, and a connect between them.
            val longest = connectTimeoutMs + 4L * waitMs + SHARED_WAIT_SLACK_MS
            return try {
                question.done.await(longest, TimeUnit.MILLISECONDS) && question.answered
            } catch (_: InterruptedException) {
                // An interrupted wait gives up as a timeout does, and leaves the thread marked as interrupted.
                Thread.currentThread().interrupt()
                false
            }
        }
        try {
            question.answered = ask(port)
        } finally {
            synchronized(lock) { asking.remove(port) }
            question.done.countDown()
        }
        return question.answered
    }

    /**
     * Reads on over the connection left waiting for [port] while it stays unanswered, so that no new one queues behind it;
     * asks over a new connection where there is none, or once the kept one was answered or ended. An answer that waited
     * on the kept one can be older than the end of the server that sent it, so only a new connection tells that the port
     * is served now.
     */
    private fun ask(port: Int): Boolean {
        val kept = synchronized(lock) { waiting.remove(port) }
        if (kept != null && read(kept) == Reply.WAITING) return keep(port, kept)
        val socket = greet(port) ?: return false
        return when (read(socket)) {
            Reply.ANSWERED -> true
            Reply.WAITING -> keep(port, socket)
            Reply.ENDED -> false
        }
    }

    /**
     * A connection to [port] with the greeting sent, null when none could be made. A connection to itself is none: where
     * nothing listens on a port of the range the system picks its own ports from, a connect can be given that very port to
     * dial from, and the greeting it sends would come back as its answer.
     */
    private fun greet(port: Int): Socket? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), connectTimeoutMs)
            if (isSelfConnect(socket.localSocketAddress, socket.remoteSocketAddress)) {
                closeQuietly(socket)
                return null
            }
            socket.getOutputStream().write(GREETING)
            socket
        } catch (_: IOException) {
            closeQuietly(socket)
            null
        }
    }

    /**
     * Waits at most [waitMs] for the answer on [socket], which stays open only while no answer has begun. The whole answer
     * counts, the version and a method the greeting allows: another client's greeting, read on a connection two probes made
     * to each other, begins with the version as well. Once the version came, the byte after it decides, within a wait of its
     * own, and the connection is done with either way: a connection is kept for an answer that has not begun, never for the
     * rest of one.
     */
    private fun read(socket: Socket): Reply {
        val version = try {
            socket.soTimeout = waitMs
            socket.getInputStream().read()
        } catch (_: SocketTimeoutException) {
            return Reply.WAITING
        } catch (_: IOException) {
            -1
        }
        val reply = try {
            if (version == SOCKS_VERSION && socket.getInputStream().read() in ANSWERED_METHODS) Reply.ANSWERED else Reply.ENDED
        } catch (_: IOException) {
            // A wait for the rest of an answer that ran out as well: SocketTimeoutException is an IOException.
            Reply.ENDED
        }
        closeQuietly(socket)
        return reply
    }

    /** Keeps [socket] waiting for its answer, in place of any other for [port]; false, as no answer came yet. */
    private fun keep(port: Int, socket: Socket): Boolean {
        synchronized(lock) { waiting.put(port, socket) }?.let(::closeQuietly)
        return false
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    internal companion object {
        /** The SOCKS5 greeting, no authentication offered, and the version byte a server answers it with. */
        private val GREETING = byteArrayOf(5, 1, 0)
        private const val SOCKS_VERSION = 5

        /** The methods an answer to [GREETING] names: no authentication, or none of those offered is acceptable. */
        private val ANSWERED_METHODS = setOf(0x00, 0xFF)

        /** Beyond the longest a question takes, for the thread that asks it to be scheduled. */
        private const val SHARED_WAIT_SLACK_MS = 500L

        /** True for a connection whose two ends are one address and port: a connection to itself. */
        fun isSelfConnect(local: SocketAddress?, remote: SocketAddress?): Boolean = local != null && local == remote
    }
}
