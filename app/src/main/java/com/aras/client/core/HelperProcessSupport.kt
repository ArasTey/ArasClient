package com.aras.client.core

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.core.aether.AetherCore
import com.aras.client.core.aether.AetherCoreManager
import com.aras.client.core.dnstunnel.DnsTunnelEngine
import com.aras.client.core.dnstunnel.DnsTunnelProcessManager
import com.aras.client.core.mieru.MieruProcessManager
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.fmt.AetherFmt
import com.aras.client.fmt.DnsTunnelFmt
import com.aras.client.util.LogUtil

/**
 * Starts the helper processes a config's *resolved chain* depends on.
 *
 * Aether and Mieru are not protocols the core speaks. Each lowers to a SOCKS
 * outbound on loopback and an external process serves that port, so a profile
 * that hops through one of them connects to a dead port unless the daemon is
 * already listening: the latency test returns -1 and the profile never comes up.
 *
 * Both managers hold a single global slot, so they were only ever started when
 * the *selected* profile was itself an Aether or Mieru profile. An entry proxy
 * (the subscription's `prevProfile`) is the outermost hop of every profile in
 * that subscription, and its own type is whatever the provider named — so its
 * daemon was never started and the whole subscription failed at once.
 */
object HelperProcessSupport {

    private val lock = Any()
    private var aetherKey: String? = null
    private var aetherRefs = 0
    private var mieruKey: String? = null
    private var mieruRefs = 0
    private var dnsTunnelKey: String? = null
    private var dnsTunnelRefs = 0

    /**
     * The hops of [chain] that an external process has to serve, in chain order.
     * Empty when the core speaks the whole chain itself.
     */
    fun helperProfilesOf(chain: List<ProfileItem>): List<ProfileItem> =
        chain.filter { it.needsHelperProcess }

    /**
     * Brings up whatever [chain] needs.
     *
     * Returns the profiles this call actually started — pass exactly that back to
     * [release]. A daemon already running for the same profile is reused rather
     * than restarted, and a profile whose daemon is taken by a *different*
     * profile is refused rather than measured through the wrong tunnel.
     *
     * An error message is returned instead of a throw, because every caller
     * reports this to the user in its own words.
     */
    fun acquire(context: Context, chain: List<ProfileItem>): Acquired {
        val helpers = helperProfilesOf(chain)
        if (helpers.isEmpty()) return Acquired(emptyList(), null)

        // Serialized: both managers hold one global slot, so two chains claiming
        // them concurrently would silently take each other's daemon.
        synchronized(lock) {
            for (helper in helpers) {
                when (helper.configType) {
                    EConfigType.AETHER -> {
                        val key = helper.helperKey()
                        when {
                            aetherRefs == 0 -> {
                                val error = startAether(context, helper)
                                if (error != null) return Acquired(emptyList(), error)
                                aetherKey = key
                            }

                            aetherKey != key -> return Acquired(
                                emptyList(),
                                "The aether core is already running for a different profile " +
                                    "(${helper.remarks}); it can only serve one at a time"
                            )
                        }
                        aetherRefs++
                    }

                    EConfigType.MIERU -> {
                        val key = helper.helperKey()
                        when {
                            mieruRefs == 0 -> {
                                val error = startMieru(context, helper)
                                if (error != null) return Acquired(emptyList(), error)
                                mieruKey = key
                            }

                            mieruKey != key -> return Acquired(
                                emptyList(),
                                "The mieru client is already running for a different profile " +
                                    "(${helper.remarks}); it can only serve one at a time"
                            )
                        }
                        mieruRefs++
                    }

                    EConfigType.DNSTUNNEL -> {
                        val key = helper.helperKey()
                        when {
                            dnsTunnelRefs == 0 -> {
                                val error = startDnsTunnel(context, helper)
                                if (error != null) return Acquired(emptyList(), error)
                                dnsTunnelKey = key
                            }

                            dnsTunnelKey != key -> return Acquired(
                                emptyList(),
                                "The DNS tunnel is already running for a different profile " +
                                    "(${helper.remarks}); it can only serve one at a time"
                            )
                        }
                        dnsTunnelRefs++
                    }

                    else -> Unit
                }
            }
        }
        return Acquired(helpers, null)
    }

