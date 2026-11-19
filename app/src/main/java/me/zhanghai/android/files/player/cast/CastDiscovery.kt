/*
 * Copyright (c) 2026 Ordo contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package me.zhanghai.android.files.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Finds cast devices with the system's mDNS (`_googlecast._tcp`). NsdManager runs the multicast in the system's own
 * daemon, so Ordo needs no MulticastLock and no extra permission. [onChange] runs on the main thread.
 */
class CastDiscovery(context: Context, private val onChange: (List<CastDevice>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, CastDevice>()
    // Before Android 14 NsdManager resolves one service at a time; the others wait here.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            handler.post { toResolve += info; resolveNext() }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            handler.post { if (devices.remove(info.serviceName) != null) onChange(devices.values.toList()) }
        }

        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun start() {
        if (running || nsd == null) return
        running = true
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        toResolve.clear()
        runCatching { nsd?.stopServiceDiscovery(discoveryListener) }
    }

    private fun resolveNext() {
        if (resolving || !running) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION") // The replacement, registerServiceInfoCallback, exists only from Android 14.
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handler.post { onResolved(serviceInfo) }
            }
        })
    }

    private fun onResolved(serviceInfo: NsdServiceInfo) {
        resolving = false
        val host = hostOf(serviceInfo)
        if (running && host != null) {
            devices[serviceInfo.serviceName] =
                CastDevice.fromTxt(serviceInfo.serviceName, serviceInfo.attributes, host, serviceInfo.port)
            onChange(devices.values.toList())
        }
        resolveNext()
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else @Suppress("DEPRECATION") listOfNotNull(info.host)
        // The file server listens on IPv4; prefer the device's IPv4 address.
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    companion object {
        private const val SERVICE_TYPE = "_googlecast._tcp"
    }
}
