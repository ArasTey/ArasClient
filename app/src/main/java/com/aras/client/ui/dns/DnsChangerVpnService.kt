package com.aras.client.ui.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.aras.client.ui.main.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*

class ProtectedSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val protectAction: (Socket) -> Boolean
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val sock = delegate.createSocket(s, host, port, autoClose)
        protectAction(sock)
        return sock
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val sock = delegate.createSocket(host, port)
        protectAction(sock)
        return sock
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val sock = delegate.createSocket(host, port, localHost, localPort)
        protectAction(sock)
        return sock
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val sock = delegate.createSocket(host, port)
        protectAction(sock)
        return sock
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        val sock = delegate.createSocket(address, port, localAddress, localPort)
        protectAction(sock)
        return sock
    }
}

class DnsChangerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.aras.client.DNS_CHANGER_START"
        const val ACTION_STOP = "com.aras.client.DNS_CHANGER_STOP"
        const val CHANNEL_ID = "dns_vpn_service_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
        var currentStatus = "disconnected"
            private set
        var currentDnsServers = ArrayList<String>()
            private set
        var currentDnsType = "udp"
            private set
        var currentDohUrl = ""
            private set
        var currentDotDomain = ""
            private set
        var currentServerName = ""
            private set
        var notificationTitleText = "DNS Changer"
            private set
        var disconnectActionText = "Disconnect"
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val action = intent.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (action == ACTION_START) {
            val servers = intent.getStringArrayListExtra("servers") ?: arrayListOf("1.1.1.1", "1.0.0.1")
            val dnsType = intent.getStringExtra("dnsType") ?: "udp"
            val dohUrl = intent.getStringExtra("dohUrl") ?: ""
            val dotDomain = intent.getStringExtra("dotDomain") ?: ""
            val serverName = intent.getStringExtra("serverName") ?: ""
            val notificationTitle = intent.getStringExtra("notificationTitle") ?: "DNS Changer"
            val disconnectText = intent.getStringExtra("disconnectText") ?: "Disconnect"
            startVpn(servers, dnsType, dohUrl, dotDomain, serverName, notificationTitle, disconnectText)
        }
        return START_STICKY
    }

    private fun startVpn(servers: ArrayList<String>, dnsType: String, dohUrl: String, dotDomain: String, serverName: String, notificationTitle: String, disconnectText: String) {
        if (isRunning) {
            stopVpn()
        }
        currentDnsServers = servers
        currentDnsType = dnsType
        currentDohUrl = dohUrl
        currentDotDomain = dotDomain
        currentServerName = serverName
        if (notificationTitle.isNotEmpty()) notificationTitleText = notificationTitle
        if (disconnectText.isNotEmpty()) disconnectActionText = disconnectText

        createNotificationChannel()
        val notification = createNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val builder = Builder()
                .setSession("DNSChangerVPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00::2", 128)
                .addDnsServer("10.0.0.2")
                .addDnsServer("fd00::2")
                .addRoute("10.0.0.2", 32)
                .addRoute("fd00::2", 128)
                .allowBypass()

            for (server in servers) {
                try {
                    val addr = InetAddress.getByName(server)
                    if (addr is java.net.Inet4Address) {
                        builder.addDnsServer(server)
                        builder.addRoute(server, 32)
                    } else if (addr is java.net.Inet6Address) {
                        builder.addDnsServer(server)
                        builder.addRoute(server, 128)
                    }
                } catch (e: Exception) {
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                stopVpn()
                return
            }

            isRunning = true
            currentStatus = "connected"
            val displayServerText = if (serverName.isNotEmpty()) serverName else servers.joinToString(", ")
            updateNotification(displayServerText)

            serviceScope.launch {
                runDnsProxy()
            }
        } catch (e: Exception) {
            stopVpn()
        }
    }

    private suspend fun runDnsProxy() {
        val pfd = vpnInterface ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)

        val readBuffer = ByteArray(32767)

        while (isRunning && serviceScope.isActive) {
            try {
                val length = inputStream.read(readBuffer)
                if (length < 28) {
                    delay(2)
                    continue
                }

                val packetCopy = readBuffer.copyOf(length)
                val versionAndIhl = packetCopy[0].toInt() and 0xFF
                val version = versionAndIhl and 0xF0

                if (version == 0x40) {
                    val ihl = (versionAndIhl and 0x0F) * 4
                    if (length < ihl + 8) continue
                    val protocol = packetCopy[9].toInt() and 0xFF
                    if (protocol != 17) continue

                    val srcPort = ((packetCopy[ihl].toInt() and 0xFF) shl 8) or (packetCopy[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packetCopy[ihl + 2].toInt() and 0xFF) shl 8) or (packetCopy[ihl + 3].toInt() and 0xFF)

                    if (dstPort == 53 && currentDnsServers.isNotEmpty()) {
                        val srcIp = packetCopy.copyOfRange(12, 16)
                        val dstIp = packetCopy.copyOfRange(16, 20)
                        val dnsQuery = packetCopy.copyOfRange(ihl + 8, length)

                        serviceScope.launch(Dispatchers.IO) {
                            forwardDnsQueryIp4(
                                dnsQuery,
                                outputStream,
                                srcIp,
                                dstIp,
                                srcPort,
                                dstPort
                            )
                        }
                    }
                } else if (version == 0x60) {
                    if (length < 48) continue
                    val nextHeader = packetCopy[6].toInt() and 0xFF
                    if (nextHeader != 17) continue

                    val srcPort = ((packetCopy[40].toInt() and 0xFF) shl 8) or (packetCopy[41].toInt() and 0xFF)
                    val dstPort = ((packetCopy[42].toInt() and 0xFF) shl 8) or (packetCopy[43].toInt() and 0xFF)

                    if (dstPort == 53 && currentDnsServers.isNotEmpty()) {
                        val srcIp = packetCopy.copyOfRange(8, 24)
                        val dstIp = packetCopy.copyOfRange(24, 40)
                        val dnsQuery = packetCopy.copyOfRange(48, length)

                        serviceScope.launch(Dispatchers.IO) {
                            forwardDnsQueryIp6(
                                dnsQuery,
                                outputStream,
                                srcIp,
                                dstIp,
                                srcPort,
                                dstPort
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun forwardDnsQueryIp4(
        dnsQuery: ByteArray,
        outputStream: FileOutputStream,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ) {
        val dnsResponse = resolveDnsQuery(dnsQuery) ?: return

        val respPacket = buildIp4UdpResponsePacket(
            dstIp,
            srcIp,
            dstPort,
            srcPort,
            dnsResponse
        )
        synchronized(outputStream) {
            try {
                outputStream.write(respPacket)
                outputStream.flush()
            } catch (e: Exception) {
            }
        }
    }

    private fun forwardDnsQueryIp6(
        dnsQuery: ByteArray,
        outputStream: FileOutputStream,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ) {
        val dnsResponse = resolveDnsQuery(dnsQuery) ?: return

        val respPacket = buildIp6UdpResponsePacket(
            dstIp,
            srcIp,
            dstPort,
            srcPort,
            dnsResponse
        )
        synchronized(outputStream) {
            try {
                outputStream.write(respPacket)
                outputStream.flush()
            } catch (e: Exception) {
            }
        }
    }

    private fun resolveDnsQuery(dnsQuery: ByteArray): ByteArray? {
        return if (currentDnsType == "doh" && currentDohUrl.isNotEmpty()) {
            queryDoH(dnsQuery, currentDohUrl)
        } else if (currentDnsType == "dot" && currentDotDomain.isNotEmpty() && currentDnsServers.isNotEmpty()) {
            queryDoT(dnsQuery, currentDnsServers[0], currentDotDomain)
        } else {
            queryUdp(dnsQuery, currentDnsServers)
        }
    }

    private fun queryUdp(dnsQuery: ByteArray, servers: List<String>): ByteArray? {
        for (targetIp in servers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(dnsQuery, dnsQuery.size, address, 53)
                socket.send(packet)

                val recvBuffer = ByteArray(32767)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                socket.soTimeout = 3000
                socket.receive(recvPacket)

                val result = ByteArray(recvPacket.length)
                System.arraycopy(recvPacket.data, 0, result, 0, recvPacket.length)
                return result
            } catch (e: Exception) {
            } finally {
                socket?.close()
            }
        }
        return null
    }

    private fun queryDoH(dnsQuery: ByteArray, dohUrl: String): ByteArray? {
        try {
            val url = URL(dohUrl)
            val connection = url.openConnection() as HttpsURLConnection
            val defaultFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            connection.sslSocketFactory = ProtectedSSLSocketFactory(defaultFactory) { socket ->
                protect(socket)
            }
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.doOutput = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000

            connection.outputStream.use { out ->
                out.write(dnsQuery)
                out.flush()
            }

            if (connection.responseCode == 200) {
                return connection.inputStream.readBytes()
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun queryDoT(dnsQuery: ByteArray, targetIp: String, domain: String): ByteArray? {
        try {
            val factory = SSLSocketFactory.getDefault()
            val rawSocket = factory.createSocket(targetIp, 853)
            protect(rawSocket)
            val socket = rawSocket as javax.net.ssl.SSLSocket
            socket.soTimeout = 4000
            socket.startHandshake()

            val out = socket.outputStream
            val len = dnsQuery.size
            out.write((len shr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(dnsQuery)
            out.flush()

            val inStream = socket.inputStream
            val len1 = inStream.read()
            val len2 = inStream.read()
            if (len1 != -1 && len2 != -1) {
                val respLen = (len1 shl 8) or len2
                val respBuffer = ByteArray(respLen)
                var bytesRead = 0
                while (bytesRead < respLen) {
                    val read = inStream.read(respBuffer, bytesRead, respLen - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                socket.close()
                return respBuffer
            }
            socket.close()
        } catch (e: Exception) {
        }
        return null
    }

    private fun buildIp4UdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val payloadLen = dnsPayload.size
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)

        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen shr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x01.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 17.toByte()
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        var ipSum = 0L
        for (i in 0 until 10) {
            val word = ((packet[i * 2].toInt() and 0xFF) shl 8) or (packet[i * 2 + 1].toInt() and 0xFF)
            ipSum += word
        }
        while (ipSum shr 16 > 0) {
            ipSum = (ipSum and 0xFFFF) + (ipSum shr 16)
        }
        val ipChecksum = (ipSum.inv() and 0xFFFF).toInt()
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()

        System.arraycopy(dnsPayload, 0, packet, 28, payloadLen)
        return packet
    }

    private fun buildIp6UdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val payloadLen = dnsPayload.size
        val udpLen = 8 + payloadLen
        val totalLen = 40 + udpLen
        val packet = ByteArray(totalLen)

        packet[0] = 0x60.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x00.toByte()
        packet[4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[5] = (udpLen and 0xFF).toByte()
        packet[6] = 17.toByte()
        packet[7] = 64.toByte()

        System.arraycopy(srcIp, 0, packet, 8, 16)
        System.arraycopy(dstIp, 0, packet, 24, 16)

        packet[40] = ((srcPort shr 8) and 0xFF).toByte()
        packet[41] = (srcPort and 0xFF).toByte()
        packet[42] = ((dstPort shr 8) and 0xFF).toByte()
        packet[43] = (dstPort and 0xFF).toByte()
        packet[44] = ((udpLen shr 8) and 0xFF).toByte()
        packet[45] = (udpLen and 0xFF).toByte()

        val checksum = calculateIp6UdpChecksum(srcIp, dstIp, srcPort, dstPort, udpLen, dnsPayload)
        packet[46] = ((checksum shr 8) and 0xFF).toByte()
        packet[47] = (checksum and 0xFF).toByte()

        System.arraycopy(dnsPayload, 0, packet, 48, payloadLen)
        return packet
    }

    private fun calculateIp6UdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        udpLen: Int,
        payload: ByteArray
    ): Int {
        var sum = 0L

        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 16 step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }

        sum += udpLen
        sum += 17

        sum += srcPort
        sum += dstPort
        sum += udpLen

        for (i in payload.indices step 2) {
            val b1 = payload[i].toInt() and 0xFF
            val b2 = if (i + 1 < payload.size) payload[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        var checksum = (sum.inv() and 0xFFFF).toInt()
        if (checksum == 0) checksum = 0xFFFF
        return checksum
    }

    private fun stopVpn() {
        isRunning = false
        currentStatus = "disconnected"
        serviceScope.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Changer Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMainIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitleText)
            .setContentText(contentText)
            .setSmallIcon(com.aras.client.R.mipmap.ic_launcher)
            .setContentIntent(pendingMainIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, disconnectActionText, pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(contentText))
    }
}
