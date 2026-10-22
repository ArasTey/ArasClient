package com.aras.client.core.dnstunnel

/**
 * How the app points the core at the DNS a tunnel engine serves.
 */
object DnsTunnelDns {

    /**
     * The address of the engine's own DNS, for the core's DNS section.
     *
     * The scheme matters: the core reads a bare "127.0.0.1:10822" as a URL and
     * refuses to start ("first path segment in URL cannot contain colon"), so
     * the plain-DNS-over-UDP form is written out in full.
     */
    fun serverAddress(port: Int): String = "udp://127.0.0.1:$port"
}
