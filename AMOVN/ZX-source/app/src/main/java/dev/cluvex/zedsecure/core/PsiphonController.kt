package dev.cluvex.zedsecure.core

import android.content.Context
import android.net.VpnService
import dev.cluvex.zedsecure.core.AppLog as Log
import ca.psiphon.PsiphonTunnel
import java.util.concurrent.atomic.AtomicBoolean

class PsiphonController(
    private val service: VpnService,
    private val configJson: String,
    private val onSocksPort: (Int) -> Unit,
    private val onEstablished: () -> Unit,
    private val onStopped: (String?) -> Unit,
) {
    private val active = AtomicBoolean(false)
    private var tunnel: PsiphonTunnel? = null

    val isRunning: Boolean get() = active.get()

    private val host = object : PsiphonTunnel.HostService {
        override fun getContext(): Context = service
        override fun getPsiphonConfig(): String = configJson

        override fun bindToDevice(fileDescriptor: Long) {
            if (!service.protect(fileDescriptor.toInt())) {
                throw IllegalStateException("VpnService.protect failed for fd $fileDescriptor")
            }
        }

        override fun onDiagnosticMessage(message: String) {
            Log.i(TAG, message)
        }

        override fun onListeningSocksProxyPort(port: Int) {
            Log.i(TAG, "socks proxy on $port")
            onSocksPort(port)
        }

        override fun onSocksProxyPortInUse(port: Int) = fail("socks port $port already in use")
        override fun onConnecting() { Log.i(TAG, "connecting") }
        override fun onConnected() {
            Log.i(TAG, "tunnel established")
            onEstablished()
        }
        override fun onConnectedServerRegion(region: String) { Log.i(TAG, "region $region") }
        override fun onUpstreamProxyError(message: String) = fail("upstream proxy error: $message")
        override fun onInproxyMustUpgrade() = fail("this build cannot run the selected mode")
        override fun onExiting() {
            if (active.compareAndSet(true, false)) onStopped(null)
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (active.get()) stop()
        return try {
            val instance = PsiphonTunnel.newPsiphonTunnel(host)
            tunnel = instance
            instance.setVpnMode(true)
            active.set(true)
            instance.startTunneling("")
            true
        } catch (e: Exception) {
            active.set(false)
            tunnel = null
            Log.e(TAG, "start failed", e)
            onStopped(e.message ?: "psiphon failed to start")
            false
        }
    }

    @Synchronized
    fun stop() {
        val instance = tunnel ?: return
        active.set(false)
        tunnel = null
        runCatching { instance.stop() }
    }

    private fun fail(message: String) {
        if (active.compareAndSet(true, false)) {
            Log.e(TAG, message)
            onStopped(message)
        }
    }

    private companion object {
        const val TAG = "Psiphon"
    }
}
