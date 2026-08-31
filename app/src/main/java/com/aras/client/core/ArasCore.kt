package com.aras.client.core

// Single access point to the bundled native core bindings.
typealias CoreController = libv2ray.CoreController
typealias CoreCallbackHandler = libv2ray.CoreCallbackHandler
typealias ProcessFinder = libv2ray.ProcessFinder
object ArasCore {
    fun initCoreEnv(assetPath: String, deviceId: String) =
        libv2ray.Libv2ray.initCoreEnv(assetPath, deviceId)
    fun reconcileBrowserDialer(dialerAddr: String?) =
        libv2ray.Libv2ray.reconcileBrowserDialer(dialerAddr)
    fun checkVersionX() = libv2ray.Libv2ray.checkVersionX()
    fun measureOutboundDelay(config: String?, testUrl: String?) =
        libv2ray.Libv2ray.measureOutboundDelay(config, testUrl)
    fun newCoreController(handler: CoreCallbackHandler?) =
        libv2ray.Libv2ray.newCoreController(handler)
    fun fetchQuicCertSha256(request: String) =
        libv2ray.Libv2ray.fetchQuicCertSha256(request)
    fun fetchTlsCertSha256(request: String) =
        libv2ray.Libv2ray.fetchTlsCertSha256(request)
}