    /** Stops the daemons [acquire] started, once nothing is using them. */
    fun release(acquired: Acquired) {
        synchronized(lock) {
            acquired.profiles.forEach { helper ->
                when (helper.configType) {
                    EConfigType.AETHER -> {
                        aetherRefs = (aetherRefs - 1).coerceAtLeast(0)
                        if (aetherRefs == 0) {
                            aetherKey = null
                            AetherCoreManager.stop()
                        }
                    }

                    EConfigType.MIERU -> {
                        mieruRefs = (mieruRefs - 1).coerceAtLeast(0)
                        if (mieruRefs == 0) {
                            mieruKey = null
                            MieruProcessManager.stop()
                        }
                    }

                    EConfigType.DNSTUNNEL -> {
                        dnsTunnelRefs = (dnsTunnelRefs - 1).coerceAtLeast(0)
                        if (dnsTunnelRefs == 0) {
                            dnsTunnelKey = null
                            DnsTunnelProcessManager.stop()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Drops every claim and stops both daemons. Used when the session ends, so the
     * refcounts cannot outlive the processes they describe.
     */
    fun reset() {
        synchronized(lock) {
            aetherRefs = 0
            aetherKey = null
            mieruRefs = 0
            mieruKey = null
            dnsTunnelRefs = 0
            dnsTunnelKey = null
            AetherCoreManager.stop()
            MieruProcessManager.stop()
            DnsTunnelProcessManager.stop()
        }
    }

    private fun startAether(context: Context, profile: ProfileItem): String? {
        if (!AetherCoreManager.isAvailable(context)) return "This build does not carry the aether core"
        val core = AetherCore.of(profile)
        LogUtil.i(AppConfig.TAG, "HelperProcess: aether core: ${core.command}")
        val started = AetherCoreManager.start(context, core) { line ->
            LogUtil.d(AppConfig.TAG, "aether | $line")
        }
        if (!started) {
            AetherCoreManager.stop()
            return "The aether core did not start; see the log for its own error"
        }
        return null
    }

    private fun startMieru(context: Context, profile: ProfileItem): String? {
        if (!MieruProcessManager.isAvailable(context)) return "This build does not carry the mieru client"
        val port = AetherFmt.listenPortOf(profile.mieruListenPort)
            ?: AppConfig.PORT_MIERU_SOCKS.toInt()
        LogUtil.i(AppConfig.TAG, "HelperProcess: starting the mieru client on $port")
        if (!MieruProcessManager.start(context, profile, port)) {
            MieruProcessManager.stop()
            return "The mieru client did not start; see the log for its own error"
        }
        return null
    }

    private fun startDnsTunnel(context: Context, profile: ProfileItem): String? {
        val engine = DnsTunnelEngine.of(profile.dnsTunnelEngine)
        if (!DnsTunnelProcessManager.isAvailable(context, engine)) {
            return "This build does not carry the ${engine.displayName} client"
        }
        val socksPort = DnsTunnelFmt.listenPortOf(profile.dnsTunnelPort)
            ?: AppConfig.PORT_DNSTUNNEL_SOCKS.toInt()
        val dnsPort = DnsTunnelFmt.listenPortOf(profile.dnsTunnelDnsPort)
        LogUtil.i(AppConfig.TAG, "HelperProcess: starting the ${engine.displayName} client on $socksPort")
        if (!DnsTunnelProcessManager.start(context, profile, engine, socksPort, dnsPort)) {
            DnsTunnelProcessManager.stop()
            return "The ${engine.displayName} client did not start; see the log for its own error"
        }
        return null
    }

    private val ProfileItem.needsHelperProcess: Boolean
        get() = configType == EConfigType.AETHER ||
            configType == EConfigType.MIERU ||
            configType == EConfigType.DNSTUNNEL

    /** What identifies a running daemon: the profile plus the port it was told to bind. */
    private fun ProfileItem.helperKey(): String {
        val port = when (configType) {
            EConfigType.AETHER -> com.aras.client.core.aether.AetherCoreManager.listenPort(this)
            EConfigType.DNSTUNNEL -> DnsTunnelFmt.listenPortOf(dnsTunnelPort)
                ?: AppConfig.PORT_DNSTUNNEL_SOCKS.toInt()
            else -> AetherFmt.listenPortOf(mieruListenPort) ?: AppConfig.PORT_MIERU_SOCKS.toInt()
        }
        return "${remarks}|$port"
    }

    /** What [acquire] started, and any reason it could not. */
    class Acquired(
        val profiles: List<ProfileItem>,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }
}