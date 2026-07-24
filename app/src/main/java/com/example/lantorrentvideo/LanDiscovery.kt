package com.example.lantorrentvideo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class LanDiscovery(context: Context, private val onFound: (String) -> Unit) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var started = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.contains("_lantorrent")) {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        onFound("http://$host:${serviceInfo.port}")
                    }
                })
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        manager.discoverServices("_lantorrent._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        if (!started) return
        runCatching { manager.stopServiceDiscovery(discoveryListener) }
        started = false
    }
}
